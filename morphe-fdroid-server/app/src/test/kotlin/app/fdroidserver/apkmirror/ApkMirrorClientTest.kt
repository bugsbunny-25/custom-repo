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
}
