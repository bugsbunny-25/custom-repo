package app.fdroidserver.scraper

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpCookie
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.zip.ZipFile
import org.slf4j.Logger

/**
 * Shared scraping infrastructure for the APK-source clients
 * ([app.fdroidserver.apkmirror.ApkMirrorClient] and
 * [app.fdroidserver.apkpure.ApkPureClient]): the browser-like HTTP client,
 * the shared cookie jar, Cloudflare "managed challenge" detection + retry,
 * the FlareSolverr last-resort fallback, and the final APK download. Only the
 * source-specific bits - how versions are discovered ([getVersions]) and how a
 * version page resolves to a direct download link ([resolveDownloadUrl]) -
 * live in the subclasses; everything both sites need to get *past Cloudflare*
 * is here so it isn't duplicated (and so a fix to the challenge/cookie
 * handling benefits both sites at once).
 *
 * Both APKMirror and APKPure are fronted by Cloudflare and both were observed
 * serving the identical `Just a moment...` interstitial, so the same
 * detection/retry/FlareSolverr machinery applies verbatim to each. Scraping
 * either site is inherently fragile (breaks whenever they change their markup)
 * and is against their Terms of Service - use at your own risk.
 *
 * When a Cloudflare challenge outlasts [get]'s own retries, and a
 * FlareSolverr instance is configured via [flareSolverrUrl], falls back to
 * it as a last resort: FlareSolverr drives a real headless browser that can
 * actually execute the challenge's JS, then hands back the solved page plus
 * the session cookies (`cf_clearance` etc.) it earned. Those cookies get
 * merged into the shared cookie jar, so it isn't just that one request that
 * benefits - every subsequent request in this client reuses the same jar.
 *
 * [flareSolverrUrl] is a `var`, not fixed at construction, because it's
 * meant to be sourced from the admin UI's Settings page (stored in the DB
 * via `AppConfig`/`Schema.Settings.flareSolverrUrl`) rather than a Docker
 * env var - `PatchScheduler` re-reads the current setting and assigns it
 * onto each client before each patch-check sweep, so a value changed in
 * Settings takes effect on the next run without restarting the app.
 */
abstract class ScraperClient(
    protected val logger: Logger,
    flareSolverrUrl: String? = null,
) {

    /** Short label used to prefix this source's log/error messages
     * (e.g. `"apkmirror"`, `"apkpure"`). */
    protected abstract val sourceName: String

    /** A discovered app version and the page URL that resolves to its
     * download (a version release page on APKMirror, a `/download/{version}`
     * page on APKPure). */
    data class VersionEntry(val version: String, val pageUrl: String)

    /** Result of resolving a download: the final direct URL plus an optional
     * referer value (the download page that led to the asset). Some CDNs/hosts
     * reject direct requests without a browser Referer header, so we return
     * it alongside the URL so the downloader can send it back. */
    data class DownloadInfo(val url: String, val referer: String?)

    /** Discovers the app's available versions, newest first. */
    abstract fun getVersions(appUrl: String): List<VersionEntry>

    /** Follows the version page's download chain to a direct APK URL, or null
     * if it couldn't be resolved. */
    abstract fun resolveDownloadUrl(versionPageUrl: String): DownloadInfo?

    /** Only ever holds a value [isValidFlareSolverrUrl] would accept for a
     * FlareSolverr endpoint - assigning a blank or malformed value disables
     * the fallback (stores null) rather than failing later with a confusing
     * "couldn't connect"-style error from a URL that was never going to work. */
    var flareSolverrUrl: String? = flareSolverrUrl?.trim()?.takeIf { isValidFlareSolverrUrl(it) }
        set(value) {
            field = value?.trim()?.takeIf { isValidFlareSolverrUrl(it) }
        }

    // APKMirror/APKPure are fronted by Cloudflare, which gates the final asset
    // link on both a browser-like header set *and* the session cookies handed
    // out while browsing to it (e.g. a bot-check clearance cookie). A
    // CookieManager shared across every request - including the final APK
    // download - is required, or the asset request comes in "cookieless" and
    // gets a 403 even though the same URL works fine in a browser that already
    // holds those cookies.
    private val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ALL)

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .cookieHandler(cookieManager)
        .build()

    /**
     * The User-Agent every request goes out with.
     *
     * This is a `var`, and that is the whole point. Cloudflare binds a
     * `cf_clearance` cookie to the exact User-Agent that earned it;
     * FlareSolverr's own README says so outright: "If you want to use
     * Cloudflare clearance cookie in your scripts, make sure you use the
     * FlareSolverr User-Agent too. If they don't match you will see the
     * challenge."
     *
     * Before this was here, [solveWithFlareSolverr] merged the solved
     * cookies into the jar but kept sending [USER_AGENT], so the clearance
     * cookie was rejected on the very next request - which meant every
     * single page fetch was challenged and every single one of them paid for
     * a fresh headless-browser solve. Adopting the UA FlareSolverr reports
     * is what lets one solve cover the whole clearance window instead of one
     * request.
     */
    @Volatile
    protected var userAgent: String = USER_AGENT
        private set

    protected fun requestBuilder(url: String, referer: String? = null): HttpRequest.Builder {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", userAgent)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .timeout(Duration.ofSeconds(30))
        if (!referer.isNullOrBlank()) builder.header("Referer", referer)
        return builder
    }

    /**
     * Fetches [url].
     *
     * A Cloudflare "managed challenge" interstitial (the `Just a moment...`
     * page) hands straight over to [solveWithFlareSolverr]: a plain HTTP
     * client cannot execute the challenge's JS, so retrying it is futile.
     * Only if that also comes up empty do we raise a distinct, actionable
     * error rather than quietly returning (or worse, parsing) the
     * interstitial HTML as if it were the real page.
     *
     * A *transport-level* failure is different, and is retried once. An
     * upstream session proxy that has expired its session answers with
     * something like `410 Gone`, and the next request through it gets a
     * fresh session and succeeds. APKMirror's flow happened to survive that
     * by accident - its warm-up request absorbed the stale-session response
     * inside a `runCatching` and the feed request that followed went
     * through - while APKPure's single `get` for the versions page had no
     * second chance and failed the whole version check (seen on Prime
     * Video). Putting the retry here, in the shared path, makes the two
     * symmetric by construction instead of leaving it to whichever client
     * happens to make two requests.
     */
    protected fun get(url: String, referer: String? = null): String {
        var lastStatus = -1
        for (attempt in 1..TRANSPORT_ATTEMPTS) {
            val request = requestBuilder(url, referer).GET().build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            if (isCloudflareChallenge(response)) {
                return solveWithFlareSolverr(url) ?: error(
                    "$sourceName: blocked by a Cloudflare challenge fetching $url" +
                        if (flareSolverrUrl == null) {
                            " (no FlareSolverr configured - set one in Settings)"
                        } else {
                            " (FlareSolverr fallback also failed)"
                        },
                )
            }

            if (response.statusCode() in 200..299) return response.body()

            lastStatus = response.statusCode()
            if (lastStatus !in RETRYABLE_STATUSES || attempt == TRANSPORT_ATTEMPTS) break
            logger.info("$sourceName: HTTP $lastStatus fetching $url - retrying once (likely an expired upstream session)")
            Thread.sleep(TRANSPORT_RETRY_DELAY_MS)
        }
        error("HTTP $lastStatus fetching $url")
    }

    /** Detects Cloudflare's interstitial rather than the real page: either
     * the `cf-mitigated: challenge` response header Cloudflare adds to
     * challenged responses, or the `Just a moment...` challenge page's
     * markup (checked on the body since the header isn't always present -
     * some challenge modes only show up in the HTML). */
    private fun isCloudflareChallenge(response: HttpResponse<String>): Boolean {
        if (response.headers().firstValue("cf-mitigated").orElse("").equals("challenge", ignoreCase = true)) {
            return true
        }
        return CLOUDFLARE_CHALLENGE_RE.containsMatchIn(response.body())
    }

    // There is deliberately no retry-with-backoff before falling back. A
    // plain HTTP client cannot execute a managed challenge's JavaScript, so
    // retrying one is futile by construction - it just adds latency before
    // the only thing that can actually clear it. (The previous code had a
    // full exponential-backoff path here, but MAX_FETCH_ATTEMPTS was 1, so
    // none of it was ever reachable.)

    @Serializable
    private data class FlareSolverrRequest(
        val cmd: String,
        val url: String? = null,
        val maxTimeout: Int? = null,
        /** Reuses an existing browser instance instead of launching and
         * destroying Chrome per request - see [ensureSession]. */
        val session: String? = null,
        @SerialName("session_ttl_minutes")
        val sessionTtlMinutes: Int? = null,
        /** Skips images, CSS and fonts. Nothing here parses them, and they
         * are a large part of what a headless-browser navigation costs. */
        val disableMedia: Boolean? = null,
    )

    @Serializable
    private data class FlareSolverrResponse(
        val status: String,
        val message: String = "",
        val solution: FlareSolverrSolution? = null,
        /** Returned by `sessions.create`. */
        val session: String? = null,
    )

    @Serializable
    private data class FlareSolverrSolution(
        val response: String,
        val cookies: List<FlareSolverrCookie> = emptyList(),
        /**
         * The UA the headless browser actually used. Previously dropped on
         * the floor by `ignoreUnknownKeys`, which is precisely why the
         * clearance cookies alongside it never worked - see [userAgent].
         */
        val userAgent: String? = null,
    )

    @Serializable
    private data class FlareSolverrCookie(
        val name: String,
        val value: String,
        val domain: String,
        val path: String = "/",
        val secure: Boolean = false,
        @SerialName("httpOnly") val httpOnly: Boolean = false,
    )

    // explicitNulls = false so the optional fields of FlareSolverrRequest are
    // omitted rather than sent as JSON nulls; encodeDefaults = false for the
    // same reason. FlareSolverr reads these with Python's json module, and
    // an explicit `"session": null` is not the same thing to it as an absent
    // key.
    private val flareSolverrJson = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    /**
     * The FlareSolverr session this client reuses, or null if sessions are
     * not working (an older FlareSolverr, or creation failed) and each
     * request should launch its own throwaway browser.
     *
     * Named after the source rather than randomly generated, deliberately:
     * each patch job runs in its own short-lived JVM
     * ([app.fdroidserver.patching.PatchWorkerLauncher]), so a per-process
     * random id would mean a brand-new Chrome instance for every job. A
     * stable name lets those processes land on the browser the previous one
     * already got clearance in.
     */
    @Volatile
    private var flareSolverrSession: String? = null

    @Volatile
    private var sessionAttempted = false

    /**
     * Last-resort fallback for [get]: asks FlareSolverr (a sidecar that
     * drives a real headless browser specifically to clear Cloudflare
     * challenges) to fetch [url] itself. Returns null - rather than throwing
     * - on any failure (not configured, unreachable, couldn't solve it) so
     * [get] can report its own "still challenged" error instead of a
     * confusing FlareSolverr-shaped one.
     *
     * Two things are carried back out of the solution, and both matter:
     * the cookies (a fresh `cf_clearance`) *and* the User-Agent the browser
     * used. Taking only the cookies - which is what this did before - leaves
     * them unusable, because Cloudflare ties the clearance cookie to the UA
     * that earned it. See [userAgent].
     *
     * Runs inside a reused browser session where possible, so the cost is
     * one navigation rather than a full Chrome start-up and teardown.
     */
    private fun solveWithFlareSolverr(url: String): String? {
        val endpoint = flareSolverrUrl ?: return null
        logger.info("$sourceName: Cloudflare challenge, falling back to FlareSolverr for $url")

        val session = ensureSession(endpoint)
        val solved = requestViaFlareSolverr(endpoint, url, session)
        if (solved != null) return solved

        // A session that FlareSolverr no longer knows about (it restarted, or
        // rotated the session on its TTL) fails every request until it is
        // recreated, so one retry with a fresh session is worth it before
        // giving up. Without a session there is nothing to recreate, so the
        // first failure is final.
        if (session == null) return null
        logger.info("$sourceName: retrying through a fresh FlareSolverr session")
        flareSolverrSession = null
        sessionAttempted = false
        val retrySession = ensureSession(endpoint)
        return requestViaFlareSolverr(endpoint, url, retrySession)
    }

    /** One `request.get`. Null on any failure. */
    private fun requestViaFlareSolverr(endpoint: String, url: String, session: String?): String? = try {
        val parsed = postToFlareSolverr(
            endpoint,
            FlareSolverrRequest(
                cmd = "request.get",
                url = url,
                maxTimeout = FLARESOLVERR_TIMEOUT_MS,
                session = session,
                disableMedia = true,
            ),
        )
        val solution = parsed?.solution
        if (parsed == null || parsed.status != "ok" || solution == null) {
            logger.warn("$sourceName: FlareSolverr couldn't solve the challenge for $url: ${parsed?.message ?: "no response"}")
            null
        } else {
            adoptSolution(solution)
            logger.info("$sourceName: FlareSolverr solved the challenge for $url")
            solution.response
        }
    } catch (e: Exception) {
        logger.warn("$sourceName: FlareSolverr fallback failed for $url: $e")
        null
    }

    /**
     * Takes the clearance cookies *and* the User-Agent from a solved page.
     *
     * Both or neither: a clearance cookie without its matching UA is worse
     * than useless, because it looks like it should work and silently does
     * not.
     */
    private fun adoptSolution(solution: FlareSolverrSolution) {
        mergeFlareSolverrCookies(solution.cookies)
        val solvedUserAgent = solution.userAgent?.trim()
        if (!solvedUserAgent.isNullOrEmpty() && solvedUserAgent != userAgent) {
            logger.info("$sourceName: adopting FlareSolverr's User-Agent so its clearance cookie is accepted")
            userAgent = solvedUserAgent
        }
    }

    /**
     * The session id to send, creating the session on first use.
     *
     * Returns null if sessions are unavailable, in which case callers fall
     * back to a sessionless request - FlareSolverr then spins up a
     * throwaway browser per request, which is what this is trying to avoid
     * but still works.
     */
    private fun ensureSession(endpoint: String): String? {
        flareSolverrSession?.let { return it }
        if (sessionAttempted) return null
        sessionAttempted = true

        val name = "custom-repo-$sourceName"
        val parsed = try {
            postToFlareSolverr(
                endpoint,
                FlareSolverrRequest(
                    cmd = "sessions.create",
                    session = name,
                    sessionTtlMinutes = SESSION_TTL_MINUTES,
                ),
            )
        } catch (e: Exception) {
            logger.warn("$sourceName: couldn't create a FlareSolverr session ($e) - falling back to per-request browsers")
            null
        }

        if (parsed == null) return null

        if (parsed.status == "ok") {
            val created = parsed.session ?: name
            logger.info("$sourceName: using FlareSolverr session '$created' (TTL ${SESSION_TTL_MINUTES}m)")
            flareSolverrSession = created
            return created
        }

        // A session of this name already exists - which is the normal case
        // for a second process, and exactly what the stable name is for.
        if (parsed.message.contains("already exists", ignoreCase = true)) {
            logger.info("$sourceName: reusing existing FlareSolverr session '$name'")
            flareSolverrSession = name
            return name
        }

        logger.warn("$sourceName: FlareSolverr refused to create a session: ${parsed.message}")
        return null
    }

    private fun postToFlareSolverr(endpoint: String, body: FlareSolverrRequest): FlareSolverrResponse? {
        val request = HttpRequest.newBuilder(URI.create(endpoint))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofMillis(FLARESOLVERR_TIMEOUT_MS + 10_000L))
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    flareSolverrJson.encodeToString(FlareSolverrRequest.serializer(), body),
                ),
            )
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            logger.warn("$sourceName: FlareSolverr returned HTTP ${response.statusCode()} for ${body.cmd}")
            return null
        }
        return flareSolverrJson.decodeFromString(FlareSolverrResponse.serializer(), response.body())
    }

    private fun mergeFlareSolverrCookies(cookies: List<FlareSolverrCookie>) {
        for (cookie in cookies) {
            val domain = cookie.domain.removePrefix(".")
            val httpCookie = HttpCookie(cookie.name, cookie.value).apply {
                this.domain = domain
                path = cookie.path
                secure = cookie.secure
                isHttpOnly = cookie.httpOnly
                // HttpCookie defaults to RFC 2965 (version 1), which sends
                // Cookie: name="value";$Path="...";$Domain="..." - the site
                // (like most servers) expects the plain Netscape-style
                // name=value pair instead, so it'd otherwise silently ignore
                // this cookie on the next request.
                version = 0
            }
            cookieManager.cookieStore.add(URI("https://$domain${cookie.path}"), httpCookie)
        }
    }

    /**
     * Whether this client already holds a Cloudflare clearance cookie that
     * covers [url]'s host.
     *
     * Used by the subclasses to skip their "warm-up" page fetch. That
     * warm-up exists to look like a browser that navigated to the feed
     * rather than one that arrived cold, but it is a whole extra page fetch
     * per app per sweep - and while a challenge is in force, a whole extra
     * headless-browser solve. Once clearance is held there is nothing to warm
     * up, so the request is pure cost.
     */
    protected fun hasClearance(url: String): Boolean {
        val host = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: return false
        return cookieManager.cookieStore.cookies.any { cookie ->
            if (!cookie.name.equals(CLEARANCE_COOKIE, ignoreCase = true)) return@any false
            if (cookie.hasExpired()) return@any false
            val domain = cookie.domain?.removePrefix(".")?.lowercase() ?: return@any false
            host == domain || host.endsWith(".$domain")
        }
    }

    protected fun parse(html: String, baseUrl: String): Document = Jsoup.parse(html, baseUrl)

    /** Streams [url] to [destination]. If [referer] is provided, it will be
     * sent as the Referer request header. Returns true on success.
     *
     * A download only counts if it's complete and actually an APK/bundle:
     * the byte count has to match `Content-Length` when the server sends one,
     * and the file has to open as a zip (APK, `.apkm`, `.xapk` and `.apks`
     * all are). Without that, a cut-off transfer or an HTML error/challenge
     * page served with a 200 was handed on to the merger and patcher, which
     * then failed with errors that pointed nowhere near the download. One
     * retry covers a transfer dropped mid-way. */
    fun downloadApk(url: String, destination: File, referer: String? = null): Boolean {
        for (attempt in 1..DOWNLOAD_ATTEMPTS) {
            try {
                downloadOnce(url, destination, referer)
                return true
            } catch (e: Exception) {
                logger.error("$sourceName: error downloading $url (attempt $attempt/$DOWNLOAD_ATTEMPTS): $e")
                destination.delete()
            }
        }
        return false
    }

    private fun downloadOnce(url: String, destination: File, referer: String?) {
        // Use the same HttpClient (and thus the same cookie jar) that
        // resolved the download chain, rather than a bare URLConnection -
        // that's what makes the final asset request look like a
        // continuation of the browsing session instead of a cookieless,
        // out-of-nowhere request that Cloudflare rejects with 403.
        val request = requestBuilder(url, referer).GET().build()
        destination.parentFile?.mkdirs()
        val response = httpClient.send(
            request,
            HttpResponse.BodyHandlers.ofFile(
                destination.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            ),
        )
        if (response.statusCode() !in 200..299) {
            error("HTTP ${response.statusCode()} downloading $url")
        }
        val expected = response.headers().firstValueAsLong("Content-Length")
        if (expected.isPresent && expected.asLong != destination.length()) {
            error("incomplete download: got ${destination.length()} of ${expected.asLong} bytes")
        }
        if (!isZipArchive(destination)) {
            val type = response.headers().firstValue("Content-Type").orElse("unknown type")
            error("downloaded file is not an APK or APK bundle ($type, ${destination.length()} bytes)")
        }
    }

    private fun isZipArchive(file: File): Boolean =
        runCatching { ZipFile(file).use { it.size() > 0 } }.getOrDefault(false)

    companion object {
        // Both sites block requests with non-browser User-Agents.
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        /** A FlareSolverr URL is only usable if it parses as an absolute
         * http(s) URL with a host - guards the [flareSolverrUrl] setter
         * against a blank/malformed value saved in Settings, so a typo
         * there disables the fallback instead of being sent as a request
         * target and failing in a more confusing way. */
        fun isValidFlareSolverrUrl(url: String): Boolean {
            val uri = runCatching { URI(url) }.getOrNull() ?: return false
            return uri.isAbsolute && (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()
        }

        // Matches Cloudflare's "Just a moment..." managed-challenge interstitial,
        // as opposed to the real page - seen in practice on both APKMirror and
        // APKPure (HTML listing pages, RSS feeds, and download pages alike).
        private val CLOUDFLARE_CHALLENGE_RE = Regex("""<title>Just a moment\.\.\.</title>|_cf_chl_opt|challenges\.cloudflare\.com""")

        private const val DOWNLOAD_ATTEMPTS = 2

        /**
         * Attempts for a page fetch that fails at the transport level. Two,
         * not more: the case this exists for is a one-shot stale session,
         * where the retry either works immediately or the status is telling
         * the truth.
         */
        private const val TRANSPORT_ATTEMPTS = 2
        private const val TRANSPORT_RETRY_DELAY_MS = 1_000L

        /**
         * Statuses worth one more try.
         *
         * 410 is the important one and the reason this exists: HTTP says
         * "permanently gone", but session proxies in front of these sites use
         * it for "your session expired", and the next request gets a new one.
         * 408/425/429 and the 5xx gateway family are the ordinary transient
         * set. 403 and 404 are deliberately absent - a retry there is just a
         * second helping of the same answer, and 403 in particular is how
         * Cloudflare says no to a cookieless request, which needs a solve
         * rather than a repeat.
         */
        private val RETRYABLE_STATUSES = setOf(408, 410, 425, 429, 500, 502, 503, 504)

        /**
         * How long FlareSolverr keeps our browser session before rotating
         * it. Comfortably longer than a patch sweep so one sweep does not
         * lose its clearance halfway through, and short enough that a
         * session wedged by a site change clears itself out without anyone
         * intervening. FlareSolverr rotates expired sessions itself, so
         * nothing here has to destroy them - which matters because this
         * process may be killed at any time (the patch worker is a
         * short-lived JVM).
         */
        private const val SESSION_TTL_MINUTES = 60

        /** Cloudflare's clearance cookie, which is what a solve earns. */
        private const val CLEARANCE_COOKIE = "cf_clearance"

        // How long FlareSolverr itself is allowed to spend solving a single
        // challenge (its own internal timeout, in ms) - a real headless
        // browser navigation is much slower than our own HTTP requests.
        private const val FLARESOLVERR_TIMEOUT_MS = 60_000
    }
}
