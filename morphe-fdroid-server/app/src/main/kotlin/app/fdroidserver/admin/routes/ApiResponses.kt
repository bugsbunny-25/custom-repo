package app.fdroidserver.admin.routes

import kotlinx.serialization.Serializable

/**
 * Small shared response wrappers for the admin JSON API. kotlinx.serialization
 * needs a concrete type to encode (unlike Python's `json.dumps` on a raw
 * heterogeneous dict), so these replace what were ad-hoc dicts in the
 * Python version. Field names/shapes match the ported admin UI JS exactly
 * (see the audit notes in `AppConfig.kt`/`PatchLibrary.kt`).
 */
@Serializable
data class ErrorResponse(val error: String)

@Serializable
data class OkResponse(val ok: Boolean = true)

@Serializable
data class DeletedResponse(val ok: Boolean = true, val deleted: Map<String, String>)
