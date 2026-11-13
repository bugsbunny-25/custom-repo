package app.fdroidserver.scraper

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Cloudflare clearance round trip, against a fake Cloudflare-fronted site
 * and a fake FlareSolverr.
 *
 * This exists because of a bug that cost ~1100 FlareSolverr calls a day on a
 * real deployment. The solved cookies were merged into the cookie jar but
 * FlareSolverr's `userAgent` was dropped, and Cloudflare ties `cf_clearance`
 * to the UA that earned it - so the clearance cookie was rejected on the very
 * next request, every page fetch was challenged, and every challenge paid for
 * a fresh headless-browser solve. It looked like it was working: pages were
 * returned, nothing errored.
 *
 * The load-bearing assertion is therefore not "a solve succeeds" but
 * "the request *after* a solve does not need another one".
 */
class FlareSolverrClearanceTest {

    private val site = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { start() }
    private val flare = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { start() }

    @AfterEach
    fun stop() {
        site.stop(0)
        flare.stop(0)
    }

    private val siteRequests = AtomicInteger()
    private val challengesServed = AtomicInteger()
    private val solveRequests = AtomicInteger()
    private val sessionCreates = AtomicInteger()
    private val solveBodies = mutableListOf<String>()
    private val sessionsSeen = mutableListOf<String?>()

    /** What the browser FlareSolverr drives claims to be. */
    private val browserUserAgent = "Mozilla/5.0 (X11; Linux x86_64) HeadlessChrome/131.0.0.0"

    private fun siteUrl(path: String) = "http://127.0.0.1:${site.address.port}$path"
    private fun flareUrl() = "http://127.0.0.1:${flare.address.port}/v1"

    /**
     * Serves the real page only to a request that carries BOTH the clearance
     * cookie and the exact User-Agent it was issued to - which is how
     * Cloudflare actually behaves, and the only way this test can tell the
     * bug from the fix.
     */
    private fun startSite(body: String = "<html>real page</html>") {
        site.createContext("/") { exchange ->
            siteRequests.incrementAndGet()
            val cookies = exchange.requestHeaders.getFirst("Cookie") ?: ""
            val ua = exchange.requestHeaders.getFirst("User-Agent") ?: ""
            val cleared = cookies.contains("cf_clearance=token-1") && ua == browserUserAgent
            if (cleared) {
                respond(exchange, body)
            } else {
                challengesServed.incrementAndGet()
                respond(exchange, "<html><title>Just a moment...</title></html>")
            }
        }
    }

    private fun startFlareSolverr(sessionsSupported: Boolean = true, pageBody: String = "<html>real page</html>") {
        flare.createContext("/v1") { exchange ->
            val body = exchange.requestBody.readBytes().decodeToString()
            when {
                body.contains("\"sessions.create\"") -> {
                    sessionCreates.incrementAndGet()
                    if (sessionsSupported) {
                        respond(exchange, """{"status":"ok","message":"Session created successfully.","session":"custom-repo-test"}""")
                    } else {
                        respond(exchange, """{"status":"error","message":"Unknown command"}""")
                    }
                }
                else -> {
                    solveRequests.incrementAndGet()
                    solveBodies += body
                    sessionsSeen += Regex(""""session"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
                    respond(
                        exchange,
                        """
                        {"status":"ok","message":"","solution":{
                          "url":"${siteUrl("/app")}",
                          "status":200,
                          "userAgent":"$browserUserAgent",
                          "cookies":[{"name":"cf_clearance","value":"token-1","domain":"127.0.0.1","path":"/","secure":false,"httpOnly":false}],
                          "response":${'"'}${pageBody.replace("\"", "\\\"")}${'"'}
                        }}
                        """.trimIndent(),
                    )
                }
            }
        }
    }

    private fun respond(exchange: HttpExchange, body: String) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "text/html")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    /** Minimal concrete client; same package, so `get` is reachable. */
    private class TestClient(flareSolverrUrl: String?) :
        ScraperClient(LoggerFactory.getLogger("test"), flareSolverrUrl) {
        override val sourceName = "test"
        override fun getVersions(appUrl: String): List<VersionEntry> = emptyList()
        override fun resolveDownloadUrl(versionPageUrl: String): DownloadInfo? = null
        fun fetch(url: String): String = get(url)
        fun clearanceHeldFor(url: String): Boolean = hasClearance(url)
    }

    // -----------------------------------------------------------------------

    @Test
    fun `a request after a solve does not need another solve`() {
        startSite()
        startFlareSolverr()
        val client = TestClient(flareUrl())

        assertEquals("<html>real page</html>", client.fetch(siteUrl("/app")).trim())
        assertEquals(1, solveRequests.get(), "the first fetch should need exactly one solve")

        // The regression: with the UA dropped, this second fetch is challenged
        // again and solves again.
        assertEquals("<html>real page</html>", client.fetch(siteUrl("/other")).trim())
        assertEquals(1, solveRequests.get(), "the second fetch must reuse the clearance, not solve again")

        client.fetch(siteUrl("/third"))
        assertEquals(1, solveRequests.get(), "clearance should keep holding")
    }

    @Test
    fun `the solved User-Agent is adopted`() {
        startSite()
        startFlareSolverr()
        val client = TestClient(flareUrl())

        client.fetch(siteUrl("/app"))

        // The site only returns the real page to the browser's UA, so a
        // successful direct fetch is itself the proof the UA was adopted.
        val challengesBefore = challengesServed.get()
        client.fetch(siteUrl("/again"))
        assertEquals(challengesBefore, challengesServed.get(), "no further challenge should be served")
    }

    @Test
    fun `the clearance cookie is recorded for the host`() {
        startSite()
        startFlareSolverr()
        val client = TestClient(flareUrl())

        assertFalse(client.clearanceHeldFor(siteUrl("/app")), "no clearance before the first solve")
        client.fetch(siteUrl("/app"))
        assertTrue(client.clearanceHeldFor(siteUrl("/app")), "clearance should be held after a solve")
    }

    @Test
    fun `a session is created once and reused`() {
        startSite()
        startFlareSolverr()
        val client = TestClient(flareUrl())

        client.fetch(siteUrl("/app"))
        // Force a second solve by starting a client that shares nothing.
        val second = TestClient(flareUrl())
        second.fetch(siteUrl("/app2"))

        assertEquals(2, solveRequests.get())
        assertEquals(
            listOf("custom-repo-test", "custom-repo-test"),
            sessionsSeen,
            "every request.get should carry the session, so Chrome is not relaunched per request",
        )
    }

    @Test
    fun `media loading is disabled on solve requests`() {
        startSite()
        startFlareSolverr()
        TestClient(flareUrl()).fetch(siteUrl("/app"))

        assertTrue(solveBodies.single().contains("\"disableMedia\":true"), "got: ${solveBodies.single()}")
    }

    @Test
    fun `a FlareSolverr without session support still solves`() {
        // Older FlareSolverr builds reject sessions.create. The fallback is a
        // throwaway browser per request - slower, but it must still work
        // rather than failing the fetch outright.
        startSite()
        startFlareSolverr(sessionsSupported = false)
        val client = TestClient(flareUrl())

        assertEquals("<html>real page</html>", client.fetch(siteUrl("/app")).trim())
        assertEquals(1, sessionCreates.get(), "session creation should be attempted once")
        assertEquals(listOf<String?>(null), sessionsSeen, "and then not sent")

        client.fetch(siteUrl("/other"))
        assertEquals(1, sessionCreates.get(), "a refused session must not be retried on every request")
    }

    @Test
    fun `a challenge with no FlareSolverr configured fails with an actionable message`() {
        startSite()
        val client = TestClient(null)

        val error = runCatching { client.fetch(siteUrl("/app")) }.exceptionOrNull()
        assertTrue(error != null, "a challenge with no fallback must not return the interstitial as content")
        assertTrue(
            error!!.message!!.contains("no FlareSolverr configured"),
            "got: ${error.message}",
        )
    }

    @Test
    fun `hasClearance does not match a different host`() {
        startSite()
        startFlareSolverr()
        val client = TestClient(flareUrl())
        client.fetch(siteUrl("/app"))

        assertFalse(client.clearanceHeldFor("https://example.com/app"))
    }

    // -----------------------------------------------------------------------
    // Transport-level retry
    // -----------------------------------------------------------------------
    // A session proxy in front of these sites answers an expired session with
    // 410, and the next request through it gets a fresh one. APKMirror used to
    // survive that by accident (its warm-up request absorbed the failure
    // inside a runCatching, and the feed request that followed succeeded)
    // while APKPure's single fetch for the versions page had no second chance
    // and failed the whole version check. The retry lives in the shared get()
    // so both are covered by construction.

    @Test
    fun `a 410 from an expired upstream session is retried once and recovers`() {
        val attempts = AtomicInteger()
        site.createContext("/") { exchange ->
            if (attempts.incrementAndGet() == 1) {
                val body = "session expired".toByteArray()
                exchange.sendResponseHeaders(410, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            } else {
                respond(exchange, "<html>real page</html>")
            }
        }

        val client = TestClient(null)
        assertEquals("<html>real page</html>", client.fetch(siteUrl("/versions")).trim())
        assertEquals(2, attempts.get(), "the first 410 should be retried exactly once")
    }

    @Test
    fun `a persistent 410 still fails, reporting the status`() {
        site.createContext("/") { exchange ->
            val body = "gone".toByteArray()
            exchange.sendResponseHeaders(410, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }

        val error = runCatching { TestClient(null).fetch(siteUrl("/versions")) }.exceptionOrNull()
        assertTrue(error?.message?.contains("HTTP 410") == true, "got: ${error?.message}")
    }

    @Test
    fun `a 404 is not retried`() {
        // Retrying a genuine "not found" just buys the same answer twice, and
        // doubles the load on a site that is already rate-limiting us.
        val attempts = AtomicInteger()
        site.createContext("/") { exchange ->
            attempts.incrementAndGet()
            val body = "nope".toByteArray()
            exchange.sendResponseHeaders(404, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }

        runCatching { TestClient(null).fetch(siteUrl("/missing")) }
        assertEquals(1, attempts.get(), "a 404 should be taken at its word")
    }

    @Test
    fun `a 503 is retried once`() {
        val attempts = AtomicInteger()
        site.createContext("/") { exchange ->
            if (attempts.incrementAndGet() == 1) {
                val body = "busy".toByteArray()
                exchange.sendResponseHeaders(503, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            } else {
                respond(exchange, "<html>ok</html>")
            }
        }

        assertEquals("<html>ok</html>", TestClient(null).fetch(siteUrl("/x")).trim())
        assertEquals(2, attempts.get())
    }
}
