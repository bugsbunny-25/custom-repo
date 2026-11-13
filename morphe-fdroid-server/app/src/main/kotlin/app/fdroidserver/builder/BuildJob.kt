package app.fdroidserver.builder

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a builder run was asked to do.
 *
 * These names are part of the on-disk request format that the host side
 * writes/reads (see docs/integration-spec.md), so they are spelled
 * explicitly with [SerialName] rather than relying on enum-name defaults -
 * renaming a Kotlin constant must not silently change the file format.
 */
@Serializable
enum class BuildJobKind {
    /** Everything the nightly timer does: GitHub releases, patch sources,
     * patch runs for both channels, index updates, pruning, cleanup. */
    @SerialName("full")
    FULL,

    /** GitHub release check + main repo index update only. */
    @SerialName("github")
    GITHUB,

    /** Patch sweep for the mobile channel (all enabled targets). */
    @SerialName("patch")
    PATCH,

    /** Patch sweep for the TV channel. */
    @SerialName("patch-tv")
    PATCH_TV,

    /** One mobile patch target, by id. Requires [BuildRequest.targetId]. */
    @SerialName("patch-target")
    PATCH_TARGET,

    /** One TV patch target, by id. Requires [BuildRequest.targetId]. */
    @SerialName("patch-target-tv")
    PATCH_TARGET_TV,

    /** One mobile target at an explicit version page URL. Requires
     * [BuildRequest.targetId], [BuildRequest.version] and
     * [BuildRequest.versionPageUrl]. */
    @SerialName("patch-version")
    PATCH_VERSION,

    /** As above, TV channel. */
    @SerialName("patch-version-tv")
    PATCH_VERSION_TV,

    /** Re-check the configured patch sources for new `.mpp` bundles. */
    @SerialName("patch-source")
    PATCH_SOURCE,

    /** As above, TV channel. */
    @SerialName("patch-source-tv")
    PATCH_SOURCE_TV,

    /** Housekeeping only: prune the archive, delete build intermediates,
     * refresh the indexes. Does not download or patch anything. */
    @SerialName("housekeeping")
    HOUSEKEEPING,
}

/**
 * One file under `$REPO_PATH/.requests/<id>.json`.
 *
 * The admin server (which no longer does any patching itself when
 * `RUN_SCHEDULERS=false`) writes these; a systemd `.path` unit on the host
 * notices the new file and starts the builder container, which claims and
 * executes them. Nothing here needs a container socket or any extra
 * privilege - it is just a file in a directory both containers mount.
 *
 * Unknown fields are ignored on read, and every optional field has a
 * default, so a request written by an older or newer build still decodes.
 */
@Serializable
data class BuildRequest(
    val id: String,
    val kind: BuildJobKind,
    /** ISO-8601 instant, UTC. */
    val requestedAt: String,
    val targetId: String? = null,
    val version: String? = null,
    val versionPageUrl: String? = null,
    /** For [BuildJobKind.GITHUB]: limit the check to this `owner/name`. */
    val repo: String? = null,
    /** Free-text note for whoever reads the status later, e.g. "admin UI". */
    val source: String = "unknown",
)
