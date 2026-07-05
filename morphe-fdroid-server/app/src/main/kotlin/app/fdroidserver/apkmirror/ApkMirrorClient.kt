package app.fdroidserver.apkmirror

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.StandardOpenOption
import java.time.Duration
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Scrapes APKMirror app pages to discover versions and resolve a direct
 * download URL, and downloads the resulting APK. Direct Kotlin port of the
 * old Python `apkmirror.py`, using Jsoup instead of BeautifulSoup (the CSS
 * selectors translate close to verbatim) and the JDK's own `java.net.http`
 * client instead of `requests`.
 *
 * APKMirror has no official API. This is inherently fragile (breaks
 * whenever APKMirror changes its markup) and scraping is against
 * APKMirror's Terms of Service - use at your own risk (same caveat as the
 * Python version carried).
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

    private fun get(url: String, referer: String? = null): String {
        val request = requestBuilder(url, referer).GET().build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            error("HTTP ${response.statusCode()} fetching $url")
        }
        return response.body()
    }

    private fun parse(html: String, baseUrl: String): Document = Jsoup.parse(html, baseUrl)

    /** Returns app versions on an APKMirror app listing page, newest first,
     * as they appear on the page. */
    fun getVersions(appUrl: String): List<VersionEntry> = getVersionsPage(appUrl, page = 1)

    private fun getVersionsPage(appUrl: String, page: Int): List<VersionEntry> {
        val url = if (page <= 1) appUrl else appendQueryParam(appUrl, "page", page.toString())
        val doc = parse(get(url), url)
        val seen = mutableSetOf<String>()
        val versions = mutableListOf<VersionEntry>()

        for (row in doc.select("div.appRow")) {
            val link = row.selectFirst("h5.appRowTitle a[href]") ?: continue
            val pageUrl = link.absUrl("href")
            val text = link.text().trim()
            val version = VERSION_RE.find(text)?.groupValues?.get(1) ?: text
            if (!seen.add(version)) continue
            versions.add(VersionEntry(version, pageUrl))
        }

        logger.info("apkmirror: found ${versions.size} version(s) on $url")
        return versions
    }

    /** Looks for [version] beyond what [getVersions] already returned, by
     * paging through the app's older-releases listing (APKMirror only shows
     * the newest handful on the first page). Used when a patch's
     * `supported_versions` pins a version that has since scrolled off the
     * first page. Returns null if it isn't found within [maxPages]. */
    fun findVersion(appUrl: String, version: String, maxPages: Int = 5): VersionEntry? {
        for (page in 2..maxPages) {
            val versions = getVersionsPage(appUrl, page)
            if (versions.isEmpty()) return null
            versions.firstOrNull { it.version == version }?.let { return it }
        }
        return null
    }

    private fun appendQueryParam(url: String, key: String, value: String): String {
        val separator = if ("?" in url) "&" else "?"
        return "$url$separator$key=$value"
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

        private val VERSION_RE = Regex("""(\d+(?:\.\d+){1,3})""")
    }
}
