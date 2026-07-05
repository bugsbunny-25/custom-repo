package app.fdroidserver.patching

import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.Option
import app.morphe.patcher.patch.Options
import app.morphe.patcher.patch.Patch
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PatchSelectorTest {

    private fun fakePatch(
        name: String,
        defaultEnabled: Boolean,
        options: Map<String, Option<*>> = emptyMap(),
        compatibility: List<Compatibility>? = null,
    ): Patch<*> {
        val patch = mockk<Patch<*>>()
        val optionsMock = mockk<Options>()
        every { optionsMock.containsKey(any<String>()) } answers { options.containsKey(firstArg()) }
        every { optionsMock.get(any<String>()) } answers { options.getValue(firstArg()) }
        every { patch.name } returns name
        every { patch.default } returns defaultEnabled
        every { patch.options } returns optionsMock
        every { patch.compatibility } returns compatibility
        return patch
    }

    private fun fakeCompatibility(packageName: String?): Compatibility {
        val compat = mockk<Compatibility>()
        every { compat.packageName } returns packageName
        return compat
    }

    private fun fakeOption(currentValue: Any?, default: Any? = currentValue): Option<Any?> {
        val option = mockk<Option<Any?>>(relaxed = true)
        every { option.value } returns currentValue
        every { option.default } returns default
        return option
    }

    @Test
    fun `default-enabled patch stays enabled with no override`() {
        val patch = fakePatch("hide-ads", defaultEnabled = true)
        val result = PatchSelector.applyOverrides(setOf(patch), emptyMap(), emptyMap(), "com.example.app")
        assertTrue(result.contains(patch))
    }

    @Test
    fun `default-disabled patch stays disabled with no override`() {
        val patch = fakePatch("custom-branding", defaultEnabled = false)
        val result = PatchSelector.applyOverrides(setOf(patch), emptyMap(), emptyMap(), "com.example.app")
        assertFalse(result.contains(patch))
    }

    @Test
    fun `patch_selection overrides default enabled state in both directions`() {
        val enabledByDefault = fakePatch("hide-ads", defaultEnabled = true)
        val disabledByDefault = fakePatch("custom-branding", defaultEnabled = false)

        val result = PatchSelector.applyOverrides(
            setOf(enabledByDefault, disabledByDefault),
            selection = mapOf("hide-ads" to false, "custom-branding" to true),
            optionOverrides = emptyMap(),
            packageName = "com.example.app",
        )

        assertFalse(result.contains(enabledByDefault))
        assertTrue(result.contains(disabledByDefault))
    }

    @Test
    fun `option override converts string value to the option's own type and sets it`() {
        val option = fakeOption(currentValue = false)
        val patch = fakePatch("hide-ads", defaultEnabled = true, options = mapOf("aggressive" to option))

        PatchSelector.applyOverrides(
            setOf(patch),
            selection = emptyMap(),
            optionOverrides = mapOf("hide-ads" to mapOf("aggressive" to "true")),
            packageName = "com.example.app",
        )

        io.mockk.verify { option.value = true }
    }

    @Test
    fun `option override for an unknown key is skipped without throwing`() {
        val patch = fakePatch("hide-ads", defaultEnabled = true, options = emptyMap())

        val result = PatchSelector.applyOverrides(
            setOf(patch),
            selection = emptyMap(),
            optionOverrides = mapOf("hide-ads" to mapOf("stale-key" to "value")),
            packageName = "com.example.app",
        )

        assertEquals(setOf(patch), result)
    }

    @Test
    fun `patch restricted to a different package is excluded even if default-enabled`() {
        val patch = fakePatch(
            "hide-ads",
            defaultEnabled = true,
            compatibility = listOf(fakeCompatibility("com.other.app")),
        )

        val result = PatchSelector.applyOverrides(setOf(patch), emptyMap(), emptyMap(), "com.example.app")

        assertFalse(result.contains(patch))
    }

    @Test
    fun `patch restricted to the target package is included`() {
        val patch = fakePatch(
            "hide-ads",
            defaultEnabled = true,
            compatibility = listOf(fakeCompatibility("com.example.app")),
        )

        val result = PatchSelector.applyOverrides(setOf(patch), emptyMap(), emptyMap(), "com.example.app")

        assertTrue(result.contains(patch))
    }

    @Test
    fun `universal patch entry within a compatibility list is included regardless of package`() {
        val patch = fakePatch(
            "hide-ads",
            defaultEnabled = true,
            compatibility = listOf(fakeCompatibility(null)),
        )

        val result = PatchSelector.applyOverrides(setOf(patch), emptyMap(), emptyMap(), "com.example.app")

        assertTrue(result.contains(patch))
    }
}
