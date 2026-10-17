package app.fdroidserver.github

import app.fdroidserver.config.AppConfig
import app.fdroidserver.fdroidrepo.FdroidRepoManager
import java.io.File
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Orchestrates the GitHub-releases pipeline: for each enabled configured
 * repo, check for new releases, download matching APK assets into the main
 * F-Droid repo, and (optionally) prune old versions. Direct Kotlin port of
 * the orchestration in the old Python `update_checker.py`'s
 * `check_for_updates()`.
 */
class GithubScheduler(
    private val appConfig: AppConfig,
    private val releaseChecker: GitHubReleaseChecker,
    private val repoDir: File,
    private val fdroidRepoManager: FdroidRepoManager,
    private val logger: Logger = LoggerFactory.getLogger(GithubScheduler::class.java.name),
) {
    suspend fun checkForUpdates() {
        val maxVersionsPerApp = appConfig.getSettings().maxVersionsPerApp
        val repos = appConfig.listEnabledGithubRepos().map {
            GitHubReleaseChecker.RepoConfig(it.repo, it.includePrereleases, it.includeDrafts, it.maxReleases, it.apkPattern)
        }

        var anyUpdated = false
        for (repoConfig in repos) {
            if (checkRepo(repoConfig)) anyUpdated = true
        }
        if (removeCorruptApks()) anyUpdated = true

        if (anyUpdated) {
            fdroidRepoManager.updateIndex(repoDir)
            pruneOldApks(maxVersionsPerApp)
        }

        logger.info("GitHub update check completed")
    }

    /**
     * Downloads new releases for one repo (network calls happen here,
     * outside any DB transaction), then records what happened via
     * [AppConfig.recordGithubOutcomes] in one transaction. Returns true if
     * anything new was downloaded.
     */
    private suspend fun checkRepo(repoConfig: GitHubReleaseChecker.RepoConfig): Boolean {
        val releases = try {
            releaseChecker.getReleases(repoConfig)
        } catch (e: Exception) {
            logger.error("${repoConfig.repo}: error fetching releases: $e")
            return false
        }
        if (releases.isEmpty()) return false

        val alreadyProcessed = appConfig.processedReleaseIds(repoConfig.repo)

        val outcomes = mutableListOf<AppConfig.GithubReleaseOutcome>()
        var downloadedAny = false

        for (release in releases) {
            val releaseIdStr = release.id.toString()
            val matchingAssets = releaseChecker.matchingAssets(release, repoConfig)

            if (releaseIdStr in alreadyProcessed) {
                if (repairTruncatedAssets(repoConfig, releaseIdStr, matchingAssets)) downloadedAny = true
                continue
            }

            val releaseType = releaseChecker.releaseType(release)
            logger.info("${repoConfig.repo}: processing $releaseType release ${release.tagName}")

            if (matchingAssets.isEmpty()) {
                logger.warn("${repoConfig.repo}: no APK files found in release ${release.tagName}")
                outcomes.add(AppConfig.GithubReleaseOutcome(releaseIdStr, release.tagName, releaseType, 0, 0))
                continue
            }

            var downloadedCount = 0
            for (asset in matchingAssets) {
                val destination = assetFile(releaseIdStr, asset)
                // Left over from an earlier check that downloaded some but
                // not all of this release's assets.
                if (releaseChecker.isComplete(destination, asset)) {
                    downloadedCount++
                    continue
                }
                if (releaseChecker.downloadAsset(repoConfig.repo, asset, destination)) {
                    downloadedCount++
                    downloadedAny = true
                }
            }
            logger.info("${repoConfig.repo}: downloaded $downloadedCount/${matchingAssets.size} APK(s) from ${release.tagName}")
            // Only mark the release as processed once every asset is in
            // place, so a failed download is retried on the next check
            // instead of being skipped forever.
            if (downloadedCount < matchingAssets.size) {
                logger.warn("${repoConfig.repo}: ${release.tagName} incomplete, will retry on the next check")
                continue
            }
            outcomes.add(AppConfig.GithubReleaseOutcome(releaseIdStr, release.tagName, releaseType, matchingAssets.size, downloadedCount))
        }

        if (outcomes.isNotEmpty()) appConfig.recordGithubOutcomes(repoConfig.repo, outcomes)

        return downloadedAny
    }

    private fun assetFile(releaseId: String, asset: GitHubReleaseChecker.GitHubAsset) =
        File(repoDir, "repo/$releaseId-${asset.name}")

    /** Re-downloads assets of an already-processed release whose file is
     * still in the repo but truncated (left by older versions of the
     * downloader). Missing files are left alone: pruning removes them on
     * purpose. Returns true if anything was replaced. */
    private suspend fun repairTruncatedAssets(
        repoConfig: GitHubReleaseChecker.RepoConfig,
        releaseId: String,
        assets: List<GitHubReleaseChecker.GitHubAsset>,
    ): Boolean {
        var repaired = false
        for (asset in assets) {
            val destination = assetFile(releaseId, asset)
            if (!destination.isFile || releaseChecker.isComplete(destination, asset)) continue
            logger.warn("${repoConfig.repo}: ${destination.name} is truncated (${destination.length()} bytes, expected ${asset.size}), re-downloading")
            if (releaseChecker.downloadAsset(repoConfig.repo, asset, destination)) repaired = true
        }
        return repaired
    }

    /** Deletes APKs in the repo dir that don't open as a zip, which
     * `fdroid update` rejects anyway ("EOCD signature not found"). Catches
     * truncated downloads from releases no longer in the checked window.
     * Returns true if anything was deleted. */
    private fun removeCorruptApks(): Boolean {
        val files = File(repoDir, "repo").listFiles { f -> f.isFile && f.extension == "apk" } ?: return false
        var removed = false
        for (file in files) {
            if (releaseChecker.isValidZip(file)) continue
            logger.warn("Removing corrupt APK ${file.name} (${file.length()} bytes)")
            if (file.delete()) removed = true
        }
        return removed
    }

    /** Keeps only the newest [maxVersionsPerApp] APKs per app in the repo
     * dir, where "app" is determined by looking up each file's
     * `{release_id}-...` prefix against the DB's release_id -> repo mapping
     * (same grouping the Python version's `cleanup_old_apks` used; filenames
     * alone don't identify the app, only the release_id does). */
    private suspend fun pruneOldApks(maxVersionsPerApp: Int) {
        if (maxVersionsPerApp <= 0) return

        val releaseIdToRepo = appConfig.releaseIdToRepoMap()

        fdroidRepoManager.pruneOldVersions(File(repoDir, "repo"), maxVersionsPerApp) { file ->
            val releaseId = file.name.substringBefore('-')
            releaseIdToRepo[releaseId] ?: "unknown-app"
        }
    }
}
