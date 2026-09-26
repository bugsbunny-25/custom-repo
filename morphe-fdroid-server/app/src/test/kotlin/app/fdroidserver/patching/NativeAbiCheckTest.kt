package app.fdroidserver.patching

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NativeAbiCheckTest {

    @Test
    fun `apk without native libraries is supported`() {
        assertEquals(
            NativeAbiCheck.Result.NoNativeCode,
            NativeAbiCheck.check(listOf("AndroidManifest.xml", "classes.dex", "res/raw/lib.so")),
        )
    }

    @Test
    fun `apk with arm64-v8a libraries is supported`() {
        val result = NativeAbiCheck.check(
            listOf("classes.dex", "lib/armeabi-v7a/libfoo.so", "lib/arm64-v8a/libfoo.so"),
        )
        assertEquals(NativeAbiCheck.Result.Supported(setOf("armeabi-v7a", "arm64-v8a")), result)
    }

    @Test
    fun `apk with only other architectures is unsupported`() {
        val result = NativeAbiCheck.check(listOf("lib/x86/libfoo.so", "lib/armeabi-v7a/libfoo.so"))
        assertTrue(result is NativeAbiCheck.Result.Unsupported)
        assertEquals(
            "no arm64-v8a native libraries (APK only has armeabi-v7a, x86)",
            (result as NativeAbiCheck.Result.Unsupported).reason,
        )
    }

    @Test
    fun `reads entries from an apk file`(@TempDir dir: File) {
        val apk = File(dir, "app.apk")
        ZipOutputStream(apk.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("lib/x86_64/libfoo.so"))
            zip.closeEntry()
        }
        assertEquals(NativeAbiCheck.Result.Unsupported(setOf("x86_64")), NativeAbiCheck.check(apk))
    }
}
