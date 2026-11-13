package app.fdroidserver.admin.routes

import app.fdroidserver.builder.BuildJobKind
import app.fdroidserver.builder.BuildState
import app.fdroidserver.builder.BuildTrigger
import app.fdroidserver.builder.TriggerOutcome
import app.fdroidserver.config.AppConfig
import app.fdroidserver.github.GithubScheduler
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
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
fun Route.repoRoutes(appConfig: AppConfig, githubScheduler: GithubScheduler, buildTrigger: BuildTrigger) {
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
        respondToCheck(buildTrigger.trigger(BuildJobKind.GITHUB))
    }

    post("/api/repos/{id}/check") {
        val id = call.parameters["id"]?.toIntOrNull()
        val repo = id?.let { repoId -> appConfig.listRepos().firstOrNull { it.id == repoId } }
        if (repo == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Repo not found"))
        } else if (!repo.enabled) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("${repo.repo} is paused - switch it back on to check it"))
        } else {
            respondToCheck(buildTrigger.trigger(BuildJobKind.GITHUB, repo = repo.repo))
        }
    }

    // In all-in-one mode this is the scheduler's own live state. In split
    // mode the check happens in the builder container, so the builder's
    // status document is translated into the same shape - which is what lets
    // the admin UI's existing polling loop work unchanged in both modes.
    get("/api/repos/check-status") {
        val builderStatus = buildTrigger.status()
        if (builderStatus == null) {
            call.respond(githubScheduler.checkStatus)
        } else {
            val githubStep = builderStatus.steps.firstOrNull { it.name == "github-releases" }
            call.respond(
                GithubScheduler.CheckStatus(
                    running = builderStatus.state == BuildState.RUNNING,
                    repo = builderStatus.githubRepo,
                    downloaded = builderStatus.githubDownloaded,
                    indexUpdated = builderStatus.indexUpdated,
                    finishedAt = builderStatus.finishedAt,
                    error = builderStatus.error ?: githubStep?.takeIf { it.state == "failed" }?.message,
                ),
            )
        }
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

/**
 * The GitHub check's own response shape.
 *
 * Unlike a patch run, this endpoint was always asynchronous - it returns 202
 * immediately and the UI polls /api/repos/check-status - so a queued request
 * in split mode is indistinguishable to the UI from a started check in
 * all-in-one mode, and both answer 202.
 */
private suspend fun RoutingContext.respondToCheck(outcome: TriggerOutcome) {
    when (outcome) {
        is TriggerOutcome.Ran -> call.respond(HttpStatusCode.Accepted, CheckStartedResponse())
        is TriggerOutcome.Queued -> call.respond(HttpStatusCode.Accepted, CheckStartedResponse())
        is TriggerOutcome.Busy -> call.respond(HttpStatusCode.Conflict, ErrorResponse(outcome.message))
    }
}
