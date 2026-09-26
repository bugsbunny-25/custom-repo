package app.fdroidserver.patching

import java.io.File
import java.util.zip.ZipFile

/**
 * Checks that an APK will run on an arm64-v8a device before it's patched.
 * Every device this repo serves is arm64, so a build that only carries
 * native code for other ABIs (armeabi-v7a, x86, ...) would publish fine and
 * then fail to install or crash on launch.
 *
 * Native code lives at `lib/<abi>/<name>.so` in an APK; split bundles are merged
 * by [BundleMerger] first, which folds every split's `lib/` into the one APK,
 * so this only ever needs to look at a single file. An APK with no `.so`
 * files at all is pure Java/Kotlin and runs on any ABI.
 */
object NativeAbiCheck {
    const val REQUIRED_ABI = "arm64-v8a"

    sealed class Result {
        object NoNativeCode : Result()
        data class Supported(val abis: Set<String>) : Result()
        data class Unsupported(val abis: Set<String>) : Result() {
            val reason: String
                get() = "no $REQUIRED_ABI native libraries (APK only has ${abis.sorted().joinToString(", ")})"
        }
    }

    fun check(apk: File): Result = ZipFile(apk).use { zip ->
        check(zip.entries().asSequence().map { it.name }.toList())
    }

    /** Pure entry-name logic, split out so it's testable without building
     * zip files. */
    internal fun check(entryNames: List<String>): Result {
        val abis = entryNames.mapNotNull { name ->
            val parts = name.split('/')
            if (parts.size == 3 && parts[0] == "lib" && parts[2].endsWith(".so")) parts[1] else null
        }.toSet()
        return when {
            abis.isEmpty() -> Result.NoNativeCode
            REQUIRED_ABI in abis -> Result.Supported(abis)
            else -> Result.Unsupported(abis)
        }
    }
}
