package app.fdroidserver.patching

import app.morphe.engine.PatchBundleIncompatibleException
import app.morphe.engine.PatcherCompatibility
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
 * Compatibility data comes from [appVersionSupport], which reads
 * `Patch.compatibility` itself rather than through the engine's
 * `compatibleVersionsForDisplay`: that helper drops `AppTarget(version = null)`
 * ("any version"), which is how a bundle declares experimental support for
 * versions beyond its tested list. Older `.mpp` bundles that still use the
 * deprecated `compatiblePackages` shape are covered too - morphe-patcher
 * converts it into `compatibility` when the patch is constructed.
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
        // Specific versions declared for this package, stable and
        // experimental. Empty when the patch only declares "any version".
        val versions: List<String>,
        // Subset of [versions] that morphe-patcher's AppTarget.isExperimental
        // flags as only experimentally supported. Excluded by default when
        // deciding what to patch (see PatchScheduler) unless a target opts
        // in via PatchAttachment.includeExperimentalVersions.
        val experimentalVersions: List<String> = emptyList(),
        // An `AppTarget(version = null)`: any version of the package is
        // supported, as a stable target...
        val anyVersion: Boolean,
        // ...or only experimentally (usually alongside a list of tested
        // [versions]); honoured only when the attachment opts in.
        val anyVersionExperimental: Boolean,
    ) {
        fun toVersionSupport() = AppVersionSupport(versions, experimentalVersions, anyVersion, anyVersionExperimental)
    }

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
        return PatchInfo(
            name = name,
            description = description,
            enabled = default,
            packages = appVersionSupport().map { (packageName, support) ->
                PackageInfo(
                    packageName = packageName,
                    versions = support.versions,
                    experimentalVersions = support.experimentalVersions,
                    anyVersion = support.anyVersion,
                    anyVersionExperimental = support.anyVersionExperimental,
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
}
