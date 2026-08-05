package app.fdroidserver.patching

import app.fdroidserver.config.AppConfig
import app.fdroidserver.config.AppDatabase
import app.fdroidserver.github.GitHubReleaseChecker
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Covers [PatchLibraryGithubScheduler]: the pure "which asset to import when
 * a release has more than one `.mpp`" tie-break logic (no network needed),
 * plus the end-to-end import/skip-if-already-imported behavior against a
 * real (temp-dir) [AppConfig] with a [GitHubReleaseChecker] `spyk` - a spy
 * rather than a bare `mockk` so [GitHubReleaseChecker.matchingAssets] (the
 * regex asset filtering) runs for real and only the two methods that would
 * otherwise hit the network (`getReleases`/`downloadAsset`) are stubbed.
 */
class PatchLibraryGithubSchedulerTest {

    private fun release(
        id: Long,
        tag: String,
        assets: List<GitHubReleaseChecker.GitHubAsset>,
        prerelease: Boolean = false,
    ) = GitHubReleaseChecker.GitHubRelease(id = id, tagName = tag, prerelease = prerelease, assets = assets)

    private fun asset(id: Long, name: String) = GitHubReleaseChecker.GitHubAsset(id = id, name = name)

    private fun schedulerWithRealChecker(patchesDir: File = File(".")): PatchLibraryGithubScheduler =
        PatchLibraryGithubScheduler(
            mockk(relaxed = true),
            spyk(GitHubReleaseChecker(HttpClient(CIO), githubToken = null)),
            patchesDir,
            AppConfig.PatchSchemas.Mobile,
        )

    // ---- selectMppAsset (pure tie-break logic, no network) -----------------

    @Test
    fun `selectMppAsset returns the only asset unconditionally, even if its name doesn't match the tag`() {
        val onlyAsset = asset(1, "unrelated-name.mpp")
        val result = schedulerWithRealChecker().selectMppAsset(release(1, "v1.0.0", listOf(onlyAsset)), listOf(onlyAsset))
        assertEquals(onlyAsset, result)
    }

    @Test
    fun `selectMppAsset picks the asset matching the release tag when there are multiple`() {
        val old = asset(1, "patches-0.9.0.mpp")
        val current = asset(2, "patches-1.0.0.mpp")
        val rel = release(2, "v1.0.0", listOf(old, current))
        assertEquals(current, schedulerWithRealChecker().selectMppAsset(rel, listOf(old, current)))
    }

    @Test
    fun `selectMppAsset matches a tag without a leading v too`() {
        val old = asset(1, "patches-0.9.0.mpp")
        val current = asset(2, "patches-1.0.0.mpp")
        val rel = release(2, "1.0.0", listOf(old, current)) // no leading "v" in the tag itself
        assertEquals(current, schedulerWithRealChecker().selectMppAsset(rel, listOf(old, current)))
    }

    @Test
    fun `selectMppAsset falls back to the first asset when none of several match the tag`() {
        val a = asset(1, "patches-a.mpp")
        val b = asset(2, "patches-b.mpp")
        val rel = release(3, "v9.9.9", listOf(a, b))
        assertEquals(a, schedulerWithRealChecker().selectMppAsset(rel, listOf(a, b)))
    }

    // ---- end-to-end checkForUpdates / checkEntryNow -------------------------

    private fun newAppConfig(tempDir: File): AppConfig =
        AppConfig(AppDatabase(File(tempDir, "db").apply { mkdirs() }))

    @Test
    fun `checkForUpdates imports a new release's mpp asset and records its version`(@TempDir tempDir: File) {
        runBlocking {
            val appConfig = newAppConfig(tempDir)
            val patchesDir = File(tempDir, "patches").apply { mkdirs() }
            appConfig.addPatchToLibrary(
                AppConfig.PatchSchemas.Mobile, "yt-ads", "Block Ads", "yt-ads.mpp",
                githubRepo = "someowner/somerepo", githubIncludePrereleases = false,
            )

            val mppAsset = asset(42, "patches-1.0.0.mpp")
            val rel = release(100, "v1.0.0", listOf(mppAsset))
            val checker = spyk(GitHubReleaseChecker(HttpClient(CIO), githubToken = null))
            coEvery { checker.getReleases(any()) } returns listOf(rel)
            coEvery { checker.downloadAsset(any(), any(), any()) } answers {
                thirdArg<File>().writeText("mpp-bytes")
                true
            }

            val scheduler = PatchLibraryGithubScheduler(appConfig, checker, patchesDir, AppConfig.PatchSchemas.Mobile)
            assertTrue(scheduler.checkForUpdates())

            assertEquals("mpp-bytes", File(patchesDir, "yt-ads.mpp").readText())
            val entry = appConfig.listPatchLibrary(AppConfig.PatchSchemas.Mobile).single()
            assertEquals("v1.0.0", entry.version)
            assertEquals("yt-ads.mpp", entry.file)
        }
    }

    @Test
    fun `checkForUpdates does not re-download a release that was already imported`(@TempDir tempDir: File) {
        runBlocking {
            val appConfig = newAppConfig(tempDir)
            val patchesDir = File(tempDir, "patches").apply { mkdirs() }
            appConfig.addPatchToLibrary(
                AppConfig.PatchSchemas.Mobile, "yt-ads", "Block Ads", "yt-ads.mpp",
                githubRepo = "someowner/somerepo",
            )

            val rel = release(100, "v1.0.0", listOf(asset(42, "patches-1.0.0.mpp")))
            val checker = spyk(GitHubReleaseChecker(HttpClient(CIO), githubToken = null))
            coEvery { checker.getReleases(any()) } returns listOf(rel)
            coEvery { checker.downloadAsset(any(), any(), any()) } answers {
                thirdArg<File>().writeText("mpp-bytes")
                true
            }

            val scheduler = PatchLibraryGithubScheduler(appConfig, checker, patchesDir, AppConfig.PatchSchemas.Mobile)
            assertTrue(scheduler.checkForUpdates())
            assertFalse(scheduler.checkForUpdates(), "second check against the same latest release should be a no-op")

            coVerify(exactly = 1) { checker.downloadAsset(any(), any(), any()) }
        }
    }

    @Test
    fun `checkForUpdates picks the mpp asset matching the release version when the release carries older ones too`(@TempDir tempDir: File) {
        runBlocking {
            val appConfig = newAppConfig(tempDir)
            val patchesDir = File(tempDir, "patches").apply { mkdirs() }
            appConfig.addPatchToLibrary(
                AppConfig.PatchSchemas.Mobile, "yt-ads", "Block Ads", "yt-ads.mpp",
                githubRepo = "someowner/somerepo",
            )

            val old = asset(1, "patches-0.9.0.mpp")
            val current = asset(2, "patches-1.0.0.mpp")
            val rel = release(200, "v1.0.0", listOf(old, current))
            val checker = spyk(GitHubReleaseChecker(HttpClient(CIO), githubToken = null))
            coEvery { checker.getReleases(any()) } returns listOf(rel)
            coEvery { checker.downloadAsset(any(), current, any()) } answers {
                thirdArg<File>().writeText("current-bytes")
                true
            }
            coEvery { checker.downloadAsset(any(), old, any()) } answers {
                thirdArg<File>().writeText("old-bytes")
                true
            }

            val scheduler = PatchLibraryGithubScheduler(appConfig, checker, patchesDir, AppConfig.PatchSchemas.Mobile)
            assertTrue(scheduler.checkForUpdates())

            assertEquals("current-bytes", File(patchesDir, "yt-ads.mpp").readText())
        }
    }

    @Test
    fun `checkForUpdates skips a release with no mpp assets`(@TempDir tempDir: File) {
        runBlocking {
            val appConfig = newAppConfig(tempDir)
            val patchesDir = File(tempDir, "patches").apply { mkdirs() }
            appConfig.addPatchToLibrary(
                AppConfig.PatchSchemas.Mobile, "yt-ads", "Block Ads", "yt-ads.mpp",
                githubRepo = "someowner/somerepo",
            )

            val rel = release(100, "v1.0.0", listOf(asset(1, "notes.txt")))
            val checker = spyk(GitHubReleaseChecker(HttpClient(CIO), githubToken = null))
            coEvery { checker.getReleases(any()) } returns listOf(rel)

            val scheduler = PatchLibraryGithubScheduler(appConfig, checker, patchesDir, AppConfig.PatchSchemas.Mobile)
            assertFalse(scheduler.checkForUpdates())
            coVerify(exactly = 0) { checker.downloadAsset(any(), any(), any()) }
        }
    }

    @Test
    fun `checkEntryNow checks and imports just the requested entry`(@TempDir tempDir: File) {
        runBlocking {
            val appConfig = newAppConfig(tempDir)
            val patchesDir = File(tempDir, "patches").apply { mkdirs() }
            appConfig.addPatchToLibrary(AppConfig.PatchSchemas.Mobile, "yt-ads", "Block Ads", "yt-ads.mpp", githubRepo = "someowner/somerepo")
            appConfig.addPatchToLibrary(AppConfig.PatchSchemas.Mobile, "manual-patch", "Manual", "manual-patch.mpp") // no github_repo

            val rel = release(100, "v1.0.0", listOf(asset(1, "patches-1.0.0.mpp")))
            val checker = spyk(GitHubReleaseChecker(HttpClient(CIO), githubToken = null))
            coEvery { checker.getReleases(any()) } returns listOf(rel)
            coEvery { checker.downloadAsset(any(), any(), any()) } answers {
                thirdArg<File>().writeText("mpp-bytes")
                true
            }

            val scheduler = PatchLibraryGithubScheduler(appConfig, checker, patchesDir, AppConfig.PatchSchemas.Mobile)

            assertFalse(scheduler.checkEntryNow("manual-patch"), "entries without a github_repo are never auto-updated")
            assertTrue(scheduler.checkEntryNow("yt-ads"))
            assertFalse(File(patchesDir, "manual-patch.mpp").exists())
            assertEquals("mpp-bytes", File(patchesDir, "yt-ads.mpp").readText())
        }
    }
}
