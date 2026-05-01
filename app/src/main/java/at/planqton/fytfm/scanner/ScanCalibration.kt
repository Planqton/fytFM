package at.planqton.fytfm.scanner

/**
 * Pure helpers for the FM-scan calibration step in [RadioScanner]:
 * computing the noise floor from a set of band samples and deriving the
 * "found station" RSSI threshold from it.
 *
 * Extracted so the algorithm can be unit-tested independently of the JNI
 * tuner (which the sampling phase needs but the math doesn't).
 *
 * Behaviour pinned by tests:
 * - Empty samples → [DEFAULT_NOISE_FLOOR] (25). This keeps the scan
 *   functional on tuners that fail every RSSI read instead of producing
 *   a noise-floor of 0 (which would mark every silent slot as "found").
 * - Non-empty samples → average the **lower half** of the sorted list
 *   (rounded down). The lower half is "actual noise"; the upper half is
 *   already-broadcasting frequencies that bias the floor upwards.
 * - At least one sample is always used, even for a 1-element list.
 */
internal object ScanCalibration {

    /** Default fallback when no valid noise samples exist. */
    const val DEFAULT_NOISE_FLOOR = 25

    /** RSSI offset above noise floor for normal-sensitivity scans. */
    const val OFFSET_NORMAL = 15

    /** RSSI offset above noise floor for high-sensitivity scans (more weak stations). */
    const val OFFSET_SENSITIVE = 5

    /**
     * Computes the noise floor from a list of valid RSSI samples (any
     * order). Pre-filtering of zero / saturated values is the caller's
     * responsibility — RadioScanner accepts only `rssi in 1..99`.
     */
    fun calculateNoiseFloor(samples: List<Int>): Int {
        if (samples.isEmpty()) return DEFAULT_NOISE_FLOOR
        val sorted = samples.sorted()
        val lowerHalf = sorted.take(maxOf(sorted.size / 2, 1))
        return lowerHalf.average().toInt()
    }

    /**
     * Returns the RSSI threshold a station must clear to count as "found":
     * `noiseFloor + offset(highSensitivity)`. Tests pin both branches.
     */
    fun thresholdFor(noiseFloor: Int, highSensitivity: Boolean): Int {
        val offset = if (highSensitivity) OFFSET_SENSITIVE else OFFSET_NORMAL
        return noiseFloor + offset
    }

    /**
     * Generates the [count] sample frequencies that span [min]..[max],
     * starting in the middle of the first segment so we don't probe the
     * absolute band edges (where some tuners report unstable values).
     */
    fun sampleFrequencies(min: Float, max: Float, count: Int): List<Float> {
        if (count <= 0) return emptyList()
        val stepSize = (max - min) / count
        return (0 until count).map { i -> min + stepSize / 2 + i * stepSize }
    }
}
