package at.planqton.fytfm.dab

import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.Timer

/**
 * Regression tests for MockDabTunerManager — specifically Bug 1.2
 * (anonymous Timer leaked because it had no field reference and stopScan()
 * was a no-op). The scan timer now lives in [MockDabTunerManager.scanTimer]
 * and stopScan()/deinitialize() cancel it.
 *
 * The mock uses [Timer], which fires on its own daemon thread (NOT the
 * main looper). To observe listener callbacks we need both real-time sleep
 * (so the timer can tick) and an explicit Looper drain (so the
 * mainHandler.post{} bodies actually execute). The reflection-based tests
 * verify the structural fix directly so the suite stays fast.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MockDabTunerManagerTest {

    private class RecordingListener : DabScanListener {
        var startedCount = 0
        var progressCount = 0
        var finishedCount = 0
        var errorCount = 0
        val foundStations = mutableListOf<DabStation>()

        override fun onScanStarted() { startedCount++ }
        override fun onScanProgress(percent: Int, blockLabel: String) { progressCount++ }
        override fun onServiceFound(service: DabStation) { foundStations += service }
        override fun onScanFinished(services: List<DabStation>) { finishedCount++ }
        override fun onScanError(error: String) { errorCount++ }
    }

    private fun scanTimerOf(manager: MockDabTunerManager): Timer? {
        val field = MockDabTunerManager::class.java.getDeclaredField("scanTimer")
        field.isAccessible = true
        return field.get(manager) as? Timer
    }

    private fun drainLooper() {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    // ===== Structural tests (fast) =====

    @Test
    fun `startScan stores the scanTimer in a field`() {
        val manager = MockDabTunerManager()
        assertNull("scanTimer is null before any scan", scanTimerOf(manager))

        manager.startScan(RecordingListener())
        assertNotNull("scanTimer must be assigned so it can be cancelled later", scanTimerOf(manager))

        manager.stopScan()
    }

    @Test
    fun `stopScan clears the scanTimer field`() {
        val manager = MockDabTunerManager()
        manager.startScan(RecordingListener())

        manager.stopScan()
        assertNull("stopScan must null out the field — otherwise the leak persists", scanTimerOf(manager))
    }

    @Test
    fun `deinitialize cancels the scanTimer`() {
        val manager = MockDabTunerManager()
        manager.startScan(RecordingListener())
        assertNotNull(scanTimerOf(manager))

        manager.deinitialize()
        assertNull("deinitialize must cancel and null the scanTimer", scanTimerOf(manager))
    }

    @Test
    fun `restarting scan cancels the previous timer (no leak across scans)`() {
        val manager = MockDabTunerManager()
        manager.startScan(RecordingListener())
        val first = scanTimerOf(manager)
        assertNotNull(first)

        manager.startScan(RecordingListener())
        val second = scanTimerOf(manager)
        assertNotNull(second)
        assertFalse(
            "a fresh Timer instance must be installed (old one cancelled)",
            first === second,
        )

        manager.stopScan()
    }

    @Test
    fun `stopScan before any scan is a safe no-op`() {
        val manager = MockDabTunerManager()
        // Must not throw — scanTimer is null until first scan.
        manager.stopScan()
        manager.deinitialize()
        assertNull(scanTimerOf(manager))
    }

    // ===== End-to-end test (slow — uses real timer) =====

    @Test
    fun `full scan reports started, progress and finished via listener`() {
        val manager = MockDabTunerManager()
        val listener = RecordingListener()

        manager.startScan(listener)
        // Initial 300ms + 6 frequencies × 500ms = 3.3s. Add headroom for
        // GC / scheduler jitter on the CI host. Listener callbacks are
        // posted to the main looper, which we drain afterwards.
        Thread.sleep(4_500)
        drainLooper()

        assertEquals("onScanStarted fires exactly once", 1, listener.startedCount)
        assertEquals("scan reports finished exactly once", 1, listener.finishedCount)
        assertEquals("no error path triggered", 0, listener.errorCount)
        assertTrue("listener should have collected mock stations", listener.foundStations.isNotEmpty())
        assertNull("self-cancel path nulls the field via the timer body", scanTimerOf(manager))
    }
}
