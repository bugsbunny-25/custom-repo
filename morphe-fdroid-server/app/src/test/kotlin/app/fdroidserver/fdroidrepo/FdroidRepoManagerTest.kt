package app.fdroidserver.fdroidrepo

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
}
