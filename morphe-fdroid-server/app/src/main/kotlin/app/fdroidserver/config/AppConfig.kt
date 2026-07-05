package app.fdroidserver.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
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
                includePrereleases = row[GithubRepos.includePrereleases] ?: settings[Settings.defaultIncludePrereleases],
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
            SqlExpressionBuilder.run { (GithubCheckedReleases.repoId eq repoId) and (GithubCheckedReleases.releaseId eq releaseId) }
        }
        deleted > 0
    }

    // ---------------------------------------------------------------------
    // Patch library (reusable .mpp files)
    // ---------------------------------------------------------------------

    @Serializable
    data class PatchLibraryEntryView(
        val id: String,
        val name: String,
        val file: String,
        val version: String,
        val updatedAt: String,
    )

    @Serializable
    data class PatchLibraryPayload(
        val id: String = "",
        val name: String = "",
        val version: String = "",
        val filename: String? = null,
        val contentBase64: String? = null,
    )

    private fun patchLibraryRowToView(row: org.jetbrains.exposed.sql.ResultRow): PatchLibraryEntryView =
        PatchLibraryEntryView(
            id = row[PatchLibraryTable.id],
            name = row[PatchLibraryTable.name],
            file = row[PatchLibraryTable.file],
            version = row[PatchLibraryTable.version],
            updatedAt = row[PatchLibraryTable.updatedAt],
        )

    suspend fun listPatchLibrary(): List<PatchLibraryEntryView> = db.tx {
        PatchLibraryTable.selectAll().map(::patchLibraryRowToView)
    }

    suspend fun addPatchToLibrary(id: String, name: String, fileName: String): Result<PatchLibraryEntryView> = db.tx {
        if (!isValidSlug(id)) return@tx Result.Error("A valid id (letters, numbers, - and _ only) is required")
        if (PatchLibraryTable.selectAll().where { PatchLibraryTable.id eq id }.any()) {
            return@tx Result.Error("A patch with id \"$id\" already exists")
        }
        val updatedAt = now()
        PatchLibraryTable.insert {
            it[PatchLibraryTable.id] = id
            it[PatchLibraryTable.name] = name.ifBlank { id }
            it[file] = fileName
            it[version] = ""
            it[PatchLibraryTable.updatedAt] = updatedAt
        }
        Result.Ok(PatchLibraryEntryView(id, name.ifBlank { id }, fileName, "", updatedAt))
    }

    data class PatchLibraryUpdateResult(val entry: PatchLibraryEntryView, val contentUpdated: Boolean)

    suspend fun updatePatchInLibrary(
        id: String,
        newName: String?,
        newVersion: String?,
        newFileName: String?,
    ): Result<PatchLibraryUpdateResult> = db.tx {
        val existing = PatchLibraryTable.selectAll().where { PatchLibraryTable.id eq id }.singleOrNull()
            ?: return@tx Result.Error("Patch not found")

        val contentUpdated = newFileName != null
        PatchLibraryTable.update({ PatchLibraryTable.id eq id }) {
            if (newName != null) it[name] = newName.ifBlank { id }
            if (newVersion != null) it[version] = newVersion
            if (newFileName != null) it[file] = newFileName
            it[updatedAt] = now()
        }
        val updatedRow = PatchLibraryTable.selectAll().where { PatchLibraryTable.id eq id }.single()
        Result.Ok(PatchLibraryUpdateResult(patchLibraryRowToView(updatedRow), contentUpdated))
    }

    data class PatchDeleteResult(val id: String, val storedFile: String?, val detachedFrom: Int)

    /** Deleting from `patch_library` cascades to `patch_attachments` via the
     * FK's `ON DELETE CASCADE` - no manual sweep over every target needed
     * (the old map-based version had to loop `patched_apps` by hand). */
    suspend fun deletePatchFromLibrary(id: String): Result<PatchDeleteResult> = db.tx {
        val existing = PatchLibraryTable.selectAll().where { PatchLibraryTable.id eq id }.singleOrNull()
            ?: return@tx Result.Error("Patch not found")
        val storedFile = existing[PatchLibraryTable.file]
        val detached = PatchAttachments.selectAll().where { PatchAttachments.patchId eq id }.count().toInt()
        PatchLibraryTable.deleteWhere { SqlExpressionBuilder.run { PatchLibraryTable.id eq id } }
        Result.Ok(PatchDeleteResult(id, storedFile.ifBlank { null }, detached))
    }

    /** Clears patch_selection/option_overrides for every attachment
     * referencing [patchId] - called when a library patch's content is
     * updated, since the new file may add/remove/rename sub-patches or
     * options and stale overrides could reference things that no longer
     * exist. */
    suspend fun resetPatchCustomizations(patchId: String): Int = db.tx {
        val rows = PatchAttachments.selectAll().where { PatchAttachments.patchId eq patchId }.toList()
        var reset = 0
        for (row in rows) {
            val hadOverrides = row[PatchAttachments.patchSelectionJson] != "{}" ||
                row[PatchAttachments.optionOverridesJson] != "{}"
            if (hadOverrides) reset++
        }
        PatchAttachments.update({ PatchAttachments.patchId eq patchId }) {
            it[patchSelectionJson] = "{}"
            it[optionOverridesJson] = "{}"
        }
        reset
    }

    /** Clears cached processed (version, patchId) results for [patchId] -
     * call alongside [resetPatchCustomizations] when a library patch's
     * content changes, so the next check re-patches with the new file
     * instead of skipping versions already processed with the old one. */
    suspend fun invalidatePatchCache(patchId: String): Int = db.tx {
        PatchCheckedEntries.deleteWhere { SqlExpressionBuilder.run { PatchCheckedEntries.patchId eq patchId } }
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
        val packageName: String,
        val enabled: Boolean,
        val patches: List<PatchAttachmentView>,
        val checkedEntries: List<PatchCheckedEntry>,
    )

    private fun toStringList(value: String): List<String> =
        value.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    private fun attachmentsFor(targetId: String): List<PatchAttachmentView> {
        val library = PatchLibraryTable.selectAll().associateBy({ it[PatchLibraryTable.id] }, { it })
        return PatchAttachments.selectAll().where { PatchAttachments.targetId eq targetId }.map { row ->
            val patchId = row[PatchAttachments.patchId]
            val libRow = library[patchId]
            val patchSelection: Map<String, Boolean> = runCatching {
                json.decodeFromString<Map<String, Boolean>>(row[PatchAttachments.patchSelectionJson])
            }.getOrDefault(emptyMap())
            val optionOverrides: Map<String, Map<String, String>> = runCatching {
                json.decodeFromString<Map<String, Map<String, String>>>(row[PatchAttachments.optionOverridesJson])
            }.getOrDefault(emptyMap())
            PatchAttachmentView(
                patchId = patchId,
                patchName = libRow?.get(PatchLibraryTable.name) ?: patchId,
                patchVersion = libRow?.get(PatchLibraryTable.version) ?: "",
                supportedVersions = toStringList(row[PatchAttachments.supportedVersions]),
                patchArgs = row[PatchAttachments.patchArgs],
                includeExperimentalVersions = row[PatchAttachments.includeExperimentalVersions],
                patchSelection = patchSelection,
                optionOverrides = optionOverrides,
            )
        }
    }

    private fun checkedEntriesForTarget(targetId: String): List<PatchCheckedEntry> =
        PatchCheckedEntries.selectAll().where { PatchCheckedEntries.targetId eq targetId }
            .orderBy(PatchCheckedEntries.processedAt, SortOrder.DESC)
            .map { row ->
                PatchCheckedEntry(
                    cacheKey = row[PatchCheckedEntries.cacheKey],
                    version = row[PatchCheckedEntries.version],
                    patchId = row[PatchCheckedEntries.patchId],
                    patchVersion = row[PatchCheckedEntries.patchVersion],
                    processedAt = row[PatchCheckedEntries.processedAt],
                    output = row[PatchCheckedEntries.output],
                )
            }

    suspend fun listPatchTargets(): List<PatchTargetView> = db.tx {
        PatchTargets.selectAll().map { row ->
            val id = row[PatchTargets.id]
            PatchTargetView(
                id = id,
                name = row[PatchTargets.name],
                apkmirrorUrl = row[PatchTargets.apkmirrorUrl],
                packageName = row[PatchTargets.packageName],
                enabled = row[PatchTargets.enabled],
                patches = attachmentsFor(id),
                checkedEntries = checkedEntriesForTarget(id),
            )
        }
    }

    @Serializable
    data class PatchTargetPayload(
        val id: String,
        val name: String = "",
        val apkmirrorUrl: String,
        val packageName: String = "",
        val enabled: Boolean = true,
    )

    suspend fun addPatchTarget(payload: PatchTargetPayload): Result<PatchTargetPayload> = db.tx {
        if (!isValidSlug(payload.id)) return@tx Result.Error("A valid id (letters, numbers, - and _ only) is required")
        if (payload.apkmirrorUrl.isBlank()) return@tx Result.Error("APKMirror URL is required")
        if (PatchTargets.selectAll().where { PatchTargets.id eq payload.id }.any()) {
            return@tx Result.Error("A patch target with id \"${payload.id}\" already exists")
        }
        PatchTargets.insert {
            it[id] = payload.id
            it[name] = payload.name.ifBlank { payload.id }
            it[apkmirrorUrl] = payload.apkmirrorUrl
            it[packageName] = payload.packageName
            it[enabled] = payload.enabled
        }
        Result.Ok(payload)
    }

    suspend fun updatePatchTarget(id: String, payload: PatchTargetPayload): Result<PatchTargetPayload> = db.tx {
        if (payload.apkmirrorUrl.isBlank()) return@tx Result.Error("APKMirror URL is required")
        val updated = PatchTargets.update({ PatchTargets.id eq id }) {
            it[name] = payload.name.ifBlank { id }
            it[apkmirrorUrl] = payload.apkmirrorUrl
            it[packageName] = payload.packageName
            it[enabled] = payload.enabled
        }
        if (updated == 0) Result.Error("Patch target not found") else Result.Ok(payload)
    }

    /** Deleting a target cascades to its `patch_attachments` and
     * `patch_checked_entries` rows via `ON DELETE CASCADE`. */
    suspend fun deletePatchTarget(id: String): Result<String> = db.tx {
        val deleted = PatchTargets.deleteWhere { SqlExpressionBuilder.run { PatchTargets.id eq id } }
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
    suspend fun attachPatchToTarget(targetId: String, payload: AttachPayload): Result<PatchAttachmentView> = db.tx {
        if (!PatchTargets.selectAll().where { PatchTargets.id eq targetId }.any()) {
            return@tx Result.Error("Patch target not found")
        }
        if (payload.patchId.isBlank()) return@tx Result.Error("patch_id is required")
        val libRow = PatchLibraryTable.selectAll().where { PatchLibraryTable.id eq payload.patchId }.singleOrNull()
            ?: return@tx Result.Error("Patch \"${payload.patchId}\" not found in the library")

        val existing = PatchAttachments.selectAll()
            .where { (PatchAttachments.targetId eq targetId) and (PatchAttachments.patchId eq payload.patchId) }
            .singleOrNull()

        val supportedVersions = payload.supportedVersions?.let { toStringList(it) }
            ?: existing?.get(PatchAttachments.supportedVersions)?.let { toStringList(it) }
            ?: emptyList()
        val patchArgs = payload.patchArgs ?: existing?.get(PatchAttachments.patchArgs) ?: ""
        val patchSelection = payload.patchSelection
            ?: existing?.get(PatchAttachments.patchSelectionJson)?.let {
                runCatching { json.decodeFromString<Map<String, Boolean>>(it) }.getOrDefault(emptyMap())
            }
            ?: emptyMap()
        val optionOverrides = payload.optionOverrides
            ?: existing?.get(PatchAttachments.optionOverridesJson)?.let {
                runCatching { json.decodeFromString<Map<String, Map<String, String>>>(it) }.getOrDefault(emptyMap())
            }
            ?: emptyMap()
        val includeExperimentalVersions = payload.includeExperimentalVersions
            ?: existing?.get(PatchAttachments.includeExperimentalVersions)
            ?: false

        val supportedVersionsStr = supportedVersions.joinToString(",")
        val patchSelectionStr = json.encodeToString(patchSelection)
        val optionOverridesStr = json.encodeToString(optionOverrides)

        if (existing != null) {
            PatchAttachments.update({ (PatchAttachments.targetId eq targetId) and (PatchAttachments.patchId eq payload.patchId) }) {
                it[PatchAttachments.supportedVersions] = supportedVersionsStr
                it[PatchAttachments.patchArgs] = patchArgs
                it[patchSelectionJson] = patchSelectionStr
                it[optionOverridesJson] = optionOverridesStr
                it[PatchAttachments.includeExperimentalVersions] = includeExperimentalVersions
            }
        } else {
            PatchAttachments.insert {
                it[PatchAttachments.targetId] = targetId
                it[patchId] = payload.patchId
                it[PatchAttachments.supportedVersions] = supportedVersionsStr
                it[PatchAttachments.patchArgs] = patchArgs
                it[patchSelectionJson] = patchSelectionStr
                it[optionOverridesJson] = optionOverridesStr
                it[PatchAttachments.includeExperimentalVersions] = includeExperimentalVersions
            }
        }

        Result.Ok(
            PatchAttachmentView(
                patchId = payload.patchId,
                patchName = libRow[PatchLibraryTable.name],
                patchVersion = libRow[PatchLibraryTable.version],
                supportedVersions = supportedVersions,
                patchArgs = patchArgs,
                includeExperimentalVersions = includeExperimentalVersions,
                patchSelection = patchSelection,
                optionOverrides = optionOverrides,
            )
        )
    }

    suspend fun detachPatchFromTarget(targetId: String, patchId: String): Result<String> = db.tx {
        val deleted = PatchAttachments.deleteWhere {
            SqlExpressionBuilder.run { (PatchAttachments.targetId eq targetId) and (PatchAttachments.patchId eq patchId) }
        }
        if (deleted == 0) Result.Error("Attachment not found") else Result.Ok(patchId)
    }

    suspend fun deletePatchCheckedEntry(targetId: String, cacheKey: String): Boolean = db.tx {
        val deleted = PatchCheckedEntries.deleteWhere {
            SqlExpressionBuilder.run { (PatchCheckedEntries.targetId eq targetId) and (PatchCheckedEntries.cacheKey eq cacheKey) }
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
                includePrereleases = row[GithubRepos.includePrereleases] ?: settings[Settings.defaultIncludePrereleases],
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
        val repoId = GithubRepos.selectAll().where { GithubRepos.repo eq repo }.singleOrNull()?.get(GithubRepos.id) ?: return@tx
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
        GithubCheckedReleases.selectAll().associate { it[GithubCheckedReleases.releaseId] to (idToRepo[it[GithubCheckedReleases.repoId]] ?: "unknown-app") }
    }

    data class EnabledPatchTarget(
        val id: String,
        val apkmirrorUrl: String,
        val packageName: String,
        val patches: List<PatchAttachmentView>,
    )

    suspend fun listEnabledPatchTargets(): List<EnabledPatchTarget> = db.tx {
        PatchTargets.selectAll().where { PatchTargets.enabled eq true }.map { row ->
            val id = row[PatchTargets.id]
            EnabledPatchTarget(id, row[PatchTargets.apkmirrorUrl], row[PatchTargets.packageName], attachmentsFor(id))
        }
    }

    data class PatchLibraryEntry(val id: String, val name: String, val file: String, val version: String)

    suspend fun patchLibraryById(): Map<String, PatchLibraryEntry> = db.tx {
        PatchLibraryTable.selectAll().associate {
            it[PatchLibraryTable.id] to PatchLibraryEntry(
                it[PatchLibraryTable.id], it[PatchLibraryTable.name], it[PatchLibraryTable.file], it[PatchLibraryTable.version],
            )
        }
    }

    suspend fun processedPatchCacheKeys(targetId: String): Set<String> = db.tx {
        PatchCheckedEntries.selectAll().where { PatchCheckedEntries.targetId eq targetId }
            .map { it[PatchCheckedEntries.cacheKey] }
            .toSet()
    }

    /** Records one successfully-applied (target, patch, version) result -
     * the SQL replacement for `patchCacheStore.update { ... }` in
     * `PatchScheduler.checkTarget`. */
    suspend fun recordPatchCheckedEntry(
        targetId: String,
        cacheKey: String,
        version: String,
        patchId: String,
        patchVersion: String,
        output: String,
    ) = db.tx {
        val exists = PatchCheckedEntries.selectAll()
            .where { (PatchCheckedEntries.targetId eq targetId) and (PatchCheckedEntries.cacheKey eq cacheKey) }
            .any()
        val processedAt = now()
        if (exists) {
            PatchCheckedEntries.update({ (PatchCheckedEntries.targetId eq targetId) and (PatchCheckedEntries.cacheKey eq cacheKey) }) {
                it[PatchCheckedEntries.version] = version
                it[PatchCheckedEntries.patchId] = patchId
                it[PatchCheckedEntries.patchVersion] = patchVersion
                it[PatchCheckedEntries.processedAt] = processedAt
                it[PatchCheckedEntries.output] = output
            }
        } else {
            PatchCheckedEntries.insert {
                it[PatchCheckedEntries.targetId] = targetId
                it[PatchCheckedEntries.cacheKey] = cacheKey
                it[PatchCheckedEntries.version] = version
                it[PatchCheckedEntries.patchId] = patchId
                it[PatchCheckedEntries.patchVersion] = patchVersion
                it[PatchCheckedEntries.processedAt] = processedAt
                it[PatchCheckedEntries.output] = output
            }
        }
    }
}
