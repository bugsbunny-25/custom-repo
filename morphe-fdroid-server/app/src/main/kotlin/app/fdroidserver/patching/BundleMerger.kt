package app.fdroidserver.patching

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
     * A plain APK's zip has `AndroidManifest.xml` at its root; an
     * `.apkm`/`.xapk`/`.apks` bundle instead has `base.apk` (and the real
     * manifest lives inside that). Check the extension first (cheap), and
     * fall back to inspecting zip entries for files with no/ambiguous
     * extension (APKMirror's `download.php` URLs don't always carry a
     * useful one).
     */
    fun isBundle(file: File): Boolean {
        if (file.extension.lowercase() in BUNDLE_EXTENSIONS) return true
        return runCatching {
            ZipFile(file).use { zip -> zip.getEntry("base.apk") != null }
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

    companion object {
        private val BUNDLE_EXTENSIONS = setOf("apkm", "xapk", "apks")
    }
}
