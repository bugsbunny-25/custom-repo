package app.fdroidserver.admin.routes

import app.fdroidserver.config.AppConfig
import app.fdroidserver.fdroidrepo.FdroidRepoManager
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import java.io.File
import kotlinx.serialization.Serializable

@Serializable
data class SettingsResponse(val settings: AppConfig.SettingsView)

/**
 * The Settings tab's API. `repo_url` is deliberately not part of
 * [AppConfig.SettingsUpdatePayload] - it's locked once initialized (set only
 * during `/api/setup/init`) since changing it would desync the served
 * F-Droid repo from clients that already subscribed at the old URL.
 * `repo_name`/`repo_description`/`repo_icon` are editable and re-applied to
 * both F-Droid repo dirs' own `config.yml` on save.
 */
fun Route.settingsRoutes(
    appConfig: AppConfig,
    fdroidRepoManager: FdroidRepoManager,
    repoDir: File,
    patchedRepoDir: File,
    patchedTvRepoDir: File,
) {
    get("/api/settings") {
        call.respond(SettingsResponse(appConfig.getSettings()))
    }

    put("/api/settings") {
        val payload = call.receive<AppConfig.SettingsUpdatePayload>()
        val updated = appConfig.updateSettings(payload)
        if (payload.repoName != null || payload.repoDescription != null || payload.repoIcon != null) {
            fdroidRepoManager.writeRepoMetadata(
                repoDir, updated.repoName, updated.repoDescription,
                FdroidRepoManager.mainRepoUrl(updated.repoUrl), updated.repoIcon,
            )
            fdroidRepoManager.writeRepoMetadata(
                patchedRepoDir, updated.repoName, updated.repoDescription,
                FdroidRepoManager.patchedRepoUrl(updated.repoUrl), updated.repoIcon,
            )
            fdroidRepoManager.writeRepoMetadata(
                patchedTvRepoDir, updated.repoName, updated.repoDescription,
                FdroidRepoManager.patchedTvRepoUrl(updated.repoUrl), updated.repoIcon,
            )
        }
        call.respond(SettingsResponse(updated))
    }
}
