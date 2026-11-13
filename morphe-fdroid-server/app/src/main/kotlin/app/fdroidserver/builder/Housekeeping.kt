package app.fdroidserver.builder

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Deletes build intermediates once a run has published successfully.
 *
 * What is and is not an intermediate matters here, because the directory
 * names are misleading. `/srv/fdroid/patched` and `/srv/fdroid/patched-tv`
 * are *F-Droid repositories*, not scratch space: their `repo/` subdirectories
 * hold the signed APKs that `/patched/repo` and `/patched-tv/repo` serve to
 * clients. Deleting those would take the apps away from every subscriber. So
 * published APKs are never touched here - they are instead *bounded*, by
 * `maxVersionsPerApp` (already applied after each publish) and by
 * `ARCHIVE_KEEP` (see FdroidRepoManager.pruneArchive).
 *
 * What this class removes is genuinely reproducible scratch state:
 *
 *   tmp, `<name>-prepared.apk`        downloaded (and bundle-merged) APKs,
 *                                    kept during a run so several patches for
 *                                    one version share a single download
 *   tmp, `patch-<...>` dirs          per-job working directories
 *   tmp, `staged-<...>`              patch output staged before being moved
 *                                    into the repo
 *   patched/tmp and patched-tv/tmp   fdroidserver's own scratch directories
 *                                    (contents only; the dirs stay)
 *
 * Only runs after success, deliberately: on a failed run these files are the
 * evidence, and re-running will reuse the already-downloaded APK rather than
 * fetching it again.
 */
class Housekeeping(
    private val repoDir: File,
    private val logger: Logger = LoggerFactory.getLogger(Housekeeping::class.java.name),
) {

    data class Result(val filesDeleted: Int, val bytesFreed: Long)

    /**
     * @param keepPreparedApks when true, leaves the downloaded/merged APK
     *   cache in place and clears only working directories. Set
     *   KEEP_PREPARED_APKS=true if bandwidth is scarcer than disk.
     */
    fun cleanIntermediates(keepPreparedApks: Boolean = false): Result {
        var files = 0
        var bytes = 0L

        val tmpDir = File(repoDir, "tmp")
        if (tmpDir.isDirectory) {
            val entries = tmpDir.listFiles() ?: emptyArray()
            for (entry in entries) {
                val isWorkDir = entry.isDirectory && (entry.name.startsWith("patch-") || entry.name.startsWith("staged-"))
                val isStaged = entry.isFile && entry.name.startsWith("staged-")
                val isPrepared = entry.isFile && entry.name.endsWith("-prepared.apk")
                val remove = isWorkDir || isStaged || (isPrepared && !keepPreparedApks)
                if (!remove) continue
                val size = sizeOf(entry)
                if (deleteRecursively(entry)) {
                    files++
                    bytes += size
                }
            }
        }

        // fdroidserver's own scratch dirs. The directory itself is kept -
        // `fdroid update` expects it to exist - only its contents go.
        for (repo in listOf(repoDir, File(repoDir, "patched"), File(repoDir, "patched-tv"))) {
            val fdroidTmp = File(repo, "tmp")
            if (!fdroidTmp.isDirectory) continue
            // The top-level /srv/fdroid/tmp is this project's own working
            // directory, handled above; don't walk it twice.
            if (fdroidTmp.canonicalFile == File(repoDir, "tmp").canonicalFile) continue
            for (entry in fdroidTmp.listFiles() ?: emptyArray()) {
                val size = sizeOf(entry)
                if (deleteRecursively(entry)) {
                    files++
                    bytes += size
                }
            }
        }

        if (files > 0) {
            logger.info("Housekeeping: removed $files intermediate(s), freeing ${bytes / 1_048_576} MiB")
        }
        return Result(files, bytes)
    }

    private fun sizeOf(file: File): Long =
        if (file.isDirectory) {
            file.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        } else {
            file.length()
        }

    private fun deleteRecursively(file: File): Boolean {
        val ok = file.deleteRecursively()
        if (!ok) logger.warn("Housekeeping: could not delete ${file.absolutePath}")
        return ok
    }
}
