package at.planqton.fytfm.controller

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import at.planqton.fytfm.FrequencyScaleView
import at.planqton.fytfm.RdsManager
import at.planqton.fytfm.TWUtilHelper
import at.planqton.fytfm.dab.DabStation
import at.planqton.fytfm.dab.DabTunerManager
import at.planqton.fytfm.data.PresetRepository
import at.planqton.fytfm.data.RadioStation
import com.android.fmradio.FmNative
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Haupt-Controller der FM/AM und DAB koordiniert.
 * Bietet eine einheitliche Schnittstelle für die MainActivity.
 * Integriert TWUtil für FYT Head Unit MCU-Kommunikation.
 */
class RadioController(
    private val context: Context,
    fmNative: FmNative,
    rdsManager: RdsManager,
    dabTunerManager: DabTunerManager,
    private val presetRepository: PresetRepository,
    twUtil: TWUtilHelper? = null
) {
    companion object {
        private const val TAG = "RadioController"
    }

    val fmAmController = FmAmController(context, fmNative, rdsManager, presetRepository, twUtil)
    val dabController = DabController(context, dabTunerManager, presetRepository)

    var currentMode = FrequencyScaleView.RadioMode.FM
        private set

    // Unified callbacks
    var onModeChanged: ((FrequencyScaleView.RadioMode) -> Unit)? = null
    var onRadioStateChanged: ((isOn: Boolean) -> Unit)? = null
    var onFrequencyChanged: ((frequency: Float) -> Unit)? = null
    var onStationChanged: ((RadioStation?) -> Unit)? = null
    var onRdsUpdate: ((ps: String, rt: String, rssi: Int, pi: Int, pty: Int) -> Unit)? = null
    var onDabTunerReady: (() -> Unit)? = null
    var onDabServiceStarted: ((DabStation) -> Unit)? = null
    var onDabDynamicLabel: ((String) -> Unit)? = null
    var onDabSlideshow: ((Bitmap) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    // Multi-Listener event stream (parallel zu den Lambda-Properties oben).
    // Bestehende Callbacks bleiben funktionsfähig; ViewModels koennen
    // unabhaengig per collect{} mithoeren ohne Callbacks zu ueberschreiben.
    private val _events = MutableSharedFlow<RadioEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<RadioEvent> = _events.asSharedFlow()

    /**
     * Initialisiert beide Controller.
     */
    fun initialize() {
        fmAmController.initialize()
        dabController.initialize()
        setupCallbacks()
        setupExtendedDabCallbacks()
        currentMode = fmAmController.getLastMode()
    }

    private fun setupCallbacks() {
        // FM/AM Callbacks
        fmAmController.onRadioStateChanged = { isOn ->
            onRadioStateChanged?.invoke(isOn)
            _events.tryEmit(RadioEvent.RadioStateChanged(isOn))
        }
        fmAmController.onFrequencyChanged = { freq ->
            onFrequencyChanged?.invoke(freq)
            _events.tryEmit(RadioEvent.FrequencyChanged(freq))
        }
        fmAmController.onRdsUpdate = { ps, rt, rssi, pi, pty ->
            onRdsUpdate?.invoke(ps, rt, rssi, pi, pty)
            _events.tryEmit(RadioEvent.RdsUpdate(ps, rt, rssi, pi, pty))
        }
        fmAmController.onError = { msg ->
            onError?.invoke(msg)
            _events.tryEmit(RadioEvent.ErrorEvent(msg))
        }

        // DAB Callbacks
        dabController.onTunerReady = {
            onDabTunerReady?.invoke()
            _events.tryEmit(RadioEvent.DabTunerReady)
            dabController.tuneToLastOrFirst()
        }
        dabController.onServiceStarted = { station ->
            onDabServiceStarted?.invoke(station)
            _events.tryEmit(RadioEvent.DabServiceStarted(station))
        }
        dabController.onDynamicLabel = { dls ->
            onDabDynamicLabel?.invoke(dls)
            _events.tryEmit(RadioEvent.DabDynamicLabel(dls))
        }
        dabController.onSlideshow = { bitmap ->
            onDabSlideshow?.invoke(bitmap)
            _events.tryEmit(RadioEvent.DabSlideshow(bitmap))
        }
        dabController.onTunerError = { msg ->
            onError?.invoke(msg)
            _events.tryEmit(RadioEvent.ErrorEvent(msg))
        }
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
        onModeChanged?.invoke(mode)
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
     * Seek starten (nur FM/AM).
     */
    fun seek(forward: Boolean) {
        if (currentMode != FrequencyScaleView.RadioMode.DAB) {
            fmAmController.seek(forward)
        }
    }

    /**
     * Zu einer Station tunen.
     */
    fun tuneStation(station: RadioStation): Boolean {
        return when {
            station.isDab -> {
                if (currentMode != FrequencyScaleView.RadioMode.DAB) {
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
     * Zu einer Frequenz tunen (nur FM/AM).
     */
    fun tune(frequency: Float): Boolean {
        return if (currentMode != FrequencyScaleView.RadioMode.DAB) {
            fmAmController.tune(frequency)
        } else {
            false
        }
    }

    /**
     * Aktuelle Frequenz (nur FM/AM).
     */
    fun getCurrentFrequency(): Float = fmAmController.currentFrequency

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
            else -> FmNative.isLibraryLoaded()
        }
    }

    /**
     * Gibt die Stationen für den aktuellen Modus zurück.
     */
    fun getStationsForCurrentMode(): List<RadioStation> {
        return when (currentMode) {
            FrequencyScaleView.RadioMode.FM -> presetRepository.loadFmStations()
            FrequencyScaleView.RadioMode.AM -> presetRepository.loadAmStations()
            FrequencyScaleView.RadioMode.DAB, FrequencyScaleView.RadioMode.DAB_DEV -> presetRepository.loadDabStations()
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
     * Tuner-Settings anwenden.
     */
    fun setMonoMode(enabled: Boolean) = fmAmController.setMonoMode(enabled)
    fun setLocalMode(enabled: Boolean) = fmAmController.setLocalMode(enabled)
    fun setRadioArea(area: Int) = fmAmController.setRadioArea(area)
    fun applyTunerSettings() = fmAmController.applyTunerSettings()

    /**
     * Tuned zu einer Frequenz ohne Speicherung (für Seek-Operationen).
     */
    fun tuneRaw(frequency: Float): Boolean = fmAmController.tuneRaw(frequency)

    /**
     * Power On mit detaillierten Schritten (für Legacy-Kompatibilität).
     */
    fun powerOnFmAmWithSteps(frequency: Float): Triple<Boolean, Boolean, Boolean> {
        val result = fmAmController.powerOnWithSteps(frequency)
        if (result.first && result.second) {
            onRadioStateChanged?.invoke(true)
        }
        return result
    }

    /**
     * Vollständige Power-On Sequenz für FYT Head Units.
     * Beinhaltet TWUtil MCU-Kommunikation, FmService, und FM-Chip.
     */
    fun powerOnFmAmFull(frequency: Float): Boolean {
        val success = fmAmController.powerOnFull(frequency)
        if (success) {
            onRadioStateChanged?.invoke(true)
        }
        return success
    }

    /**
     * Vollständige Power-Off Sequenz für FYT Head Units.
     */
    fun powerOffFmAmFull() {
        fmAmController.powerOffFull()
        onRadioStateChanged?.invoke(false)
    }

    /**
     * Power Off für FM/AM.
     */
    fun powerOffFmAm() {
        fmAmController.powerOff()
        onRadioStateChanged?.invoke(false)
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
        onRadioStateChanged?.invoke(false)
    }

    /**
     * Power On für DAB.
     */
    fun powerOnDab(): Boolean {
        val success = dabController.powerOn()
        if (success) {
            onRadioStateChanged?.invoke(true)
        }
        return success
    }

    // ========== Extended Callbacks ==========

    var onDabServiceStopped: (() -> Unit)? = null
    var onDabAudioStarted: ((Int) -> Unit)? = null
    var onDabDlPlus: ((artist: String?, title: String?) -> Unit)? = null
    var onDabReceptionStats: ((sync: Boolean, quality: String, snr: Int) -> Unit)? = null
    var onDabRecordingStarted: (() -> Unit)? = null
    var onDabRecordingStopped: ((java.io.File?) -> Unit)? = null
    var onDabRecordingError: ((String) -> Unit)? = null
    var onDabEpgReceived: ((Any?) -> Unit)? = null

    /**
     * Erweiterte DAB-Callbacks setzen.
     */
    fun setupExtendedDabCallbacks() {
        dabController.onServiceStopped = {
            onDabServiceStopped?.invoke()
            _events.tryEmit(RadioEvent.DabServiceStopped)
        }
        dabController.onAudioStarted = { sessionId ->
            onDabAudioStarted?.invoke(sessionId)
            _events.tryEmit(RadioEvent.DabAudioStarted(sessionId))
        }
        dabController.onDlPlus = { artist, title ->
            onDabDlPlus?.invoke(artist, title)
            _events.tryEmit(RadioEvent.DabDlPlus(artist, title))
        }
        dabController.onReceptionStats = { sync, quality, snr ->
            onDabReceptionStats?.invoke(sync, quality, snr)
            _events.tryEmit(RadioEvent.DabReceptionStats(sync, quality, snr))
        }
        dabController.onRecordingStarted = {
            onDabRecordingStarted?.invoke()
            _events.tryEmit(RadioEvent.DabRecordingStarted)
        }
        dabController.onRecordingStopped = { file ->
            onDabRecordingStopped?.invoke(file)
            _events.tryEmit(RadioEvent.DabRecordingStopped(file))
        }
        dabController.onRecordingError = { error ->
            onDabRecordingError?.invoke(error)
            _events.tryEmit(RadioEvent.DabRecordingError(error))
        }
        dabController.onEpgDataReceived = { data ->
            onDabEpgReceived?.invoke(data)
            _events.tryEmit(RadioEvent.DabEpgReceived(data))
        }
    }
}
