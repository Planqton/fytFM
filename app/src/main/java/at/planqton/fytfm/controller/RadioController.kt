package at.planqton.fytfm.controller

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import at.planqton.fytfm.FrequencyScaleView
import at.planqton.fytfm.RdsManager
import at.planqton.fytfm.TWUtilHelper
import at.planqton.fytfm.dab.DabStation
import at.planqton.fytfm.dab.DabTunerBackend
import at.planqton.fytfm.dab.DabTunerManager
import at.planqton.fytfm.dab.MockDabTunerManager
import at.planqton.fytfm.data.PresetRepository
import at.planqton.fytfm.data.RadioStation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Haupt-Controller der FM/AM und DAB koordiniert.
 * Bietet eine einheitliche Schnittstelle für die MainActivity.
 * Integriert TWUtil für FYT Head Unit MCU-Kommunikation.
 */
class RadioController(
    private val context: Context,
    fmNative: FmNativeApi,
    rdsManager: RdsManager,
    private val realDabBackend: DabTunerManager,
    private val mockDabBackend: MockDabTunerManager,
    private val presetRepository: PresetRepository,
    twUtil: TWUtilHelper? = null
) {
    companion object {
        private const val TAG = "RadioController"
        // Shared with FmAmController so the existing persisted mode value survives the refactor.
        private const val PREFS_NAME = "fytfm_fmam"
        private const val KEY_LAST_RADIO_MODE = "last_radio_mode"
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    val fmAmController = FmAmController(context, fmNative, rdsManager, presetRepository, twUtil)
    val dabController = DabController(context, realDabBackend, presetRepository)

    /**
     * Switch DAB backend to mock (for DAB_DEV mode). Caller is responsible
     * for powering off DAB BEFORE calling this — the swap clears callbacks
     * but cannot deinitialize the previous backend safely on its own.
     */
    fun useMockDabBackend() {
        Log.i(TAG, "Switching DAB backend → MOCK")
        dabController.setBackend(mockDabBackend)
    }

    /** Switch DAB backend to the real OMRI tuner (for DAB mode). */
    fun useRealDabBackend() {
        Log.i(TAG, "Switching DAB backend → REAL")
        dabController.setBackend(realDabBackend)
    }

    /** True if the mock backend is currently active. */
    fun isMockDabBackendActive(): Boolean = dabController.backend === mockDabBackend

    var currentMode = FrequencyScaleView.RadioMode.FM
        private set

    // Multi-Listener event stream — the only public mechanism for observing
    // controller state changes. Lambda-callback properties were retired in
    // Phase A once MainActivity migrated to collecting from the VM.
    // Bestehende Callbacks bleiben funktionsfähig; ViewModels koennen
    // unabhaengig per collect{} mithoeren ohne Callbacks zu ueberschreiben.
    private val _events = MutableSharedFlow<RadioEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<RadioEvent> = _events.asSharedFlow()

    /**
     * Coroutine scope owning the sub-controller event collectors. Cancelled
     * by [release]; tests that build many controllers should call it to
     * avoid leaking collectors across test boundaries.
     */
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Initialisiert beide Controller.
     */
    fun initialize() {
        fmAmController.initialize()
        dabController.initialize()
        startEventBridge()
        currentMode = loadLastMode()
        // FmAmController hat keinen eigenen Mode-Zustand mehr — fuer FM/AM
        // muessen wir seinen Cache synchron halten (Step-Groesse, Frequenz-Key,
        // Stations-Liste). Fuer DAB/DAB_DEV bleibt FmAmController beim Default,
        // das ist egal, weil dann FM/AM-Operationen nicht laufen.
        if (currentMode == FrequencyScaleView.RadioMode.FM ||
            currentMode == FrequencyScaleView.RadioMode.AM) {
            fmAmController.setMode(currentMode)
        }
    }

    private fun loadLastMode(): FrequencyScaleView.RadioMode {
        val modeName = prefs.getString(KEY_LAST_RADIO_MODE, FrequencyScaleView.RadioMode.FM.name)
        return try {
            FrequencyScaleView.RadioMode.valueOf(modeName ?: "FM")
        } catch (e: Exception) {
            FrequencyScaleView.RadioMode.FM
        }
    }

    private fun saveLastMode() {
        prefs.edit().putString(KEY_LAST_RADIO_MODE, currentMode.name).apply()
    }

    /**
     * Bridges the sub-controller event flows onto our outer [events] flow.
     * Replaces the old `setupCallbacks` + `setupExtendedDabCallbacks` lambda
     * assignments — sub-controllers now expose [FmAmController.events] and
     * [DabController.events] which we collect from independently. Multiple
     * subscribers can now observe sub-controllers directly without us
     * stepping on each other's lambda assignments.
     */
    private fun startEventBridge() {
        controllerScope.launch {
            fmAmController.events.collect { event ->
                when (event) {
                    is FmAmEvent.RadioStateChanged -> _events.tryEmit(RadioEvent.RadioStateChanged(event.isOn))
                    is FmAmEvent.FrequencyChanged -> _events.tryEmit(RadioEvent.FrequencyChanged(event.frequency))
                    is FmAmEvent.RdsUpdate -> _events.tryEmit(
                        RadioEvent.RdsUpdate(event.ps, event.rt, event.rssi, event.pi, event.pty)
                    )
                    is FmAmEvent.SeekComplete -> { /* not re-emitted; FrequencyChanged carries the new freq */ }
                    is FmAmEvent.Error -> _events.tryEmit(RadioEvent.ErrorEvent(event.message))
                }
            }
        }
        controllerScope.launch {
            dabController.events.collect { event ->
                when (event) {
                    DabEvent.TunerReady -> {
                        _events.tryEmit(RadioEvent.DabTunerReady)
                        dabController.tuneToLastOrFirst()
                    }
                    is DabEvent.ServiceStarted -> _events.tryEmit(RadioEvent.DabServiceStarted(event.station))
                    DabEvent.ServiceStopped -> _events.tryEmit(RadioEvent.DabServiceStopped)
                    is DabEvent.TunerError -> _events.tryEmit(RadioEvent.ErrorEvent(event.message))
                    is DabEvent.DynamicLabel -> _events.tryEmit(RadioEvent.DabDynamicLabel(event.dls))
                    is DabEvent.DlPlus -> _events.tryEmit(RadioEvent.DabDlPlus(event.artist, event.title))
                    is DabEvent.Slideshow -> _events.tryEmit(RadioEvent.DabSlideshow(event.bitmap))
                    is DabEvent.ReceptionStats -> _events.tryEmit(
                        RadioEvent.DabReceptionStats(event.sync, event.quality, event.snr)
                    )
                    is DabEvent.AudioStarted -> _events.tryEmit(RadioEvent.DabAudioStarted(event.audioSessionId))
                    DabEvent.RecordingStarted -> _events.tryEmit(RadioEvent.DabRecordingStarted)
                    is DabEvent.RecordingStopped -> _events.tryEmit(RadioEvent.DabRecordingStopped(event.file))
                    is DabEvent.RecordingError -> _events.tryEmit(RadioEvent.DabRecordingError(event.error))
                    is DabEvent.EpgReceived -> _events.tryEmit(RadioEvent.DabEpgReceived(event.data))
                }
            }
        }
    }

    /**
     * Cancel the event-bridge collectors. Call from the owning Activity's
     * `onDestroy` (or from test teardown) to avoid leaking the
     * [controllerScope]'s coroutines across lifecycle boundaries.
     */
    fun release() {
        controllerScope.cancel()
    }

    /**
     * Wechselt den Radio-Modus.
     */
    fun setMode(mode: FrequencyScaleView.RadioMode) {
        Log.i(TAG, "setMode: $currentMode -> $mode")

        if (mode == currentMode) return

        // Aktuellen Modus ausschalten
        when (currentMode) {
            FrequencyScaleView.RadioMode.FM, FrequencyScaleView.RadioMode.AM -> {
                if (fmAmController.isRadioOn) {
                    fmAmController.powerOff()
                }
            }
            FrequencyScaleView.RadioMode.DAB, FrequencyScaleView.RadioMode.DAB_DEV -> {
                if (dabController.isDabOn) {
                    dabController.powerOff()
                }
            }
        }

        currentMode = mode
        saveLastMode()
        _events.tryEmit(RadioEvent.ModeChanged(mode))

        // Neuen Modus vorbereiten
        when (mode) {
            FrequencyScaleView.RadioMode.FM, FrequencyScaleView.RadioMode.AM -> {
                fmAmController.setMode(mode)
            }
            FrequencyScaleView.RadioMode.DAB, FrequencyScaleView.RadioMode.DAB_DEV -> {
                // DAB/DAB Dev wird separat eingeschaltet
            }
        }
    }

    /**
     * Schaltet das Radio ein/aus (für aktuellen Modus).
     */
    fun togglePower(): Boolean {
        return when (currentMode) {
            FrequencyScaleView.RadioMode.FM, FrequencyScaleView.RadioMode.AM -> {
                fmAmController.togglePower()
            }
            FrequencyScaleView.RadioMode.DAB, FrequencyScaleView.RadioMode.DAB_DEV -> {
                dabController.togglePower()
            }
        }
    }

    /**
     * Prüft ob das Radio eingeschaltet ist.
     */
    fun isRadioOn(): Boolean {
        return when (currentMode) {
            FrequencyScaleView.RadioMode.FM, FrequencyScaleView.RadioMode.AM -> {
                fmAmController.isRadioOn
            }
            FrequencyScaleView.RadioMode.DAB, FrequencyScaleView.RadioMode.DAB_DEV -> {
                dabController.isDabOn
            }
        }
    }

    /**
     * Zum nächsten/vorherigen Sender wechseln.
     */
    fun skipStation(forward: Boolean): RadioStation? {
        return when (currentMode) {
            FrequencyScaleView.RadioMode.FM, FrequencyScaleView.RadioMode.AM -> {
                fmAmController.skipStation(forward)
            }
            FrequencyScaleView.RadioMode.DAB, FrequencyScaleView.RadioMode.DAB_DEV -> {
                dabController.skipStation(forward)
            }
        }
    }

    /**
     * Seek starten (nur FM/AM — nicht DAB+ und nicht DAB Demo).
     */
    fun seek(forward: Boolean) {
        if (currentMode != FrequencyScaleView.RadioMode.DAB &&
            currentMode != FrequencyScaleView.RadioMode.DAB_DEV) {
            fmAmController.seek(forward)
        }
    }

    /**
     * Zu einer Station tunen.
     */
    fun tuneStation(station: RadioStation): Boolean {
        return when {
            station.isDab -> {
                // Stay in whichever DAB-mode (real or demo) is currently
                // active. Demo stations have service-IDs ≥ 2000 so they
                // simply won't be tuned to by the real backend, and vice
                // versa — but never auto-switch the user's mode here.
                if (currentMode != FrequencyScaleView.RadioMode.DAB &&
                    currentMode != FrequencyScaleView.RadioMode.DAB_DEV) {
                    setMode(FrequencyScaleView.RadioMode.DAB)
                }
                dabController.tuneStation(station)
            }
            station.isAM -> {
                if (currentMode != FrequencyScaleView.RadioMode.AM) {
                    setMode(FrequencyScaleView.RadioMode.AM)
                }
                fmAmController.tuneStation(station)
            }
            else -> {
                if (currentMode != FrequencyScaleView.RadioMode.FM) {
                    setMode(FrequencyScaleView.RadioMode.FM)
                }
                fmAmController.tuneStation(station)
            }
        }
    }

    /**
     * Zu einer Frequenz tunen (nur FM/AM — nicht DAB+ und nicht DAB Demo).
     */
    fun tune(frequency: Float): Boolean {
        return if (currentMode != FrequencyScaleView.RadioMode.DAB &&
            currentMode != FrequencyScaleView.RadioMode.DAB_DEV) {
            fmAmController.tune(frequency)
        } else {
            false
        }
    }

    /**
     * Aktuelle Frequenz (nur FM/AM).
     */
    fun getCurrentFrequency(): Float = fmAmController.currentFrequency

    /** Persistiert die aktuelle UI-Frequenz im FM/AM-Pref-Slot, ohne den
     *  Chip umzuprogrammieren. Aufrufer ist der UI-tune-Pfad, der den Chip
     *  schon via [RdsManager.tune] / [FmNative.tune] selbst tuned. */
    fun persistFrequency(frequency: Float) = fmAmController.persistFrequency(frequency)

    /**
     * Aktueller DAB Service.
     */
    fun getCurrentDabService(): DabStation? = dabController.getCurrentService()

    /**
     * Prüft ob ein Tuner für den Modus verfügbar ist.
     */
    fun isTunerAvailable(mode: FrequencyScaleView.RadioMode): Boolean {
        return when (mode) {
            FrequencyScaleView.RadioMode.DAB -> dabController.isDabAvailable()
            else -> fmAmController.isLibraryLoaded()
        }
    }

    /**
     * Gibt die Stationen für den aktuellen Modus zurück.
     * DAB and DAB_DEV use separate persistence: real-DAB scan results never
     * appear in demo mode and demo stations never leak into real DAB.
     */
    fun getStationsForCurrentMode(): List<RadioStation> {
        return when (currentMode) {
            FrequencyScaleView.RadioMode.FM -> presetRepository.loadFmStations()
            FrequencyScaleView.RadioMode.AM -> presetRepository.loadAmStations()
            FrequencyScaleView.RadioMode.DAB -> presetRepository.loadDabStations()
            FrequencyScaleView.RadioMode.DAB_DEV -> presetRepository.loadDabDevStations()
        }
    }

    // ========== FM/AM Specific ==========

    /**
     * Setzt den Mute-Status (FM/AM).
     * @return int result from native (0 = success)
     */
    fun setMute(mute: Boolean): Int {
        return fmAmController.setMute(mute)
    }

    /**
     * Gibt den RSSI zurück (FM/AM).
     */
    fun getRssi(): Int = fmAmController.getRssi()

    /**
     * Tuned zu einer Frequenz ohne Speicherung (für Seek-Operationen).
     */
    fun tuneRaw(frequency: Float): Boolean = fmAmController.tuneRaw(frequency)

    /**
     * Vollständige Power-On Sequenz für FYT Head Units.
     * Beinhaltet TWUtil MCU-Kommunikation, FmService, und FM-Chip.
     */
    fun powerOnFmAmFull(frequency: Float): Boolean {
        val success = fmAmController.powerOnFull(frequency)
        if (success) {
            _events.tryEmit(RadioEvent.RadioStateChanged(true))
        }
        return success
    }

    /**
     * Vollständige Power-Off Sequenz für FYT Head Units.
     */
    fun powerOffFmAmFull() {
        fmAmController.powerOffFull()
        _events.tryEmit(RadioEvent.RadioStateChanged(false))
    }

    /**
     * Power Off für FM/AM.
     */
    fun powerOffFmAm() {
        fmAmController.powerOff()
        _events.tryEmit(RadioEvent.RadioStateChanged(false))
    }

    // ========== DAB Specific ==========

    /**
     * DAB Audio Session ID.
     */
    fun getDabAudioSessionId(): Int = dabController.getAudioSessionId()

    /**
     * DAB Service direkt tunen.
     */
    fun tuneDabService(serviceId: Int, ensembleId: Int): Boolean {
        return dabController.tuneService(serviceId, ensembleId)
    }

    /**
     * DAB Recording.
     */
    fun isDabRecording(): Boolean = dabController.isRecording()
    fun startDabRecording(context: Context, path: String): Boolean = dabController.startRecording(context, path)
    fun stopDabRecording() = dabController.stopRecording()

    /**
     * DAB EPG.
     */
    fun getDabEpgData(): at.planqton.fytfm.dab.EpgData? = dabController.getCurrentEpgData() as? at.planqton.fytfm.dab.EpgData

    /**
     * DAB verfügbar?
     */
    fun isDabAvailable(): Boolean = dabController.isDabAvailable()

    /**
     * Prüft ob DAB eingeschaltet ist.
     */
    fun isDabOn(): Boolean = dabController.isDabOn

    /**
     * Prüft ob ein DAB-Tuner vorhanden ist.
     */
    fun hasDabTuner(): Boolean = dabController.hasTuner()

    /**
     * Power Off für DAB.
     */
    fun powerOffDab() {
        dabController.powerOff()
        _events.tryEmit(RadioEvent.RadioStateChanged(false))
    }

    /**
     * Power On für DAB.
     */
    fun powerOnDab(): Boolean {
        val success = dabController.powerOn()
        if (success) {
            _events.tryEmit(RadioEvent.RadioStateChanged(true))
        }
        return success
    }

}
