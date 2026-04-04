package at.planqton.fytfm.controller

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import at.planqton.fytfm.FrequencyScaleView
import at.planqton.fytfm.RdsManager
import at.planqton.fytfm.dab.DabStation
import at.planqton.fytfm.dab.DabTunerManager
import at.planqton.fytfm.data.PresetRepository
import at.planqton.fytfm.data.RadioStation
import com.android.fmradio.FmNative

/**
 * Haupt-Controller der FM/AM und DAB koordiniert.
 * Bietet eine einheitliche Schnittstelle für die MainActivity.
 */
class RadioController(
    private val context: Context,
    fmNative: FmNative,
    rdsManager: RdsManager,
    dabTunerManager: DabTunerManager,
    private val presetRepository: PresetRepository
) {
    companion object {
        private const val TAG = "RadioController"
    }

    val fmAmController = FmAmController(context, fmNative, rdsManager, presetRepository)
    val dabController = DabController(context, dabTunerManager, presetRepository)

    var currentMode = FrequencyScaleView.RadioMode.FM
        private set

    // Unified callbacks
    var onModeChanged: ((FrequencyScaleView.RadioMode) -> Unit)? = null
    var onRadioStateChanged: ((isOn: Boolean) -> Unit)? = null
    var onFrequencyChanged: ((frequency: Float) -> Unit)? = null
    var onStationChanged: ((RadioStation?) -> Unit)? = null
    var onRdsUpdate: ((ps: String, rt: String, rssi: Int, pi: Int, pty: Int) -> Unit)? = null
    var onDabServiceStarted: ((DabStation) -> Unit)? = null
    var onDabDynamicLabel: ((String) -> Unit)? = null
    var onDabSlideshow: ((Bitmap) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    /**
     * Initialisiert beide Controller.
     */
    fun initialize() {
        fmAmController.initialize()
        dabController.initialize()
        setupCallbacks()
        currentMode = fmAmController.getLastMode()
    }

    private fun setupCallbacks() {
        // FM/AM Callbacks
        fmAmController.onRadioStateChanged = { isOn ->
            onRadioStateChanged?.invoke(isOn)
        }
        fmAmController.onFrequencyChanged = { freq ->
            onFrequencyChanged?.invoke(freq)
        }
        fmAmController.onRdsUpdate = { ps, rt, rssi, pi, pty ->
            onRdsUpdate?.invoke(ps, rt, rssi, pi, pty)
        }
        fmAmController.onError = { msg ->
            onError?.invoke(msg)
        }

        // DAB Callbacks
        dabController.onTunerReady = {
            dabController.tuneToLastOrFirst()
        }
        dabController.onServiceStarted = { station ->
            onDabServiceStarted?.invoke(station)
        }
        dabController.onDynamicLabel = { dls ->
            onDabDynamicLabel?.invoke(dls)
        }
        dabController.onSlideshow = { bitmap ->
            onDabSlideshow?.invoke(bitmap)
        }
        dabController.onTunerError = { msg ->
            onError?.invoke(msg)
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
            FrequencyScaleView.RadioMode.DAB -> {
                if (dabController.isDabOn) {
                    dabController.powerOff()
                }
            }
        }

        currentMode = mode
        onModeChanged?.invoke(mode)

        // Neuen Modus vorbereiten
        when (mode) {
            FrequencyScaleView.RadioMode.FM, FrequencyScaleView.RadioMode.AM -> {
                fmAmController.setMode(mode)
            }
            FrequencyScaleView.RadioMode.DAB -> {
                // DAB wird separat eingeschaltet
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
            FrequencyScaleView.RadioMode.DAB -> {
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
            FrequencyScaleView.RadioMode.DAB -> {
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
            FrequencyScaleView.RadioMode.DAB -> {
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
            FrequencyScaleView.RadioMode.DAB -> presetRepository.loadDabStations()
        }
    }
}
