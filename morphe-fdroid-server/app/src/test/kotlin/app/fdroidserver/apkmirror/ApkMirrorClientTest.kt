package app.fdroidserver.apkmirror

import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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

    @Test
    fun `extracts a plain dotted version`() {
        assertEquals("2.24.15.73", client.extractVersion("WhatsApp Messenger 2.24.15.73"))
    }

    @Test
    fun `keeps a rc suffix instead of truncating at the dash`() {
        assertEquals(
            "21.1.12-2-rc",
            client.extractVersion("AccuWeather: Weather Radar 21.1.12-2-rc"),
        )
    }

    @Test
    fun `keeps a beta suffix`() {
        assertEquals("1.2.3-beta1", client.extractVersion("Some App 1.2.3-beta1"))
    }

    @Test
    fun `falls back to the full text when there is no digit`() {
        assertEquals("Some App Latest", client.extractVersion("Some App Latest"))
    }
}
