package app.fdroidserver.builder

import org.slf4j.LoggerFactory
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/**
 * A cross-process mutex for "only one builder touches /srv/fdroid at a time".
 *
 * The schedulers already hold in-process mutexes (PatchScheduler.runLock,
 * GithubScheduler.checkMutex), but those only serialize work inside one JVM.
 * Once the builder is its own container, two runs can genuinely overlap - the
 * nightly systemd timer firing while a `.path`-unit run started by the admin
 * UI is still going. Both would write the same repo directories and both
 * would call `fdroid update`, which is not safe to run twice concurrently
 * against one repo.
 *
 * This uses an OS-level advisory lock (`FileChannel.tryLock`) rather than a
 * pid/lock *file* on purpose: the kernel drops the lock when the holder
 * exits, however it exits. A lock file containing a pid has to be cleaned up,
 * and a builder killed by the OOM killer or `podman stop` would leave a stale
 * one behind that blocks every later run until someone deletes it by hand.
 *
 * The lock file itself lives under the status directory, not the request
 * directory, so a host-side systemd `.path` unit watching the request
 * directory for `DirectoryNotEmpty` is not kept permanently triggered by it.
 */
class BuildLock private constructor(
    private val raf: RandomAccessFile,
    private val lock: FileLock,
    private val lockFile: File,
) : AutoCloseable {

    override fun close() {
        runCatching { lock.release() }
        runCatching { raf.close() }
        // Deliberately not deleting lockFile: another process may already
        // have opened it, and deleting the inode out from under it would let
        // two builders each hold a lock on a different inode of the same path.
    }

    companion object {
        private val logger = LoggerFactory.getLogger(BuildLock::class.java.name)
        const val FILE_NAME = "builder.lock"

        /**
         * Returns a held lock, or null if another process holds it. Never
         * blocks - a builder that finds the lock taken should exit cleanly
         * and let the holder finish, because the work is idempotent and the
         * next trigger will pick up anything outstanding.
         */
        fun tryAcquire(statusDir: File): BuildLock? {
            statusDir.mkdirs()
            val lockFile = File(statusDir, FILE_NAME)
            val raf = try {
                RandomAccessFile(lockFile, "rw")
            } catch (e: Exception) {
                logger.error("Cannot open build lock file $lockFile: $e")
                return null
            }
            val lock = try {
                raf.channel.tryLock()
            } catch (e: OverlappingFileLockException) {
                // Same JVM already holds it.
                null
            } catch (e: Exception) {
                logger.error("Cannot take build lock on $lockFile: $e")
                runCatching { raf.close() }
                return null
            }
            if (lock == null) {
                runCatching { raf.close() }
                return null
            }
            return BuildLock(raf, lock, lockFile)
        }
    }
}
