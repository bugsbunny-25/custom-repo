package app.fdroidserver.admin.routes

import app.fdroidserver.config.AppConfig
import app.fdroidserver.patching.PatchScheduler
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable

@Serializable
data class TargetsResponse(val targets: List<AppConfig.PatchTargetView>)

@Serializable
data class TargetOkResponse(val ok: Boolean = true, val target: AppConfig.PatchTargetPayload)

@Serializable
data class AttachmentOkResponse(val ok: Boolean = true, val attachment: AppConfig.PatchAttachmentView)

@Serializable
data class RunResponse(val ok: Boolean = true, val updated: Boolean)

@Serializable
data class RunVersionPayload(val version: String, val versionPageUrl: String)

/** The Patching tab's "Patched Apps" API - CRUD on patch targets plus
 * attach/detach of library patches, matching the Python version's
 * `/api/patch-targets*` routes exactly. Registered twice (once per
 * [AppConfig.PatchSchema] / [PatchScheduler] instance) with distinct
 * [basePath]s so the "Patching" and "Patched TV" tabs get fully independent
 * app targets. */
fun Route.patchTargetRoutes(
    appConfig: AppConfig,
    patchScheduler: PatchScheduler,
    schema: AppConfig.PatchSchema,
    basePath: String = "/api/patch-targets",
) {
    get(basePath) {
        call.respond(TargetsResponse(appConfig.listPatchTargets(schema)))
    }

    // "Patch now" / "Patch all" buttons in the admin UI - runs the same
    // pipeline as the scheduled background sweep, synchronously, for either
    // one target or every enabled target.
    post("$basePath/{id}/run") {
        val id = call.parameters["id"]
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid patch target id"))
            return@post
        }
        val updated = patchScheduler.runTargetNow(id)
        call.respond(RunResponse(updated = updated))
    }

    // "Patch specific version" button - patches a target at a user-pasted
    // APKMirror version page URL (and user-typed version string, since
    // version formats vary too much across apps to reliably scrape) with
    // every attached patch, bypassing the normal version-listing/matching so
    // it works even for versions the target's app-listing page hasn't
    // surfaced or that don't match any attachment's supported_versions.
    post("$basePath/{id}/run-version") {
        val id = call.parameters["id"]
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid patch target id"))
            return@post
        }
        val payload = call.receive<RunVersionPayload>()
        if (payload.version.isBlank() || payload.versionPageUrl.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("version and versionPageUrl are required"))
            return@post
        }
        val updated = patchScheduler.runSpecificVersion(id, payload.version, payload.versionPageUrl)
        call.respond(RunResponse(updated = updated))
    }

    post("$basePath/run-all") {
        val updated = patchScheduler.checkForUpdates()
        call.respond(RunResponse(updated = updated))
    }

    post(basePath) {
        val payload = call.receive<AppConfig.PatchTargetPayload>()
        val result = appConfig.addPatchTarget(schema, payload)
        when (result) {
            is AppConfig.Result.Error -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(result.message))
            is AppConfig.Result.Ok -> call.respond(HttpStatusCode.Created, TargetOkResponse(target = result.value))
        }
    }

    put("$basePath/{id}") {
        val id = call.parameters["id"]
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid patch target id"))
            return@put
        }
        val payload = call.receive<AppConfig.PatchTargetPayload>()
        val result = appConfig.updatePatchTarget(schema, id, payload)
        when (result) {
            is AppConfig.Result.Error -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(result.message))
            is AppConfig.Result.Ok -> call.respond(TargetOkResponse(target = result.value))
        }
    }

    delete("$basePath/{id}") {
        val id = call.parameters["id"]
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid patch target id"))
            return@delete
        }
        val result = appConfig.deletePatchTarget(schema, id)
        when (result) {
            is AppConfig.Result.Error -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(result.message))
            is AppConfig.Result.Ok -> call.respond(DeletedResponse(deleted = mapOf("id" to id)))
        }
    }

    post("$basePath/{id}/attachments") {
        val targetId = call.parameters["id"]
        if (targetId == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid patch target id"))
            return@post
        }
        val payload = call.receive<AppConfig.AttachPayload>()
        val result = appConfig.attachPatchToTarget(schema, targetId, payload)
        when (result) {
            is AppConfig.Result.Error -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(result.message))
            is AppConfig.Result.Ok -> call.respond(HttpStatusCode.Created, AttachmentOkResponse(attachment = result.value))
        }
    }

    delete("$basePath/{id}/attachments/{patchId}") {
        val targetId = call.parameters["id"]
        val patchId = call.parameters["patchId"]
        if (targetId == null || patchId == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid patch target id"))
            return@delete
        }
        val result = appConfig.detachPatchFromTarget(schema, targetId, patchId)
        when (result) {
            is AppConfig.Result.Error -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(result.message))
            is AppConfig.Result.Ok -> call.respond(DeletedResponse(deleted = mapOf("target_id" to targetId, "patch_id" to patchId)))
        }
    }

    delete("$basePath/{id}/checked/{cacheKey}") {
        val targetId = call.parameters["id"]
        val cacheKey = call.parameters["cacheKey"]
        if (targetId == null || cacheKey == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid patch target id"))
            return@delete
        }
        val ok = appConfig.deletePatchCheckedEntry(schema, targetId, cacheKey)
        if (ok) {
            call.respond(DeletedResponse(deleted = mapOf("target_id" to targetId, "cache_key" to cacheKey)))
        } else {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Checked entry not found"))
        }
    }
}
