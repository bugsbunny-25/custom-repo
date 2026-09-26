package app.fdroidserver.apkmirror

import app.fdroidserver.scraper.ScraperClient
import app.fdroidserver.scraper.ScraperClient.DownloadInfo
import app.fdroidserver.scraper.ScraperClient.VersionEntry
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Discovers APKMirror app versions from its RSS feed and resolves/downloads
 * the actual APK by scraping the version/variant/download pages. Direct
 * Kotlin port of the old Python `apkmirror.py`'s download-resolution logic,
 * using Jsoup instead of BeautifulSoup (the CSS selectors translate close to
 * verbatim); version discovery itself has since moved off HTML scraping onto
 * the feed (see [getVersions]).
 *
 * All the Cloudflare-challenge handling, FlareSolverr fallback, shared cookie
 * jar, and APK download live in [ScraperClient] (shared with
 * [app.fdroidserver.apkpure.ApkPureClient]); only the APKMirror-specific
 * feed/page parsing is here.
 *
 * APKMirror has no official API. Both the feed and the page-scraping
 * fallbacks are inherently fragile (break whenever APKMirror changes its
 * markup) and scraping is against APKMirror's Terms of Service - use at your
 * own risk (same caveat as the Python version carried).
 */
class ApkMirrorClient(
    logger: Logger = LoggerFactory.getLogger(ApkMirrorClient::class.java.name),
    flareSolverrUrl: String? = null,
) : ScraperClient(logger, flareSolverrUrl) {

    override val sourceName: String = "apkmirror"

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
     * request goes through the same shared client/cookie jar as every other
     * request here - including [downloadApk] - so any cookies APKMirror hands
     * out on the app page carry over to the feed request, which also sends
     * [appUrl] as its Referer. If the warm-up itself gets stuck behind a
     * challenge [get] can't clear, we don't let that sink the whole call -
     * fall through and try the feed directly, since it may not need the same
     * cookies/referer to go through.
     */
    override fun getVersions(appUrl: String): List<VersionEntry> {
        runCatching { get(appUrl) }.onFailure {
            logger.debug("apkmirror: warm-up request to $appUrl failed, fetching feed directly instead: $it")
        }

        val url = appUrl.trimEnd('/') + "/feed/"
        val versions = parseFeedVersions(get(url, referer = appUrl))
        logger.info("apkmirror: found ${versions.size} version(s) on $url")
        logger.info("apkmirror: versions on $url: ${versions.joinToString { it.version }}")
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

    /** From a version page, picks a variant (preferring arm64-v8a/universal
     * over other architectures, and a plain APK over an .apkm bundle within
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
     * (see `ApkMirrorClientTest`). Architecture comes first: every device
     * this repo serves is arm64, so an arm64-v8a/universal variant wins even
     * when it's only offered as an .apkm BUNDLE (BundleMerger merges bundles
     * into a single APK before patching, so that's still usable). Among
     * variants of the same architecture class, a plain APK is preferred.
     * A non-arm64 variant is only picked when nothing else exists, and
     * [app.fdroidserver.patching.NativeAbiCheck] then refuses it before
     * patching unless it turns out to have no native code at all.
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
            compareBy({ !it.isArm64OrUniversal }, { !it.isApk }, { !it.isBundle })
        ).first().href
    }

    /** Follows the variant page -> download page -> final asset URL chain
     * and returns a [DownloadInfo] containing the direct APK URL and the
     * download page to use as a Referer, or null if it couldn't be resolved. */
    override fun resolveDownloadUrl(versionPageUrl: String): DownloadInfo? {
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

    companion object {
        private val CHANNEL_TITLE_RE = Regex("""^Download (.+) APKs for Android - APKMirror$""")
    }
}
