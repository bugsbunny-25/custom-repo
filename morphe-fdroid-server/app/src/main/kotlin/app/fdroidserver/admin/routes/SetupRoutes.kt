package app.fdroidserver.admin.routes

import app.fdroidserver.config.AppConfig
import app.fdroidserver.fdroidrepo.FdroidRepoManager
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.io.File
import kotlinx.serialization.Serializable

@Serializable
data class SetupStatusResponse(val initialized: Boolean)

@Serializable
data class SetupOkResponse(val ok: Boolean = true, val settings: AppConfig.SettingsView)

/**
 * First-run setup - the server won't do anything (no scheduler loops, empty
 * GitHub/Patching tabs) until `repo_name`/`repo_description`/`repo_url` are
 * collected here. One-time: [AppConfig.completeSetup] rejects a second call
 * once [AppConfig.SettingsView.initialized] is true - further changes go
 * through `/api/settings` instead.
 *
 * `payload.repoUrl` is the *base* URL (e.g. `https://fdroid.example.com`),
 * stored as-is in settings; [FdroidRepoManager.mainRepoUrl]/[FdroidRepoManager.patchedRepoUrl]
 * append the `/repo`/`/patched/repo` suffixes each repo dir's own
 * `config.yml` needs to match where nginx actually serves it.
 */
fun Route.setupRoutes(
    appConfig: AppConfig,
    fdroidRepoManager: FdroidRepoManager,
    repoDir: File,
    patchedRepoDir: File,
    patchedTvRepoDir: File,
) {
    get("/api/setup/status") {
        call.respond(SetupStatusResponse(appConfig.isInitialized()))
    }

    post("/api/setup/init") {
        val payload = call.receive<AppConfig.SetupPayload>()
        when (val result = appConfig.completeSetup(payload)) {
            is AppConfig.Result.Error -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(result.message))
            is AppConfig.Result.Ok -> {
                fdroidRepoManager.writeRepoMetadata(
                    repoDir, payload.repoName, payload.repoDescription,
                    FdroidRepoManager.mainRepoUrl(payload.repoUrl), payload.repoIcon,
                )
                fdroidRepoManager.writeRepoMetadata(
                    patchedRepoDir, payload.repoName, payload.repoDescription,
                    FdroidRepoManager.patchedRepoUrl(payload.repoUrl), payload.repoIcon,
                )
                fdroidRepoManager.writeRepoMetadata(
                    patchedTvRepoDir, payload.repoName, payload.repoDescription,
                    FdroidRepoManager.patchedTvRepoUrl(payload.repoUrl), payload.repoIcon,
                )
                call.respond(HttpStatusCode.Created, SetupOkResponse(settings = result.value))
            }
        }
    }
}
