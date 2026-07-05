package app.fdroidserver

import app.fdroidserver.admin.AdminServer
import app.fdroidserver.apkmirror.ApkMirrorClient
import app.fdroidserver.config.AppConfig
import app.fdroidserver.config.AppDatabase
import app.fdroidserver.fdroidrepo.FdroidRepoManager
import app.fdroidserver.github.GitHubReleaseChecker
import app.fdroidserver.github.GithubScheduler
import app.fdroidserver.patching.BundleMerger
import app.fdroidserver.patching.PatchApplier
import app.fdroidserver.patching.PatchLibrary
import app.fdroidserver.patching.PatchScheduler
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * Entry point - replaces the three Python supervisord-managed processes
 * (`config_ui.py`, `update_checker.py`, `patch_checker.py`) with one JVM
 * process running the admin server plus both scheduler loops as coroutines.
 *
 * Config storage: everything (settings, GitHub repos, patch library/targets,
 * checked-release history) now lives in a single SQLite database via
 * [AppDatabase]/[AppConfig], not `config.yml`/`cache.json` files. `DB_PATH`
 * is a *directory* (defaults to `/app/db` for production container images;
 * override to something like `./data` for local dev runs so you don't need
 * the container's `/app` layout) - [AppDatabase] creates `app.db` inside it
 * on first run if it doesn't exist yet.
 */
fun main() {
    val logger = LoggerFactory.getLogger("Main")

    val dbDir = File(System.getenv("DB_PATH") ?: "/app/db")
    val repoDir = File(System.getenv("REPO_PATH") ?: "/srv/fdroid")
    val patchedRepoDir = File(repoDir.absolutePath + "/patched")
    val tmpDir = File(repoDir.absolutePath + "/tmp")
    val defaultPatchesDir = File(repoDir.absolutePath + "/patches")

    tmpDir.mkdirs()

    val database = AppDatabase(dbDir)
    val appConfig = AppConfig(database)

    val fdroidRepoManager = FdroidRepoManager()

    val patchLibrary = PatchLibrary()

    val patchesDir = defaultPatchesDir
    patchesDir.mkdirs()

    val apkMirrorClient = ApkMirrorClient()
    val bundleMerger = BundleMerger()
    val patchApplier = PatchApplier()
    val signing = PatchApplier.SigningConfig(keystoreFile = File(repoDir, "patched-keystore.jks"))
    val patchScheduler = PatchScheduler(
        appConfig, apkMirrorClient, patchLibrary, bundleMerger,
        patchApplier, patchesDir, patchedRepoDir, tmpDir, fdroidRepoManager, signing,
    )

    val adminServer = AdminServer(appConfig, patchLibrary, patchesDir, fdroidRepoManager, repoDir, patchedRepoDir, patchScheduler)
    adminServer.start()
    logger.info("Admin server started on :5001")

    runBlocking {
        val rootJob = coroutineContext[Job]!!

        // Run off the main/admin-server thread and with a bounded timeout
        // (see FdroidRepoManager.runFdroid) so a stalled `fdroid init` (e.g.
        // waiting on entropy for keystore generation) can never prevent the
        // admin server above from starting or from serving requests.
        val initRepoJob = launch {
            fdroidRepoManager.initRepo(repoDir)
            fdroidRepoManager.initRepo(patchedRepoDir)
        }

        // `docker stop`/SIGTERM otherwise just kills the process mid-`join()`
        // - stop accepting admin requests and cancel the scheduler loops so
        // the JVM can exit on its own instead of being force-killed after
        // Docker's grace period.
        Runtime.getRuntime().addShutdownHook(
            Thread {
                logger.info("Shutdown signal received - stopping admin server and scheduler loops")
                adminServer.stop()
                rootJob.cancel()
                runBlocking { rootJob.join() }
            },
        )

        try {
            // Wait for repo init (bounded by FdroidRepoManager's own timeout,
            // so this can't hang) before reading repoDir's config.yml below -
            // the admin server is already serving requests regardless.
            initRepoJob.join()

            // If the DB hasn't been set up yet but repoDir's own config.yml
            // already has repo_name/repo_description/repo_url filled in (e.g. a
            // pre-existing fdroid-data volume from before the SQLite-backed
            // setup flow, or one hand-edited outside the admin UI), import those
            // values straight into the database instead of making the user
            // retype values that are already sitting on disk.
            if (!appConfig.isInitialized()) {
                fdroidRepoManager.readRepoMetadata(repoDir)?.let { existing ->
                    logger.info("Found existing repo identity in ${File(repoDir, "config.yml")} - importing it instead of prompting for first-run setup")
                    // existing.url is repoDir's own config.yml value, which (pre
                    // this base-URL split) is the full "/repo"-suffixed URL -
                    // strip it back to the base so it round-trips through
                    // AppConfig.SetupPayload the same as a fresh setup would.
                    val baseUrl = existing.url.removeSuffix("/repo").removeSuffix("/")
                    val result = appConfig.completeSetup(
                        AppConfig.SetupPayload(existing.name, existing.description, baseUrl, existing.icon),
                    )
                    if (result is AppConfig.Result.Ok) {
                        fdroidRepoManager.writeRepoMetadata(
                            repoDir, existing.name, existing.description,
                            FdroidRepoManager.mainRepoUrl(baseUrl), existing.icon,
                        )
                        fdroidRepoManager.writeRepoMetadata(
                            patchedRepoDir, existing.name, existing.description,
                            FdroidRepoManager.patchedRepoUrl(baseUrl), existing.icon,
                        )
                    }
                }
            }

            // The scheduler loops (GitHub release checks, APKMirror patch
            // checks) stay dormant - and the GitHub/Patching tabs stay empty -
            // until first-run setup (repo_name/repo_description/repo_url) is
            // completed via the admin UI / POST /api/setup/init (or imported
            // above). The admin server itself runs unconditionally above so
            // that setup form is reachable.
            while (!appConfig.isInitialized()) {
                delay(2.seconds)
            }
            logger.info("Setup complete - starting scheduler loops")

            val settings = appConfig.getSettings()
            val githubHttpClient = HttpClient(CIO)
            try {
                val releaseChecker = GitHubReleaseChecker(githubHttpClient, settings.githubToken.ifBlank { null })
                val githubScheduler = GithubScheduler(appConfig, releaseChecker, repoDir, fdroidRepoManager)

                val githubInterval = settings.updateInterval.seconds
                val patchInterval = (settings.patchCheckInterval ?: settings.updateInterval).seconds

                val githubJob = launch {
                    while (true) {
                        runCatching { githubScheduler.checkForUpdates() }
                            .onFailure { logger.error("GitHub scheduler error: $it") }
                        delay(githubInterval)
                    }
                }
                val patchJob = launch {
                    while (true) {
                        runCatching { patchScheduler.checkForUpdates() }
                            .onFailure { logger.error("Patch scheduler error: $it") }
                        delay(patchInterval)
                    }
                }

                githubJob.join()
                patchJob.join()
            } finally {
                githubHttpClient.close()
            }
        } catch (e: CancellationException) {
            logger.info("Shutdown complete")
        }
    }
}
