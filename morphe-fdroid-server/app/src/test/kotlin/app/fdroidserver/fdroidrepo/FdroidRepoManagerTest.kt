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
    // The app and version come from the repo index, never the filename. This
    // started out parsing F-Droid's canonical `packageName_versionCode.apk`,
    // which matched nothing here: GitHub assets land as
    // `{releaseId}-{assetName}.apk` and patched builds as
    // `{targetId}__{patchId}__{version}.apk`. Every file failed to parse, so
    // the prune skipped all 180 of them and reclaimed nothing. These tests
    // use realistic names on purpose.

    private fun archiveWithIndex(
        tempDir: File,
        files: List<String>,
        index: List<Triple<String, String, Long>>,
    ): File {
        val dir = File(tempDir, "archive").apply { mkdirs() }
        files.forEach { File(dir, it).writeText("x") }
        val packages = index.groupBy { it.second }.entries.joinToString(",") { (pkg, entries) ->
            val arr = entries.joinToString(",") { (apkName, _, code) ->
                """{"apkName":"$apkName","versionCode":$code}"""
            }
            """"$pkg":[$arr]"""
        }
        File(dir, "index-v1.json").writeText("""{"repo":{},"apps":[],"packages":{$packages}}""")
        return dir
    }

    private fun names(dir: File): Set<String> =
        dir.listFiles()!!.map { it.name }.filterNot { it.startsWith("index-") }.toSet()

    @Test
    fun `pruneArchive keeps the newest N per app using GitHub-style asset names`(@TempDir tempDir: File) {
        val dir = archiveWithIndex(
            tempDir,
            files = listOf(
                "273373805-findroid-v1.0.0-release.apk",
                "281110422-findroid-v1.1.0-release.apk",
                "290004711-findroid-v1.2.0-release.apk",
                "301225533-jellyfin-v2.6.0.apk",
            ),
            index = listOf(
                Triple("273373805-findroid-v1.0.0-release.apk", "dev.jdtech.jellyfin", 100L),
                Triple("281110422-findroid-v1.1.0-release.apk", "dev.jdtech.jellyfin", 110L),
                Triple("290004711-findroid-v1.2.0-release.apk", "dev.jdtech.jellyfin", 120L),
                Triple("301225533-jellyfin-v2.6.0.apk", "org.jellyfin.mobile", 260L),
            ),
        )

        val deleted = FdroidRepoManager().pruneArchive(tempDir, keep = 2)

        assertEquals(1, deleted)
        assertEquals(
            setOf(
                "281110422-findroid-v1.1.0-release.apk",
                "290004711-findroid-v1.2.0-release.apk",
                "301225533-jellyfin-v2.6.0.apk",
            ),
            names(dir),
        )
    }

    @Test
    fun `pruneArchive handles the patched-build naming too`(@TempDir tempDir: File) {
        val dir = archiveWithIndex(
            tempDir,
            files = listOf(
                "youtube__morphe__21.30.00.apk",
                "youtube__morphe__21.36.45.apk",
            ),
            index = listOf(
                Triple("youtube__morphe__21.30.00.apk", "app.morphe.android.youtube", 1_561_200_000L),
                Triple("youtube__morphe__21.36.45.apk", "app.morphe.android.youtube", 1_561_295_689L),
            ),
        )

        FdroidRepoManager().pruneArchive(tempDir, keep = 1)

        assertEquals(setOf("youtube__morphe__21.36.45.apk"), names(dir))
    }

    @Test
    fun `pruneArchive ranks by version code numerically`(@TempDir tempDir: File) {
        // Guards the obvious trap: "9" > "10" as strings would keep the old one.
        val dir = archiveWithIndex(
            tempDir,
            files = listOf("a-9.apk", "b-10.apk"),
            index = listOf(
                Triple("a-9.apk", "com.example.app", 9L),
                Triple("b-10.apk", "com.example.app", 10L),
            ),
        )

        FdroidRepoManager().pruneArchive(tempDir, keep = 1)

        assertEquals(setOf("b-10.apk"), names(dir))
    }

    @Test
    fun `pruneArchive deletes nothing when there is no index`(@TempDir tempDir: File) {
        // The index is the only thing that knows what these files are, so
        // without it the safe answer is to do nothing rather than guess.
        val dir = File(tempDir, "archive").apply { mkdirs() }
        listOf("273373805-findroid-v1.0.0.apk", "281110422-findroid-v1.1.0.apk").forEach {
            File(dir, it).writeText("x")
        }

        assertEquals(0, FdroidRepoManager().pruneArchive(tempDir, keep = 1))
        assertEquals(2, names(dir).size)
    }

    @Test
    fun `pruneArchive deletes nothing when the index is corrupt`(@TempDir tempDir: File) {
        val dir = File(tempDir, "archive").apply { mkdirs() }
        File(dir, "a.apk").writeText("x")
        File(dir, "b.apk").writeText("x")
        File(dir, "index-v1.json").writeText("{ truncated")

        assertEquals(0, FdroidRepoManager().pruneArchive(tempDir, keep = 1))
        assertEquals(2, names(dir).size)
    }

    @Test
    fun `pruneArchive leaves files the index does not mention`(@TempDir tempDir: File) {
        val dir = archiveWithIndex(
            tempDir,
            files = listOf("known-1.apk", "known-2.apk", "stranger.apk"),
            index = listOf(
                Triple("known-1.apk", "com.example.app", 1L),
                Triple("known-2.apk", "com.example.app", 2L),
            ),
        )

        FdroidRepoManager().pruneArchive(tempDir, keep = 1)

        assert("stranger.apk" in names(dir)) { "an unattributable file must not be deleted" }
        assertEquals(setOf("known-2.apk", "stranger.apk"), names(dir))
    }

    @Test
    fun `pruneArchive removes an APK's sidecar files with it`(@TempDir tempDir: File) {
        val dir = archiveWithIndex(
            tempDir,
            files = listOf("old.apk", "old.apk.asc", "old_src.tar.gz", "new.apk"),
            index = listOf(
                Triple("old.apk", "com.example.app", 1L),
                Triple("new.apk", "com.example.app", 2L),
            ),
        )

        FdroidRepoManager().pruneArchive(tempDir, keep = 1)

        assertEquals(setOf("new.apk"), names(dir))
    }

    @Test
    fun `pruneArchive reads index-v2 when v1 is absent`(@TempDir tempDir: File) {
        val dir = File(tempDir, "archive").apply { mkdirs() }
        File(dir, "old.apk").writeText("x")
        File(dir, "new.apk").writeText("x")
        File(dir, "index-v2.json").writeText(
            """
            {"packages":{"com.example.app":{"versions":{
              "h1":{"file":{"name":"/old.apk"},"manifest":{"versionCode":1}},
              "h2":{"file":{"name":"/new.apk"},"manifest":{"versionCode":2}}
            }}}}
            """.trimIndent(),
        )

        FdroidRepoManager().pruneArchive(tempDir, keep = 1)

        assertEquals(setOf("new.apk"), names(dir))
    }

    @Test
    fun `pruneArchive with keep of zero or less deletes nothing`(@TempDir tempDir: File) {
        val dir = archiveWithIndex(
            tempDir,
            files = listOf("a.apk", "b.apk"),
            index = listOf(
                Triple("a.apk", "com.example.app", 1L),
                Triple("b.apk", "com.example.app", 2L),
            ),
        )

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
        archiveWithIndex(
            tempDir,
            files = listOf("a.apk", "b.apk"),
            index = listOf(
                Triple("a.apk", "com.example.app", 1L),
                Triple("b.apk", "com.example.app", 2L),
            ),
        )
        val repo = File(tempDir, "repo").apply { mkdirs() }
        File(repo, "c.apk").writeText("x")

        FdroidRepoManager().pruneArchive(tempDir, keep = 1)

        assertEquals(setOf("c.apk"), repo.listFiles()!!.map { it.name }.toSet())
    }
}
