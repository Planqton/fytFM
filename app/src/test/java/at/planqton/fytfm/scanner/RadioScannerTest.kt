package at.planqton.fytfm.scanner

import android.os.Bundle
import android.os.Looper
import at.planqton.fytfm.RdsManager
import at.planqton.fytfm.controller.FmNativeApi
import at.planqton.fytfm.data.RadioStation
import com.android.fmradio.FmNative
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Tests for [RadioScanner]. The scanner spawns a worker thread per scan
 * and posts results back via the main Looper, so each test:
 *
 *  1. Sets up an [FmNativeApi] mock (or null for the bail-fast paths).
 *  2. Starts a scan with a [CountDownLatch] in the onComplete callback.
 *  3. Idles the Robolectric main looper to drain `mainHandler.post` calls.
 *  4. Awaits the latch with a short timeout.
 *
 * What's covered: postSortedComplete snapshot semantics, stopScan/skipScan
 * gating when no scan is running, and the bail-fast paths when [FmNativeApi]
 * is null. Plus the [RadioScanner.scanFMNative] hardware-autoscan path,
 * which is the only scan flow without a per-frequency `Thread.sleep` —
 * the manual scanFM/scanAM paths sleep 250ms × 200+ frequencies, too slow
 * for unit tests without restructuring the loop.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RadioScannerTest {

    private lateinit var rdsManager: RdsManager
    private lateinit var fmNative: FmNativeApi

    @Before
    fun setup() {
        rdsManager = mockk(relaxed = true)
        fmNative = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    /** Drives the scan thread to completion, then drains main-looper posts.
     *  We can't simply `latch.await` because the test runs on the same
     *  thread Robolectric uses for the main Looper — blocking it would also
     *  block the very `mainHandler.post` callback that counts the latch
     *  down. So we poll: drain the looper, sleep briefly, repeat. */
    private fun awaitCompletion(latch: CountDownLatch) {
        val deadline = System.currentTimeMillis() + 2_000
        while (latch.count > 0 && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (latch.count == 0L) break
            Thread.sleep(20)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue("scan callback was not invoked within 2s", latch.count == 0L)
    }

    // ============ postSortedComplete (snapshot semantics) ============

    @Test
    fun `postSortedComplete returns stations sorted ascending by frequency`() {
        val scanner = RadioScanner(rdsManager, fmNative)
        val unsorted = listOf(
            RadioStation(98.4f, "FM4", -55, false),
            RadioStation(87.6f, "Ö1", -48, false),
            RadioStation(102.5f, "Ö3", -52, false),
        )
        var captured: List<RadioStation>? = null
        scanner.postSortedComplete(unsorted) { captured = it }
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertNotNull(captured)
        assertEquals(listOf(87.6f, 98.4f, 102.5f), captured!!.map { it.frequency })
    }

    @Test
    fun `postSortedComplete is a snapshot — mutating source after call does not affect callback list`() {
        val scanner = RadioScanner(rdsManager, fmNative)
        val mutable = mutableListOf(
            RadioStation(98.4f, "FM4", -55, false),
            RadioStation(87.6f, "Ö1", -48, false),
        )
        var captured: List<RadioStation>? = null
        scanner.postSortedComplete(mutable) { captured = it }
        // Mutate AFTER the post but BEFORE the main-looper drains.
        mutable.add(RadioStation(102.5f, "Ö3", -52, false))
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(2, captured!!.size)
        assertEquals(listOf(87.6f, 98.4f), captured!!.map { it.frequency })
    }

    @Test
    fun `postSortedComplete with empty input returns empty list`() {
        val scanner = RadioScanner(rdsManager, fmNative)
        var captured: List<RadioStation>? = null
        scanner.postSortedComplete(emptyList()) { captured = it }
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(emptyList<RadioStation>(), captured)
    }

    // ============ stopScan / skipScan gating ============

    @Test
    fun `stopScan when not scanning does NOT call native stopScan`() {
        val scanner = RadioScanner(rdsManager, fmNative)
        scanner.stopScan()
        verify(exactly = 0) { fmNative.stopScan() }
    }

    @Test
    fun `skipScan when not scanning does NOT call native stopScan`() {
        val scanner = RadioScanner(rdsManager, fmNative)
        scanner.skipScan()
        verify(exactly = 0) { fmNative.stopScan() }
    }

    // ============ Bail-fast when FmNative is null ============

    @Test
    fun `scanFM with null FmNative completes immediately with empty list`() {
        val scanner = RadioScanner(rdsManager, fmNative = null)
        val latch = CountDownLatch(1)
        var result: List<RadioStation>? = null
        scanner.scanFM(
            onProgress = { _, _, _, _, _ -> },
            onComplete = { stations -> result = stations; latch.countDown() },
        )
        awaitCompletion(latch)
        assertEquals(emptyList<RadioStation>(), result)
    }

    @Test
    fun `scanAM with null FmNative completes immediately with empty list`() {
        val scanner = RadioScanner(rdsManager, fmNative = null)
        val latch = CountDownLatch(1)
        var result: List<RadioStation>? = null
        scanner.scanAM(
            onProgress = { _, _, _, _ -> },
            onComplete = { stations -> result = stations; latch.countDown() },
        )
        awaitCompletion(latch)
        assertEquals(emptyList<RadioStation>(), result)
    }

    @Test
    fun `scanFMSignalOnly with null FmNative completes immediately with empty list`() {
        val scanner = RadioScanner(rdsManager, fmNative = null)
        val latch = CountDownLatch(1)
        var result: List<RadioStation>? = null
        scanner.scanFMSignalOnly(
            onProgress = { _, _, _, _ -> },
            onComplete = { stations -> result = stations; latch.countDown() },
        )
        awaitCompletion(latch)
        assertEquals(emptyList<RadioStation>(), result)
    }

    @Test
    fun `scanFMNative with null FmNative completes immediately with empty list`() {
        val scanner = RadioScanner(rdsManager, fmNative = null)
        val latch = CountDownLatch(1)
        var result: List<RadioStation>? = null
        scanner.scanFMNative(
            onProgress = { _, _, _, _, _ -> },
            onComplete = { stations -> result = stations; latch.countDown() },
        )
        awaitCompletion(latch)
        assertEquals(emptyList<RadioStation>(), result)
    }

    // ============ scanFMNative — fmsyu_jni autoscan happy / edge cases ============
    //
    // scanFMNative is the only scan flow that doesn't sleep per-frequency,
    // so the full path runs deterministically in tests. These pin the
    // outBundle parsing contract (freq*10 + RSSI shorts).

    @Test
    fun `scanFMNative returns stations parsed from outBundle param0+param1 shorts`() {
        // fmsyu_jni AUTOSCAN populates the OUT bundle with parallel arrays
        // of frequency-times-10 and RSSI. We seed them on the captured slot.
        every { fmNative.fmsyu_jni(any(), any(), any()) } answers {
            val out = arg<Bundle>(2)
            out.putShortArray("param0", shortArrayOf(876, 984, 1025))  // 87.6 / 98.4 / 102.5 MHz
            out.putShortArray("param1", shortArrayOf(-48, -55, -52))
            0  // success
        }

        val scanner = RadioScanner(rdsManager, fmNative)
        val latch = CountDownLatch(1)
        var result: List<RadioStation>? = null
        scanner.scanFMNative(
            onProgress = { _, _, _, _, _ -> },
            onComplete = { stations -> result = stations; latch.countDown() },
        )
        awaitCompletion(latch)

        assertNotNull(result)
        assertEquals(3, result!!.size)
        // Sorted ascending by frequency (postSortedComplete).
        assertEquals(listOf(87.6f, 98.4f, 102.5f), result!!.map { it.frequency })
        // RSSI carried through paired positionally with the input order.
        val byFreq = result!!.associateBy { it.frequency }
        assertEquals(-48, byFreq[87.6f]!!.rssi)
        assertEquals(-55, byFreq[98.4f]!!.rssi)
        assertEquals(-52, byFreq[102.5f]!!.rssi)
    }

    @Test
    fun `scanFMNative invokes onStationFound for each parsed station`() {
        every { fmNative.fmsyu_jni(any(), any(), any()) } answers {
            val out = arg<Bundle>(2)
            out.putShortArray("param0", shortArrayOf(876, 984))
            out.putShortArray("param1", shortArrayOf(-48, -55))
            0
        }

        val found = mutableListOf<RadioStation>()
        val scanner = RadioScanner(rdsManager, fmNative)
        val latch = CountDownLatch(1)
        scanner.scanFMNative(
            onProgress = { _, _, _, _, _ -> },
            onStationFound = { station -> found.add(station) },
            onComplete = { _ -> latch.countDown() },
        )
        awaitCompletion(latch)

        assertEquals(2, found.size)
        assertEquals(setOf(87.6f, 98.4f), found.map { it.frequency }.toSet())
    }

    @Test
    fun `scanFMNative drops frequencies outside the FM band`() {
        every { fmNative.fmsyu_jni(any(), any(), any()) } answers {
            val out = arg<Bundle>(2)
            // 50.0 MHz (below FM_MIN), 98.4 MHz (in-band), 200.0 MHz (above FM_MAX)
            out.putShortArray("param0", shortArrayOf(500, 984, 2000))
            out.putShortArray("param1", shortArrayOf(-30, -55, -40))
            0
        }

        val scanner = RadioScanner(rdsManager, fmNative)
        val latch = CountDownLatch(1)
        var result: List<RadioStation>? = null
        scanner.scanFMNative(
            onProgress = { _, _, _, _, _ -> },
            onComplete = { stations -> result = stations; latch.countDown() },
        )
        awaitCompletion(latch)

        // Only the in-band frequency should survive.
        assertEquals(1, result!!.size)
        assertEquals(98.4f, result!![0].frequency)
    }

    @Test
    fun `scanFMNative returns empty list when fmsyu_jni returns success but no param0`() {
        every { fmNative.fmsyu_jni(any(), any(), any()) } returns 0
        // outBundle stays empty — getShortArray("param0") returns null.

        val scanner = RadioScanner(rdsManager, fmNative)
        val latch = CountDownLatch(1)
        var result: List<RadioStation>? = null
        scanner.scanFMNative(
            onProgress = { _, _, _, _, _ -> },
            onComplete = { stations -> result = stations; latch.countDown() },
        )
        awaitCompletion(latch)

        assertEquals(emptyList<RadioStation>(), result)
    }

    @Test
    fun `scanFMNative tolerates radio init exception and continues to call fmsyu_jni`() {
        // openDev / powerUp may throw if the radio is already on; the scanner
        // logs the exception and proceeds (the comment says "may already be on").
        every { fmNative.openDev() } throws RuntimeException("already open")
        every { fmNative.fmsyu_jni(any(), any(), any()) } answers {
            val out = arg<Bundle>(2)
            out.putShortArray("param0", shortArrayOf(984))
            out.putShortArray("param1", shortArrayOf(-55))
            0
        }

        val scanner = RadioScanner(rdsManager, fmNative)
        val latch = CountDownLatch(1)
        var result: List<RadioStation>? = null
        scanner.scanFMNative(
            onProgress = { _, _, _, _, _ -> },
            onComplete = { stations -> result = stations; latch.countDown() },
        )
        awaitCompletion(latch)

        assertEquals(1, result!!.size)
        assertEquals(98.4f, result!![0].frequency)
        // fmsyu_jni was reached despite the openDev exception.
        verify { fmNative.fmsyu_jni(eq(FmNative.CMD_AUTOSCAN), any(), any()) }
    }
}
