package app.fdroidserver.patching

import app.morphe.patcher.patch.Patch
import app.morphe.patcher.patch.loadPatchesFromJar
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Introspects `.mpp` patch files by calling morphe-patcher's
 * `loadPatchesFromJar` directly - no CLI, no text parsing. This is the
 * direct replacement for the old Python `morphe_inspect.py`, which shelled
 * out to `morphe-cli list-patches ...` and regex-parsed its stdout (a
 * process that had to be reverse-engineered more than once and was still
 * only a best-effort guess at the real output format). Calling the library
 * directly gets typed data with none of that fragility.
 *
 * NOTE on API surface: coded against the `Patch`/`Option`/`Compatibility`
 * shape in morphe-patcher `1.6.0-dev.1` (the version pinned in
 * `gradle/libs.versions.toml`): `Patch.default` (default-enabled flag) and
 * `Patch.compatibility` (`List<Compatibility>?`, each with a nullable
 * `packageName` and a list of `AppTarget`s carrying the version string).
 * The older `Patch.use` / `Patch.compatiblePackages` / `Option.key` /
 * `Option.title` accessors this file used to read still exist but are
 * `@Deprecated`; when bumping the pinned version again, re-check whether
 * `default`/`compatibility`/`name` are still the current shape.
 */
class PatchLibrary {

    @Serializable
    data class PackageInfo(
        // Wire name is "package" (not "package_name") to match the admin
        // UI's JS, which was ported from the Python version verbatim and
        // reads `pkg.package` - "package" itself can't be a Kotlin
        // identifier (reserved keyword), hence the SerialName override
        // rather than just renaming the Kotlin property.
        @SerialName("package") val packageName: String,
        val versions: List<String>, // empty == compatible with any version of this package
    )

    @Serializable
    data class OptionInfo(
        val key: String,
        val title: String,
        val description: String?,
        val required: Boolean,
        val default: String?,
        val type: String,
        val possibleValues: Map<String, String>?, // null if not an enum-style option
    )

    @Serializable
    data class PatchInfo(
        val name: String?,
        val description: String?,
        // Named "enabled" (not "enabledByDefault"/"enabled_by_default") to
        // match the ported admin UI JS's `subPatch.enabled` / `p.enabled`.
        val enabled: Boolean,
        val packages: List<PackageInfo>, // empty list == universal / no package restriction
        val options: List<OptionInfo>,
    )

    /**
     * Loads every patch inside [mppFiles] as typed metadata, for the admin
     * UI's "View Packages" (grouped by package, client-side) and "Configure"
     * (filtered to one app's package_name, client-side) features. Those
     * client-side behaviors are unchanged from the Python version - only the
     * data source changed.
     */
    fun inspect(mppFiles: Set<File>): List<PatchInfo> =
        loadPatchesFromJar(mppFiles).map { it.toPatchInfo() }

    fun inspect(mppFile: File): List<PatchInfo> = inspect(setOf(mppFile))

    // Uses morphe-patcher's current (non-deprecated) shape: `Patch.default`,
    // `Patch.compatibility` (a `List<Compatibility>` of per-package targets)
    // and `Option.name`. Only the flat package name + version list is
    // surfaced here since that's all the admin UI needs; per-target
    // metadata (minSdk, isExperimental, etc. from `AppTarget`) is dropped -
    // revisit if that becomes useful to surface.
    private fun Patch<*>.toPatchInfo(): PatchInfo = PatchInfo(
        name = name,
        description = description,
        enabled = default,
        packages = compatibility?.mapNotNull { compat ->
            val packageName = compat.packageName ?: return@mapNotNull null
            // A target with a null version means "any version" - matches
            // the empty-list-means-any-version convention PackageInfo uses.
            val versions = if (compat.targets.any { it.version == null }) {
                emptyList()
            } else {
                compat.targets.map { it.version!! }
            }
            PackageInfo(packageName, versions)
        } ?: emptyList(),
        options = options.values.map { option ->
            OptionInfo(
                key = option.name,
                title = option.name,
                description = option.description,
                required = option.required,
                default = option.default?.toString(),
                type = option.type.toString(),
                possibleValues = option.values?.mapValues { it.value.toString() },
            )
        },
    )
}
