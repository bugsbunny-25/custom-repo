package app.fdroidserver.config

import app.morphe.engine.patches.RemotePatchSourceFactory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant

/**
 * Typed operations against the SQLite-backed [Schema.kt] tables (via
 * [AppDatabase]) - the direct replacement for the old `ConfigStore`/
 * `JsonCacheStore` + free-function `AppConfig` object that worked on plain
 * `Map<String, Any?>` trees loaded from `config.yml`/`cache.json`.
 *
 * Every public view/payload type here (`GithubRepoView`, `RepoPayload`,
 * `PatchLibraryEntryView`, `PatchTargetView`, `Result<T>`, ...) keeps the
 * exact same shape the map-based version had, so the `admin/routes` files and
 * ported admin UI JS need no changes - only the implementation moved from
 * map manipulation to SQL.
 */
class AppConfig(private val db: AppDatabase) {

    private val json = Json { ignoreUnknownKeys = true }

    private fun now(): String = Instant.now().toString()

    private val SLUG_RE = Regex("^[a-zA-Z0-9_-]+$")
    fun isValidSlug(value: String): Boolean = value.isNotBlank() && SLUG_RE.matches(value)

    sealed class Result<out T> {
        data class Ok<T>(val value: T) : Result<T>()
        data class Error(val message: String) : Result<Nothing>()
    }

    // ---------------------------------------------------------------------
    // Settings (app-wide config + first-run setup)
    // ---------------------------------------------------------------------

    @Serializable
    data class SettingsView(
        val initialized: Boolean,
        val repoName: String,
        val repoDescription: String,
        val repoUrl: String,
        val repoIcon: String,
        val githubToken: String,
        val updateInterval: Int,
        val patchCheckInterval: Int?,
        val apkPattern: String,
        val maxVersionsPerApp: Int,
        val logLevel: String,
        val flareSolverrUrl: String,
        val defaultIncludePrereleases: Boolean,
        val defaultIncludeDrafts: Boolean,
        val defaultMaxReleases: Int,
        val defaultEnabled: Boolean,
    )

    private fun readSettingsRow(): SettingsView {
        val row = Settings.selectAll().single()
        return SettingsView(
            initialized = row[Settings.initialized],
            repoName = row[Settings.repoName],
            repoDescription = row[Settings.repoDescription],
            repoUrl = row[Settings.repoUrl],
            repoIcon = row[Settings.repoIcon],
            githubToken = row[Settings.githubToken],
            updateInterval = row[Settings.updateIntervalSeconds],
            patchCheckInterval = row[Settings.patchCheckIntervalSeconds],
            apkPattern = row[Settings.apkPattern],
            maxVersionsPerApp = row[Settings.maxVersionsPerApp],
            logLevel = row[Settings.logLevel],
            flareSolverrUrl = row[Settings.flareSolverrUrl],
            defaultIncludePrereleases = row[Settings.defaultIncludePrereleases],
            defaultIncludeDrafts = row[Settings.defaultIncludeDrafts],
            defaultMaxReleases = row[Settings.defaultMaxReleases],
            defaultEnabled = row[Settings.defaultEnabled],
        )
    }

    suspend fun getSettings(): SettingsView = db.tx { readSettingsRow() }

    suspend fun isInitialized(): Boolean = db.tx { Settings.selectAll().single()[Settings.initialized] }

    @Serializable
    data class SetupPayload(
        val repoName: String,
        val repoDescription: String,
        val repoUrl: String,
        val repoIcon: String = "",
    )

    /** One-time first-run setup - rejected once [Settings.initialized] is
     * already true. Persisting the F-Droid repo's own `config.yml` copy of
     * these fields (via `FdroidRepoManager.writeRepoMetadata`) is the
     * caller's responsibility (see `SetupRoutes.kt`), same split as
     * [updateSettings]. */
    suspend fun completeSetup(payload: SetupPayload): Result<SettingsView> = db.tx {
        val row = Settings.selectAll().single()
        if (row[Settings.initialized]) return@tx Result.Error("Setup has already been completed")
        if (payload.repoName.isBlank()) return@tx Result.Error("repo_name is required")
        if (payload.repoDescription.isBlank()) return@tx Result.Error("repo_description is required")
        if (payload.repoUrl.isBlank()) return@tx Result.Error("repo_url is required")

        Settings.update {
            it[initialized] = true
            it[repoName] = payload.repoName
            it[repoDescription] = payload.repoDescription
            it[repoUrl] = payload.repoUrl
            it[repoIcon] = payload.repoIcon
        }
        Result.Ok(readSettingsRow())
    }

    /** Fields left null keep their previous value - lets the Settings page
     * PUT only the section the user edited. `repo_url` is intentionally not
     * present here: it's locked once initialized (see plan/README) and can
     * only be set via [completeSetup]. */
    @Serializable
    data class SettingsUpdatePayload(
        val repoName: String? = null,
        val repoDescription: String? = null,
        val repoIcon: String? = null,
        val githubToken: String? = null,
        val updateInterval: Int? = null,
        val patchCheckInterval: Int? = null,
        val apkPattern: String? = null,
        val maxVersionsPerApp: Int? = null,
        val logLevel: String? = null,
        val flareSolverrUrl: String? = null,
        val defaultIncludePrereleases: Boolean? = null,
        val defaultIncludeDrafts: Boolean? = null,
        val defaultMaxReleases: Int? = null,
        val defaultEnabled: Boolean? = null,
    )

    suspend fun updateSettings(payload: SettingsUpdatePayload): SettingsView = db.tx {
        Settings.update {
            payload.repoName?.let { v -> it[repoName] = v }
            payload.repoDescription?.let { v -> it[repoDescription] = v }
            payload.repoIcon?.let { v -> it[repoIcon] = v }
            payload.githubToken?.let { v -> it[githubToken] = v }
            payload.updateInterval?.let { v -> it[updateIntervalSeconds] = v }
            payload.patchCheckInterval?.let { v -> it[patchCheckIntervalSeconds] = v }
            payload.apkPattern?.let { v -> it[apkPattern] = v }
            payload.maxVersionsPerApp?.let { v -> it[maxVersionsPerApp] = v }
            payload.logLevel?.let { v -> it[logLevel] = v }
            payload.flareSolverrUrl?.let { v -> it[flareSolverrUrl] = v }
            payload.defaultIncludePrereleases?.let { v -> it[defaultIncludePrereleases] = v }
            payload.defaultIncludeDrafts?.let { v -> it[defaultIncludeDrafts] = v }
            payload.defaultMaxReleases?.let { v -> it[defaultMaxReleases] = v }
            payload.defaultEnabled?.let { v -> it[defaultEnabled] = v }
        }
        readSettingsRow()
    }

    // ---------------------------------------------------------------------
    // GitHub repos (the "GitHub" tab)
    // ---------------------------------------------------------------------

    @Serializable
    data class CheckedReleaseEntry(
        val buildNumber: String,
        val releaseId: String,
        val tag: String,
        val type: String,
        val processedAt: String,
        val apksFound: Int,
        val apksDownloaded: Int,
    )

    @Serializable
    data class GithubRepoView(
        val id: Int,
        val repo: String,
        val includePrereleases: Boolean,
        val includeDrafts: Boolean,
        val maxReleases: Int,
        val enabled: Boolean,
        val apkPattern: String,
        val checkedEntries: List<CheckedReleaseEntry>,
    )

    private fun checkedEntriesFor(repoId: Int): List<CheckedReleaseEntry> =
        GithubCheckedReleases.selectAll().where { GithubCheckedReleases.repoId eq repoId }
            .orderBy(GithubCheckedReleases.processedAt, SortOrder.DESC)
            .map { row ->
                CheckedReleaseEntry(
                    buildNumber = row[GithubCheckedReleases.releaseId],
                    releaseId = row[GithubCheckedReleases.releaseId],
                    tag = row[GithubCheckedReleases.tag],
                    type = row[GithubCheckedReleases.type],
                    processedAt = row[GithubCheckedReleases.processedAt],
                    apksFound = row[GithubCheckedReleases.apksFound],
                    apksDownloaded = row[GithubCheckedReleases.apksDownloaded],
                )
            }

    /** Matches the old map-based `listRepos`' behavior of only returning
     * *enabled* repos (disabled ones are hidden from the GitHub tab
     * entirely). */
    suspend fun listRepos(): List<GithubRepoView> = db.tx {
        val settings = Settings.selectAll().single()
        GithubRepos.selectAll().orderBy(GithubRepos.id).mapNotNull { row ->
            val enabled = row[GithubRepos.enabled]
            if (!enabled) return@mapNotNull null
            val repoId = row[GithubRepos.id]
            GithubRepoView(
                id = repoId,
                repo = row[GithubRepos.repo],
                includePrereleases = row[GithubRepos.includePrereleases]
                    ?: settings[Settings.defaultIncludePrereleases],
                includeDrafts = row[GithubRepos.includeDrafts] ?: settings[Settings.defaultIncludeDrafts],
                maxReleases = row[GithubRepos.maxReleases] ?: settings[Settings.defaultMaxReleases],
                enabled = enabled,
                apkPattern = row[GithubRepos.apkPattern] ?: settings[Settings.apkPattern],
                checkedEntries = checkedEntriesFor(repoId),
            )
        }
    }

    @Serializable
    data class RepoPayload(
        val repo: String,
        val includePrereleases: Boolean = true,
        val includeDrafts: Boolean = false,
        val maxReleases: Int = 5,
        val enabled: Boolean = true,
        val apkPattern: String = """.*\.apk$""",
    )

    /** Returns the new repo's id, or null if [payload].repo is blank or
     * already exists. */
    suspend fun addRepo(payload: RepoPayload): Int? = db.tx {
        if (payload.repo.isBlank()) return@tx null
        if (GithubRepos.selectAll().where { GithubRepos.repo eq payload.repo }.any()) return@tx null
        val result = GithubRepos.insert {
            it[repo] = payload.repo
            it[includePrereleases] = payload.includePrereleases
            it[includeDrafts] = payload.includeDrafts
            it[maxReleases] = payload.maxReleases
            it[enabled] = payload.enabled
            it[apkPattern] = payload.apkPattern
        }
        result[GithubRepos.id]
    }

    suspend fun updateRepo(repoId: Int, payload: RepoPayload): Boolean = db.tx {
        if (payload.repo.isBlank()) return@tx false
        val updated = GithubRepos.update({ GithubRepos.id eq repoId }) {
            it[repo] = payload.repo
            it[includePrereleases] = payload.includePrereleases
            it[includeDrafts] = payload.includeDrafts
            it[maxReleases] = payload.maxReleases
            it[enabled] = payload.enabled
            it[apkPattern] = payload.apkPattern
        }
        updated > 0
    }

    suspend fun deleteCheckedEntry(repoId: Int, releaseId: String): Boolean = db.tx {
        val deleted = GithubCheckedReleases.deleteWhere {
            (GithubCheckedReleases.repoId eq repoId) and (GithubCheckedReleases.releaseId eq releaseId)
        }
        deleted > 0
    }

    // ---------------------------------------------------------------------
    // Patch library (reusable .mpp files) + patch targets (apps watched on
    // APKMirror) + their attachments - all parameterized over a [PatchSchema]
    // so the "Patching" and "Patched TV" tabs each get fully independent
    // data (own library, own targets, own attachments, own processed-history)
    // while sharing this one implementation.
    // ---------------------------------------------------------------------

    /** Bundles the 4 tables backing one patch "channel" (see [Schema.kt]'s
     * `PatchLibrarySchema`/`PatchTargetsSchema`/etc. base classes) so call
     * sites pass one param instead of 4. */
    data class PatchSchema(
        val library: PatchLibrarySchema,
        val targets: PatchTargetsSchema,
        val attachments: PatchAttachmentsSchema,
        val checkedEntries: PatchCheckedEntriesSchema,
    )

    object PatchSchemas {
        val Mobile = PatchSchema(PatchLibraryTable, PatchTargets, PatchAttachments, PatchCheckedEntries)
        val Tv = PatchSchema(PatchLibraryTableTv, PatchTargetsTv, PatchAttachmentsTv, PatchCheckedEntriesTv)
    }

    @Serializable
    data class PatchLibraryEntryView(
        val id: String,
        val name: String,
        val file: String,
        val version: String,
        val updatedAt: String,
        // Blank sourceUrl means this entry is manually managed (uploaded .mpp
        // files); a repo URL opts it into PatchSourceScheduler's automatic
        // download/import instead. Any URL the vendored engine's
        // RemotePatchSourceFactory can parse works - GitHub or GitLab.
        val sourceUrl: String = "",
        // Display-only, derived from sourceUrl ("github"/"gitlab", blank when
        // there's no source or it can't be parsed) so the admin UI can label
        // an entry without re-implementing URL parsing in JS.
        val sourceProvider: String = "",
        val includePrereleases: Boolean = false,
    )

    @Serializable
    data class PatchLibraryPayload(
        val id: String = "",
        val name: String = "",
        val version: String = "",
        val filename: String? = null,
        val contentBase64: String? = null,
        // Nullable (rather than defaulting to ""/false) so PUT can
        // distinguish "not provided, don't change" from "explicitly cleared".
        val sourceUrl: String? = null,
        val includePrereleases: Boolean? = null,
        // Accepted for compatibility with clients written against the
        // GitHub-only API (a bare "owner/repo"). Ignored when sourceUrl is
        // given; see [resolvedSourceUrl].
        val githubRepo: String? = null,
    ) {
        /** [sourceUrl] if given, otherwise the legacy [githubRepo] expanded to
         * the GitHub URL it always meant. Null when neither was provided. */
        val resolvedSourceUrl: String?
            get() = sourceUrl ?: githubRepo?.let { repo ->
                repo.trim().let { if (it.isBlank()) "" else "https://github.com/$it" }
            }
    }

    private fun patchLibraryRowToView(
        schema: PatchSchema,
        row: org.jetbrains.exposed.v1.core.ResultRow
    ): PatchLibraryEntryView {
        val sourceUrl = row[schema.library.sourceUrl]
        return PatchLibraryEntryView(
            id = row[schema.library.id],
            name = row[schema.library.name],
            file = row[schema.library.file],
            version = row[schema.library.version],
            updatedAt = row[schema.library.updatedAt],
            sourceUrl = sourceUrl,
            sourceProvider = RemotePatchSourceFactory.parse(sourceUrl)?.provider?.name?.lowercase().orEmpty(),
            includePrereleases = row[schema.library.includePrereleases],
        )
    }

    suspend fun listPatchLibrary(schema: PatchSchema): List<PatchLibraryEntryView> = db.tx {
        schema.library.selectAll().map { patchLibraryRowToView(schema, it) }
    }

    suspend fun addPatchToLibrary(
        schema: PatchSchema,
        id: String,
        name: String,
        fileName: String,
        sourceUrl: String = "",
        includePrereleases: Boolean = false,
    ): Result<PatchLibraryEntryView> = db.tx {
        if (!isValidSlug(id)) return@tx Result.Error("A valid id (letters, numbers, - and _ only) is required")
        if (schema.library.selectAll().where { schema.library.id eq id }.any()) {
            return@tx Result.Error("A patch with id \"$id\" already exists")
        }
        // Store the canonical form the engine parsed out (e.g. a pasted
        // "github.com/owner/repo/releases/tag/v1" becomes
        // "https://github.com/owner/repo") so the stored value round-trips
        // through RemotePatchSourceFactory unchanged on every later read.
        val parsed = RemotePatchSourceFactory.parse(sourceUrl)
        if (sourceUrl.isNotBlank() && parsed == null) {
            return@tx Result.Error("\"$sourceUrl\" is not a GitHub or GitLab repo URL")
        }
        val canonicalUrl = parsed?.canonicalUrl.orEmpty()
        val updatedAt = now()
        schema.library.insert {
            it[schema.library.id] = id
            it[schema.library.name] = name.ifBlank { id }
            it[file] = fileName
            it[version] = ""
            it[schema.library.sourceUrl] = canonicalUrl
            it[schema.library.includePrereleases] = includePrereleases
            it[schema.library.updatedAt] = updatedAt
        }
        Result.Ok(
            PatchLibraryEntryView(
                id = id,
                name = name.ifBlank { id },
                file = fileName,
                version = "",
                updatedAt = updatedAt,
                sourceUrl = canonicalUrl,
                sourceProvider = parsed?.provider?.name?.lowercase().orEmpty(),
                includePrereleases = includePrereleases,
            )
        )
    }

    data class PatchLibraryUpdateResult(val entry: PatchLibraryEntryView, val contentUpdated: Boolean)

    suspend fun updatePatchInLibrary(
        schema: PatchSchema,
        id: String,
        newName: String?,
        newVersion: String?,
        newFileName: String?,
        newSourceUrl: String? = null,
        newIncludePrereleases: Boolean? = null,
    ): Result<PatchLibraryUpdateResult> = db.tx {
        val existing = schema.library.selectAll().where { schema.library.id eq id }.singleOrNull()
            ?: return@tx Result.Error("Patch not found")

        // Blank clears the source (back to manual uploads); anything else has
        // to parse, so a typo can't silently disable auto-updates.
        val canonicalUrl = newSourceUrl?.let { raw ->
            if (raw.isBlank()) {
                ""
            } else {
                RemotePatchSourceFactory.parse(raw)?.canonicalUrl
                    ?: return@tx Result.Error("\"$raw\" is not a GitHub or GitLab repo URL")
            }
        }

        val contentUpdated = newFileName != null
        schema.library.update({ schema.library.id eq id }) {
            if (newName != null) it[name] = newName.ifBlank { id }
            if (newVersion != null) it[version] = newVersion
            if (newFileName != null) it[file] = newFileName
            if (canonicalUrl != null) it[sourceUrl] = canonicalUrl
            if (newIncludePrereleases != null) it[includePrereleases] = newIncludePrereleases
            it[updatedAt] = now()
        }
        val updatedRow = schema.library.selectAll().where { schema.library.id eq id }.single()
        Result.Ok(PatchLibraryUpdateResult(patchLibraryRowToView(schema, updatedRow), contentUpdated))
    }

    /** Entries opted into [app.fdroidserver.patching.PatchSourceScheduler]'s
     * automatic download (non-blank `source_url`). */
    data class PatchLibrarySourceEntry(
        val id: String,
        val sourceUrl: String,
        val includePrereleases: Boolean,
        /** Tag of the release last imported for this entry - what stops the
         * same release being re-downloaded on every poll. Named after the
         * column it lives in, which predates GitLab support (GitLab keys
         * releases by tag, not by a numeric id, so the tag is the one
         * identifier both providers share). */
        val lastReleaseId: String,
    )

    suspend fun listPatchLibrarySources(schema: PatchSchema): List<PatchLibrarySourceEntry> = db.tx {
        schema.library.selectAll().mapNotNull { row ->
            val url = row[schema.library.sourceUrl]
            if (url.isBlank()) return@mapNotNull null
            PatchLibrarySourceEntry(
                id = row[schema.library.id],
                sourceUrl = url,
                includePrereleases = row[schema.library.includePrereleases],
                lastReleaseId = row[schema.library.lastReleaseId],
            )
        }
    }

    /** Records a successful automatic `.mpp` download/import from a remote
     * patch source for [id] - the auto-update counterpart to
     * [updatePatchInLibrary], called by
     * [app.fdroidserver.patching.PatchSourceScheduler] after it
     * downloads a new release's `.mpp` asset. Always treated as a content
     * update (unlike [updatePatchInLibrary], which only resets
     * customizations/cache when a new file was actually uploaded) - the
     * caller is expected to follow up with [resetPatchCustomizations] and
     * [invalidatePatchCache], same as a manual file update via the admin UI. */
    suspend fun recordPatchLibrarySourceUpdate(
        schema: PatchSchema,
        id: String,
        version: String,
        fileName: String,
        releaseId: String,
    ): Result<PatchLibraryEntryView> = db.tx {
        if (schema.library.selectAll().where { schema.library.id eq id }.empty()) {
            return@tx Result.Error("Patch not found")
        }
        schema.library.update({ schema.library.id eq id }) {
            it[file] = fileName
            it[schema.library.version] = version
            it[lastReleaseId] = releaseId
            it[updatedAt] = now()
        }
        val updatedRow = schema.library.selectAll().where { schema.library.id eq id }.single()
        Result.Ok(patchLibraryRowToView(schema, updatedRow))
    }

    data class PatchDeleteResult(val id: String, val storedFile: String?, val detachedFrom: Int)

    /** Deleting from `patch_library` cascades to `patch_attachments` via the
     * FK's `ON DELETE CASCADE` - no manual sweep over every target needed
     * (the old map-based version had to loop `patched_apps` by hand). */
    suspend fun deletePatchFromLibrary(schema: PatchSchema, id: String): Result<PatchDeleteResult> = db.tx {
        val existing = schema.library.selectAll().where { schema.library.id eq id }.singleOrNull()
            ?: return@tx Result.Error("Patch not found")
        val storedFile = existing[schema.library.file]
        val detached = schema.attachments.selectAll().where { schema.attachments.patchId eq id }.count().toInt()
        schema.library.deleteWhere { schema.library.id eq id }
        Result.Ok(PatchDeleteResult(id, storedFile.ifBlank { null }, detached))
    }

    /** Clears patch_selection/option_overrides for every attachment
     * referencing [patchId] - called when a library patch's content is
     * updated, since the new file may add/remove/rename sub-patches or
     * options and stale overrides could reference things that no longer
     * exist. */
    suspend fun resetPatchCustomizations(schema: PatchSchema, patchId: String): Int = db.tx {
        val rows = schema.attachments.selectAll().where { schema.attachments.patchId eq patchId }.toList()
        var reset = 0
        for (row in rows) {
            val hadOverrides = row[schema.attachments.patchSelectionJson] != "{}" ||
                    row[schema.attachments.optionOverridesJson] != "{}"
            if (hadOverrides) reset++
        }
        schema.attachments.update({ schema.attachments.patchId eq patchId }) {
            it[patchSelectionJson] = "{}"
            it[optionOverridesJson] = "{}"
        }
        reset
    }

    /** Clears cached processed (version, patchId) results for [patchId] -
     * call alongside [resetPatchCustomizations] when a library patch's
     * content changes, so the next check re-patches with the new file
     * instead of skipping versions already processed with the old one. */
    suspend fun invalidatePatchCache(schema: PatchSchema, patchId: String): Int = db.tx {
        schema.checkedEntries.deleteWhere { schema.checkedEntries.patchId eq patchId }
    }

    // ---------------------------------------------------------------------
    // Patch targets (apps watched on APKMirror) + their attachments
    // ---------------------------------------------------------------------

    @Serializable
    data class PatchAttachmentView(
        val patchId: String,
        val patchName: String,
        val patchVersion: String,
        val supportedVersions: List<String>,
        val patchArgs: String,
        val patchSelection: Map<String, Boolean>,
        val optionOverrides: Map<String, Map<String, String>>,
        // Whether versions the .mpp file itself marks experimental should be
        // patched. Excluded by default (see PatchScheduler.checkTarget) -
        // only relevant when supportedVersions is empty (i.e. falling back
        // to the .mpp file's own version list rather than an explicit
        // override).
        val includeExperimentalVersions: Boolean = false,
    )

    @Serializable
    data class PatchCheckedEntry(
        val cacheKey: String,
        val version: String,
        val patchId: String,
        val patchVersion: String,
        val processedAt: String,
        val output: String,
    )

    @Serializable
    data class PatchTargetView(
        val id: String,
        val name: String,
        val apkmirrorUrl: String,
        val apkpureUrl: String,
        val packageName: String,
        val enabled: Boolean,
        val patches: List<PatchAttachmentView>,
        val checkedEntries: List<PatchCheckedEntry>,
    )

    private fun toStringList(value: String): List<String> =
        value.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    private fun attachmentsFor(schema: PatchSchema, targetId: String): List<PatchAttachmentView> {
        val library = schema.library.selectAll().associateBy({ it[schema.library.id] }, { it })
        return schema.attachments.selectAll().where { schema.attachments.targetId eq targetId }.map { row ->
            val patchId = row[schema.attachments.patchId]
            val libRow = library[patchId]
            val patchSelection: Map<String, Boolean> = runCatching {
                json.decodeFromString<Map<String, Boolean>>(row[schema.attachments.patchSelectionJson])
            }.getOrDefault(emptyMap())
            val optionOverrides: Map<String, Map<String, String>> = runCatching {
                json.decodeFromString<Map<String, Map<String, String>>>(row[schema.attachments.optionOverridesJson])
            }.getOrDefault(emptyMap())
            PatchAttachmentView(
                patchId = patchId,
                patchName = libRow?.get(schema.library.name) ?: patchId,
                patchVersion = libRow?.get(schema.library.version) ?: "",
                supportedVersions = toStringList(row[schema.attachments.supportedVersions]),
                patchArgs = row[schema.attachments.patchArgs],
                includeExperimentalVersions = row[schema.attachments.includeExperimentalVersions],
                patchSelection = patchSelection,
                optionOverrides = optionOverrides,
            )
        }
    }

    private fun checkedEntriesForTarget(schema: PatchSchema, targetId: String): List<PatchCheckedEntry> =
        schema.checkedEntries.selectAll().where { schema.checkedEntries.targetId eq targetId }
            .orderBy(schema.checkedEntries.processedAt, SortOrder.DESC)
            .map { row ->
                PatchCheckedEntry(
                    cacheKey = row[schema.checkedEntries.cacheKey],
                    version = row[schema.checkedEntries.version],
                    patchId = row[schema.checkedEntries.patchId],
                    patchVersion = row[schema.checkedEntries.patchVersion],
                    processedAt = row[schema.checkedEntries.processedAt],
                    output = row[schema.checkedEntries.output],
                )
            }

    suspend fun listPatchTargets(schema: PatchSchema): List<PatchTargetView> = db.tx {
        schema.targets.selectAll().map { row ->
            val id = row[schema.targets.id]
            PatchTargetView(
                id = id,
                name = row[schema.targets.name],
                apkmirrorUrl = row[schema.targets.apkmirrorUrl],
                apkpureUrl = row[schema.targets.apkpureUrl],
                packageName = row[schema.targets.packageName],
                enabled = row[schema.targets.enabled],
                patches = attachmentsFor(schema, id),
                checkedEntries = checkedEntriesForTarget(schema, id),
            )
        }
    }

    @Serializable
    data class PatchTargetPayload(
        val id: String,
        val name: String = "",
        val apkmirrorUrl: String,
        val apkpureUrl: String = "",
        val packageName: String = "",
        val enabled: Boolean = true,
    )

    suspend fun addPatchTarget(schema: PatchSchema, payload: PatchTargetPayload): Result<PatchTargetPayload> = db.tx {
        if (!isValidSlug(payload.id)) return@tx Result.Error("A valid id (letters, numbers, - and _ only) is required")
        if (payload.apkmirrorUrl.isBlank()) return@tx Result.Error("APKMirror URL is required")
        if (schema.targets.selectAll().where { schema.targets.id eq payload.id }.any()) {
            return@tx Result.Error("A patch target with id \"${payload.id}\" already exists")
        }
        schema.targets.insert {
            it[id] = payload.id
            it[name] = payload.name.ifBlank { payload.id }
            it[apkmirrorUrl] = payload.apkmirrorUrl
            it[apkpureUrl] = payload.apkpureUrl
            it[packageName] = payload.packageName
            it[enabled] = payload.enabled
        }
        Result.Ok(payload)
    }

    suspend fun updatePatchTarget(
        schema: PatchSchema,
        id: String,
        payload: PatchTargetPayload
    ): Result<PatchTargetPayload> = db.tx {
        if (payload.apkmirrorUrl.isBlank()) return@tx Result.Error("APKMirror URL is required")
        val updated = schema.targets.update({ schema.targets.id eq id }) {
            it[name] = payload.name.ifBlank { id }
            it[apkmirrorUrl] = payload.apkmirrorUrl
            it[apkpureUrl] = payload.apkpureUrl
            it[packageName] = payload.packageName
            it[enabled] = payload.enabled
        }
        if (updated == 0) Result.Error("Patch target not found") else Result.Ok(payload)
    }

    /** Deleting a target cascades to its `patch_attachments` and
     * `patch_checked_entries` rows via `ON DELETE CASCADE`. */
    suspend fun deletePatchTarget(schema: PatchSchema, id: String): Result<String> = db.tx {
        val deleted = schema.targets.deleteWhere { schema.targets.id eq id }
        if (deleted == 0) Result.Error("Patch target not found") else Result.Ok(id)
    }

    @Serializable
    data class AttachPayload(
        val patchId: String,
        val supportedVersions: String? = null,
        val patchArgs: String? = null,
        val patchSelection: Map<String, Boolean>? = null,
        val optionOverrides: Map<String, Map<String, String>>? = null,
        val includeExperimentalVersions: Boolean? = null,
    )

    /**
     * Creates or updates an app's attachment to a library patch. Fields left
     * null in [payload] keep their previous value (or default to
     * empty/unset for a brand-new attachment) - lets the "Configure
     * Patches" UI save just patch_selection/option_overrides without
     * clobbering supported_versions, and vice versa.
     */
    suspend fun attachPatchToTarget(
        schema: PatchSchema,
        targetId: String,
        payload: AttachPayload
    ): Result<PatchAttachmentView> = db.tx {
        if (!schema.targets.selectAll().where { schema.targets.id eq targetId }.any()) {
            return@tx Result.Error("Patch target not found")
        }
        if (payload.patchId.isBlank()) return@tx Result.Error("patch_id is required")
        val libRow = schema.library.selectAll().where { schema.library.id eq payload.patchId }.singleOrNull()
            ?: return@tx Result.Error("Patch \"${payload.patchId}\" not found in the library")

        val existing = schema.attachments.selectAll()
            .where { (schema.attachments.targetId eq targetId) and (schema.attachments.patchId eq payload.patchId) }
            .singleOrNull()

        val supportedVersions = payload.supportedVersions?.let { toStringList(it) }
            ?: existing?.get(schema.attachments.supportedVersions)?.let { toStringList(it) }
            ?: emptyList()
        val patchArgs = payload.patchArgs ?: existing?.get(schema.attachments.patchArgs) ?: ""
        val patchSelection = payload.patchSelection
            ?: existing?.get(schema.attachments.patchSelectionJson)?.let {
                runCatching { json.decodeFromString<Map<String, Boolean>>(it) }.getOrDefault(emptyMap())
            }
            ?: emptyMap()
        val optionOverrides = payload.optionOverrides
            ?: existing?.get(schema.attachments.optionOverridesJson)?.let {
                runCatching { json.decodeFromString<Map<String, Map<String, String>>>(it) }.getOrDefault(emptyMap())
            }
            ?: emptyMap()
        val includeExperimentalVersions = payload.includeExperimentalVersions
            ?: existing?.get(schema.attachments.includeExperimentalVersions)
            ?: false

        val supportedVersionsStr = supportedVersions.joinToString(",")
        val patchSelectionStr = json.encodeToString(patchSelection)
        val optionOverridesStr = json.encodeToString(optionOverrides)

        if (existing != null) {
            schema.attachments.update({ (schema.attachments.targetId eq targetId) and (schema.attachments.patchId eq payload.patchId) }) {
                it[schema.attachments.supportedVersions] = supportedVersionsStr
                it[schema.attachments.patchArgs] = patchArgs
                it[patchSelectionJson] = patchSelectionStr
                it[optionOverridesJson] = optionOverridesStr
                it[schema.attachments.includeExperimentalVersions] = includeExperimentalVersions
            }
        } else {
            schema.attachments.insert {
                it[schema.attachments.targetId] = targetId
                it[patchId] = payload.patchId
                it[schema.attachments.supportedVersions] = supportedVersionsStr
                it[schema.attachments.patchArgs] = patchArgs
                it[patchSelectionJson] = patchSelectionStr
                it[optionOverridesJson] = optionOverridesStr
                it[schema.attachments.includeExperimentalVersions] = includeExperimentalVersions
            }
        }

        Result.Ok(
            PatchAttachmentView(
                patchId = payload.patchId,
                patchName = libRow[schema.library.name],
                patchVersion = libRow[schema.library.version],
                supportedVersions = supportedVersions,
                patchArgs = patchArgs,
                includeExperimentalVersions = includeExperimentalVersions,
                patchSelection = patchSelection,
                optionOverrides = optionOverrides,
            )
        )
    }

    suspend fun detachPatchFromTarget(schema: PatchSchema, targetId: String, patchId: String): Result<String> = db.tx {
        val deleted = schema.attachments.deleteWhere {
            (schema.attachments.targetId eq targetId) and (schema.attachments.patchId eq patchId)
        }
        if (deleted == 0) Result.Error("Attachment not found") else Result.Ok(patchId)
    }

    suspend fun deletePatchCheckedEntry(schema: PatchSchema, targetId: String, cacheKey: String): Boolean = db.tx {
        val deleted = schema.checkedEntries.deleteWhere {
            (schema.checkedEntries.targetId eq targetId) and (schema.checkedEntries.cacheKey eq cacheKey)
        }
        deleted > 0
    }

    // ---------------------------------------------------------------------
    // Scheduler support - read-mostly helpers used by GithubScheduler /
    // PatchScheduler instead of loading the whole config/cache as raw maps.
    // ---------------------------------------------------------------------

    data class EnabledGithubRepo(
        val repo: String,
        val includePrereleases: Boolean,
        val includeDrafts: Boolean,
        val maxReleases: Int,
        val apkPattern: String,
    )

    suspend fun listEnabledGithubRepos(): List<EnabledGithubRepo> = db.tx {
        val settings = Settings.selectAll().single()
        GithubRepos.selectAll().where { GithubRepos.enabled eq true }.map { row ->
            EnabledGithubRepo(
                repo = row[GithubRepos.repo],
                includePrereleases = row[GithubRepos.includePrereleases]
                    ?: settings[Settings.defaultIncludePrereleases],
                includeDrafts = row[GithubRepos.includeDrafts] ?: settings[Settings.defaultIncludeDrafts],
                maxReleases = row[GithubRepos.maxReleases] ?: settings[Settings.defaultMaxReleases],
                apkPattern = row[GithubRepos.apkPattern] ?: settings[Settings.apkPattern],
            )
        }
    }

    suspend fun processedReleaseIds(repo: String): Set<String> = db.tx {
        val repoId = GithubRepos.selectAll().where { GithubRepos.repo eq repo }.singleOrNull()?.get(GithubRepos.id)
            ?: return@tx emptySet()
        GithubCheckedReleases.selectAll().where { GithubCheckedReleases.repoId eq repoId }
            .map { it[GithubCheckedReleases.releaseId] }
            .toSet()
    }

    data class GithubReleaseOutcome(
        val releaseId: String,
        val tag: String,
        val type: String,
        val apksFound: Int,
        val apksDownloaded: Int,
    )

    /** Records the outcome of newly-processed releases for [repo] in one
     * transaction - the SQL replacement for `cacheStore.update { ... }` in
     * `GithubScheduler.checkRepo`. */
    suspend fun recordGithubOutcomes(repo: String, outcomes: List<GithubReleaseOutcome>) = db.tx {
        val repoId =
            GithubRepos.selectAll().where { GithubRepos.repo eq repo }.singleOrNull()?.get(GithubRepos.id) ?: return@tx
        val processedAt = now()
        for (outcome in outcomes) {
            GithubCheckedReleases.insert {
                it[GithubCheckedReleases.repoId] = repoId
                it[releaseId] = outcome.releaseId
                it[tag] = outcome.tag
                it[type] = outcome.type
                it[GithubCheckedReleases.processedAt] = processedAt
                it[apksFound] = outcome.apksFound
                it[apksDownloaded] = outcome.apksDownloaded
            }
        }
    }

    /** Maps every already-seen GitHub release id to the repo it belongs to -
     * used for the "keep only the newest N APKs per app" cleanup pass, which
     * groups downloaded files by the repo their `{release_id}-...` prefix
     * came from. */
    suspend fun releaseIdToRepoMap(): Map<String, String> = db.tx {
        val idToRepo = GithubRepos.selectAll().associate { it[GithubRepos.id] to it[GithubRepos.repo] }
        GithubCheckedReleases.selectAll().associate {
            it[GithubCheckedReleases.releaseId] to (idToRepo[it[GithubCheckedReleases.repoId]] ?: "unknown-app")
        }
    }

    data class EnabledPatchTarget(
        val id: String,
        val apkmirrorUrl: String,
        val apkpureUrl: String,
        val packageName: String,
        val patches: List<PatchAttachmentView>,
    )

    suspend fun listEnabledPatchTargets(schema: PatchSchema): List<EnabledPatchTarget> = db.tx {
        schema.targets.selectAll().where { schema.targets.enabled eq true }.map { row ->
            val id = row[schema.targets.id]
            EnabledPatchTarget(
                id,
                row[schema.targets.apkmirrorUrl],
                row[schema.targets.apkpureUrl],
                row[schema.targets.packageName],
                attachmentsFor(schema, id),
            )
        }
    }

    data class PatchLibraryEntry(val id: String, val name: String, val file: String, val version: String)

    suspend fun patchLibraryById(schema: PatchSchema): Map<String, PatchLibraryEntry> = db.tx {
        schema.library.selectAll().associate {
            it[schema.library.id] to PatchLibraryEntry(
                it[schema.library.id], it[schema.library.name], it[schema.library.file], it[schema.library.version],
            )
        }
    }

    suspend fun processedPatchCacheKeys(schema: PatchSchema, targetId: String): Set<String> = db.tx {
        schema.checkedEntries.selectAll().where { schema.checkedEntries.targetId eq targetId }
            .map { it[schema.checkedEntries.cacheKey] }
            .toSet()
    }

    /** Records one successfully-applied (target, patch, version) result -
     * the SQL replacement for `patchCacheStore.update { ... }` in
     * `PatchScheduler.checkTarget`. */
    suspend fun recordPatchCheckedEntry(
        schema: PatchSchema,
        targetId: String,
        cacheKey: String,
        version: String,
        patchId: String,
        patchVersion: String,
        output: String,
    ) = db.tx {
        val exists = schema.checkedEntries.selectAll()
            .where { (schema.checkedEntries.targetId eq targetId) and (schema.checkedEntries.cacheKey eq cacheKey) }
            .any()
        val processedAt = now()
        if (exists) {
            schema.checkedEntries.update({ (schema.checkedEntries.targetId eq targetId) and (schema.checkedEntries.cacheKey eq cacheKey) }) {
                it[schema.checkedEntries.version] = version
                it[schema.checkedEntries.patchId] = patchId
                it[schema.checkedEntries.patchVersion] = patchVersion
                it[schema.checkedEntries.processedAt] = processedAt
                it[schema.checkedEntries.output] = output
            }
        } else {
            schema.checkedEntries.insert {
                it[schema.checkedEntries.targetId] = targetId
                it[schema.checkedEntries.cacheKey] = cacheKey
                it[schema.checkedEntries.version] = version
                it[schema.checkedEntries.patchId] = patchId
                it[schema.checkedEntries.patchVersion] = patchVersion
                it[schema.checkedEntries.processedAt] = processedAt
                it[schema.checkedEntries.output] = output
            }
        }
    }
}
