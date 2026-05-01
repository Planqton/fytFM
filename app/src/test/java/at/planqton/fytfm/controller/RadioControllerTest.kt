package at.planqton.fytfm.controller

import android.content.Context
import androidx.core.content.edit
import at.planqton.fytfm.FrequencyScaleView
import at.planqton.fytfm.RdsManager
import at.planqton.fytfm.dab.DabStation
import at.planqton.fytfm.dab.DabTunerManager
import at.planqton.fytfm.dab.MockDabTunerManager
import at.planqton.fytfm.data.PresetRepository
import at.planqton.fytfm.data.RadioStation
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
 * Tests for [RadioController]. The orchestrator owns currentMode +
 * persistence, dispatches public-API calls to FmAm vs Dab sub-controllers
 * by mode, and emits [RadioEvent]s for ViewModel observers.
 *
 * Sub-controllers are real (we control their hardware deps via mocked
 * [FmNativeApi] / [DabTunerManager]), so the wiring behaviour the
 * controller is responsible for actually executes — no double-mocking.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RadioControllerTest {

    private lateinit var context: Context
    private lateinit var fmNative: FmNativeApi
    private lateinit var rdsManager: RdsManager
    private lateinit var dabTunerManager: DabTunerManager
    private lateinit var mockDabTuner: MockDabTunerManager
    private lateinit var presetRepo: PresetRepository
    private lateinit var controller: RadioController

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = RuntimeEnvironment.getApplication()
        // Wipe both prefs files used by the controllers we instantiate.
        context.getSharedPreferences("fytfm_fmam", Context.MODE_PRIVATE).edit { clear() }
        context.getSharedPreferences("fytfm_dab", Context.MODE_PRIVATE).edit { clear() }

        fmNative = mockk(relaxed = true)
        rdsManager = mockk(relaxed = true)
        dabTunerManager = mockk(relaxed = true)
        mockDabTuner = mockk(relaxed = true)
        presetRepo = mockk(relaxed = true)

        every { fmNative.isLibraryLoaded() } returns true
        every { fmNative.powerOn(any()) } returns true
        every { fmNative.powerOff() } returns true
        every { fmNative.tune(any()) } returns true
        every { fmNative.openDev() } returns true
        every { fmNative.powerUp(any()) } returns true
        every { fmNative.setMute(any()) } returns 0
        every { fmNative.getrssi() } returns 50

        every { dabTunerManager.initialize(any()) } returns true
        every { dabTunerManager.tuneService(any(), any()) } returns true
        every { dabTunerManager.isDabAvailable(any()) } returns true

        controller = RadioController(
            context = context,
            fmNative = fmNative,
            rdsManager = rdsManager,
            realDabBackend = dabTunerManager,
            mockDabBackend = mockDabTuner,
            presetRepository = presetRepo,
            twUtil = null,
        )
        controller.initialize()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    // ============ Initial state + persistence ============

    @Test
    fun `initial currentMode is FM on fresh prefs`() {
        assertEquals(FrequencyScaleView.RadioMode.FM, controller.currentMode)
    }

    @Test
    fun `initialize loads the previously persisted mode`() {
        // Persist DAB explicitly, then re-initialise.
        context.getSharedPreferences("fytfm_fmam", Context.MODE_PRIVATE).edit {
            putString("last_radio_mode", "DAB")
        }
        val fresh = RadioController(context, fmNative, rdsManager, dabTunerManager, mockDabTuner, presetRepo, null)
        fresh.initialize()
        assertEquals(FrequencyScaleView.RadioMode.DAB, fresh.currentMode)
    }

    @Test
    fun `initialize falls back to FM when persisted mode string is invalid`() {
        context.getSharedPreferences("fytfm_fmam", Context.MODE_PRIVATE).edit {
            putString("last_radio_mode", "BOGUS")
        }
        val fresh = RadioController(context, fmNative, rdsManager, dabTunerManager, mockDabTuner, presetRepo, null)
        fresh.initialize()
        assertEquals(FrequencyScaleView.RadioMode.FM, fresh.currentMode)
    }

    // ============ setMode ============

    @Test
    fun `setMode updates currentMode and persists to prefs`() {
        controller.setMode(FrequencyScaleView.RadioMode.AM)
        assertEquals(FrequencyScaleView.RadioMode.AM, controller.currentMode)
        val stored = context.getSharedPreferences("fytfm_fmam", Context.MODE_PRIVATE)
            .getString("last_radio_mode", null)
        assertEquals("AM", stored)
    }

    @Test
    fun `setMode to the same mode is a no-op (no event, no persist)`() = runTest {
        val collected = mutableListOf<RadioEvent>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.events.collect { collected.add(it) }
        }
        runCurrent()
        controller.setMode(FrequencyScaleView.RadioMode.FM) // already FM
        advanceUntilIdle()
        job.cancel()
        assertTrue(
            "no-op when mode unchanged — no ModeChanged event, got: $collected",
            collected.none { it is RadioEvent.ModeChanged },
        )
    }

    @Test
    fun `setMode emits ModeChanged event`() = runTest {
        val collected = mutableListOf<RadioEvent>()
        // backgroundScope.launch with UnconfinedTestDispatcher subscribes
        // immediately — otherwise the collector starts AFTER the emit and
        // (replay = 0) drops the event.
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.events.collect { collected.add(it) }
        }
        runCurrent()
        controller.setMode(FrequencyScaleView.RadioMode.AM)
        advanceUntilIdle()
        job.cancel()
        val modeEvent = collected.filterIsInstance<RadioEvent.ModeChanged>().firstOrNull()
        assertEquals(FrequencyScaleView.RadioMode.AM, modeEvent?.mode)
    }

    @Test
    fun `setMode FM to DAB powers off the FM-side first when it was on`() {
        controller.fmAmController.powerOn() // simulate FM running
        assertTrue(controller.fmAmController.isRadioOn)

        controller.setMode(FrequencyScaleView.RadioMode.DAB)

        // FM must have been turned off as part of the mode switch.
        verify { fmNative.powerOff() }
        assertFalse(controller.fmAmController.isRadioOn)
    }

    @Test
    fun `setMode DAB to FM powers off the DAB-side first when it was on`() {
        controller.setMode(FrequencyScaleView.RadioMode.DAB)
        controller.dabController.powerOn() // simulate DAB running
        assertTrue(controller.dabController.isDabOn)

        controller.setMode(FrequencyScaleView.RadioMode.FM)

        verify { dabTunerManager.deinitialize() }
        assertFalse(controller.dabController.isDabOn)
    }

    @Test
    fun `setMode does NOT auto-power-on the new mode (stays off)`() {
        // FM is on, switch to AM — AM must NOT auto-start (user controls power).
        controller.fmAmController.powerOn()
        controller.setMode(FrequencyScaleView.RadioMode.AM)
        assertFalse(controller.fmAmController.isRadioOn)
    }

    @Test
    fun `setMode FM to DAB does NOT delegate to fmAmController_setMode (DAB has no FM-side state)`() {
        // The setMode dispatch only forwards to fmAmController.setMode for FM/AM
        // targets. Switching INTO DAB must not touch FM/AM mode state.
        val oldFmAmMode = controller.fmAmController.currentMode
        controller.setMode(FrequencyScaleView.RadioMode.DAB)
        // FmAmController.currentMode is unchanged because RadioController
        // only forwards setMode for FM/AM targets.
        assertEquals(oldFmAmMode, controller.fmAmController.currentMode)
    }

    // ============ togglePower / isRadioOn dispatch ============

    @Test
    fun `togglePower in FM mode delegates to fmAmController`() {
        assertFalse(controller.isRadioOn())
        controller.togglePower()
        assertTrue(controller.isRadioOn())
        verify { fmNative.powerOn(any()) }
    }

    @Test
    fun `togglePower in DAB mode delegates to dabController`() {
        controller.setMode(FrequencyScaleView.RadioMode.DAB)
        assertFalse(controller.isRadioOn())
        controller.togglePower()
        assertTrue(controller.isRadioOn())
        verify { dabTunerManager.initialize(context) }
    }

    @Test
    fun `isRadioOn reflects fmAm state in FM mode`() {
        controller.fmAmController.powerOn()
        assertTrue(controller.isRadioOn())
        controller.fmAmController.powerOff()
        assertFalse(controller.isRadioOn())
    }

    @Test
    fun `isRadioOn reflects dab state in DAB mode`() {
        controller.setMode(FrequencyScaleView.RadioMode.DAB)
        controller.dabController.powerOn()
        assertTrue(controller.isRadioOn())
    }

    // ============ Event flow forwarding (callback → events) ============

    @Test
    fun `RadioStateChanged from fmAmController emits onto events flow`() = runTest {
        val collected = mutableListOf<RadioEvent>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.events.collect { collected.add(it) }
        }
        runCurrent()
        controller.fmAmController.powerOn()
        advanceUntilIdle()
        job.cancel()
        val event = collected.filterIsInstance<RadioEvent.RadioStateChanged>().firstOrNull()
        assertEquals(true, event?.isOn)
    }

    @Test
    fun `FrequencyChanged from fmAmController emits onto events flow`() = runTest {
        val collected = mutableListOf<RadioEvent>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.events.collect { collected.add(it) }
        }
        runCurrent()
        controller.fmAmController.tune(99.5f)
        advanceUntilIdle()
        job.cancel()
        val event = collected.filterIsInstance<RadioEvent.FrequencyChanged>().firstOrNull()
        assertEquals(99.5f, event?.frequency ?: 0f, 0.01f)
    }

    @Test
    fun `Multiple events accumulate in order on the SharedFlow`() = runTest {
        val collected = mutableListOf<RadioEvent>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.events.collect { collected.add(it) }
        }
        runCurrent()
        controller.setMode(FrequencyScaleView.RadioMode.AM)
        controller.fmAmController.powerOn()
        controller.fmAmController.tune(990f)
        advanceUntilIdle()
        job.cancel()

        // We expect at least: ModeChanged(AM), RadioStateChanged(true), FrequencyChanged(990).
        val types = collected.map { it::class.simpleName }
        assertTrue("ModeChanged event present", types.contains("ModeChanged"))
        assertTrue("RadioStateChanged event present", types.contains("RadioStateChanged"))
        assertTrue("FrequencyChanged event present", types.contains("FrequencyChanged"))
    }

    // ============ tuneStation routing ============

    @Test
    fun `tuneStation with DAB station auto-switches mode and tunes`() {
        controller.setMode(FrequencyScaleView.RadioMode.FM)
        val dabStation = RadioStation(
            frequency = 0f, isDab = true,
            serviceId = 1234, ensembleId = 1,
        )
        controller.tuneStation(dabStation)
        // Auto-switched to DAB.
        assertEquals(FrequencyScaleView.RadioMode.DAB, controller.currentMode)
        verify { dabTunerManager.tuneService(1234, 1) }
    }

    @Test
    fun `tuneStation with FM station auto-switches mode if currently DAB`() {
        controller.setMode(FrequencyScaleView.RadioMode.DAB)
        val fmStation = RadioStation(frequency = 99.5f, isDab = false)
        controller.tuneStation(fmStation)
        // Should switch back to FM/AM (not DAB).
        assertFalse(
            "must switch out of DAB",
            controller.currentMode == FrequencyScaleView.RadioMode.DAB,
        )
        verify { fmNative.tune(99.5f) }
    }

    // ============ skipStation dispatch ============

    @Test
    fun `skipStation in FM mode delegates to fmAmController`() {
        every { presetRepo.loadFmStations() } returns listOf(
            RadioStation(frequency = 88.0f),
            RadioStation(frequency = 99.5f),
        )
        controller.fmAmController.tune(88.0f)
        val result = controller.skipStation(forward = true)
        assertEquals(99.5f, result?.frequency ?: 0f, 0.01f)
        verify { presetRepo.loadFmStations() }
    }

    @Test
    fun `skipStation in DAB mode delegates to dabController`() {
        controller.setMode(FrequencyScaleView.RadioMode.DAB)
        every { presetRepo.loadDabStations() } returns listOf(
            RadioStation(frequency = 0f, isDab = true, serviceId = 100),
            RadioStation(frequency = 0f, isDab = true, serviceId = 200),
        )
        controller.dabController.tuneService(100, 1)
        val result = controller.skipStation(forward = true)
        assertEquals(200, result?.serviceId)
        verify { presetRepo.loadDabStations() }
    }

    // ============ seek (FM/AM only) ============

    @Test
    fun `seek in FM mode delegates to fmAmController`() {
        every { fmNative.seek(any(), any()) } returns floatArrayOf(99.5f)
        controller.seek(forward = true)
        verify { fmNative.seek(any(), true) }
    }

    @Test
    fun `seek in DAB mode is a no-op (DAB has no analogue seek)`() {
        controller.setMode(FrequencyScaleView.RadioMode.DAB)
        controller.seek(forward = true)
        verify(exactly = 0) { fmNative.seek(any(), any()) }
    }

    // ============ isTunerAvailable ============

    @Test
    fun `isTunerAvailable for FM returns library-loaded flag`() {
        every { fmNative.isLibraryLoaded() } returns true
        assertTrue(controller.isTunerAvailable(FrequencyScaleView.RadioMode.FM))
        every { fmNative.isLibraryLoaded() } returns false
        assertFalse(controller.isTunerAvailable(FrequencyScaleView.RadioMode.FM))
    }

    @Test
    fun `isTunerAvailable for DAB consults dabController_isDabAvailable`() {
        every { dabTunerManager.isDabAvailable(context) } returns true
        assertTrue(controller.isTunerAvailable(FrequencyScaleView.RadioMode.DAB))
        every { dabTunerManager.isDabAvailable(context) } returns false
        assertFalse(controller.isTunerAvailable(FrequencyScaleView.RadioMode.DAB))
    }
}
