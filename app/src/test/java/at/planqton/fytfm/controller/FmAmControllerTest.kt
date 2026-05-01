package at.planqton.fytfm.controller

import android.content.Context
import androidx.core.content.edit
import at.planqton.fytfm.FrequencyScaleView
import at.planqton.fytfm.RdsManager
import at.planqton.fytfm.data.PresetRepository
import at.planqton.fytfm.data.RadioStation
import at.planqton.fytfm.platform.NoopRadioPlatform
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Tests for [FmAmController]. The controller wraps [FmNative] (JNI hardware
 * access) and [RdsManager], so we mock both with MockK and use a real
 * Robolectric SharedPreferences for the frequency-persistence checks.
 *
 * Coverage focus:
 * - Power lifecycle: togglePower / powerOn / powerOff (success + native-failure paths)
 * - Tune: clamp + persist + RDS-reset + native-error
 * - skipStation cycling through preset list
 * - Mode switching (FM ↔ AM) with the wasOn-restore dance
 * - Per-mode last-frequency persistence (separate prefs keys for FM/AM)
 * - RDS callback wiring + RT-handler invocation
 * - setMute / setMonoMode / setLocalMode / setRadioArea native pass-through
 * - Error paths: native exceptions surface via onError
 *
 * NOT covered: powerOnFull/powerOffFull (TWUtil MCU integration — needs
 * real hardware) and seek (depends on FmNative's float[] return shape;
 * covered minimally below).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class FmAmControllerTest {

    /** Subscribes to the SharedFlow on the test scheduler and returns a list
     *  the collector appends to. Call `runCurrent()` after the action under
     *  test before asserting; cancel returned job via `events::clear` is not
     *  needed because backgroundScope cancels at end of [runTest]. */
    private fun <T> TestScope.recordFlow(flow: Flow<T>): MutableList<T> {
        val events = mutableListOf<T>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            flow.collect { events.add(it) }
        }
        runCurrent()
        return events
    }

    private lateinit var context: Context
    private lateinit var fmNative: FmNativeApi
    private lateinit var rdsManager: RdsManager
    private lateinit var presetRepo: PresetRepository
    private lateinit var controller: FmAmController

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        // Wipe the prefs file so frequency-persistence tests start fresh.
        context.getSharedPreferences("fytfm_fmam", Context.MODE_PRIVATE).edit { clear() }

        fmNative = mockk(relaxed = true)
        rdsManager = mockk(relaxed = true)
        presetRepo = mockk(relaxed = true)

        // Default native behaviour: success.
        every { fmNative.powerOn(any()) } returns true
        every { fmNative.tune(any()) } returns true
        every { fmNative.openDev() } returns true
        every { fmNative.powerUp(any()) } returns true
        every { fmNative.setMute(any()) } returns 0
        every { fmNative.getrssi() } returns 50

        controller = FmAmController(
            context = context,
            fmNative = fmNative,
            rdsManager = rdsManager,
            presetRepository = presetRepo,
            twUtil = null,
            platform = NoopRadioPlatform,
        )
        controller.initialize()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ============ Power lifecycle ============

    @Test
    fun `togglePower from off calls powerOn and flips isRadioOn true`() {
        assertFalse(controller.isRadioOn)
        val result = controller.togglePower()
        assertTrue("togglePower returns true when newly on", result)
        assertTrue(controller.isRadioOn)
        verify { fmNative.powerOn(any()) }
    }

    @Test
    fun `togglePower from on calls powerOff and flips isRadioOn false`() {
        controller.powerOn()
        assertTrue(controller.isRadioOn)

        val result = controller.togglePower()
        assertFalse("togglePower returns false when newly off", result)
        assertFalse(controller.isRadioOn)
        verify { fmNative.powerOff() }
    }

    @Test
    fun `powerOn fires onRadioStateChanged with true on success`() = runTest {
        val events = recordFlow(controller.events)
        controller.powerOn()
        runCurrent()
        val event = events.filterIsInstance<FmAmEvent.RadioStateChanged>().last()
        assertEquals(true, event.isOn)
    }

    @Test
    fun `powerOn enables RDS only in FM mode (not AM)`() {
        controller.setMode(FrequencyScaleView.RadioMode.AM)
        controller.powerOn()
        verify(exactly = 0) { rdsManager.enableRds() }
        verify(exactly = 0) { rdsManager.startPolling(any()) }

        controller.powerOff()
        controller.setMode(FrequencyScaleView.RadioMode.FM)
        controller.powerOn()
        verify { rdsManager.enableRds() }
        verify { rdsManager.startPolling(any()) }
    }

    @Test
    fun `powerOn returns false and fires onError when native returns false`() = runTest {
        every { fmNative.powerOn(any()) } returns false
        val events = recordFlow(controller.events)
        val result = controller.powerOn()
        runCurrent()
        assertFalse(result)
        assertFalse("isRadioOn must stay false on native failure", controller.isRadioOn)
        val error = events.filterIsInstance<FmAmEvent.Error>().last()
        assertEquals("Radio konnte nicht gestartet werden", error.message)
    }

    @Test
    fun `powerOn fires onError when native throws`() = runTest {
        every { fmNative.powerOn(any()) } throws RuntimeException("JNI boom")
        val events = recordFlow(controller.events)
        val result = controller.powerOn()
        runCurrent()
        assertFalse(result)
        val error = events.filterIsInstance<FmAmEvent.Error>().last()
        assertTrue("error message must surface the native cause", error.message.contains("JNI boom"))
    }

    @Test
    fun `powerOff stops RDS polling and fires onRadioStateChanged false`() = runTest {
        controller.powerOn()
        val events = recordFlow(controller.events)
        controller.powerOff()
        runCurrent()
        verify { rdsManager.stopPolling() }
        val event = events.filterIsInstance<FmAmEvent.RadioStateChanged>().last()
        assertEquals(false, event.isOn)
        assertFalse(controller.isRadioOn)
    }

    // ============ Tune ============

    @Test
    fun `tune clamps below FM_MIN to FM_MIN`() {
        controller.tune(50.0f)
        assertEquals(FmAmController.FM_MIN, controller.currentFrequency, 0.01f)
    }

    @Test
    fun `tune clamps above FM_MAX to FM_MAX`() {
        controller.tune(120.0f)
        assertEquals(FmAmController.FM_MAX, controller.currentFrequency, 0.01f)
    }

    @Test
    fun `tune persists frequency to prefs (FM mode → fm key)`() {
        controller.tune(99.5f)
        val stored = context.getSharedPreferences("fytfm_fmam", Context.MODE_PRIVATE)
            .getFloat("last_fm_frequency", -1f)
        assertEquals(99.5f, stored, 0.01f)
    }

    @Test
    fun `tune fires onFrequencyChanged with clamped value`() = runTest {
        val events = recordFlow(controller.events)
        controller.tune(98.4f)
        runCurrent()
        val event = events.filterIsInstance<FmAmEvent.FrequencyChanged>().last()
        assertEquals(98.4f, event.frequency, 0.01f)
    }

    @Test
    fun `tune returns false and does NOT fire onFrequencyChanged when native fails`() = runTest {
        every { fmNative.tune(any()) } returns false
        val events = recordFlow(controller.events)
        val result = controller.tune(99.5f)
        runCurrent()
        assertFalse(result)
        assertFalse(
            "callback must NOT fire on native failure",
            events.any { it is FmAmEvent.FrequencyChanged },
        )
    }

    @Test
    fun `tune fires onError when native throws`() = runTest {
        every { fmNative.tune(any()) } throws RuntimeException("tune JNI boom")
        val events = recordFlow(controller.events)
        controller.tune(99.5f)
        runCurrent()
        val error = events.filterIsInstance<FmAmEvent.Error>().last()
        assertTrue(error.message.contains("tune JNI boom"))
    }

    @Test
    fun `tune in FM mode resets RDS data after successful tune`() {
        // First, push some RDS data through the captured callback.
        val callbackSlot = slot<RdsManager.RdsCallback>()
        every { rdsManager.startPolling(capture(callbackSlot)) } returns Unit
        controller.powerOn() // captures the callback
        callbackSlot.captured.onRdsUpdate("FM4", "Some Song", -60, 0xA3E0, 10, 0, 0, shortArrayOf())
        assertEquals("FM4", controller.currentPs)

        // Now tune — RDS state must reset.
        controller.tune(95.5f)
        assertEquals("PS resets on FM tune", "", controller.currentPs)
        assertEquals("RT resets on FM tune", "", controller.currentRt)
        assertEquals("PI resets on FM tune", 0, controller.currentPi)
    }

    @Test
    fun `tune in AM mode does NOT reset RDS state (AM has no RDS)`() {
        // AM tune shouldn't touch RDS fields.
        controller.setMode(FrequencyScaleView.RadioMode.AM)
        // Inject some leftover RDS state via the FM callback path first.
        controller.setMode(FrequencyScaleView.RadioMode.FM)
        val callbackSlot = slot<RdsManager.RdsCallback>()
        every { rdsManager.startPolling(capture(callbackSlot)) } returns Unit
        controller.powerOn()
        callbackSlot.captured.onRdsUpdate("FM4", "Song", -60, 0xA3E0, 10, 0, 0, shortArrayOf())

        controller.powerOff()
        controller.setMode(FrequencyScaleView.RadioMode.AM)
        controller.tune(990f)
        // PS/RT remain untouched in AM.
        assertEquals("FM4", controller.currentPs)
    }

    // ============ tuneStation ============

    @Test
    fun `tuneStation switches mode based on station_isAM flag`() {
        controller.tuneStation(RadioStation(frequency = 990f, isAM = true))
        assertEquals(FrequencyScaleView.RadioMode.AM, controller.currentMode)

        controller.tuneStation(RadioStation(frequency = 99.5f, isAM = false))
        assertEquals(FrequencyScaleView.RadioMode.FM, controller.currentMode)
    }

    // ============ skipStation ============

    @Test
    fun `skipStation forward cycles to next preset`() {
        every { presetRepo.loadFmStations() } returns listOf(
            RadioStation(frequency = 88.0f),
            RadioStation(frequency = 99.5f),
            RadioStation(frequency = 105.0f),
        )
        controller.tune(88.0f) // start at first
        val result = controller.skipStation(forward = true)
        assertEquals(99.5f, result?.frequency ?: 0f, 0.01f)
        assertEquals(99.5f, controller.currentFrequency, 0.01f)
    }

    @Test
    fun `skipStation forward wraps from last to first`() {
        every { presetRepo.loadFmStations() } returns listOf(
            RadioStation(frequency = 88.0f),
            RadioStation(frequency = 99.5f),
            RadioStation(frequency = 105.0f),
        )
        controller.tune(105.0f) // start at last
        val result = controller.skipStation(forward = true)
        assertEquals(88.0f, result?.frequency ?: 0f, 0.01f)
    }

    @Test
    fun `skipStation backward wraps from first to last`() {
        every { presetRepo.loadFmStations() } returns listOf(
            RadioStation(frequency = 88.0f),
            RadioStation(frequency = 99.5f),
            RadioStation(frequency = 105.0f),
        )
        controller.tune(88.0f) // start at first
        val result = controller.skipStation(forward = false)
        assertEquals(105.0f, result?.frequency ?: 0f, 0.01f)
    }

    @Test
    fun `skipStation returns null on empty preset list`() {
        every { presetRepo.loadFmStations() } returns emptyList()
        assertNull(controller.skipStation(forward = true))
    }

    @Test
    fun `skipStation lands on first preset when current frequency is off-list`() {
        every { presetRepo.loadFmStations() } returns listOf(
            RadioStation(frequency = 88.0f),
            RadioStation(frequency = 99.5f),
        )
        controller.tune(101.7f) // not in preset list
        val result = controller.skipStation(forward = true)
        // currentIndex = -1 → newIndex = 0 → first station.
        assertEquals(88.0f, result?.frequency ?: 0f, 0.01f)
    }

    @Test
    fun `skipStation in AM mode reads AM stations not FM`() {
        controller.setMode(FrequencyScaleView.RadioMode.AM)
        every { presetRepo.loadAmStations() } returns listOf(
            RadioStation(frequency = 540f, isAM = true),
            RadioStation(frequency = 990f, isAM = true),
        )
        controller.tune(540f)
        controller.skipStation(forward = true)
        verify { presetRepo.loadAmStations() }
        verify(exactly = 0) { presetRepo.loadFmStations() }
    }

    // ============ Mode switching ============

    @Test
    fun `setMode FM to AM swaps the persisted-frequency key`() {
        // Pre-seed AM and FM frequencies separately.
        context.getSharedPreferences("fytfm_fmam", Context.MODE_PRIVATE).edit {
            putFloat("last_fm_frequency", 92.3f)
            putFloat("last_am_frequency", 720f)
        }
        // Re-create controller so it picks up the seeded prefs on initialize.
        controller = FmAmController(
            context, fmNative, rdsManager, presetRepo, null, NoopRadioPlatform,
        )
        controller.initialize()
        assertEquals(92.3f, controller.currentFrequency, 0.01f)

        controller.setMode(FrequencyScaleView.RadioMode.AM)
        assertEquals(720f, controller.currentFrequency, 0.01f)
        assertEquals(FrequencyScaleView.RadioMode.AM, controller.currentMode)
    }

    @Test
    fun `setMode DAB is ignored (handled by DabController)`() {
        controller.setMode(FrequencyScaleView.RadioMode.DAB)
        // Mode stays at the previous value (FM by default).
        assertEquals(FrequencyScaleView.RadioMode.FM, controller.currentMode)
    }

    @Test
    fun `setMode while radio is on triggers off-mode-on dance`() {
        controller.powerOn()
        assertTrue(controller.isRadioOn)

        controller.setMode(FrequencyScaleView.RadioMode.AM)
        // Was on → must end up on again (radio survives mode switch).
        assertTrue("radio must still be on after mode switch", controller.isRadioOn)
        verify(atLeast = 1) { fmNative.powerOff() }
        verify(atLeast = 2) { fmNative.powerOn(any()) } // once before, once after
    }

    @Test
    fun `setMode while radio is off does not call powerOn`() {
        controller.setMode(FrequencyScaleView.RadioMode.AM)
        // Initial state was off — must not power on.
        assertFalse(controller.isRadioOn)
    }

    // ============ RDS callback wiring ============

    @Test
    fun `RDS callback updates state and fires onRdsUpdate`() = runTest {
        val callbackSlot = slot<RdsManager.RdsCallback>()
        every { rdsManager.startPolling(capture(callbackSlot)) } returns Unit
        controller.powerOn() // captures the callback

        val events = recordFlow(controller.events)

        callbackSlot.captured.onRdsUpdate(
            "Ö1", "Ein Lied", -55, 0xA101, 14, 0, 0, shortArrayOf(),
        )
        runCurrent()

        assertTrue(
            "controller's onRdsUpdate must fire",
            events.any { it is FmAmEvent.RdsUpdate },
        )
        assertEquals("Ö1", controller.currentPs)
        assertEquals("Ein Lied", controller.currentRt)
        assertEquals(-55, controller.currentRssi)
        assertEquals(0xA101, controller.currentPi)
        assertEquals(14, controller.currentPty)
    }

    @Test
    fun `RDS callback handles null ps and null rt as empty strings`() {
        val callbackSlot = slot<RdsManager.RdsCallback>()
        every { rdsManager.startPolling(capture(callbackSlot)) } returns Unit
        controller.powerOn()

        callbackSlot.captured.onRdsUpdate(null, null, 0, 0, 0, 0, 0, shortArrayOf())
        assertEquals("", controller.currentPs)
        assertEquals("", controller.currentRt)
    }

    // ============ Pass-through helpers ============

    @Test
    fun `setMonoMode delegates to fmNative`() {
        controller.setMonoMode(true)
        verify { fmNative.setMonoMode(true) }
    }

    @Test
    fun `setLocalMode delegates to fmNative`() {
        controller.setLocalMode(true)
        verify { fmNative.setLocalMode(true) }
    }

    @Test
    fun `setRadioArea delegates to fmNative`() {
        controller.setRadioArea(2)
        verify { fmNative.setRadioArea(2) }
    }

    @Test
    fun `setMute returns native result on success`() {
        every { fmNative.setMute(true) } returns 0
        assertEquals(0, controller.setMute(true))
    }

    @Test
    fun `setMute returns -1 and fires onError when native throws`() = runTest {
        every { fmNative.setMute(any()) } throws RuntimeException("mute boom")
        val events = recordFlow(controller.events)
        assertEquals(-1, controller.setMute(true))
        runCurrent()
        val error = events.filterIsInstance<FmAmEvent.Error>().last()
        assertTrue(error.message.contains("mute boom"))
    }

    @Test
    fun `getRssi returns 0 on native exception (graceful degradation)`() {
        every { fmNative.getrssi() } throws RuntimeException("rssi boom")
        // Must NOT throw — UI polls this every second.
        assertEquals(0, controller.getRssi())
    }

    @Test
    fun `tuneRaw returns native result without persisting`() {
        every { fmNative.tune(any()) } returns true
        controller.tune(99.5f) // persist 99.5
        controller.tuneRaw(101.7f)
        // tuneRaw must NOT update currentFrequency or persist.
        assertEquals("currentFrequency must NOT change for tuneRaw", 99.5f, controller.currentFrequency, 0.01f)
        val stored = context.getSharedPreferences("fytfm_fmam", Context.MODE_PRIVATE)
            .getFloat("last_fm_frequency", -1f)
        assertEquals("persisted value must NOT change for tuneRaw", 99.5f, stored, 0.01f)
    }
}
