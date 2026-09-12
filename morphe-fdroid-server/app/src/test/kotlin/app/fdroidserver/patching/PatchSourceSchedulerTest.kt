package app.fdroidserver.patching

import app.fdroidserver.config.AppConfig
import app.fdroidserver.config.AppDatabase
import app.morphe.engine.model.Release
import app.morphe.engine.model.ReleaseAsset
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Covers [PatchSourceScheduler] end to end against canned HTTP responses.
 *
 * The HTTP client is Ktor's [MockEngine] rather than a mocked
 * `RemotePatchSource`, so the vendored engine's **real** GitHub and GitLab
 * sources run: their endpoint URLs, headers, JSON normalization (GitLab's
 * `assets.links[]` / `direct_asset_url` shape in particular) and the
 * `patches-bundle.json` fast path are all exercised, not stubbed out. The
 * request URLs the tests assert on are therefore also a check that we're
 * talking to the same endpoints morphe-desktop talks to.
 */
class PatchSourceSchedulerTest {

    private fun newAppConfig(tempDir: File): AppConfig =
        AppConfig(AppDatabase(File(tempDir, "db").apply { mkdirs() }))

    /** Records every request so tests can assert on which endpoints were hit,
     * and answers from [routes] - the first entry whose key is a substring of
     * the request URL wins; anything unmatched is a 404, which is what the
     * engine treats as "this source doesn't publish that". */
    private class FakeRemote(private val routes: List<Pair<String, String>>) {
        val requested = mutableListOf<String>()

        fun client(): HttpClient = HttpClient(
            MockEngine { request: HttpRequestData ->
                val url = request.url.toString()
                requested += url
                val body = routes.firstOrNull { (match, _) -> match in url }?.second
                if (body == null) {
                    respondError(HttpStatusCode.NotFound)
                } else {
                    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            },
        )
    }

    private fun githubReleasesJson(tag: String, vararg assetNames: String): String {
        val assets = assetNames.joinToString(",") { name ->
            """{"id":1,"name":"$name","browser_download_url":"https://github.com/o/r/releases/download/$tag/$name","size":10}"""
        }
        return """[{"id":100,"tag_name":"$tag","prerelease":false,"draft":false,"assets":[$assets]}]"""
    }

    // ---- selectPatchAsset (pure tie-break logic, no network) ---------------

    private fun scheduler(tempDir: File, client: HttpClient, patchesDir: File): PatchSourceScheduler =
        PatchSourceScheduler(newAppConfig(tempDir), client, patchesDir, AppConfig.PatchSchemas.Mobile)

    private fun release(tag: String, vararg assets: ReleaseAsset) =
        Release(tagName = tag, assets = assets.toList())

    private fun asset(name: String) = ReleaseAsset(name = name, downloadUrl = "https://example.invalid/$name")

    private fun bareScheduler(@TempDir tempDir: File = File(".")): PatchSourceScheduler =
        PatchSourceScheduler(
            io.mockk.mockk(relaxed = true),
            HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) }),
            File("."),
            AppConfig.PatchSchemas.Mobile,
        )

    @Test
    fun `selectPatchAsset returns the only asset unconditionally, even if its name doesn't match the tag`() {
        val only = asset("unrelated-name.mpp")
        assertEquals(only, bareScheduler().selectPatchAsset(release("v1.0.0", only), listOf(only)))
    }

    @Test
    fun `selectPatchAsset picks the asset matching the release tag when there are multiple`() {
        val old = asset("patches-0.9.0.mpp")
        val current = asset("patches-1.0.0.mpp")
        assertEquals(current, bareScheduler().selectPatchAsset(release("v1.0.0", old, current), listOf(old, current)))
    }

    @Test
    fun `selectPatchAsset matches a tag without a leading v too`() {
        val old = asset("patches-0.9.0.mpp")
        val current = asset("patches-1.0.0.mpp")
        assertEquals(current, bareScheduler().selectPatchAsset(release("1.0.0", old, current), listOf(old, current)))
    }

    @Test
    fun `selectPatchAsset falls back to the first asset when none of several match the tag`() {
        val a = asset("patches-a.mpp")
        val b = asset("patches-b.mpp")
        assertEquals(a, bareScheduler().selectPatchAsset(release("v9.9.9", a, b), listOf(a, b)))
    }

    // ---- GitHub ------------------------------------------------------------

    @Test
    fun `imports a new release from a GitHub source and records its version`(@TempDir tempDir: File) = runBlocking {
        val remote = FakeRemote(
            listOf(
                "api.github.com/repos/someowner/somerepo/releases" to githubReleasesJson("v1.0.0", "patches-1.0.0.mpp"),
                "releases/download/v1.0.0/patches-1.0.0.mpp" to "mpp-bytes",
            ),
        )
        val appConfig = newAppConfig(tempDir)
        val patchesDir = File(tempDir, "patches").apply { mkdirs() }
        appConfig.addPatchToLibrary(
            AppConfig.PatchSchemas.Mobile, "yt-ads", "Block Ads", "yt-ads.mpp",
            sourceUrl = "https://github.com/someowner/somerepo",
        )

        val scheduler = PatchSourceScheduler(appConfig, remote.client(), patchesDir, AppConfig.PatchSchemas.Mobile)
        assertTrue(scheduler.checkForUpdates())

        assertEquals("mpp-bytes", File(patchesDir, "yt-ads.mpp").readText())
        val entry = appConfig.listPatchLibrary(AppConfig.PatchSchemas.Mobile).single()
        assertEquals("1.0.0", entry.version)
        assertEquals("yt-ads.mpp", entry.file)
        assertEquals("github", entry.sourceProvider)
        // The manifest fast path is tried before the rate-limited releases API.
        assertTrue(
            remote.requested.any { "raw.githubusercontent.com/someowner/somerepo/main/patches-bundle.json" in it },
            "expected the patches-bundle.json fast path to be tried first, got ${remote.requested}",
        )
    }

    @Test
    fun `prefers the patches-bundle json manifest over the releases API`(@TempDir tempDir: File) = runBlocking {
        val remote = FakeRemote(
            listOf(
                "raw.githubusercontent.com/someowner/somerepo/main/patches-bundle.json" to
                    """{"version":"2.0.0","download_url":"https://cdn.invalid/patches-2.0.0.mpp"}""",
                "cdn.invalid/patches-2.0.0.mpp" to "manifest-bytes",
            ),
        )
        val appConfig = newAppConfig(tempDir)
        val patchesDir = File(tempDir, "patches").apply { mkdirs() }
        appConfig.addPatchToLibrary(
            AppConfig.PatchSchemas.Mobile, "yt-ads", "Block Ads", "yt-ads.mpp",
            sourceUrl = "https://github.com/someowner/somerepo",
        )

        val scheduler = PatchSourceScheduler(appConfig, remote.client(), patchesDir, AppConfig.PatchSchemas.Mobile)
        assertTrue(scheduler.checkForUpdates())

        assertEquals("manifest-bytes", File(patchesDir, "yt-ads.mpp").readText())
        assertEquals("2.0.0", appConfig.listPatchLibrary(AppConfig.PatchSchemas.Mobile).single().version)
        assertFalse(
            remote.requested.any { "api.github.com" in it },
            "the releases API should not be hit when a manifest resolves, got ${remote.requested}",
        )
    }

    @Test
    fun `an entry that opts into pre-releases reads the dev branch manifest`(@TempDir tempDir: File) = runBlocking {
        val remote = FakeRemote(
            listOf(
                "someowner/somerepo/dev/patches-bundle.json" to
                    """{"version":"2.1.0-dev.3","download_url":"https://cdn.invalid/patches-dev.mpp"}""",
                "cdn.invalid/patches-dev.mpp" to "dev-bytes",
            ),
        )
        val appConfig = newAppConfig(tempDir)
        val patchesDir = File(tempDir, "patches").apply { mkdirs() }
        appConfig.addPatchToLibrary(
            AppConfig.PatchSchemas.Mobile, "yt-ads", "Block Ads", "yt-ads.mpp",
            sourceUrl = "https://github.com/someowner/somerepo",
            includePrereleases = true,
        )

        val scheduler = PatchSourceScheduler(appConfig, remote.client(), patchesDir, AppConfig.PatchSchemas.Mobile)
        assertTrue(scheduler.checkForUpdates())
        assertEquals("dev-bytes", File(patchesDir, "yt-ads.mpp").readText())
    }

    @Test
    fun `skips pre-releases from the releases API unless the entry opted in`(@TempDir tempDir: File) = runBlocking {
        val releases = """
            [{"id":101,"tag_name":"v1.1.0-dev.1","prerelease":true,"draft":false,
              "assets":[{"id":1,"name":"patches-1.1.0-dev.1.mpp","browser_download_url":"https://dl.invalid/dev.mpp","size":10}]},
             {"id":100,"tag_name":"v1.0.0","prerelease":false,"draft":false,
              "assets":[{"id":2,"name":"patches-1.0.0.mpp","browser_download_url":"https://dl.invalid/stable.mpp","size":10}]}]
        """.trimIndent()
        val remote = FakeRemote(
            listOf(
                "api.github.com/repos/someowner/somerepo/releases" to releases,
                "dl.invalid/stable.mpp" to "stable-bytes",
                "dl.invalid/dev.mpp" to "dev-bytes",
            ),
        )
        val appConfig = newAppConfig(tempDir)
        val patchesDir = File(tempDir, "patches").apply { mkdirs() }
        appConfig.addPatchToLibrary(
            AppConfig.PatchSchemas.Mobile, "yt-ads", "Block Ads", "yt-ads.mpp",
            sourceUrl = "https://github.com/someowner/somerepo",
        )

        val scheduler = PatchSourceScheduler(appConfig, remote.client(), patchesDir, AppConfig.PatchSchemas.Mobile)
        assertTrue(scheduler.checkForUpdates())
        assertEquals("stable-bytes", File(patchesDir, "yt-ads.mpp").readText())
    }

    @Test
    fun `does not re-download a release that was already imported`(@TempDir tempDir: File) = runBlocking {
        val remote = FakeRemote(
            listOf(
                "api.github.com/repos/someowner/somerepo/releases" to githubReleasesJson("v1.0.0", "patches-1.0.0.mpp"),
                "releases/download/v1.0.0/patches-1.0.0.mpp" to "mpp-bytes",
            ),
        )
        val appConfig = newAppConfig(tempDir)
        val patchesDir = File(tempDir, "patches").apply { mkdirs() }
        appConfig.addPatchToLibrary(
            AppConfig.PatchSchemas.Mobile, "yt-ads", "Block Ads", "yt-ads.mpp",
            sourceUrl = "https://github.com/someowner/somerepo",
        )

        val client = remote.client()
        val scheduler = PatchSourceScheduler(appConfig, client, patchesDir, AppConfig.PatchSchemas.Mobile)
        assertTrue(scheduler.checkForUpdates())
        val downloadsAfterFirst = remote.requested.count { "patches-1.0.0.mpp" in it }

        assertFalse(scheduler.checkForUpdates(), "second check against the same latest release should be a no-op")
        assertEquals(downloadsAfterFirst, remote.requested.count { "patches-1.0.0.mpp" in it })
    }

    @Test
    fun `picks the mpp asset matching the release when the release carries older ones too`(@TempDir tempDir: File) = runBlocking {
        val remote = FakeRemote(
            listOf(
                "api.github.com/repos/someowner/somerepo/releases" to
                    githubReleasesJson("v1.0.0", "patches-0.9.0.mpp", "patches-1.0.0.mpp"),
                "releases/download/v1.0.0/patches-1.0.0.mpp" to "current-bytes",
                "releases/download/v1.0.0/patches-0.9.0.mpp" to "old-bytes",
            ),
        )
        val appConfig = newAppConfig(tempDir)
        val patchesDir = File(tempDir, "patches").apply { mkdirs() }
        appConfig.addPatchToLibrary(
            AppConfig.PatchSchemas.Mobile, "yt-ads", "Block Ads", "yt-ads.mpp",
            sourceUrl = "https://github.com/someowner/somerepo",
        )

        val scheduler = PatchSourceScheduler(appConfig, remote.client(), patchesDir, AppConfig.PatchSchemas.Mobile)
        assertTrue(scheduler.checkForUpdates())
        assertEquals("current-bytes", File(patchesDir, "yt-ads.mpp").readText())
    }

    @Test
    fun `skips a release with no mpp assets`(@TempDir tempDir: File) = runBlocking {
        val remote = FakeRemote(
            listOf("api.github.com/repos/someowner/somerepo/releases" to githubReleasesJson("v1.0.0", "notes.txt")),
        )
        val appConfig = newAppConfig(tempDir)
        val patchesDir = File(tempDir, "patches").apply { mkdirs() }
        appConfig.addPatchToLibrary(
            AppConfig.PatchSchemas.Mobile, "yt-ads", "Block Ads", "yt-ads.mpp",
            sourceUrl = "https://github.com/someowner/somerepo",
        )

        val scheduler = PatchSourceScheduler(appConfig, remote.client(), patchesDir, AppConfig.PatchSchemas.Mobile)
        assertFalse(scheduler.checkForUpdates())
        assertFalse(File(patchesDir, "yt-ads.mpp").exists())
    }

    // ---- GitLab ------------------------------------------------------------

    @Test
    fun `imports a new release from a GitLab source`(@TempDir tempDir: File) = runBlocking {
        // GitLab's shape: assets live under assets.links[], the URL is
        // direct_asset_url, and there is no prerelease flag or asset size.
        val gitlabReleases = """
            [{"tag_name":"v3.0.0","name":"3.0.0","released_at":"2026-01-01T00:00:00Z","description":"notes",
              "assets":{"links":[
                {"name":"patches-3.0.0.mpp","direct_asset_url":"https://gitlab.com/o/r/-/releases/v3.0.0/downloads/patches-3.0.0.mpp"}
              ]}}]
        """.trimIndent()
        val remote = FakeRemote(
            listOf(
                "gitlab.com/api/v4/projects/" to gitlabReleases,
                "downloads/patches-3.0.0.mpp" to "gitlab-bytes",
            ),
        )
        val appConfig = newAppConfig(tempDir)
        val patchesDir = File(tempDir, "patches").apply { mkdirs() }
        appConfig.addPatchToLibrary(
            AppConfig.PatchSchemas.Mobile, "yt-ads", "Block Ads", "yt-ads.mpp",
            sourceUrl = "https://gitlab.com/someowner/somerepo",
        )

        val scheduler = PatchSourceScheduler(appConfig, remote.client(), patchesDir, AppConfig.PatchSchemas.Mobile)
        assertTrue(scheduler.checkForUpdates())

        assertEquals("gitlab-bytes", File(patchesDir, "yt-ads.mpp").readText())
        val entry = appConfig.listPatchLibrary(AppConfig.PatchSchemas.Mobile).single()
        assertEquals("3.0.0", entry.version)
        assertEquals("gitlab", entry.sourceProvider)
        assertTrue(
            remote.requested.any { "gitlab.com/api/v4/projects/someowner%2Fsomerepo/releases" in it },
            "expected GitLab's URL-encoded project path, got ${remote.requested}",
        )
        assertFalse(
            remote.requested.any { "api.github.com" in it },
            "a GitLab source must never call GitHub, got ${remote.requested}",
        )
    }

    // ---- entry selection ---------------------------------------------------

    @Test
    fun `checkEntryNow checks and imports just the requested entry`(@TempDir tempDir: File) = runBlocking {
        val remote = FakeRemote(
            listOf(
                "api.github.com/repos/someowner/somerepo/releases" to githubReleasesJson("v1.0.0", "patches-1.0.0.mpp"),
                "releases/download/v1.0.0/patches-1.0.0.mpp" to "mpp-bytes",
            ),
        )
        val appConfig = newAppConfig(tempDir)
        val patchesDir = File(tempDir, "patches").apply { mkdirs() }
        appConfig.addPatchToLibrary(
            AppConfig.PatchSchemas.Mobile, "yt-ads", "Block Ads", "yt-ads.mpp",
            sourceUrl = "https://github.com/someowner/somerepo",
        )
        // No source_url: manually uploaded, never auto-updated.
        appConfig.addPatchToLibrary(AppConfig.PatchSchemas.Mobile, "manual-patch", "Manual", "manual-patch.mpp")

        val scheduler = PatchSourceScheduler(appConfig, remote.client(), patchesDir, AppConfig.PatchSchemas.Mobile)

        assertFalse(scheduler.checkEntryNow("manual-patch"), "entries without a source_url are never auto-updated")
        assertTrue(scheduler.checkEntryNow("yt-ads"))
        assertFalse(File(patchesDir, "manual-patch.mpp").exists())
        assertEquals("mpp-bytes", File(patchesDir, "yt-ads.mpp").readText())
    }

    @Test
    fun `a legacy GitHub-only entry keeps working after its source_url is backfilled`(@TempDir tempDir: File) = runBlocking {
        // Pre-GitLab databases stored a bare "owner/repo" in github_repo;
        // AppDatabase backfills source_url from it on startup. Going through
        // the payload's legacy field is the same path an old client takes.
        val appConfig = newAppConfig(tempDir)
        appConfig.addPatchToLibrary(
            AppConfig.PatchSchemas.Mobile, "yt-ads", "Block Ads", "yt-ads.mpp",
            sourceUrl = AppConfig.PatchLibraryPayload(githubRepo = "someowner/somerepo").resolvedSourceUrl!!,
        )

        val entry = appConfig.listPatchLibrary(AppConfig.PatchSchemas.Mobile).single()
        assertEquals("https://github.com/someowner/somerepo", entry.sourceUrl)
        assertEquals("github", entry.sourceProvider)
    }
}
