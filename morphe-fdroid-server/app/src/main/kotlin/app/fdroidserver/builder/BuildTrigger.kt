package app.fdroidserver.builder

import app.fdroidserver.github.GithubScheduler
import app.fdroidserver.patching.PatchScheduler

/** What happened when the admin UI asked for work to be done. */
sealed interface TriggerOutcome {
    /** The work ran here and then, synchronously. */
    data class Ran(val updated: Boolean) : TriggerOutcome

    /** A request file was written; a builder container will pick it up. */
    data class Queued(val requestId: String) : TriggerOutcome

    /** Refused because something is already running. */
    data class Busy(val message: String) : TriggerOutcome
}

/**
 * How the admin UI's "Patch now" / "Check now" buttons get their work done.
 *
 * There are two deployments and they differ only here:
 *
 *  - all-in-one (`RUN_SCHEDULERS=true`, the shipped default): the admin
 *    process owns the schedulers and runs the job itself, exactly as before.
 *  - split (`RUN_SCHEDULERS=false`): the admin process holds no scheduler
 *    loops and must stay small, so it writes a request file and a host-side
 *    systemd `.path` unit starts a builder container to do the work.
 *
 * Keeping that difference behind one interface is what lets the HTTP routes,
 * and the admin UI behind them, stay the same in both.
 *
 * Note what is *not* here: the Patch Library's per-entry "Check source now"
 * stays synchronous in both modes. It downloads one `.mpp` bundle and writes
 * it to disk - no APK decode, no patching, no signing - so it does not carry
 * the memory cost that moving work out of the always-on process is meant to
 * avoid.
 */
interface BuildTrigger {
    suspend fun trigger(
        kind: BuildJobKind,
        targetId: String? = null,
        version: String? = null,
        versionPageUrl: String? = null,
        repo: String? = null,
    ): TriggerOutcome

    /** The current/last builder run, for the UI to display. Null in
     * all-in-one mode, where there is no builder and the existing
     * per-scheduler status endpoints already cover it. */
    fun status(): BuildStatus?
}

/** All-in-one mode: run it now, in this process. */
class InProcessBuildTrigger(
    private val patchScheduler: PatchScheduler,
    private val patchSchedulerTv: PatchScheduler,
    private val githubScheduler: GithubScheduler,
) : BuildTrigger {

    override suspend fun trigger(
        kind: BuildJobKind,
        targetId: String?,
        version: String?,
        versionPageUrl: String?,
        repo: String?,
    ): TriggerOutcome = when (kind) {
        BuildJobKind.PATCH -> TriggerOutcome.Ran(patchScheduler.checkForUpdates())
        BuildJobKind.PATCH_TV -> TriggerOutcome.Ran(patchSchedulerTv.checkForUpdates())
        BuildJobKind.PATCH_TARGET ->
            TriggerOutcome.Ran(patchScheduler.runTargetNow(requireTarget(targetId)))
        BuildJobKind.PATCH_TARGET_TV ->
            TriggerOutcome.Ran(patchSchedulerTv.runTargetNow(requireTarget(targetId)))
        BuildJobKind.PATCH_VERSION ->
            TriggerOutcome.Ran(
                patchScheduler.runSpecificVersion(requireTarget(targetId), version!!, versionPageUrl!!),
            )
        BuildJobKind.PATCH_VERSION_TV ->
            TriggerOutcome.Ran(
                patchSchedulerTv.runSpecificVersion(requireTarget(targetId), version!!, versionPageUrl!!),
            )
        BuildJobKind.GITHUB ->
            // Already asynchronous with its own status endpoint, so this
            // keeps that behaviour rather than blocking the request.
            if (githubScheduler.startCheckNow(repo)) {
                TriggerOutcome.Ran(updated = false)
            } else {
                TriggerOutcome.Busy("A GitHub check is already running")
            }
        else -> TriggerOutcome.Busy("${kind.name} is only available in split (builder) mode")
    }

    override fun status(): BuildStatus? = null

    private fun requireTarget(targetId: String?): String =
        targetId ?: throw IllegalArgumentException("targetId is required")
}

/** Split mode: write a request file and let the builder container do it. */
class QueueingBuildTrigger(private val queue: BuildRequestQueue) : BuildTrigger {

    private val statusStore = BuildStatusStore(queue.statusDir)

    override suspend fun trigger(
        kind: BuildJobKind,
        targetId: String?,
        version: String?,
        versionPageUrl: String?,
        repo: String?,
    ): TriggerOutcome {
        val id = queue.enqueue(
            kind = kind,
            targetId = targetId,
            version = version,
            versionPageUrl = versionPageUrl,
            repo = repo,
            source = "admin-ui",
        )
        return TriggerOutcome.Queued(id)
    }

    override fun status(): BuildStatus = statusStore.read()
}
