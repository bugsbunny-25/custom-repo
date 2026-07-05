package app.fdroidserver.admin

import app.fdroidserver.apkmirror.ApkMirrorClient
import app.fdroidserver.config.AppConfig
import app.fdroidserver.config.AppDatabase
import app.fdroidserver.fdroidrepo.FdroidRepoManager
import app.fdroidserver.patching.BundleMerger
import app.fdroidserver.patching.PatchApplier
import app.fdroidserver.patching.PatchLibrary
import app.fdroidserver.patching.PatchScheduler
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * End-to-end test of the real Ktor routing + kotlinx.serialization wiring
 * (snake_case naming strategy, request/response DTOs) against a real
 * (temp-dir) SQLite database - this is what caught the field-name mismatches
 * between the independently-designed Kotlin DTOs and what the ported admin
 * UI JS actually sends/expects (documented in `AppConfig.kt`/
 * `PatchLibrary.kt`), so keep it exercising the exact same endpoints the JS
 * calls.
 */
class AdminServerTest {

    private fun server(tempDir: File): AdminServer {
        val appConfig = AppConfig(AppDatabase(File(tempDir, "db").apply { mkdirs() }))
        val patchesDir = File(tempDir, "patches").apply { mkdirs() }
        val repoDir = File(tempDir, "repo").apply { mkdirs() }
        val patchedRepoDir = File(tempDir, "patched-repo").apply { mkdirs() }
        val fdroidRepoManager = FdroidRepoManager()
        val patchScheduler = PatchScheduler(
            appConfig, ApkMirrorClient(), PatchLibrary(), BundleMerger(), PatchApplier(),
            patchesDir, patchedRepoDir, File(tempDir, "tmp").apply { mkdirs() }, fdroidRepoManager,
            PatchApplier.SigningConfig(keystoreFile = File(repoDir, "patched-keystore.jks")),
        )
        return AdminServer(appConfig, PatchLibrary(), patchesDir, fdroidRepoManager, repoDir, patchedRepoDir, patchScheduler)
    }

    @Test
    fun `admin page is served`(@TempDir tempDir: File) {
        testApplication {
            application { with(server(tempDir)) { configureServer() } }
            val response = client.get("/admin")
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("<title>Repo Config</title>"))
        }
    }

    @Test
    fun `repos list starts empty and reflects an added repo with snake_case fields`(@TempDir tempDir: File) {
        testApplication {
            application { with(server(tempDir)) { configureServer() } }

            val empty = client.get("/api/repos")
            assertEquals(HttpStatusCode.OK, empty.status)
            assertEquals("""{"repos":[]}""", empty.bodyAsText())

            val created = client.post("/api/repos") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"repo":"termux/termux-app","max_releases":5,"apk_pattern":".*\\.apk$",""" +
                        """"include_prereleases":true,"include_drafts":false,"enabled":true}""",
                )
            }
            assertEquals(HttpStatusCode.Created, created.status)

            val listed = client.get("/api/repos")
            val body = listed.bodyAsText()
            assertTrue(body.contains(""""repo":"termux/termux-app""""), "expected repo field in: $body")
            assertTrue(body.contains(""""include_prereleases":true"""), "expected snake_case key in: $body")
            assertTrue(body.contains(""""max_releases":5"""), "expected snake_case key in: $body")
        }
    }

    @Test
    fun `patch library upload, inspect field names, and delete round-trip`(@TempDir tempDir: File) {
        testApplication {
            application { with(server(tempDir)) { configureServer() } }

            val contentBase64 = java.util.Base64.getEncoder().encodeToString("fake-mpp-bytes".toByteArray())
            val created = client.post("/api/patch-library") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"id":"yt-ads","name":"Block Ads","version":"1.0",""" +
                        """"filename":"yt-ads.mpp","content_base64":"$contentBase64"}""",
                )
            }
            assertEquals(HttpStatusCode.Created, created.status)
            assertTrue(File(tempDir, "patches/yt-ads.mpp").exists())

            val listed = client.get("/api/patch-library")
            val listedBody = listed.bodyAsText()
            assertTrue(listedBody.contains(""""id":"yt-ads""""), "expected id in: $listedBody")
            assertTrue(listedBody.contains(""""file":"yt-ads.mpp""""), "expected file in: $listedBody")

            val deleted = client.delete("/api/patch-library/yt-ads")
            assertEquals(HttpStatusCode.OK, deleted.status)
            assertTrue(!File(tempDir, "patches/yt-ads.mpp").exists())
        }
    }

    @Test
    fun `patch target attach-configure-detach round-trip preserves unrelated fields`(@TempDir tempDir: File) {
        testApplication {
            application { with(server(tempDir)) { configureServer() } }

            val contentBase64 = java.util.Base64.getEncoder().encodeToString("fake-mpp-bytes".toByteArray())
            client.post("/api/patch-library") {
                contentType(ContentType.Application.Json)
                setBody("""{"id":"yt-ads","name":"Block Ads","filename":"yt-ads.mpp","content_base64":"$contentBase64"}""")
            }
            client.post("/api/patch-targets") {
                contentType(ContentType.Application.Json)
                setBody("""{"id":"youtube","name":"YouTube","apkmirror_url":"https://apkmirror.com/x"}""")
            }

            val attached = client.post("/api/patch-targets/youtube/attachments") {
                contentType(ContentType.Application.Json)
                setBody("""{"patch_id":"yt-ads","supported_versions":"19.16.39, 19.17.0","patch_args":"--foo"}""")
            }
            assertEquals(HttpStatusCode.Created, attached.status)
            val attachedBody = attached.bodyAsText()
            assertTrue(attachedBody.contains(""""supported_versions":["19.16.39","19.17.0"]"""), "expected parsed csv->array in: $attachedBody")

            // "Configure" save omits supported_versions/patch_args entirely -
            // they must survive unchanged (this exact bug existed during
            // development: a naive re-implementation clobbered them).
            val configured = client.post("/api/patch-targets/youtube/attachments") {
                contentType(ContentType.Application.Json)
                setBody("""{"patch_id":"yt-ads","patch_selection":{"custom-branding":false},"option_overrides":{"custom-branding":{"appName":"MyTube"}}}""")
            }
            assertEquals(HttpStatusCode.Created, configured.status)

            val targets = client.get("/api/patch-targets").bodyAsText()
            assertTrue(targets.contains(""""supported_versions":["19.16.39","19.17.0"]"""), "supported_versions should survive: $targets")
            assertTrue(targets.contains(""""patch_args":"--foo""""), "patch_args should survive: $targets")
            assertTrue(targets.contains(""""custom-branding":false"""), "patch_selection should be saved: $targets")

            val detached = client.delete("/api/patch-targets/youtube/attachments/yt-ads")
            assertEquals(HttpStatusCode.OK, detached.status)
        }
    }
}
