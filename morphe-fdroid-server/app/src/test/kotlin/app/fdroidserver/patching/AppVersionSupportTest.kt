package app.fdroidserver.patching

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.bytecodePatch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppVersionSupportTest {

    private val pkg = "com.example.app"

    private fun patchFor(vararg targets: AppTarget) = bytecodePatch(name = "Example") {
        compatibleWith(Compatibility(name = "Example app", packageName = pkg, targets = targets.toList()))
    }

    @Test
    fun `experimental specific versions are only patchable when included`() {
        val support = patchFor(
            AppTarget(version = "2.0", isExperimental = true),
            AppTarget(version = "1.0"),
        ).appVersionSupport().getValue(pkg)

        assertEquals(listOf("2.0", "1.0"), support.versions)
        assertEquals(listOf("2.0"), support.experimentalVersions)
        assertEquals(listOf("1.0"), support.patchableVersions(includeExperimental = false))
        assertEquals(listOf("2.0", "1.0"), support.patchableVersions(includeExperimental = true))
    }

    @Test
    fun `an experimental any-version target opens every version only when included`() {
        val patch = patchFor(
            AppTarget(version = null, isExperimental = true),
            AppTarget(version = "1.0"),
        )
        val support = patch.appVersionSupport().getValue(pkg)

        assertFalse(support.anyVersion)
        assertTrue(support.anyVersionExperimental)
        assertEquals(listOf("1.0"), support.patchableVersions(includeExperimental = false))
        assertEquals(listOf("*"), support.patchableVersions(includeExperimental = true))

        assertTrue(patch.supportsAppVersion(pkg, "1.0", includeExperimental = false))
        assertFalse(patch.supportsAppVersion(pkg, "3.0", includeExperimental = false))
        assertTrue(patch.supportsAppVersion(pkg, "3.0", includeExperimental = true))
    }

    @Test
    fun `a stable any-version target alongside listed versions allows every version`() {
        val patch = patchFor(AppTarget(version = null), AppTarget(version = "1.0"))

        assertEquals(listOf("*"), patch.appVersionSupport().getValue(pkg).patchableVersions(includeExperimental = false))
        assertTrue(patch.supportsAppVersion(pkg, "9.9", includeExperimental = false))
    }

    @Test
    fun `experimental-only declarations yield nothing unless included`() {
        val patch = patchFor(AppTarget(version = null, isExperimental = true))
        val support = patch.appVersionSupport().getValue(pkg)

        assertEquals(emptyList<String>(), support.patchableVersions(includeExperimental = false))
        assertFalse(patch.supportsAppVersion(pkg, "1.0", includeExperimental = false))
        assertTrue(patch.supportsAppVersion(pkg, "1.0", includeExperimental = true))
    }

    @Test
    fun `other packages and universal patches`() {
        val patch = patchFor(AppTarget(version = "1.0"))
        assertFalse(patch.supportsAppVersion("com.other", "1.0", includeExperimental = true))

        val universal = bytecodePatch(name = "Universal") {}
        assertTrue(universal.supportsAppVersion(pkg, "1.0", includeExperimental = false))
    }
}
