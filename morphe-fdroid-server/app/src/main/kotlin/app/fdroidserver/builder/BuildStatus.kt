package app.fdroidserver.builder

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant

@Serializable
enum class BuildState {
    IDLE,
    RUNNING,
    SUCCEEDED,
    FAILED,

    /** A run was asked for but the builder found another one already
     * holding the lock, so it did nothing. Not an error. */
    SKIPPED_LOCKED,
}

@Serializable
data class BuildStepStatus(
    val name: String,
    /** "running" | "ok" | "failed" | "skipped" */
    val state: String,
    val message: String? = null,
    val startedAt: String? = null,
    val finishedAt: String? = null,
)

/**
 * The builder's single status document, at `$REPO_PATH/.status/builder.json`.
 *
 * Written only by the builder, read by the admin server (so the UI can show
 * what the last/current run is doing) and by anyone on the host who wants to
 * know whether the nightly run worked. The schema is documented in
 * docs/integration-spec.md.
 *
 * The GitHub-specific counters exist so the admin UI's existing
 * `/api/repos/check-status` polling keeps working unchanged when the GitHub
 * check has moved out of the admin process and into the builder.
 */
@Serializable
data class BuildStatus(
    val state: BuildState = BuildState.IDLE,
    val runId: String? = null,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val currentStep: String? = null,
    val steps: List<BuildStepStatus> = emptyList(),
    val error: String? = null,
    /** Ids of the request files this run claimed. */
    val requestIds: List<String> = emptyList(),
    val kinds: List<BuildJobKind> = emptyList(),
    /** True if any step published a new or changed index. */
    val indexUpdated: Boolean = false,
    val githubDownloaded: Int = 0,
    val githubRepo: String? = null,
    /** Set when the run patched at least one APK. */
    val patchedCount: Int = 0,
)

/**
 * Reads and writes [BuildStatus] as a single JSON file.
 *
 * Writes go to a sibling temp file and are then moved into place with
 * ATOMIC_MOVE: the admin server polls this file every few seconds while a
 * run is in progress, and a reader must never see a half-written document.
 * Reads tolerate a missing or corrupt file by returning [BuildStatus] in its
 * default (idle) state, because a status file is a progress report, not
 * durable state - losing it must not break the UI.
 */
class BuildStatusStore(private val statusDir: File) {

    private val logger = LoggerFactory.getLogger(BuildStatusStore::class.java.name)
    private val file = File(statusDir, FILE_NAME)

    fun read(): BuildStatus {
        if (!file.exists()) return BuildStatus()
        return try {
            json.decodeFromString<BuildStatus>(file.readText())
        } catch (e: Exception) {
            logger.warn("Unreadable builder status at $file ($e) - reporting idle")
            BuildStatus()
        }
    }

    fun write(status: BuildStatus) {
        try {
            statusDir.mkdirs()
            val tmp = File.createTempFile("builder-status", ".json", statusDir)
            tmp.writeText(json.encodeToString(status))
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            // A run that cannot report its status should still finish its
            // actual work, so this is logged rather than thrown.
            logger.error("Failed to write builder status to $file: $e")
        }
    }

    companion object {
        const val FILE_NAME = "builder.json"
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }
    }
}

/** Convenience for building up a run's status as it progresses. */
class BuildStatusRecorder(
    private val store: BuildStatusStore,
    private val runId: String,
    private val requestIds: List<String>,
    private val kinds: List<BuildJobKind>,
) {
    private val startedAt = Instant.now().toString()
    private val steps = mutableListOf<BuildStepStatus>()
    private var indexUpdated = false
    private var githubDownloaded = 0
    private var githubRepo: String? = null
    private var patchedCount = 0

    fun started() = flush(BuildState.RUNNING, currentStep = null)

    fun stepStarted(name: String) {
        steps += BuildStepStatus(name, "running", startedAt = Instant.now().toString())
        flush(BuildState.RUNNING, currentStep = name)
    }

    fun stepFinished(name: String, ok: Boolean, message: String? = null) {
        val idx = steps.indexOfLast { it.name == name }
        val finished = Instant.now().toString()
        val updated = BuildStepStatus(
            name = name,
            state = if (ok) "ok" else "failed",
            message = message,
            startedAt = steps.getOrNull(idx)?.startedAt,
            finishedAt = finished,
        )
        if (idx >= 0) steps[idx] = updated else steps += updated
        flush(BuildState.RUNNING, currentStep = null)
    }

    fun noteIndexUpdated() { indexUpdated = true }
    fun noteGithub(downloaded: Int, repo: String?) {
        githubDownloaded += downloaded
        if (repo != null) githubRepo = repo
    }
    fun notePatched(count: Int) { patchedCount += count }

    fun finished(state: BuildState, error: String? = null) =
        flush(state, currentStep = null, error = error, finishedAt = Instant.now().toString())

    private fun flush(
        state: BuildState,
        currentStep: String?,
        error: String? = null,
        finishedAt: String? = null,
    ) {
        store.write(
            BuildStatus(
                state = state,
                runId = runId,
                startedAt = startedAt,
                finishedAt = finishedAt,
                currentStep = currentStep,
                steps = steps.toList(),
                error = error,
                requestIds = requestIds,
                kinds = kinds,
                indexUpdated = indexUpdated,
                githubDownloaded = githubDownloaded,
                githubRepo = githubRepo,
                patchedCount = patchedCount,
            ),
        )
    }
}
