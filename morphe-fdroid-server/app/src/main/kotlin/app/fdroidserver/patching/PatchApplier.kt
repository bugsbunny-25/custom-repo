package app.fdroidserver.patching

import app.morphe.engine.PatchEngine
import app.morphe.engine.patches.PatchBundleLoader
import app.morphe.patcher.apk.ApkUtils
import app.morphe.patcher.patch.Patch
import kotlinx.coroutines.runBlocking
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Thin adapter between this server's config shapes and
 * [app.morphe.engine.PatchEngine] - the patching pipeline vendored from
 * morphe-desktop (see `app/morphe/engine/README.md`). Everything that
 * actually touches the APK (patch filtering, applying, rebuilding, signing)
 * happens in the engine, so this server patches exactly the way the official
 * desktop app does; this class only translates our
 * `patch_selection`/`option_overrides` maps into a [PatchEngine.Config] and
 * the engine's [PatchEngine.Result] back into an [ApplyResult].
 *
 * Replaces the hand-rolled `Patcher`/`PatcherConfig`/`ApkUtils` pipeline this
 * file used to carry. Behaviour inherited from the engine that the old
 * pipeline didn't have:
 *  - version-scoped compatibility filtering (a patch that declares support
 *    for specific app versions is skipped for other versions - see
 *    [forceCompatibility] for the escape hatch)
 *  - split-bundle (`.apkm`/`.xapk`/`.apks`) merging as step 0 (this server
 *    normally pre-merges in [PatchWorkerEntryPoint], so it's a no-op here)
 *  - options set through morphe-patcher's own `setOptions`, which reports
 *    unknown keys and bad values instead of throwing
 *  - the legacy-keystore-alias signing fallback
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
        /** [appliedPatches] is what actually landed in the output APK - with
         * strict failure handling (see [apply]) this is every selected patch. */
        data class Success(
            val packageName: String,
            val versionName: String,
            val appliedPatches: List<String> = emptyList(),
        ) : ApplyResult()

        data class Failure(val packageName: String?, val error: Throwable) : ApplyResult()

        /** The APK was not patched because devices served by this repo can't
         * run it (see [NativeAbiCheck]); [reason] is shown in the admin UI. */
        data class Unsupported(val packageName: String?, val reason: String) : ApplyResult()
    }

    /** Loads every patch out of [patchFile] through the engine's
     * [PatchBundleLoader], which isolates per-bundle load failures and keeps
     * the file each patch came from. */
    fun loadPatches(patchFile: File): Set<Patch<*>> = PatchBundleLoader.loadFlat(setOf(patchFile))

    /**
     * Applies the enabled subset of [patches] to [inputApk], producing a
     * signed APK at [outputApk]. [workDir] is the engine's scratch space and
     * should be unique per invocation (the caller cleans it up).
     *
     * [selection] is our `patch_selection` map (patch name -> on/off); names
     * absent from it keep the patch's own `default` flag. [optionOverrides]
     * is `option_overrides` (patch name -> option key -> value as a string
     * from the admin UI), converted to the options' real types by
     * [PatchSelector.convertOptions].
     *
     * [forceCompatibility] bypasses the engine's version check for
     * [packageName] - set it when an operator pinned this exact app version
     * by hand (they asked for that version specifically, even if the `.mpp`
     * doesn't list it); leave it off when the version list was derived from
     * the `.mpp` itself, where the check is a no-op anyway.
     *
     * Failure handling is strict: if any selected patch throws, no APK is
     * written and this returns [ApplyResult.Failure]. A partially patched
     * build published to an F-Droid repo unattended would be worse than no
     * build at all, so the engine's lenient `failOnError = false` mode is
     * deliberately not used here.
     */
    fun apply(
        inputApk: File,
        patches: Set<Patch<*>>,
        selection: Map<String, Boolean>,
        optionOverrides: Map<String, Map<String, String>>,
        packageName: String,
        outputApk: File,
        workDir: File,
        signing: SigningConfig,
        forceCompatibility: Boolean = false,
    ): ApplyResult {
        workDir.mkdirs()

        val (enabled, disabled) = PatchSelector.splitSelection(selection)
        val config = PatchEngine.Config(
            inputApk = inputApk,
            patches = patches,
            outputApk = outputApk,
            enabledPatches = enabled,
            disabledPatches = disabled,
            forceCompatibility = forceCompatibility,
            patchOptions = PatchSelector.convertOptions(patches, optionOverrides),
            keystoreDetails = ApkUtils.KeyStoreDetails(
                signing.keystoreFile,
                signing.keystorePassword,
                signing.keyAlias,
                signing.keyPassword,
            ),
            signerName = signing.signerName,
            tempDir = workDir,
        )

        val result = try {
            runBlocking { PatchEngine.patch(config) { message -> logger.info(message) } }
        } catch (e: Exception) {
            // The engine only throws for init errors (e.g. the APK can't be
            // opened at all); every pipeline step failure comes back as a
            // Result with success = false.
            return ApplyResult.Failure(null, e)
        }

        if (!result.success) {
            return ApplyResult.Failure(result.packageName, RuntimeException(failureSummary(result)))
        }
        if (packageName.isNotBlank() && result.packageName != packageName) {
            // Caught here as well as in PatchScheduler so a mismatch can never
            // reach the repo directory, whichever entry point ran the job.
            return ApplyResult.Failure(
                result.packageName,
                RuntimeException("patched APK is ${result.packageName}, expected $packageName"),
            )
        }
        return ApplyResult.Success(result.packageName, result.packageVersionName, result.appliedPatches)
    }

    /** Flattens the engine's per-step / per-patch failure detail into one
     * message, since [ApplyResult.Failure] carries a single throwable that
     * ends up in the logs and the admin UI. */
    private fun failureSummary(result: PatchEngine.Result): String {
        val failedStep = result.stepResults.firstOrNull { !it.success }
        val stepPart = failedStep?.let { "${it.step.name.lowercase()} failed: ${it.error ?: "unknown error"}" }
            ?: "patching failed"
        if (result.failedPatches.isEmpty()) return stepPart
        // Patch errors are full stack traces (the engine records them that
        // way); only the first line of each is useful in a one-line summary.
        val patchPart = result.failedPatches.joinToString("; ") { failed ->
            "${failed.name}: ${failed.error.lineSequence().firstOrNull()?.trim().orEmpty()}"
        }
        return "$stepPart (failed patches: $patchPart)"
    }
}
