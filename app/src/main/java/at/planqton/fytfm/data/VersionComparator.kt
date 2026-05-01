package at.planqton.fytfm.data

/**
 * Numeric dot-separated version comparison used by [UpdateRepository].
 *
 * Behaviour pinned by tests:
 * - Equal versions return false (no "update to same version" prompt).
 * - Each segment is parsed as an Int; non-numeric segments (e.g. `-beta`)
 *   collapse to 0, so `1.0.0-beta` == `1.0.0`. This is a known limitation
 *   — the GitHub release flow never tags pre-releases as "latest", so the
 *   beta-equals-stable case doesn't come up in production today.
 * - Missing trailing segments default to 0: `1.0` == `1.0.0`.
 *
 * Extracted from `UpdateRepository.isVersionNewer` so the comparison can
 * be unit-tested without a `Context`/`PackageManager` shim.
 */
internal object VersionComparator {

    /**
     * Returns true if [latest] is strictly newer than [current].
     * Caller is responsible for stripping any leading `v`/`V` prefix.
     */
    fun isNewer(latest: String, current: String): Boolean {
        val latestParts = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }

        for (i in 0 until maxOf(latestParts.size, currentParts.size)) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }
}
