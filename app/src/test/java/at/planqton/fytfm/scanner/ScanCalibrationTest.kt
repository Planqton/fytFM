package at.planqton.fytfm.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [ScanCalibration]. Pin the noise-floor algorithm and the
 * threshold derivation that gate "is this a station?" decisions during
 * an FM scan. A regression here means missed stations (threshold too
 * high) or false positives (threshold too low).
 */
class ScanCalibrationTest {

    // ============ calculateNoiseFloor ============

    @Test
    fun `empty samples returns the documented default of 25`() {
        // Critical: a tuner that fails every RSSI read must NOT collapse
        // the floor to 0 (which would mark every silent slot as "found").
        assertEquals(25, ScanCalibration.calculateNoiseFloor(emptyList()))
        assertEquals(ScanCalibration.DEFAULT_NOISE_FLOOR, ScanCalibration.calculateNoiseFloor(emptyList()))
    }

    @Test
    fun `single-sample list uses that one sample (lower-half is at least 1)`() {
        // Without the `maxOf(size/2, 1)` guard, a 1-element list would
        // call .take(0) → empty → average() throws NaN/Exception.
        assertEquals(42, ScanCalibration.calculateNoiseFloor(listOf(42)))
    }

    @Test
    fun `averages the lower half of sorted samples`() {
        // [10, 20, 30, 40] → lower half = [10, 20] → average 15.
        assertEquals(15, ScanCalibration.calculateNoiseFloor(listOf(40, 10, 30, 20)))
    }

    @Test
    fun `lower-half is computed from sorted (input order does not matter)`() {
        val sorted = listOf(10, 20, 30, 40)
        val unsorted = listOf(40, 10, 30, 20)
        assertEquals(
            ScanCalibration.calculateNoiseFloor(sorted),
            ScanCalibration.calculateNoiseFloor(unsorted),
        )
    }

    @Test
    fun `odd-size list rounds the lower half down (size 5 → take 2)`() {
        // [10, 20, 30, 40, 50] → size/2 = 2 → take(2) = [10, 20] → avg 15.
        assertEquals(15, ScanCalibration.calculateNoiseFloor(listOf(10, 20, 30, 40, 50)))
    }

    @Test
    fun `floor truncates the average (not banker rounding)`() {
        // [10, 11] → lower half = [10] → average 10 → 10.
        // [10, 13] → lower half = [10] → average 10 → 10.
        assertEquals(10, ScanCalibration.calculateNoiseFloor(listOf(10, 11)))
        assertEquals(10, ScanCalibration.calculateNoiseFloor(listOf(10, 13)))
        // [10, 11, 14] → lower half size = 1 → take(1) = [10] → 10.
        assertEquals(10, ScanCalibration.calculateNoiseFloor(listOf(10, 11, 14)))
    }

    @Test
    fun `realistic 10-sample case picks a sensible noise floor`() {
        // Simulate a band with 5 silent slots (low RSSI) and 5 occupied
        // slots (high RSSI). Floor should be around the silent-slot avg.
        val samples = listOf(8, 10, 12, 14, 15, 65, 70, 72, 80, 85)
        val floor = ScanCalibration.calculateNoiseFloor(samples)
        // Lower half (sorted [8,10,12,14,15,65,70,72,80,85] → take 5)
        // → [8,10,12,14,15] → avg 11.8 → 11.
        assertEquals(11, floor)
    }

    @Test
    fun `large-sample case (50 samples) is dominated by the bottom 25`() {
        // Bottom-25 are tightly clustered at ~12, top-25 at ~80.
        val noise = (1..25).map { 10 + (it % 5) }   // 11..14, repeating
        val signal = (1..25).map { 75 + (it % 10) } // 75..84, repeating
        val samples = noise + signal
        val floor = ScanCalibration.calculateNoiseFloor(samples)
        // Lower 50 (size/2 = 50)? No — total 50, half = 25. So bottom 25
        // are the noise samples averaged ≈ 12.
        assertTrue("floor must reflect the noise cluster, got $floor", floor in 10..14)
    }

    // ============ thresholdFor ============

    @Test
    fun `threshold normal-sensitivity is noiseFloor + 15`() {
        assertEquals(40, ScanCalibration.thresholdFor(noiseFloor = 25, highSensitivity = false))
    }

    @Test
    fun `threshold high-sensitivity is noiseFloor + 5 (catches weaker stations)`() {
        assertEquals(30, ScanCalibration.thresholdFor(noiseFloor = 25, highSensitivity = true))
    }

    @Test
    fun `threshold offsets are exposed as constants for documentation`() {
        assertEquals(15, ScanCalibration.OFFSET_NORMAL)
        assertEquals(5, ScanCalibration.OFFSET_SENSITIVE)
    }

    @Test
    fun `threshold scales linearly with noise floor`() {
        // Two stations with the same RSSI delta-from-floor stay equally
        // detectable across different noise environments.
        assertEquals(20, ScanCalibration.thresholdFor(5, false) - 0)
        assertEquals(35, ScanCalibration.thresholdFor(20, false) - 0)
    }

    // ============ sampleFrequencies ============

    @Test
    fun `sampleFrequencies generates count entries`() {
        val freqs = ScanCalibration.sampleFrequencies(min = 87.5f, max = 108.0f, count = 10)
        assertEquals(10, freqs.size)
    }

    @Test
    fun `sampleFrequencies starts in the middle of the first segment (not at min)`() {
        // For 87.5..108.0 / 10 → step 2.05 → first sample at 87.5 + 1.025 = 88.525.
        val freqs = ScanCalibration.sampleFrequencies(87.5f, 108.0f, 10)
        assertEquals(88.525f, freqs.first(), 0.001f)
    }

    @Test
    fun `sampleFrequencies last entry stays inside the band`() {
        val freqs = ScanCalibration.sampleFrequencies(87.5f, 108.0f, 10)
        assertTrue("last must be < max, got ${freqs.last()}", freqs.last() < 108.0f)
    }

    @Test
    fun `sampleFrequencies entries are evenly spaced`() {
        val freqs = ScanCalibration.sampleFrequencies(87.5f, 108.0f, 10)
        val deltas = freqs.zipWithNext { a, b -> b - a }
        val first = deltas.first()
        for (d in deltas) {
            assertEquals("delta drift", first, d, 0.001f)
        }
    }

    @Test
    fun `sampleFrequencies returns empty for non-positive count (defensive)`() {
        assertTrue(ScanCalibration.sampleFrequencies(87.5f, 108.0f, 0).isEmpty())
        assertTrue(ScanCalibration.sampleFrequencies(87.5f, 108.0f, -1).isEmpty())
    }

    @Test
    fun `sampleFrequencies count=1 returns the band midpoint`() {
        // count=1 → step = (max - min) → first = min + step/2 = (min+max)/2.
        val freqs = ScanCalibration.sampleFrequencies(87.5f, 108.0f, 1)
        assertEquals(1, freqs.size)
        assertEquals(97.75f, freqs.first(), 0.001f)
    }

    @Test
    fun `sampleFrequencies AM band example (522 to 1620)`() {
        // Documents the algorithm against the AM band in case scanning
        // ever extends to AM noise calibration.
        val freqs = ScanCalibration.sampleFrequencies(522f, 1620f, 10)
        assertEquals(10, freqs.size)
        // First = 522 + (1098/10)/2 = 522 + 54.9 = 576.9
        assertEquals(576.9f, freqs.first(), 0.1f)
    }
}
