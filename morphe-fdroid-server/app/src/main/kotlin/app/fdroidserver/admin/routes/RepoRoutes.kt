package app.fdroidserver.admin.routes

import app.fdroidserver.config.AppConfig
import app.fdroidserver.github.GithubScheduler
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
data class ReposResponse(val repos: List<AppConfig.GithubRepoView>)

@Serializable
data class CheckStartedResponse(val started: Boolean = true)

@Serializable
data class RepoOkResponse(val ok: Boolean = true, val repo: AppConfig.RepoPayload)

/** The "GitHub" tab's API - unchanged endpoints/shapes from the Python
 * version's `/api/repos*` routes, just backed by SQLite via [AppConfig]. */
fun Route.repoRoutes(appConfig: AppConfig, githubScheduler: GithubScheduler) {
    get("/api/repos") {
        call.respond(ReposResponse(appConfig.listRepos()))
    }

    post("/api/repos") {
        val payload = call.receive<AppConfig.RepoPayload>()
        val newId = appConfig.addRepo(payload)
        if (newId == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Repo is required"))
        } else {
            call.respond(HttpStatusCode.Created, RepoOkResponse(repo = payload))
        }
    }

    put("/api/repos/{id}") {
        val id = call.parameters["id"]?.toIntOrNull()
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid repo id"))
            return@put
        }
        val payload = call.receive<AppConfig.RepoPayload>()
        val ok = appConfig.updateRepo(id, payload)
        if (ok) {
            call.respond(RepoOkResponse(repo = payload))
        } else {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Repo not found"))
        }
    }

    // "Check now" buttons. The check runs in the background (downloads can
    // take longer than the nginx proxy timeout) and the UI polls
    // /api/repos/check-status until it finishes.
    post("/api/repos/check") {
        if (githubScheduler.startCheckNow()) {
            call.respond(HttpStatusCode.Accepted, CheckStartedResponse())
        } else {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("A GitHub check is already running"))
        }
    }

    post("/api/repos/{id}/check") {
        val id = call.parameters["id"]?.toIntOrNull()
        val repo = id?.let { repoId -> appConfig.listRepos().firstOrNull { it.id == repoId } }
        if (repo == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Repo not found"))
        } else if (githubScheduler.startCheckNow(repo.repo)) {
            call.respond(HttpStatusCode.Accepted, CheckStartedResponse())
        } else {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("A GitHub check is already running"))
        }
    }

    get("/api/repos/check-status") {
        call.respond(githubScheduler.checkStatus)
    }

    delete("/api/repos/{id}/checked/{releaseId}") {
        val id = call.parameters["id"]?.toIntOrNull()
        val releaseId = call.parameters["releaseId"]
        if (id == null || releaseId == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid repo id"))
            return@delete
        }
        val ok = appConfig.deleteCheckedEntry(id, releaseId)
        if (ok) {
            call.respond(DeletedResponse(deleted = mapOf("repo_id" to id.toString(), "release_id" to releaseId)))
        } else {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Checked entry not found"))
        }
    }
}
