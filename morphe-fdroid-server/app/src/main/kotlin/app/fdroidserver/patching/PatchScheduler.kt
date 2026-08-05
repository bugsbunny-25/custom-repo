package app.fdroidserver.patching

import app.fdroidserver.apkmirror.ApkMirrorClient
import app.fdroidserver.apkpure.ApkPureClient
import app.fdroidserver.config.AppConfig
import app.fdroidserver.fdroidrepo.FdroidRepoManager
import app.fdroidserver.scraper.ScraperClient
import java.io.File
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Orchestrates the whole patching pipeline: for each enabled patch target
 * (an app), check APKMirror for a new version matching one of its attached
 * library patches - falling back to APKPure for versions APKMirror doesn't
 * list, when the target has an APKPure URL configured - download (and merge,
 * if it's a bundle) the APK, apply the patch via [PatchApplier], and publish
 * the result into the patched F-Droid repo - keeping only the newest 3
 * versions per (app, patch) pair.
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
    private val apkPureClient: ApkPureClient,
    private val patchLibrary: PatchLibrary,
    private val bundleMerger: BundleMerger,
    private val patchApplier: PatchApplier,
    private val patchesDir: File,
    private val patchedRepoDir: File,
    private val tmpDir: File,
    private val fdroidRepoManager: FdroidRepoManager,
    private val signing: PatchApplier.SigningConfig,
    private val schema: AppConfig.PatchSchema = AppConfig.PatchSchemas.Mobile,
    private val logger: Logger = LoggerFactory.getLogger(PatchScheduler::class.java.name),
) {
    suspend fun checkForUpdates(): Boolean {
        refreshFlareSolverrUrl()
        val library = appConfig.patchLibraryById(schema)
        val targets = appConfig.listEnabledPatchTargets(schema)

        var anyUpdated = false
        for (target in targets) {
            if (checkEnabledTarget(target, library)) anyUpdated = true
        }

        if (anyUpdated) publishUpdates()
        logger.info("Patch update check completed")
        return anyUpdated
    }

    /** Pulls the current FlareSolverr URL out of Settings and hands it to
     * [apkMirrorClient] - it's stored/edited in the DB via the admin UI's
     * Settings page rather than a Docker env var, so re-reading it at the
     * start of each entry point (rather than once at startup) is what lets a
     * value changed in Settings take effect on the very next run.
     * [ApkMirrorClient.flareSolverrUrl]'s own setter discards anything that
     * doesn't look like a usable http(s) URL, so a blank/malformed setting
     * just disables the fallback rather than misbehaving. */
    private suspend fun refreshFlareSolverrUrl() {
        val url = appConfig.getSettings().flareSolverrUrl
        apkMirrorClient.flareSolverrUrl = url
        apkPureClient.flareSolverrUrl = url
    }

    /** Which source a candidate version (and its download page) came from, so
     * [prepareApk] resolves/downloads it through the right client. */
    private enum class Source { APKMIRROR, APKPURE }

    private fun clientFor(source: Source): ScraperClient =
        if (source == Source.APKPURE) apkPureClient else apkMirrorClient

    /** APKPure download/app URLs live under apkpure.com (and its download
     * CDNs); anything else is treated as APKMirror. Used by
     * [runSpecificVersion] to route a user-pasted version URL to the right
     * client. */
    private fun sourceForUrl(url: String): Source =
        if (Regex("""apkpure\.""", RegexOption.IGNORE_CASE).containsMatchIn(url)) Source.APKPURE else Source.APKMIRROR

    /** Runs the patching pipeline for a single enabled target, identified by
     * [targetId] - used by the admin UI's "Patch now" button, as opposed to
     * [checkForUpdates]'s scheduled sweep over every enabled target. Returns
     * whether a new patched version was published. */
    suspend fun runTargetNow(targetId: String): Boolean {
        refreshFlareSolverrUrl()
        val library = appConfig.patchLibraryById(schema)
        val target = appConfig.listEnabledPatchTargets(schema).firstOrNull { it.id == targetId }
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
     * could extract for every app. [versionPageUrl] may be an APKMirror
     * version page or an APKPure `/download/{version}` page - the source is
     * detected from the URL host so either works. Returns whether anything was
     * published. */
    suspend fun runSpecificVersion(targetId: String, version: String, versionPageUrl: String): Boolean {
        refreshFlareSolverrUrl()
        val library = appConfig.patchLibraryById(schema)
        val target = appConfig.listEnabledPatchTargets(schema).firstOrNull { it.id == targetId }
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
            preparedApk = prepareApk(target.id, version, versionPageUrl, sourceForUrl(versionPageUrl))
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

    /** A candidate version paired with the source that will resolve/download
     * it (see [prepareApk]). */
    private data class SourcedCandidate(val entry: ScraperClient.VersionEntry, val source: Source)

    private suspend fun checkTarget(
        target: AppConfig.EnabledPatchTarget,
        library: Map<String, AppConfig.PatchLibraryEntry>,
        apkmirrorVersions: List<ScraperClient.VersionEntry>,
    ): Boolean {
        var updated = false
        val downloadedApks = mutableMapOf<String, File>() // version -> prepared (possibly merged) apk

        // APKPure is a fallback: only fetched (once, memoized) when APKMirror
        // doesn't list a version an attachment needs, and only if the target
        // actually has an APKPure URL configured. A fetch failure logs and
        // yields an empty list rather than sinking the whole target - APKMirror
        // is still the primary source.
        var apkpureFetched = false
        var apkpureVersionsCache: List<ScraperClient.VersionEntry> = emptyList()
        fun apkpureVersions(): List<ScraperClient.VersionEntry> {
            if (target.apkpureUrl.isBlank()) return emptyList()
            if (!apkpureFetched) {
                apkpureFetched = true
                apkpureVersionsCache = try {
                    apkPureClient.getVersions(target.apkpureUrl)
                } catch (e: Exception) {
                    logger.error("${target.id}: error fetching apkpure versions: $e")
                    emptyList()
                }
            }
            return apkpureVersionsCache
        }

        try {
            for (attachment in target.patches) {
                val libEntry = library[attachment.patchId]
                if (libEntry == null) {
                    logger.warn("${target.id}: attached patch '${attachment.patchId}' not found in library, skipping")
                    continue
                }

                val processed = appConfig.processedPatchCacheKeys(schema, target.id)
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

                fun matchIn(list: List<ScraperClient.VersionEntry>): ScraperClient.VersionEntry? =
                    list.firstOrNull { v ->
                        val cacheKey = "${v.version}::${attachment.patchId}"
                        cacheKey !in processed && matchesSupportedVersion(v.version, supportedVersions)
                    }

                // Prefer APKMirror; only reach for the APKPure fallback (which
                // is what triggers its lazy fetch) when APKMirror doesn't list
                // a needed version and an APKPure URL is configured.
                val listedCandidate = matchIn(apkmirrorVersions)?.let { SourcedCandidate(it, Source.APKMIRROR) }
                    ?: matchIn(apkpureVersions())?.let { SourcedCandidate(it, Source.APKPURE) }

                if (listedCandidate == null) {
                    // APKMirror's feed only ever exposes its newest ~10
                    // releases and has no pagination, so a pinned version
                    // that isn't in it can't be found any other way - unlike
                    // the old HTML listing this replaced, there's no history
                    // left to page through. APKPure (when configured) is the
                    // one extra place to look, so only warn about a pinned
                    // version once it's absent from both.
                    concreteSupported
                        .filterNot { "$it::${attachment.patchId}" in processed }
                        .filterNot { pinned ->
                            apkmirrorVersions.any { it.version == pinned } || apkpureVersions().any { it.version == pinned }
                        }
                        .forEach { pinned ->
                            val alsoApkpure = if (target.apkpureUrl.isNotBlank()) " or apkpure's version list" else ""
                            logger.warn(
                                "${target.id}: configured version $pinned for patch '${attachment.patchId}' " +
                                    "not in apkmirror's feed$alsoApkpure, skipping",
                            )
                        }
                }

                val candidate = listedCandidate ?: continue

                val version = candidate.entry.version
                logger.info("${target.id}: found new patchable version $version for patch '${attachment.patchId}' (source: ${candidate.source.name.lowercase()})")

                val preparedApk = downloadedApks.getOrPut(version) {
                    prepareApk(target.id, version, candidate.entry.pageUrl, candidate.source) ?: continue
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
                    schema = schema,
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

    /** Downloads the APK for [version] from [source] (merging it first if
     * it's an .apkm/.xapk bundle), or null on failure. */
    private fun prepareApk(targetId: String, version: String, pageUrl: String, source: Source): File? {
        val client = clientFor(source)
        val downloadInfo = client.resolveDownloadUrl(pageUrl)
        if (downloadInfo == null) {
            logger.error("$targetId: failed to resolve download URL for $version")
            return null
        }
        val rawFile = File(tmpDir, "$targetId-$version-download.bin")
        if (!client.downloadApk(downloadInfo.url, rawFile, downloadInfo.referer)) {
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
