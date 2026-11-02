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

    companion object {
        /**
         * The versions worth patching for a package across every enabled
         * patch in a bundle that names it ([supports], one per patch).
         *
         * A patch with a stable "any version" target (a branding patch, say)
         * is version-agnostic, so it neither narrows nor widens the list:
         * taking the first patch's list instead (what this used to do) let
         * such a patch open the door to app versions the main patches don't
         * support, which the engine then silently skipped, publishing a
         * mostly unpatched APK. Among the rest, when the attachment opts into
         * experimental versions and most of them accept any version
         * experimentally, any version goes (`"*"`); otherwise the specific
         * versions the most patches support win - the "recommended version"
         * idea the Morphe manager uses, and the intersection whenever the
         * patches agree. `"*"` when every patch is version-agnostic; empty
         * when none of them can run without the experimental opt-in.
         */
        fun mergedPatchableVersions(supports: List<AppVersionSupport>, includeExperimental: Boolean): List<String> {
            val restricting = supports.filterNot { it.anyVersion }
            if (restricting.isEmpty()) return if (supports.isNotEmpty()) listOf("*") else emptyList()

            val perPatch = restricting
                .map { it to it.patchableVersions(includeExperimental) }
                .filter { (_, versions) -> versions.isNotEmpty() }
            if (perPatch.isEmpty()) return emptyList()
            val anyExperimental = perPatch.count { (_, versions) -> "*" in versions }
            if (anyExperimental * 2 > perPatch.size) return listOf("*")

            // A patch that accepts any version experimentally still counts
            // for every version it names explicitly.
            val counts = LinkedHashMap<String, Int>()
            for ((support, versions) in perPatch) {
                val specific = if ("*" in versions) support.versions else versions
                for (v in specific.distinct()) counts[v] = (counts[v] ?: 0) + 1
            }
            if (counts.isEmpty()) return listOf("*")
            val best = counts.values.max()
            return counts.filterValues { it == best }.keys.toList()
        }
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
