package app.fdroidserver.scraper

import app.fdroidserver.apkmirror.ApkMirrorClient
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Covers [ScraperClient.downloadApk]'s check that what arrived is a whole
 * APK/bundle, against a local HTTP server. */
class ScraperClientDownloadTest {

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { start() }

    @AfterEach
    fun stop() = server.stop(0)

    private fun serve(path: String, body: ByteArray, contentType: String) {
        server.createContext(path) { exchange ->
            exchange.responseHeaders.add("Content-Type", contentType)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
    }

    private fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    private fun zipBytes(): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(ByteArray(16))
            zip.closeEntry()
        }
    }.toByteArray()

    @Test
    fun `a zip download succeeds`(@TempDir tempDir: File) {
        serve("/app.apk", zipBytes(), "application/vnd.android.package-archive")
        val destination = File(tempDir, "download.bin")
        assertTrue(ApkMirrorClient().downloadApk(url("/app.apk"), destination))
        assertTrue(destination.length() > 0)
    }

    @Test
    fun `an HTML page served with a 200 is rejected and removed`(@TempDir tempDir: File) {
        serve("/page", "<html><title>Just a moment...</title></html>".toByteArray(), "text/html")
        val destination = File(tempDir, "download.bin")
        assertFalse(ApkMirrorClient().downloadApk(url("/page"), destination))
        assertFalse(destination.exists())
    }
}
