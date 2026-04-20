package at.planqton.fytfm.controller

import android.content.Context
import android.content.Intent
import android.util.Log
import at.planqton.fytfm.FrequencyScaleView
import at.planqton.fytfm.RdsManager
import at.planqton.fytfm.TWUtilHelper
import at.planqton.fytfm.data.PresetRepository
import at.planqton.fytfm.data.RadioStation
import com.android.fmradio.FmNative

/**
 * Controller für FM/AM Radio Logik.
 * Kapselt FM/AM-State und Tuner-Operationen aus MainActivity.
 * Integriert TWUtil für FYT Head Unit MCU-Kommunikation.
 */
class FmAmController(
    private val context: Context,
    private val fmNative: FmNative,
    private val rdsManager: RdsManager,
    private val presetRepository: PresetRepository,
    private val twUtil: TWUtilHelper? = null
) {
    companion object {
        private const val TAG = "FmAmController"
        private const val PREFS_NAME = "fytfm_fmam"
        private const val KEY_LAST_FM_FREQUENCY = "last_fm_frequency"
        private const val KEY_LAST_AM_FREQUENCY = "last_am_frequency"
        private const val KEY_LAST_RADIO_MODE = "last_radio_mode"

        // Frequenzbereiche
        const val FM_MIN = 87.5f
        const val FM_MAX = 108.0f
        const val AM_MIN = 522f
        const val AM_MAX = 1620f
        const val FM_STEP = 0.1f
        const val AM_STEP = 9f
    }

    // State
    var isRadioOn = false
        private set
    var currentFrequency = 98.4f
        private set
    var currentMode = FrequencyScaleView.RadioMode.FM
        private set

    // RDS Data
    var currentPs: String = ""
        private set
    var currentRt: String = ""
        private set
    var currentPi: Int = 0
        private set
    var currentPty: Int = 0
        private set
    var currentRssi: Int = 0
        private set

    // Callbacks für UI-Updates
    var onRadioStateChanged: ((isOn: Boolean) -> Unit)? = null
    var onFrequencyChanged: ((frequency: Float) -> Unit)? = null
    var onRdsUpdate: ((ps: String, rt: String, rssi: Int, pi: Int, pty: Int) -> Unit)? = null
    var onSeekComplete: ((frequency: Float) -> Unit)? = null
    var onError: ((message: String) -> Unit)? = null

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Initialisiert den Controller und lädt gespeicherte Einstellungen.
     */
    fun initialize() {
        loadLastFrequency()
        loadLastMode()
        setupRdsCallback()
    }

    private fun setupRdsCallback() {
        // RDS callback wird über startPolling gesetzt
    }

    private val rdsCallback = RdsManager.RdsCallback { ps, rt, rssi, pi, pty, tp, ta, afList ->
        currentPs = ps ?: ""
        currentRt = rt ?: ""
        currentRssi = rssi
        currentPi = pi
        currentPty = pty
        onRdsUpdate?.invoke(currentPs, currentRt, currentRssi, currentPi, currentPty)
    }

    /**
     * Schaltet das Radio ein/aus.
     */
    fun togglePower(): Boolean {
        Log.i(TAG, "togglePower() isRadioOn=$isRadioOn mode=$currentMode")

        return if (isRadioOn) {
            powerOff()
            false
        } else {
            powerOn()
            true
        }
    }

    fun powerOn(): Boolean {
        Log.i(TAG, "Powering ON $currentMode at $currentFrequency")

        try {
            val success = fmNative.powerOn(currentFrequency)

            if (success) {
                isRadioOn = true

                // RDS aktivieren (nur für FM)
                if (currentMode == FrequencyScaleView.RadioMode.FM) {
                    rdsManager.enableRds()
                    rdsManager.startPolling(rdsCallback)
                }

                onRadioStateChanged?.invoke(true)
                return true
            } else {
                Log.e(TAG, "powerUp failed")
                onError?.invoke("Radio konnte nicht gestartet werden")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "powerOn error: ${e.message}", e)
            onError?.invoke("Tuner Error: ${e.message}")
            return false
        }
    }

    fun powerOff() {
        Log.i(TAG, "Powering OFF")

        try {
            rdsManager.stopPolling()
            fmNative.powerOff()
            isRadioOn = false
            onRadioStateChanged?.invoke(false)
        } catch (e: Exception) {
            Log.e(TAG, "powerOff error: ${e.message}", e)
        }
    }

    // ========== Full Power Sequence (with TWUtil/MCU) ==========

    /**
     * Vollständige Power-On Sequenz für FYT Head Units.
     * Beinhaltet TWUtil MCU-Kommunikation, FmService, und FM-Chip.
     *
     * @param frequency Die Frequenz zum Starten
     * @return true wenn erfolgreich
     */
    fun powerOnFull(frequency: Float): Boolean {
        Log.i(TAG, "======= powerOnFull($frequency) =======")

        try {
            currentFrequency = frequency

            // Step 0: FmService für Audio-Routing starten
            Log.i(TAG, "Step 0: Starting FmService for audio routing")
            try {
                val serviceIntent = Intent()
                serviceIntent.setClassName("com.syu.music", "com.android.fmradio.FmService")
                context.startService(serviceIntent)
            } catch (e: Exception) {
                Log.w(TAG, "Could not start FmService: ${e.message}")
            }

            // Step 0b: ACTION_OPEN_RADIO Broadcast
            Log.i(TAG, "Step 0b: Sending ACTION_OPEN_RADIO broadcast")
            context.sendBroadcast(Intent("com.action.ACTION_OPEN_RADIO"))

            // Step 1+2: TWUtil MCU Initialisierung
            if (twUtil?.isAvailable == true) {
                Log.i(TAG, "Step 1: TWUtil.initRadioSequence()")
                twUtil.initRadioSequence()

                Log.i(TAG, "Step 2: TWUtil.radioOn()")
                twUtil.radioOn()

                Log.i(TAG, "Step 2b: TWUtil.unmute()")
                twUtil.unmute()

                // Kurze Pause für MCU
                Thread.sleep(100)
            } else {
                Log.w(TAG, "TWUtil NOT available - skipping MCU init!")
            }

            // Step 3: FM-Chip einschalten
            Log.i(TAG, "Step 3: FmNative.openDev()")
            val openResult = fmNative.openDev()
            Log.i(TAG, "openDev result: $openResult")

            Log.i(TAG, "Step 4: FmNative.powerUp($frequency)")
            val powerResult = fmNative.powerUp(frequency)
            Log.i(TAG, "powerUp result: $powerResult")

            Log.i(TAG, "Step 5: FmNative.tune($frequency)")
            val tuneResult = fmNative.tune(frequency)
            Log.i(TAG, "tune result: $tuneResult")

            // Step 5b: Unmute FM-Chip
            Log.i(TAG, "Step 5b: FmNative.setMute(false)")
            val muteResult = fmNative.setMute(false)
            Log.i(TAG, "setMute(false) result: $muteResult")

            // Step 5c: Audio-Source nochmal setzen
            if (twUtil?.isAvailable == true) {
                Log.i(TAG, "Step 5c: TWUtil.setAudioSourceFm() (repeat)")
                twUtil.setAudioSourceFm()
            }

            isRadioOn = openResult && powerResult
            Log.i(TAG, "isRadioOn = $isRadioOn")

            if (isRadioOn) {
                // Step 6: RDS aktivieren
                Log.i(TAG, "Step 6: RdsManager.enableRds()")
                rdsManager.enableRds()

                // Step 6b: Tuner Settings anwenden
                Log.i(TAG, "Step 6b: Apply tuner settings")
                applyTunerSettings()

                // Step 7: RDS-Polling starten
                Log.i(TAG, "Step 7: startPolling()")
                rdsManager.startPolling(rdsCallback)

                onRadioStateChanged?.invoke(true)
            } else {
                Log.e(TAG, "RADIO FAILED TO START!")
                onError?.invoke("Radio konnte nicht gestartet werden")
            }

            Log.i(TAG, "======= powerOnFull() done =======")
            return isRadioOn

        } catch (e: Exception) {
            Log.e(TAG, "powerOnFull error: ${e.message}", e)
            isRadioOn = false
            onError?.invoke("Tuner Error: ${e.message}")
            return false
        }
    }

    /**
     * Vollständige Power-Off Sequenz für FYT Head Units.
     */
    fun powerOffFull() {
        Log.i(TAG, "======= powerOffFull() =======")

        try {
            rdsManager.stopPolling()
            fmNative.powerOff()
            twUtil?.radioOff()
            isRadioOn = false
            onRadioStateChanged?.invoke(false)
            Log.i(TAG, "======= powerOffFull() done =======")
        } catch (e: Exception) {
            Log.e(TAG, "powerOffFull error: ${e.message}", e)
        }
    }

    /**
     * Wendet Tuner-Settings an (LOC, Mono, Area).
     */
    private fun applyTunerSettings() {
        try {
            val localMode = presetRepository.isLocalMode()
            fmNative.setLocalMode(localMode)
            Log.d(TAG, "Applied LOC mode: $localMode")

            val monoMode = presetRepository.isMonoMode()
            fmNative.setMonoMode(monoMode)
            Log.d(TAG, "Applied Mono mode: $monoMode")

            val area = presetRepository.getRadioArea()
            fmNative.setRadioArea(area)
            Log.d(TAG, "Applied Radio Area: $area")
        } catch (e: Exception) {
            Log.e(TAG, "applyTunerSettings error: ${e.message}")
        }
    }

    /**
     * Tuned zu einer Frequenz.
     */
    fun tune(frequency: Float): Boolean {
        Log.i(TAG, "tune: $frequency MHz")

        val clampedFreq = clampFrequency(frequency)
        currentFrequency = clampedFreq
        saveLastFrequency()

        try {
            val success = fmNative.tune(clampedFreq)
            if (success) {
                onFrequencyChanged?.invoke(clampedFreq)

                // RDS zurücksetzen bei Frequenzwechsel (nur FM)
                if (currentMode == FrequencyScaleView.RadioMode.FM) {
                    currentPs = ""
                    currentRt = ""
                    currentPi = 0
                    currentPty = 0
                }

                return true
            } else {
                Log.e(TAG, "tune failed")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "tune error: ${e.message}", e)
            onError?.invoke("Tuner Error: ${e.message}")
            return false
        }
    }

    /**
     * Tuned zu einer Station.
     */
    fun tuneStation(station: RadioStation): Boolean {
        currentMode = if (station.isAM) FrequencyScaleView.RadioMode.AM else FrequencyScaleView.RadioMode.FM
        return tune(station.frequency)
    }

    /**
     * Seek in eine Richtung starten.
     */
    fun seek(forward: Boolean) {
        Log.i(TAG, "seek: forward=$forward")

        try {
            val result = fmNative.seek(currentFrequency, forward)
            // seek returns float[] where [0] is the found frequency
            if (result != null && result.isNotEmpty() && result[0] > 0) {
                val foundFreq = result[0]
                currentFrequency = foundFreq
                saveLastFrequency()
                onSeekComplete?.invoke(foundFreq)
                onFrequencyChanged?.invoke(foundFreq)
            }
        } catch (e: Exception) {
            Log.e(TAG, "seek error: ${e.message}", e)
            onError?.invoke("Seek Error: ${e.message}")
        }
    }

    /**
     * Frequenz in 0.1 MHz Schritten ändern.
     */
    fun stepFrequency(up: Boolean) {
        val step = if (currentMode == FrequencyScaleView.RadioMode.AM) AM_STEP else FM_STEP
        val newFreq = if (up) currentFrequency + step else currentFrequency - step
        tune(newFreq)
    }

    /**
     * Zum nächsten/vorherigen gespeicherten Sender wechseln.
     */
    fun skipStation(forward: Boolean): RadioStation? {
        val stations = if (currentMode == FrequencyScaleView.RadioMode.AM) {
            presetRepository.loadAmStations()
        } else {
            presetRepository.loadFmStations()
        }

        if (stations.isEmpty()) return null

        val currentIndex = stations.indexOfFirst {
            kotlin.math.abs(it.frequency - currentFrequency) < 0.05f
        }

        val newIndex = if (currentIndex == -1) {
            0
        } else if (forward) {
            (currentIndex + 1) % stations.size
        } else {
            (currentIndex - 1 + stations.size) % stations.size
        }

        val targetStation = stations[newIndex]
        val success = tune(targetStation.frequency)
        return if (success) targetStation else null
    }

    /**
     * Radio Mode wechseln (FM/AM).
     */
    fun setMode(mode: FrequencyScaleView.RadioMode) {
        if (mode == FrequencyScaleView.RadioMode.DAB) {
            Log.w(TAG, "setMode: DAB is handled by DabController")
            return
        }

        Log.i(TAG, "setMode: $currentMode -> $mode")

        val wasOn = isRadioOn
        if (wasOn) {
            powerOff()
        }

        currentMode = mode
        loadLastFrequency() // Lade die letzte Frequenz für den neuen Modus
        saveLastMode()

        if (wasOn) {
            powerOn()
        }
    }

    // Tuner Einstellungen
    fun setMonoMode(enabled: Boolean) {
        try {
            fmNative.setMonoMode(enabled)
        } catch (e: Exception) {
            Log.e(TAG, "setMonoMode error: ${e.message}")
        }
    }

    fun setLocalMode(enabled: Boolean) {
        try {
            fmNative.setLocalMode(enabled)
        } catch (e: Exception) {
            Log.e(TAG, "setLocalMode error: ${e.message}")
        }
    }

    fun setRadioArea(area: Int) {
        try {
            fmNative.setRadioArea(area)
        } catch (e: Exception) {
            Log.e(TAG, "setRadioArea error: ${e.message}")
        }
    }

    /**
     * Setzt den Mute-Status.
     * @return int result from native (0 = success)
     */
    fun setMute(mute: Boolean): Int {
        return try {
            fmNative.setMute(mute)
        } catch (e: Exception) {
            Log.e(TAG, "setMute error: ${e.message}")
            -1
        }
    }

    /**
     * Gibt den aktuellen RSSI-Wert zurück.
     */
    fun getRssi(): Int {
        return try {
            fmNative.getrssi()
        } catch (e: Exception) {
            Log.e(TAG, "getRssi error: ${e.message}")
            0
        }
    }

    /**
     * Öffnet das FM-Device.
     */
    fun openDev(): Boolean {
        return try {
            fmNative.openDev()
        } catch (e: Exception) {
            Log.e(TAG, "openDev error: ${e.message}")
            false
        }
    }

    /**
     * Erweiterte Power-On Methode mit separaten Schritten.
     */
    fun powerOnWithSteps(frequency: Float): Triple<Boolean, Boolean, Boolean> {
        val openResult = openDev()
        val powerResult = try { fmNative.powerUp(frequency) } catch (e: Exception) { false }
        val tuneResult = try { fmNative.tune(frequency) } catch (e: Exception) { false }

        if (openResult && powerResult) {
            isRadioOn = true
            currentFrequency = frequency
            onRadioStateChanged?.invoke(true)
        }

        return Triple(openResult, powerResult, tuneResult)
    }

    // Helper
    private fun clampFrequency(frequency: Float): Float {
        return if (currentMode == FrequencyScaleView.RadioMode.AM) {
            frequency.coerceIn(AM_MIN, AM_MAX)
        } else {
            frequency.coerceIn(FM_MIN, FM_MAX)
        }
    }

    // Persistence
    private fun saveLastFrequency() {
        val key = if (currentMode == FrequencyScaleView.RadioMode.AM) {
            KEY_LAST_AM_FREQUENCY
        } else {
            KEY_LAST_FM_FREQUENCY
        }
        prefs.edit().putFloat(key, currentFrequency).apply()
    }

    private fun loadLastFrequency() {
        val key = if (currentMode == FrequencyScaleView.RadioMode.AM) {
            KEY_LAST_AM_FREQUENCY
        } else {
            KEY_LAST_FM_FREQUENCY
        }
        val defaultFreq = if (currentMode == FrequencyScaleView.RadioMode.AM) 1008f else 98.4f
        currentFrequency = prefs.getFloat(key, defaultFreq)
    }

    private fun saveLastMode() {
        prefs.edit().putString(KEY_LAST_RADIO_MODE, currentMode.name).apply()
    }

    private fun loadLastMode() {
        val modeName = prefs.getString(KEY_LAST_RADIO_MODE, FrequencyScaleView.RadioMode.FM.name)
        currentMode = try {
            FrequencyScaleView.RadioMode.valueOf(modeName ?: "FM")
        } catch (e: Exception) {
            FrequencyScaleView.RadioMode.FM
        }
        // DAB wird vom DabController gehandhabt
        if (currentMode == FrequencyScaleView.RadioMode.DAB) {
            currentMode = FrequencyScaleView.RadioMode.FM
        }
    }

    /**
     * Gibt die gespeicherte Frequenz für einen Modus zurück.
     */
    fun getLastFrequency(mode: FrequencyScaleView.RadioMode): Float {
        val key = if (mode == FrequencyScaleView.RadioMode.AM) {
            KEY_LAST_AM_FREQUENCY
        } else {
            KEY_LAST_FM_FREQUENCY
        }
        val defaultFreq = if (mode == FrequencyScaleView.RadioMode.AM) 1008f else 98.4f
        return prefs.getFloat(key, defaultFreq)
    }

    /**
     * Gibt den gespeicherten Radio-Modus zurück.
     */
    fun getLastMode(): FrequencyScaleView.RadioMode {
        val modeName = prefs.getString(KEY_LAST_RADIO_MODE, FrequencyScaleView.RadioMode.FM.name)
        return try {
            FrequencyScaleView.RadioMode.valueOf(modeName ?: "FM")
        } catch (e: Exception) {
            FrequencyScaleView.RadioMode.FM
        }
    }
}
