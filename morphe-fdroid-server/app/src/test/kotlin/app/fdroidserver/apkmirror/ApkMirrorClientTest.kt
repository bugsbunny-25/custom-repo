package app.fdroidserver.apkmirror

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApkMirrorClientTest {

    private val client = ApkMirrorClient()

    private fun doc(rowsHtml: String) = Jsoup.parse(
        """<html><body><div class="variants-table">$rowsHtml</div></body></html>""",
        "https://www.apkmirror.com/",
    )

    private fun row(text: String, href: String) =
        """<div class="table-row">$text <a class="accent_color" href="$href">download</a></div>"""

    @Test
    fun `prefers a plain apk over a bundle`() {
        val document = doc(
            row("APK (armeabi-v7a)", "/apk-armeabi") +
                row("BUNDLE (arm64-v8a)", "/bundle-arm64"),
        )
        assertEquals("https://www.apkmirror.com/apk-armeabi", client.pickBestVariantHref(document))
    }

    @Test
    fun `prefers arm64-v8a-universal apk over other architectures`() {
        val document = doc(
            row("APK (armeabi-v7a)", "/apk-armeabi") +
                row("APK (arm64-v8a)", "/apk-arm64") +
                row("APK (x86)", "/apk-x86"),
        )
        assertEquals("https://www.apkmirror.com/apk-arm64", client.pickBestVariantHref(document))
    }

    @Test
    fun `falls back to a bundle when no plain apk variant exists`() {
        val document = doc(
            row("BUNDLE (armeabi-v7a)", "/bundle-armeabi") +
                row("BUNDLE (arm64-v8a)", "/bundle-arm64"),
        )
        assertEquals("https://www.apkmirror.com/bundle-arm64", client.pickBestVariantHref(document))
    }

    @Test
    fun `returns null when there are no variant rows`() {
        val document = Jsoup.parse("<html><body>no variants here</body></html>", "https://www.apkmirror.com/")
        assertNull(client.pickBestVariantHref(document))
    }

    private fun readFeedFixture(name: String): String =
        checkNotNull(
            ApkMirrorClientTest::class.java.classLoader.getResourceAsStream("apkmirror/$name"),
        ) { "Missing test resource apkmirror/$name" }.bufferedReader().readText()

    @Test
    fun `parses all 10 versions from the Disney+ feed, keeping rc and date suffixes`() {
        val versions = client.parseFeedVersions(readFeedFixture("disney-plus-feed.xml"))

        assertEquals(
            listOf(
                "26.11.0+rc3-2026.06.24",
                "26.11.0+rc1-2026.06.18",
                "26.10.0+rc3-2026.06.13",
                "26.9.2+rc1-2026.06.12",
                "26.9.1+rc1-2026.06.04",
                "26.9.0+rc2-2026.06.01",
                "26.8.1+rc1-2026.05.26",
                "26.7.0+rc4-2026.05.01",
                "26.6.0+rc5-2026.04.21",
                "26.5.1+rc1-2026.04.08",
            ),
            versions.map { it.version },
        )
        assertEquals(
            "https://www.apkmirror.com/apk/disney/disney/disney-26-11-0rc3-2026-06-24-release/",
            versions.first().pageUrl,
        )
    }

    @Test
    fun `parses all 10 versions from the Uber Eats feed, keeping the beta suffix`() {
        val versions = client.parseFeedVersions(readFeedFixture("uber-eats-feed.xml"))

        assertEquals(
            listOf(
                "6.330.10005 beta",
                "6.329.10003",
                "6.327.10000",
                "6.329.10001 beta",
                "6.328.10000",
                "6.322.10003",
                "6.321.10000",
                "6.322.10004 beta",
                "6.319.10001",
                "6.318.10000",
            ),
            versions.map { it.version },
        )
        assertEquals(
            "https://www.apkmirror.com/apk/uber-technologies-inc/uber-eats-food-delivery/" +
                "uber-eats-food-and-grocery-6-330-10005-release/",
            versions.first().pageUrl,
        )
    }

    @Test
    fun `parses all 10 versions from the Disney+ Android TV feed, ignoring the variant suffix`() {
        val versions = client.parseFeedVersions(readFeedFixture("disney-plus-android-tv-feed.xml"))

        assertEquals(
            listOf(
                "26.06.22.3",
                "26.06.08.3",
                "26.05.25.0",
                "26.05.11.1",
                "26.04.27.1",
                "26.04.13.3",
                "26.03.30.3",
                "26.03.16.0",
                "26.03.02.3",
                "26.02.16.3",
            ),
            versions.map { it.version },
        )
        assertEquals(
            10,
            versions.size,
        )
    }

    @Test
    fun `returns no versions when the channel title doesn't match the expected format`() {
        val xml = """
            <rss version="2.0"><channel>
                <title>Something unexpected</title>
                <item><title>App 1.2.3 by Someone</title><link>https://example.com/</link></item>
            </channel></rss>
        """.trimIndent()

        assertEquals(emptyList<ApkMirrorClient.VersionEntry>(), client.parseFeedVersions(xml))
    }

    // --- Cloudflare-challenge handling -------------------------------------
    //
    // These spin up a throwaway JDK HttpServer rather than mocking
    // java.net.http.HttpClient (which isn't practical to mock - it's a
    // concrete final class with no seams), so the retry loop in
    // ApkMirrorClient.get() is exercised against a real HTTP round trip: a
    // fake "Just a moment..." interstitial on the first request(s), like the
    // one that blocked curl while fetching the Uber Eats feed fixture for
    // this test file, followed by the real feed.

    private val disneyFeedXml = readFeedFixture("disney-plus-feed.xml")

    private fun startFeedServer(vararg responses: FakeResponse): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requestCount = AtomicInteger(0)
        server.createContext("/") { exchange ->
            val index = requestCount.getAndIncrement().coerceAtMost(responses.size - 1)
            val response = responses[index]
            response.headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
            val bytes = response.body.toByteArray()
            exchange.sendResponseHeaders(response.status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server
    }

    private data class FakeResponse(val status: Int, val body: String, val headers: Map<String, String> = emptyMap())

    private val cloudflareChallengeBody = """
        <!DOCTYPE html><html><head><title>Just a moment...</title></head><body>
        <script>window._cf_chl_opt = {};</script>
        </body></html>
    """.trimIndent()

    @Test
    fun `retries past a transient Cloudflare challenge and returns the real versions`() {
        val server = startFeedServer(
            FakeResponse(200, cloudflareChallengeBody),
            FakeResponse(200, disneyFeedXml),
        )
        try {
            val client = ApkMirrorClient()
            val versions = client.getVersions("http://127.0.0.1:${server.address.port}/apk/disney/disney")
            assertEquals("26.11.0+rc3-2026.06.24", versions.first().version)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `gives up after repeated Cloudflare challenges with a clear error`() {
        val server = startFeedServer(FakeResponse(200, cloudflareChallengeBody))
        try {
            val client = ApkMirrorClient()
            val exception = assertThrows(IllegalStateException::class.java) {
                client.getVersions("http://127.0.0.1:${server.address.port}/apk/disney/disney")
            }
            assertTrue(exception.message.orEmpty().contains("Cloudflare challenge"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `treats a cf-mitigated challenge header as a challenge even with an ordinary body`() {
        val server = startFeedServer(
            FakeResponse(200, "not actually a challenge page", mapOf("cf-mitigated" to "challenge")),
            FakeResponse(200, disneyFeedXml),
        )
        try {
            val client = ApkMirrorClient()
            val versions = client.getVersions("http://127.0.0.1:${server.address.port}/apk/disney/disney")
            assertEquals("26.11.0+rc3-2026.06.24", versions.first().version)
        } finally {
            server.stop(0)
        }
    }
}
