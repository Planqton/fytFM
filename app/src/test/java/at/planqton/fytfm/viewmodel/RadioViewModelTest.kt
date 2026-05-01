package at.planqton.fytfm.viewmodel

import android.graphics.Bitmap
import at.planqton.fytfm.FrequencyScaleView
import at.planqton.fytfm.controller.RadioController
import at.planqton.fytfm.controller.RadioEvent
import at.planqton.fytfm.dab.DabStation
import at.planqton.fytfm.data.PresetRepository
import at.planqton.fytfm.data.RadioStation
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for RadioViewModel. The VM now observes RadioController via a
 * SharedFlow of RadioEvent rather than stealing lambda callback slots, so the
 * test drives it by emitting events onto a shared-flow mock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RadioViewModelTest {

    private lateinit var mockRadioController: RadioController
    private lateinit var mockPresetRepository: PresetRepository
    private lateinit var viewModel: RadioViewModel
    private lateinit var events: MutableSharedFlow<RadioEvent>

    private fun emit(event: RadioEvent) {
        assertTrue("Event $event must fit in the test buffer", events.tryEmit(event))
    }

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())

        mockRadioController = mockk(relaxed = true)
        mockPresetRepository = mockk(relaxed = true)
        events = MutableSharedFlow(replay = 0, extraBufferCapacity = 64)

        every { mockRadioController.events } returns events
        every { mockRadioController.currentMode } returns FrequencyScaleView.RadioMode.FM
        every { mockRadioController.getCurrentFrequency() } returns 100.0f
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

    // ========== INITIAL STATE TESTS ==========

    @Test
    fun `initial state is FmAm for FM mode`() {
        val state = viewModel.uiState.value

        assertTrue(state is RadioUiState.FmAm)
        val fmState = state as RadioUiState.FmAm
        assertEquals(100.0f, fmState.frequency, 0.01f)
        assertEquals(FrequencyScaleView.RadioMode.FM, fmState.mode)
        assertEquals(false, fmState.isPlaying)
    }

    @Test
    fun `initial state loads DAB for DAB mode`() {
        every { mockRadioController.currentMode } returns FrequencyScaleView.RadioMode.DAB

        val newViewModel = RadioViewModel(mockRadioController, mockPresetRepository)
        val state = newViewModel.uiState.value

        assertTrue(state is RadioUiState.Dab)
    }

    // ========== PUBLIC API TESTS ==========

    @Test
    fun `togglePower calls RadioController`() {
        viewModel.togglePower()

        verify { mockRadioController.togglePower() }
    }

    @Test
    fun `setMode calls RadioController`() {
        viewModel.setMode(FrequencyScaleView.RadioMode.DAB)

        verify { mockRadioController.setMode(FrequencyScaleView.RadioMode.DAB) }
    }

    @Test
    fun `tune calls RadioController`() {
        viewModel.tune(103.5f)

        verify { mockRadioController.tune(103.5f) }
    }

    @Test
    fun `tuneStation calls RadioController and triggers callback`() {
        val station = RadioStation(
            frequency = 95.5f,
            name = "Test FM",
            isDab = false
        )
        var callbackStation: RadioStation? = null
        viewModel.onStationChanged = { callbackStation = it }

        viewModel.tuneStation(station)

        verify { mockRadioController.tuneStation(station) }
        assertEquals(station, callbackStation)
    }

    @Test
    fun `skipStation calls RadioController`() {
        val station = RadioStation(frequency = 100.0f, name = "Next", isDab = false)
        every { mockRadioController.skipStation(true) } returns station

        var callbackStation: RadioStation? = null
        viewModel.onStationChanged = { callbackStation = it }

        viewModel.skipStation(true)

        verify { mockRadioController.skipStation(true) }
        assertEquals(station, callbackStation)
    }

    @Test
    fun `seek calls RadioController`() {
        viewModel.seek(true)

        verify { mockRadioController.seek(true) }
    }

    @Test
    fun `toggleFavoritesOnly toggles state`() {
        val initialState = viewModel.stationListState.value
        assertFalse(initialState.showFavoritesOnly)

        viewModel.toggleFavoritesOnly()

        assertTrue(viewModel.stationListState.value.showFavoritesOnly)

        viewModel.toggleFavoritesOnly()

        assertFalse(viewModel.stationListState.value.showFavoritesOnly)
    }

    @Test
    fun `toggleViewMode toggles carousel mode`() {
        val initialState = viewModel.stationListState.value
        assertTrue(initialState.isCarouselMode)

        viewModel.toggleViewMode()

        assertFalse(viewModel.stationListState.value.isCarouselMode)

        viewModel.toggleViewMode()

        assertTrue(viewModel.stationListState.value.isCarouselMode)
    }

    @Test
    fun `refreshStations updates station list`() {
        val stations = listOf(
            RadioStation(frequency = 89.5f, name = "Station1", isDab = false),
            RadioStation(frequency = 95.0f, name = "Station2", isDab = false)
        )
        every { mockRadioController.getStationsForCurrentMode() } returns stations

        viewModel.refreshStations()

        assertEquals(stations, viewModel.stationListState.value.stations)
    }

    // ========== EVENT HANDLER TESTS ==========

    @Test
    fun `handleModeChanged updates state for FM`() {
        emit(RadioEvent.ModeChanged(FrequencyScaleView.RadioMode.FM))

        val state = viewModel.uiState.value
        assertTrue(state is RadioUiState.FmAm)
        assertEquals(FrequencyScaleView.RadioMode.FM, (state as RadioUiState.FmAm).mode)
    }

    @Test
    fun `handleModeChanged updates state for DAB`() {
        emit(RadioEvent.ModeChanged(FrequencyScaleView.RadioMode.DAB))

        val state = viewModel.uiState.value
        assertTrue(state is RadioUiState.Dab)
    }

    @Test
    fun `handleRadioStateChanged updates isPlaying`() {
        emit(RadioEvent.RadioStateChanged(true))

        val state = viewModel.uiState.value
        assertTrue(state is RadioUiState.FmAm)
        assertTrue((state as RadioUiState.FmAm).isPlaying)
    }

    @Test
    fun `handleFrequencyChanged updates frequency`() {
        var callbackFrequency: Float? = null
        viewModel.onFrequencyChanged = { callbackFrequency = it }

        emit(RadioEvent.FrequencyChanged(102.5f))

        val state = viewModel.uiState.value as RadioUiState.FmAm
        assertEquals(102.5f, state.frequency, 0.01f)
        assertEquals(102.5f, callbackFrequency!!, 0.01f)
    }

    @Test
    fun `handleRdsUpdate updates RDS data`() {
        emit(RadioEvent.RdsUpdate("FM1", "Now playing: Test Song", 85, 1234, 5))

        val state = viewModel.uiState.value as RadioUiState.FmAm
        assertEquals("FM1", state.rdsData.ps)
        assertEquals("Now playing: Test Song", state.rdsData.rt)
        assertEquals(85, state.rdsData.rssi)
        assertEquals(1234, state.rdsData.pi)
        assertEquals(5, state.rdsData.pty)
    }

    @Test
    fun `handleError stores error and triggers callback`() {
        var errorMessage: String? = null
        viewModel.onError = { errorMessage = it }

        emit(RadioEvent.ErrorEvent("Test error"))

        assertEquals("Test error", viewModel.lastError)
        assertEquals("Test error", errorMessage)
    }

    // ========== DAB CALLBACK TESTS ==========

    @Test
    fun `handleDabServiceStarted updates DAB state`() {
        emit(RadioEvent.ModeChanged(FrequencyScaleView.RadioMode.DAB))

        val dabStation = DabStation(
            serviceId = 12345,
            ensembleId = 67890,
            serviceLabel = "DAB Test",
            ensembleLabel = "Multiplex A",
            ensembleFrequencyKHz = 225648
        )

        emit(RadioEvent.DabServiceStarted(dabStation))

        val state = viewModel.uiState.value as RadioUiState.Dab
        assertTrue(state.isPlaying)
        assertEquals("DAB Test", state.currentStation?.name)
        assertEquals(12345, state.dabState.serviceId)
        assertEquals("Multiplex A", state.dabState.ensembleLabel)
    }

    @Test
    fun `handleDabDynamicLabel updates DLS`() {
        emit(RadioEvent.ModeChanged(FrequencyScaleView.RadioMode.DAB))

        emit(RadioEvent.DabDynamicLabel("Artist - Song Title"))

        val state = viewModel.uiState.value as RadioUiState.Dab
        assertEquals("Artist - Song Title", state.dabState.dls)
    }

    // ========== DEEZER INTEGRATION TESTS ==========

    @Test
    fun `updateDeezerTrack updates FM state`() {
        val track = at.planqton.fytfm.deezer.TrackInfo(
            artist = "Test Artist",
            title = "Test Song"
        )

        viewModel.updateDeezerTrack(track, "/path/to/cover.jpg")

        val state = viewModel.uiState.value as RadioUiState.FmAm
        assertEquals(track, state.deezerState.currentTrack)
        assertEquals("/path/to/cover.jpg", state.deezerState.coverPath)
        assertFalse(state.deezerState.isSearching)
    }

    @Test
    fun `updateDeezerTrack updates DAB state`() {
        emit(RadioEvent.ModeChanged(FrequencyScaleView.RadioMode.DAB))

        val track = at.planqton.fytfm.deezer.TrackInfo(
            artist = "DAB Artist",
            title = "DAB Song"
        )

        viewModel.updateDeezerTrack(track, "/path/to/dab-cover.jpg")

        val state = viewModel.uiState.value as RadioUiState.Dab
        assertEquals(track, state.deezerState.currentTrack)
        assertEquals("/path/to/dab-cover.jpg", state.deezerState.coverPath)
    }

    @Test
    fun `setDeezerSearching updates search state`() {
        viewModel.setDeezerSearching(true, "Artist - Song")

        val state = viewModel.uiState.value as RadioUiState.FmAm
        assertTrue(state.deezerState.isSearching)
        assertEquals("Artist - Song", state.deezerState.lastQuery)
    }

    @Test
    fun `setDeezerSearching updates DAB search state`() {
        emit(RadioEvent.ModeChanged(FrequencyScaleView.RadioMode.DAB))

        viewModel.setDeezerSearching(true, "DAB Query")

        val state = viewModel.uiState.value as RadioUiState.Dab
        assertTrue(state.deezerState.isSearching)
        assertEquals("DAB Query", state.deezerState.lastQuery)
    }

    // ========== STATE FLOW TESTS ==========

    @Test
    fun `stationListState is updated when mode changes`() {
        val fmStations = listOf(
            RadioStation(frequency = 100.0f, name = "FM1", isDab = false)
        )
        every { mockRadioController.getStationsForCurrentMode() } returns fmStations

        emit(RadioEvent.ModeChanged(FrequencyScaleView.RadioMode.FM))

        assertEquals(fmStations, viewModel.stationListState.value.stations)
    }

    @Test
    fun `multiple state changes accumulate correctly`() {
        emit(RadioEvent.RadioStateChanged(true))
        emit(RadioEvent.FrequencyChanged(98.3f))
        emit(RadioEvent.RdsUpdate("SWR3", "Good Morning", 90, 5555, 10))

        val state = viewModel.uiState.value as RadioUiState.FmAm
        assertTrue(state.isPlaying)
        assertEquals(98.3f, state.frequency, 0.01f)
        assertEquals("SWR3", state.rdsData.ps)
        assertEquals("Good Morning", state.rdsData.rt)
    }
}
