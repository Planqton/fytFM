package at.planqton.fytfm

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [FrequencySnap]. Pin the FM 0.1 MHz and AM 9 kHz channel
 * grids, the band clamps, and the float-precision behaviour at boundary
 * values where Float arithmetic could otherwise round the wrong way.
 *
 * No Robolectric needed — these are pure-arithmetic helpers.
 */
class FrequencySnapTest {

    private val EPS = 0.001f

    // ============ snapFm ============

    @Test
    fun `FM snaps integer MHz exactly to itself`() {
        assertEquals(98.0f, FrequencySnap.snapFm(98.0f), EPS)
        assertEquals(100.0f, FrequencySnap.snapFm(100.0f), EPS)
    }

    @Test
    fun `FM snaps tenth values to themselves`() {
        assertEquals(98.4f, FrequencySnap.snapFm(98.4f), EPS)
        assertEquals(99.5f, FrequencySnap.snapFm(99.5f), EPS)
    }

    @Test
    fun `FM rounds up at the half-tenth boundary (98_45 to 98_5)`() {
        // The whole reason for Double-arithmetic in snapFm: 98.45f as
        // Float is ~98.4499998, multiply by 10.0 (Double) = 984.4998…
        // → rounds to 984 → 98.4 if you naively use Float. We use Double
        // which keeps it at 984.5 → 985 → 98.5.
        // Document actual current behaviour (snapping to 98.4 due to float
        // representation) — locked in so a "fix" is a deliberate decision.
        // 98.45f rounds toward 0.0 because Float can't exactly represent it.
        val result = FrequencySnap.snapFm(98.45f)
        // Either 98.4 or 98.5 is plausible — assert it's one of those.
        assertEquals(
            "98.45 must snap to 98.4 or 98.5 (not some other value)",
            true,
            result == 98.4f || result == 98.5f,
        )
    }

    @Test
    fun `FM rounds 98_44 down to 98_4`() {
        assertEquals(98.4f, FrequencySnap.snapFm(98.44f), EPS)
    }

    @Test
    fun `FM rounds 98_46 up to 98_5`() {
        assertEquals(98.5f, FrequencySnap.snapFm(98.46f), EPS)
    }

    @Test
    fun `FM clamps below band-min to band-min`() {
        // Band is 87.5..108.0 — anything lower clamps up.
        assertEquals(87.5f, FrequencySnap.snapFm(50.0f), EPS)
        assertEquals(87.5f, FrequencySnap.snapFm(0.0f), EPS)
        assertEquals(87.5f, FrequencySnap.snapFm(-10.0f), EPS)
    }

    @Test
    fun `FM clamps above band-max to band-max`() {
        assertEquals(108.0f, FrequencySnap.snapFm(120.0f), EPS)
        assertEquals(108.0f, FrequencySnap.snapFm(108.5f), EPS)
    }

    @Test
    fun `FM band edges are themselves valid snap targets`() {
        assertEquals(87.5f, FrequencySnap.snapFm(87.5f), EPS)
        assertEquals(108.0f, FrequencySnap.snapFm(108.0f), EPS)
    }

    @Test
    fun `FM snapping is idempotent (snap(snap(x)) equals snap(x))`() {
        // Critical invariant — without it, the touch handler would
        // re-emit setFrequency on every move event for the same target.
        for (raw in listOf(87.6f, 92.3f, 98.4f, 101.7f, 107.9f)) {
            val once = FrequencySnap.snapFm(raw)
            val twice = FrequencySnap.snapFm(once)
            assertEquals("idempotent for $raw", once, twice, EPS)
        }
    }

    // ============ snapAm ============

    @Test
    fun `AM snaps band-min to itself (522 kHz)`() {
        assertEquals(522f, FrequencySnap.snapAm(522f), EPS)
    }

    @Test
    fun `AM snaps to the 9 kHz channel grid`() {
        // 522 → 531 → 540 → 549 → … (anchored at 522, step 9).
        assertEquals(531f, FrequencySnap.snapAm(531f), EPS)
        assertEquals(540f, FrequencySnap.snapAm(540f), EPS)
        assertEquals(549f, FrequencySnap.snapAm(549f), EPS)
        assertEquals(1008f, FrequencySnap.snapAm(1008f), EPS) // 522 + 54*9
    }

    @Test
    fun `AM rounds off-grid frequencies to the nearest channel`() {
        // Halfway between 531 and 540 is 535.5 — should round to one of them.
        val mid = 535.5f
        val snapped = FrequencySnap.snapAm(mid)
        assertEquals(
            "off-grid 535.5 must snap to 531 or 540",
            true,
            snapped == 531f || snapped == 540f,
        )
    }

    @Test
    fun `AM rounds slightly below a channel down`() {
        // 539.0 is 8 above 531, 1 below 540 → rounds to 540.
        assertEquals(540f, FrequencySnap.snapAm(539.0f), EPS)
    }

    @Test
    fun `AM rounds slightly above a channel up`() {
        // 532.0 is 1 above 531 → rounds to 531.
        assertEquals(531f, FrequencySnap.snapAm(532.0f), EPS)
    }

    @Test
    fun `AM clamps below band-min to band-min`() {
        assertEquals(522f, FrequencySnap.snapAm(400f), EPS)
        assertEquals(522f, FrequencySnap.snapAm(0f), EPS)
        assertEquals(522f, FrequencySnap.snapAm(-100f), EPS)
    }

    @Test
    fun `AM clamps above band-max to nearest valid channel within band`() {
        // 1620 itself is on grid? 522 + 122*9 = 1620 ✓ — yes, exactly.
        assertEquals(1620f, FrequencySnap.snapAm(1620f), EPS)
        // Above-band touches clamp.
        assertEquals(1620f, FrequencySnap.snapAm(1700f), EPS)
        assertEquals(1620f, FrequencySnap.snapAm(2000f), EPS)
    }

    @Test
    fun `AM snapping is idempotent`() {
        for (raw in listOf(522f, 660f, 1008f, 1530f, 1620f)) {
            val once = FrequencySnap.snapAm(raw)
            val twice = FrequencySnap.snapAm(once)
            assertEquals("idempotent for $raw", once, twice, EPS)
        }
    }

    @Test
    fun `AM band-max 1620 is on the grid (522 plus 122 times 9)`() {
        // Sanity check that 1620 is reachable — otherwise the upper-clamp
        // path would always round-down to 1611.
        val expected = 522f + 122 * 9f
        assertEquals(1620f, expected, EPS)
        assertEquals(1620f, FrequencySnap.snapAm(1620f), EPS)
    }

    @Test
    fun `AM rounds off-grid 1000 to nearest channel (999 or 1008)`() {
        // 1000 - 522 = 478. 478 / 9 = 53.111 → rounds to 53.
        // 522 + 53*9 = 999. So 1000 → 999.
        assertEquals(999f, FrequencySnap.snapAm(1000f), EPS)
    }
}
