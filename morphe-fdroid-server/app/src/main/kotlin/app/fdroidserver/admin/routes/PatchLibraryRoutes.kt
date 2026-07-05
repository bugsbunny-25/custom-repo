package app.fdroidserver.admin.routes

import app.fdroidserver.config.AppConfig
import app.fdroidserver.patching.PatchLibrary
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import java.io.File
import java.util.Base64
import kotlinx.serialization.Serializable

@Serializable
data class LibraryResponse(val library: List<AppConfig.PatchLibraryEntryView>)

@Serializable
data class PatchOkResponse(val ok: Boolean = true, val patch: AppConfig.PatchLibraryEntryView)

@Serializable
data class InspectResponse(val patchId: String, val patches: List<PatchLibrary.PatchInfo>)

/** The Patching tab's "Patch Library" API - upload/update/delete `.mpp`
 * files and introspect them, matching the Python version's
 * `/api/patch-library*` routes exactly. */
fun Route.patchLibraryRoutes(
    appConfig: AppConfig,
    patchLibrary: PatchLibrary,
    patchesDir: File,
) {
    get("/api/patch-library") {
        call.respond(LibraryResponse(appConfig.listPatchLibrary()))
    }

    post("/api/patch-library") {
        val payload = call.receive<AppConfig.PatchLibraryPayload>()
        if (!appConfig.isValidSlug(payload.id)) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("A valid id (letters, numbers, - and _ only) is required"))
            return@post
        }
        val filename = payload.filename
        val contentBase64 = payload.contentBase64
        if (filename.isNullOrBlank() || contentBase64.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("A .mpp file is required"))
            return@post
        }

        val storedName = "${payload.id}.mpp"
        val bytes = try {
            Base64.getDecoder().decode(contentBase64)
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid base64 content"))
            return@post
        }

        val result = appConfig.addPatchToLibrary(payload.id, payload.name, storedName)
        when (result) {
            is AppConfig.Result.Error -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(result.message))
            is AppConfig.Result.Ok -> {
                patchesDir.mkdirs()
                File(patchesDir, storedName).writeBytes(bytes)
                call.respond(HttpStatusCode.Created, PatchOkResponse(patch = result.value))
            }
        }
    }

    put("/api/patch-library/{id}") {
        val id = call.parameters["id"]
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid patch id"))
            return@put
        }
        val payload = call.receive<AppConfig.PatchLibraryPayload>()

        var bytesToWrite: ByteArray? = null
        if (!payload.contentBase64.isNullOrBlank()) {
            bytesToWrite = try {
                Base64.getDecoder().decode(payload.contentBase64)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid base64 content"))
                return@put
            }
        }

        val existing = appConfig.listPatchLibrary().firstOrNull { it.id == id }
        val newFileName = if (bytesToWrite != null) (existing?.file?.ifBlank { "$id.mpp" } ?: "$id.mpp") else null
        val updateResult = appConfig.updatePatchInLibrary(id, payload.name, payload.version, newFileName)
        if (updateResult is AppConfig.Result.Ok && updateResult.value.contentUpdated) {
            appConfig.resetPatchCustomizations(id)
            appConfig.invalidatePatchCache(id)
        }

        when (updateResult) {
            is AppConfig.Result.Error -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(updateResult.message))
            is AppConfig.Result.Ok -> {
                if (bytesToWrite != null) {
                    patchesDir.mkdirs()
                    File(patchesDir, updateResult.value.entry.file).writeBytes(bytesToWrite)
                }
                call.respond(PatchOkResponse(patch = updateResult.value.entry))
            }
        }
    }

    delete("/api/patch-library/{id}") {
        val id = call.parameters["id"]
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid patch id"))
            return@delete
        }

        val result = appConfig.deletePatchFromLibrary(id)
        when (result) {
            is AppConfig.Result.Error -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(result.message))
            is AppConfig.Result.Ok -> {
                result.value.storedFile?.let { File(patchesDir, it).delete() }
                appConfig.invalidatePatchCache(id)
                call.respond(DeletedResponse(deleted = mapOf("id" to id)))
            }
        }
    }

    get("/api/patch-library/{id}/inspect") {
        val id = call.parameters["id"]
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid patch id"))
            return@get
        }
        val entry = appConfig.listPatchLibrary().firstOrNull { it.id == id }
        if (entry == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Patch not found"))
            return@get
        }
        val patchFile = File(patchesDir, entry.file)
        if (!patchFile.exists()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Patch file missing on disk: $patchFile"))
            return@get
        }

        val patches = try {
            patchLibrary.inspect(patchFile)
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to inspect patch: ${e.message}"))
            return@get
        }
        call.respond(InspectResponse(id, patches))
    }
}
