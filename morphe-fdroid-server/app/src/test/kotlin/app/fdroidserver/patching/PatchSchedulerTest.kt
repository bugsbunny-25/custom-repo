package app.fdroidserver.patching

import app.fdroidserver.apkmirror.ApkMirrorClient
import app.fdroidserver.apkpure.ApkPureClient
import app.fdroidserver.config.AppConfig
import app.fdroidserver.config.AppDatabase
import app.fdroidserver.fdroidrepo.FdroidRepoManager
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Covers the guard added to [PatchScheduler.checkTarget] against matching an
 * older version than one already successfully patched for the same
 * attachment, when `supportedVersions` comes from the `.mpp` file's own
 * derived list rather than an explicit admin-UI override (the regression this
 * guards against: reddit's `.mpp` declares a long historical version list,
 * and APKMirror's feed is ordered by publish time rather than version number,
 * so an old, never-yet-processed version could surface and get patched even
 * after a much newer version of the same attachment was already done).
 *
 * [PatchScheduler.isOlderVersion]/[PatchScheduler.isDerivedCandidateAllowed]
 * are tested directly (both `internal`, the same reasoning
 * `ApkMirrorClient`'s `internal fun parseFeedVersions`/`pickBestVariantHref`
 * are pulled out for) rather than through `checkTarget`/`checkForUpdates`
 * end-to-end: reaching a matched candidate all the way through
 * `applyPatchToVersion` would spawn a real `PatchWorkerLauncher` subprocess
 * (not mockable - concrete class, real `ProcessBuilder`), and in this test
 * environment that subprocess fails immediately regardless of which
 * candidate was selected (classes run from a directory here, not the
 * packaged jar `PatchWorkerLauncher` expects), so success/failure of the
 * guard wouldn't be distinguishable through `checkForUpdates`'s observable
 * side effects (DB writes) alone.
 *
 * [scheduler] is still built through the real constructor - a real
 * `AppDatabase(tempDir)`-backed [AppConfig], real (unused-over-network)
 * [ApkMirrorClient]/[ApkPureClient] instances, matching the pattern
 * `AdminServerTest` already uses - rather than mocking anything, even though
 * this particular pair of methods doesn't touch any of it.
 */
class PatchSchedulerTest {

    private fun scheduler(tempDir: File): PatchScheduler {
        val appConfig = AppConfig(AppDatabase(File(tempDir, "db").apply { mkdirs() }))
        val patchesDir = File(tempDir, "patches").apply { mkdirs() }
        val patchedRepoDir = File(tempDir, "patched-repo").apply { mkdirs() }
        val tmpDir = File(tempDir, "tmp").apply { mkdirs() }
        return PatchScheduler(
            appConfig, ApkMirrorClient(), ApkPureClient(), PatchLibrary(), PatchWorkerLauncher(),
            patchesDir, patchedRepoDir, tmpDir, FdroidRepoManager(),
            PatchApplier.SigningConfig(keystoreFile = File(tempDir, "patched-keystore.jks")),
        )
    }

    // --- isOlderVersion --------------------------------------------------

    @Test
    fun `a lower weekly-build number is older`(@TempDir tempDir: File) {
        assertTrue(scheduler(tempDir).isOlderVersion("2026.04.0", "2026.14.0"))
    }

    @Test
    fun `a higher weekly-build number is not older`(@TempDir tempDir: File) {
        assertFalse(scheduler(tempDir).isOlderVersion("2026.14.0", "2026.04.0"))
    }

    @Test
    fun `an identical version is not older`(@TempDir tempDir: File) {
        assertFalse(scheduler(tempDir).isOlderVersion("2026.14.0", "2026.14.0"))
    }

    @Test
    fun `a numerically lower patch component is older even with a matching suffix`(@TempDir tempDir: File) {
        assertTrue(scheduler(tempDir).isOlderVersion("26.5.1+rc1-2026.04.08", "26.5.2+rc1-2026.04.08"))
    }

    @Test
    fun `differing token shapes are not confidently comparable`(@TempDir tempDir: File) {
        val s = scheduler(tempDir)
        // Different number of digit/non-digit runs.
        assertFalse(s.isOlderVersion("2026.4", "2026.04.0"))
        // A digit run lined up against a non-digit run.
        assertFalse(s.isOlderVersion("2026.beta", "2026.14.0"))
    }

    // --- isDerivedCandidateAllowed -----------------------------------------

    @Test
    fun `a derived candidate older than an already-processed version is rejected`(@TempDir tempDir: File) {
        val allowed = scheduler(tempDir).isDerivedCandidateAllowed(
            candidateVersion = "2026.04.0",
            usingDerivedVersions = true,
            alreadyProcessedVersions = listOf("2026.14.0"),
        )
        assertFalse(allowed)
    }

    @Test
    fun `a derived candidate newer than every already-processed version is allowed`(@TempDir tempDir: File) {
        val allowed = scheduler(tempDir).isDerivedCandidateAllowed(
            candidateVersion = "2026.15.0",
            usingDerivedVersions = true,
            alreadyProcessedVersions = listOf("2026.14.0"),
        )
        assertTrue(allowed)
    }

    @Test
    fun `a derived candidate is allowed when nothing has been processed yet`(@TempDir tempDir: File) {
        val allowed = scheduler(tempDir).isDerivedCandidateAllowed(
            candidateVersion = "2026.04.0",
            usingDerivedVersions = true,
            alreadyProcessedVersions = emptyList(),
        )
        assertTrue(allowed)
    }

    @Test
    fun `an explicit admin-pinned version is honored regardless of order`(@TempDir tempDir: File) {
        // Same "older" version as the rejected case above, but
        // usingDerivedVersions is false here - the operator pinned it via
        // supportedVersions, so it must not be skipped just because a newer
        // version of the same attachment was already patched.
        val allowed = scheduler(tempDir).isDerivedCandidateAllowed(
            candidateVersion = "2026.04.0",
            usingDerivedVersions = false,
            alreadyProcessedVersions = listOf("2026.14.0"),
        )
        assertTrue(allowed)
    }
}
