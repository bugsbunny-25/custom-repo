package app.fdroidserver.apkpure

import app.fdroidserver.scraper.ScraperClient
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApkPureClientTest {

    private val client = ApkPureClient()

    private val base = "https://apkpure.com/flightaware-flight-tracker/com.flightaware.android.liveFlightTracker"

    private fun doc(bodyHtml: String) = Jsoup.parse("<html><body>$bodyHtml</body></html>", "$base/versions")

    /** A version-list row shaped like APKPure's real markup: a
     * `div.ver_download_link` carrying `data-dt-version`, with a version-name
     * link inside. [href] is the link's href ("" for the latest release, whose
     * link omits the version segment). */
    private fun verRow(version: String, href: String) =
        """<li><div class="ver_download_link dt-old-versions-item-new" data-dt-version="$version"
              data-dt-apkid="b/APK/base64blob">
              <a class="ver-item-n dt-version-name-link" href="$href">FlightAware $version</a>
              <a class="ver_download_btn" href="$href">Download</a>
           </div></li>"""

    // --- version discovery -----------------------------------------------------

    @Test
    fun `reads versions from data-dt-version rows, newest first`() {
        val document = doc(
            verRow("5.15.4", "$base/download/5.15.4") +
                verRow("5.15.3", "$base/download/5.15.3") +
                verRow("5.15.2", "$base/download/5.15.2"),
        )
        val versions = client.parseVersions(document, base)
        assertEquals(listOf("5.15.4", "5.15.3", "5.15.2"), versions.map { it.version })
        assertEquals("$base/download/5.15.4", versions.first().pageUrl)
    }

    @Test
    fun `captures the latest release even though its link omits the version segment`() {
        // APKPure's newest release links to the bare `.../download` page.
        val document = doc(
            verRow("5.15.5", "$base/download") +
                verRow("5.15.4", "$base/download/5.15.4"),
        )
        val versions = client.parseVersions(document, base)
        assertEquals(listOf("5.15.5", "5.15.4"), versions.map { it.version })
        assertEquals("$base/download", versions.first().pageUrl)
    }

    @Test
    fun `constructs a download page URL when a row has no usable link`() {
        val document = doc(
            """<li><div class="ver_download_link" data-dt-version="5.15.4"></div></li>""",
        )
        val entry = client.parseVersions(document, base).single()
        assertEquals("5.15.4", entry.version)
        assertEquals("$base/download/5.15.4", entry.pageUrl)
    }

    @Test
    fun `dedupes a version whose row appears more than once, keeping the first`() {
        val document = doc(
            verRow("5.15.4", "$base/download/5.15.4") +
                verRow("5.15.4", "$base/download/5.15.4") +
                verRow("5.15.3", "$base/download/5.15.3"),
        )
        assertEquals(listOf("5.15.4", "5.15.3"), client.parseVersions(document, base).map { it.version })
    }

    @Test
    fun `ignores the APKPure app promo row and the hero duplicate of the latest`() {
        val document = doc(
            // The `.ver-top-down` hero copy of the latest and the `.dl-ref`
            // APKPure-app promo both carry data-dt-version but are not
            // `.ver_download_link` rows, so they must not appear.
            """<div class="ver-top-down" data-dt-version="5.15.5" data-dt-apkid="b/APK/x"></div>""" +
                verRow("5.15.5", "$base/download") +
                """<div class="dl-ref" data-dt-version="3.20.77" data-dt-apkid="b/APK/aegon"></div>""",
        )
        assertEquals(listOf("5.15.5"), client.parseVersions(document, base).map { it.version })
    }

    // --- download-link resolution ----------------------------------------------

    private fun downloadDoc(bodyHtml: String) = Jsoup.parse("<html><body>$bodyHtml</body></html>", "$base/download/5.15.4")

    @Test
    fun `prefers the explicit download_link anchor`() {
        val document = downloadDoc(
            """
            <a href="https://d.apkpure.com/b/APK/com.other?versionCode=1">other</a>
            <a id="download_link" href="https://d.apkpure.com/b/APK/com.flightaware.android.liveFlightTracker?versionCode=501500400">Click here</a>
            """.trimIndent(),
        )
        assertEquals(
            "https://d.apkpure.com/b/APK/com.flightaware.android.liveFlightTracker?versionCode=501500400",
            client.pickDownloadHref(document),
        )
    }

    @Test
    fun `falls back to an asset-path download link when there is no download_link id`() {
        val document = downloadDoc(
            """
            <a href="$base">back to app</a>
            <a class="btn download-start-btn" href="https://d.apkpure.com/b/APK/com.flightaware.android.liveFlightTracker?versionCode=501500400">Download APK (28.3 MB)</a>
            """.trimIndent(),
        )
        assertEquals(
            "https://d.apkpure.com/b/APK/com.flightaware.android.liveFlightTracker?versionCode=501500400",
            client.pickDownloadHref(document),
        )
    }

    @Test
    fun `skips the APKPure app promo download and finds the real one`() {
        // The aegon promo link is a real d.apkpure.com CDN URL, but under
        // /custom/com.apkpure.aegon - it must never be picked as the app.
        val document = downloadDoc(
            """
            <a class="aegon-down-item-btn" href="https://d.apkpure.com/custom/com.apkpure.aegon-3207727.apk?_fn=x">Download</a>
            <a class="download-btn" href="https://d.apkpure.com/b/APK/com.flightaware.android.liveFlightTracker?versionCode=501500400">Download</a>
            """.trimIndent(),
        )
        assertEquals(
            "https://d.apkpure.com/b/APK/com.flightaware.android.liveFlightTracker?versionCode=501500400",
            client.pickDownloadHref(document),
        )
    }

    @Test
    fun `does not treat an ordinary in-page apkpure link as the download asset`() {
        val document = downloadDoc(
            """
            <a href="$base">back to app</a>
            <a href="https://apkpure.com/some-other-app/com.other">another app</a>
            """.trimIndent(),
        )
        assertNull(client.pickDownloadHref(document))
    }

    // --- warm-up + Cloudflare handling (shared ScraperClient machinery) ---------
    //
    // These mirror ApkMirrorClientTest's HttpServer-based tests, but exercise
    // the APKPure flow: a warm-up GET of the app page, then the `/versions`
    // page with the app page as Referer.

    private data class FakeResponse(val status: Int, val body: String, val headers: Map<String, String> = emptyMap())

    private val cloudflareChallengeBody = """
        <!DOCTYPE html><html><head><title>Just a moment...</title></head><body>
        <script>window._cf_chl_opt = {};</script>
        </body></html>
    """.trimIndent()

    // A minimal `/versions` page with one real-shaped row. The link uses a
    // relative href so the test doesn't need the server's (random) port.
    private val versionsHtml =
        """<html><body><li><div class="ver_download_link" data-dt-version="5.15.4">
              <a class="dt-version-name-link" href="download/5.15.4">FlightAware 5.15.4</a>
           </div></li></body></html>"""

    private fun startVersionsServer(
        versions: List<FakeResponse>,
        warmUp: List<FakeResponse> = listOf(FakeResponse(200, "<html>app page</html>")),
    ): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val warmUpCount = AtomicInteger(0)
        val versionsCount = AtomicInteger(0)
        server.createContext("/") { exchange ->
            val isVersions = exchange.requestURI.path.endsWith("/versions")
            val counter = if (isVersions) versionsCount else warmUpCount
            val responses = if (isVersions) versions else warmUp
            val response = responses[counter.getAndIncrement().coerceAtMost(responses.size - 1)]
            response.headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
            val bytes = response.body.toByteArray()
            exchange.sendResponseHeaders(response.status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server
    }

    @Test
    fun `warms up with the app page and reads versions from the versions page`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var versionsReferer: String? = null
        server.createContext("/") { exchange ->
            val bytes: ByteArray
            if (exchange.requestURI.path.endsWith("/versions")) {
                versionsReferer = exchange.requestHeaders.getFirst("Referer")
                bytes = versionsHtml.toByteArray()
            } else {
                bytes = "<html>app page</html>".toByteArray()
            }
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val appUrl = "http://127.0.0.1:${server.address.port}/flightaware/com.flightaware"
            val versions = ApkPureClient().getVersions(appUrl)
            assertEquals("5.15.4", versions.single().version)
            assertEquals(appUrl, versionsReferer)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `retries past a transient Cloudflare challenge on the versions page`() {
        val server = startVersionsServer(
            versions = listOf(FakeResponse(200, cloudflareChallengeBody), FakeResponse(200, versionsHtml)),
        )
        try {
            val appUrl = "http://127.0.0.1:${server.address.port}/flightaware/com.flightaware"
            val versions = ApkPureClient().getVersions(appUrl)
            assertEquals("5.15.4", versions.single().version)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `gives up after repeated Cloudflare challenges with a clear error`() {
        val server = startVersionsServer(versions = listOf(FakeResponse(200, cloudflareChallengeBody)))
        try {
            val client = ApkPureClient(flareSolverrUrl = null)
            val exception = assertThrows(IllegalStateException::class.java) {
                client.getVersions("http://127.0.0.1:${server.address.port}/flightaware/com.flightaware")
            }
            assertTrue(exception.message.orEmpty().contains("Cloudflare challenge"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `validates FlareSolverr URLs through the shared ScraperClient contract`() {
        assertTrue(ScraperClient.isValidFlareSolverrUrl("http://flaresolverr:8191/v1"))
        assertTrue(!ScraperClient.isValidFlareSolverrUrl("garbage"))
        val client = ApkPureClient(flareSolverrUrl = "not a url")
        assertNull(client.flareSolverrUrl)
        client.flareSolverrUrl = "http://flaresolverr:8191/v1"
        assertEquals("http://flaresolverr:8191/v1", client.flareSolverrUrl)
    }
}
