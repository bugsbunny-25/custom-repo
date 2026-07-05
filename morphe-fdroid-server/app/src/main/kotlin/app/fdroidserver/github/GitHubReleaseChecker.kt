package app.fdroidserver.github

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpStatement
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Polls configured GitHub repos for new releases, downloads matching APK
 * assets, and reports what changed - the direct Kotlin port of the old
 * Python `update_checker.py`. Behavior (which releases count, the
 * `max_versions_per_app` cleanup rule, the cache shape) is carried over
 * unchanged; only the HTTP/JSON plumbing is new (Ktor client +
 * kotlinx.serialization instead of `requests` + raw dicts).
 */
class GitHubReleaseChecker(
    private val httpClient: HttpClient,
    private val githubToken: String?,
    private val logger: Logger = LoggerFactory.getLogger(GitHubReleaseChecker::class.java.name),
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class RepoConfig(
        val repo: String,
        val includePrereleases: Boolean = true,
        val includeDrafts: Boolean = false,
        val maxReleases: Int = 5,
        val apkPattern: String = """.*\.apk$""",
    )

    @Serializable
    data class GitHubAsset(val id: Long, val name: String)

    @Serializable
    data class GitHubRelease(
        val id: Long,
        @SerialName("tag_name") val tagName: String,
        val prerelease: Boolean = false,
        val draft: Boolean = false,
        val assets: List<GitHubAsset> = emptyList(),
    )

    data class ProcessedRelease(
        val releaseId: Long,
        val tag: String,
        val type: String,
        val processedAt: String,
        val apksFound: Int,
        val apksDownloaded: Int,
    )

    private fun authHeaders(): Map<String, String> = buildMap {
        put("Accept", "application/vnd.github.v3+json")
        if (!githubToken.isNullOrBlank()) put("Authorization", "Bearer $githubToken")
    }

    suspend fun getReleases(repoConfig: RepoConfig): List<GitHubRelease> {
        val perPage = if (repoConfig.maxReleases <= 0) 100 else repoConfig.maxReleases.coerceAtMost(100)
        val url = "https://api.github.com/repos/${repoConfig.repo}/releases?per_page=$perPage"

        val response = httpClient.get(url) {
            authHeaders().forEach { (k, v) -> header(k, v) }
        }
        if (response.status == HttpStatusCode.NotFound) {
            logger.warn("No releases found for ${repoConfig.repo}")
            return emptyList()
        }
        if (!response.status.isSuccess()) {
            logger.warn("Error fetching releases for ${repoConfig.repo}: HTTP ${response.status}")
            return emptyList()
        }

        val releases = json.decodeFromString<List<GitHubRelease>>(response.bodyAsText())
        val filtered = releases.filter { release ->
            (!release.draft || repoConfig.includeDrafts) && (!release.prerelease || repoConfig.includePrereleases)
        }.let { if (repoConfig.maxReleases > 0) it.take(repoConfig.maxReleases) else it }

        logger.info(
            "${repoConfig.repo}: found ${filtered.size} release(s) to process " +
                "(prereleases=${repoConfig.includePrereleases}, drafts=${repoConfig.includeDrafts})"
        )
        return filtered
    }

    fun matchingAssets(release: GitHubRelease, repoConfig: RepoConfig): List<GitHubAsset> {
        val pattern = runCatching { Regex(repoConfig.apkPattern) }.getOrElse {
            logger.warn("${repoConfig.repo}: invalid apk_pattern '${repoConfig.apkPattern}', falling back to .*\\.apk$")
            Regex(""".*\.apk$""")
        }
        return release.assets.filter { pattern.containsMatchIn(it.name) }
    }

    suspend fun downloadAsset(repo: String, asset: GitHubAsset, destination: File): Boolean {
        return try {
            logger.info("Downloading ${asset.name}...")
            val url = "https://api.github.com/repos/$repo/releases/assets/${asset.id}"
            val statement: HttpStatement = httpClient.prepareGet(url) {
                header("Accept", "application/octet-stream")
                authHeaders()["Authorization"]?.let { header("Authorization", it) }
            }
            statement.execute { response ->
                if (!response.status.isSuccess()) {
                    logger.warn("Failed to download ${asset.name}: HTTP ${response.status}")
                    return@execute false
                }
                destination.parentFile?.mkdirs()
                response.bodyAsChannel().toInputStream().use { input ->
                    destination.outputStream().use { out -> input.copyTo(out) }
                }
                true
            }
        } catch (e: Exception) {
            logger.error("Error downloading ${asset.name}: $e")
            destination.delete()
            false
        }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun releaseType(release: GitHubRelease): String = when {
        release.draft -> "draft"
        release.prerelease -> "pre-release"
        else -> "stable"
    }

    fun nowIso(): String = Instant.now().toString()
}
