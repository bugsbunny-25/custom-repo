package app.fdroidserver.patching

import app.fdroidserver.config.AppConfig
import app.morphe.engine.PatcherCompatibility
import app.morphe.engine.model.Release
import app.morphe.engine.model.ReleaseAsset
import app.morphe.engine.patches.RemotePatchSource
import app.morphe.engine.patches.RemotePatchSourceFactory
import io.ktor.client.HttpClient
import java.io.File
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Keeps `.mpp` patch library entries that opted into automatic updates (a
 * non-blank `source_url`, see
 * [app.fdroidserver.config.PatchLibrarySchema]) in sync with their remote
 * repo, as an alternative to manually uploading a new `.mpp` file every time
 * one is cut.
 *
 * All the remote work is the vendored engine's
 * [RemotePatchSource] - the same code path morphe-desktop uses to fetch
 * patches - so this server supports every provider the desktop app does
 * (**GitHub and GitLab** today) with the same endpoints, the same
 * `patches-bundle.json` fast path, and the same retry/rate-limit handling.
 * This class only owns the scheduling, the "have I already imported this?"
 * bookkeeping, and writing the file into the patches directory.
 *
 * Replaces `PatchLibraryGithubScheduler`, which drove
 * [app.fdroidserver.github.GitHubReleaseChecker] (still used, unchanged, by
 * the GitHub tab's APK watcher) and could only ever talk to GitHub.
 */
class PatchSourceScheduler(
    private val appConfig: AppConfig,
    private val httpClient: HttpClient,
    private val patchesDir: File,
    private val schema: AppConfig.PatchSchema,
    private val logger: Logger = LoggerFactory.getLogger(PatchSourceScheduler::class.java.name),
) {
    /** Checks every library entry with a configured `source_url` and imports
     * any newer release's `.mpp`. Returns whether anything was imported (the
     * caller may want to know, e.g. to log a summary; unlike [PatchScheduler]
     * there's no repo index to republish since `.mpp` files aren't part of any
     * F-Droid repo). */
    suspend fun checkForUpdates(): Boolean {
        val entries = appConfig.listPatchLibrarySources(schema)
        var anyUpdated = false
        for (entry in entries) {
            if (checkEntry(entry)) anyUpdated = true
        }
        return anyUpdated
    }

    /** Checks and (if newer) imports a single entry by id - used by the
     * admin UI's "Check source now" button, as opposed to [checkForUpdates]'s
     * scheduled sweep over every opted-in entry. Returns false (without
     * error) if [id] doesn't exist or has no `source_url` configured. */
    suspend fun checkEntryNow(id: String): Boolean {
        val entry = appConfig.listPatchLibrarySources(schema).firstOrNull { it.id == id } ?: return false
        return checkEntry(entry)
    }

    private suspend fun checkEntry(entry: AppConfig.PatchLibrarySourceEntry): Boolean {
        val source = RemotePatchSourceFactory.from(entry.sourceUrl, httpClient)
        if (source == null) {
            logger.error("${entry.id}: '${entry.sourceUrl}' is not a patch source URL the engine understands, skipping")
            return false
        }

        val latest = latestRelease(source, entry.includePrereleases)
        if (latest == null) {
            logger.warn("${entry.id}: no usable release found on ${entry.sourceUrl}")
            return false
        }

        if (isAlreadyImported(entry, latest)) return false

        val patchAssets = latest.assets.filter { it.isPatchFile() }
        if (patchAssets.isEmpty()) {
            logger.warn("${entry.id}: release ${latest.tagName} of ${entry.sourceUrl} has no .mpp assets, skipping")
            return false
        }

        val asset = selectPatchAsset(latest, patchAssets)
        logger.info("${entry.id}: found new release ${latest.tagName} on ${entry.sourceUrl}, downloading ${asset.name}")

        val storedName = "${entry.id}.mpp"
        val tmpFile = File(patchesDir, "$storedName.tmp-${System.currentTimeMillis()}")
        try {
            val download = source.downloadAsset(asset, tmpFile)
            if (download.isFailure) {
                logger.error("${entry.id}: error downloading ${asset.name}: ${download.exceptionOrNull()}")
                return false
            }

            // Refuse a bundle built against a newer morphe-patcher than this
            // build ships *before* it replaces the working one: installing it
            // would turn every subsequent patch run for this entry into a
            // link error with no obvious cause. Same check (and same message)
            // morphe-desktop shows its users.
            PatcherCompatibility.incompatibilityMessage(tmpFile)?.let { message ->
                logger.error("${entry.id}: not importing ${latest.tagName} - $message")
                return false
            }

            val destination = File(patchesDir, storedName)
            // Copy-then-delete (rather than tmpFile.renameTo(destination))
            // so a failure here can't leave destination half-written; the
            // existing, previously-imported .mpp is only replaced once the
            // download above has fully succeeded.
            destination.parentFile?.mkdirs()
            tmpFile.copyTo(destination, overwrite = true)
        } catch (e: Exception) {
            logger.error("${entry.id}: error importing ${asset.name} from ${entry.sourceUrl}: $e")
            return false
        } finally {
            tmpFile.delete()
        }

        val result = appConfig.recordPatchLibrarySourceUpdate(
            schema, entry.id, latest.getVersion(), storedName, latest.tagName,
        )
        return when (result) {
            is AppConfig.Result.Error -> {
                logger.error("${entry.id}: failed to record source update: ${result.message}")
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
                logger.info("${entry.id}: imported ${latest.tagName} from ${entry.sourceUrl} (asset: ${asset.name})")
                true
            }
        }
    }

    /**
     * The newest release worth importing, preferring the engine's
     * `patches-bundle.json` fast path.
     *
     * That manifest lives on the provider's raw CDN (`main` for stable, `dev`
     * for pre-releases) and costs nothing against GitHub's/GitLab's
     * rate-limited release APIs, which matters for a server polling on a timer
     * rather than a desktop app fetching on demand. Sources that don't publish
     * one fall back to the release list, filtered the same way the old
     * GitHub-only checker filtered it: drafts never, pre-releases only when
     * the entry opted in.
     */
    internal suspend fun latestRelease(source: RemotePatchSource, includePrereleases: Boolean): Release? {
        source.fetchLatestFromManifest(prerelease = includePrereleases).getOrNull()
            ?.let { return it }

        val releases = source.listReleases().getOrElse { error ->
            logger.error("${source.repoPath}: error fetching releases: $error")
            return null
        }
        // Providers return releases newest-first; keep that order and take the
        // first one this entry is willing to accept.
        return releases.firstOrNull { release ->
            !release.draft && (includePrereleases || !release.isDevRelease())
        }
    }

    /**
     * Whether [release] is the one already recorded for [entry].
     *
     * Matches on the tag (what's recorded going forward, and the only
     * identifier GitLab and the `patches-bundle.json` manifest expose) or on
     * the provider's numeric release id (what the GitHub-only predecessor
     * recorded), so entries carried over from that version aren't re-imported
     * on the first run after the upgrade. One exception stays: an entry whose
     * source publishes a `patches-bundle.json` is re-imported once, because
     * the manifest carries no numeric id to match the stored one against.
     */
    private fun isAlreadyImported(entry: AppConfig.PatchLibrarySourceEntry, release: Release): Boolean {
        if (entry.lastReleaseId.isBlank()) return false
        if (entry.lastReleaseId == release.tagName) return true
        return release.id != 0L && entry.lastReleaseId == release.id.toString()
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
     * unconditionally - no need to match against the tag at all. */
    internal fun selectPatchAsset(release: Release, patchAssets: List<ReleaseAsset>): ReleaseAsset {
        if (patchAssets.size == 1) return patchAssets.single()

        val tag = release.tagName
        val normalizedTag = release.getVersion()
        val tagMatches = patchAssets.filter { asset ->
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
                        "(${patchAssets.joinToString { it.name }}); using the first one",
                )
                patchAssets.first()
            }
        }
    }
}
