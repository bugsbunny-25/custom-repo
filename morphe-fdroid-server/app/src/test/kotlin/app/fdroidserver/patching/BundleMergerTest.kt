package app.fdroidserver.patching

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BundleMergerTest {

    @Test
    fun `detects bundle by extension`(@TempDir tempDir: File) {
        val file = File(tempDir, "app.apkm")
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(BundleMerger().isBundle(file))
    }

    @Test
    fun `detects bundle by base-apk zip entry when extension is ambiguous`(@TempDir tempDir: File) {
        val file = File(tempDir, "download.bin")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("base.apk"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("split_config.arm64_v8a.apk"))
            zip.write(byteArrayOf(4, 5, 6))
            zip.closeEntry()
        }
        assertTrue(BundleMerger().isBundle(file))
    }

    @Test
    fun `plain apk zip with manifest at root is not a bundle`(@TempDir tempDir: File) {
        val file = File(tempDir, "download.bin")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }
        assertFalse(BundleMerger().isBundle(file))
    }
}
