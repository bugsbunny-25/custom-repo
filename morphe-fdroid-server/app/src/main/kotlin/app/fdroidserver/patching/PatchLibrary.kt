package app.fdroidserver.patching

import app.morphe.engine.PatchBundleIncompatibleException
import app.morphe.engine.PatcherCompatibility
import app.morphe.engine.compatibleVersionsForDisplay
import app.morphe.engine.patches.PatchBundleLoader
import app.morphe.engine.readableMessage
import app.morphe.patcher.patch.Patch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Introspects `.mpp` patch files for the admin UI, by loading them through the
 * vendored engine's [PatchBundleLoader] and reading the resulting typed
 * `Patch` objects - no CLI, no text parsing.
 *
 * Compatibility data is read via the engine's
 * [compatibleVersionsForDisplay] extension rather than by walking
 * `Patch.compatibility` by hand, so this agrees with what morphe-desktop shows
 * for the same file - including its fallback to the deprecated
 * `compatiblePackages` shape, which older `.mpp` bundles still use and which
 * the hand-rolled version this replaces ignored entirely (such bundles showed
 * up as "universal", i.e. compatible with every app).
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
        // Subset of [versions] that morphe-patcher's AppTarget.isExperimental
        // flags as only experimentally supported. Excluded by default when
        // deciding what to patch (see PatchScheduler) unless a target opts
        // in via PatchAttachment.includeExperimentalVersions.
        val experimentalVersions: List<String> = emptyList(),
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
     * (filtered to one app's package_name, client-side) features.
     *
     * A bundle built against a newer morphe-patcher than this build ships
     * fails to load with a [java.lang.Error] (NoSuchMethodError /
     * NoClassDefFoundError), which would escape a `catch (e: Exception)` in
     * the callers and take down the request. Those are caught here and
     * re-thrown as a [PatchBundleIncompatibleException] carrying the engine's
     * user-facing "needs patcher X, this build ships Y" message, the same way
     * morphe-desktop surfaces it.
     */
    fun inspect(mppFiles: Set<File>): List<PatchInfo> = try {
        PatchBundleLoader.loadFlat(mppFiles).map { it.toPatchInfo() }
    } catch (t: Throwable) {
        val incompatible = mppFiles.firstNotNullOfOrNull { PatcherCompatibility.incompatibilityMessage(it) }
        if (incompatible != null) throw PatchBundleIncompatibleException(incompatible)
        if (t is Exception) throw t
        throw IllegalStateException(t.readableMessage(), t)
    }

    fun inspect(mppFile: File): List<PatchInfo> = inspect(setOf(mppFile))

    private fun Patch<*>.toPatchInfo(): PatchInfo {
        // Two passes over the same data: everything, then only the
        // non-experimental targets. The difference is the experimental set -
        // the engine's helper doesn't expose the flag itself, only the
        // filtered lists.
        val allVersions = versionsByPackage(includeExperimental = true)
        val stableVersions = versionsByPackage(includeExperimental = false)

        return PatchInfo(
            name = name,
            description = description,
            enabled = default,
            packages = allVersions.map { (packageName, versions) ->
                val stable = stableVersions[packageName].orEmpty().toSet()
                PackageInfo(
                    packageName = packageName,
                    versions = versions,
                    experimentalVersions = versions.filterNot { it in stable },
                )
            },
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

    /**
     * Package name -> declared versions, merging the engine's per-compatibility-entry
     * pairs (a patch can declare the same package more than once) and dropping
     * universal entries, whose package name is null. An empty version list is
     * kept as-is: it means "any version of this package", the convention
     * [PackageInfo.versions] documents.
     */
    private fun Patch<*>.versionsByPackage(includeExperimental: Boolean): Map<String, List<String>> =
        compatibleVersionsForDisplay(includeExperimental)
            .mapNotNull { (packageName, versions) -> packageName?.let { it to versions } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, versionLists) -> versionLists.flatten().distinct() }
}
