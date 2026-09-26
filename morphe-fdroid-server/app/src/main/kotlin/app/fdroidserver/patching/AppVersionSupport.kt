package app.fdroidserver.patching

import app.morphe.patcher.patch.Patch

/**
 * What a patch declares about one app package's versions, read straight off
 * morphe-patcher's `Patch.compatibility` / `AppTarget` list.
 *
 * The engine's helpers (`supportedVersionsFor`, `compatibleVersionsForDisplay`)
 * only return the targets that carry a version string, so an
 * `AppTarget(version = null)` - morphe-patcher's "any version" - is dropped
 * whenever the same package also lists specific versions. That is exactly how
 * a bundle says "these versions are tested, any other version works
 * experimentally" (`AppTarget(version = null, isExperimental = true)`), so the
 * opt-in to experimental versions could never reach those versions. This
 * keeps the null targets as the [anyVersion] / [anyVersionExperimental] flags.
 *
 * [versions] is every specific version declared (stable and experimental, in
 * declaration order); [experimentalVersions] is the subset declared only as
 * experimental. A version declared both ways counts as stable.
 */
data class AppVersionSupport(
    val versions: List<String>,
    val experimentalVersions: List<String>,
    val anyVersion: Boolean,
    val anyVersionExperimental: Boolean,
) {
    /** Versions (or the `"*"` wildcard) this package may be patched at, with
     * or without experimental targets. Empty when only experimental targets
     * are declared and [includeExperimental] is off. */
    fun patchableVersions(includeExperimental: Boolean): List<String> = when {
        anyVersion || (includeExperimental && anyVersionExperimental) -> listOf("*")
        includeExperimental -> versions
        else -> versions.filterNot { it in experimentalVersions }
    }

    /** Whether [version] is declared, honouring the experimental flag. */
    fun supports(version: String, includeExperimental: Boolean): Boolean {
        if (anyVersion || (includeExperimental && anyVersionExperimental)) return true
        if (version !in versions) return false
        return includeExperimental || version !in experimentalVersions
    }
}

/** Per-package version support for every package this patch names. Universal
 * compatibility entries (no package name) are left out. */
fun Patch<*>.appVersionSupport(): Map<String, AppVersionSupport> {
    val entries = compatibility.orEmpty().filter { it.packageName != null }
    return entries.groupBy { it.packageName!! }.mapValues { (_, sameName) ->
        val targets = sameName.flatMap { it.targets }
        val stable = targets.filterNot { it.isExperimental }
        val experimental = targets.filter { it.isExperimental }
        val stableVersions = stable.mapNotNull { it.version }.toSet()
        AppVersionSupport(
            versions = targets.mapNotNull { it.version }.distinct(),
            experimentalVersions = experimental.mapNotNull { it.version }
                .filterNot { it in stableVersions }
                .distinct(),
            anyVersion = stable.any { it.version == null },
            anyVersionExperimental = experimental.any { it.version == null },
        )
    }
}

/**
 * Whether this patch should run on [packageName] at [versionName]: a patch
 * with no compatibility at all, or only universal entries, runs anywhere; a
 * patch that names other packages but not this one never does; otherwise the
 * version has to be declared for this package (see [AppVersionSupport.supports]).
 * Handed to the vendored engine as its version filter - see
 * [PatchApplier.apply].
 */
fun Patch<*>.supportsAppVersion(packageName: String, versionName: String, includeExperimental: Boolean): Boolean {
    val compat = compatibility
    if (compat.isNullOrEmpty()) return true
    val support = appVersionSupport()[packageName]
        ?: return compat.any { it.packageName == null }
    return support.supports(versionName, includeExperimental)
}
