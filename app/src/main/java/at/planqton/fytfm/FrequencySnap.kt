package at.planqton.fytfm

/**
 * Pure frequency-snapping helpers extracted from
 * [FrequencyScaleView.onTouchEvent] so the rounding rules can be
 * unit-tested without instantiating a View.
 *
 * Behaviour pinned by [FrequencySnapTest]:
 * - **FM**: snap to nearest 0.1 MHz, clamped to [FM_MIN, FM_MAX]
 *   (87.5–108.0). Done in `Double`-arithmetic so float precision wobble
 *   doesn't push 98.45 → 98.4 (it goes to 98.5 as users expect).
 * - **AM**: snap to the nearest 9 kHz channel grid anchored at AM_MIN
 *   (522 kHz), clamped to [AM_MIN, AM_MAX] (522–1620). Off-grid touches
 *   round to the closest valid channel.
 */
internal object FrequencySnap {

    /**
     * Rounds [rawMHz] to the nearest 0.1 MHz, then clamps to the FM band.
     * Operates in Double for the multiply-by-10 step to avoid the float
     * rounding artefact where `0.05 * 10 = 0.4999…` (which would round
     * down instead of up).
     */
    fun snapFm(rawMHz: Float): Float {
        val scaled = Math.round(rawMHz * 10.0)
        return (scaled / 10.0f).coerceIn(
            FrequencyScaleView.FM_MIN_FREQUENCY,
            FrequencyScaleView.FM_MAX_FREQUENCY,
        )
    }

    /**
     * Rounds [rawKHz] to the nearest valid AM channel (anchored at
     * AM_MIN_FREQUENCY = 522 kHz, step 9 kHz), then clamps to the AM band.
     * Off-grid touches round to the closest channel boundary.
     */
    fun snapAm(rawKHz: Float): Float {
        val steps = Math.round(
            (rawKHz - FrequencyScaleView.AM_MIN_FREQUENCY) / FrequencyScaleView.AM_FREQUENCY_STEP,
        )
        return (FrequencyScaleView.AM_MIN_FREQUENCY + steps * FrequencyScaleView.AM_FREQUENCY_STEP)
            .coerceIn(FrequencyScaleView.AM_MIN_FREQUENCY, FrequencyScaleView.AM_MAX_FREQUENCY)
    }
}
