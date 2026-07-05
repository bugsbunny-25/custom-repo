package app.fdroidserver.patching

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import java.io.File

/**
 * Exercises [PatchLibrary.inspect] against the real `patches-1.33.0.mpp`
 * bundle (a real Morphe patch jar for YouTube/YouTube Music/Reddit, checked
 * into `src/test/resources/patching/`), rather than a hand-built stub jar -
 * this is the one file whose shape the `default`/`compatibility`/`name`
 * mapping documented in `PatchLibrary.kt` depends on, so it's worth covering
 * with the real artifact instead of a fake `Patch<*>`.
 *
 * The concrete values asserted below (patch count, names, options, package
 * versions) were captured by running `PatchLibrary().inspect()` against this
 * exact file - re-verify them if `patches-1.33.0.mpp` is ever swapped for a
 * different version of the bundle.
 */
class PatchLibraryTest {

    companion object {
        private lateinit var mppFile: File

        @JvmStatic
        @BeforeAll
        fun locateFixture() {
            val resource = requireNotNull(
                PatchLibraryTest::class.java.classLoader.getResource("patching/patches-1.33.0.mpp")
            ) { "Missing test resource patching/patches-1.33.0.mpp" }
            mppFile = File(resource.toURI())
        }
    }

    private val youtube = "com.google.android.youtube"
    private val youtubeMusic = "com.google.android.apps.youtube.music"
    private val reddit = "com.reddit.frontpage"

    @Test
    fun `inspect loads every patch in the real bundle without throwing`() {
        val patches = assertDoesNotThrow { PatchLibrary().inspect(mppFile) }
        assertEquals(121, patches.size)
    }

    @Test
    fun `every loaded patch has a non-blank name and description`() {
        val patches = PatchLibrary().inspect(mppFile)
        for (patch in patches) {
            assertFalse(patch.name.isNullOrBlank(), "patch with description '${patch.description}' has no name")
            assertFalse(patch.description.isNullOrBlank(), "patch '${patch.name}' has no description")
        }
    }

    @Test
    fun `universal patches are exactly the three package-less patches, all disabled by default`() {
        val patches = PatchLibrary().inspect(mppFile)
        val universal = patches.filter { it.packages.isEmpty() }

        assertEquals(
            setOf("Change package name", "Disable Play Store updates", "Override certificate pinning"),
            universal.map { it.name }.toSet(),
        )
        assertTrue(universal.all { !it.enabled }, "expected every universal patch to be disabled by default")
    }

    @Test
    fun `package-restricted patches only reference the three apps this bundle targets`() {
        val patches = PatchLibrary().inspect(mppFile)
        val restrictedPackageNames = patches.flatMap { it.packages }.map { it.packageName }.toSet()
        assertEquals(setOf(youtube, youtubeMusic, reddit), restrictedPackageNames)
    }

    @Test
    fun `each app's compatible versions are consistent across every patch that targets it`() {
        val patches = PatchLibrary().inspect(mppFile)
        val versionsByPackage = patches.flatMap { it.packages }
            .groupBy({ it.packageName }, { it.versions })
            .mapValues { (_, versionLists) -> versionLists.toSet() }

        assertEquals(1, versionsByPackage.getValue(youtube).size, "expected one consistent version list for youtube")
        assertEquals(1, versionsByPackage.getValue(youtubeMusic).size, "expected one consistent version list for youtube music")
        assertEquals(1, versionsByPackage.getValue(reddit).size, "expected one consistent version list for reddit")

        assertEquals(
            listOf("21.26.360", "21.25.523", "21.24.360", "21.05.265", "20.51.39", "20.31.42", "20.21.37"),
            versionsByPackage.getValue(youtube).single(),
        )
        assertEquals(
            listOf("9.26.51", "9.25.50", "9.24.51", "9.15.51", "8.51.51", "7.29.52"),
            versionsByPackage.getValue(youtubeMusic).single(),
        )
        assertEquals(
            listOf("2026.26.0", "2026.25.0", "2026.24.0", "2026.14.0", "2026.04.0"),
            versionsByPackage.getValue(reddit).single(),
        )
    }

    @Test
    fun `a universal patch has no packages and no options`() {
        val patches = PatchLibrary().inspect(mppFile)
        val patch = patches.single { it.name == "Disable Play Store updates" }

        assertFalse(patch.enabled)
        assertTrue(patch.packages.isEmpty())
        assertTrue(patch.options.isEmpty())
    }

    @Test
    fun `a required string option carries its default and single allowed value`() {
        val patches = PatchLibrary().inspect(mppFile)
        val patch = patches.single { it.name == "Custom branding name for Reddit" }

        assertFalse(patch.enabled)
        assertEquals(listOf(reddit), patch.packages.map { it.packageName })

        val option = patch.options.single()
        assertEquals("appName", option.key)
        assertTrue(option.required)
        assertEquals("Reddit Morphe", option.default)
        assertEquals("kotlin.String", option.type)
        assertEquals(setOf("Default"), option.possibleValues?.keys)
    }

    @Test
    fun `a multi-option patch exposes every option with its own type and default`() {
        val patches = PatchLibrary().inspect(mppFile)
        val patch = patches.single { it.name == "Change package name" }

        assertFalse(patch.enabled)
        assertTrue(patch.packages.isEmpty())

        val optionsByKey = patch.options.associateBy { it.key }
        assertEquals(setOf("packageName", "updatePermissions", "updateProviders"), optionsByKey.keys)

        val packageName = optionsByKey.getValue("packageName")
        assertTrue(packageName.required)
        assertEquals("Default", packageName.default)
        assertEquals("kotlin.String", packageName.type)

        val updatePermissions = optionsByKey.getValue("updatePermissions")
        assertFalse(updatePermissions.required)
        assertEquals("false", updatePermissions.default)
        assertEquals("kotlin.Boolean", updatePermissions.type)
        assertEquals(null, updatePermissions.possibleValues)

        val updateProviders = optionsByKey.getValue("updateProviders")
        assertFalse(updateProviders.required)
        assertEquals("false", updateProviders.default)
        assertEquals("kotlin.Boolean", updateProviders.type)
    }

    @Test
    fun `an enum-style option lists every possible value`() {
        val patches = PatchLibrary().inspect(mppFile)
        val patch = patches.first { it.name == "Theme" && it.packages.any { pkg -> pkg.packageName == youtube } }

        val darkTheme = patch.options.single { it.key == "darkThemeBackgroundColor" }
        assertEquals("@android:color/black", darkTheme.default)
        assertTrue(darkTheme.possibleValues!!.keys.containsAll(listOf("Pure black", "Material You (Neutral)", "Catppuccin (Mocha)")))

        // Music's "Theme" patch only exposes the dark-theme option, unlike YouTube's.
        val musicTheme = patches.single { it.name == "Theme" && it.packages.any { pkg -> pkg.packageName == youtubeMusic } }
        assertEquals(setOf("darkThemeBackgroundColor"), musicTheme.options.map { it.key }.toSet())
    }

    @Test
    fun `same-named patches for different apps are distinct entries with their own packages`() {
        val patches = PatchLibrary().inspect(mppFile)
        val gmsCoreSupport = patches.filter { it.name == "GmsCore support" }

        assertEquals(2, gmsCoreSupport.size)
        assertTrue(gmsCoreSupport.all { it.enabled })
        assertEquals(
            setOf(youtube, youtubeMusic),
            gmsCoreSupport.flatMap { it.packages }.map { it.packageName }.toSet(),
        )
    }

    @Test
    fun `inspect with a set of files matches inspect with a single file`() {
        val fromSet = PatchLibrary().inspect(setOf(mppFile))
        val fromSingle = PatchLibrary().inspect(mppFile)
        assertEquals(fromSingle.map { it.name }.sortedBy { it }, fromSet.map { it.name }.sortedBy { it })
    }
}
