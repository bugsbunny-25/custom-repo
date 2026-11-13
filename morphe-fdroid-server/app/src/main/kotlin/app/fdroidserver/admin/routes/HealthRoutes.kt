package app.fdroidserver.admin.routes

import app.fdroidserver.builder.BuildStatus
import app.fdroidserver.builder.BuildTrigger
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

@Serializable
data class HealthResponse(val ok: Boolean = true)

/**
 * `GET /api/health` and `GET /api/builder/status`.
 *
 * /api/health exists because nginx's own `/health` cannot say anything about
 * this process: it is a static `return 200` in the nginx config, so it keeps
 * answering cheerfully while the JVM behind /admin and /api/ is wedged. The
 * container's HEALTHCHECK probes both, which is what lets `podman
 * auto-update` reject an image whose app starts but does not work.
 *
 * It deliberately does no I/O - no database query, no disk check. A health
 * probe that touches SQLite would start failing during a long write and get
 * a working container restarted for no reason. Answering at all proves what
 * this endpoint is for: the HTTP server is up and serving routes.
 */
fun Route.healthRoutes(buildTrigger: BuildTrigger) {
    get("/api/health") {
        call.respond(HealthResponse())
    }

    // Null in all-in-one mode, where there is no separate builder - the UI
    // falls back to the per-scheduler endpoints it already polls.
    //
    // Encoded here rather than handed to ContentNegotiation because this is
    // the one response whose *default* values carry meaning: a builder that
    // has never run is `state: IDLE` with everything else at its default,
    // and the app-wide Json omits defaults, which would send `{}`. The UI
    // reads `state` to decide whether to keep waiting for the builder
    // container to start, so an absent `state` makes it give up immediately.
    get("/api/builder/status") {
        val status: BuildStatus? = buildTrigger.status()
        if (status == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("No builder is configured (RUN_SCHEDULERS is on)"))
        } else {
            call.respondText(statusJson.encodeToString(status), ContentType.Application.Json)
        }
    }
}

/**
 * Same snake_case naming as every other endpoint (the admin UI reads
 * `current_step`, `github_downloaded`), but with defaults encoded.
 */
@OptIn(ExperimentalSerializationApi::class)
private val statusJson = Json {
    encodeDefaults = true
    namingStrategy = JsonNamingStrategy.SnakeCase
}
