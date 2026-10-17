package app.fdroidserver.github

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpStatement
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipFile
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
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
    data class GitHubAsset(
        val id: Long,
        val name: String,
        /** Byte size GitHub reports for the asset; 0 when unknown. */
        val size: Long = 0,
    )

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

    /**
     * Downloads [asset] to [destination], retrying up to [maxAttempts] times.
     * The bytes go to a hidden `.part` file next to [destination] and are
     * only moved into place once [isComplete] passes, so an interrupted or
     * cut-off transfer never leaves a truncated APK where `fdroid update`
     * (and the next check) would pick it up.
     */
    suspend fun downloadAsset(
        repo: String,
        asset: GitHubAsset,
        destination: File,
        maxAttempts: Int = 3,
        retryDelayMillis: Long = 5_000,
    ): Boolean {
        for (attempt in 1..maxAttempts) {
            if (downloadOnce(repo, asset, destination, attempt, maxAttempts)) return true
            if (attempt < maxAttempts) delay(retryDelayMillis * attempt)
        }
        logger.error("Giving up on ${asset.name} after $maxAttempts attempt(s)")
        return false
    }

    private suspend fun downloadOnce(repo: String, asset: GitHubAsset, destination: File, attempt: Int, maxAttempts: Int): Boolean {
        destination.parentFile?.mkdirs()
        val partFile = File(destination.parentFile, ".${destination.name}.part")
        return try {
            logger.info("Downloading ${asset.name} (attempt $attempt/$maxAttempts)...")
            val url = "https://api.github.com/repos/$repo/releases/assets/${asset.id}"
            val statement: HttpStatement = httpClient.prepareGet(url) {
                header("Accept", "application/octet-stream")
                authHeaders()["Authorization"]?.let { header("Authorization", it) }
            }
            val ok = statement.execute { response ->
                if (!response.status.isSuccess()) {
                    logger.warn("Failed to download ${asset.name}: HTTP ${response.status}")
                    return@execute false
                }
                response.bodyAsChannel().toInputStream().use { input ->
                    partFile.outputStream().use { out -> input.copyTo(out) }
                }
                val contentLength = response.contentLength()
                if (contentLength != null && partFile.length() != contentLength) {
                    logger.warn("Download of ${asset.name} was cut off: got ${partFile.length()} of $contentLength bytes")
                    return@execute false
                }
                true
            }
            if (!ok) return false
            if (!isComplete(partFile, asset)) {
                logger.warn(
                    "Downloaded ${asset.name} is incomplete or not a valid APK " +
                        "(${partFile.length()} bytes, expected ${asset.size})"
                )
                return false
            }
            Files.move(partFile.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Error downloading ${asset.name}: $e")
            false
        } finally {
            partFile.delete()
        }
    }

    /** True if [file] holds the whole of [asset]: its size matches what
     * GitHub reports (when known) and it opens as a zip, which is what
     * `fdroid update` needs to read it as an APK. */
    fun isComplete(file: File, asset: GitHubAsset): Boolean {
        if (!file.isFile) return false
        if (asset.size > 0 && file.length() != asset.size) return false
        return isValidZip(file)
    }

    fun isValidZip(file: File): Boolean = runCatching { ZipFile(file).use { true } }.getOrDefault(false)

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
