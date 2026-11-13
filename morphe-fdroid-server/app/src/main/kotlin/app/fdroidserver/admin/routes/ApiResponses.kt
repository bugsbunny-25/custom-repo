package app.fdroidserver.admin.routes

import app.fdroidserver.builder.TriggerOutcome
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingContext
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

/**
 * Turns a [TriggerOutcome] into the HTTP response the admin UI expects.
 *
 * The three outcomes map to three status codes on purpose:
 *  - 200 with `updated` - the work is done (all-in-one mode).
 *  - 202 with `queued` + `request_id` - accepted, a builder will do it. The
 *    UI switches to polling /api/builder/status when it sees this.
 *  - 409 - something is already running; the UI shows the message as-is.
 */
suspend fun RoutingContext.respondToTrigger(outcome: TriggerOutcome) {
    when (outcome) {
        is TriggerOutcome.Ran -> call.respond(RunResponse(updated = outcome.updated))
        is TriggerOutcome.Queued -> call.respond(
            HttpStatusCode.Accepted,
            RunResponse(updated = false, queued = true, requestId = outcome.requestId),
        )
        is TriggerOutcome.Busy -> call.respond(HttpStatusCode.Conflict, ErrorResponse(outcome.message))
    }
}
