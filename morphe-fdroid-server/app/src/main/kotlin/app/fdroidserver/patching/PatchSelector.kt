package app.fdroidserver.patching

import app.morphe.patcher.patch.Option
import app.morphe.patcher.patch.Patch

/**
 * Translates our config's `patch_selection`/`option_overrides` into the
 * shapes [app.morphe.engine.PatchEngine.Config] takes.
 *
 * Patch *filtering* itself (defaults, package compatibility, app-version
 * compatibility) is no longer done here - the vendored engine's
 * `filterPatches` does it, the same way morphe-desktop does. What's left is
 * the part that's genuinely ours: our selection map is keyed by patch name
 * with an explicit on/off per entry, while the engine takes separate
 * enabled/disabled name sets, and our option overrides arrive as plain
 * strings from a text input in the admin UI while morphe-patcher's options
 * are typed.
 */
object PatchSelector {

    /** The engine's two name sets. Names absent from both keep the patch's
     * own `default` flag (the engine's non-exclusive mode), which is exactly
     * what our `selection[name] ?: patch.default` used to mean. */
    data class Selection(val enabled: Set<String>, val disabled: Set<String>)

    fun splitSelection(selection: Map<String, Boolean>): Selection = Selection(
        enabled = selection.filterValues { it }.keys,
        disabled = selection.filterNot { it.value }.keys,
    )

    /**
     * Converts `option_overrides` (patch name -> option key -> string) into
     * the typed `patchOptions` map the engine hands to morphe-patcher's
     * `setOptions`.
     *
     * [patches] is needed to find each option's declared type: the admin UI
     * only ever stores strings, so a Boolean option would otherwise be
     * assigned `"true"` and morphe-patcher's own `setOptions` would reject
     * the value (logging a warning) and silently leave the option unset.
     *
     * Unknown patch names and unknown option keys are dropped here rather
     * than passed through - `setOptions` tolerates them, but dropping them
     * keeps the warning in one place and means a stale override left behind
     * by an `.mpp` update can't look like a real option.
     */
    fun convertOptions(
        patches: Set<Patch<*>>,
        optionOverrides: Map<String, Map<String, String>>,
    ): Map<String, Map<String, Any?>> {
        if (optionOverrides.isEmpty()) return emptyMap()
        val patchesByName = patches.mapNotNull { patch -> patch.name?.let { it to patch } }.toMap()

        return optionOverrides.mapNotNull { (patchName, overrides) ->
            val patch = patchesByName[patchName] ?: return@mapNotNull null
            val converted = overrides.mapNotNull { (key, rawValue) ->
                // Options.get() is declared non-null and *throws* for a missing
                // key rather than returning null, so a stale override left
                // behind by an .mpp update has to be filtered with containsKey
                // first - an elvis here would never run.
                if (!patch.options.containsKey(key)) return@mapNotNull null
                key to convertValue(rawValue, patch.options[key])
            }.toMap()
            if (converted.isEmpty()) null else patchName to converted
        }.toMap()
    }

    /**
     * Converts a single override string to the type the option actually
     * holds, inferred from its current value or its default.
     *
     * Only scalars and lists of scalars are handled. An option with no
     * default and no value gives nothing to infer from, so the string is
     * passed through unchanged and morphe-patcher decides whether it fits.
     */
    private fun convertValue(raw: String, option: Option<*>): Any? {
        val sample = option.value ?: option.default
        return when (sample) {
            is Boolean -> raw.toBooleanStrictOrNull() ?: raw
            is Int -> raw.toIntOrNull() ?: raw
            is Long -> raw.toLongOrNull() ?: raw
            is Float -> raw.toFloatOrNull() ?: raw
            is Double -> raw.toDoubleOrNull() ?: raw
            is String -> raw
            // List-valued options (e.g. a patch's list of filter strings).
            // Accepts either a bracketed list ("[a, b]" - the syntax
            // morphe-desktop's CLI uses for -O values) or a bare
            // comma-separated list, with each element converted to the type
            // of the sample list's own elements.
            is List<*> -> parseList(raw).map { element -> convertScalar(element, sample.firstOrNull()) }
            else -> raw
        }
    }

    private fun parseList(raw: String): List<String> {
        val inner = raw.trim().let { trimmed ->
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) trimmed.substring(1, trimmed.length - 1) else trimmed
        }
        if (inner.isBlank()) return emptyList()
        return inner.split(',').map { it.trim().trim('"', '\'') }
    }

    private fun convertScalar(raw: String, sample: Any?): Any = when (sample) {
        is Boolean -> raw.toBooleanStrictOrNull() ?: raw
        is Int -> raw.toIntOrNull() ?: raw
        is Long -> raw.toLongOrNull() ?: raw
        is Float -> raw.toFloatOrNull() ?: raw
        is Double -> raw.toDoubleOrNull() ?: raw
        else -> raw
    }
}
