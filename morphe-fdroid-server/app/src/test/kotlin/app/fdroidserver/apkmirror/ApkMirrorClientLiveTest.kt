package app.fdroidserver.apkmirror

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * End-to-end check that the full resolve-and-download flow (versions listing,
 * variant page, download page, final asset) actually works against live
 * APKMirror, a real target for the cookie/referer/header handling in
 * [ApkMirrorClient], since none of that can be exercised by the synthetic-HTML
 * unit tests in [ApkMirrorClientTest].
 *
 * Tagged "network" and excluded from the default `test` task (see
 * app/build.gradle.kts) since it depends on a third party being up and not
 * rate-limiting/blocking us. Run explicitly with:
 * ./gradlew test --tests "*ApkMirrorClientLiveTest*" -DincludeNetworkTests=true
 */
@Tag("network")
class ApkMirrorClientLiveTest {

    private val client = ApkMirrorClient()

    @Test
    fun `downloads the latest YouTube apk from apkmirror`(@TempDir tempDir: File) {
        val appUrl = "https://www.apkmirror.com/apk/google-inc/youtube/"

        val versions = client.getVersions(appUrl)
        assertTrue(versions.isNotEmpty(), "expected at least one version listed for YouTube")
        val latest = versions.first()

        val downloadInfo = client.resolveDownloadUrl(latest.pageUrl)
        assertTrue(downloadInfo != null, "could not resolve a download URL for " + latest.pageUrl)
        checkNotNull(downloadInfo)

        val destination = File(tempDir, "youtube-" + latest.version + ".apk")
        val downloaded = client.downloadApk(downloadInfo.url, destination, downloadInfo.referer)

        assertTrue(downloaded, "downloadApk reported failure for " + downloadInfo.url)
        // A real APK is at least several MB. A 403 or a broken cookie/referer
        // chain would silently save a tiny HTML error/interstitial page instead.
        assertTrue(
            destination.exists() && destination.length() > 1_000_000,
            "downloaded file is missing or too small (" + destination.length() + " bytes)",
        )

        // A ZIP local-file-header signature confirms we got real binary APK
        // content, not an HTML error page saved under a .apk filename.
        val signature = destination.inputStream().use { input -> input.readNBytes(4) }
        val zipLocalFileHeader = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        assertTrue(
            signature.contentEquals(zipLocalFileHeader),
            "downloaded file does not start with a ZIP signature: " + signature.joinToString(),
        )
    }
}
