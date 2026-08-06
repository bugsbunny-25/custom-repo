package app.fdroidserver.patching

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
 * [PatchApplier.apply] needs, minus the already-loaded `Set<Patch<*>>` (not
 * JSON-serializable), which the worker rebuilds itself from [patchFile] +
 * [patchSelection] + [optionOverrides] via [PatchSelector.applyOverrides].
 */
@Serializable
private data class PatchWorkerRequest(
    val inputApk: String,
    val patchFile: String,
    val patchSelection: Map<String, Boolean>,
    val optionOverrides: Map<String, Map<String, String>>,
    val packageName: String,
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
    val error: String? = null,
)

/**
 * Runs the APK patch/sign pipeline ([PatchApplier]) in a short-lived child
 * JVM instead of the long-running admin-server process, so the admin
 * server's own heap (capped at `-Xmx512m` in supervisord.conf to keep idle
 * container RAM small) doesn't have to be sized for the patch pipeline's
 * memory-spiky decompile/rewrite/re-sign work. The child is spawned with no
 * `-Xmx`/GC/allocator flags at all - it gets the JVM's normal default sizing
 * (a fraction of whatever memory it sees, host or cgroup) rather than
 * inheriting the admin server's deliberately tight limits.
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
        inputApk: File,
        patchFile: File,
        patchSelection: Map<String, Boolean>,
        optionOverrides: Map<String, Map<String, String>>,
        packageName: String,
        outputApk: File,
        workDir: File,
        signing: PatchApplier.SigningConfig,
    ): PatchApplier.ApplyResult {
        workDir.mkdirs()
        val requestFile = createRestrictedTempFile("patch-worker-request", ".json")
        val responseFile = createRestrictedTempFile("patch-worker-response", ".json")

        return try {
            requestFile.writeText(
                json.encodeToString(
                    PatchWorkerRequest(
                        inputApk = inputApk.absolutePath,
                        patchFile = patchFile.absolutePath,
                        patchSelection = patchSelection,
                        optionOverrides = optionOverrides,
                        packageName = packageName,
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
                PatchApplier.ApplyResult.Success(response.packageName ?: "", response.versionName ?: "")
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
 * `--patch-worker <requestFile> <responseFile>`. Runs one patch/sign job and
 * exits; never touches the admin server, database, or scheduler loops.
 */
object PatchWorkerEntryPoint {
    private val logger = LoggerFactory.getLogger(PatchWorkerEntryPoint::class.java.name)

    fun run(requestPath: String, responsePath: String): Int {
        val responseFile = File(responsePath)
        val request = try {
            json.decodeFromString<PatchWorkerRequest>(File(requestPath).readText())
        } catch (e: Exception) {
            logger.error("Failed to read patch worker request: $e")
            return 1
        }

        val response = try {
            val applier = PatchApplier()
            val loadedPatches = applier.loadPatches(File(request.patchFile))
            val patchesToApply = PatchSelector.applyOverrides(
                loadedPatches,
                request.patchSelection,
                request.optionOverrides,
                request.packageName,
            )
            val signing = PatchApplier.SigningConfig(
                keystoreFile = File(request.keystoreFile),
                keystorePassword = request.keystorePassword,
                keyAlias = request.keyAlias,
                keyPassword = request.keyPassword,
                signerName = request.signerName,
            )
            when (
                val result = applier.apply(
                    File(request.inputApk),
                    patchesToApply,
                    File(request.outputApk),
                    File(request.workDir),
                    signing,
                )
            ) {
                is PatchApplier.ApplyResult.Success -> PatchWorkerResponse(true, result.packageName, result.versionName)
                is PatchApplier.ApplyResult.Failure -> PatchWorkerResponse(false, result.packageName, error = result.error.toString())
            }
        } catch (e: Exception) {
            logger.error("Patch worker job failed: $e")
            PatchWorkerResponse(false, error = e.toString())
        }

        responseFile.writeText(json.encodeToString(response))
        return if (response.success) 0 else 1
    }
}
