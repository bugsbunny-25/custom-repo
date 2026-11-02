package app.fdroidserver.patching

import app.fdroidserver.patching.PatchUpdatePolicy.Record
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers the "keep the newest (app version, bundle version) combo and only
 * patch when one of them moves up" rule in [PatchUpdatePolicy], and the
 * [VersionOrder] it's built on.
 */
class PatchUpdatePolicyTest {

    private fun published(version: String, bundle: String) =
        Record(version, bundle, "app__patch__$version.apk")

    private fun skipped(version: String, bundle: String) =
        Record(version, bundle, PatchUpdatePolicy.SKIPPED_PREFIX + "no arm64-v8a libraries")

    private fun select(
        candidates: List<String>,
        records: List<Record>,
        bundle: String = "1.0.0",
        pinned: Boolean = false,
    ) = PatchUpdatePolicy.selectVersion(candidates, records, bundle, pinned)

    // --- selectVersion -----------------------------------------------------

    @Test
    fun `nothing patched yet picks the highest listed version, not the first`() {
        assertEquals("20.5.0", select(listOf("20.3.0", "20.5.0", "20.4.0"), emptyList()))
    }

    @Test
    fun `the same app and bundle version is not patched again`() {
        assertNull(select(listOf("20.5.0", "20.4.0"), listOf(published("20.5.0", "1.0.0"))))
    }

    @Test
    fun `a newer app version is patched`() {
        assertEquals("20.6.0", select(listOf("20.6.0", "20.5.0"), listOf(published("20.5.0", "1.0.0"))))
    }

    @Test
    fun `a newer bundle re-patches the stored app version`() {
        assertEquals("20.5.0", select(listOf("20.5.0", "20.4.0"), listOf(published("20.5.0", "1.0.0")), bundle = "1.1.0"))
    }

    @Test
    fun `a newer bundle with a newer app version picks the newer app version`() {
        assertEquals("20.6.0", select(listOf("20.5.0", "20.6.0"), listOf(published("20.5.0", "1.0.0")), bundle = "1.1.0"))
    }

    @Test
    fun `a newer bundle never walks back to older already-patched versions`() {
        val records = listOf(published("20.3.0", "1.0.0"), published("20.4.0", "1.0.0"), published("20.5.0", "1.1.0"))
        assertNull(select(listOf("20.5.0", "20.4.0", "20.3.0"), records, bundle = "1.1.0"))
    }

    @Test
    fun `an older version re-listed out of order is not patched`() {
        assertNull(select(listOf("20.2.0", "20.5.0"), listOf(published("20.5.0", "1.0.0"))))
    }

    @Test
    fun `an older bundle version does not count as an update`() {
        assertNull(select(listOf("20.5.0"), listOf(published("20.5.0", "1.1.0")), bundle = "1.0.0"))
    }

    @Test
    fun `a skipped version is not retried, even for a newer bundle`() {
        val records = listOf(published("20.4.0", "1.0.0"), skipped("20.5.0", "1.0.0"))
        assertNull(select(listOf("20.5.0", "20.4.0"), records))
        // The bundle moving up re-patches the stored combo (20.4.0), not the
        // skipped version.
        assertEquals("20.4.0", select(listOf("20.5.0", "20.4.0"), records, bundle = "1.1.0"))
    }

    @Test
    fun `a skipped entry is not the stored combo`() {
        assertEquals("20.4.0", select(listOf("20.5.0", "20.4.0"), listOf(skipped("20.5.0", "1.0.0"))))
    }

    @Test
    fun `pinned versions are each patched once per bundle version, even older ones`() {
        val records = listOf(published("20.5.0", "1.0.0"))
        assertEquals("20.2.0", select(listOf("20.2.0", "20.5.0"), records, pinned = true))
        assertNull(select(listOf("20.5.0"), records, pinned = true))
    }

    @Test
    fun `an unorderable new version is allowed once`() {
        assertEquals("abc123", select(listOf("abc123"), listOf(published("20.5.0", "1.0.0"))))
    }

    // --- bundleMovedUp -----------------------------------------------------

    @Test
    fun `bundle versions compare numerically and ignore a leading v`() {
        assertTrue(PatchUpdatePolicy.bundleMovedUp("v1.10.0", "1.9.0"))
        assertFalse(PatchUpdatePolicy.bundleMovedUp("1.9.0", "v1.10.0"))
        assertFalse(PatchUpdatePolicy.bundleMovedUp("1.9.0", "1.9.0"))
        assertTrue(PatchUpdatePolicy.bundleMovedUp("1.3.0", "1.3.0-dev.4"))
        assertTrue(PatchUpdatePolicy.bundleMovedUp("1.3.0-dev.5", "1.3.0-dev.4"))
    }

    @Test
    fun `a bundle version recorded blank counts as moved`() {
        assertTrue(PatchUpdatePolicy.bundleMovedUp("1.0.0", ""))
        assertFalse(PatchUpdatePolicy.bundleMovedUp("", "1.0.0"))
    }

    // --- VersionOrder ------------------------------------------------------

    @Test
    fun `calendar and dotted versions order numerically`() {
        assertEquals(-1, VersionOrder.compare("2026.04.0", "2026.14.0"))
        assertEquals(1, VersionOrder.compare("20.10.1", "20.9.3"))
        assertEquals(0, VersionOrder.compare("2026.4", "2026.04.0"))
    }

    @Test
    fun `a release is newer than its pre-release`() {
        assertEquals(1, VersionOrder.compare("1.2.0", "1.2.0-beta1"))
        assertEquals(-1, VersionOrder.compare("1.2.0-beta1", "1.2.0-beta2"))
        assertEquals(-1, VersionOrder.compare("26.5.1+rc1-2026.04.08", "26.5.2+rc1-2026.04.08"))
    }

    @Test
    fun `versions that can't be ordered return null`() {
        assertNull(VersionOrder.compare("abc123", "20.5.0"))
        assertNull(VersionOrder.compare("1.2.0-beta", "1.2.0-rc"))
    }
}
