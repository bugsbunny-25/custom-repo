package app.fdroidserver

import app.fdroidserver.admin.AdminServer
import app.fdroidserver.builder.BuildTrigger
import app.fdroidserver.builder.BuilderRun
import app.fdroidserver.builder.InProcessBuildTrigger
import app.fdroidserver.builder.QueueingBuildTrigger
import app.fdroidserver.config.AppConfig
import app.fdroidserver.fdroidrepo.FdroidRepoManager
import app.fdroidserver.patching.PatchWorkerEntryPoint
import app.fdroidserver.patching.PatchWorkerLauncher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

/**
 * Entry point. One jar, three roles:
 *
 *   server            the always-on admin UI + JSON API (nginx sits in front
 *                     of it and serves the repo itself). This is the default.
 *   builder [run]     a one-shot run of the actual work - GitHub checks,
 *                     patching, `fdroid update`, pruning - which then exits.
 *   --patch-worker    the per-patch child JVM, spawned by
 *                     PatchWorkerLauncher, never invoked by hand.
 *
 * Whether `server` also runs the scheduler loops is controlled by
 * RUN_SCHEDULERS (see [AppComponents.Env]), which defaults to true - the
 * all-in-one behaviour this image has always had. Setting it to false is what
 * splits serving from building; the admin UI's buttons then write request
 * files instead of patching in-process.
 *
 * Config storage: everything (settings, GitHub repos, patch library/targets,
 * checked-release history) lives in a single SQLite database via
 * [AppConfig]/[app.fdroidserver.config.AppDatabase], not `config.yml`.
 * `DB_PATH` is a *directory* (defaults to `/app/db`); the database file
 * `app.db` is created inside it on first run.
 */
fun main(args: Array<String>) {
    // Dispatched to by PatchWorkerLauncher, which re-invokes this same jar as
    // `java -jar app.jar --patch-worker <requestFile> <responseFile>` in a
    // fresh, short-lived JVM - keeps the memory-spiky APK patch/sign pipeline
    // out of the long-running process, so a patch run's peak usage is
    // reclaimed by the OS the moment the job ends. Checked before anything
    // else so the worker starts no server and reads no role config.
    if (args.size == 3 && args[0] == PatchWorkerLauncher.PATCH_WORKER_FLAG) {
        exitProcess(PatchWorkerEntryPoint.run(args[1], args[2]))
    }

    // The vendored engine and morphe-patcher log through java.util.logging;
    // route those records into logback so they obey LOG_LEVEL and look like
    // everything else.
    LoggingBridge.install()
    val logger = LoggerFactory.getLogger("Main")

    when (val role = args.firstOrNull() ?: "server") {
        "server" -> runServer()
        "builder" -> exitProcess(BuilderRun.run(args.drop(1)))
        else -> {
            logger.error("Unknown role '$role' - expected 'server' or 'builder'")
            exitProcess(2)
        }
    }
}

private fun runServer() {
    val logger = LoggerFactory.getLogger("Main")

    AppComponents.create().use { components ->
        val env = components.settings
        val fdroidRepoManager = components.fdroidRepoManager
        val appConfig = components.appConfig

        val buildTrigger: BuildTrigger = if (env.runSchedulers) {
            InProcessBuildTrigger(components.patchScheduler, components.patchSchedulerTv, components.githubScheduler)
        } else {
            QueueingBuildTrigger(components.queue)
        }

        val adminServer = AdminServer(
            appConfig, components.patchLibrary, env.patchesDir, fdroidRepoManager, env.repoDir,
            env.patchedRepoDir, components.patchScheduler, components.patchSourceScheduler,
            env.patchesTvDir, env.patchedTvRepoDir, components.patchSchedulerTv,
            components.patchSourceSchedulerTv, components.githubScheduler, buildTrigger,
        )
        adminServer.start()
        logger.info("Admin server started on :5001")
        logger.info(
            if (env.runSchedulers) {
                "RUN_SCHEDULERS is on - this process also runs the GitHub/patch loops (all-in-one mode)"
            } else {
                "RUN_SCHEDULERS is off - patching happens in the builder container; UI actions queue request files in ${env.repoDir}/.requests"
            },
        )

        runBlocking {
            val rootJob = coroutineContext[Job]!!

            // Run off the main/admin-server thread and with a bounded timeout
            // (see FdroidRepoManager.runFdroid) so a stalled `fdroid init`
            // (e.g. waiting on entropy for keystore generation) can never
            // prevent the admin server above from starting or from serving
            // requests.
            val initRepoJob = launch {
                listOf(env.repoDir, env.patchedRepoDir, env.patchedTvRepoDir).forEach { dir ->
                    if (!fdroidRepoManager.initRepo(dir)) {
                        logger.error("Repository at $dir is not usable - the admin UI will still load, but publishing will fail")
                    }
                }
            }

            // SIGTERM otherwise just kills the process mid-`join()` - stop
            // accepting admin requests and cancel the loops so the JVM can
            // exit on its own instead of being force-killed after the
            // runtime's grace period.
            Runtime.getRuntime().addShutdownHook(
                Thread {
                    logger.info("Shutdown signal received - stopping admin server and scheduler loops")
                    adminServer.stop()
                    rootJob.cancel()
                    runBlocking { rootJob.join() }
                },
            )

            try {
                // Wait for repo init (bounded by FdroidRepoManager's own
                // timeout, so this can't hang) before reading repoDir's
                // config.yml below - the admin server is already serving
                // requests regardless.
                initRepoJob.join()

                importExistingRepoIdentity(components, logger)

                if (!env.runSchedulers) {
                    // Split mode: nothing periodic to run here. Park until
                    // shutdown; the admin server keeps serving on its own
                    // threads.
                    logger.info("Serving only - waiting for shutdown")
                    rootJob.join()
                    return@runBlocking
                }

                // The scheduler loops stay dormant - and the GitHub/Patching
                // tabs stay empty - until first-run setup
                // (repo_name/repo_description/repo_url) is completed via the
                // admin UI / POST /api/setup/init (or imported above). The
                // admin server itself runs unconditionally above so that
                // setup form is reachable.
                while (!appConfig.isInitialized()) {
                    delay(2.seconds)
                }
                logger.info("Setup complete - starting scheduler loops")

                val settings = appConfig.getSettings()
                val githubInterval = settings.updateInterval.seconds
                val patchInterval = (settings.patchCheckInterval ?: settings.updateInterval).seconds

                val githubJob = launch {
                    while (true) {
                        runCatching { components.githubScheduler.checkForUpdates() }
                            .onFailure { logger.error("GitHub scheduler error: $it") }
                        delay(githubInterval)
                    }
                }
                val patchJob = launch {
                    while (true) {
                        // Checked right before patchScheduler so a freshly
                        // auto-imported .mpp is used the same cycle it's
                        // downloaded in, not the next one.
                        runCatching { components.patchSourceScheduler.checkForUpdates() }
                            .onFailure { logger.error("Patch source scheduler error: $it") }
                        runCatching { components.patchScheduler.checkForUpdates() }
                            .onFailure { logger.error("Patch scheduler error: $it") }
                        runCatching { housekeepAfterLoop(components) }
                            .onFailure { logger.error("Housekeeping error: $it") }
                        delay(patchInterval)
                    }
                }
                val patchTvJob = launch {
                    while (true) {
                        runCatching { components.patchSourceSchedulerTv.checkForUpdates() }
                            .onFailure { logger.error("Patch source scheduler (TV) error: $it") }
                        runCatching { components.patchSchedulerTv.checkForUpdates() }
                            .onFailure { logger.error("Patch TV scheduler error: $it") }
                        delay(patchInterval)
                    }
                }

                githubJob.join()
                patchJob.join()
                patchTvJob.join()
            } catch (e: CancellationException) {
                logger.info("Shutdown complete")
            }
        }
    }
}

/**
 * Bounds the archive and clears intermediates in all-in-one mode.
 *
 * In split mode the builder does this at the end of every run. All-in-one
 * has no "end of run", so it is attached to the patch loop - the point just
 * after anything new has been published, which is when `archive_older` will
 * have rotated versions into `archive/` and when the scratch files are no
 * longer needed.
 */
private suspend fun housekeepAfterLoop(components: AppComponents) {
    val env = components.settings
    if (env.archiveKeep > 0) {
        for (repo in listOf(env.repoDir, env.patchedRepoDir, env.patchedTvRepoDir)) {
            if (components.fdroidRepoManager.pruneArchive(repo, env.archiveKeep) > 0) {
                components.fdroidRepoManager.updateIndex(repo)
            }
        }
    }
    components.housekeeping.cleanIntermediates(env.keepPreparedApks)
}

/**
 * If the DB hasn't been set up yet but the repo's own config.yml already has
 * repo_name/repo_description/repo_url filled in (e.g. a pre-existing
 * fdroid-data volume from before the SQLite-backed setup flow, or one
 * hand-edited outside the admin UI), import those values straight into the
 * database instead of making the user retype values already on disk.
 */
private suspend fun importExistingRepoIdentity(components: AppComponents, logger: org.slf4j.Logger) {
    val appConfig = components.appConfig
    if (appConfig.isInitialized()) return
    val env = components.settings
    val fdroidRepoManager = components.fdroidRepoManager

    val existing = fdroidRepoManager.readRepoMetadata(env.repoDir) ?: return
    logger.info(
        "Found existing repo identity in ${File(env.repoDir, "config.yml")} - importing it instead of prompting for first-run setup",
    )
    // existing.url is repoDir's own config.yml value, which (pre this
    // base-URL split) is the full "/repo"-suffixed URL - strip it back to the
    // base so it round-trips through AppConfig.SetupPayload the same as a
    // fresh setup would.
    val baseUrl = existing.url.removeSuffix("/repo").removeSuffix("/")
    val result = appConfig.completeSetup(
        AppConfig.SetupPayload(existing.name, existing.description, baseUrl, existing.icon),
    )
    if (result !is AppConfig.Result.Ok) return

    fdroidRepoManager.writeRepoMetadata(
        env.repoDir, existing.name, existing.description,
        FdroidRepoManager.mainRepoUrl(baseUrl), existing.icon,
    )
    fdroidRepoManager.writeRepoMetadata(
        env.patchedRepoDir, existing.name, existing.description,
        FdroidRepoManager.patchedRepoUrl(baseUrl), existing.icon,
    )
    fdroidRepoManager.writeRepoMetadata(
        env.patchedTvRepoDir, existing.name, existing.description,
        FdroidRepoManager.patchedTvRepoUrl(baseUrl), existing.icon,
    )
}
