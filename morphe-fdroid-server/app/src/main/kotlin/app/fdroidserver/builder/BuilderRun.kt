package app.fdroidserver.builder

import app.fdroidserver.AppComponents
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * The one-shot `builder` role: do the work, report what happened, exit.
 *
 * This is the half of the split that costs memory - downloading APKs,
 * merging split bundles, patching, signing, running `fdroid update`. Running
 * it as its own short-lived container is the whole point: the JVM's peak
 * (and the patch worker child's ~1.7 GiB peak) is returned to the host the
 * moment the process exits, instead of staying resident in a long-running
 * server for the container's lifetime.
 *
 * Idempotent by construction. Every step it runs is something the scheduled
 * loops already ran repeatedly: version matching is checked against the
 * database before anything is downloaded, prepared APKs are reused, and
 * `fdroid update` regenerates the index from whatever APKs are on disk. So a
 * run that is triggered twice, or that dies halfway and is triggered again,
 * converges to the same place.
 *
 * Exit codes, which the systemd units on the host read:
 *   0  the run finished, or there was nothing to do, or another builder
 *      already held the lock (all non-failures)
 *   1  at least one step failed - see `.status/builder.json` and the
 *      container log
 *   2  bad arguments
 */
object BuilderRun {

    private val logger = LoggerFactory.getLogger(BuilderRun::class.java.name)

    private const val STEP_INIT = "init-repos"
    private const val STEP_GITHUB = "github-releases"
    private const val STEP_PATCH_SOURCES = "patch-sources"
    private const val STEP_PATCH_SOURCES_TV = "patch-sources-tv"
    private const val STEP_PATCH = "patch-mobile"
    private const val STEP_PATCH_TV = "patch-tv"
    private const val STEP_ARCHIVE = "prune-archive"
    private const val STEP_CLEANUP = "clean-intermediates"

    fun run(args: List<String>): Int {
        // `builder` and `builder run` mean the same thing; the subcommand is
        // accepted so the host unit can read as `builder run --full`.
        val flags = args.filter { it.startsWith("--") }.toSet()
        val positional = args.filterNot { it.startsWith("--") }
        if (positional.isNotEmpty() && positional != listOf("run")) {
            logger.error("Unknown builder arguments: ${positional.joinToString(" ")} (expected: builder [run] [--full|--requests-only])")
            return 2
        }
        val unknownFlags = flags - setOf("--full", "--requests-only")
        if (unknownFlags.isNotEmpty()) {
            logger.error("Unknown builder flags: ${unknownFlags.joinToString(" ")}")
            return 2
        }
        val requestsOnly = "--requests-only" in flags
        val forceFull = "--full" in flags
        if (requestsOnly && forceFull) {
            logger.error("--full and --requests-only contradict each other")
            return 2
        }

        AppComponents.create().use { components ->
            val queue = components.queue
            val statusStore = components.statusStore

            // Taken before anything is claimed or written. A builder that
            // loses this race does nothing at all - it must not even claim
            // requests, or the holder would finish without doing them.
            val lock = BuildLock.tryAcquire(queue.statusDir)
            if (lock == null) {
                logger.info("Another builder run holds the lock - leaving the work to it")
                return 0
            }

            lock.use {
                val requests = queue.claimAll()
                val kinds = buildKinds(requests, forceFull, requestsOnly)
                if (kinds.isEmpty()) {
                    logger.info("Nothing to do (no pending requests)")
                    queue.releaseClaimed(success = true)
                    return 0
                }

                val runId = requests.firstOrNull()?.id ?: scheduledRunId()
                val recorder = BuildStatusRecorder(statusStore, runId, requests.map { it.id }, kinds.toList())
                recorder.started()
                logger.info("Builder run $runId starting: ${kinds.joinToString(", ") { it.name }}")

                var failed = false
                try {
                    failed = execute(components, requests, kinds, recorder)
                } catch (e: Exception) {
                    logger.error("Builder run $runId failed: $e", e)
                    recorder.finished(BuildState.FAILED, error = e.toString())
                    queue.releaseClaimed(success = false)
                    return 1
                }

                if (failed) {
                    recorder.finished(BuildState.FAILED, error = "one or more steps failed - see steps[]")
                    queue.releaseClaimed(success = false)
                    logger.error("Builder run $runId finished with failures")
                    return 1
                }

                // Only on a clean run: on a failure these files are the
                // evidence, and the already-downloaded APKs make the retry
                // cheaper.
                runCatching {
                    recorder.stepStarted(STEP_CLEANUP)
                    val result = components.housekeeping.cleanIntermediates(components.settings.keepPreparedApks)
                    recorder.stepFinished(
                        STEP_CLEANUP, ok = true,
                        message = "removed ${result.filesDeleted} intermediate(s), freed ${result.bytesFreed / 1_048_576} MiB",
                    )
                }.onFailure {
                    // Housekeeping is not worth failing a good publish over.
                    recorder.stepFinished(STEP_CLEANUP, ok = false, message = it.toString())
                    logger.warn("Housekeeping failed after a successful run: $it")
                }

                recorder.finished(BuildState.SUCCEEDED)
                queue.releaseClaimed(success = true)
                logger.info("Builder run $runId finished")
                return 0
            }
        }
    }

    /** Which steps this run should do. */
    private fun buildKinds(
        requests: List<BuildRequest>,
        forceFull: Boolean,
        requestsOnly: Boolean,
    ): Set<BuildJobKind> {
        val fromRequests = requests.map { it.kind }.toSet()
        return when {
            forceFull -> fromRequests + BuildJobKind.FULL
            requestsOnly -> fromRequests
            // A bare `builder run` with nothing queued is the scheduled
            // nightly case.
            fromRequests.isEmpty() -> setOf(BuildJobKind.FULL)
            else -> fromRequests
        }
    }

    /** Returns true if any step failed. */
    private fun execute(
        components: AppComponents,
        requests: List<BuildRequest>,
        kinds: Set<BuildJobKind>,
        recorder: BuildStatusRecorder,
    ): Boolean = runBlocking {
        var failed = false
        val env = components.settings
        val full = BuildJobKind.FULL in kinds

        // Idempotent, and cheap when the repos already exist. A fresh volume
        // needs it before anything else can publish.
        recorder.stepStarted(STEP_INIT)
        val initOk = listOf(env.repoDir, env.patchedRepoDir, env.patchedTvRepoDir)
            .map { components.fdroidRepoManager.initRepo(it) }
            .all { it }
        recorder.stepFinished(STEP_INIT, ok = initOk, message = if (initOk) null else "see log for which repo failed")
        if (!initOk) return@runBlocking true

        if (!components.appConfig.isInitialized()) {
            logger.info("First-run setup has not been completed in the admin UI - nothing to build yet")
            return@runBlocking false
        }

        if (full || BuildJobKind.GITHUB in kinds) {
            val repo = requests.firstOrNull { it.kind == BuildJobKind.GITHUB }?.repo
            failed = step(recorder, STEP_GITHUB) {
                val result = components.githubScheduler.checkForUpdates(onlyRepo = repo)
                recorder.noteGithub(result.downloaded, repo)
                if (result.indexUpdated) recorder.noteIndexUpdated()
                "downloaded ${result.downloaded} APK(s)" + if (result.indexUpdated) ", index updated" else ""
            } || failed
        }

        if (full || BuildJobKind.PATCH_SOURCE in kinds) {
            failed = step(recorder, STEP_PATCH_SOURCES) {
                if (components.patchSourceScheduler.checkForUpdates()) "new patch bundle imported" else "no change"
            } || failed
        }
        if (full || BuildJobKind.PATCH_SOURCE_TV in kinds) {
            failed = step(recorder, STEP_PATCH_SOURCES_TV) {
                if (components.patchSourceSchedulerTv.checkForUpdates()) "new patch bundle imported" else "no change"
            } || failed
        }

        // Mobile channel.
        val mobileTargets = requests.filter { it.kind == BuildJobKind.PATCH_TARGET }
        val mobileVersions = requests.filter { it.kind == BuildJobKind.PATCH_VERSION }
        if (full || BuildJobKind.PATCH in kinds || mobileTargets.isNotEmpty() || mobileVersions.isNotEmpty()) {
            failed = step(recorder, STEP_PATCH) {
                runPatchChannel(
                    scheduler = components.patchScheduler,
                    sweep = full || BuildJobKind.PATCH in kinds,
                    targets = mobileTargets,
                    versions = mobileVersions,
                    recorder = recorder,
                )
            } || failed
        }

        // TV channel.
        val tvTargets = requests.filter { it.kind == BuildJobKind.PATCH_TARGET_TV }
        val tvVersions = requests.filter { it.kind == BuildJobKind.PATCH_VERSION_TV }
        if (full || BuildJobKind.PATCH_TV in kinds || tvTargets.isNotEmpty() || tvVersions.isNotEmpty()) {
            failed = step(recorder, STEP_PATCH_TV) {
                runPatchChannel(
                    scheduler = components.patchSchedulerTv,
                    sweep = full || BuildJobKind.PATCH_TV in kinds,
                    targets = tvTargets,
                    versions = tvVersions,
                    recorder = recorder,
                )
            } || failed
        }

        // Archive bounding, every run. Done after publishing so the versions
        // `archive_older` has just rotated out of repo/ are included, and
        // followed by an index refresh for any repo it actually changed -
        // otherwise the archive index would still advertise APKs that are no
        // longer on disk.
        failed = step(recorder, STEP_ARCHIVE) {
            if (env.archiveKeep <= 0) {
                "disabled (ARCHIVE_KEEP=${env.archiveKeep})"
            } else {
                var totalRemoved = 0
                for (repo in listOf(env.repoDir, env.patchedRepoDir, env.patchedTvRepoDir)) {
                    val removed = components.fdroidRepoManager.pruneArchive(repo, env.archiveKeep)
                    totalRemoved += removed
                    if (removed > 0) {
                        if (components.fdroidRepoManager.updateIndex(repo)) recorder.noteIndexUpdated()
                    }
                }
                "removed $totalRemoved archived APK(s), keeping newest ${env.archiveKeep} per app"
            }
        } || failed

        failed
    }

    /**
     * Runs the per-target and per-version requests for one channel, then the
     * full sweep if this run includes one. Specific requests go first: they
     * are what a person is sitting in front of the admin UI waiting for.
     */
    private suspend fun runPatchChannel(
        scheduler: app.fdroidserver.patching.PatchScheduler,
        sweep: Boolean,
        targets: List<BuildRequest>,
        versions: List<BuildRequest>,
        recorder: BuildStatusRecorder,
    ): String {
        var patched = 0
        val notes = mutableListOf<String>()

        for (request in versions) {
            val targetId = request.targetId
            val version = request.version
            val url = request.versionPageUrl
            if (targetId == null || version == null || url == null) {
                notes += "skipped malformed patch-version request ${request.id}"
                continue
            }
            if (scheduler.runSpecificVersion(targetId, version, url)) patched++
        }
        for (request in targets) {
            val targetId = request.targetId
            if (targetId == null) {
                notes += "skipped malformed patch-target request ${request.id}"
                continue
            }
            if (scheduler.runTargetNow(targetId)) patched++
        }
        if (sweep && scheduler.checkForUpdates()) patched++

        recorder.notePatched(patched)
        if (patched > 0) recorder.noteIndexUpdated()
        notes += if (patched > 0) "$patched publish(es)" else "no change"
        return notes.joinToString("; ")
    }

    /**
     * Runs one step, recording its outcome. Returns true if it failed.
     *
     * A failing step does not abort the run: a GitHub outage must not stop
     * the patch sweep, and neither must stop the archive from being bounded.
     * The run as a whole is then reported as failed so the host's systemd
     * unit (and the admin UI) see it.
     */
    private inline fun step(recorder: BuildStatusRecorder, name: String, body: () -> String): Boolean {
        recorder.stepStarted(name)
        return try {
            val message = body()
            recorder.stepFinished(name, ok = true, message = message)
            logger.info("Builder step $name: $message")
            false
        } catch (e: Exception) {
            recorder.stepFinished(name, ok = false, message = e.toString())
            logger.error("Builder step $name failed: $e", e)
            true
        }
    }

    /** Run id for a run that no request asked for (the scheduled case). */
    private fun scheduledRunId(): String =
        "scheduled-" + Instant.now().toString().replace(':', '-')
}
