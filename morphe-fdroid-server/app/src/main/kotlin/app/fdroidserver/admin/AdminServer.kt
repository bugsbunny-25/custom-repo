package app.fdroidserver.admin

import app.fdroidserver.admin.routes.patchLibraryRoutes
import app.fdroidserver.admin.routes.patchTargetRoutes
import app.fdroidserver.admin.routes.repoRoutes
import app.fdroidserver.admin.routes.settingsRoutes
import app.fdroidserver.admin.routes.setupRoutes
import app.fdroidserver.config.AppConfig
import app.fdroidserver.fdroidrepo.FdroidRepoManager
import app.fdroidserver.patching.PatchLibrary
import app.fdroidserver.patching.PatchScheduler
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import java.io.File

/**
 * The admin web UI + JSON API - direct replacement for the old Python
 * `config_ui.py`'s stdlib `ThreadingHTTPServer`. Uses Ktor instead, but the
 * HTML/JS (`resources/admin.html`, ported close to verbatim from
 * `config_ui.py`'s embedded `HTML` string) and every JSON API endpoint's
 * path, method, and JSON shape are unchanged, so the UI itself required no
 * redesign - only its backend implementation changed (now SQLite via
 * [AppConfig] instead of `config.yml`/`cache.json` files).
 *
 * JSON field names: the ported admin UI JS expects snake_case keys
 * (`include_prereleases`, `apkmirror_url`, etc.). Rather than annotate every
 * property with `@SerialName`, this configures kotlinx.serialization's
 * [JsonNamingStrategy.SnakeCase] globally, so idiomatic camelCase Kotlin
 * properties (`includePrereleases`) serialize as the snake_case the JS
 * expects automatically.
 */
@OptIn(ExperimentalSerializationApi::class)
class AdminServer(
    private val appConfig: AppConfig,
    private val patchLibrary: PatchLibrary,
    private val patchesDir: File,
    private val fdroidRepoManager: FdroidRepoManager,
    private val repoDir: File,
    private val patchedRepoDir: File,
    private val patchScheduler: PatchScheduler,
    private val host: String = "0.0.0.0",
    private val port: Int = 5001,
) {
    private val adminHtml: String by lazy {
        AdminServer::class.java.classLoader.getResourceAsStream("admin.html")
            ?.bufferedReader()?.use { it.readText() }
            ?: error("admin.html resource not found on classpath")
    }

    private var engine: EmbeddedServer<out ApplicationEngine, out ApplicationEngine.Configuration>? = null

    fun start() {
        engine = embeddedServer(Netty, port = port, host = host) {
            configureServer()
        }.start(wait = false)
    }

    fun stop() {
        engine?.stop(1000, 2000)
    }

    /** internal (not private) so `testApplication { application { ... } }`
     * integration tests can install the same routes/plugins without going
     * through a real Netty embeddedServer. */
    internal fun Application.configureServer() {
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    namingStrategy = JsonNamingStrategy.SnakeCase
                },
            )
        }
        routing {
            get("/admin") { call.respondText(adminHtml, ContentType.Text.Html) }
            get("/admin/") { call.respondText(adminHtml, ContentType.Text.Html) }
            setupRoutes(appConfig, fdroidRepoManager, repoDir, patchedRepoDir)
            settingsRoutes(appConfig, fdroidRepoManager, repoDir, patchedRepoDir)
            repoRoutes(appConfig)
            patchLibraryRoutes(appConfig, patchLibrary, patchesDir)
            patchTargetRoutes(appConfig, patchScheduler)
        }
    }
}
