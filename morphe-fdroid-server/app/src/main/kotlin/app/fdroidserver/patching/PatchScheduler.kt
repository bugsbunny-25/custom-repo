package app.fdroidserver.patching

import app.fdroidserver.apkmirror.ApkMirrorClient
import app.fdroidserver.config.AppConfig
import app.fdroidserver.fdroidrepo.FdroidRepoManager
import java.io.File
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Orchestrates the whole patching pipeline: for each enabled patch target
 * (an app), check APKMirror for a new version matching one of its attached
 * library patches, download (and merge, if it's a bundle) the APK, apply
 * the patch via [PatchApplier], and publish the result into the patched
 * F-Droid repo - keeping only the newest 3 versions per (app, patch) pair.
 *
 * Direct Kotlin port of the orchestration in the old Python
 * `patch_checker.py`'s `check_for_updates()`. The output filename no longer
 * carries a `{lang}-{dpi}-{arch}` variant suffix the Python version had:
 * that was tied to Python's manual split-selection logic in `apk_bundle.py`,
 * which [BundleMerger] replaces with morphe-patcher's `ApkMerger` - which
 * merges every split unconditionally (see `BundleMerger`'s doc comment), so
 * there's no longer a single "selected variant" to name the file after.
 */
class PatchScheduler(
    private val appConfig: AppConfig,
    private val apkMirrorClient: ApkMirrorClient,
    private val patchLibrary: PatchLibrary,
    private val bundleMerger: BundleMerger,
    private val patchApplier: PatchApplier,
    private val patchesDir: File,
    private val patchedRepoDir: File,
    private val tmpDir: File,
    private val fdroidRepoManager: FdroidRepoManager,
    private val signing: PatchApplier.SigningConfig,
    private val logger: Logger = LoggerFactory.getLogger(PatchScheduler::class.java.name),
) {
    suspend fun checkForUpdates(): Boolean {
        val library = appConfig.patchLibraryById()
        val targets = appConfig.listEnabledPatchTargets()

        var anyUpdated = false
        for (target in targets) {
            if (checkEnabledTarget(target, library)) anyUpdated = true
        }

        if (anyUpdated) publishUpdates()
        logger.info("Patch update check completed")
        return anyUpdated
    }

    /** Runs the patching pipeline for a single enabled target, identified by
     * [targetId] - used by the admin UI's "Patch now" button, as opposed to
     * [checkForUpdates]'s scheduled sweep over every enabled target. Returns
     * whether a new patched version was published. */
    suspend fun runTargetNow(targetId: String): Boolean {
        val library = appConfig.patchLibraryById()
        val target = appConfig.listEnabledPatchTargets().firstOrNull { it.id == targetId }
        if (target == null) {
            logger.warn("$targetId: not found or not enabled, skipping manual patch run")
            return false
        }

        val updated = checkEnabledTarget(target, library)
        if (updated) publishUpdates()
        return updated
    }

    /** Patches a single [target] at a user-specified [versionPageUrl] and
     * [version] with every patch attached to it, bypassing the normal
     * version-listing and `supported_versions` matching in [checkTarget]
     * entirely - the admin UI's "Patch specific version" button uses this
     * when the user pastes a version-specific APKMirror page instead of
     * waiting for the target's own app-listing page to surface it. [version]
     * is taken as user input rather than scraped off the page, since
     * APKMirror version strings don't reliably follow a single numeric
     * pattern (dates, build hashes, "beta"/"rc" suffixes, etc.) that a regex
     * could extract for every app. Returns whether anything was published. */
    suspend fun runSpecificVersion(targetId: String, version: String, versionPageUrl: String): Boolean {
        val library = appConfig.patchLibraryById()
        val target = appConfig.listEnabledPatchTargets().firstOrNull { it.id == targetId }
        if (target == null) {
            logger.warn("$targetId: not found or not enabled, skipping specific-version patch run")
            return false
        }
        if (target.patches.isEmpty()) {
            logger.info("${target.id}: no patches attached, skipping specific-version patch run")
            return false
        }

        var updated = false
        var preparedApk: File? = null
        try {
            preparedApk = prepareApk(target.id, version, versionPageUrl)
            if (preparedApk == null) return false

            for (attachment in target.patches) {
                val libEntry = library[attachment.patchId]
                if (libEntry == null) {
                    logger.warn("${target.id}: attached patch '${attachment.patchId}' not found in library, skipping")
                    continue
                }
                if (applyPatchToVersion(target, attachment, libEntry, version, preparedApk)) updated = true
            }
        } finally {
            preparedApk?.delete()
        }

        if (updated) publishUpdates()
        return updated
    }

    private fun publishUpdates() {
        fdroidRepoManager.updateIndex(patchedRepoDir)
        fdroidRepoManager.pruneOldVersions(File(patchedRepoDir, "repo"), MAX_VERSIONS_PER_APP) { file ->
            // Filenames are {targetId}__{patchId}__{version}.apk - group by the
            // (targetId, patchId) prefix so cleanup is independent per version.
            file.nameWithoutExtension.split("__").take(2).joinToString("__")
        }
    }

    private suspend fun checkEnabledTarget(
        target: AppConfig.EnabledPatchTarget,
        library: Map<String, AppConfig.PatchLibraryEntry>,
    ): Boolean {
        if (target.apkmirrorUrl.isBlank()) {
            logger.warn("${target.id}: no apkmirror_url configured, skipping")
            return false
        }
        if (target.patches.isEmpty()) {
            logger.info("${target.id}: no patches attached, skipping")
            return false
        }

        val versions = try {
            apkMirrorClient.getVersions(target.apkmirrorUrl)
        } catch (e: Exception) {
            logger.error("${target.id}: error fetching apkmirror versions: $e")
            return false
        }

        return checkTarget(target, library, versions)
    }

    private suspend fun checkTarget(
        target: AppConfig.EnabledPatchTarget,
        library: Map<String, AppConfig.PatchLibraryEntry>,
        versions: List<ApkMirrorClient.VersionEntry>,
    ): Boolean {
        var updated = false
        val downloadedApks = mutableMapOf<String, File>() // version -> prepared (possibly merged) apk

        try {
            for (attachment in target.patches) {
                val libEntry = library[attachment.patchId]
                if (libEntry == null) {
                    logger.warn("${target.id}: attached patch '${attachment.patchId}' not found in library, skipping")
                    continue
                }

                val processed = appConfig.processedPatchCacheKeys(target.id)
                val supportedVersions = attachment.supportedVersions.ifEmpty {
                    val derived = deriveSupportedVersions(libEntry, target.packageName)
                    if (attachment.includeExperimentalVersions) {
                        derived.versions
                    } else {
                        derived.versions.filterNot { it in derived.experimentalVersions }
                    }
                }
                if (supportedVersions.isEmpty()) {
                    logger.warn("${target.id}: no supported_versions configured for patch '${attachment.patchId}' and none found in the .mpp file, skipping")
                    continue
                }

                // Concrete (non-glob) entries are versions the config explicitly
                // pins, as opposed to a "*"/"1.2.*" pattern that matches whatever
                // shows up. We don't try to numerically rank these - APKMirror
                // version strings don't reliably follow one format (calendar
                // versions, build hashes, "beta"/"rc" suffixes, ...), so "highest"
                // isn't always well-defined. Once every pinned version has been
                // patched, stop - don't let the listedCandidate fallback below
                // walk down to older, already-unprocessed versions just because
                // they're next in listing order (that used to cause each
                // subsequent pass to patch a progressively older version, which
                // then pushed the real latest out of pruneOldVersions's
                // keep-window).
                val concreteSupported = supportedVersions.filter { '*' !in it && '?' !in it }
                if (concreteSupported.isNotEmpty() && concreteSupported.all { "$it::${attachment.patchId}" in processed }) {
                    continue
                }

                val listedCandidate = versions.firstOrNull { v ->
                    val cacheKey = "${v.version}::${attachment.patchId}"
                    cacheKey !in processed && matchesSupportedVersion(v.version, supportedVersions)
                }

                // If nothing on the visible listing page matched, page through
                // APKMirror's history looking for each pinned version directly, in
                // the order they're configured (a version scrolled off the first
                // page needs finding some other way, but since we're not ranking
                // version strings, config order is the only tie-break we have).
                val pinnedCandidate = if (listedCandidate == null) {
                    concreteSupported
                        .filterNot { "$it::${attachment.patchId}" in processed }
                        .filterNot { pinned -> versions.any { it.version == pinned } }
                        .firstNotNullOfOrNull { pinned ->
                            logger.info(
                                "${target.id}: configured version $pinned for patch '${attachment.patchId}' " +
                                    "not in apkmirror listing, searching for it directly",
                            )
                            apkMirrorClient.findVersion(target.apkmirrorUrl, pinned).also {
                                if (it == null) {
                                    logger.warn(
                                        "${target.id}: could not find configured version $pinned on apkmirror " +
                                            "for patch '${attachment.patchId}'",
                                    )
                                }
                            }
                        }
                } else {
                    null
                }

                val candidate = listedCandidate ?: pinnedCandidate ?: continue

                val version = candidate.version
                logger.info("${target.id}: found new patchable version $version for patch '${attachment.patchId}'")

                val preparedApk = downloadedApks.getOrPut(version) {
                    prepareApk(target.id, version, candidate.pageUrl) ?: continue
                }

                if (applyPatchToVersion(target, attachment, libEntry, version, preparedApk)) updated = true
            }
        } finally {
            downloadedApks.values.forEach { it.delete() }
        }

        return updated
    }

    /** Applies one attached patch to an already-downloaded [preparedApk] for
     * [version], records the result, and returns whether it was published.
     * Shared by [checkTarget]'s per-version sweep and [runSpecificVersion]'s
     * direct, user-triggered single-version run. */
    private suspend fun applyPatchToVersion(
        target: AppConfig.EnabledPatchTarget,
        attachment: AppConfig.PatchAttachmentView,
        libEntry: AppConfig.PatchLibraryEntry,
        version: String,
        preparedApk: File,
    ): Boolean {
        val patchFile = File(patchesDir, libEntry.file)
        if (!patchFile.exists()) {
            logger.error("${target.id}: patch file missing: $patchFile")
            return false
        }

        val outputName = "${target.id}__${attachment.patchId}__$version.apk"
        val outputPath = File(patchedRepoDir, "repo/$outputName")
        outputPath.parentFile?.mkdirs()
        val cacheKey = "$version::${attachment.patchId}"

        val loadedPatches = patchApplier.loadPatches(patchFile)
        val patchesToApply = PatchSelector.applyOverrides(
            loadedPatches,
            attachment.patchSelection,
            attachment.optionOverrides,
            target.packageName,
        )
        val workDir = File(tmpDir, "patch-${target.id}-${attachment.patchId}-${System.currentTimeMillis()}")

        val result = try {
            patchApplier.apply(preparedApk, patchesToApply, outputPath, workDir, signing)
        } finally {
            workDir.deleteRecursively()
        }

        return when (result) {
            is PatchApplier.ApplyResult.Success -> {
                if (target.packageName.isNotBlank() && result.packageName != target.packageName) {
                    logger.error("${target.id}: package mismatch for $version (expected ${target.packageName}, got ${result.packageName}), discarding")
                    outputPath.delete()
                    return false
                }
                appConfig.recordPatchCheckedEntry(
                    targetId = target.id,
                    cacheKey = cacheKey,
                    version = version,
                    patchId = attachment.patchId,
                    patchVersion = libEntry.version,
                    output = outputName,
                )
                true
            }
            is PatchApplier.ApplyResult.Failure -> {
                logger.error("${target.id}: morphe patch '${attachment.patchId}' failed for $version: ${result.error}")
                false
            }
        }
    }

    /** Downloads the APK for [version] (merging it first if it's an .apkm
     * bundle), or null on failure. */
    private fun prepareApk(targetId: String, version: String, pageUrl: String): File? {
        val downloadInfo = apkMirrorClient.resolveDownloadUrl(pageUrl)
        if (downloadInfo == null) {
            logger.error("$targetId: failed to resolve download URL for $version")
            return null
        }
        val rawFile = File(tmpDir, "$targetId-$version-download.bin")
        if (!apkMirrorClient.downloadApk(downloadInfo.url, rawFile, downloadInfo.referer)) {
            logger.error("$targetId: failed to download $version")
            return null
        }

        if (!bundleMerger.isBundle(rawFile)) return rawFile

        logger.info("$targetId: $version is an APK bundle; merging all splits")
        val mergedFile = File(tmpDir, "$targetId-$version-merged.apk")
        return try {
            bundleMerger.merge(rawFile, mergedFile)
            mergedFile
        } catch (e: Exception) {
            logger.error("$targetId: failed to merge bundle for $version: $e")
            null
        } finally {
            rawFile.delete()
        }
    }

    /** [versions]: every version the .mpp declares support for. [experimentalVersions]:
     * the subset of those flagged experimental by morphe-patcher's `AppTarget.isExperimental` -
     * excluded from [versions] by callers unless the attachment opts in
     * (see `PatchScheduler.checkTarget`). */
    private data class DerivedVersions(val versions: List<String>, val experimentalVersions: Set<String>)

    /** When an attachment doesn't specify supported_versions, fall back to
     * whatever the .mpp file itself declares (via [PatchLibrary]), filtered
     * to the target's package_name. A package with no version list in the
     * .mpp means "any version" (represented as a single "*" pattern). */
    private fun deriveSupportedVersions(libEntry: AppConfig.PatchLibraryEntry, packageName: String): DerivedVersions {
        val patchFile = File(patchesDir, libEntry.file)
        if (!patchFile.exists()) return DerivedVersions(emptyList(), emptySet())

        val patches = try {
            patchLibrary.inspect(patchFile)
        } catch (e: Exception) {
            logger.warn("Could not read supported versions from '${libEntry.id}': $e")
            return DerivedVersions(emptyList(), emptySet())
        }

        for (patch in patches) {
            for (pkg in patch.packages) {
                if (packageName.isNotBlank() && pkg.packageName != packageName) continue
                if (pkg.versions.isEmpty()) return DerivedVersions(listOf("*"), emptySet())
                return DerivedVersions(pkg.versions, pkg.experimentalVersions.toSet())
            }
        }
        return DerivedVersions(emptyList(), emptySet())
    }

    private fun matchesSupportedVersion(version: String, supportedVersions: List<String>): Boolean {
        if (supportedVersions.isEmpty()) return false
        return supportedVersions.any { pattern ->
            pattern == "*" || pattern == version || globToRegex(pattern).matches(version)
        }
    }

    private fun globToRegex(glob: String): Regex {
        val escaped = Regex.escape(glob).replace("\\*", ".*").replace("\\?", ".")
        return Regex(escaped)
    }

    companion object {
        private const val MAX_VERSIONS_PER_APP = 3
    }
}
