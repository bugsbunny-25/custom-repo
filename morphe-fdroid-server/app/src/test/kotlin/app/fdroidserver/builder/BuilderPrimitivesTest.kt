package app.fdroidserver.builder

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The file-based hand-off between the admin server and the builder container.
 *
 * These are the parts with no HTTP and no JVM in front of them - a request
 * file, a rename, an OS lock - and they are exactly the parts that fail
 * silently in production if they are wrong: a request that is never claimed
 * means a button in the UI that does nothing, and a lock that does not hold
 * means two builders writing one repo at once.
 */
class BuilderPrimitivesTest {

    @Test
    fun `enqueue then claim round-trips a request`(@TempDir tempDir: File) {
        val queue = BuildRequestQueue(tempDir)

        val id = queue.enqueue(BuildJobKind.PATCH_TARGET, targetId = "yt", source = "test")

        val claimed = queue.claimAll()
        assertEquals(1, claimed.size)
        assertEquals(id, claimed[0].id)
        assertEquals(BuildJobKind.PATCH_TARGET, claimed[0].kind)
        assertEquals("yt", claimed[0].targetId)
        assertEquals("test", claimed[0].source)
    }

    @Test
    fun `claiming empties the requests directory so a path unit re-arms`(@TempDir tempDir: File) {
        // A systemd .path unit triggers on DirectoryNotEmpty=, and a
        // subdirectory counts as an entry. If claimed/failed lived inside
        // .requests, the unit would fire forever.
        val queue = BuildRequestQueue(tempDir)
        queue.enqueue(BuildJobKind.FULL)

        queue.claimAll()

        assertEquals(
            emptyList<String>(),
            queue.requestsDir.listFiles()!!.map { it.name },
            "nothing may remain under .requests after a claim",
        )
    }

    @Test
    fun `a claimed request is deleted on success and kept on failure`(@TempDir tempDir: File) {
        val queue = BuildRequestQueue(tempDir)
        queue.enqueue(BuildJobKind.FULL)
        queue.claimAll()

        queue.releaseClaimed(success = true)
        assertTrue(File(queue.statusDir, "claimed").listFiles().isNullOrEmpty())
        assertTrue(File(queue.statusDir, "failed").listFiles().isNullOrEmpty())

        queue.enqueue(BuildJobKind.FULL)
        queue.claimAll()
        queue.releaseClaimed(success = false)
        assertEquals(1, File(queue.statusDir, "failed").listFiles()!!.size, "a failed run's request is kept for inspection")
    }

    @Test
    fun `an unparseable request is moved aside rather than jamming the queue`(@TempDir tempDir: File) {
        val queue = BuildRequestQueue(tempDir)
        queue.requestsDir.mkdirs()
        File(queue.requestsDir, "20260101T000000000-bad.json").writeText("{ not json")

        val claimed = queue.claimAll()

        assertTrue(claimed.isEmpty())
        assertTrue(queue.requestsDir.listFiles().isNullOrEmpty(), "the bad file must leave .requests")
        assertEquals(1, File(queue.statusDir, "failed").listFiles()!!.size)
    }

    @Test
    fun `requests are claimed in the order they were made`(@TempDir tempDir: File) {
        val queue = BuildRequestQueue(tempDir)
        val first = queue.enqueue(BuildJobKind.PATCH_TARGET, targetId = "a")
        Thread.sleep(2) // ids carry millisecond precision
        val second = queue.enqueue(BuildJobKind.PATCH_TARGET, targetId = "b")

        val claimed = queue.claimAll()

        assertEquals(listOf(first, second), claimed.map { it.id })
    }

    @Test
    fun `peekAll does not claim`(@TempDir tempDir: File) {
        val queue = BuildRequestQueue(tempDir)
        queue.enqueue(BuildJobKind.FULL)

        assertEquals(1, queue.peekAll().size)
        assertEquals(1, queue.peekAll().size, "peeking twice must see the same request")
        assertEquals(1, queue.claimAll().size)
    }

    @Test
    fun `a request decodes even when written by a build that did not know some fields`(@TempDir tempDir: File) {
        val queue = BuildRequestQueue(tempDir)
        queue.requestsDir.mkdirs()
        File(queue.requestsDir, "20260101T000000000-old.json").writeText(
            """{"id":"20260101T000000000-old","kind":"full","requestedAt":"2026-01-01T00:00:00Z","somethingNew":true}""",
        )

        val claimed = queue.claimAll()

        assertEquals(1, claimed.size)
        assertEquals(BuildJobKind.FULL, claimed[0].kind)
        assertEquals("unknown", claimed[0].source, "a missing optional field falls back to its default")
    }

    @Test
    fun `the on-disk kind names are the documented ones`(@TempDir tempDir: File) {
        // These strings are the integration contract with the host side, so a
        // Kotlin rename must not change them silently.
        val queue = BuildRequestQueue(tempDir)
        queue.enqueue(BuildJobKind.PATCH_TARGET_TV, targetId = "x")

        val text = queue.requestsDir.listFiles()!!.single().readText()
        assertTrue(text.contains("\"patch-target-tv\""), "expected the serial name, got: $text")
    }

    @Test
    fun `the build lock is exclusive and is released on close`(@TempDir tempDir: File) {
        val statusDir = File(tempDir, ".status")

        val first = BuildLock.tryAcquire(statusDir)
        assertNotNull(first, "the first acquirer should win")
        assertNull(BuildLock.tryAcquire(statusDir), "a second acquirer in the same JVM must be refused")

        first!!.close()
        val third = BuildLock.tryAcquire(statusDir)
        assertNotNull(third, "the lock must be available again after close")
        third!!.close()
    }

    @Test
    fun `status round-trips and a missing file reads as idle`(@TempDir tempDir: File) {
        val store = BuildStatusStore(tempDir)

        assertEquals(BuildState.IDLE, store.read().state, "no status file yet means idle, not an error")

        store.write(BuildStatus(state = BuildState.SUCCEEDED, runId = "r1", patchedCount = 2))
        val read = store.read()
        assertEquals(BuildState.SUCCEEDED, read.state)
        assertEquals("r1", read.runId)
        assertEquals(2, read.patchedCount)
    }

    @Test
    fun `a corrupt status file reads as idle instead of throwing`(@TempDir tempDir: File) {
        File(tempDir, BuildStatusStore.FILE_NAME).writeText("{ truncated")

        assertEquals(BuildState.IDLE, BuildStatusStore(tempDir).read().state)
    }

    @Test
    fun `the recorder reports steps and a final state`(@TempDir tempDir: File) {
        val store = BuildStatusStore(tempDir)
        val recorder = BuildStatusRecorder(store, "r1", listOf("req1"), listOf(BuildJobKind.FULL))

        recorder.started()
        assertEquals(BuildState.RUNNING, store.read().state)

        recorder.stepStarted("github-releases")
        assertEquals("github-releases", store.read().currentStep)

        recorder.noteGithub(downloaded = 3, repo = "owner/name")
        recorder.stepFinished("github-releases", ok = true, message = "downloaded 3")
        recorder.finished(BuildState.SUCCEEDED)

        val final = store.read()
        assertEquals(BuildState.SUCCEEDED, final.state)
        assertNull(final.currentStep)
        assertEquals(1, final.steps.size)
        assertEquals("ok", final.steps[0].state)
        assertEquals(3, final.githubDownloaded)
        assertEquals("owner/name", final.githubRepo)
        assertEquals(listOf("req1"), final.requestIds)
        assertNotNull(final.finishedAt)
    }

    @Test
    fun `housekeeping removes scratch state but never published APKs`(@TempDir tempDir: File) {
        // The directory names are misleading: patched/repo holds the signed
        // APKs that /patched/repo serves to clients. Deleting those would
        // take the apps away from every subscriber, so this is the test that
        // matters most in this file.
        val tmp = File(tempDir, "tmp").apply { mkdirs() }
        File(tmp, "patched-yt-21.36.45-prepared.apk").writeText("downloaded")
        File(tmp, "patch-patched-yt-abc-1700").apply { mkdirs() }
            .also { File(it, "scratch.dex").writeText("x") }
        File(tmp, "staged-patched-1700-out.apk").writeText("staged")

        val publishedRepo = File(tempDir, "patched/repo").apply { mkdirs() }
        val publishedApk = File(publishedRepo, "yt__morphe__21.36.45.apk")
        publishedApk.writeText("published")
        val publishedIndex = File(publishedRepo, "index-v1.jar")
        publishedIndex.writeText("index")

        val fdroidTmp = File(tempDir, "patched/tmp").apply { mkdirs() }
        File(fdroidTmp, "leftover").writeText("x")

        val result = Housekeeping(tempDir).cleanIntermediates()

        assertTrue(tmp.listFiles().isNullOrEmpty(), "all scratch state under tmp should be gone")
        assertTrue(fdroidTmp.isDirectory, "fdroidserver's tmp directory itself must survive")
        assertTrue(fdroidTmp.listFiles().isNullOrEmpty(), "but not its contents")
        assertTrue(publishedApk.exists(), "a published APK must never be deleted by housekeeping")
        assertTrue(publishedIndex.exists(), "nor the signed index")
        assertTrue(result.filesDeleted >= 4)
        assertTrue(result.bytesFreed > 0)
    }

    @Test
    fun `housekeeping can keep the prepared-APK download cache`(@TempDir tempDir: File) {
        val tmp = File(tempDir, "tmp").apply { mkdirs() }
        val prepared = File(tmp, "patched-yt-1.0-prepared.apk")
        prepared.writeText("downloaded")
        val workDir = File(tmp, "patch-x").apply { mkdirs() }

        Housekeeping(tempDir).cleanIntermediates(keepPreparedApks = true)

        assertTrue(prepared.exists(), "KEEP_PREPARED_APKS should preserve the download cache")
        assertFalse(workDir.exists(), "working directories go regardless")
    }
}
