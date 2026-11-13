package app.fdroidserver

import app.fdroidserver.apkmirror.ApkMirrorClient
import app.fdroidserver.apkpure.ApkPureClient
import app.fdroidserver.builder.BuildRequestQueue
import app.fdroidserver.builder.BuildStatusStore
import app.fdroidserver.builder.Housekeeping
import app.fdroidserver.config.AppConfig
import app.fdroidserver.config.AppDatabase
import app.fdroidserver.fdroidrepo.FdroidRepoManager
import app.fdroidserver.github.GitHubReleaseChecker
import app.fdroidserver.github.GithubScheduler
import app.fdroidserver.patching.PatchApplier
import app.fdroidserver.patching.PatchLibrary
import app.fdroidserver.patching.PatchScheduler
import app.fdroidserver.patching.PatchSourceHttpClient
import app.fdroidserver.patching.PatchSourceScheduler
import app.fdroidserver.patching.PatchWorkerLauncher
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Everything both roles need, wired once.
 *
 * This used to live inline in [main]. It was pulled out when the process
 * split in two - an always-on `server` and a one-shot `builder` - because
 * both need the same schedulers, repo manager and database, and duplicating
 * that wiring is how the two roles would quietly drift apart.
 */
class AppComponents private constructor(
    val settings: Env,
    val appConfig: AppConfig,
    val fdroidRepoManager: FdroidRepoManager,
    val patchLibrary: PatchLibrary,
    val patchScheduler: PatchScheduler,
    val patchSchedulerTv: PatchScheduler,
    val githubScheduler: GithubScheduler,
    val patchSourceScheduler: PatchSourceScheduler,
    val patchSourceSchedulerTv: PatchSourceScheduler,
    val queue: BuildRequestQueue,
    val statusStore: BuildStatusStore,
    val housekeeping: Housekeeping,
    private val githubHttpClient: HttpClient,
    private val patchSourceHttpClient: HttpClient,
) : AutoCloseable {

    /** Resolved configuration, so nothing below reads getenv directly. */
    data class Env(
        val dbDir: File,
        val repoDir: File,
        val keysDir: File,
        val patchedRepoDir: File,
        val patchedTvRepoDir: File,
        val tmpDir: File,
        val patchesDir: File,
        val patchesTvDir: File,
        /** False puts the app in split mode: no scheduler loops in the
         * server, and UI triggers become request files. */
        val runSchedulers: Boolean,
        /** Newest versions per app to keep in `archive/`; 0 disables. */
        val archiveKeep: Int,
        val keepPreparedApks: Boolean,
    )

    override fun close() {
        runCatching { githubHttpClient.close() }
        runCatching { patchSourceHttpClient.close() }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(AppComponents::class.java.name)

        fun create(): AppComponents {
            val env = readEnv()

            env.tmpDir.mkdirs()
            env.patchesDir.mkdirs()
            env.patchesTvDir.mkdirs()
            env.keysDir.mkdirs()

            val database = AppDatabase(env.dbDir)
            val appConfig = AppConfig(database)
            val fdroidRepoManager = FdroidRepoManager()
            val patchLibrary = PatchLibrary()

            val apkMirrorClient = ApkMirrorClient()
            val apkPureClient = ApkPureClient()
            val patchWorkerLauncher = PatchWorkerLauncher()

            val signing = PatchApplier.SigningConfig(
                keystoreFile = resolveKeystore(env, "patched-keystore.jks"),
            )
            val signingTv = PatchApplier.SigningConfig(
                keystoreFile = resolveKeystore(env, "patched-tv-keystore.jks"),
            )

            val patchScheduler = PatchScheduler(
                appConfig, apkMirrorClient, apkPureClient, patchLibrary,
                patchWorkerLauncher, env.patchesDir, env.patchedRepoDir, env.tmpDir,
                fdroidRepoManager, signing,
                schema = AppConfig.PatchSchemas.Mobile,
            )
            val patchSchedulerTv = PatchScheduler(
                appConfig, apkMirrorClient, apkPureClient, patchLibrary,
                patchWorkerLauncher, env.patchesTvDir, env.patchedTvRepoDir, env.tmpDir,
                fdroidRepoManager, signingTv,
                schema = AppConfig.PatchSchemas.Tv,
            )

            // CIO's default 15s request timeout covers the whole response body
            // and silently cuts off large APK downloads, so give release
            // downloads room and rely on the socket timeout to catch a stalled
            // connection instead.
            val githubHttpClient = HttpClient(CIO) {
                install(HttpTimeout) {
                    requestTimeoutMillis = 60.minutes.inWholeMilliseconds
                    connectTimeoutMillis = 30.seconds.inWholeMilliseconds
                    socketTimeoutMillis = 2.minutes.inWholeMilliseconds
                }
            }

            // githubToken is snapshotted once here; a token changed later via
            // Settings takes effect on the next restart (or, in split mode,
            // on the next builder run - which is every run).
            val initialGithubToken = runBlocking { appConfig.getSettings() }.githubToken.ifBlank { null }
            val releaseChecker = GitHubReleaseChecker(githubHttpClient, initialGithubToken)
            val githubScheduler = GithubScheduler(appConfig, releaseChecker, env.repoDir, fdroidRepoManager)

            // Separate client for the patch sources: the vendored engine's
            // sources take a plain HttpClient and set their own headers, and
            // this one additionally attaches the GitHub token to GitHub hosts
            // only (see PatchSourceHttpClient) so a GitLab-hosted patch source
            // never receives it.
            val patchSourceHttpClient = PatchSourceHttpClient.create(initialGithubToken)
            val patchSourceScheduler =
                PatchSourceScheduler(appConfig, patchSourceHttpClient, env.patchesDir, AppConfig.PatchSchemas.Mobile)
            val patchSourceSchedulerTv =
                PatchSourceScheduler(appConfig, patchSourceHttpClient, env.patchesTvDir, AppConfig.PatchSchemas.Tv)

            val queue = BuildRequestQueue(env.repoDir)

            return AppComponents(
                settings = env,
                appConfig = appConfig,
                fdroidRepoManager = fdroidRepoManager,
                patchLibrary = patchLibrary,
                patchScheduler = patchScheduler,
                patchSchedulerTv = patchSchedulerTv,
                githubScheduler = githubScheduler,
                patchSourceScheduler = patchSourceScheduler,
                patchSourceSchedulerTv = patchSourceSchedulerTv,
                queue = queue,
                statusStore = BuildStatusStore(queue.statusDir),
                housekeeping = Housekeeping(env.repoDir),
                githubHttpClient = githubHttpClient,
                patchSourceHttpClient = patchSourceHttpClient,
            )
        }

        private fun readEnv(): Env {
            val repoDir = File(System.getenv("REPO_PATH") ?: "/srv/fdroid")
            return Env(
                dbDir = File(System.getenv("DB_PATH") ?: "/app/db"),
                repoDir = repoDir,
                keysDir = File(System.getenv("KEYS_PATH") ?: "/keys"),
                patchedRepoDir = File(repoDir, "patched"),
                patchedTvRepoDir = File(repoDir, "patched-tv"),
                tmpDir = File(repoDir, "tmp"),
                patchesDir = File(repoDir, "patches"),
                patchesTvDir = File(repoDir, "patches-tv"),
                // Defaults to the old all-in-one behaviour on purpose. The
                // image is pulled automatically by `podman auto-update`, so a
                // new image must not stop the repo publishing just because the
                // host has not had its builder timer wired up yet. Switching
                // this to false is an explicit, documented step - see
                // docs/integration-spec.md.
                runSchedulers = boolEnv("RUN_SCHEDULERS", default = true),
                archiveKeep = intEnv("ARCHIVE_KEEP", default = 3),
                keepPreparedApks = boolEnv("KEEP_PREPARED_APKS", default = false),
            )
        }

        /**
         * Keystores are looked up in `KEYS_PATH` first and only then in the
         * repo directory.
         *
         * That ordering is what makes the backup split possible without
         * touching a single byte of client-visible data: the keystores are
         * tiny and must be backed up, while the repo directory is tens of
         * gigabytes of rebuildable APKs that should not be. Falling back to
         * the repo directory keeps every existing deployment working with no
         * migration at all - if /keys is empty, nothing changes.
         */
        private fun resolveKeystore(env: Env, name: String): File {
            val inKeys = File(env.keysDir, name)
            if (inKeys.isFile) {
                logger.info("Using keystore $name from ${env.keysDir}")
                return inKeys
            }
            val inRepo = File(env.repoDir, name)
            if (inRepo.isFile) {
                logger.info("Using keystore $name from ${env.repoDir} (not yet moved to ${env.keysDir})")
            }
            // Returned even when absent: PatchApplier creates the keystore on
            // first use, and it should be created in the repo directory to
            // match existing deployments unless /keys is already in play.
            return inRepo
        }

        private fun boolEnv(name: String, default: Boolean): Boolean =
            when (System.getenv(name)?.trim()?.lowercase()) {
                null, "" -> default
                "1", "true", "yes", "on" -> true
                "0", "false", "no", "off" -> false
                else -> {
                    logger.warn("$name is not a boolean - using $default")
                    default
                }
            }

        private fun intEnv(name: String, default: Int): Int {
            val raw = System.getenv(name)?.trim()
            if (raw.isNullOrEmpty()) return default
            val parsed = raw.toIntOrNull()
            if (parsed == null) {
                logger.warn("$name is not an integer ('$raw') - using $default")
                return default
            }
            return parsed
        }
    }
}
