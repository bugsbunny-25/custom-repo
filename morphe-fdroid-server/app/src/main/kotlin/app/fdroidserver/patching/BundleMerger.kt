package app.fdroidserver.patching

import app.morphe.engine.util.BundleFormats
import app.morphe.patcher.apk.ApkMerger
import app.morphe.patcher.logging.toMorpheLogger
import java.io.File
import java.util.logging.Logger as JavaLogger
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.zip.ZipFile

/**
 * Detects and merges APKMirror `.apkm`/`.xapk`/`.apks` bundles (base.apk plus
 * per-arch/density/language split APKs) into a single installable APK, by
 * calling morphe-patcher's own `ApkMerger` directly - the same class
 * `morphe-cli`'s `patch` command uses internally for this exact purpose
 * (confirmed by reading `PatchCommand.kt`: it calls
 * `ApkMerger(logger).merge(inputFile = apk, outputFile = mergedApk, cleanMetaInf = true)`
 * whenever the input file's extension is apkm/xapk/apks).
 *
 * This replaces the old Python `apk_bundle.py`, which manually extracted the
 * bundle and hand-picked base.apk + the arm64-v8a + English + highest-
 * density splits before shelling out to APKEditor's merge CLI. That manual
 * selection logic is gone entirely here - **confirmed by reading
 * `ApkMerger.kt`'s source that it has no split-selection parameters at all**;
 * it merges every module the bundle contains
 * (`bundle.loadApkDirectory(...)` + `bundle.mergeModules(...)`) into one
 * "fat" APK unconditionally. This is simpler and matches what the official
 * tooling does, at the cost of a possibly larger merged APK (every
 * language/density/architecture included, not just one selected variant).
 */
class BundleMerger(private val logger: Logger = LoggerFactory.getLogger(BundleMerger::class.java.name)) {

    /**
     * A plain APK's zip has `AndroidManifest.xml`/`classes.dex` at its root and
     * never a nested `.apk`; a bundle instead packs the split APKs as nested
     * `.apk` entries - `base.apk` for APKMirror's `.apkm`/`.apks`, or
     * `{package}.apk` + `config.*.apk` splits for APKPure's `.xapk` (which has
     * no `base.apk`). Check the extension first (cheap), then fall back to
     * inspecting zip entries for any nested `.apk`, since the download URLs
     * (APKMirror's `download.php`, APKPure's CDN links) don't always carry a
     * useful extension.
     *
     * The extension list itself comes from the vendored engine's
     * [BundleFormats] so there's one definition of "bundle format" shared with
     * [app.morphe.engine.PatchEngine] (which does the same check before its own
     * merge step); the zip sniffing below is ours, because the engine only ever
     * sees files that still have their original name.
     */
    fun isBundle(file: File): Boolean {
        if (BundleFormats.isBundle(file)) return true
        return runCatching {
            ZipFile(file).use { zip ->
                zip.entries().asSequence().any { entry ->
                    !entry.isDirectory && entry.name.substringAfterLast('/').endsWith(".apk", ignoreCase = true)
                }
            }
        }.getOrDefault(false)
    }

    fun merge(bundleFile: File, outputFile: File) {
        logger.info("Merging APK bundle: $bundleFile -> $outputFile")
        // morphe-patcher's ApkMerger requires java.util.logging.Logger, so we create one for this purpose
        val javaLogger = JavaLogger.getLogger(BundleMerger::class.java.name)
        ApkMerger(javaLogger.toMorpheLogger()).merge(
            inputFile = bundleFile,
            outputFile = outputFile,
            cleanMetaInf = true,
        )
    }
}
