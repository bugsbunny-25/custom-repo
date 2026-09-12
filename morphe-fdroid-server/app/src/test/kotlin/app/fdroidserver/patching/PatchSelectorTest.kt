package app.fdroidserver.patching

import app.morphe.patcher.patch.Option
import app.morphe.patcher.patch.Options
import app.morphe.patcher.patch.Patch
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers what's left in [PatchSelector] after patch filtering moved into the
 * vendored engine: turning our selection map into the engine's enabled/disabled
 * name sets, and typing the admin UI's string option values.
 *
 * The filtering this class used to do (defaults, package compatibility) is now
 * `PatchEngine.filterPatches`, exercised against morphe-desktop's own tests
 * upstream - it isn't re-tested here.
 */
class PatchSelectorTest {

    private fun fakePatch(name: String, options: Map<String, Option<*>> = emptyMap()): Patch<*> {
        val patch = mockk<Patch<*>>()
        val optionsMock = mockk<Options>()
        every { optionsMock.containsKey(any<String>()) } answers { options.containsKey(firstArg()) }
        every { optionsMock.get(any<String>()) } answers { options.getValue(firstArg()) }
        every { patch.name } returns name
        every { patch.options } returns optionsMock
        return patch
    }

    private fun fakeOption(currentValue: Any?, default: Any? = currentValue): Option<Any?> {
        val option = mockk<Option<Any?>>(relaxed = true)
        every { option.value } returns currentValue
        every { option.default } returns default
        return option
    }

    @Test
    fun `selection map splits into the engine's enabled and disabled sets`() {
        val selection = PatchSelector.splitSelection(
            mapOf("hide-ads" to true, "custom-branding" to false, "spoof-client" to true),
        )

        assertEquals(setOf("hide-ads", "spoof-client"), selection.enabled)
        assertEquals(setOf("custom-branding"), selection.disabled)
    }

    @Test
    fun `patches absent from the selection map land in neither set so their own default applies`() {
        val selection = PatchSelector.splitSelection(emptyMap())

        assertTrue(selection.enabled.isEmpty())
        assertTrue(selection.disabled.isEmpty())
    }

    @Test
    fun `option override is converted to the option's own type`() {
        val bool = fakeOption(currentValue = false)
        val int = fakeOption(currentValue = 3)
        val string = fakeOption(currentValue = "left")
        val patch = fakePatch("hide-ads", mapOf("aggressive" to bool, "delay" to int, "position" to string))

        val converted = PatchSelector.convertOptions(
            setOf(patch),
            mapOf("hide-ads" to mapOf("aggressive" to "true", "delay" to "10", "position" to "right")),
        )

        assertEquals(mapOf("aggressive" to true, "delay" to 10, "position" to "right"), converted["hide-ads"])
    }

    @Test
    fun `list-valued option accepts both bracketed and bare comma-separated values`() {
        val list = fakeOption(currentValue = listOf("a"))
        val patch = fakePatch("filters", mapOf("keywords" to list))

        val bracketed = PatchSelector.convertOptions(
            setOf(patch),
            mapOf("filters" to mapOf("keywords" to "[one, \"two\"]")),
        )
        val bare = PatchSelector.convertOptions(
            setOf(patch),
            mapOf("filters" to mapOf("keywords" to "one, two")),
        )

        assertEquals(mapOf("keywords" to listOf("one", "two")), bracketed["filters"])
        assertEquals(mapOf("keywords" to listOf("one", "two")), bare["filters"])
    }

    @Test
    fun `a value that doesn't parse as the option's type is passed through unchanged`() {
        // morphe-patcher's own setOptions then rejects it with a warning
        // rather than this silently coercing it to something wrong.
        val int = fakeOption(currentValue = 3)
        val patch = fakePatch("hide-ads", mapOf("delay" to int))

        val converted = PatchSelector.convertOptions(
            setOf(patch),
            mapOf("hide-ads" to mapOf("delay" to "soon")),
        )

        assertEquals(mapOf("delay" to "soon"), converted["hide-ads"])
    }

    @Test
    fun `overrides for unknown patches and unknown option keys are dropped`() {
        val patch = fakePatch("hide-ads", mapOf("aggressive" to fakeOption(currentValue = false)))

        val converted = PatchSelector.convertOptions(
            setOf(patch),
            mapOf(
                "hide-ads" to mapOf("stale-key" to "value"),
                "removed-patch" to mapOf("aggressive" to "true"),
            ),
        )

        // "hide-ads" had only a stale key, so it drops out entirely rather
        // than being passed to the engine as an empty option map.
        assertTrue(converted.isEmpty())
    }

    @Test
    fun `an option with no value and no default is passed through as a string`() {
        val untyped = fakeOption(currentValue = null, default = null)
        val patch = fakePatch("hide-ads", mapOf("label" to untyped))

        val converted = PatchSelector.convertOptions(
            setOf(patch),
            mapOf("hide-ads" to mapOf("label" to "42")),
        )

        assertEquals(mapOf("label" to "42"), converted["hide-ads"])
    }
}
