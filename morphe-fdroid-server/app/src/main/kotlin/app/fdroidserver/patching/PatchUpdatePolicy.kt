package app.fdroidserver.patching

import java.math.BigInteger
import kotlin.math.sign

/**
 * Decides which app version (if any) one patch attachment should be patched
 * at on a scheduled run, from the versions the sources list and what was
 * already recorded for that attachment.
 *
 * The rule is "keep the newest (app version, patch bundle version) combo and
 * only patch again when one of them moves up":
 *  - the newest published entry is the stored combo;
 *  - a listed version newer than the stored app version is patched;
 *  - the stored app version itself (or a newer one) is patched again when the
 *    bundle version is above the one it was last patched with;
 *  - nothing older than the stored app version is ever picked, so a version
 *    that APKMirror re-lists out of order can't push the newest build out of
 *    the repo's keep-window;
 *  - when several versions qualify, the highest one wins, not whichever the
 *    feed happens to list first.
 *
 * Versions the operator pinned by hand (`supported_versions`) skip the
 * "not older than stored" gate - they asked for exactly those versions - but
 * still follow the combo rule, so each one is patched once per bundle
 * version rather than on every run.
 *
 * `skipped: ...` entries (the APK can't run on this repo's devices - see
 * [NativeAbiCheck]) never count as the stored combo, and are final for that
 * app version, since a new bundle can't change the APK's ABIs. Deleting an
 * entry in the admin UI retries it.
 *
 * Kept free of the database and morphe-patcher types so it's unit-testable
 * on its own.
 */
object PatchUpdatePolicy {

    /** One recorded (app version, bundle version) result for an attachment. */
    data class Record(
        val version: String,
        val patchVersion: String,
        val output: String,
    ) {
        val isSkipped: Boolean get() = output.startsWith(SKIPPED_PREFIX)
        val isPublished: Boolean get() = !isSkipped
    }

    const val SKIPPED_PREFIX = "skipped: "

    /** The newest published record - the stored combo - or null when nothing
     * has been published for this attachment yet. */
    fun latestPublished(records: List<Record>): Record? =
        records.filter { it.isPublished }.reduceOrNull { best, r -> if (isNewer(r.version, best.version)) r else best }

    /**
     * Picks the version to patch from [candidateVersions] (already filtered
     * to the attachment's supported versions, in listing order), or null when
     * the stored combo is still current. [bundleVersion] is the attached
     * library entry's current version.
     */
    fun selectVersion(
        candidateVersions: List<String>,
        records: List<Record>,
        bundleVersion: String,
        pinned: Boolean,
    ): String? {
        val byVersion = records.associateBy { it.version }
        val latest = latestPublished(records)

        val allowed = candidateVersions.distinct().filter { version ->
            if (!pinned && latest != null && VersionOrder.compare(version, latest.version)?.let { it < 0 } == true) {
                return@filter false
            }
            val record = byVersion[version] ?: return@filter true
            !record.isSkipped && bundleMovedUp(bundleVersion, record.patchVersion)
        }
        return allowed.reduceOrNull { best, v -> if (isNewer(v, best)) v else best }
    }

    /** Whether [current] is a newer bundle version than [stored]. Versions
     * that can't be ordered count as moved when they differ at all - a
     * bundle is only ever imported when its release changed. */
    fun bundleMovedUp(current: String, stored: String): Boolean {
        val a = current.trim().removePrefix("v")
        val b = stored.trim().removePrefix("v")
        if (a.isEmpty()) return false
        if (b.isEmpty()) return true
        return VersionOrder.compare(a, b)?.let { it > 0 } ?: (a != b)
    }

    private fun isNewer(a: String, b: String): Boolean = VersionOrder.compare(a, b)?.let { it > 0 } == true
}

/**
 * Best-effort ordering of app/bundle version strings, which don't follow one
 * format (dotted numbers, calendar versions, `-beta`/`+rc1` suffixes, build
 * hashes...). The leading dotted-number core is compared numerically, with
 * missing parts counting as 0 (`2026.4` == `2026.04.0`); on a tie, a version
 * without a suffix is newer than one with (`1.2.0` > `1.2.0-beta1`), and two
 * suffixes are compared run by run (digit runs as numbers). Returns null when
 * the two can't be confidently ordered, so callers can decide which way to
 * err.
 */
object VersionOrder {
    private val CORE_RE = Regex("""^(\d+(?:\.\d+)*)(.*)$""")
    private val TOKEN_RE = Regex("""\d+|\D+""")

    fun compare(a: String, b: String): Int? {
        if (a == b) return 0
        val ma = CORE_RE.find(a.trim()) ?: return null
        val mb = CORE_RE.find(b.trim()) ?: return null

        val coreA = ma.groupValues[1].split('.').map { it.toBigInteger() }
        val coreB = mb.groupValues[1].split('.').map { it.toBigInteger() }
        for (i in 0 until maxOf(coreA.size, coreB.size)) {
            val cmp = coreA.getOrElse(i) { BigInteger.ZERO }.compareTo(coreB.getOrElse(i) { BigInteger.ZERO })
            if (cmp != 0) return cmp.sign
        }

        val suffixA = ma.groupValues[2]
        val suffixB = mb.groupValues[2]
        if (suffixA == suffixB) return 0
        if (suffixA.isEmpty()) return 1
        if (suffixB.isEmpty()) return -1
        return compareSuffix(suffixA, suffixB)
    }

    private fun compareSuffix(a: String, b: String): Int? {
        val ta = TOKEN_RE.findAll(a).map { it.value }.toList()
        val tb = TOKEN_RE.findAll(b).map { it.value }.toList()
        for (i in 0 until minOf(ta.size, tb.size)) {
            val (x, y) = ta[i] to tb[i]
            val xDigits = x[0].isDigit()
            if (xDigits != y[0].isDigit()) return null
            val cmp = if (xDigits) x.toBigInteger().compareTo(y.toBigInteger()) else x.compareTo(y)
            // Differing words (beta vs rc, or a build hash) have no order.
            if (cmp != 0) return if (xDigits) cmp.sign else null
        }
        return null
    }
}
