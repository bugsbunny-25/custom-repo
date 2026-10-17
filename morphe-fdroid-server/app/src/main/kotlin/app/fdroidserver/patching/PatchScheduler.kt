package app.fdroidserver.patching

import app.fdroidserver.apkmirror.ApkMirrorClient
import app.fdroidserver.apkpure.ApkPureClient
import app.fdroidserver.config.AppConfig
import app.fdroidserver.fdroidrepo.FdroidRepoManager
import app.fdroidserver.scraper.ScraperClient
import java.io.File
import java.time.Duration
import java.time.Instant
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Orchestrates the whole patching pipeline: for each enabled patch target
 * (an app), check APKMirror for a new version matching one of its attached
 * library patches - falling back to APKPure for versions APKMirror doesn't
 * list, when the target has an APKPure URL configured - then hand the
 * version page URL + patch config off to [PatchWorkerLauncher], which
 * downloads, merges (if it's a bundle), patches, and signs the APK in a
 * separate short-lived JVM, and publish the result into the patched F-Droid
 * repo - keeping only the newest 3 versions per (app, patch) pair. This class
 * itself never downloads or opens an APK - only version-list scraping
 * (`apkMirrorClient.getVersions`/`apkPureClient.getVersions`, lightweight
 * HTML parsing) and DB/orchestration work run in the admin-server process.
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
    private val patchWorkerLauncher: PatchWorkerLauncher,
    private val patchesDir: File,
    private val patchedRepoDir: File,
    private val tmpDir: File,
    private val fdroidRepoManager: FdroidRepoManager,
    private val signing: PatchApplier.SigningConfig,
    private val schema: AppConfig.PatchSchema = AppConfig.PatchSchemas.Mobile,
    private val logger: Logger = LoggerFactory.getLogger(PatchScheduler::class.java.name),
) {
    /** Set by [refreshFlareSolverrUrl] and handed to each worker request -
     * the worker builds its own [ApkMirrorClient]/[ApkPureClient] instances
     * in a fresh process, so it can't share this process's client objects. */
    private var flareSolverrUrl: String? = null

    /** When each (target, patch, app version, bundle version) last failed to
     * patch - see [FAILED_RETRY_INTERVAL]. In memory only: a restart retries. */
    private val recentFailures = java.util.concurrent.ConcurrentHashMap<String, Instant>()

    private fun failureKey(targetId: String, patchId: String, version: String, bundleVersion: String) =
        "$targetId::$patchId::$version::$bundleVersion"
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
        flareSolverrUrl = url
    }

    /** Which source a candidate version (and its download page) came from, so
     * the patch worker resolves/downloads it through the right client. */
    private enum class Source { APKMIRROR, APKPURE }

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

        // A manual run retries a recent failure straight away - the operator
        // is presumably clicking because they fixed something.
        val updated = checkEnabledTarget(target, library, retryRecentFailures = true)
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

        val source = sourceForUrl(versionPageUrl)
        val preparedApkPath = File(tmpDir, "${target.id}-$version-prepared.apk")

        var updated = false
        try {
            for (attachment in target.patches) {
                val libEntry = library[attachment.patchId]
                if (libEntry == null) {
                    logger.warn("${target.id}: attached patch '${attachment.patchId}' not found in library, skipping")
                    continue
                }
                // The operator pasted this exact version page, so honour it even
                // if the .mpp doesn't list the version - see applyPatchToVersion's
                // forceCompatibility parameter.
                if (
                    applyPatchToVersion(
                        target, attachment, libEntry, version, versionPageUrl, source, preparedApkPath,
                        forceCompatibility = true,
                    )
                ) {
                    updated = true
                }
            }
        } finally {
            preparedApkPath.delete()
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
        retryRecentFailures: Boolean = false,
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

        return checkTarget(target, library, versions, retryRecentFailures)
    }

    /** A candidate version paired with the source that will resolve/download
     * it (in the patch worker - see [applyPatchToVersion]). */
    private data class SourcedCandidate(val entry: ScraperClient.VersionEntry, val source: Source)

    private suspend fun checkTarget(
        target: AppConfig.EnabledPatchTarget,
        library: Map<String, AppConfig.PatchLibraryEntry>,
        apkmirrorVersions: List<ScraperClient.VersionEntry>,
        retryRecentFailures: Boolean,
    ): Boolean {
        var updated = false
        // Stable per-version prepared-APK paths so multiple patch attachments
        // matching the same version share one download+merge (done by the
        // first worker invocation for that version; later ones for the same
        // version see the file already exists and skip straight to patching -
        // see PatchWorkerEntryPoint.runJob). Cleaned up once per checkTarget
        // call, same lifetime the old downloaded-file cache had.
        val preparedApkPaths = mutableSetOf<File>()

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

                // What's already been done for this attachment: the newest
                // published (app version, bundle version) combo, plus
                // skipped markers - see PatchUpdatePolicy.
                val records = appConfig.patchCheckedEntries(schema, target.id)
                    .filter { it.patchId == attachment.patchId }
                    .map { PatchUpdatePolicy.Record(it.version, it.patchVersion, it.output) }

                // An explicit operator override (attachment.supportedVersions,
                // set via the admin UI) pins exact versions; otherwise the
                // list comes from the .mpp's own declared compatibility, which
                // is the patch author's "known to work with" list and can
                // include many old historical versions.
                val usingDerivedVersions = attachment.supportedVersions.isEmpty()
                val derived = if (usingDerivedVersions) {
                    deriveSupportedVersions(libEntry, target.packageName, attachment.patchSelection)
                } else {
                    null
                }
                val supportedVersions = attachment.supportedVersions.ifEmpty {
                    derived?.let { AppVersionSupport.mergedPatchableVersions(it, attachment.includeExperimentalVersions) }.orEmpty()
                }
                if (supportedVersions.isEmpty()) {
                    if (derived != null && AppVersionSupport.mergedPatchableVersions(derived, includeExperimental = true).isNotEmpty()) {
                        logger.warn(
                            "${target.id}: the .mpp for patch '${attachment.patchId}' only declares experimental " +
                                "versions of ${target.packageName}, and the attachment doesn't include experimental versions, skipping",
                        )
                    } else {
                        logger.warn("${target.id}: no supported_versions configured for patch '${attachment.patchId}' and none found in the .mpp file, skipping")
                    }
                    continue
                }

                // Picks the highest listed version the policy allows (newer
                // than the stored combo's app version, or the same one with a
                // newer bundle) - not the first one in listing order, since
                // APKMirror's feed is ordered by publish time.
                fun pick(list: List<ScraperClient.VersionEntry>): ScraperClient.VersionEntry? {
                    val matching = list.filter { matchesSupportedVersion(it.version, supportedVersions) }
                    val chosen = PatchUpdatePolicy.selectVersion(
                        matching.map { it.version }, records, libEntry.version, pinned = !usingDerivedVersions,
                    ) ?: return null
                    return matching.first { it.version == chosen }
                }

                // Prefer APKMirror; only reach for the APKPure fallback (which
                // is what triggers its lazy fetch) when APKMirror has nothing
                // to patch and an APKPure URL is configured.
                val listedCandidate = pick(apkmirrorVersions)?.let { SourcedCandidate(it, Source.APKMIRROR) }
                    ?: pick(apkpureVersions())?.let { SourcedCandidate(it, Source.APKPURE) }

                if (listedCandidate == null) {
                    // APKMirror's feed only ever exposes its newest ~10
                    // releases and has no pagination, so a pinned version
                    // that isn't in it can't be found any other way. APKPure
                    // (when configured) is the one extra place to look, so
                    // only warn about a never-patched pinned version once
                    // it's absent from both.
                    val recordedVersions = records.map { it.version }.toSet()
                    supportedVersions.filter { '*' !in it && '?' !in it }
                        .filterNot { it in recordedVersions }
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
                    PatchUpdatePolicy.latestPublished(records)?.let { latest ->
                        logger.info(
                            "${target.id}: '${attachment.patchId}' is up to date " +
                                "(app ${latest.version}, patches ${latest.patchVersion.ifBlank { "?" }})",
                        )
                    }
                }

                val candidate = listedCandidate ?: continue

                val version = candidate.entry.version
                val failureKey = failureKey(target.id, attachment.patchId, version, libEntry.version)
                val failedAt = recentFailures[failureKey]
                if (!retryRecentFailures && failedAt != null && Duration.between(failedAt, Instant.now()) < FAILED_RETRY_INTERVAL) {
                    logger.info(
                        "${target.id}: $version with '${attachment.patchId}' ${libEntry.version} failed at $failedAt, " +
                            "not retrying before ${failedAt.plus(FAILED_RETRY_INTERVAL)}",
                    )
                    continue
                }
                logger.info(
                    "${target.id}: patching $version with '${attachment.patchId}' ${libEntry.version} " +
                        "(source: ${candidate.source.name.lowercase()})",
                )

                val preparedApkPath = File(tmpDir, "${target.id}-$version-prepared.apk")
                preparedApkPaths += preparedApkPath

                if (
                    applyPatchToVersion(
                        target, attachment, libEntry, version,
                        candidate.entry.pageUrl, candidate.source, preparedApkPath,
                        // Versions the operator pinned by hand win over the .mpp's
                        // own compatibility list; a derived list came from that same
                        // .mpp, so the engine's check (which honours the attachment's
                        // experimental opt-in too) agrees with it.
                        forceCompatibility = !usingDerivedVersions,
                    )
                ) {
                    updated = true
                }
            }
        } finally {
            preparedApkPaths.forEach { it.delete() }
        }

        return updated
    }

    /** Resolves, downloads, merges (if needed), patches, and signs [version]
     * of [target] for one attached patch - entirely inside the patch
     * worker's separate JVM (see [PatchWorkerLauncher]) - then records the
     * result and returns whether it was published. Shared by [checkTarget]'s
     * per-version sweep and [runSpecificVersion]'s direct, user-triggered
     * single-version run; both pass a [preparedApkPath] that's shared across
     * every attachment for the same [version] so the download/merge only
     * happens once (see the worker's own existence check).
     *
     * [forceCompatibility] is handed through to the vendored engine, which
     * otherwise skips any patch whose declared app versions don't include the
     * APK's own `versionName`. Pass it when the version being patched was
     * chosen by an operator (a pinned `supported_versions` entry, or a pasted
     * version page) rather than derived from the `.mpp`'s compatibility list -
     * they asked for that version specifically. */
    private suspend fun applyPatchToVersion(
        target: AppConfig.EnabledPatchTarget,
        attachment: AppConfig.PatchAttachmentView,
        libEntry: AppConfig.PatchLibraryEntry,
        version: String,
        versionPageUrl: String,
        source: Source,
        preparedApkPath: File,
        forceCompatibility: Boolean,
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

        val workDir = File(tmpDir, "patch-${target.id}-${attachment.patchId}-${System.currentTimeMillis()}")

        val result = try {
            patchWorkerLauncher.apply(
                source.name,
                versionPageUrl,
                flareSolverrUrl,
                preparedApkPath,
                patchFile,
                attachment.patchSelection,
                attachment.optionOverrides,
                target.packageName,
                outputPath,
                workDir,
                signing,
                forceCompatibility,
                attachment.includeExperimentalVersions,
            )
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
                logger.info(
                    "${target.id}: patched $version with '${attachment.patchId}' " +
                        "(${result.appliedPatches.size} patches applied)",
                )
                appConfig.recordPatchCheckedEntry(
                    schema = schema,
                    targetId = target.id,
                    cacheKey = cacheKey,
                    version = version,
                    patchId = attachment.patchId,
                    patchVersion = libEntry.version,
                    output = outputName,
                )
                recentFailures.remove(failureKey(target.id, attachment.patchId, version, libEntry.version))
                true
            }
            is PatchApplier.ApplyResult.Failure -> {
                logger.error("${target.id}: morphe patch '${attachment.patchId}' failed for $version: ${result.error}")
                // Not recorded in the DB (that would hide the APK already
                // published for this version), only throttled in memory so a
                // persistent failure doesn't re-download the APK every run.
                recentFailures[failureKey(target.id, attachment.patchId, version, libEntry.version)] = Instant.now()
                false
            }
            is PatchApplier.ApplyResult.Unsupported -> {
                // Recorded as checked (with no APK) so the same version isn't
                // re-downloaded every pass, and so the reason shows up in the
                // admin UI's version list, where deleting the entry retries it.
                logger.warn("${target.id}: skipped $version for patch '${attachment.patchId}': ${result.reason}")
                appConfig.recordPatchCheckedEntry(
                    schema = schema,
                    targetId = target.id,
                    cacheKey = cacheKey,
                    version = version,
                    patchId = attachment.patchId,
                    patchVersion = libEntry.version,
                    output = PatchUpdatePolicy.SKIPPED_PREFIX + result.reason,
                )
                false
            }
        }
    }

    /** When an attachment doesn't specify supported_versions, fall back to
     * what the .mpp file itself declares (via [PatchLibrary]) for the
     * target's package_name: one [AppVersionSupport] per patch that names the
     * package and is enabled under [selection] (the attachment's
     * patch_selection, else the patch's own default) - null when the file
     * can't be read or no enabled patch names the package. They're combined
     * by [AppVersionSupport.mergedPatchableVersions], which also applies the
     * attachment's experimental opt-in. */
    private fun deriveSupportedVersions(
        libEntry: AppConfig.PatchLibraryEntry,
        packageName: String,
        selection: Map<String, Boolean>,
    ): List<AppVersionSupport>? {
        val patchFile = File(patchesDir, libEntry.file)
        if (!patchFile.exists()) return null

        val patches = try {
            patchLibrary.inspect(patchFile)
        } catch (e: Exception) {
            logger.warn("Could not read supported versions from '${libEntry.id}': $e")
            return null
        }

        // Without a configured package name, fall back to the first package
        // the bundle names, as before.
        val pkgName = packageName.ifBlank { patches.firstNotNullOfOrNull { it.packages.firstOrNull()?.packageName } }
            ?: return null
        val supports = patches
            .filter { patch -> patch.name?.let { selection[it] } ?: patch.enabled }
            .mapNotNull { patch -> patch.packages.firstOrNull { it.packageName == pkgName }?.toVersionSupport() }
        return supports.ifEmpty { null }
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

        /** How long a failed (version, bundle version) combo waits before a
         * scheduled run tries it again, so a broken download or patch doesn't
         * re-download the APK on every run. "Patch now" ignores it. */
        private val FAILED_RETRY_INTERVAL: Duration = Duration.ofHours(6)
    }
}
