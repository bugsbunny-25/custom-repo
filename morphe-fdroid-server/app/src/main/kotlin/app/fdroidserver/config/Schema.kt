package app.fdroidserver.config

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

/**
 * SQLite schema, replacing the old `config.yml` (via [ConfigStore]) /
 * `cache.json` + `patch_cache.json` (via `JsonCacheStore`) file-based
 * storage. Normalized relational tables instead of the old nested
 * `Map<String, Any?>` tree - `github_repos`/`patches[].patches[]`'s old
 * polymorphic string-or-map shape is gone now that every field has its own
 * typed column, with nullable "override" columns falling back to
 * [Settings]' defaults the same way the YAML `defaults:` block did.
 */

/** Single-row table (row id is always 1) holding every app-wide setting,
 * including the one-time setup flag and the identity fields
 * (`repo_name`/`repo_description`/`repo_url`/`repo_icon`) collected during
 * first-run setup. */
object Settings : Table("settings") {
    val id = integer("id")
    val initialized = bool("initialized").default(false)
    val repoName = varchar("repo_name", 255).default("")
    val repoDescription = text("repo_description").default("")
    val repoUrl = varchar("repo_url", 512).default("")
    val repoIcon = varchar("repo_icon", 255).default("")
    val githubToken = varchar("github_token", 255).default("")
    val updateIntervalSeconds = integer("update_interval_seconds").default(3600)
    val patchCheckIntervalSeconds = integer("patch_check_interval_seconds").nullable()
    val apkPattern = varchar("apk_pattern", 255).default(""".*\.apk$""")
    val maxVersionsPerApp = integer("max_versions_per_app").default(0)
    val logLevel = varchar("log_level", 32).default("INFO")
    val flareSolverrUrl = varchar("flare_solverr_url", 512).default("")
    val defaultIncludePrereleases = bool("default_include_prereleases").default(true)
    val defaultIncludeDrafts = bool("default_include_drafts").default(false)
    val defaultMaxReleases = integer("default_max_releases").default(5)
    val defaultEnabled = bool("default_enabled").default(true)

    override val primaryKey = PrimaryKey(id)
}

/** The "GitHub" tab's watched repos. Nullable override columns mean "use
 * [Settings]' default" - matches the old YAML's simple-string-vs-detailed-map
 * fallback semantics. */
object GithubRepos : Table("github_repos") {
    val id = integer("id").autoIncrement()
    val repo = varchar("repo", 255).uniqueIndex()
    val includePrereleases = bool("include_prereleases").nullable()
    val includeDrafts = bool("include_drafts").nullable()
    val maxReleases = integer("max_releases").nullable()
    val enabled = bool("enabled").default(true)
    val apkPattern = varchar("apk_pattern", 255).nullable()

    override val primaryKey = PrimaryKey(id)
}

/** Per-repo history of already-processed GitHub releases (replaces
 * `cache.json`'s `{repo}.processed_releases` map). */
object GithubCheckedReleases : Table("github_checked_releases") {
    val id = integer("id").autoIncrement()
    val repoId = integer("repo_id").references(GithubRepos.id, onDelete = ReferenceOption.CASCADE)
    val releaseId = varchar("release_id", 128)
    val tag = varchar("tag", 255).default("")
    val type = varchar("type", 32).default("")
    val processedAt = varchar("processed_at", 64).default("")
    val apksFound = integer("apks_found").default(0)
    val apksDownloaded = integer("apks_downloaded").default(0)

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(repoId, releaseId)
    }
}

/** Reusable library of uploaded `.mpp` patch files. */
object PatchLibraryTable : Table("patch_library") {
    val id = varchar("id", 255)
    val name = varchar("name", 255).default("")
    val file = varchar("file", 255).default("")
    val version = varchar("version", 64).default("")
    val updatedAt = varchar("updated_at", 64).default("")

    override val primaryKey = PrimaryKey(id)
}

/** Apps watched on APKMirror for patching. */
object PatchTargets : Table("patch_targets") {
    val id = varchar("id", 255)
    val name = varchar("name", 255).default("")
    val apkmirrorUrl = varchar("apkmirror_url", 512).default("")
    val packageName = varchar("package_name", 255).default("")
    val enabled = bool("enabled").default(true)

    override val primaryKey = PrimaryKey(id)
}

/** Attaches a library patch to a target app, with app-specific overrides.
 * `patchSelectionJson`/`optionOverridesJson` store the nested
 * sub-patch-name -> bool / option-key -> value maps as JSON text rather than
 * further junction tables, since they're opaque blobs read/written as a
 * whole and never queried by their inner keys. */
object PatchAttachments : Table("patch_attachments") {
    val id = integer("id").autoIncrement()
    val targetId = varchar("target_id", 255).references(PatchTargets.id, onDelete = ReferenceOption.CASCADE)
    val patchId = varchar("patch_id", 255).references(PatchLibraryTable.id, onDelete = ReferenceOption.CASCADE)
    val supportedVersions = text("supported_versions").default("")
    val patchArgs = text("patch_args").default("")
    val patchSelectionJson = text("patch_selection_json").default("{}")
    val optionOverridesJson = text("option_overrides_json").default("{}")
    val includeExperimentalVersions = bool("include_experimental_versions").default(false)

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(targetId, patchId)
    }
}

/** Per-target history of already-processed (version, patch) pairs (replaces
 * `patch_cache.json`'s `{targetId}.processed` map). */
object PatchCheckedEntries : Table("patch_checked_entries") {
    val id = integer("id").autoIncrement()
    val targetId = varchar("target_id", 255).references(PatchTargets.id, onDelete = ReferenceOption.CASCADE)
    val cacheKey = varchar("cache_key", 255)
    val version = varchar("version", 128).default("")
    val patchId = varchar("patch_id", 255).default("")
    val patchVersion = varchar("patch_version", 64).default("")
    val processedAt = varchar("processed_at", 64).default("")
    val output = varchar("output", 512).default("")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(targetId, cacheKey)
    }
}
