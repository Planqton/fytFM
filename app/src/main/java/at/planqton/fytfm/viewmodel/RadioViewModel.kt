package at.planqton.fytfm.viewmodel

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import at.planqton.fytfm.FrequencyScaleView
import at.planqton.fytfm.controller.RadioController
import at.planqton.fytfm.controller.RadioEvent
import at.planqton.fytfm.dab.DabStation
import at.planqton.fytfm.data.PresetRepository
import at.planqton.fytfm.data.RadioStation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

    // One-shot error event stream — Phase A consumers (MainActivity) collect
    // these to drive toasts. Replay = 0 so a late subscriber doesn't re-toast
    // an old error; extraBuffer = 16 to absorb error bursts without dropping.
    private val _errorEvents = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val errorEvents: SharedFlow<String> = _errorEvents.asSharedFlow()

    /** One-shot recording-lifecycle events — Phase A target for the 3
     *  DAB-recording callbacks that previously lived on RadioController. */
    sealed class RecordingEvent {
        object Started : RecordingEvent()
        data class Stopped(val file: java.io.File?) : RecordingEvent()
        data class Failed(val message: String) : RecordingEvent()
    }
    private val _recordingEvents = MutableSharedFlow<RecordingEvent>(extraBufferCapacity = 8)
    val recordingEvents: SharedFlow<RecordingEvent> = _recordingEvents.asSharedFlow()

    /** EPG-data-arrived events. Non-null payloads only — null is dropped
     *  in [handleEvent] so collectors don't need a null check. */
    private val _epgEvents = MutableSharedFlow<at.planqton.fytfm.dab.EpgData>(extraBufferCapacity = 4)
    val epgEvents: SharedFlow<at.planqton.fytfm.dab.EpgData> = _epgEvents.asSharedFlow()

    /** DAB service-stopped pulse. Used by MainActivity to stop the audio
     *  visualizer; replay = 0 so a late subscriber doesn't auto-stop. */
    private val _dabServiceStoppedEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val dabServiceStoppedEvents: SharedFlow<Unit> = _dabServiceStoppedEvents.asSharedFlow()

    /** DL+ tag updates (artist, title nullable — broadcaster sends them
     *  separately). State also lands on dabState.dlPlusArtist/Title; the
     *  flow is for one-shot side effects (e.g. Deezer cover lookup). */
    data class DlPlusEvent(val artist: String?, val title: String?)
    private val _dlPlusEvents = MutableSharedFlow<DlPlusEvent>(extraBufferCapacity = 8)
    val dlPlusEvents: SharedFlow<DlPlusEvent> = _dlPlusEvents.asSharedFlow()

    /** MOT slideshow bitmap arrivals. Bitmap also lands on
     *  dabState.slideshow; flow is for one-shot side effects (logging,
     *  cover-source refresh). */
    private val _slideshowEvents = MutableSharedFlow<android.graphics.Bitmap>(extraBufferCapacity = 4)
    val slideshowEvents: SharedFlow<android.graphics.Bitmap> = _slideshowEvents.asSharedFlow()

    /** DAB tuner-ready pulse — fires once when the USB tuner finishes
     *  initializing. Triggers MainActivity's carousel populate + power-button refresh. */
    private val _dabTunerReadyEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val dabTunerReadyEvents: SharedFlow<Unit> = _dabTunerReadyEvents.asSharedFlow()

    /** DAB audio-track started — payload is the AudioSessionId required
     *  by the visualizer. Fires once per service-tune. */
    private val _dabAudioStartedEvents = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val dabAudioStartedEvents: SharedFlow<Int> = _dabAudioStartedEvents.asSharedFlow()

    /** DLS (Dynamic Label Segment) text arrivals. State also lands on
     *  dabState.dls; flow is for one-shot side effects (DLS log, parser
     *  log, Deezer search). */
    private val _dlsEvents = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val dlsEvents: SharedFlow<String> = _dlsEvents.asSharedFlow()

    /** Reception-stats updates. State (sync/quality/snr) also lands on
     *  dabState; flow is for the high-frequency debug-overlay refresh
     *  that doesn't want to re-render uiState collectors on every tick. */
    data class ReceptionStats(val sync: Boolean, val quality: String, val snr: Int)
    private val _receptionStatsEvents = MutableSharedFlow<ReceptionStats>(extraBufferCapacity = 8)
    val receptionStatsEvents: SharedFlow<ReceptionStats> = _receptionStatsEvents.asSharedFlow()

    /** DAB service-started events — fires when a new service is tuned.
     *  State (currentStation + dabState fields) also lands on uiState; flow
     *  is for one-shot side effects (cover refresh, RDS reset, log entry). */
    private val _dabServiceStartedEvents = MutableSharedFlow<DabStation>(extraBufferCapacity = 4)
    val dabServiceStartedEvents: SharedFlow<DabStation> = _dabServiceStartedEvents.asSharedFlow()

    // Event callbacks (simple approach)
    var onStationChanged: ((RadioStation) -> Unit)? = null
    var onFrequencyChanged: ((Float) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    init {
        // Assumes the owner (MainActivity) already called radioController.initialize().
        // VM is a passive observer — collects from the shared event flow instead of
        // stealing lambda-callback slots, so MainActivity's existing callbacks keep firing.
        observeControllerEvents()
        loadInitialState()
    }

    private fun observeControllerEvents() {
        viewModelScope.launch {
            radioController.events.collect { event -> handleEvent(event) }
        }
    }

    private fun handleEvent(event: RadioEvent) {
        when (event) {
            is RadioEvent.ModeChanged -> handleModeChanged(event.mode)
            is RadioEvent.RadioStateChanged -> handleRadioStateChanged(event.isOn)
            is RadioEvent.FrequencyChanged -> handleFrequencyChanged(event.frequency)
            is RadioEvent.RdsUpdate ->
                handleRdsUpdate(event.ps, event.rt, event.rssi, event.pi, event.pty)
            is RadioEvent.DabServiceStarted -> {
                handleDabServiceStarted(event.station)
                _dabServiceStartedEvents.tryEmit(event.station)
            }
            is RadioEvent.DabDynamicLabel -> {
                handleDabDynamicLabel(event.dls)
                _dlsEvents.tryEmit(event.dls)
            }
            is RadioEvent.DabSlideshow -> {
                handleDabSlideshow(event.bitmap)
                _slideshowEvents.tryEmit(event.bitmap)
            }
            is RadioEvent.DabDlPlus -> {
                updateDabDlPlus(event.artist, event.title)
                _dlPlusEvents.tryEmit(DlPlusEvent(event.artist, event.title))
            }
            is RadioEvent.ErrorEvent -> {
                _lastError = event.message
                _errorEvents.tryEmit(event.message)
                onError?.invoke(event.message)
            }
            RadioEvent.DabRecordingStarted -> {
                setDabRecording(true)
                _recordingEvents.tryEmit(RecordingEvent.Started)
            }
            is RadioEvent.DabRecordingStopped -> {
                setDabRecording(false)
                _recordingEvents.tryEmit(RecordingEvent.Stopped(event.file))
            }
            is RadioEvent.DabRecordingError -> {
                setDabRecording(false)
                _recordingEvents.tryEmit(RecordingEvent.Failed(event.error))
            }
            RadioEvent.DabServiceStopped -> _dabServiceStoppedEvents.tryEmit(Unit)
            is RadioEvent.DabEpgReceived -> {
                (event.data as? at.planqton.fytfm.dab.EpgData)?.let { _epgEvents.tryEmit(it) }
            }
            RadioEvent.DabTunerReady -> _dabTunerReadyEvents.tryEmit(Unit)
            is RadioEvent.DabAudioStarted -> _dabAudioStartedEvents.tryEmit(event.sessionId)
            is RadioEvent.DabReceptionStats -> {
                updateDabSignalQuality(quality = event.quality, sync = event.sync, snr = event.snr)
                _receptionStatsEvents.tryEmit(ReceptionStats(event.sync, event.quality, event.snr))
            }
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
     * Togglet Favoriten-Filter (in-memory only — does NOT persist).
     * Use [toggleFavoritesOnlyForMode] when you also need the per-mode prefs write.
     */
    fun toggleFavoritesOnly() {
        _stationListState.update { state ->
            state.copy(showFavoritesOnly = !state.showFavoritesOnly)
        }
    }

    /**
     * Loads the favourites-filter flag for [mode] from PresetRepository
     * (FM/AM/DAB get separate prefs entries) and writes it onto stationListState.
     * Call from MainActivity on app-start and on every mode change.
     */
    fun loadFavoritesFilterForMode(mode: FrequencyScaleView.RadioMode) {
        val flag = when (mode) {
            FrequencyScaleView.RadioMode.FM -> presetRepository.isShowFavoritesOnlyFm()
            FrequencyScaleView.RadioMode.AM -> presetRepository.isShowFavoritesOnlyAm()
            FrequencyScaleView.RadioMode.DAB,
            FrequencyScaleView.RadioMode.DAB_DEV -> presetRepository.isShowFavoritesOnlyDab()
        }
        _stationListState.update { it.copy(showFavoritesOnly = flag) }
    }

    /**
     * Toggles the favourites filter AND persists the new value to the
     * mode-specific prefs key. Returns the new flag for callers that want
     * to fire UI side effects (toast, button icon).
     */
    fun toggleFavoritesOnlyForMode(mode: FrequencyScaleView.RadioMode): Boolean {
        toggleFavoritesOnly()
        val newValue = _stationListState.value.showFavoritesOnly
        when (mode) {
            FrequencyScaleView.RadioMode.FM -> presetRepository.setShowFavoritesOnlyFm(newValue)
            FrequencyScaleView.RadioMode.AM -> presetRepository.setShowFavoritesOnlyAm(newValue)
            FrequencyScaleView.RadioMode.DAB,
            FrequencyScaleView.RadioMode.DAB_DEV -> presetRepository.setShowFavoritesOnlyDab(newValue)
        }
        return newValue
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
                        ensembleLabel = station.ensembleLabel,
                        // Clear per-service fields so the previous service's
                        // DLS/slideshow/DL+ doesn't bleed into the new one.
                        dls = null,
                        slideshow = null,
                        dlPlusArtist = null,
                        dlPlusTitle = null,
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
     * Updates Deezer track info (called from DeezerClient callback). [coverSourceKey]
     * is the RT/DLS the coverPath was matched against — used as a freshness key
     * so the consumer can tell when the cover is stale (RT/DLS changed but search
     * for the new RT hasn't completed yet).
     */
    fun updateDeezerTrack(
        track: at.planqton.fytfm.deezer.TrackInfo?,
        coverPath: String?,
        coverSourceKey: String? = null,
    ) {
        _uiState.update { state ->
            when (state) {
                is RadioUiState.FmAm -> state.copy(
                    deezerState = state.deezerState.copy(
                        currentTrack = track,
                        coverPath = coverPath,
                        coverSourceKey = coverSourceKey,
                        isSearching = false,
                    )
                )
                is RadioUiState.Dab -> state.copy(
                    deezerState = state.deezerState.copy(
                        currentTrack = track,
                        coverPath = coverPath,
                        coverSourceKey = coverSourceKey,
                        isSearching = false,
                    )
                )
                else -> state
            }
        }
    }

    /**
     * Atomically updates all 5 Deezer debug-overlay fields plus the lastQuery
     * + currentTrack. Called from the RtCombiner's onDebugUpdate callback.
     * Used by both the in-app debug overlay and the bug-report builder.
     */
    fun updateDeezerDebugInfo(
        status: String?,
        originalRt: String?,
        strippedRt: String?,
        query: String?,
        trackInfo: at.planqton.fytfm.deezer.TrackInfo?,
    ) {
        _uiState.update { state ->
            when (state) {
                is RadioUiState.FmAm -> state.copy(
                    deezerState = state.deezerState.copy(
                        debugStatus = status,
                        debugOriginalRt = originalRt,
                        debugStrippedRt = strippedRt,
                        lastQuery = query,
                        currentTrack = trackInfo,
                    )
                )
                is RadioUiState.Dab -> state.copy(
                    deezerState = state.deezerState.copy(
                        debugStatus = status,
                        debugOriginalRt = originalRt,
                        debugStrippedRt = strippedRt,
                        lastQuery = query,
                        currentTrack = trackInfo,
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
     * Updates DAB signal quality (string label like "good"/"fair"/"poor"
     * from the OMRI tuner) plus sync flag and SNR.
     */
    fun updateDabSignalQuality(quality: String, sync: Boolean, snr: Int = 0) {
        _uiState.update { state ->
            when (state) {
                is RadioUiState.Dab -> state.copy(
                    dabState = state.dabState.copy(
                        receptionQuality = quality,
                        snr = snr,
                        signalSync = sync,
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
