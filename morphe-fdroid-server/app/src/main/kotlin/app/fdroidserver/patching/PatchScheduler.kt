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
                    deriveSupportedVersions(libEntry, target.packageName)
                }
                if (supportedVersions.isEmpty()) {
                    logger.warn("${target.id}: no supported_versions configured for patch '${attachment.patchId}' and none found in the .mpp file, skipping")
                    continue
                }

                val listedCandidate = versions.firstOrNull { v ->
                    val cacheKey = "${v.version}::${attachment.patchId}"
                    cacheKey !in processed && matchesSupportedVersion(v.version, supportedVersions)
                }

                val maxSupported = maxConcreteVersion(supportedVersions)
                val maxSupportedCandidate = if (maxSupported != null && versions.none { it.version == maxSupported }) {
                    val maxCacheKey = "$maxSupported::${attachment.patchId}"
                    if (maxCacheKey in processed) {
                        null
                    } else {
                        logger.info(
                            "${target.id}: max supported version $maxSupported for patch '${attachment.patchId}' " +
                                "not in apkmirror listing, searching for it directly",
                        )
                        apkMirrorClient.findVersion(target.apkmirrorUrl, maxSupported).also {
                            if (it == null) {
                                logger.warn(
                                    "${target.id}: could not find max supported version $maxSupported on apkmirror " +
                                        "for patch '${attachment.patchId}'",
                                )
                            }
                        }
                    }
                } else {
                    null
                }

                val candidate = maxSupportedCandidate ?: listedCandidate ?: continue

                val version = candidate.version
                logger.info("${target.id}: found new patchable version $version for patch '${attachment.patchId}'")

                val preparedApk = downloadedApks.getOrPut(version) {
                    prepareApk(target.id, version, candidate.pageUrl) ?: continue
                }

                val patchFile = File(patchesDir, libEntry.file)
                if (!patchFile.exists()) {
                    logger.error("${target.id}: patch file missing: $patchFile")
                    continue
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

                when (result) {
                    is PatchApplier.ApplyResult.Success -> {
                        if (target.packageName.isNotBlank() && result.packageName != target.packageName) {
                            logger.error("${target.id}: package mismatch for $version (expected ${target.packageName}, got ${result.packageName}), discarding")
                            outputPath.delete()
                            continue
                        }
                        appConfig.recordPatchCheckedEntry(
                            targetId = target.id,
                            cacheKey = cacheKey,
                            version = version,
                            patchId = attachment.patchId,
                            patchVersion = libEntry.version,
                            output = outputName,
                        )
                        updated = true
                    }
                    is PatchApplier.ApplyResult.Failure -> {
                        logger.error("${target.id}: morphe patch '${attachment.patchId}' failed for $version: ${result.error}")
                    }
                }
            }
        } finally {
            downloadedApks.values.forEach { it.delete() }
        }

        return updated
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

    /** When an attachment doesn't specify supported_versions, fall back to
     * whatever the .mpp file itself declares (via [PatchLibrary]), filtered
     * to the target's package_name. A package with no version list in the
     * .mpp means "any version" (represented as a single "*" pattern). */
    private fun deriveSupportedVersions(libEntry: AppConfig.PatchLibraryEntry, packageName: String): List<String> {
        val patchFile = File(patchesDir, libEntry.file)
        if (!patchFile.exists()) return emptyList()

        val patches = try {
            patchLibrary.inspect(patchFile)
        } catch (e: Exception) {
            logger.warn("Could not read supported versions from '${libEntry.id}': $e")
            return emptyList()
        }

        for (patch in patches) {
            for (pkg in patch.packages) {
                if (packageName.isNotBlank() && pkg.packageName != packageName) continue
                if (pkg.versions.isEmpty()) return listOf("*")
                return pkg.versions
            }
        }
        return emptyList()
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

    /** Picks the highest version out of [supportedVersions], ignoring glob
     * patterns (e.g. "*", "1.2.*") since those don't name a single concrete
     * version to look for. Returns null if none are concrete. */
    private fun maxConcreteVersion(supportedVersions: List<String>): String? =
        supportedVersions
            .filter { '*' !in it && '?' !in it }
            .maxWithOrNull(::compareVersions)

    private fun compareVersions(a: String, b: String): Int {
        val partsA = a.split(".").map { it.toIntOrNull() ?: 0 }
        val partsB = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(partsA.size, partsB.size)) {
            val cmp = (partsA.getOrElse(i) { 0 }).compareTo(partsB.getOrElse(i) { 0 })
            if (cmp != 0) return cmp
        }
        return 0
    }

    companion object {
        private const val MAX_VERSIONS_PER_APP = 3
    }
}
