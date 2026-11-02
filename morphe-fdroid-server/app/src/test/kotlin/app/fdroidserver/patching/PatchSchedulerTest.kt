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
 * Covers [PatchScheduler.matchesSupportedVersion]. The version-selection
 * rule itself lives in [PatchUpdatePolicy] and is tested there; reaching it
 * through `checkForUpdates` would spawn a real patch-worker subprocess.
 */
class PatchSchedulerTest {

    private fun scheduler(tempDir: File): PatchScheduler {
        val appConfig = AppConfig(AppDatabase(File(tempDir, "db").apply { mkdirs() }))
        return PatchScheduler(
            appConfig, ApkMirrorClient(), ApkPureClient(), PatchLibrary(), PatchWorkerLauncher(),
            File(tempDir, "patches").apply { mkdirs() },
            File(tempDir, "patched-repo").apply { mkdirs() },
            File(tempDir, "tmp").apply { mkdirs() },
            FdroidRepoManager(),
            PatchApplier.SigningConfig(keystoreFile = File(tempDir, "patched-keystore.jks")),
        )
    }

    @Test
    fun `a wildcard matches stable versions but not alpha or beta builds`(@TempDir tempDir: File) {
        val s = scheduler(tempDir)
        assertTrue(s.matchesSupportedVersion("20.40.31", listOf("*")))
        assertTrue(s.matchesSupportedVersion("26.5.1+rc1-2026.04.08", listOf("*")))
        assertFalse(s.matchesSupportedVersion("20.41.33 beta", listOf("*")))
        assertFalse(s.matchesSupportedVersion("2.0.0-alpha3", listOf("2.*")))
    }

    @Test
    fun `an exact entry matches a beta build`(@TempDir tempDir: File) {
        assertTrue(scheduler(tempDir).matchesSupportedVersion("20.41.33 beta", listOf("20.41.33 beta")))
    }

    @Test
    fun `globs match per pattern`(@TempDir tempDir: File) {
        val s = scheduler(tempDir)
        assertTrue(s.matchesSupportedVersion("20.40.31", listOf("20.40.*")))
        assertFalse(s.matchesSupportedVersion("20.41.31", listOf("20.40.*")))
        assertFalse(s.matchesSupportedVersion("20.40.31", emptyList()))
    }
}
