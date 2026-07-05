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

/** The Patching tab's "Patched Apps" API - CRUD on patch targets plus
 * attach/detach of library patches, matching the Python version's
 * `/api/patch-targets*` routes exactly. */
fun Route.patchTargetRoutes(appConfig: AppConfig, patchScheduler: PatchScheduler) {
    get("/api/patch-targets") {
        call.respond(TargetsResponse(appConfig.listPatchTargets()))
    }

    // "Patch now" / "Patch all" buttons in the admin UI - runs the same
    // pipeline as the scheduled background sweep, synchronously, for either
    // one target or every enabled target.
    post("/api/patch-targets/{id}/run") {
        val id = call.parameters["id"]
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid patch target id"))
            return@post
        }
        val updated = patchScheduler.runTargetNow(id)
        call.respond(RunResponse(updated = updated))
    }

    post("/api/patch-targets/run-all") {
        val updated = patchScheduler.checkForUpdates()
        call.respond(RunResponse(updated = updated))
    }

    post("/api/patch-targets") {
        val payload = call.receive<AppConfig.PatchTargetPayload>()
        val result = appConfig.addPatchTarget(payload)
        when (result) {
            is AppConfig.Result.Error -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(result.message))
            is AppConfig.Result.Ok -> call.respond(HttpStatusCode.Created, TargetOkResponse(target = result.value))
        }
    }

    put("/api/patch-targets/{id}") {
        val id = call.parameters["id"]
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid patch target id"))
            return@put
        }
        val payload = call.receive<AppConfig.PatchTargetPayload>()
        val result = appConfig.updatePatchTarget(id, payload)
        when (result) {
            is AppConfig.Result.Error -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(result.message))
            is AppConfig.Result.Ok -> call.respond(TargetOkResponse(target = result.value))
        }
    }

    delete("/api/patch-targets/{id}") {
        val id = call.parameters["id"]
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid patch target id"))
            return@delete
        }
        val result = appConfig.deletePatchTarget(id)
        when (result) {
            is AppConfig.Result.Error -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(result.message))
            is AppConfig.Result.Ok -> call.respond(DeletedResponse(deleted = mapOf("id" to id)))
        }
    }

    post("/api/patch-targets/{id}/attachments") {
        val targetId = call.parameters["id"]
        if (targetId == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid patch target id"))
            return@post
        }
        val payload = call.receive<AppConfig.AttachPayload>()
        val result = appConfig.attachPatchToTarget(targetId, payload)
        when (result) {
            is AppConfig.Result.Error -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(result.message))
            is AppConfig.Result.Ok -> call.respond(HttpStatusCode.Created, AttachmentOkResponse(attachment = result.value))
        }
    }

    delete("/api/patch-targets/{id}/attachments/{patchId}") {
        val targetId = call.parameters["id"]
        val patchId = call.parameters["patchId"]
        if (targetId == null || patchId == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid patch target id"))
            return@delete
        }
        val result = appConfig.detachPatchFromTarget(targetId, patchId)
        when (result) {
            is AppConfig.Result.Error -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(result.message))
            is AppConfig.Result.Ok -> call.respond(DeletedResponse(deleted = mapOf("target_id" to targetId, "patch_id" to patchId)))
        }
    }

    delete("/api/patch-targets/{id}/checked/{cacheKey}") {
        val targetId = call.parameters["id"]
        val cacheKey = call.parameters["cacheKey"]
        if (targetId == null || cacheKey == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid patch target id"))
            return@delete
        }
        val ok = appConfig.deletePatchCheckedEntry(targetId, cacheKey)
        if (ok) {
            call.respond(DeletedResponse(deleted = mapOf("target_id" to targetId, "cache_key" to cacheKey)))
        } else {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Checked entry not found"))
        }
    }
}
