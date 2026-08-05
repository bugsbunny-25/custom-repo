package app.fdroidserver.apkpure

import app.fdroidserver.scraper.ScraperClient
import app.fdroidserver.scraper.ScraperClient.DownloadInfo
import app.fdroidserver.scraper.ScraperClient.VersionEntry
import org.jsoup.nodes.Document
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * The APKPure counterpart to [app.fdroidserver.apkmirror.ApkMirrorClient],
 * used as a fallback source when a target's pinned version isn't present on
 * APKMirror (see `PatchScheduler`). Shares all the Cloudflare-challenge
 * handling, FlareSolverr fallback, cookie jar, and APK download with
 * APKMirror via [ScraperClient]; only the APKPure-specific version discovery
 * and download-link resolution live here.
 *
 * APKPure has no official (public) API either, so this scrapes HTML just like
 * the APKMirror client, with the same fragility/ToS caveats. The `/versions`
 * listing renders each release as a `div.ver_download_link` carrying a
 * `data-dt-version` attribute (the exact version string) - [parseVersions]
 * keys off that attribute rather than the row's link text/URL, because the
 * *latest* release's link points at the bare `.../download` page with no
 * version in the path (only the older releases use `.../download/{version}`),
 * so reading the version from the href alone would silently drop the newest
 * one. This mirrors the example URLs the feature was specced against - an app
 * page like
 * `https://apkpure.com/flightaware-flight-tracker/com.flightaware.android.liveFlightTracker`
 * and a specific-version page like
 * `.../com.flightaware.android.liveFlightTracker/download/5.15.4`.
 */
class ApkPureClient(
    logger: Logger = LoggerFactory.getLogger(ApkPureClient::class.java.name),
    flareSolverrUrl: String? = null,
) : ScraperClient(logger, flareSolverrUrl) {

    override val sourceName: String = "apkpure"

    /**
     * Returns the app's versions from its `{appUrl}/versions` listing page,
     * newest first (APKPure lists them newest-first, which [parseVersions]
     * preserves). Warms up on [appUrl] first for the same Cloudflare
     * bot-scoring reason APKMirror's client does - hitting `/versions` cold
     * with no prior page visit and no Referer looks more scraper-like - and
     * carries the app page's cookies and [appUrl] Referer into the listing
     * request through the shared client. If the warm-up itself gets stuck
     * behind a challenge [get] can't clear, we fall through and try the
     * listing directly rather than failing the whole call.
     */
    override fun getVersions(appUrl: String): List<VersionEntry> {
        val normalizedAppUrl = appUrl.trimEnd('/')
        runCatching { get(normalizedAppUrl) }.onFailure {
            logger.debug("apkpure: warm-up request to $normalizedAppUrl failed, fetching versions page directly instead: $it")
        }

        val url = "$normalizedAppUrl/versions"
        val versions = parseVersions(parse(get(url, referer = normalizedAppUrl), url), normalizedAppUrl)
        logger.info("apkpure: found ${versions.size} version(s) on $url")
        logger.info("apkpure: versions on $url: ${versions.joinToString { it.version }}")
        return versions
    }

    /**
     * Pure version-list parsing, pulled out of [getVersions] so it's
     * unit-testable against synthetic HTML fixtures without a network call
     * (see `ApkPureClientTest`). Reads each `div.ver_download_link` row's
     * `data-dt-version` attribute (the authoritative version string) in page
     * order (newest first), dropping duplicates. This selector deliberately
     * excludes APKPure's own "Use APKPure App" promo row (a `.dl-ref` for
     * `com.apkpure.aegon`) and the duplicate `.ver-top-down` hero copy of the
     * latest release.
     *
     * The row's own version-name link is used as the `pageUrl` when present
     * (it's `.../download/{version}` for older releases and the bare
     * `.../download` for the latest - both are valid download pages that
     * [resolveDownloadUrl] then resolves to a direct file link); if a row has
     * no usable link, the page URL is constructed as `{appUrl}/download/{version}`.
     */
    internal fun parseVersions(doc: Document, appUrl: String): List<VersionEntry> {
        val base = appUrl.trimEnd('/')
        return doc.select("div.ver_download_link[data-dt-version]").mapNotNull { row ->
            val version = row.attr("data-dt-version").trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val nameLink = row.selectFirst("a.dt-version-name-link[href], a.ver-item-n[href], a[href*=/download]")
            val pageUrl = nameLink?.absUrl("href")?.ifBlank { null }?.substringBefore('?')?.substringBefore('#')
                ?: "$base/download/$version"
            VersionEntry(version, pageUrl)
        }.distinctBy { it.version }
    }

    /**
     * Resolves a `{appUrl}/download/{version}` page to a direct APK/XAPK URL.
     * Unlike APKMirror (version -> variant -> download -> asset), APKPure's
     * download page links straight to the file on its download CDN
     * (`https://d.apkpure.com/b/APK/{package}?versionCode=...`), so this is a
     * single hop. See [pickDownloadHref] for the selection. Returns the
     * download page itself as the Referer, which the CDN expects.
     */
    override fun resolveDownloadUrl(versionPageUrl: String): DownloadInfo? {
        val downloadDoc = parse(get(versionPageUrl), versionPageUrl)
        val finalUrl = pickDownloadHref(downloadDoc)
        if (finalUrl == null) {
            logger.warn("apkpure: no download link found on $versionPageUrl")
            return null
        }
        return DownloadInfo(finalUrl, versionPageUrl)
    }

    /**
     * Pure download-link selection, pulled out of [resolveDownloadUrl] so it's
     * unit-testable against synthetic HTML fixtures. Prefers the explicit
     * `#download_link` anchor (APKPure's long-standing id for the real APK
     * link); otherwise falls back to the first anchor carrying a
     * `/b/APK|XAPK|APKS/` asset path. Both paths skip APKPure's own
     * "Use APKPure App" promo link (a `d.apkpure.com/custom/com.apkpure.aegon-...`
     * URL that would otherwise look like a legitimate CDN download). XAPK
     * bundles are fine - `BundleMerger` merges them into a single APK before
     * patching (it detects a bundle by the nested `.apk` entries in the zip,
     * so the missing/ambiguous file extension on a CDN URL doesn't matter).
     */
    internal fun pickDownloadHref(doc: Document): String? {
        // Note: avoid `selectFirst(...)?.let { ... }` here - jsoup 1.22's
        // selectFirst is jspecify-@Nullable-annotated and letting that type
        // flow into an inferred lambda parameter makes the Kotlin compiler try
        // to emit the annotation (which isn't on the compile classpath). An
        // explicit null check keeps the annotated type from leaking.
        val explicit = doc.selectFirst("a#download_link[href]")
        if (explicit != null) {
            val href = explicit.absUrl("href").ifBlank { explicit.attr("href") }
            if (isDownloadAssetUrl(href)) return href
        }

        val candidate = doc.select("a[href]").firstOrNull { link ->
            isDownloadAssetUrl(link.absUrl("href").ifBlank { link.attr("href") })
        }
        if (candidate == null) return null
        return candidate.absUrl("href").ifBlank { candidate.attr("href") }
    }

    companion object {
        // APKPure's real download links carry a `/b/APK/`, `/b/XAPK/`, or
        // `/b/APKS/` asset path (e.g.
        // https://d.apkpure.com/b/APK/com.example?versionCode=123).
        private val DOWNLOAD_ASSET_PATH_RE = Regex("""/b/(?:APK|XAPK|APKS)/""", RegexOption.IGNORE_CASE)

        // APKPure's own app ("aegon") is offered as a promo download on every
        // download page - exclude it so it's never mistaken for the target app.
        private val APKPURE_PROMO_RE = Regex("""apkpure\.aegon|/custom/com\.apkpure""", RegexOption.IGNORE_CASE)

        private fun isDownloadAssetUrl(href: String): Boolean {
            if (href.isBlank()) return false
            if (APKPURE_PROMO_RE.containsMatchIn(href)) return false
            return DOWNLOAD_ASSET_PATH_RE.containsMatchIn(href)
        }
    }
}
