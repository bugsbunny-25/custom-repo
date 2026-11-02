package app.fdroidserver.admin.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** One file of the admin UI, read once from `resources/admin/`. */
private class AdminUiFile(val name: String, val contentType: ContentType) {
    val text: String by lazy {
        AdminUiFile::class.java.classLoader.getResourceAsStream("admin/$name")
            ?.bufferedReader()?.use { it.readText() }
            ?: error("admin/$name resource not found on classpath")
    }
}

private val adminPage = AdminUiFile("index.html", ContentType.Text.Html)

private val adminAssets = listOf(
    AdminUiFile("admin.css", ContentType.Text.CSS),
    AdminUiFile("admin.js", ContentType.Application.JavaScript.withCharset(Charsets.UTF_8)),
).associateBy { it.name }

/**
 * The admin page at `/admin`, plus its stylesheet and script under
 * `/admin/assets/` (nginx already proxies everything under `/admin` here).
 * Only the files listed above are served. `no-cache` makes browsers re-fetch
 * after an upgrade instead of running a stale admin.js against a newer API.
 */
fun Route.adminUiRoutes() {
    get("/admin") { call.respondAdminUiFile(adminPage) }
    get("/admin/") { call.respondAdminUiFile(adminPage) }
    get("/admin/assets/{name}") {
        val asset = adminAssets[call.parameters["name"]]
        if (asset == null) call.respond(HttpStatusCode.NotFound) else call.respondAdminUiFile(asset)
    }
}

private suspend fun ApplicationCall.respondAdminUiFile(file: AdminUiFile) {
    response.header(HttpHeaders.CacheControl, "no-cache")
    respondText(file.text, file.contentType)
}
