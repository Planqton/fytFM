package at.planqton.fytfm.controller

import android.content.Context
import android.graphics.Bitmap
import androidx.core.content.edit
import at.planqton.fytfm.dab.DabStation
import at.planqton.fytfm.dab.DabTunerManager
import at.planqton.fytfm.data.PresetRepository
import at.planqton.fytfm.data.RadioStation
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
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
import at.planqton.fytfm.dab.EpgData
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Tests for [DabController]. The controller coordinates [DabTunerManager]
 * (the OMRI/libirtdab.so wrapper) and projects its callbacks onto a
 * narrower controller-level surface plus state fields.
 *
 * Like FmAmController, the controller's value is in the **wiring** —
 * forwarding callbacks, persisting selected services, cycling presets.
 * We mock DabTunerManager so the OMRI Radio singleton never touches a
 * real USB tuner.
 *
 * Robolectric provides real SharedPreferences for the persistence checks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DabControllerTest {

    /** Subscribes to the SharedFlow on the test scheduler and returns a list
     *  the collector appends to. Call `runCurrent()` after the action under
     *  test before asserting. */
    private fun <T> TestScope.recordFlow(flow: Flow<T>): MutableList<T> {
        val events = mutableListOf<T>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            flow.collect { events.add(it) }
        }
        runCurrent()
        return events
    }

    private lateinit var context: Context
    private lateinit var dabTunerManager: DabTunerManager
    private lateinit var presetRepo: PresetRepository
    private lateinit var controller: DabController

    // Slots that capture each callback as `controller.initialize()` /
    // `controller.startRecording()` assigns it onto the (relaxed) mock.
    // Relaxed mocks return a default no-op lambda for property GETs but
    // don't store SETs — slots are how we recover the wired lambda.
    private val tunerReadySlot = slot<() -> Unit>()
    private val serviceStartedSlot = slot<(DabStation) -> Unit>()
    private val serviceStoppedSlot = slot<() -> Unit>()
    private val tunerErrorSlot = slot<(String) -> Unit>()
    private val dynamicLabelSlot = slot<(String) -> Unit>()
    private val dlPlusSlot = slot<(String?, String?) -> Unit>()
    private val slideshowSlot = slot<(android.graphics.Bitmap) -> Unit>()
    private val receptionStatsSlot = slot<(Boolean, String, Int) -> Unit>()
    private val audioStartedSlot = slot<(Int) -> Unit>()
    private val recordingStartedSlot = slot<() -> Unit>()
    private val recordingStoppedSlot = slot<(java.io.File) -> Unit>()
    private val recordingErrorSlot = slot<(String) -> Unit>()
    private val epgDataSlot = slot<(EpgData) -> Unit>()

    private fun makeStation(
        serviceId: Int,
        ensembleId: Int = 1,
        serviceLabel: String = "Mock Service",
        ensembleLabel: String = "Mock Ensemble",
    ) = DabStation(
        serviceId = serviceId,
        ensembleId = ensembleId,
        serviceLabel = serviceLabel,
        ensembleLabel = ensembleLabel,
        ensembleFrequencyKHz = 223936,
    )

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("fytfm_dab", Context.MODE_PRIVATE).edit { clear() }

        dabTunerManager = mockk(relaxed = true)
        presetRepo = mockk(relaxed = true)

        every { dabTunerManager.initialize(any()) } returns true
        every { dabTunerManager.tuneService(any(), any()) } returns true
        every { dabTunerManager.startRecording(any(), any()) } returns true
        every { dabTunerManager.isRecording() } returns false

        // Set up slot capture for every callback property the controller wires.
        every { dabTunerManager.onTunerReady = capture(tunerReadySlot) } just Runs
        every { dabTunerManager.onServiceStarted = capture(serviceStartedSlot) } just Runs
        every { dabTunerManager.onServiceStopped = capture(serviceStoppedSlot) } just Runs
        every { dabTunerManager.onTunerError = capture(tunerErrorSlot) } just Runs
        every { dabTunerManager.onDynamicLabel = capture(dynamicLabelSlot) } just Runs
        every { dabTunerManager.onDlPlus = capture(dlPlusSlot) } just Runs
        every { dabTunerManager.onSlideshow = capture(slideshowSlot) } just Runs
        every { dabTunerManager.onReceptionStats = capture(receptionStatsSlot) } just Runs
        every { dabTunerManager.onAudioStarted = capture(audioStartedSlot) } just Runs
        every { dabTunerManager.onRecordingStarted = capture(recordingStartedSlot) } just Runs
        every { dabTunerManager.onRecordingStopped = capture(recordingStoppedSlot) } just Runs
        every { dabTunerManager.onRecordingError = capture(recordingErrorSlot) } just Runs
        every { dabTunerManager.onEpgDataReceived = capture(epgDataSlot) } just Runs

        controller = DabController(context, dabTunerManager, presetRepo)
        controller.initialize()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ============ Power lifecycle ============

    @Test
    fun `togglePower from off calls powerOn and flips isDabOn true`() {
        assertFalse(controller.isDabOn)
        val result = controller.togglePower()
        assertTrue("togglePower returns true when newly on", result)
        assertTrue(controller.isDabOn)
        verify { dabTunerManager.initialize(context) }
    }

    @Test
    fun `togglePower from on calls powerOff and flips isDabOn false`() {
        controller.powerOn()
        assertTrue(controller.isDabOn)
        val result = controller.togglePower()
        assertFalse("togglePower returns false when newly off", result)
        assertFalse(controller.isDabOn)
        verify { dabTunerManager.deinitialize() }
    }

    @Test
    fun `powerOn returns false and stays off when DabTunerManager init fails`() {
        every { dabTunerManager.initialize(any()) } returns false
        val result = controller.powerOn()
        assertFalse(result)
        assertFalse("isDabOn must stay false on init failure", controller.isDabOn)
    }

    @Test
    fun `powerOff clears all current-service state fields`() {
        // Push some state into the controller via the wired callback.
        val station = makeStation(serviceId = 1234, serviceLabel = "Mock Ö1")
        serviceStartedSlot.captured.invoke(station)
        assertEquals(1234, controller.currentServiceId)
        assertEquals("Mock Ö1", controller.currentServiceLabel)

        controller.powerOn()
        controller.powerOff()
        assertEquals(0, controller.currentServiceId)
        assertEquals(0, controller.currentEnsembleId)
        assertNull(controller.currentServiceLabel)
        assertNull(controller.currentEnsembleLabel)
        assertNull(controller.currentDls)
        assertNull(controller.currentSlideshow)
    }

    @Test
    fun `powerOff calls stopService before deinitialize`() {
        controller.powerOn()
        controller.powerOff()
        verify { dabTunerManager.stopService() }
        verify { dabTunerManager.deinitialize() }
    }

    // ============ Service-started callback wiring ============

    @Test
    fun `onServiceStarted callback updates current-service state and persists ids`() = runTest {
        val station = makeStation(serviceId = 1001, ensembleId = 2, serviceLabel = "Mock FM4")

        val events = recordFlow(controller.events)
        serviceStartedSlot.captured.invoke(station)
        runCurrent()

        assertEquals(1001, controller.currentServiceId)
        assertEquals(2, controller.currentEnsembleId)
        assertEquals("Mock FM4", controller.currentServiceLabel)
        val event = events.filterIsInstance<DabEvent.ServiceStarted>().last()
        assertSame(station, event.station)

        // Persistence: saveLastService writes both ids.
        val prefs = context.getSharedPreferences("fytfm_dab", Context.MODE_PRIVATE)
        assertEquals(1001, prefs.getInt("last_dab_service_id", -1))
        assertEquals(2, prefs.getInt("last_dab_ensemble_id", -1))
    }

    @Test
    fun `onServiceStarted callback resets DLS and slideshow (fresh service)`() {
        // Pre-seed leftover DLS/slideshow via their callbacks.
        dynamicLabelSlot.captured.invoke("Old Song")
        val oldBitmap: Bitmap = mockk(relaxed = true)
        slideshowSlot.captured.invoke(oldBitmap)
        assertEquals("Old Song", controller.currentDls)
        assertSame(oldBitmap, controller.currentSlideshow)

        // Switching service must wipe both — they belong to the previous service.
        serviceStartedSlot.captured.invoke(makeStation(serviceId = 9999))
        assertNull("DLS must reset on new service", controller.currentDls)
        assertNull("Slideshow must reset on new service", controller.currentSlideshow)
    }

    // ============ Other callback forwarding ============

    @Test
    fun `onTunerReady callback fires DabEvent_TunerReady on the events flow`() = runTest {
        val events = recordFlow(controller.events)
        tunerReadySlot.captured.invoke()
        runCurrent()
        assertTrue(events.any { it is DabEvent.TunerReady })
    }

    @Test
    fun `onServiceStopped callback fires DabEvent_ServiceStopped`() = runTest {
        val events = recordFlow(controller.events)
        serviceStoppedSlot.captured.invoke()
        runCurrent()
        assertTrue(events.any { it is DabEvent.ServiceStopped })
    }

    @Test
    fun `onTunerError callback flips isDabOn false and emits TunerError`() = runTest {
        controller.powerOn()
        assertTrue(controller.isDabOn)

        val events = recordFlow(controller.events)
        tunerErrorSlot.captured.invoke("USB device disconnected")
        runCurrent()

        assertFalse("tuner error must flip isDabOn off", controller.isDabOn)
        val error = events.filterIsInstance<DabEvent.TunerError>().last()
        assertEquals("USB device disconnected", error.message)
    }

    @Test
    fun `onDynamicLabel callback updates currentDls and emits DynamicLabel`() = runTest {
        val events = recordFlow(controller.events)
        dynamicLabelSlot.captured.invoke("Now Playing: Song X")
        runCurrent()
        assertEquals("Now Playing: Song X", controller.currentDls)
        val event = events.filterIsInstance<DabEvent.DynamicLabel>().last()
        assertEquals("Now Playing: Song X", event.dls)
    }

    @Test
    fun `onDlPlus callback emits DlPlus with artist and title`() = runTest {
        val events = recordFlow(controller.events)
        dlPlusSlot.captured.invoke("Beatles", "Yesterday")
        runCurrent()
        val event = events.filterIsInstance<DabEvent.DlPlus>().last()
        assertEquals("Beatles", event.artist)
        assertEquals("Yesterday", event.title)
    }

    @Test
    fun `onSlideshow callback updates currentSlideshow and emits Slideshow`() = runTest {
        val events = recordFlow(controller.events)
        val bitmap: Bitmap = mockk(relaxed = true) {
            every { width } returns 320
            every { height } returns 240
        }
        slideshowSlot.captured.invoke(bitmap)
        runCurrent()
        assertSame(bitmap, controller.currentSlideshow)
        val event = events.filterIsInstance<DabEvent.Slideshow>().last()
        assertSame(bitmap, event.bitmap)
    }

    @Test
    fun `onReceptionStats callback emits ReceptionStats with sync, quality and SNR`() = runTest {
        val events = recordFlow(controller.events)
        receptionStatsSlot.captured.invoke(true, "good", 28)
        runCurrent()
        val event = events.filterIsInstance<DabEvent.ReceptionStats>().last()
        assertEquals(true, event.sync)
        assertEquals("good", event.quality)
        assertEquals(28, event.snr)
    }

    @Test
    fun `onAudioStarted callback emits AudioStarted with the session id`() = runTest {
        val events = recordFlow(controller.events)
        audioStartedSlot.captured.invoke(42)
        runCurrent()
        val event = events.filterIsInstance<DabEvent.AudioStarted>().last()
        assertEquals(42, event.audioSessionId)
    }

    // ============ Tune ============

    @Test
    fun `tuneService updates current ids and delegates to DabTunerManager`() {
        val result = controller.tuneService(serviceId = 5555, ensembleId = 3)
        assertTrue(result)
        assertEquals(5555, controller.currentServiceId)
        assertEquals(3, controller.currentEnsembleId)
        verify { dabTunerManager.tuneService(5555, 3) }
    }

    @Test
    fun `tuneService returns false when the underlying tune fails`() {
        every { dabTunerManager.tuneService(any(), any()) } returns false
        // Even on failure, the controller pre-sets its current ids.
        // This is current behaviour — pinned so a refactor either keeps
        // it or deliberately changes it.
        val result = controller.tuneService(7777, 9)
        assertFalse(result)
        assertEquals(7777, controller.currentServiceId)
    }

    @Test
    fun `tuneStation forwards to tuneService with station ids`() {
        val station = RadioStation(
            frequency = 0f, isDab = true,
            serviceId = 8888, ensembleId = 4,
        )
        controller.tuneStation(station)
        verify { dabTunerManager.tuneService(8888, 4) }
    }

    // ============ skipStation ============

    @Test
    fun `skipStation forward cycles through DAB presets`() {
        val stations = listOf(
            RadioStation(frequency = 0f, isDab = true, serviceId = 100, name = "A"),
            RadioStation(frequency = 0f, isDab = true, serviceId = 200, name = "B"),
            RadioStation(frequency = 0f, isDab = true, serviceId = 300, name = "C"),
        )
        every { presetRepo.loadDabStations() } returns stations
        controller.tuneService(100, 1) // start at first

        val result = controller.skipStation(forward = true)
        assertEquals(200, result?.serviceId)
        assertEquals(200, controller.currentServiceId)
    }

    @Test
    fun `skipStation forward wraps from last to first`() {
        val stations = listOf(
            RadioStation(frequency = 0f, isDab = true, serviceId = 100),
            RadioStation(frequency = 0f, isDab = true, serviceId = 200),
        )
        every { presetRepo.loadDabStations() } returns stations
        controller.tuneService(200, 1)
        val result = controller.skipStation(forward = true)
        assertEquals(100, result?.serviceId)
    }

    @Test
    fun `skipStation backward wraps from first to last`() {
        val stations = listOf(
            RadioStation(frequency = 0f, isDab = true, serviceId = 100),
            RadioStation(frequency = 0f, isDab = true, serviceId = 200),
        )
        every { presetRepo.loadDabStations() } returns stations
        controller.tuneService(100, 1)
        val result = controller.skipStation(forward = false)
        assertEquals(200, result?.serviceId)
    }

    @Test
    fun `skipStation returns null when preset list is empty`() {
        every { presetRepo.loadDabStations() } returns emptyList()
        assertNull(controller.skipStation(forward = true))
    }

    @Test
    fun `skipStation lands on first preset when current id is unknown`() {
        every { presetRepo.loadDabStations() } returns listOf(
            RadioStation(frequency = 0f, isDab = true, serviceId = 100),
            RadioStation(frequency = 0f, isDab = true, serviceId = 200),
        )
        controller.tuneService(99999, 1) // not in preset list
        val result = controller.skipStation(forward = true)
        assertEquals(100, result?.serviceId)
    }

    // ============ Persistence ============

    @Test
    fun `saveLastService writes both ids to prefs`() {
        controller.saveLastService(serviceId = 4321, ensembleId = 7)
        val prefs = context.getSharedPreferences("fytfm_dab", Context.MODE_PRIVATE)
        assertEquals(4321, prefs.getInt("last_dab_service_id", -1))
        assertEquals(7, prefs.getInt("last_dab_ensemble_id", -1))
    }

    @Test
    fun `loadLastService returns 0,0 on fresh prefs`() {
        val (sid, eid) = controller.loadLastService()
        assertEquals(0, sid)
        assertEquals(0, eid)
    }

    @Test
    fun `loadLastService returns persisted ids`() {
        controller.saveLastService(1234, 5)
        val (sid, eid) = controller.loadLastService()
        assertEquals(1234, sid)
        assertEquals(5, eid)
    }

    @Test
    fun `initialize pre-populates currentServiceId from persisted prefs`() {
        // Persist via the existing controller so the prefs file exists,
        // then build a fresh controller — its initialize() should read them.
        controller.saveLastService(serviceId = 9001, ensembleId = 3)
        val freshTuner: DabTunerManager = mockk(relaxed = true)
        every { freshTuner.initialize(any()) } returns true
        val fresh = DabController(context, freshTuner, presetRepo)

        assertEquals("before initialize, fields are zero", 0, fresh.currentServiceId)

        fresh.initialize()

        assertEquals(9001, fresh.currentServiceId)
        assertEquals(3, fresh.currentEnsembleId)
    }

    @Test
    fun `initialize does not overwrite currentServiceId when no service is persisted`() {
        // Fresh prefs (sid==0) → initialize should leave the fields at 0,
        // not crash trying to assign anything bogus.
        val freshTuner: DabTunerManager = mockk(relaxed = true)
        val fresh = DabController(context, freshTuner, presetRepo)
        fresh.initialize()
        assertEquals(0, fresh.currentServiceId)
        assertEquals(0, fresh.currentEnsembleId)
    }

    @Test
    fun `tuneService writes IDs predictively before the tuner confirms`() {
        // The Phase B currentDabServiceId migration relies on this — MainActivity
        // reads dabController.currentServiceId immediately after tuneService and
        // expects to see the new value, not the old one (or 0).
        every { dabTunerManager.tuneService(any(), any()) } returns true
        controller.tuneService(serviceId = 4242, ensembleId = 7)
        assertEquals(4242, controller.currentServiceId)
        assertEquals(7, controller.currentEnsembleId)
    }

    // ============ tuneToLastOrFirst ============

    @Test
    fun `tuneToLastOrFirst is a no-op when preset list is empty`() {
        every { presetRepo.loadDabStations() } returns emptyList()
        controller.tuneToLastOrFirst()
        verify(exactly = 0) { dabTunerManager.tuneService(any(), any()) }
    }

    @Test
    fun `tuneToLastOrFirst tunes the last-saved service when present in presets`() {
        val stations = listOf(
            RadioStation(frequency = 0f, isDab = true, serviceId = 100, name = "A"),
            RadioStation(frequency = 0f, isDab = true, serviceId = 200, name = "B"),
        )
        every { presetRepo.loadDabStations() } returns stations
        controller.saveLastService(serviceId = 200, ensembleId = 1)
        controller.tuneToLastOrFirst()
        verify { dabTunerManager.tuneService(200, any()) }
    }

    @Test
    fun `tuneToLastOrFirst falls back to first when last-saved id is missing from presets`() {
        val stations = listOf(
            RadioStation(frequency = 0f, isDab = true, serviceId = 100),
            RadioStation(frequency = 0f, isDab = true, serviceId = 200),
        )
        every { presetRepo.loadDabStations() } returns stations
        controller.saveLastService(serviceId = 9999, ensembleId = 1) // not in presets
        controller.tuneToLastOrFirst()
        verify { dabTunerManager.tuneService(100, any()) }
    }

    @Test
    fun `tuneToLastOrFirst falls back to first when no service id is saved`() {
        val stations = listOf(
            RadioStation(frequency = 0f, isDab = true, serviceId = 100),
            RadioStation(frequency = 0f, isDab = true, serviceId = 200),
        )
        every { presetRepo.loadDabStations() } returns stations
        // No saveLastService called → loadLastService returns (0, 0).
        controller.tuneToLastOrFirst()
        verify { dabTunerManager.tuneService(100, any()) }
    }

    // ============ Recording ============

    @Test
    fun `startRecording forwards path and folderUri to DabTunerManager`() {
        val result = controller.startRecording(context, "content://example/folder")
        assertTrue(result)
        verify { dabTunerManager.startRecording(context, "content://example/folder") }
    }

    @Test
    fun `recording callbacks forward through the controller`() = runTest {
        val events = recordFlow(controller.events)

        // Recording callbacks on the backend are wired up front in
        // setupCallbacks (no longer reattached on startRecording), so we can
        // fire them directly without calling startRecording.
        recordingStartedSlot.captured.invoke()
        val file = java.io.File("/tmp/recording.mp3")
        recordingStoppedSlot.captured.invoke(file)
        recordingErrorSlot.captured.invoke("disk full")
        runCurrent()

        assertTrue(events.any { it is DabEvent.RecordingStarted })
        val stopped = events.filterIsInstance<DabEvent.RecordingStopped>().last()
        assertSame(file, stopped.file)
        val error = events.filterIsInstance<DabEvent.RecordingError>().last()
        assertEquals("disk full", error.error)
    }

    @Test
    fun `isRecording delegates to DabTunerManager`() {
        every { dabTunerManager.isRecording() } returns true
        assertTrue(controller.isRecording())
        every { dabTunerManager.isRecording() } returns false
        assertFalse(controller.isRecording())
    }

    // ============ Pass-through helpers ============

    @Test
    fun `getCurrentService delegates to DabTunerManager`() {
        val s = makeStation(serviceId = 999)
        every { dabTunerManager.getCurrentService() } returns s
        assertSame(s, controller.getCurrentService())
    }

    @Test
    fun `hasTuner delegates to DabTunerManager`() {
        every { dabTunerManager.hasTuner() } returns true
        assertTrue(controller.hasTuner())
        every { dabTunerManager.hasTuner() } returns false
        assertFalse(controller.hasTuner())
    }

    @Test
    fun `isDabAvailable delegates to DabTunerManager`() {
        every { dabTunerManager.isDabAvailable(context) } returns true
        assertTrue(controller.isDabAvailable())
    }

    @Test
    fun `getAudioSessionId delegates to DabTunerManager`() {
        every { dabTunerManager.getAudioSessionId() } returns 7
        assertEquals(7, controller.getAudioSessionId())
    }

    @Test
    fun `startScan and stopScan delegate to DabTunerManager`() {
        val listener: at.planqton.fytfm.dab.DabScanListener = mockk(relaxed = true)
        controller.startScan(listener)
        verify { dabTunerManager.startScan(listener) }
        controller.stopScan()
        verify { dabTunerManager.stopScan() }
    }

    // ============ Backend swap (mock vs real DAB tuner) ============

    @Test
    fun `setBackend with same backend is a no-op`() {
        // No exception, no callback churn.
        controller.setBackend(dabTunerManager)
        assertSame(dabTunerManager, controller.backend)
    }

    @Test
    fun `setBackend swaps the active backend`() {
        val newBackend = mockk<at.planqton.fytfm.dab.DabTunerBackend>(relaxed = true)
        controller.setBackend(newBackend)
        assertSame(newBackend, controller.backend)
    }

    @Test
    fun `setBackend clears the previous backend's callbacks`() {
        val newBackend = mockk<at.planqton.fytfm.dab.DabTunerBackend>(relaxed = true)
        // Re-stub the OLD backend's setters so we can verify they're nulled out.
        every { dabTunerManager.onTunerReady = null } just Runs
        every { dabTunerManager.onServiceStarted = null } just Runs
        every { dabTunerManager.onDynamicLabel = null } just Runs

        controller.setBackend(newBackend)

        verify { dabTunerManager.onTunerReady = null }
        verify { dabTunerManager.onServiceStarted = null }
        verify { dabTunerManager.onDynamicLabel = null }
    }

    @Test
    fun `setBackend wires controller callbacks onto the new backend`() = runTest {
        val newReadySlot = slot<() -> Unit>()
        val newServiceStartedSlot = slot<(DabStation) -> Unit>()
        val newBackend = mockk<at.planqton.fytfm.dab.DabTunerBackend>(relaxed = true)
        every { newBackend.onTunerReady = capture(newReadySlot) } just Runs
        every { newBackend.onServiceStarted = capture(newServiceStartedSlot) } just Runs

        controller.setBackend(newBackend)

        // After setBackend the controller's events flow now mirrors the
        // *new* backend's callbacks.
        val events = recordFlow(controller.events)

        newReadySlot.captured.invoke()
        runCurrent()
        assertTrue("DabEvent.TunerReady fires from new backend", events.any { it is DabEvent.TunerReady })

        val s = makeStation(serviceId = 42)
        newServiceStartedSlot.captured.invoke(s)
        runCurrent()
        val serviceStarted = events.filterIsInstance<DabEvent.ServiceStarted>().last()
        assertEquals(42, serviceStarted.station.serviceId)
    }

    @Test
    fun `setBackend resets transient service state`() {
        // Drive the original backend through onServiceStarted to populate state.
        controller.powerOn()
        serviceStartedSlot.captured.invoke(
            makeStation(serviceId = 99, serviceLabel = "Old Service"),
        )
        assertEquals(99, controller.currentServiceId)
        assertEquals("Old Service", controller.currentServiceLabel)

        val newBackend = mockk<at.planqton.fytfm.dab.DabTunerBackend>(relaxed = true)
        controller.setBackend(newBackend)

        // Service IDs from the old backend mean nothing on the new one.
        assertEquals(0, controller.currentServiceId)
        assertEquals(0, controller.currentEnsembleId)
        assertNull(controller.currentServiceLabel)
        assertNull(controller.currentDls)
        assertNull(controller.currentSlideshow)
    }
}
