package at.planqton.fytfm.viewmodel

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import at.planqton.fytfm.FrequencyScaleView
import at.planqton.fytfm.controller.RadioController
import at.planqton.fytfm.dab.DabStation
import at.planqton.fytfm.data.PresetRepository
import at.planqton.fytfm.data.RadioStation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * ViewModel für die Radio-Funktionalität.
 * Koordiniert RadioController und verwaltet UI-State als StateFlow.
 */
class RadioViewModel(
    private val radioController: RadioController,
    private val presetRepository: PresetRepository
) : ViewModel() {

    // Main UI State
    private val _uiState = MutableStateFlow<RadioUiState>(RadioUiState.Off)
    val uiState: StateFlow<RadioUiState> = _uiState.asStateFlow()

    // Station List State
    private val _stationListState = MutableStateFlow(StationListState())
    val stationListState: StateFlow<StationListState> = _stationListState.asStateFlow()

    // Last error (simple approach without channels)
    private var _lastError: String? = null
    val lastError: String? get() = _lastError

    // Event callbacks (simple approach)
    var onStationChanged: ((RadioStation) -> Unit)? = null
    var onFrequencyChanged: ((Float) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    init {
        setupControllerCallbacks()
        radioController.initialize()
        loadInitialState()
    }

    /**
     * Verbindet RadioController-Callbacks mit StateFlow-Updates
     */
    private fun setupControllerCallbacks() {
        radioController.onModeChanged = { mode ->
            handleModeChanged(mode)
        }

        radioController.onRadioStateChanged = { isOn ->
            handleRadioStateChanged(isOn)
        }

        radioController.onFrequencyChanged = { frequency ->
            handleFrequencyChanged(frequency)
        }

        radioController.onRdsUpdate = { ps, rt, rssi, pi, pty ->
            handleRdsUpdate(ps, rt, rssi, pi, pty)
        }

        radioController.onDabServiceStarted = { station ->
            handleDabServiceStarted(station)
        }

        radioController.onDabDynamicLabel = { dls ->
            handleDabDynamicLabel(dls)
        }

        radioController.onDabSlideshow = { bitmap ->
            handleDabSlideshow(bitmap)
        }

        radioController.onError = { message ->
            _lastError = message
            onError?.invoke(message)
        }
    }

    private fun loadInitialState() {
        val mode = radioController.currentMode
        updateStationsForMode(mode)

        // Set initial state based on mode
        _uiState.value = when (mode) {
            FrequencyScaleView.RadioMode.FM, FrequencyScaleView.RadioMode.AM -> {
                RadioUiState.FmAm(
                    frequency = radioController.getCurrentFrequency(),
                    mode = mode,
                    isPlaying = radioController.isRadioOn(),
                    isLocalMode = presetRepository.isLocalMode(),
                    isMonoMode = presetRepository.isMonoMode()
                )
            }
            FrequencyScaleView.RadioMode.DAB, FrequencyScaleView.RadioMode.DAB_DEV -> {
                val dabService = radioController.getCurrentDabService()
                RadioUiState.Dab(
                    isPlaying = radioController.isRadioOn(),
                    dabState = DabState(
                        serviceId = dabService?.serviceId ?: 0,
                        ensembleId = dabService?.ensembleId ?: 0,
                        serviceLabel = dabService?.serviceLabel,
                        ensembleLabel = dabService?.ensembleLabel
                    )
                )
            }
        }
    }

    // ========== PUBLIC API ==========

    /**
     * Schaltet das Radio ein/aus
     */
    fun togglePower() {
        radioController.togglePower()
    }

    /**
     * Wechselt den Radio-Modus (FM/AM/DAB)
     */
    fun setMode(mode: FrequencyScaleView.RadioMode) {
        radioController.setMode(mode)
    }

    /**
     * Tuned zu einer Frequenz (FM/AM)
     */
    fun tune(frequency: Float) {
        radioController.tune(frequency)
    }

    /**
     * Tuned zu einer Station
     */
    fun tuneStation(station: RadioStation) {
        radioController.tuneStation(station)
        onStationChanged?.invoke(station)
    }

    /**
     * Springt zum nächsten/vorherigen Sender
     */
    fun skipStation(forward: Boolean) {
        val station = radioController.skipStation(forward)
        station?.let {
            onStationChanged?.invoke(it)
        }
    }

    /**
     * Startet Frequenzsuche (FM/AM)
     */
    fun seek(forward: Boolean) {
        radioController.seek(forward)
    }

    /**
     * Togglet Favoriten-Filter
     */
    fun toggleFavoritesOnly() {
        _stationListState.update { state ->
            state.copy(showFavoritesOnly = !state.showFavoritesOnly)
        }
    }

    /**
     * Togglet Carousel/List Mode
     */
    fun toggleViewMode() {
        _stationListState.update { state ->
            state.copy(isCarouselMode = !state.isCarouselMode)
        }
    }

    /**
     * Aktualisiert Stationen aus Repository
     */
    fun refreshStations() {
        updateStationsForMode(radioController.currentMode)
    }

    // ========== PRIVATE HANDLERS ==========

    private fun handleModeChanged(mode: FrequencyScaleView.RadioMode) {
        updateStationsForMode(mode)

        when (mode) {
            FrequencyScaleView.RadioMode.FM, FrequencyScaleView.RadioMode.AM -> {
                _uiState.value = RadioUiState.FmAm(
                    frequency = radioController.getCurrentFrequency(),
                    mode = mode,
                    isPlaying = false,
                    isLocalMode = presetRepository.isLocalMode(),
                    isMonoMode = presetRepository.isMonoMode()
                )
            }
            FrequencyScaleView.RadioMode.DAB, FrequencyScaleView.RadioMode.DAB_DEV -> {
                _uiState.value = RadioUiState.Dab(
                    isPlaying = false
                )
            }
        }
    }

    private fun handleRadioStateChanged(isOn: Boolean) {
        _uiState.update { state ->
            when (state) {
                is RadioUiState.FmAm -> state.copy(isPlaying = isOn)
                is RadioUiState.Dab -> state.copy(isPlaying = isOn)
                RadioUiState.Off -> if (isOn) createInitialFmState() else RadioUiState.Off
                is RadioUiState.Scanning -> state // Don't change during scan
            }
        }
    }

    private fun handleFrequencyChanged(frequency: Float) {
        _uiState.update { state ->
            when (state) {
                is RadioUiState.FmAm -> state.copy(frequency = frequency)
                else -> state
            }
        }
        onFrequencyChanged?.invoke(frequency)
    }

    private fun handleRdsUpdate(ps: String, rt: String, rssi: Int, pi: Int, pty: Int) {
        _uiState.update { state ->
            when (state) {
                is RadioUiState.FmAm -> state.copy(
                    rdsData = RdsData(
                        ps = ps,
                        rt = rt,
                        rssi = rssi,
                        pi = pi,
                        pty = pty
                    )
                )
                else -> state
            }
        }
    }

    private fun handleDabServiceStarted(station: DabStation) {
        _uiState.update { state ->
            when (state) {
                is RadioUiState.Dab -> state.copy(
                    isPlaying = true,
                    currentStation = RadioStation(
                        frequency = 0f,
                        name = station.serviceLabel,
                        isDab = true,
                        serviceId = station.serviceId,
                        ensembleId = station.ensembleId,
                        ensembleLabel = station.ensembleLabel
                    ),
                    dabState = state.dabState.copy(
                        serviceId = station.serviceId,
                        ensembleId = station.ensembleId,
                        serviceLabel = station.serviceLabel,
                        ensembleLabel = station.ensembleLabel
                    )
                )
                else -> state
            }
        }
    }

    private fun handleDabDynamicLabel(dls: String) {
        _uiState.update { state ->
            when (state) {
                is RadioUiState.Dab -> state.copy(
                    dabState = state.dabState.copy(dls = dls)
                )
                else -> state
            }
        }
    }

    private fun handleDabSlideshow(bitmap: Bitmap) {
        _uiState.update { state ->
            when (state) {
                is RadioUiState.Dab -> state.copy(
                    dabState = state.dabState.copy(slideshow = bitmap)
                )
                else -> state
            }
        }
    }

    private fun updateStationsForMode(mode: FrequencyScaleView.RadioMode) {
        val stations = radioController.getStationsForCurrentMode()
        _stationListState.update { state ->
            state.copy(stations = stations)
        }
    }

    private fun createInitialFmState(): RadioUiState.FmAm {
        return RadioUiState.FmAm(
            frequency = radioController.getCurrentFrequency(),
            mode = radioController.currentMode,
            isPlaying = true,
            isLocalMode = presetRepository.isLocalMode(),
            isMonoMode = presetRepository.isMonoMode()
        )
    }

    // ========== DEEZER INTEGRATION ==========

    /**
     * Updates Deezer track info (called from DeezerClient callback)
     */
    fun updateDeezerTrack(track: at.planqton.fytfm.deezer.TrackInfo?, coverPath: String?) {
        _uiState.update { state ->
            when (state) {
                is RadioUiState.FmAm -> state.copy(
                    deezerState = state.deezerState.copy(
                        currentTrack = track,
                        coverPath = coverPath,
                        isSearching = false
                    )
                )
                is RadioUiState.Dab -> state.copy(
                    deezerState = state.deezerState.copy(
                        currentTrack = track,
                        coverPath = coverPath,
                        isSearching = false
                    )
                )
                else -> state
            }
        }
    }

    fun setDeezerSearching(isSearching: Boolean, query: String? = null) {
        _uiState.update { state ->
            when (state) {
                is RadioUiState.FmAm -> state.copy(
                    deezerState = state.deezerState.copy(
                        isSearching = isSearching,
                        lastQuery = query
                    )
                )
                is RadioUiState.Dab -> state.copy(
                    deezerState = state.deezerState.copy(
                        isSearching = isSearching,
                        lastQuery = query
                    )
                )
                else -> state
            }
        }
    }

    // ========== MUTE CONTROL ==========

    /**
     * Toggles mute state
     */
    fun toggleMute() {
        _uiState.update { state ->
            when (state) {
                is RadioUiState.FmAm -> state.copy(isMuted = !state.isMuted)
                is RadioUiState.Dab -> state.copy(isMuted = !state.isMuted)
                else -> state
            }
        }
    }

    /**
     * Sets mute state directly
     */
    fun setMuted(muted: Boolean) {
        _uiState.update { state ->
            when (state) {
                is RadioUiState.FmAm -> state.copy(isMuted = muted)
                is RadioUiState.Dab -> state.copy(isMuted = muted)
                else -> state
            }
        }
    }

    // ========== STATION MANAGEMENT ==========

    /**
     * Updates station list with new stations
     */
    fun updateStations(stations: List<RadioStation>) {
        _stationListState.update { state ->
            state.copy(stations = stations)
        }
    }

    /**
     * Selects a station by index
     */
    fun selectStation(index: Int) {
        _stationListState.update { state ->
            state.copy(selectedIndex = index)
        }
    }

    /**
     * Gets the currently selected station
     */
    fun getCurrentStation(): RadioStation? {
        val listState = _stationListState.value
        return if (listState.selectedIndex >= 0 && listState.selectedIndex < listState.stations.size) {
            listState.stations[listState.selectedIndex]
        } else null
    }

    // ========== DAB SPECIFIC ==========

    /**
     * Updates DAB recording state
     */
    fun setDabRecording(isRecording: Boolean) {
        _uiState.update { state ->
            when (state) {
                is RadioUiState.Dab -> state.copy(
                    dabState = state.dabState.copy(isRecording = isRecording)
                )
                else -> state
            }
        }
    }

    /**
     * Updates DAB signal quality
     */
    fun updateDabSignalQuality(quality: Int, sync: Boolean) {
        _uiState.update { state ->
            when (state) {
                is RadioUiState.Dab -> state.copy(
                    dabState = state.dabState.copy(
                        receptionQuality = quality,
                        signalSync = sync
                    )
                )
                else -> state
            }
        }
    }

    /**
     * Updates DL+ artist and title information
     */
    fun updateDabDlPlus(artist: String?, title: String?) {
        _uiState.update { state ->
            when (state) {
                is RadioUiState.Dab -> state.copy(
                    dabState = state.dabState.copy(
                        dlPlusArtist = artist,
                        dlPlusTitle = title
                    )
                )
                else -> state
            }
        }
    }
}
