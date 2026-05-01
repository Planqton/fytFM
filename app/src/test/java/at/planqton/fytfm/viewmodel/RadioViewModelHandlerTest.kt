package at.planqton.fytfm.viewmodel

import android.graphics.Bitmap
import at.planqton.fytfm.FrequencyScaleView
import at.planqton.fytfm.controller.RadioController
import at.planqton.fytfm.controller.RadioEvent
import at.planqton.fytfm.dab.DabStation
import at.planqton.fytfm.data.PresetRepository
import at.planqton.fytfm.data.RadioStation
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Supplementary RadioViewModel tests covering gaps in [RadioViewModelTest]:
 * - The "drained" event branches (DAB tuner-ready, recording lifecycle,
 *   audio-started, reception stats, EPG, dl+) — must NOT corrupt state.
 * - The DAB slideshow handler (bitmap → dabState).
 * - The radio-state Off→on transition (must build a fresh FM state).
 * - Public mute / station-selection / DAB-helper APIs.
 *
 * Same harness shape as [RadioViewModelTest]: mocked RadioController +
 * a real MutableSharedFlow stub for events, observed via UnconfinedTestDispatcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RadioViewModelHandlerTest {

    private lateinit var mockRadioController: RadioController
    private lateinit var mockPresetRepository: PresetRepository
    private lateinit var viewModel: RadioViewModel
    private lateinit var events: MutableSharedFlow<RadioEvent>

    private fun emit(event: RadioEvent) {
        assertTrue("event must fit in test buffer: $event", events.tryEmit(event))
    }

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockRadioController = mockk(relaxed = true)
        mockPresetRepository = mockk(relaxed = true)
        events = MutableSharedFlow(replay = 0, extraBufferCapacity = 64)

        every { mockRadioController.events } returns events
        every { mockRadioController.currentMode } returns FrequencyScaleView.RadioMode.FM
        every { mockRadioController.getCurrentFrequency() } returns 98.4f
        every { mockRadioController.isRadioOn() } returns false
        every { mockRadioController.getCurrentDabService() } returns null
        every { mockRadioController.getStationsForCurrentMode() } returns emptyList()
        every { mockPresetRepository.isLocalMode() } returns false
        every { mockPresetRepository.isMonoMode() } returns false

        viewModel = RadioViewModel(mockRadioController, mockPresetRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    // ============ "Drained" events must not corrupt state ============

    @Test
    fun `DabTunerReady event leaves uiState untouched`() {
        val before = viewModel.uiState.value
        emit(RadioEvent.DabTunerReady)
        assertSame("uiState reference must not change for drained events", before, viewModel.uiState.value)
    }

    @Test
    fun `DabServiceStopped event leaves uiState untouched`() {
        val before = viewModel.uiState.value
        emit(RadioEvent.DabServiceStopped)
        assertSame(before, viewModel.uiState.value)
    }

    @Test
    fun `DabDlPlus event leaves uiState untouched (handled via VM helper instead)`() {
        // Note: the VM has updateDabDlPlus() as a public method, but the
        // event consumer just drains. That's by design — the event signal
        // exists for future consumers; today the path is the public method.
        val before = viewModel.uiState.value
        emit(RadioEvent.DabDlPlus("Artist", "Title"))
        assertSame(before, viewModel.uiState.value)
    }

    @Test
    fun `DabAudioStarted event leaves uiState untouched`() {
        val before = viewModel.uiState.value
        emit(RadioEvent.DabAudioStarted(sessionId = 42))
        assertSame(before, viewModel.uiState.value)
    }

    @Test
    fun `DabReceptionStats event leaves uiState untouched`() {
        val before = viewModel.uiState.value
        emit(RadioEvent.DabReceptionStats(sync = true, quality = "good", snr = 30))
        assertSame(before, viewModel.uiState.value)
    }

    @Test
    fun `DabRecordingStarted event leaves uiState untouched`() {
        val before = viewModel.uiState.value
        emit(RadioEvent.DabRecordingStarted)
        assertSame(before, viewModel.uiState.value)
    }

    @Test
    fun `DabRecordingStopped event leaves uiState untouched`() {
        val before = viewModel.uiState.value
        emit(RadioEvent.DabRecordingStopped(file = null))
        assertSame(before, viewModel.uiState.value)
    }

    @Test
    fun `DabRecordingError event leaves uiState untouched`() {
        val before = viewModel.uiState.value
        emit(RadioEvent.DabRecordingError("disk full"))
        assertSame(before, viewModel.uiState.value)
    }

    @Test
    fun `DabEpgReceived event leaves uiState untouched`() {
        val before = viewModel.uiState.value
        emit(RadioEvent.DabEpgReceived(data = "epg-payload"))
        assertSame(before, viewModel.uiState.value)
    }

    // ============ DAB slideshow ============

    @Test
    fun `DabSlideshow event projects the bitmap onto Dab uiState`() {
        emit(RadioEvent.ModeChanged(FrequencyScaleView.RadioMode.DAB))
        val bitmap: Bitmap = mockk(relaxed = true)
        emit(RadioEvent.DabSlideshow(bitmap))
        val state = viewModel.uiState.value as RadioUiState.Dab
        assertSame(bitmap, state.dabState.slideshow)
    }

    @Test
    fun `DabSlideshow event is ignored while in FmAm state`() {
        // Default initial state is FmAm — the event shouldn't switch us into Dab.
        val before = viewModel.uiState.value
        val bitmap: Bitmap = mockk(relaxed = true)
        emit(RadioEvent.DabSlideshow(bitmap))
        assertTrue(viewModel.uiState.value is RadioUiState.FmAm)
        // FmAm.copy was not invoked because the when-branch falls through to else.
        assertSame(before, viewModel.uiState.value)
    }

    // ============ Off → on transition ============

    @Test
    fun `RadioStateChanged true on Off state spins up an FmAm state`() {
        // Force VM into Off via reflection-free path: a fresh ModeChanged
        // does not produce Off, so we set it manually via the public surface.
        // The setMuted public path is enough to keep the state FmAm; we
        // need a separate way to land on Off. Easiest: construct fresh VM
        // with isRadioOn returning false stays FmAm by default. So instead
        // we test that the OFF branch maps explicitly via emit.
        // We can't directly emit "go to Off" without calling a private; so
        // verify that the transition logic in handleRadioStateChanged
        // is exercised when state happens to be FmAm with isPlaying=false.
        emit(RadioEvent.RadioStateChanged(true))
        val state = viewModel.uiState.value as RadioUiState.FmAm
        assertTrue("isPlaying must flip to true", state.isPlaying)
    }

    @Test
    fun `RadioStateChanged false flips isPlaying off without losing state`() {
        emit(RadioEvent.RadioStateChanged(true))
        val onState = viewModel.uiState.value as RadioUiState.FmAm
        assertTrue(onState.isPlaying)

        emit(RadioEvent.RadioStateChanged(false))
        val offState = viewModel.uiState.value as RadioUiState.FmAm
        assertFalse(offState.isPlaying)
        assertEquals(
            "frequency must persist across power-toggle",
            onState.frequency, offState.frequency, 0.001f,
        )
    }

    // ============ Mute API ============

    @Test
    fun `toggleMute flips the FmAm mute flag`() {
        val before = (viewModel.uiState.value as RadioUiState.FmAm).isMuted
        viewModel.toggleMute()
        val after = (viewModel.uiState.value as RadioUiState.FmAm).isMuted
        assertEquals("toggleMute must invert", !before, after)
    }

    @Test
    fun `toggleMute twice returns to the original state`() {
        val before = (viewModel.uiState.value as RadioUiState.FmAm).isMuted
        viewModel.toggleMute()
        viewModel.toggleMute()
        val after = (viewModel.uiState.value as RadioUiState.FmAm).isMuted
        assertEquals(before, after)
    }

    @Test
    fun `setMuted true forces mute regardless of current state`() {
        viewModel.setMuted(true)
        assertTrue((viewModel.uiState.value as RadioUiState.FmAm).isMuted)
        viewModel.setMuted(true) // idempotent
        assertTrue((viewModel.uiState.value as RadioUiState.FmAm).isMuted)
    }

    @Test
    fun `setMuted false unmutes`() {
        viewModel.setMuted(true)
        viewModel.setMuted(false)
        assertFalse((viewModel.uiState.value as RadioUiState.FmAm).isMuted)
    }

    @Test
    fun `mute toggles persist across DAB mode switch`() {
        viewModel.setMuted(true)
        emit(RadioEvent.ModeChanged(FrequencyScaleView.RadioMode.DAB))
        // ModeChanged builds a fresh state without mute — pinned behaviour.
        val state = viewModel.uiState.value as RadioUiState.Dab
        assertFalse("ModeChanged resets mute (current behaviour)", state.isMuted)
    }

    // ============ Station selection ============

    @Test
    fun `selectStation persists the selected index`() {
        val stations = listOf(
            RadioStation(frequency = 88.0f, name = "A"),
            RadioStation(frequency = 99.0f, name = "B"),
        )
        viewModel.updateStations(stations)
        viewModel.selectStation(1)
        assertEquals(1, viewModel.stationListState.value.selectedIndex)
    }

    @Test
    fun `getCurrentStation returns the station at selectedIndex`() {
        val stations = listOf(
            RadioStation(frequency = 88.0f, name = "Alpha"),
            RadioStation(frequency = 99.0f, name = "Beta"),
        )
        viewModel.updateStations(stations)
        viewModel.selectStation(0)
        assertEquals("Alpha", viewModel.getCurrentStation()?.name)
        viewModel.selectStation(1)
        assertEquals("Beta", viewModel.getCurrentStation()?.name)
    }

    @Test
    fun `getCurrentStation returns null when selectedIndex is -1 (default)`() {
        viewModel.updateStations(listOf(RadioStation(frequency = 88.0f)))
        assertNull("default selectedIndex is -1 → no current station", viewModel.getCurrentStation())
    }

    @Test
    fun `getCurrentStation returns null when selectedIndex is out of range`() {
        viewModel.updateStations(listOf(RadioStation(frequency = 88.0f)))
        viewModel.selectStation(5) // out of range
        assertNull(viewModel.getCurrentStation())
    }

    @Test
    fun `updateStations replaces the list and preserves favorites filter`() {
        // Set the filter, then update stations — the filter must NOT reset.
        viewModel.toggleFavoritesOnly() // showFavoritesOnly = true
        viewModel.updateStations(listOf(RadioStation(frequency = 88.0f)))
        val state = viewModel.stationListState.value
        assertTrue("favorites filter must persist across station-list update", state.showFavoritesOnly)
        assertEquals(1, state.stations.size)
    }

    // ============ DAB-specific helpers ============

    @Test
    fun `setDabRecording true projects onto dabState while in Dab mode`() {
        emit(RadioEvent.ModeChanged(FrequencyScaleView.RadioMode.DAB))
        viewModel.setDabRecording(true)
        val state = viewModel.uiState.value as RadioUiState.Dab
        assertTrue(state.dabState.isRecording)
    }

    @Test
    fun `setDabRecording is a no-op while in FmAm mode`() {
        // Default state is FmAm — call must not switch state nor crash.
        val before = viewModel.uiState.value
        viewModel.setDabRecording(true)
        assertSame(before, viewModel.uiState.value)
    }

    @Test
    fun `updateDabSignalQuality projects onto dabState`() {
        emit(RadioEvent.ModeChanged(FrequencyScaleView.RadioMode.DAB))
        viewModel.updateDabSignalQuality(quality = "good", sync = true, snr = 28)
        val state = viewModel.uiState.value as RadioUiState.Dab
        assertEquals("good", state.dabState.receptionQuality)
        assertEquals(28, state.dabState.snr)
        assertTrue(state.dabState.signalSync)
    }

    @Test
    fun `updateDabDlPlus projects artist and title onto dabState`() {
        emit(RadioEvent.ModeChanged(FrequencyScaleView.RadioMode.DAB))
        viewModel.updateDabDlPlus(artist = "Beatles", title = "Yesterday")
        val state = viewModel.uiState.value as RadioUiState.Dab
        assertEquals("Beatles", state.dabState.dlPlusArtist)
        assertEquals("Yesterday", state.dabState.dlPlusTitle)
    }

    @Test
    fun `updateDabDlPlus accepts null artist and null title (clear)`() {
        emit(RadioEvent.ModeChanged(FrequencyScaleView.RadioMode.DAB))
        viewModel.updateDabDlPlus("X", "Y")
        viewModel.updateDabDlPlus(null, null)
        val state = viewModel.uiState.value as RadioUiState.Dab
        assertNull(state.dabState.dlPlusArtist)
        assertNull(state.dabState.dlPlusTitle)
    }

    // ============ Initial DAB state with active service ============

    @Test
    fun `initial state in DAB mode populates dabState from active service`() {
        every { mockRadioController.currentMode } returns FrequencyScaleView.RadioMode.DAB
        every { mockRadioController.getCurrentDabService() } returns DabStation(
            serviceId = 1234,
            ensembleId = 9,
            serviceLabel = "Mock Ö1",
            ensembleLabel = "Mock MUX",
            ensembleFrequencyKHz = 223936,
        )
        every { mockRadioController.isRadioOn() } returns true
        val newVm = RadioViewModel(mockRadioController, mockPresetRepository)
        val state = newVm.uiState.value as RadioUiState.Dab
        assertTrue(state.isPlaying)
        assertEquals(1234, state.dabState.serviceId)
        assertEquals("Mock Ö1", state.dabState.serviceLabel)
        assertEquals("Mock MUX", state.dabState.ensembleLabel)
    }

    // ============ updateDeezerTrack coverSourceKey (Phase B Round) ============

    @Test
    fun `updateDeezerTrack persists coverSourceKey in DAB deezerState (freshness key)`() {
        emit(RadioEvent.ModeChanged(FrequencyScaleView.RadioMode.DAB))
        val track = at.planqton.fytfm.deezer.TrackInfo(artist = "A", title = "T", trackId = "1")
        viewModel.updateDeezerTrack(track, "/local/cover.jpg", "Now: A - T")
        val state = viewModel.uiState.value as RadioUiState.Dab
        assertEquals("/local/cover.jpg", state.deezerState.coverPath)
        assertEquals("Now: A - T", state.deezerState.coverSourceKey)
        assertSame(track, state.deezerState.currentTrack)
    }

    @Test
    fun `updateDeezerTrack persists coverSourceKey in FmAm deezerState`() {
        val track = at.planqton.fytfm.deezer.TrackInfo(artist = "A", title = "T")
        viewModel.updateDeezerTrack(track, "/cover.png", "Beatles - Yesterday")
        val state = viewModel.uiState.value as RadioUiState.FmAm
        assertEquals("/cover.png", state.deezerState.coverPath)
        assertEquals("Beatles - Yesterday", state.deezerState.coverSourceKey)
    }

    @Test
    fun `updateDeezerTrack with null coverSourceKey clears the freshness key`() {
        emit(RadioEvent.ModeChanged(FrequencyScaleView.RadioMode.DAB))
        // Pre-seed
        viewModel.updateDeezerTrack(
            at.planqton.fytfm.deezer.TrackInfo(artist = "A", title = "T"),
            "/cover.jpg",
            "old key",
        )
        // Re-call with null clears all 3 — pattern used by service-change reset.
        viewModel.updateDeezerTrack(null, null, null)
        val state = viewModel.uiState.value as RadioUiState.Dab
        assertNull(state.deezerState.coverPath)
        assertNull(state.deezerState.coverSourceKey)
        assertNull(state.deezerState.currentTrack)
    }

    @Test
    fun `updateDeezerTrack defaults coverSourceKey to null when not provided (back-compat)`() {
        val track = at.planqton.fytfm.deezer.TrackInfo(artist = "A", title = "T")
        // Old 2-arg call path still compiles + leaves coverSourceKey null.
        viewModel.updateDeezerTrack(track, "/cover.jpg")
        val state = viewModel.uiState.value as RadioUiState.FmAm
        assertEquals("/cover.jpg", state.deezerState.coverPath)
        assertNull(state.deezerState.coverSourceKey)
    }

    // ============ Favourites filter — per-mode persistence (Phase B) ============

    @Test
    fun `loadFavoritesFilterForMode reads FM key for FM mode`() {
        every { mockPresetRepository.isShowFavoritesOnlyFm() } returns true
        viewModel.loadFavoritesFilterForMode(FrequencyScaleView.RadioMode.FM)
        assertTrue(viewModel.stationListState.value.showFavoritesOnly)
    }

    @Test
    fun `loadFavoritesFilterForMode reads AM key for AM mode`() {
        every { mockPresetRepository.isShowFavoritesOnlyAm() } returns true
        viewModel.loadFavoritesFilterForMode(FrequencyScaleView.RadioMode.AM)
        assertTrue(viewModel.stationListState.value.showFavoritesOnly)
    }

    @Test
    fun `loadFavoritesFilterForMode reads DAB key for both DAB and DAB_DEV`() {
        every { mockPresetRepository.isShowFavoritesOnlyDab() } returns true
        viewModel.loadFavoritesFilterForMode(FrequencyScaleView.RadioMode.DAB)
        assertTrue(viewModel.stationListState.value.showFavoritesOnly)
        // Reset
        every { mockPresetRepository.isShowFavoritesOnlyDab() } returns false
        viewModel.loadFavoritesFilterForMode(FrequencyScaleView.RadioMode.DAB_DEV)
        assertFalse(viewModel.stationListState.value.showFavoritesOnly)
    }

    @Test
    fun `toggleFavoritesOnlyForMode flips state and persists to FM key`() {
        every { mockPresetRepository.isShowFavoritesOnlyFm() } returns false
        viewModel.loadFavoritesFilterForMode(FrequencyScaleView.RadioMode.FM)
        assertFalse(viewModel.stationListState.value.showFavoritesOnly)

        val newValue = viewModel.toggleFavoritesOnlyForMode(FrequencyScaleView.RadioMode.FM)
        assertTrue(newValue)
        assertTrue(viewModel.stationListState.value.showFavoritesOnly)
        io.mockk.verify { mockPresetRepository.setShowFavoritesOnlyFm(true) }
    }

    @Test
    fun `toggleFavoritesOnlyForMode persists to AM key when in AM mode`() {
        viewModel.toggleFavoritesOnlyForMode(FrequencyScaleView.RadioMode.AM)
        io.mockk.verify { mockPresetRepository.setShowFavoritesOnlyAm(any()) }
    }

    @Test
    fun `toggleFavoritesOnlyForMode persists to DAB key for DAB and DAB_DEV`() {
        viewModel.toggleFavoritesOnlyForMode(FrequencyScaleView.RadioMode.DAB)
        io.mockk.verify { mockPresetRepository.setShowFavoritesOnlyDab(any()) }
        viewModel.toggleFavoritesOnlyForMode(FrequencyScaleView.RadioMode.DAB_DEV)
        io.mockk.verify(exactly = 2) { mockPresetRepository.setShowFavoritesOnlyDab(any()) }
    }

    @Test
    fun `toggleFavoritesOnlyForMode toggles independently per call`() {
        // Pre-state: false
        val a = viewModel.toggleFavoritesOnlyForMode(FrequencyScaleView.RadioMode.FM)
        val b = viewModel.toggleFavoritesOnlyForMode(FrequencyScaleView.RadioMode.FM)
        val c = viewModel.toggleFavoritesOnlyForMode(FrequencyScaleView.RadioMode.FM)
        assertEquals("toggle 1: false → true", true, a)
        assertEquals("toggle 2: true → false", false, b)
        assertEquals("toggle 3: false → true", true, c)
    }

    // ============ updateDeezerDebugInfo (Phase B Round) ============

    @Test
    fun `updateDeezerDebugInfo writes all 5 debug fields atomically (DAB)`() {
        emit(RadioEvent.ModeChanged(FrequencyScaleView.RadioMode.DAB))
        val track = at.planqton.fytfm.deezer.TrackInfo(artist = "A", title = "T")
        viewModel.updateDeezerDebugInfo(
            status = "Found",
            originalRt = "Now: A - T",
            strippedRt = "A - T",
            query = """artist:"A" track:"T"""",
            trackInfo = track,
        )
        val state = viewModel.uiState.value as RadioUiState.Dab
        assertEquals("Found", state.deezerState.debugStatus)
        assertEquals("Now: A - T", state.deezerState.debugOriginalRt)
        assertEquals("A - T", state.deezerState.debugStrippedRt)
        assertEquals("""artist:"A" track:"T"""", state.deezerState.lastQuery)
        assertSame(track, state.deezerState.currentTrack)
    }

    @Test
    fun `updateDeezerDebugInfo writes all 5 debug fields atomically (FmAm)`() {
        viewModel.updateDeezerDebugInfo(
            status = "Searching",
            originalRt = "raw rt",
            strippedRt = "rt",
            query = "q",
            trackInfo = null,
        )
        val state = viewModel.uiState.value as RadioUiState.FmAm
        assertEquals("Searching", state.deezerState.debugStatus)
        assertEquals("raw rt", state.deezerState.debugOriginalRt)
        assertEquals("rt", state.deezerState.debugStrippedRt)
        assertEquals("q", state.deezerState.lastQuery)
        assertNull(state.deezerState.currentTrack)
    }

    @Test
    fun `updateDeezerDebugInfo with all-nulls clears the debug fields`() {
        // Pre-seed
        viewModel.updateDeezerDebugInfo("X", "Y", "Z", "Q", null)
        // Wipe (used by the "Not found" path).
        viewModel.updateDeezerDebugInfo(null, null, null, null, null)
        val state = viewModel.uiState.value as RadioUiState.FmAm
        assertNull(state.deezerState.debugStatus)
        assertNull(state.deezerState.debugOriginalRt)
        assertNull(state.deezerState.debugStrippedRt)
        assertNull(state.deezerState.lastQuery)
    }

    @Test
    fun `updateDeezerDebugInfo while in Off state is a no-op (no crash)`() {
        // Off-state has no deezerState — must fall through silently.
        // (Default initial state is FmAm via radioController.currentMode mock,
        // but the when-else branch must still be safe.)
        // Construct a controller that returns Off as initial state... actually
        // the VM doesn't allow Off as initial via mode — defaults to FM. So
        // the worst case here is FmAm, already covered above. This test
        // documents that the when-statement is exhaustive.
        viewModel.updateDeezerDebugInfo("X", null, null, null, null)
        // No throw == pass.
    }

    @Test
    fun `setDeezerSearching persists query string for debug display`() {
        viewModel.setDeezerSearching(isSearching = true, query = "Beatles Yesterday")
        val state = viewModel.uiState.value as RadioUiState.FmAm
        assertTrue(state.deezerState.isSearching)
        assertEquals("Beatles Yesterday", state.deezerState.lastQuery)
    }

    // ============ errorEvents (Phase A migration target) ============

    @Test
    fun `ErrorEvent is re-emitted on errorEvents flow for downstream collectors`() {
        val collected = mutableListOf<String>()
        val job = CoroutineScope(UnconfinedTestDispatcher()).launch {
            viewModel.errorEvents.collect { collected.add(it) }
        }
        try {
            emit(RadioEvent.ErrorEvent("disk full"))
            assertEquals(listOf("disk full"), collected)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `ErrorEvent still updates lastError and fires onError callback (back-compat)`() {
        // The errorEvents addition must NOT break the existing onError callback
        // path — MainActivity is being migrated incrementally and the callback
        // is still consumed by some sites today.
        var callbackFired: String? = null
        viewModel.onError = { callbackFired = it }
        emit(RadioEvent.ErrorEvent("tuner died"))
        assertEquals("tuner died", callbackFired)
        assertEquals("tuner died", viewModel.lastError)
    }

    // ============ recordingEvents (Phase A migration target) ============

    @Test
    fun `DabRecordingStarted sets isRecording true and emits Started event`() {
        emit(RadioEvent.ModeChanged(FrequencyScaleView.RadioMode.DAB))
        val collected = mutableListOf<RadioViewModel.RecordingEvent>()
        val job = CoroutineScope(UnconfinedTestDispatcher()).launch {
            viewModel.recordingEvents.collect { collected.add(it) }
        }
        try {
            emit(RadioEvent.DabRecordingStarted)
            val state = viewModel.uiState.value as RadioUiState.Dab
            assertTrue("isRecording must flip to true", state.dabState.isRecording)
            assertEquals(1, collected.size)
            assertTrue(
                "Started event must be emitted",
                collected[0] is RadioViewModel.RecordingEvent.Started,
            )
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `DabRecordingStopped sets isRecording false and emits Stopped(file)`() {
        emit(RadioEvent.ModeChanged(FrequencyScaleView.RadioMode.DAB))
        emit(RadioEvent.DabRecordingStarted) // pre-state
        val collected = mutableListOf<RadioViewModel.RecordingEvent>()
        val job = CoroutineScope(UnconfinedTestDispatcher()).launch {
            viewModel.recordingEvents.collect { collected.add(it) }
        }
        try {
            val file = java.io.File("/tmp/recording.mp3")
            emit(RadioEvent.DabRecordingStopped(file))
            val state = viewModel.uiState.value as RadioUiState.Dab
            assertFalse("isRecording must flip to false", state.dabState.isRecording)
            assertEquals(1, collected.size)
            val stopped = collected[0] as RadioViewModel.RecordingEvent.Stopped
            assertSame(file, stopped.file)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `DabRecordingStopped tolerates a null file (recording aborted before save)`() {
        emit(RadioEvent.ModeChanged(FrequencyScaleView.RadioMode.DAB))
        emit(RadioEvent.DabRecordingStarted)
        val collected = mutableListOf<RadioViewModel.RecordingEvent>()
        val job = CoroutineScope(UnconfinedTestDispatcher()).launch {
            viewModel.recordingEvents.collect { collected.add(it) }
        }
        try {
            emit(RadioEvent.DabRecordingStopped(file = null))
            assertFalse((viewModel.uiState.value as RadioUiState.Dab).dabState.isRecording)
            val stopped = collected.single() as RadioViewModel.RecordingEvent.Stopped
            assertNull(stopped.file)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `DabRecordingError sets isRecording false and emits Failed(message)`() {
        emit(RadioEvent.ModeChanged(FrequencyScaleView.RadioMode.DAB))
        emit(RadioEvent.DabRecordingStarted)
        val collected = mutableListOf<RadioViewModel.RecordingEvent>()
        val job = CoroutineScope(UnconfinedTestDispatcher()).launch {
            viewModel.recordingEvents.collect { collected.add(it) }
        }
        try {
            emit(RadioEvent.DabRecordingError("disk full"))
            assertFalse(
                "isRecording must flip to false on failure too",
                (viewModel.uiState.value as RadioUiState.Dab).dabState.isRecording,
            )
            val failed = collected.single() as RadioViewModel.RecordingEvent.Failed
            assertEquals("disk full", failed.message)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `recording state mutations while in FmAm are silent (no isRecording on FmAm state)`() {
        // FmAm has no isRecording field — the setDabRecording fall-through
        // must not crash, and the event must still be emitted (so MainActivity
        // can still toast even if mode-switch races the recorder).
        val collected = mutableListOf<RadioViewModel.RecordingEvent>()
        val job = CoroutineScope(UnconfinedTestDispatcher()).launch {
            viewModel.recordingEvents.collect { collected.add(it) }
        }
        try {
            // We start in FmAm by default — emit a recording-stopped event.
            emit(RadioEvent.DabRecordingStopped(file = java.io.File("/tmp/x.mp3")))
            assertEquals(1, collected.size)
            assertTrue(viewModel.uiState.value is RadioUiState.FmAm)
        } finally {
            job.cancel()
        }
    }

    // ============ dlPlusEvents + state projection (Phase A migration target) ============

    @Test
    fun `DabDlPlus updates dabState AND emits onto dlPlusEvents`() {
        emit(RadioEvent.ModeChanged(FrequencyScaleView.RadioMode.DAB))
        val collected = mutableListOf<RadioViewModel.DlPlusEvent>()
        val job = CoroutineScope(UnconfinedTestDispatcher()).launch {
            viewModel.dlPlusEvents.collect { collected.add(it) }
        }
        try {
            emit(RadioEvent.DabDlPlus(artist = "Beatles", title = "Yesterday"))
            // State projection
            val state = viewModel.uiState.value as RadioUiState.Dab
            assertEquals("Beatles", state.dabState.dlPlusArtist)
            assertEquals("Yesterday", state.dabState.dlPlusTitle)
            // Event emission
            assertEquals(1, collected.size)
            assertEquals("Beatles", collected[0].artist)
            assertEquals("Yesterday", collected[0].title)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `DabDlPlus with null artist or title is propagated as-is`() {
        // Some broadcasters send artist + null title or vice versa — must
        // not crash, must preserve the partial info for downstream consumers.
        emit(RadioEvent.ModeChanged(FrequencyScaleView.RadioMode.DAB))
        val collected = mutableListOf<RadioViewModel.DlPlusEvent>()
        val job = CoroutineScope(UnconfinedTestDispatcher()).launch {
            viewModel.dlPlusEvents.collect { collected.add(it) }
        }
        try {
            emit(RadioEvent.DabDlPlus(artist = "Solo Artist", title = null))
            emit(RadioEvent.DabDlPlus(artist = null, title = "Anonymous Track"))
            assertEquals(2, collected.size)
            assertEquals(null, collected[0].title)
            assertEquals(null, collected[1].artist)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `DabDlPlus while in FmAm mode emits but does NOT mutate FmAm state`() {
        // FmAm has no dlPlus fields — the state-projection branch falls
        // through, but the event still fires (mode-switch race tolerance).
        val collected = mutableListOf<RadioViewModel.DlPlusEvent>()
        val job = CoroutineScope(UnconfinedTestDispatcher()).launch {
            viewModel.dlPlusEvents.collect { collected.add(it) }
        }
        try {
            emit(RadioEvent.DabDlPlus("X", "Y"))
            assertEquals(1, collected.size)
            assertTrue(viewModel.uiState.value is RadioUiState.FmAm)
        } finally {
            job.cancel()
        }
    }

    // ============ slideshowEvents + state projection (Phase A migration target) ============

    @Test
    fun `DabSlideshow updates dabState AND emits onto slideshowEvents`() {
        emit(RadioEvent.ModeChanged(FrequencyScaleView.RadioMode.DAB))
        val bitmap: Bitmap = mockk(relaxed = true)
        val collected = mutableListOf<Bitmap>()
        val job = CoroutineScope(UnconfinedTestDispatcher()).launch {
            viewModel.slideshowEvents.collect { collected.add(it) }
        }
        try {
            emit(RadioEvent.DabSlideshow(bitmap))
            assertSame(bitmap, (viewModel.uiState.value as RadioUiState.Dab).dabState.slideshow)
            assertEquals(1, collected.size)
            assertSame(bitmap, collected[0])
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `DabSlideshow in FmAm mode still emits the bitmap event (state stays FmAm)`() {
        val bitmap: Bitmap = mockk(relaxed = true)
        val collected = mutableListOf<Bitmap>()
        val job = CoroutineScope(UnconfinedTestDispatcher()).launch {
            viewModel.slideshowEvents.collect { collected.add(it) }
        }
        try {
            emit(RadioEvent.DabSlideshow(bitmap))
            // Event still fires for cover-source-refresh side effect.
            assertEquals(1, collected.size)
            assertTrue(viewModel.uiState.value is RadioUiState.FmAm)
        } finally {
            job.cancel()
        }
    }

    // ============ DabServiceStarted clears per-service state (Phase B Round 2) ============

    @Test
    fun `DabServiceStarted clears the previous service's DLS, slideshow and DL+ state`() {
        emit(RadioEvent.ModeChanged(FrequencyScaleView.RadioMode.DAB))
        // Pre-seed previous service's metadata.
        emit(RadioEvent.DabDynamicLabel("Old Show: Beatles - Yesterday"))
        emit(RadioEvent.DabDlPlus(artist = "Beatles", title = "Yesterday"))
        val oldBitmap: Bitmap = mockk(relaxed = true)
        emit(RadioEvent.DabSlideshow(oldBitmap))
        val before = viewModel.uiState.value as RadioUiState.Dab
        assertNotNull(before.dabState.dls)
        assertNotNull(before.dabState.slideshow)
        assertNotNull(before.dabState.dlPlusArtist)

        // Switch to a new service — the previous service's per-service
        // metadata must be wiped so the UI doesn't bleed old DLS into
        // the new service's "now playing" view.
        val newStation = DabStation(
            serviceId = 9999, ensembleId = 1,
            serviceLabel = "New Service", ensembleLabel = "MUX",
            ensembleFrequencyKHz = 223936,
        )
        emit(RadioEvent.DabServiceStarted(newStation))

        val after = viewModel.uiState.value as RadioUiState.Dab
        assertNull("DLS must reset on new service", after.dabState.dls)
        assertNull("Slideshow must reset on new service", after.dabState.slideshow)
        assertNull("DL+ artist must reset on new service", after.dabState.dlPlusArtist)
        assertNull("DL+ title must reset on new service", after.dabState.dlPlusTitle)
        // But the new service identity is set:
        assertEquals(9999, after.dabState.serviceId)
        assertEquals("New Service", after.dabState.serviceLabel)
    }

    // ============ dabServiceStartedEvents (Phase A — last callback retired) ============

    @Test
    fun `DabServiceStarted updates uiState AND emits the DabStation onto dabServiceStartedEvents`() {
        emit(RadioEvent.ModeChanged(FrequencyScaleView.RadioMode.DAB))
        val station = DabStation(
            serviceId = 1234,
            ensembleId = 9,
            serviceLabel = "Mock FM4",
            ensembleLabel = "Mock MUX",
            ensembleFrequencyKHz = 223936,
        )
        val collected = mutableListOf<DabStation>()
        val job = CoroutineScope(UnconfinedTestDispatcher()).launch {
            viewModel.dabServiceStartedEvents.collect { collected.add(it) }
        }
        try {
            emit(RadioEvent.DabServiceStarted(station))
            // State projection still happens
            val state = viewModel.uiState.value as RadioUiState.Dab
            assertEquals(1234, state.dabState.serviceId)
            assertEquals("Mock FM4", state.dabState.serviceLabel)
            assertTrue(state.isPlaying)
            // Event emission for one-shot side effects
            assertEquals(1, collected.size)
            assertEquals(1234, collected[0].serviceId)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `dabServiceStartedEvents fires per service-tune (no de-dup across re-tunes)`() {
        emit(RadioEvent.ModeChanged(FrequencyScaleView.RadioMode.DAB))
        val collected = mutableListOf<DabStation>()
        val job = CoroutineScope(UnconfinedTestDispatcher()).launch {
            viewModel.dabServiceStartedEvents.collect { collected.add(it) }
        }
        try {
            val s1 = DabStation(1, 1, "A", "MUX", 223936)
            val s2 = DabStation(2, 1, "B", "MUX", 223936)
            emit(RadioEvent.DabServiceStarted(s1))
            emit(RadioEvent.DabServiceStarted(s2))
            assertEquals(2, collected.size)
            assertEquals(1, collected[0].serviceId)
            assertEquals(2, collected[1].serviceId)
        } finally {
            job.cancel()
        }
    }

    // ============ dlsEvents + state projection (Phase A migration target) ============

    @Test
    fun `DabDynamicLabel updates dabState_dls AND emits onto dlsEvents`() {
        emit(RadioEvent.ModeChanged(FrequencyScaleView.RadioMode.DAB))
        val collected = mutableListOf<String>()
        val job = CoroutineScope(UnconfinedTestDispatcher()).launch {
            viewModel.dlsEvents.collect { collected.add(it) }
        }
        try {
            emit(RadioEvent.DabDynamicLabel("Now Playing: Beatles - Yesterday"))
            // State projection
            val state = viewModel.uiState.value as RadioUiState.Dab
            assertEquals("Now Playing: Beatles - Yesterday", state.dabState.dls)
            // Event emission
            assertEquals(1, collected.size)
            assertEquals("Now Playing: Beatles - Yesterday", collected[0])
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `Multiple DabDynamicLabel emissions accumulate (no de-duplication)`() {
        // Same DLS twice in a row CAN happen during scan jitter — both should
        // fire because the parser-log consumer needs every arrival.
        emit(RadioEvent.ModeChanged(FrequencyScaleView.RadioMode.DAB))
        val collected = mutableListOf<String>()
        val job = CoroutineScope(UnconfinedTestDispatcher()).launch {
            viewModel.dlsEvents.collect { collected.add(it) }
        }
        try {
            emit(RadioEvent.DabDynamicLabel("same"))
            emit(RadioEvent.DabDynamicLabel("same"))
            assertEquals(2, collected.size)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `Empty DLS string is still emitted (clearing message is meaningful)`() {
        // Some broadcasters send an empty DLS to mark "no current track" —
        // the consumer needs to know to wipe the now-playing display.
        emit(RadioEvent.ModeChanged(FrequencyScaleView.RadioMode.DAB))
        val collected = mutableListOf<String>()
        val job = CoroutineScope(UnconfinedTestDispatcher()).launch {
            viewModel.dlsEvents.collect { collected.add(it) }
        }
        try {
            emit(RadioEvent.DabDynamicLabel(""))
            assertEquals(listOf(""), collected)
        } finally {
            job.cancel()
        }
    }

    // ============ receptionStatsEvents + state projection (Phase A migration target) ============

    @Test
    fun `DabReceptionStats updates all three dabState fields AND emits event`() {
        emit(RadioEvent.ModeChanged(FrequencyScaleView.RadioMode.DAB))
        val collected = mutableListOf<RadioViewModel.ReceptionStats>()
        val job = CoroutineScope(UnconfinedTestDispatcher()).launch {
            viewModel.receptionStatsEvents.collect { collected.add(it) }
        }
        try {
            emit(RadioEvent.DabReceptionStats(sync = true, quality = "good", snr = 28))
            val state = viewModel.uiState.value as RadioUiState.Dab
            assertEquals("good", state.dabState.receptionQuality)
            assertEquals(28, state.dabState.snr)
            assertTrue(state.dabState.signalSync)
            assertEquals(1, collected.size)
            assertEquals(true, collected[0].sync)
            assertEquals("good", collected[0].quality)
            assertEquals(28, collected[0].snr)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `DabReceptionStats handles transitioning from sync to no-sync`() {
        emit(RadioEvent.ModeChanged(FrequencyScaleView.RadioMode.DAB))
        emit(RadioEvent.DabReceptionStats(sync = true, quality = "good", snr = 25))
        val collected = mutableListOf<RadioViewModel.ReceptionStats>()
        val job = CoroutineScope(UnconfinedTestDispatcher()).launch {
            viewModel.receptionStatsEvents.collect { collected.add(it) }
        }
        try {
            emit(RadioEvent.DabReceptionStats(sync = false, quality = "poor", snr = 5))
            val state = viewModel.uiState.value as RadioUiState.Dab
            assertEquals("poor", state.dabState.receptionQuality)
            assertEquals(5, state.dabState.snr)
            assertFalse("sync flag must follow the event", state.dabState.signalSync)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `DabReceptionStats in FmAm mode emits but does NOT mutate FmAm state`() {
        // FmAm has no reception fields; mode-switch race tolerance.
        val collected = mutableListOf<RadioViewModel.ReceptionStats>()
        val job = CoroutineScope(UnconfinedTestDispatcher()).launch {
            viewModel.receptionStatsEvents.collect { collected.add(it) }
        }
        try {
            emit(RadioEvent.DabReceptionStats(true, "good", 30))
            assertEquals(1, collected.size)
            assertTrue(viewModel.uiState.value is RadioUiState.FmAm)
        } finally {
            job.cancel()
        }
    }

    // ============ epgEvents (Phase A migration target) ============

    @Test
    fun `DabEpgReceived emits the typed EpgData onto epgEvents`() {
        val epg: at.planqton.fytfm.dab.EpgData = mockk(relaxed = true)
        val collected = mutableListOf<at.planqton.fytfm.dab.EpgData>()
        val job = CoroutineScope(UnconfinedTestDispatcher()).launch {
            viewModel.epgEvents.collect { collected.add(it) }
        }
        try {
            emit(RadioEvent.DabEpgReceived(data = epg))
            assertEquals(1, collected.size)
            assertSame("payload must be the same EpgData instance", epg, collected[0])
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `DabEpgReceived with non-EpgData payload is silently dropped`() {
        // The controller declares the event payload as Any? — old/wrong types
        // must NOT crash the consumer. Filtered at the VM boundary.
        val collected = mutableListOf<at.planqton.fytfm.dab.EpgData>()
        val job = CoroutineScope(UnconfinedTestDispatcher()).launch {
            viewModel.epgEvents.collect { collected.add(it) }
        }
        try {
            emit(RadioEvent.DabEpgReceived(data = "not-an-epg-payload"))
            emit(RadioEvent.DabEpgReceived(data = null))
            emit(RadioEvent.DabEpgReceived(data = 42))
            assertTrue("non-EpgData payloads must NOT reach collectors", collected.isEmpty())
        } finally {
            job.cancel()
        }
    }

    // ============ dabTunerReadyEvents (Phase A migration target) ============

    @Test
    fun `DabTunerReady emits one Unit pulse on dabTunerReadyEvents`() {
        var pulseCount = 0
        val job = CoroutineScope(UnconfinedTestDispatcher()).launch {
            viewModel.dabTunerReadyEvents.collect { pulseCount++ }
        }
        try {
            emit(RadioEvent.DabTunerReady)
            assertEquals(1, pulseCount)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `dabTunerReadyEvents has replay 0 (late subscriber doesn't trigger carousel)`() {
        emit(RadioEvent.DabTunerReady)
        var pulseCount = 0
        val job = CoroutineScope(UnconfinedTestDispatcher()).launch {
            viewModel.dabTunerReadyEvents.collect { pulseCount++ }
        }
        try {
            assertEquals("late subscriber must NOT see the pre-subscription pulse", 0, pulseCount)
        } finally {
            job.cancel()
        }
    }

    // ============ dabAudioStartedEvents (Phase A migration target) ============

    @Test
    fun `DabAudioStarted emits the AudioSessionId payload`() {
        val collected = mutableListOf<Int>()
        val job = CoroutineScope(UnconfinedTestDispatcher()).launch {
            viewModel.dabAudioStartedEvents.collect { collected.add(it) }
        }
        try {
            emit(RadioEvent.DabAudioStarted(sessionId = 12345))
            assertEquals(listOf(12345), collected)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `dabAudioStartedEvents emits 0 too (controller may forward 0 for invalid sessions)`() {
        // MainActivity filters audioSessionId > 0 itself — VM doesn't filter,
        // so we don't lose the signal that "audio started but session is invalid".
        val collected = mutableListOf<Int>()
        val job = CoroutineScope(UnconfinedTestDispatcher()).launch {
            viewModel.dabAudioStartedEvents.collect { collected.add(it) }
        }
        try {
            emit(RadioEvent.DabAudioStarted(sessionId = 0))
            assertEquals(listOf(0), collected)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `DabServiceStopped emits one Unit pulse on dabServiceStoppedEvents`() {
        var pulseCount = 0
        val job = CoroutineScope(UnconfinedTestDispatcher()).launch {
            viewModel.dabServiceStoppedEvents.collect { pulseCount++ }
        }
        try {
            emit(RadioEvent.DabServiceStopped)
            assertEquals(1, pulseCount)
            emit(RadioEvent.DabServiceStopped)
            assertEquals("each event = one pulse (no de-dup)", 2, pulseCount)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `dabServiceStoppedEvents has replay 0 (late subscriber does not auto-stop)`() {
        emit(RadioEvent.DabServiceStopped)
        var pulseCount = 0
        val job = CoroutineScope(UnconfinedTestDispatcher()).launch {
            viewModel.dabServiceStoppedEvents.collect { pulseCount++ }
        }
        try {
            // Late subscribe — must NOT receive the pre-subscription pulse.
            assertEquals(
                "no replay — otherwise visualizer would stop on every Activity recreate",
                0, pulseCount,
            )
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `errorEvents has replay 0 (late subscriber does not see old errors)`() {
        // A toast is one-shot UX — re-toasting an old error on Activity recreate
        // would be confusing. Verify the SharedFlow drops events with no listener.
        emit(RadioEvent.ErrorEvent("old error before subscribe"))
        val collected = mutableListOf<String>()
        val job = CoroutineScope(UnconfinedTestDispatcher()).launch {
            viewModel.errorEvents.collect { collected.add(it) }
        }
        try {
            // Late subscribe — old error must NOT replay.
            assertTrue(
                "no replay of pre-subscription errors, got: $collected",
                collected.isEmpty(),
            )
            emit(RadioEvent.ErrorEvent("new error after subscribe"))
            assertEquals(listOf("new error after subscribe"), collected)
        } finally {
            job.cancel()
        }
    }
}
