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

    // -----------------------------------------------------------------------
    // Archive pruning
    // -----------------------------------------------------------------------
    // The archive is the single biggest thing on disk (16 GB of 180 old
    // versions on the deployment this was written for) and the only pruning
    // that deletes data clients could still be asking for, so the edges get
    // tests rather than trust.

    private fun archive(tempDir: File, vararg names: String): File {
        val dir = File(tempDir, "archive").apply { mkdirs() }
        names.forEach { File(dir, it).writeText("x") }
        return dir
    }

    private fun names(dir: File): Set<String> = dir.listFiles()!!.map { it.name }.toSet()

    @Test
    fun `pruneArchive keeps the newest N version codes per app`(@TempDir tempDir: File) {
        val dir = archive(
            tempDir,
            "com.example.app_100.apk",
            "com.example.app_101.apk",
            "com.example.app_102.apk",
            "com.example.app_103.apk",
            "com.other.app_7.apk",
        )

        val deleted = FdroidRepoManager().pruneArchive(tempDir, keep = 2)

        assertEquals(2, deleted)
        assertEquals(
            setOf("com.example.app_102.apk", "com.example.app_103.apk", "com.other.app_7.apk"),
            names(dir),
        )
    }

    @Test
    fun `pruneArchive ranks version codes numerically, not as strings`(@TempDir tempDir: File) {
        // The bug this guards: "9" > "10" lexicographically, which would
        // delete the newest build and keep an old one.
        val dir = archive(tempDir, "com.example.app_9.apk", "com.example.app_10.apk")

        FdroidRepoManager().pruneArchive(tempDir, keep = 1)

        assertEquals(setOf("com.example.app_10.apk"), names(dir))
    }

    @Test
    fun `pruneArchive removes an APK's sidecar files with it`(@TempDir tempDir: File) {
        val dir = archive(
            tempDir,
            "com.example.app_1.apk",
            "com.example.app_1.apk.asc",
            "com.example.app_1_src.tar.gz",
            "com.example.app_2.apk",
        )

        FdroidRepoManager().pruneArchive(tempDir, keep = 1)

        assertEquals(setOf("com.example.app_2.apk"), names(dir))
    }

    @Test
    fun `pruneArchive leaves files it cannot parse alone`(@TempDir tempDir: File) {
        val dir = archive(tempDir, "no-version-code.apk", "com.example.app_1.apk", "com.example.app_2.apk")

        FdroidRepoManager().pruneArchive(tempDir, keep = 1)

        assert("no-version-code.apk" in names(dir)) {
            "a name that isn't packageName_versionCode must not be guessed at"
        }
    }

    @Test
    fun `pruneArchive with keep of zero or less deletes nothing`(@TempDir tempDir: File) {
        val dir = archive(tempDir, "com.example.app_1.apk", "com.example.app_2.apk")

        assertEquals(0, FdroidRepoManager().pruneArchive(tempDir, keep = 0))
        assertEquals(0, FdroidRepoManager().pruneArchive(tempDir, keep = -1))
        assertEquals(2, names(dir).size)
    }

    @Test
    fun `pruneArchive is a no-op when there is no archive directory`(@TempDir tempDir: File) {
        assertEquals(0, FdroidRepoManager().pruneArchive(tempDir, keep = 3))
    }

    @Test
    fun `pruneArchive does not touch the live repo directory`(@TempDir tempDir: File) {
        archive(tempDir, "com.example.app_1.apk", "com.example.app_2.apk")
        val repo = File(tempDir, "repo").apply { mkdirs() }
        File(repo, "com.example.app_3.apk").writeText("x")

        FdroidRepoManager().pruneArchive(tempDir, keep = 1)

        assertEquals(setOf("com.example.app_3.apk"), names(repo))
    }
}
