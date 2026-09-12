package app.fdroidserver.patching

import app.fdroidserver.LoggingBridge
import app.fdroidserver.apkmirror.ApkMirrorClient
import app.fdroidserver.apkpure.ApkPureClient
import app.fdroidserver.scraper.ScraperClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

private val json = Json { ignoreUnknownKeys = true }

/**
 * On-disk request handed to the `--patch-worker` child process - everything
 * needed to go from a version page URL all the way to a signed, patched APK:
 * resolving + downloading the APK, merging it if it's a split bundle, and
 * running [PatchApplier]. [patchSelection]/[optionOverrides] stand in for an
 * already-loaded `Set<Patch<*>>` (not JSON-serializable) - the worker loads
 * the bundle itself from [patchFile] and hands both maps to [PatchApplier],
 * which turns them into the vendored engine's enabled/disabled name sets and
 * typed option map (see [PatchSelector]).
 */
@Serializable
private data class PatchWorkerRequest(
    /** "APKMIRROR" or "APKPURE" - which client to resolve/download through. */
    val source: String,
    val versionPageUrl: String,
    val flareSolverrUrl: String?,
    /** Stable per-(target, version) path outside [workDir] - if it already
     * exists (a previous job for the same version left it there), download
     * and merge are skipped and this file is patched directly. Callers
     * with multiple patches for the same version pass the same path so the
     * (possibly large) download/merge only happens once. */
    val preparedApkPath: String,
    val patchFile: String,
    val patchSelection: Map<String, Boolean>,
    val optionOverrides: Map<String, Map<String, String>>,
    val packageName: String,
    /** Bypasses the engine's app-version compatibility check - see
     * [PatchApplier.apply]. Defaults to false so a request written by an
     * older build still decodes. */
    val forceCompatibility: Boolean = false,
    val outputApk: String,
    val workDir: String,
    val keystoreFile: String,
    val keystorePassword: String?,
    val keyAlias: String,
    val keyPassword: String,
    val signerName: String,
)

@Serializable
private data class PatchWorkerResponse(
    val success: Boolean,
    val packageName: String? = null,
    val versionName: String? = null,
    /** Names of the patches the engine actually applied - logged by the
     * scheduler so a run's contents are visible without the worker's own
     * stdout. */
    val appliedPatches: List<String> = emptyList(),
    val error: String? = null,
)

/**
 * Runs the *entire* per-version patch job - resolving the download URL,
 * downloading the APK, merging it if it's a split bundle ([BundleMerger]),
 * and patching/signing it ([PatchApplier]) - in a short-lived child JVM
 * instead of the long-running admin-server process. None of the memory-heavy
 * APK work (resource-table parsing during bundle merge, decompile/rewrite/
 * re-sign during patching) should run there, so [PatchScheduler] only ever
 * does version-matching/DB/orchestration work and hands this class a version
 * page URL + patch config - never a downloaded file. Whatever a patch run
 * peaks at is then given back to the OS when the child exits, rather than
 * staying resident in the admin server's heap for the container's lifetime.
 *
 * Both JVMs run with no resource flags (see supervisord.conf) and size
 * themselves against whatever memory they see - the container's cgroup limit
 * if one is set, the host's RAM otherwise.
 *
 * Request/response cross the process boundary as JSON files under the JVM's
 * temp directory (not the persisted `/srv/fdroid` volume) - simpler than
 * piping stdout and avoids putting the keystore password in a command-line
 * argument (visible in `ps`/`/proc`).
 */
class PatchWorkerLauncher(private val logger: Logger = LoggerFactory.getLogger(PatchWorkerLauncher::class.java.name)) {

    private val javaBin = File(File(System.getProperty("java.home"), "bin"), "java").absolutePath
    private val jarPath = File(PatchWorkerLauncher::class.java.protectionDomain.codeSource.location.toURI()).absolutePath

    fun apply(
        source: String,
        versionPageUrl: String,
        flareSolverrUrl: String?,
        preparedApkPath: File,
        patchFile: File,
        patchSelection: Map<String, Boolean>,
        optionOverrides: Map<String, Map<String, String>>,
        packageName: String,
        outputApk: File,
        workDir: File,
        signing: PatchApplier.SigningConfig,
        forceCompatibility: Boolean = false,
    ): PatchApplier.ApplyResult {
        workDir.mkdirs()
        val requestFile = createRestrictedTempFile("patch-worker-request", ".json")
        val responseFile = createRestrictedTempFile("patch-worker-response", ".json")

        return try {
            requestFile.writeText(
                json.encodeToString(
                    PatchWorkerRequest(
                        source = source,
                        versionPageUrl = versionPageUrl,
                        flareSolverrUrl = flareSolverrUrl,
                        preparedApkPath = preparedApkPath.absolutePath,
                        patchFile = patchFile.absolutePath,
                        patchSelection = patchSelection,
                        optionOverrides = optionOverrides,
                        packageName = packageName,
                        forceCompatibility = forceCompatibility,
                        outputApk = outputApk.absolutePath,
                        workDir = workDir.absolutePath,
                        keystoreFile = signing.keystoreFile.absolutePath,
                        keystorePassword = signing.keystorePassword,
                        keyAlias = signing.keyAlias,
                        keyPassword = signing.keyPassword,
                        signerName = signing.signerName,
                    ),
                ),
            )

            val process = ProcessBuilder(javaBin, "-jar", jarPath, PATCH_WORKER_FLAG, requestFile.absolutePath, responseFile.absolutePath)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .start()
            val exited = process.waitFor(20, java.util.concurrent.TimeUnit.MINUTES)
            if (!exited) {
                process.destroyForcibly()
                return PatchApplier.ApplyResult.Failure(null, RuntimeException("patch worker timed out after 20 minutes"))
            }

            if (!responseFile.exists()) {
                return PatchApplier.ApplyResult.Failure(
                    null,
                    RuntimeException("patch worker exited with code ${process.exitValue()} and wrote no response"),
                )
            }
            val response = json.decodeFromString<PatchWorkerResponse>(responseFile.readText())
            if (response.success) {
                PatchApplier.ApplyResult.Success(
                    response.packageName ?: "",
                    response.versionName ?: "",
                    response.appliedPatches,
                )
            } else {
                PatchApplier.ApplyResult.Failure(response.packageName, RuntimeException(response.error ?: "unknown patch worker failure"))
            }
        } catch (e: Exception) {
            logger.error("Failed to run patch worker: $e")
            PatchApplier.ApplyResult.Failure(null, e)
        } finally {
            requestFile.delete()
            responseFile.delete()
        }
    }

    private fun createRestrictedTempFile(prefix: String, suffix: String): File {
        val permissions = PosixFilePermissions.asFileAttribute(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        return Files.createTempFile(prefix, suffix, permissions).toFile()
    }

    companion object {
        const val PATCH_WORKER_FLAG = "--patch-worker"
    }
}

/**
 * Entry point for the child process spawned by [PatchWorkerLauncher] -
 * dispatched to from `Main.kt`'s `main(args)` when invoked as
 * `--patch-worker <requestFile> <responseFile>`. Runs one version's whole
 * download-through-sign job and exits; never touches the admin server,
 * database, or scheduler loops.
 */
object PatchWorkerEntryPoint {
    private val logger = LoggerFactory.getLogger(PatchWorkerEntryPoint::class.java.name)

    fun run(requestPath: String, responsePath: String): Int {
        // Own JVM, so it has to install the bridge itself - this is where the
        // engine and morphe-patcher actually run, and their JUL output is the
        // only per-patch detail there is when a run fails.
        LoggingBridge.install()
        val responseFile = File(responsePath)
        val request = try {
            json.decodeFromString<PatchWorkerRequest>(File(requestPath).readText())
        } catch (e: Exception) {
            logger.error("Failed to read patch worker request: $e")
            return 1
        }

        val response = try {
            runJob(request)
        } catch (e: Exception) {
            logger.error("Patch worker job failed: $e")
            PatchWorkerResponse(false, error = e.toString())
        }

        responseFile.writeText(json.encodeToString(response))
        return if (response.success) 0 else 1
    }

    private fun runJob(request: PatchWorkerRequest): PatchWorkerResponse {
        val workDir = File(request.workDir).apply { mkdirs() }
        val preparedApk = File(request.preparedApkPath)

        if (!preparedApk.exists()) {
            val client: ScraperClient = if (request.source == "APKPURE") {
                ApkPureClient(flareSolverrUrl = request.flareSolverrUrl)
            } else {
                ApkMirrorClient(flareSolverrUrl = request.flareSolverrUrl)
            }

            val downloadInfo = client.resolveDownloadUrl(request.versionPageUrl)
                ?: return PatchWorkerResponse(false, error = "failed to resolve download URL for ${request.versionPageUrl}")

            val rawFile = File(workDir, "download.bin")
            if (!client.downloadApk(downloadInfo.url, rawFile, downloadInfo.referer)) {
                return PatchWorkerResponse(false, error = "failed to download ${request.versionPageUrl}")
            }

            // Bundle merging (.apkm/.xapk splits -> one APK) loads every
            // split's resource table into memory via reandroid and is just as
            // memory-spiky as patching itself - this is exactly why it (like
            // the download above) runs here, in the unbounded-heap worker,
            // rather than in PatchScheduler's admin-server process.
            val bundleMerger = BundleMerger()
            if (bundleMerger.isBundle(rawFile)) {
                bundleMerger.merge(rawFile, preparedApk)
                rawFile.delete()
            } else {
                rawFile.renameTo(preparedApk)
            }
        }

        val applier = PatchApplier()
        // Patch filtering (defaults, package + app-version compatibility) and
        // option typing now happen inside PatchApplier/the engine, so the whole
        // loaded bundle is handed over as-is rather than pre-filtered here.
        val loadedPatches = applier.loadPatches(File(request.patchFile))
        val signing = PatchApplier.SigningConfig(
            keystoreFile = File(request.keystoreFile),
            keystorePassword = request.keystorePassword,
            keyAlias = request.keyAlias,
            keyPassword = request.keyPassword,
            signerName = request.signerName,
        )
        return when (
            val result = applier.apply(
                inputApk = preparedApk,
                patches = loadedPatches,
                selection = request.patchSelection,
                optionOverrides = request.optionOverrides,
                packageName = request.packageName,
                outputApk = File(request.outputApk),
                workDir = workDir,
                signing = signing,
                forceCompatibility = request.forceCompatibility,
            )
        ) {
            is PatchApplier.ApplyResult.Success ->
                PatchWorkerResponse(true, result.packageName, result.versionName, result.appliedPatches)
            is PatchApplier.ApplyResult.Failure -> PatchWorkerResponse(false, result.packageName, error = result.error.toString())
        }
    }
}
