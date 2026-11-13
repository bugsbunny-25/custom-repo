package app.fdroidserver.builder

import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The file-based hand-off between the always-on admin server and the
 * one-shot builder container.
 *
 * Layout under `$REPO_PATH` (both containers mount it):
 *
 *   .requests/<id>.json          a pending request. The admin server creates
 *                                these; a host-side systemd `.path` unit
 *                                watching this directory starts the builder.
 *   .status/builder.json         the builder's status document.
 *   .status/builder.lock         the cross-process build lock.
 *   .status/claimed/<id>.json    requests the current run took ownership of.
 *   .status/failed/<id>.json     requests whose run failed, kept for
 *                                inspection (most recent [KEEP_FAILED]).
 *
 * Why claimed/failed live under `.status/` and not inside `.requests/`: a
 * systemd `.path` unit triggers on `DirectoryNotEmpty=`, and a subdirectory
 * counts as an entry. Keeping them elsewhere means `.requests/` really is
 * empty once a run has claimed everything, so the unit re-arms instead of
 * firing in a loop.
 *
 * Claiming is a rename, which is atomic within a filesystem: two builders
 * racing for the same request cannot both win, and the loser simply sees one
 * fewer request.
 */
class BuildRequestQueue(private val repoDir: File) {

    private val logger = LoggerFactory.getLogger(BuildRequestQueue::class.java.name)

    val requestsDir = File(repoDir, ".requests")
    val statusDir = File(repoDir, ".status")
    private val claimedDir = File(statusDir, "claimed")
    private val failedDir = File(statusDir, "failed")

    /**
     * Writes a new request and returns its id.
     *
     * Written to a temp file and moved into place so the host's `.path` unit
     * cannot observe (and the builder cannot claim) a partially written
     * request.
     */
    fun enqueue(
        kind: BuildJobKind,
        targetId: String? = null,
        version: String? = null,
        versionPageUrl: String? = null,
        repo: String? = null,
        source: String = "admin-ui",
    ): String {
        requestsDir.mkdirs()
        val id = newId()
        val request = BuildRequest(
            id = id,
            kind = kind,
            requestedAt = Instant.now().toString(),
            targetId = targetId,
            version = version,
            versionPageUrl = versionPageUrl,
            repo = repo,
            source = source,
        )
        val tmp = File.createTempFile("request", ".json.tmp", requestsDir)
        tmp.writeText(json.encodeToString(request))
        Files.move(
            tmp.toPath(),
            File(requestsDir, "$id.json").toPath(),
            StandardCopyOption.ATOMIC_MOVE,
        )
        logger.info("Queued build request $id (${kind.name}) from $source")
        return id
    }

    /** Pending requests, oldest first, without claiming them. */
    fun peekAll(): List<BuildRequest> =
        (requestsDir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray())
            .sortedBy { it.name }
            .mapNotNull { decode(it) }

    /**
     * Takes ownership of every pending request by moving it out of
     * [requestsDir], and returns the ones that decoded. A request whose file
     * cannot be parsed is moved to [failedDir] rather than left to jam the
     * queue forever.
     */
    fun claimAll(): List<BuildRequest> {
        val files = requestsDir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        claimedDir.mkdirs()
        val claimed = mutableListOf<BuildRequest>()
        for (file in files.sortedBy { it.name }) {
            val target = File(claimedDir, file.name)
            val moved = try {
                Files.move(file.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                true
            } catch (e: Exception) {
                // Another builder claimed it first, or it vanished.
                logger.debug("Could not claim ${file.name}: $e")
                false
            }
            if (!moved) continue
            val request = decode(target)
            if (request == null) {
                logger.warn("Discarding unparseable build request ${file.name}")
                moveTo(target, failedDir)
            } else {
                claimed += request
            }
        }
        return claimed
    }

    /** Clears the claimed requests after a successful run. */
    fun releaseClaimed(success: Boolean) {
        val files = claimedDir.listFiles { f -> f.isFile } ?: return
        for (file in files) {
            if (success) {
                file.delete()
            } else {
                moveTo(file, failedDir)
            }
        }
        if (!success) trimFailed()
    }

    private fun moveTo(file: File, dir: File) {
        dir.mkdirs()
        runCatching {
            Files.move(file.toPath(), File(dir, file.name).toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.onFailure { file.delete() }
    }

    private fun trimFailed() {
        val files = failedDir.listFiles { f -> f.isFile }?.sortedByDescending { it.name } ?: return
        files.drop(KEEP_FAILED).forEach { it.delete() }
    }

    private fun decode(file: File): BuildRequest? = try {
        json.decodeFromString<BuildRequest>(file.readText())
    } catch (e: Exception) {
        logger.warn("Cannot decode build request ${file.name}: $e")
        null
    }

    /**
     * Sortable, collision-resistant id: UTC timestamp to the millisecond
     * plus random suffix. Sortable matters because [claimAll] runs requests
     * in filename order, which should be request order.
     */
    private fun newId(): String {
        val stamp = ID_FORMAT.format(Instant.now().atZone(ZoneOffset.UTC))
        val suffix = (1..6).map { ALPHABET[RANDOM.nextInt(ALPHABET.length)] }.joinToString("")
        return "$stamp-$suffix"
    }

    companion object {
        private const val KEEP_FAILED = 20
        private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
        private val RANDOM = SecureRandom()
        private val ID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS")
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }
    }
}
