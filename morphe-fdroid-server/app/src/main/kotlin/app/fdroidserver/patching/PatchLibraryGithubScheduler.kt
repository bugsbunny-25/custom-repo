package app.fdroidserver.patching

import app.fdroidserver.config.AppConfig
import app.fdroidserver.github.GitHubReleaseChecker
import java.io.File
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Keeps `.mpp` patch library entries that opted into GitHub auto-updates
 * (a non-blank `github_repo`, see [app.fdroidserver.config.Schema.PatchLibrarySchema])
 * in sync with that repo's releases, as an alternative to manually
 * uploading a new `.mpp` file every time one is cut: for each such entry,
 * checks the repo's releases (respecting its per-entry "include
 * pre-releases" setting), and if the newest matching release hasn't been
 * imported yet, downloads its `.mpp` asset and records it exactly like a
 * manual upload would (same `resetPatchCustomizations`/`invalidatePatchCache`
 * follow-up as [app.fdroidserver.admin.routes.patchLibraryRoutes]' PUT
 * handler).
 *
 * Reuses [GitHubReleaseChecker] (the same one the APK-release
 * [app.fdroidserver.github.GithubScheduler] uses) rather than duplicating its
 * HTTP/auth/pagination handling - only the asset filename pattern (`.mpp`
 * instead of `.apk`) and the per-entry config shape differ.
 */
class PatchLibraryGithubScheduler(
    private val appConfig: AppConfig,
    private val releaseChecker: GitHubReleaseChecker,
    private val patchesDir: File,
    private val schema: AppConfig.PatchSchema,
    private val logger: Logger = LoggerFactory.getLogger(PatchLibraryGithubScheduler::class.java.name),
) {
    /** Checks every library entry with a configured `github_repo` and
     * imports any newer release's `.mpp`. Returns whether anything was
     * imported (the caller may want to know, e.g. to log a summary; unlike
     * [PatchScheduler] there's no repo index to republish since `.mpp` files
     * aren't part of any F-Droid repo). */
    suspend fun checkForUpdates(): Boolean {
        val entries = appConfig.listPatchLibraryWithGithubRepo(schema)
        var anyUpdated = false
        for (entry in entries) {
            if (checkEntry(entry)) anyUpdated = true
        }
        return anyUpdated
    }

    /** Checks and (if newer) imports a single entry by id - used by the
     * admin UI's "Check GitHub now" button, as opposed to [checkForUpdates]'s
     * scheduled sweep over every opted-in entry. Returns false (without
     * error) if [id] doesn't exist or has no `github_repo` configured. */
    suspend fun checkEntryNow(id: String): Boolean {
        val entry = appConfig.listPatchLibraryWithGithubRepo(schema).firstOrNull { it.id == id } ?: return false
        return checkEntry(entry)
    }

    private suspend fun checkEntry(entry: AppConfig.PatchLibraryGithubEntry): Boolean {
        val repoConfig = GitHubReleaseChecker.RepoConfig(
            repo = entry.githubRepo,
            includePrereleases = entry.githubIncludePrereleases,
            includeDrafts = false,
            // Fetched (then filtered by prerelease/draft) newest-first; we
            // only ever act on the single newest qualifying one, but ask for
            // a small buffer of releases rather than just 1 so a repo whose
            // very latest release is a prerelease (when this entry excludes
            // those) doesn't look like it has no releases at all.
            maxReleases = 10,
            apkPattern = MPP_ASSET_PATTERN,
        )

        val releases = try {
            releaseChecker.getReleases(repoConfig)
        } catch (e: Exception) {
            logger.error("${entry.id}: error fetching releases from ${entry.githubRepo}: $e")
            return false
        }
        val latest = releases.firstOrNull() ?: return false

        if (latest.id.toString() == entry.githubLastReleaseId) return false // already imported

        val mppAssets = releaseChecker.matchingAssets(latest, repoConfig)
        if (mppAssets.isEmpty()) {
            logger.warn("${entry.id}: release ${latest.tagName} of ${entry.githubRepo} has no .mpp assets, skipping")
            return false
        }

        val asset = selectMppAsset(latest, mppAssets)
        logger.info("${entry.id}: found new release ${latest.tagName} on ${entry.githubRepo}, downloading ${asset.name}")

        val storedName = "${entry.id}.mpp"
        val tmpFile = File(patchesDir, "$storedName.tmp-${System.currentTimeMillis()}")
        val downloaded = try {
            releaseChecker.downloadAsset(entry.githubRepo, asset, tmpFile)
        } catch (e: Exception) {
            logger.error("${entry.id}: error downloading ${asset.name} from ${entry.githubRepo}: $e")
            false
        }
        if (!downloaded) {
            tmpFile.delete()
            return false
        }

        val destination = File(patchesDir, storedName)
        try {
            // Copy-then-delete (rather than tmpFile.renameTo(destination))
            // so a failure here can't leave destination half-written; the
            // existing, previously-imported .mpp is only replaced once the
            // download above has fully succeeded.
            destination.parentFile?.mkdirs()
            tmpFile.copyTo(destination, overwrite = true)
        } finally {
            tmpFile.delete()
        }

        val result = appConfig.recordPatchLibraryGithubUpdate(schema, entry.id, latest.tagName, storedName, latest.id.toString())
        return when (result) {
            is AppConfig.Result.Error -> {
                logger.error("${entry.id}: failed to record GitHub update: ${result.message}")
                false
            }
            is AppConfig.Result.Ok -> {
                // Same follow-up as a manual re-upload via the admin UI: the
                // new .mpp may add/remove/rename sub-patches or options, so
                // stale per-target overrides and cached processed versions
                // could reference things that no longer exist / need
                // re-checking against the new file.
                appConfig.resetPatchCustomizations(schema, entry.id)
                appConfig.invalidatePatchCache(schema, entry.id)
                logger.info("${entry.id}: imported ${latest.tagName} from ${entry.githubRepo} (asset: ${asset.name})")
                true
            }
        }
    }

    /** Picks which asset to import when a release has more than one `.mpp`
     * file - some repos' releases carry earlier versions' `.mpp` files
     * alongside the current one (e.g. kept for reference/rollback), so
     * picking "the first .mpp asset" isn't reliable. Prefers whichever
     * asset's filename contains the release's own tag (with/without a
     * leading "v", the most common tag convention) - if that's ambiguous
     * (zero or multiple matches) it falls back to the first asset and logs
     * a warning, since there's no more reliable signal to break the tie on
     * asset filenames alone. When there's only one `.mpp` asset, it's used
     * unconditionally - no need to matching against the tag at all. */
    internal fun selectMppAsset(
        release: GitHubReleaseChecker.GitHubRelease,
        mppAssets: List<GitHubReleaseChecker.GitHubAsset>,
    ): GitHubReleaseChecker.GitHubAsset {
        if (mppAssets.size == 1) return mppAssets.single()

        val tag = release.tagName
        val normalizedTag = tag.removePrefix("v").removePrefix("V")
        val tagMatches = mppAssets.filter { asset ->
            asset.name.contains(tag, ignoreCase = true) || asset.name.contains(normalizedTag, ignoreCase = true)
        }

        return when {
            tagMatches.size == 1 -> tagMatches.single()
            tagMatches.isNotEmpty() -> {
                logger.warn(
                    "${release.tagName}: multiple .mpp assets match this release's version " +
                        "(${tagMatches.joinToString { it.name }}); using the first one",
                )
                tagMatches.first()
            }
            else -> {
                logger.warn(
                    "${release.tagName}: multiple .mpp assets found but none match this release's version " +
                        "(${mppAssets.joinToString { it.name }}); using the first one",
                )
                mppAssets.first()
            }
        }
    }

    companion object {
        private const val MPP_ASSET_PATTERN = """.*\.mpp$"""
    }
}
