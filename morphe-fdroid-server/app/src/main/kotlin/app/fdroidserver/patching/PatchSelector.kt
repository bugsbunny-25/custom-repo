package app.fdroidserver.patching

import app.morphe.patcher.patch.Option
import app.morphe.patcher.patch.Patch

/**
 * Applies our config's `patch_selection`/`option_overrides` on top of a
 * loaded patch set's own defaults (`Patch.default` / `Option.default`),
 * replacing the old Python version's `--include=`/`--exclude=`/`--options=`
 * CLI flag templating (guessed, never confirmed against the real CLI) with
 * direct field access on typed objects (confirmed real API).
 */
object PatchSelector {

    fun applyOverrides(
        patches: Set<Patch<*>>,
        selection: Map<String, Boolean>,
        optionOverrides: Map<String, Map<String, String>>,
        packageName: String,
    ): Set<Patch<*>> {
        val filtered = patches.filter { patch ->
            val name = patch.name ?: return@filter false
            if (!isCompatibleWithPackage(patch, packageName)) return@filter false
            selection[name] ?: patch.default
        }.toSet()

        filtered.forEach { patch ->
            val name = patch.name ?: return@forEach
            val overridesForThisPatch = optionOverrides[name] ?: return@forEach
            overridesForThisPatch.forEach { (key, rawValue) ->
                // Options.get() is declared non-null (throws rather than
                // returning null for a missing key per its Kotlin metadata,
                // confirmed at compile time) - guard with containsKey first
                // so a stale/renamed option key in config.yml doesn't crash
                // the whole patch run.
                if (!patch.options.containsKey(key)) return@forEach
                setOptionValue(patch.options[key], rawValue)
            }
        }
        return filtered
    }

    /**
     * A patch with no `compatibility` list, or with any entry whose
     * `packageName` is null, is universal (applies to every app). Otherwise
     * it only applies to the packages explicitly listed - a .mpp file
     * commonly bundles patches for several unrelated apps, so without this
     * check every default-enabled patch in the file (regardless of which
     * app it targets) would get applied to whatever APK we're patching.
     */
    private fun isCompatibleWithPackage(patch: Patch<*>, packageName: String): Boolean {
        val compatibility = patch.compatibility ?: return true
        return compatibility.any { it.packageName == null || it.packageName == packageName }
    }

    /**
     * `Options` is `Map<String, Option<*>>` - a star-projected `Option<*>`'s
     * `value` setter isn't directly callable (Kotlin can't guarantee `T`
     * safety through a `*` projection), so this needs an explicit unchecked
     * cast. This is safe in practice because we're only ever writing back a
     * value converted to match the *current* value's own runtime type (see
     * [convertValue]), the same type the option already holds.
     */
    @Suppress("UNCHECKED_CAST")
    private fun setOptionValue(option: Option<*>, rawValue: String) {
        val converted = convertValue(rawValue, option.value ?: option.default)
        (option as Option<Any?>).value = converted
    }

    /**
     * Our admin UI only ever stores option override values as plain strings
     * (they come from a text input) - convert back to whatever primitive
     * type the option's current/default value actually is, so e.g. a
     * Boolean option keeps being a real Boolean rather than becoming the
     * string `"true"`.
     *
     * TODO: this only handles common scalar cases (Boolean/Int/Long/Float/
     * Double/String). Options backed by lists or enum-style `values` maps
     * may need richer handling - this hasn't been exercised against a real
     * `.mpp` file with such an option yet.
     */
    private fun convertValue(raw: String, sample: Any?): Any = when (sample) {
        is Boolean -> raw.toBooleanStrictOrNull() ?: raw
        is Int -> raw.toIntOrNull() ?: raw
        is Long -> raw.toLongOrNull() ?: raw
        is Float -> raw.toFloatOrNull() ?: raw
        is Double -> raw.toDoubleOrNull() ?: raw
        else -> raw
    }
}
