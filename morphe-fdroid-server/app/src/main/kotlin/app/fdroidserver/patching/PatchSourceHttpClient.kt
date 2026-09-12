package app.fdroidserver.patching

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpHeaders

/**
 * The Ktor client the vendored engine's patch sources run on
 * ([app.morphe.engine.network.HttpService] wraps whatever client it is
 * handed).
 *
 * The engine's [app.morphe.engine.patches.GitHubPatchSource] sends no
 * credentials - morphe-desktop talks to GitHub anonymously. A server polling
 * several repos on a schedule runs into the 60 requests/hour unauthenticated
 * limit much sooner than a desktop user does, so the configured GitHub token
 * (the same one the GitHub tab uses) is attached here instead, leaving the
 * engine files untouched.
 *
 * The token is attached **only** to GitHub hosts. A `defaultRequest { }` block
 * would be the obvious place for this, but the URL isn't resolved yet when
 * that block runs, so there would be no way to tell GitHub from GitLab and the
 * operator's GitHub credential would be sent to gitlab.com on every request.
 */
object PatchSourceHttpClient {

    /** api.github.com for the releases API, raw.githubusercontent.com for the
     * `patches-bundle.json` fast path, objects.githubusercontent.com for the
     * asset downloads the release API redirects to. */
    private val GITHUB_HOSTS = setOf(
        "api.github.com",
        "github.com",
        "raw.githubusercontent.com",
        "objects.githubusercontent.com",
    )

    fun create(githubToken: String?): HttpClient {
        val token = githubToken?.takeIf { it.isNotBlank() }
        return HttpClient(CIO) {
            if (token != null) {
                install(
                    createClientPlugin("GitHubTokenAuth") {
                        onRequest { request, _ ->
                            if (request.url.host in GITHUB_HOSTS) {
                                request.headers.append(HttpHeaders.Authorization, "Bearer $token")
                            }
                        }
                    },
                )
            }
        }
    }
}
