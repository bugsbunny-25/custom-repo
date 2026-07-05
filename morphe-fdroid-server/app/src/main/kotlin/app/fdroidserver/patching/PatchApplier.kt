package app.fdroidserver.patching

import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.apk.ApkUtils
import app.morphe.patcher.apk.ApkUtils.applyTo
import app.morphe.patcher.dex.BytecodeMode
import app.morphe.patcher.dex.NoOpDexVerifier
import app.morphe.patcher.patch.Patch
import app.morphe.patcher.patch.loadPatchesFromJar
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Applies loaded patches to an APK and produces a signed output APK, by
 * calling morphe-patcher's `Patcher`/`PatcherConfig`/`ApkUtils` directly -
 * the direct replacement for the old Python `patch_checker.py`'s
 * `morphe-cli patch --patches=... --out=... --include=/--exclude=/--options=...`
 * shell-out (whose include/exclude/option flags were never confirmed
 * against the real CLI, only guessed).
 *
 * Constructor signature for [PatcherConfig] and the signing details below
 * were confirmed directly against morphe-patcher `1.5.2-dev.2` (the exact
 * version this project depends on - see `gradle/libs.versions.toml`), not
 * guessed.
 */
class PatchApplier(private val logger: Logger = LoggerFactory.getLogger(PatchApplier::class.java.name)) {

    data class SigningConfig(
        val keystoreFile: File,
        val keystorePassword: String? = null,
        val keyAlias: String = "morphe-fdroid-server",
        val keyPassword: String = "morphe-fdroid-server",
        val signerName: String = "morphe-fdroid-server",
    )

    sealed class ApplyResult {
        data class Success(val packageName: String, val versionName: String) : ApplyResult()
        data class Failure(val packageName: String?, val error: Throwable) : ApplyResult()
    }

    /** Loads every patch out of [patchFile], ready to be filtered/configured
     * by [PatchSelector.applyOverrides] and passed to [apply]. */
    fun loadPatches(patchFile: File): Set<Patch<*>> = loadPatchesFromJar(setOf(patchFile))

    /**
     * Applies [patches] (already filtered/configured - see
     * [PatchSelector.applyOverrides]) to [inputApk], producing a signed APK
     * at [outputApk]. [workDir] is used for the patcher's own scratch space
     * and should be unique per invocation (caller's responsibility to clean
     * it up afterwards).
     */
    fun apply(
        inputApk: File,
        patches: Set<Patch<*>>,
        outputApk: File,
        workDir: File,
        signing: SigningConfig,
    ): ApplyResult {
        workDir.mkdirs()
        val config = PatcherConfig(
            inputApk,
            workDir,
            null,
            workDir.absolutePath,
            true,
            emptySet(),
            BytecodeMode.STRIP_FAST,
            NoOpDexVerifier,
        )

        return Patcher(config).use { patcher ->
            val packageName = patcher.context.packageMetadata.packageName
            val versionName = patcher.context.packageMetadata.versionName

            try {
                patcher += patches

                var failure: Throwable? = null
                runBlocking {
                    patcher().collect { result ->
                        val exception = result.exception
                        if (exception != null) {
                            logger.error("Patch \"${result.patch.name}\" failed: $exception")
                            if (failure == null) failure = exception
                        } else {
                            logger.info("Applied: ${result.patch.name}")
                        }
                    }
                }
                failure?.let { return@use ApplyResult.Failure(packageName, it) }

                val patcherResult = patcher.get()
                val workingCopy = inputApk.copyTo(workDir.resolve(inputApk.name), overwrite = true)
                patcherResult.applyTo(workingCopy)

                ApkUtils.signApk(
                    workingCopy,
                    outputApk,
                    signing.signerName,
                    ApkUtils.KeyStoreDetails(signing.keystoreFile, signing.keystorePassword, signing.keyAlias, signing.keyPassword),
                )

                ApplyResult.Success(packageName, versionName)
            } catch (e: Exception) {
                ApplyResult.Failure(packageName, e)
            }
        }
    }
}
