package app.fdroidserver.fdroidrepo

import java.io.File
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml

/**
 * Thin wrapper around the external `fdroid` CLI (from the `fdroidserver`
 * Python project - a separate, actively-maintained tool we depend on rather
 * than reimplement, see plan.md §8), plus the "keep only the newest N
 * versions per app" cleanup logic that's pure file-system manipulation and
 * carries over unchanged from the Python version.
 */
class FdroidRepoManager(private val logger: Logger = LoggerFactory.getLogger(FdroidRepoManager::class.java.name)) {

    /**
     * Creates the repo (and its keystore) if it isn't there yet. Returns
     * true if the repo is usable afterwards.
     *
     * The exit code used to be discarded, which hid a real failure mode: on
     * an image missing `setuptools`, `fdroid init` creates `repo/` and then
     * dies in its `from pkg_resources import get_distribution` fallback
     * (fdroidserver/init.py) without writing `config.yml`. The caller saw
     * nothing and the app carried on into first-run setup as though the
     * volume were simply new. So both the exit code and the artifact it is
     * supposed to produce are checked, and a failure is logged loudly.
     */
    fun initRepo(repoDir: File): Boolean {
        repoDir.mkdirs()
        val configFile = File(repoDir, "config.yml")
        if (configFile.exists()) {
            logger.info("F-Droid repository already initialized at $repoDir")
            return true
        }
        logger.info("Initializing F-Droid repository at $repoDir...")
        val (exitCode, output) = runFdroid(repoDir, "init")
        if (exitCode != 0) {
            logger.error("`fdroid init` failed in $repoDir (exit $exitCode): $output")
            return false
        }
        if (!configFile.exists()) {
            logger.error(
                "`fdroid init` reported success in $repoDir but wrote no config.yml - " +
                    "the repo is half-initialized and will not publish. Output: $output",
            )
            return false
        }
        return true
    }

    private val yaml: Yaml = run {
        val dumperOptions = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isPrettyFlow = true
        }
        Yaml(dumperOptions)
    }

    /**
     * Writes `repo_name`/`repo_description`/`repo_url`/`repo_icon` into
     * `{repoDir}/config.yml` - the F-Droid tool's own config file, created by
     * `fdroid init` in [initRepo] - so the served repo index actually
     * reflects the identity collected during first-run setup / edited on the
     * Settings page. Restores behavior the old Python `update_checker.py`'s
     * `update_fdroid_config()` had (append onto the existing generated
     * config rather than overwrite it, so `fdroid init`'s other generated
     * keys like `repo_keyalias`/`repo_pubkey` are preserved).
     */
    @Suppress("UNCHECKED_CAST")
    fun writeRepoMetadata(repoDir: File, name: String, description: String, url: String, icon: String) {
        val configFile = File(repoDir, "config.yml")
        val fdroidConfig: MutableMap<String, Any?> = if (configFile.exists()) {
            (yaml.load<Any?>(configFile.readText()) as? Map<String, Any?>)?.toMutableMap() ?: linkedMapOf()
        } else {
            linkedMapOf()
        }
        fdroidConfig["repo_name"] = name
        fdroidConfig["repo_description"] = description
        fdroidConfig["repo_url"] = url
        if (icon.isNotBlank()) fdroidConfig["repo_icon"] = icon
        configFile.parentFile?.mkdirs()
        configFile.writeText(yaml.dump(fdroidConfig))
    }

    data class RepoMetadata(val name: String, val description: String, val url: String, val icon: String)

    /**
     * Reads `repo_name`/`repo_description`/`repo_url`/`repo_icon` back out
     * of `{repoDir}/config.yml`, or returns null if the file doesn't exist
     * or any of the three required fields is missing/blank.
     *
     * Used on startup to detect a repo that was already fully set up
     * outside the admin UI (e.g. hand-edited, or carried over from a volume
     * that predates the SQLite-backed setup flow) so the one-time setup
     * form can be skipped instead of asking the user to re-enter values
     * that are already sitting in the mounted `config.yml`.
     */
    @Suppress("UNCHECKED_CAST")
    fun readRepoMetadata(repoDir: File): RepoMetadata? {
        val configFile = File(repoDir, "config.yml")
        if (!configFile.exists()) return null
        val fdroidConfig = (yaml.load<Any?>(configFile.readText()) as? Map<String, Any?>) ?: return null

        val name = fdroidConfig["repo_name"] as? String
        val description = fdroidConfig["repo_description"] as? String
        val url = fdroidConfig["repo_url"] as? String
        if (name.isNullOrBlank() || description.isNullOrBlank() || url.isNullOrBlank()) return null

        val icon = fdroidConfig["repo_icon"] as? String ?: ""
        return RepoMetadata(name, description, url, icon)
    }

    fun updateIndex(repoDir: File): Boolean {
        logger.info("Updating F-Droid repository index in $repoDir...")
        val (exitCode, output) = runFdroid(repoDir, "update", "-c")
        return if (exitCode == 0) {
            logger.info("F-Droid repository updated successfully")
            true
        } else {
            logger.error("Error updating F-Droid repo at $repoDir: $output")
            false
        }
    }

    private fun runFdroid(workingDir: File, vararg args: String): Pair<Int, String> {
        return try {
            val process = ProcessBuilder("fdroid", *args)
                .directory(workingDir)
                .redirectErrorStream(true)
                .start()
            // Close stdin immediately: `fdroid` can otherwise block waiting on
            // an interactive prompt (e.g. keystore setup) that will never be
            // answered in this non-interactive/containerized context.
            process.outputStream.close()
            val output = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(FDROID_TIMEOUT_MINUTES, java.util.concurrent.TimeUnit.MINUTES)
            if (!finished) {
                logger.error("Timed out after $FDROID_TIMEOUT_MINUTES minutes running fdroid ${args.joinToString(" ")} in $workingDir - killing process")
                process.destroyForcibly()
                return -1 to "Timed out waiting for fdroid ${args.joinToString(" ")}"
            }
            process.exitValue() to output
        } catch (e: Exception) {
            logger.error("Error running fdroid ${args.joinToString(" ")} in $workingDir: $e")
            -1 to (e.message ?: e.toString())
        }
    }

    /**
     * Keeps only the newest [maxVersions] `.apk` files per group in
     * [repoDir] (grouped by [groupKey]), deleting the rest. `maxVersions <=
     * 0` disables cleanup entirely (matches the Python version's
     * `max_versions_per_app: 0` = keep-all convention).
     *
     * Ranks by the version encoded in the filename (`{targetId}__{patchId}__
     * {version}.apk`), not file mtime: [PatchScheduler] can end up patching
     * an older version after the newest one has already been published (see
     * its own comment on that), which would otherwise have a newer mtime
     * than the actual latest version and push it out of the keep-window.
     */
    fun pruneOldVersions(
        repoDir: File,
        maxVersions: Int,
        // Callers whose file names carry versions that don't split cleanly on
        // dots (PatchScheduler's "+rc1-..." suffixes) pass their own order.
        versionOrder: (File, File) -> Int = { a, b -> compareVersions(extractVersion(a), extractVersion(b)) },
        groupKey: (File) -> String,
    ) {
        if (maxVersions <= 0) return
        val files = repoDir.listFiles { f -> f.isFile && f.extension == "apk" } ?: return
        val groups = files.groupBy(groupKey)

        for ((key, groupFiles) in groups) {
            val sorted = groupFiles.sortedWith { a, b -> versionOrder(b, a) }
            if (sorted.size > maxVersions) {
                sorted.drop(maxVersions).forEach { old ->
                    logger.info("Removing old APK for $key: ${old.name}")
                    old.delete()
                }
            }
        }
    }

    /**
     * Bounds the `archive/` directory to the newest [keep] versions per app.
     *
     * `archive_older` moves versions out of `repo/` into `archive/` but never
     * removes them, so the archive grows without limit - 16 GB of 180 old
     * versions on the deployment this was written for, on the disk that gets
     * backed up nightly. Everything in there is rebuildable (published APKs
     * and patched builds), so a bound is safe; what is not safe is losing the
     * *newest* versions, hence keep-newest-N rather than delete-older-than-N.
     *
     * Archive filenames are F-Droid's own `packageName_versionCode.apk`
     * shape, which is why this cannot reuse [pruneOldVersions] - that ranks
     * dotted version *names* out of this project's own
     * `{targetId}__{patchId}__{version}.apk` naming. Here the trailing
     * integer is the version code and sorts numerically; a file that does not
     * match the pattern is left alone rather than guessed at.
     *
     * Each removed APK's sidecar files (`.asc` signatures, and the
     * `_src.tar.gz` sources F-Droid writes next to an APK) go with it, since
     * an archive entry with no APK is just dead weight in the index.
     *
     * `keep <= 0` disables pruning entirely, matching the keep-all
     * convention [pruneOldVersions] already uses.
     *
     * Returns the number of APKs deleted, so the caller knows whether the
     * index needs regenerating.
     */
    fun pruneArchive(repoDir: File, keep: Int): Int {
        if (keep <= 0) return 0
        val archiveDir = File(repoDir, "archive")
        if (!archiveDir.isDirectory) return 0

        val apks = archiveDir.listFiles { f -> f.isFile && f.extension == "apk" } ?: return 0
        // packageName_versionCode.apk -> (packageName, versionCode)
        val parsed = apks.mapNotNull { file ->
            val stem = file.nameWithoutExtension
            val code = stem.substringAfterLast('_', "").toLongOrNull() ?: return@mapNotNull null
            val pkg = stem.substringBeforeLast('_', "")
            if (pkg.isEmpty()) null else Triple(file, pkg, code)
        }
        val unparsed = apks.size - parsed.size
        if (unparsed > 0) {
            logger.info("Archive prune in $archiveDir: leaving $unparsed file(s) alone - name is not packageName_versionCode.apk")
        }

        var deleted = 0
        for ((pkg, entries) in parsed.groupBy { it.second }) {
            val sorted = entries.sortedByDescending { it.third }
            if (sorted.size <= keep) continue
            for ((file, _, code) in sorted.drop(keep)) {
                logger.info("Archive prune: removing $pkg version code $code (${file.name})")
                if (file.delete()) {
                    deleted++
                    deleteSidecars(file)
                } else {
                    logger.warn("Archive prune: failed to delete ${file.name}")
                }
            }
        }
        if (deleted > 0) {
            logger.info("Archive prune in $archiveDir: removed $deleted APK(s), keeping newest $keep per app")
        }
        return deleted
    }

    /** Removes the files F-Droid writes alongside an APK, if present. */
    private fun deleteSidecars(apk: File) {
        val parent = apk.parentFile ?: return
        listOf(
            File(parent, apk.name + ".asc"),
            File(parent, apk.nameWithoutExtension + "_src.tar.gz"),
            File(parent, apk.nameWithoutExtension + "_src.tar.gz.asc"),
        ).forEach { sidecar ->
            if (sidecar.exists() && sidecar.delete()) {
                logger.info("Archive prune: removed sidecar ${sidecar.name}")
            }
        }
    }

    private fun extractVersion(file: File): String = file.nameWithoutExtension.substringAfterLast("__")

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
        private const val FDROID_TIMEOUT_MINUTES = 5L

        /**
         * Derives each F-Droid repo's `repo_url` from the single base URL
         * collected during setup - F-Droid requires `repo_url` to point
         * directly at the directory the index/APKs are served from (hence
         * the mandatory `/repo` suffix), and [nginx.conf] serves the main
         * and patched repos at `/repo` and `/patched/repo` respectively.
         */
        fun mainRepoUrl(baseUrl: String): String = baseUrl.trimEnd('/') + "/repo"
        fun patchedRepoUrl(baseUrl: String): String = baseUrl.trimEnd('/') + "/patched/repo"
        fun patchedTvRepoUrl(baseUrl: String): String = baseUrl.trimEnd('/') + "/patched-tv/repo"
    }
}
