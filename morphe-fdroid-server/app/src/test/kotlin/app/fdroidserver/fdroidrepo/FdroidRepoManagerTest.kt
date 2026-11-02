package app.fdroidserver.fdroidrepo

import app.fdroidserver.patching.VersionOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class FdroidRepoManagerTest {

    @Test
    fun `readRepoMetadata returns null when config,yml is missing`(@TempDir tempDir: File) {
        assertNull(FdroidRepoManager().readRepoMetadata(tempDir))
    }

    @Test
    fun `readRepoMetadata returns null when a required field is missing`(@TempDir tempDir: File) {
        File(tempDir, "config.yml").writeText("repo_name: Only Name\n")
        assertNull(FdroidRepoManager().readRepoMetadata(tempDir))
    }

    @Test
    fun `readRepoMetadata round-trips what writeRepoMetadata wrote, preserving other keys`(@TempDir tempDir: File) {
        File(tempDir, "config.yml").writeText("repo_keyalias: fdroid-repo\n")
        val manager = FdroidRepoManager()

        manager.writeRepoMetadata(tempDir, "My Repo", "desc", "https://example.com/fdroid/repo", "icon.png")

        val metadata = manager.readRepoMetadata(tempDir)
        assertEquals(FdroidRepoManager.RepoMetadata("My Repo", "desc", "https://example.com/fdroid/repo", "icon.png"), metadata)
        assert(File(tempDir, "config.yml").readText().contains("repo_keyalias: fdroid-repo")) {
            "expected fdroid init's other generated keys to survive"
        }
    }

    @Test
    fun `readRepoMetadata defaults icon to empty string when absent`(@TempDir tempDir: File) {
        File(tempDir, "config.yml").writeText(
            """
            repo_name: My Repo
            repo_description: desc
            repo_url: https://example.com/fdroid/repo
            """.trimIndent(),
        )
        val metadata = FdroidRepoManager().readRepoMetadata(tempDir)
        assertEquals(FdroidRepoManager.RepoMetadata("My Repo", "desc", "https://example.com/fdroid/repo", ""), metadata)
    }

    @Test
    fun `pruneOldVersions keeps the newest files by the given version order`(@TempDir tempDir: File) {
        val names = listOf("app__p__26.5.1+rc1-2026.04.08", "app__p__26.5.2+rc1-2026.04.08", "app__p__26.4.9+rc1-2026.03.01")
        names.forEach { File(tempDir, "$it.apk").writeText("x") }

        FdroidRepoManager().pruneOldVersions(
            tempDir, 2,
            versionOrder = { a, b ->
                VersionOrder.compare(a.nameWithoutExtension.substringAfterLast("__"), b.nameWithoutExtension.substringAfterLast("__"))!!
            },
        ) { "app__p" }

        val left = tempDir.listFiles()!!.map { it.nameWithoutExtension }.toSet()
        assertEquals(setOf("app__p__26.5.1+rc1-2026.04.08", "app__p__26.5.2+rc1-2026.04.08"), left)
    }
}
