package app.fdroidserver.github

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Covers [GitHubReleaseChecker.downloadAsset] against a [MockEngine], in
 * particular that a cut-off transfer never ends up at the destination path
 * (truncated APKs used to be kept and then skipped as already downloaded).
 */
class GitHubReleaseCheckerTest {

    private val apkBytes: ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(ByteArray(4096) { it.toByte() })
            zip.closeEntry()
        }
    }.toByteArray()

    private val asset = GitHubReleaseChecker.GitHubAsset(id = 42, name = "app.apk", size = apkBytes.size.toLong())

    /** Serves [bodies] in order, one per request, repeating the last one. */
    private fun checker(vararg bodies: ByteArray): Pair<GitHubReleaseChecker, () -> Int> {
        var calls = 0
        val client = HttpClient(
            MockEngine {
                val body = bodies[minOf(calls, bodies.size - 1)]
                calls++
                respond(body, HttpStatusCode.OK)
            },
        )
        return GitHubReleaseChecker(client, githubToken = null) to { calls }
    }

    private fun download(checker: GitHubReleaseChecker, destination: File) = runBlocking {
        checker.downloadAsset("owner/repo", asset, destination, maxAttempts = 3, retryDelayMillis = 0)
    }

    @Test
    fun `complete download is moved into place`(@TempDir dir: File) {
        val (checker, calls) = checker(apkBytes)
        val destination = File(dir, "repo/1-app.apk")

        assertTrue(download(checker, destination))
        assertArrayEquals(apkBytes, destination.readBytes())
        assertEquals(1, calls())
        assertEquals(listOf("1-app.apk"), destination.parentFile.list()!!.toList())
    }

    @Test
    fun `truncated download is retried and never left at the destination`(@TempDir dir: File) {
        val (checker, calls) = checker(apkBytes.copyOf(apkBytes.size / 2))
        val destination = File(dir, "repo/1-app.apk")

        assertFalse(download(checker, destination))
        assertFalse(destination.exists())
        assertEquals(3, calls())
        assertEquals(emptyList<String>(), destination.parentFile.list()!!.toList())
    }

    @Test
    fun `retry after an empty response succeeds`(@TempDir dir: File) {
        val (checker, calls) = checker(ByteArray(0), apkBytes)
        val destination = File(dir, "repo/1-app.apk")

        assertTrue(download(checker, destination))
        assertArrayEquals(apkBytes, destination.readBytes())
        assertEquals(2, calls())
    }

    @Test
    fun `file of the right size that is not a zip is rejected`(@TempDir dir: File) {
        val (checker, _) = checker(ByteArray(apkBytes.size))
        val destination = File(dir, "repo/1-app.apk")

        assertFalse(download(checker, destination))
        assertFalse(destination.exists())
    }

    @Test
    fun `existing truncated file is replaced`(@TempDir dir: File) {
        val (checker, _) = checker(apkBytes)
        val destination = File(dir, "repo/1-app.apk").apply {
            parentFile.mkdirs()
            writeBytes(apkBytes.copyOf(10))
        }
        assertFalse(checker.isComplete(destination, asset))

        assertTrue(download(checker, destination))
        assertTrue(checker.isComplete(destination, asset))
    }
}
