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
import kotlin.random.Random
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

    protected fun requestBuilder(url: String, referer: String? = null): HttpRequest.Builder {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .timeout(Duration.ofSeconds(30))
        if (!referer.isNullOrBlank()) builder.header("Referer", referer)
        return builder
    }

    /**
     * Fetches [url], retrying with backoff if the site serves a Cloudflare
     * "managed challenge" interstitial (the `Just a moment...` page). A plain
     * HTTP client can't execute the challenge's JS/proof-of-work, so retrying
     * can't force a pass the way a real browser would - once retries are
     * exhausted, [solveWithFlareSolverr] is tried as a last resort if
     * configured; only if that also comes up empty do we raise a distinct,
     * actionable error instead of quietly returning (or worse, parsing) the
     * interstitial HTML as if it were the real page.
     */
    protected fun get(url: String, referer: String? = null): String {
        lateinit var response: HttpResponse<String>
        for (attempt in 1..MAX_FETCH_ATTEMPTS) {
            val request = requestBuilder(url, referer).GET().build()
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (!isCloudflareChallenge(response)) break
            if (attempt == MAX_FETCH_ATTEMPTS) {
                return solveWithFlareSolverr(url) ?: error(
                    "$sourceName: blocked by a Cloudflare challenge fetching $url after $MAX_FETCH_ATTEMPTS attempts" +
                        if (flareSolverrUrl == null) "" else " (FlareSolverr fallback also failed)",
                )
            }
            logger.warn("$sourceName: Cloudflare challenge fetching $url (attempt $attempt/$MAX_FETCH_ATTEMPTS), retrying")
            Thread.sleep(challengeRetryDelayMs(attempt, response))
        }
        if (response.statusCode() !in 200..299) {
            error("HTTP ${response.statusCode()} fetching $url")
        }
        return response.body()
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

    /** Backoff delay before retrying a challenged request: honors a
     * `Retry-After` header if Cloudflare sent one, otherwise exponential
     * backoff from [BASE_RETRY_DELAY_MS] with random jitter so concurrent
     * retries don't all land on the same instant. */
    private fun challengeRetryDelayMs(attempt: Int, response: HttpResponse<String>): Long {
        val retryAfterSeconds = response.headers().firstValue("retry-after").orElse(null)?.toLongOrNull()
        if (retryAfterSeconds != null) return retryAfterSeconds * 1000

        val backoff = BASE_RETRY_DELAY_MS * (1L shl (attempt - 1))
        return backoff.coerceAtMost(MAX_RETRY_DELAY_MS) + Random.nextLong(JITTER_MS)
    }

    @Serializable
    private data class FlareSolverrRequest(val cmd: String, val url: String, val maxTimeout: Int)

    @Serializable
    private data class FlareSolverrResponse(val status: String, val message: String = "", val solution: FlareSolverrSolution? = null)

    @Serializable
    private data class FlareSolverrSolution(val response: String, val cookies: List<FlareSolverrCookie> = emptyList())

    @Serializable
    private data class FlareSolverrCookie(
        val name: String,
        val value: String,
        val domain: String,
        val path: String = "/",
        val secure: Boolean = false,
        @SerialName("httpOnly") val httpOnly: Boolean = false,
    )

    private val flareSolverrJson = Json { ignoreUnknownKeys = true }

    /**
     * Last-resort fallback for [get] once its own retries are exhausted:
     * asks FlareSolverr (a sidecar that drives a real headless browser
     * specifically to clear Cloudflare challenges) to fetch [url] itself.
     * Returns null - rather than throwing - on any failure (not configured,
     * unreachable, couldn't solve it) so [get] can fall back to its normal
     * "still challenged" error instead of a confusing FlareSolverr-shaped
     * one.
     *
     * The solved page's cookies (typically including a fresh `cf_clearance`)
     * are merged into the shared cookie jar before returning, so it's not just
     * this one response that benefits - every later request through this
     * client reuses the same jar. FlareSolverr's own request isn't retried;
     * if it fails, the normal retry loop in [get] will simply hit it again
     * next time [get] is called.
     */
    private fun solveWithFlareSolverr(url: String): String? {
        val endpoint = flareSolverrUrl ?: return null
        return try {
            logger.info("$sourceName: still challenged after $MAX_FETCH_ATTEMPTS attempts, falling back to FlareSolverr for $url")
            val requestBody = flareSolverrJson.encodeToString(
                FlareSolverrRequest.serializer(),
                FlareSolverrRequest(cmd = "request.get", url = url, maxTimeout = FLARESOLVERR_TIMEOUT_MS),
            )
            val request = HttpRequest.newBuilder(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(FLARESOLVERR_TIMEOUT_MS + 10_000L))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                logger.warn("$sourceName: FlareSolverr returned HTTP ${response.statusCode()} for $url")
                return null
            }

            val parsed = flareSolverrJson.decodeFromString(FlareSolverrResponse.serializer(), response.body())
            val solution = parsed.solution
            if (parsed.status != "ok" || solution == null) {
                logger.warn("$sourceName: FlareSolverr couldn't solve the challenge for $url: ${parsed.message}")
                return null
            }

            mergeFlareSolverrCookies(solution.cookies)
            logger.info("$sourceName: FlareSolverr solved the challenge for $url")
            solution.response
        } catch (e: Exception) {
            logger.warn("$sourceName: FlareSolverr fallback failed for $url: $e")
            null
        }
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

    protected fun parse(html: String, baseUrl: String): Document = Jsoup.parse(html, baseUrl)

    /** Streams [url] to [destination]. If [referer] is provided, it will be
     * sent as the Referer request header. Returns true on success. */
    fun downloadApk(url: String, destination: File, referer: String? = null): Boolean {
        return try {
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
            true
        } catch (e: Exception) {
            logger.error("$sourceName: error downloading $url: $e")
            destination.delete()
            false
        }
    }

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

        private const val MAX_FETCH_ATTEMPTS = 3
        private const val BASE_RETRY_DELAY_MS = 1_500L
        private const val MAX_RETRY_DELAY_MS = 8_000L
        private const val JITTER_MS = 750L

        // How long FlareSolverr itself is allowed to spend solving a single
        // challenge (its own internal timeout, in ms) - a real headless
        // browser navigation is much slower than our own HTTP requests.
        private const val FLARESOLVERR_TIMEOUT_MS = 60_000
    }
}
