package app.fdroidserver.apkmirror

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.io.File
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.StandardOpenOption
import java.time.Duration
import kotlin.random.Random
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Discovers APKMirror app versions from its RSS feed and resolves/downloads
 * the actual APK by scraping the version/variant/download pages. Direct
 * Kotlin port of the old Python `apkmirror.py`'s download-resolution logic,
 * using Jsoup instead of BeautifulSoup (the CSS selectors translate close to
 * verbatim) and the JDK's own `java.net.http` client instead of `requests`;
 * version discovery itself has since moved off HTML scraping onto the feed
 * (see [getVersions]).
 *
 * APKMirror has no official API. Both the feed and the page-scraping
 * fallbacks are inherently fragile (break whenever APKMirror changes its
 * markup) and scraping is against APKMirror's Terms of Service - use at your
 * own risk (same caveat as the Python version carried).
 */
class ApkMirrorClient(private val logger: Logger = LoggerFactory.getLogger(ApkMirrorClient::class.java.name)) {

    data class VersionEntry(val version: String, val pageUrl: String)

    // APKMirror is fronted by Cloudflare, which gates the final asset link on
    // both a browser-like header set *and* the session cookies handed out
    // while browsing to it (e.g. a bot-check clearance cookie). A CookieManager
    // shared across every request - including the final APK download - is
    // required, or the asset request comes in "cookieless" and gets a 403
    // even though the same URL works fine in a browser that already holds
    // those cookies.
    private val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ALL)

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .cookieHandler(cookieManager)
        .build()

    private fun requestBuilder(url: String, referer: String? = null): HttpRequest.Builder {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .timeout(Duration.ofSeconds(30))
        if (!referer.isNullOrBlank()) builder.header("Referer", referer)
        return builder
    }

    /**
     * Fetches [url], retrying with backoff if APKMirror serves a Cloudflare
     * "managed challenge" interstitial (the `Just a moment...` page also
     * seen while preparing the RSS-feed fixtures for `ApkMirrorClientTest` -
     * it showed up intermittently on the same feed URL that worked moments
     * earlier, so it reads as rate-limiting/heuristic bot-scoring rather than
     * a hard per-URL block). A plain HTTP client can't execute the
     * challenge's JS/proof-of-work, so retrying can't force a pass the way a
     * real browser would - but backing off and trying again a few times
     * recovers the transient cases, and failing that we raise a distinct,
     * actionable error instead of quietly returning (or worse, parsing) the
     * interstitial HTML as if it were the real page.
     */
    private fun get(url: String, referer: String? = null): String {
        lateinit var response: HttpResponse<String>
        for (attempt in 1..MAX_FETCH_ATTEMPTS) {
            val request = requestBuilder(url, referer).GET().build()
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (!isCloudflareChallenge(response)) break
            if (attempt == MAX_FETCH_ATTEMPTS) {
                error("apkmirror: blocked by a Cloudflare challenge fetching $url after $MAX_FETCH_ATTEMPTS attempts")
            }
            logger.warn("apkmirror: Cloudflare challenge fetching $url (attempt $attempt/$MAX_FETCH_ATTEMPTS), retrying")
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

    private fun parse(html: String, baseUrl: String): Document = Jsoup.parse(html, baseUrl)

    /**
     * Returns app versions from APKMirror's RSS feed (`{appUrl}/feed/`),
     * newest first. Reads the version straight out of the feed's `<title>`
     * text (see [parseFeedVersions]), so suffixes the old HTML-listing
     * regex used to truncate - " beta", "+rc3-2026.06.24", " (Android TV)" -
     * come through intact, matching whatever a patch's `supported_versions`
     * entry pins. The feed only exposes the newest ~10 releases - APKMirror
     * has no pagination for it, unlike the HTML listing this replaced - so
     * there's no way to look further back for an older pinned version.
     *
     * Before fetching the feed, visits [appUrl] itself first - hitting
     * `/feed/` stone cold, with no prior page visit and no Referer, looks
     * more like a scraper to Cloudflare's bot-scoring than a client that
     * browsed there normally (this is likely why the Uber Eats feed
     * intermittently challenged us while preparing the test fixtures for
     * `ApkMirrorClientTest`, while the Disney+ ones didn't). The warm-up
     * request goes through the same shared [httpClient]/[cookieManager] as
     * every other request here - including [downloadApk] - so any cookies
     * APKMirror hands out on the app page carry over to the feed request,
     * which also sends [appUrl] as its Referer. If the warm-up itself gets
     * stuck behind a challenge [get] can't clear, we don't let that sink the
     * whole call - fall through and try the feed directly, since it may not
     * need the same cookies/referer to go through.
     */
    fun getVersions(appUrl: String): List<VersionEntry> {
        runCatching { get(appUrl) }.onFailure {
            logger.debug("apkmirror: warm-up request to $appUrl failed, fetching feed directly instead: $it")
        }

        val url = appUrl.trimEnd('/') + "/feed/"
        val versions = parseFeedVersions(get(url, referer = appUrl))
        logger.info("apkmirror: found ${versions.size} version(s) on $url")
        logger.debug("apkmirror: versions on $url: ${versions.joinToString { it.version }}")
        return versions
    }

    /**
     * Pure feed-parsing logic, pulled out of [getVersions] so it's
     * unit-testable against saved feed XML without a network call (see
     * `ApkMirrorClientTest`).
     *
     * APKMirror's feed `<channel><title>` is always
     * "Download {app_name} APKs for Android - APKMirror", and each
     * `<item><title>` is "{app_name} (variant) {version} by {company}" with
     * the "(variant)" segment - e.g. "(Android TV)", "(Fire TV)" - present
     * only for apps that ship a device-specific listing. Anchoring the
     * item-title regex on the app name pulled from the channel title (rather
     * than guessing at where the version starts) is what lets the version
     * capture group run all the way to " by ", keeping beta/rc/date suffixes
     * as part of the version instead of truncating them.
     */
    internal fun parseFeedVersions(feedXml: String): List<VersionEntry> {
        val doc = Jsoup.parse(feedXml, "", Parser.xmlParser())
        val channelTitle = doc.selectFirst("channel > title")?.text().orEmpty()
        val appName = CHANNEL_TITLE_RE.find(channelTitle)?.groupValues?.get(1) ?: return emptyList()
        val itemTitleRe = Regex("^${Regex.escape(appName)}(?: \\([^)]*\\))? (.+) by .+$")

        return doc.select("item").mapNotNull { item ->
            val title = item.selectFirst("title")?.text() ?: return@mapNotNull null
            val link = item.selectFirst("link")?.text()?.trim() ?: return@mapNotNull null
            val version = itemTitleRe.find(title)?.groupValues?.get(1) ?: return@mapNotNull null
            VersionEntry(version, link)
        }
    }

    /** From a version page, picks a variant (preferring a plain APK over an
     * .apkm bundle, and arm64-v8a/universal over other architectures within
     * that) and returns its download page URL. */
    private fun findVariantDownloadPage(versionPageUrl: String): String? =
        pickBestVariantHref(parse(get(versionPageUrl), versionPageUrl))

    internal data class VariantCandidate(
        val isApk: Boolean,
        val isArm64OrUniversal: Boolean,
        val isBundle: Boolean,
        val href: String,
    )

    /**
     * Pure selection logic, pulled out of [findVariantDownloadPage] so it's
     * unit-testable against synthetic HTML fixtures without a network call
     * (see `ApkMirrorClientTest`). Prefers a plain APK variant (ideally
     * universal/arm64-v8a); if the app only ships an .apkm BUNDLE on
     * APKMirror (common for large apps), falls back to that - BundleMerger
     * merges bundles into a single APK before patching, so a bundle variant
     * is still usable, just less preferred.
     */
    internal fun pickBestVariantHref(doc: Document): String? {
        val rows = doc.select("div.variants-table div.table-row").ifEmpty { doc.select("div.table-row") }
        if (rows.isEmpty()) return null

        val candidates = rows.mapNotNull { row ->
            val link = row.selectFirst("a.accent_color[href]") ?: return@mapNotNull null
            val href = link.absUrl("href")
            val rowText = row.text().lowercase()
            val isBundle = "bundle" in rowText
            val isApk = "apk" in rowText && !isBundle
            val isArm64OrUniversal = "universal" in rowText || "noarch" in rowText || "arm64-v8a" in rowText
            VariantCandidate(isApk, isArm64OrUniversal, isBundle, href)
        }
        if (candidates.isEmpty()) return null

        return candidates.sortedWith(
            compareBy({ !it.isApk }, { !it.isArm64OrUniversal }, { !it.isBundle })
        ).first().href
    }

    /** Result of resolving a download: the final direct URL plus an optional
     * referer value (the download page that led to the asset). Some CDNs/hosts
     * reject direct requests without a browser Referer header, so we return
     * it alongside the URL so the downloader can send it back. */
    data class DownloadInfo(val url: String, val referer: String?)

    /** Follows the variant page -> download page -> final asset URL chain
     * and returns a [DownloadInfo] containing the direct APK URL and the
     * download page to use as a Referer, or null if it couldn't be resolved. */
    fun resolveDownloadUrl(versionPageUrl: String): DownloadInfo? {
        val variantPage = findVariantDownloadPage(versionPageUrl)
        if (variantPage == null) {
            logger.warn("apkmirror: no variant found for $versionPageUrl")
            return null
        }

        val variantDoc = parse(get(variantPage, referer = versionPageUrl), variantPage)
        val downloadLink = variantDoc.selectFirst("a#downloadButton[href]")
            ?: variantDoc.selectFirst("a.downloadButton[href]")
        if (downloadLink == null) {
            logger.warn("apkmirror: no download button found on $variantPage")
            return null
        }
        val downloadPage = downloadLink.absUrl("href")

        val downloadDoc = parse(get(downloadPage, referer = variantPage), downloadPage)
        val finalLink = downloadDoc.selectFirst("a[rel=nofollow][href*=download.php]")
        if (finalLink == null) {
            logger.warn("apkmirror: no final download link found on $downloadPage")
            return null
        }
        // Return both the final URL and the page we came from so callers can
        // set a proper Referer header when performing the actual GET.
        return DownloadInfo(finalLink.absUrl("href"), downloadPage)
    }

    /** Streams [url] to [destination]. If [referer] is provided, it will be
     * sent as the Referer request header. Returns true on success. */
    fun downloadApk(url: String, destination: File, referer: String? = null): Boolean {
        return try {
            // Use the same HttpClient (and thus the same cookie jar) that
            // resolved the download chain, rather than a bare URLConnection -
            // that's what makes the final asset request look like a
            // continuation of the browsing session instead of a cookieless,
            // out-of-nowhere request that APKMirror/Cloudflare reject with 403.
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
            logger.error("apkmirror: error downloading $url: $e")
            destination.delete()
            false
        }
    }

    companion object {
        // APKMirror blocks requests with non-browser User-Agents.
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val CHANNEL_TITLE_RE = Regex("""^Download (.+) APKs for Android - APKMirror$""")

        // Matches Cloudflare's "Just a moment..." managed-challenge interstitial,
        // as opposed to the real page - seen in practice on both the HTML
        // listing pages and the RSS feed.
        private val CLOUDFLARE_CHALLENGE_RE = Regex("""<title>Just a moment\.\.\.</title>|_cf_chl_opt|challenges\.cloudflare\.com""")

        private const val MAX_FETCH_ATTEMPTS = 3
        private const val BASE_RETRY_DELAY_MS = 1_500L
        private const val MAX_RETRY_DELAY_MS = 8_000L
        private const val JITTER_MS = 750L
    }
}
