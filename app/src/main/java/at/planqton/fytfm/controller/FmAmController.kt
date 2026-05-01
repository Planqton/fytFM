package at.planqton.fytfm.controller

import android.content.Context
import android.util.Log
import at.planqton.fytfm.FrequencyScaleView
import at.planqton.fytfm.RdsManager
import at.planqton.fytfm.TWUtilHelper
import at.planqton.fytfm.data.PresetRepository
import at.planqton.fytfm.data.RadioStation
import at.planqton.fytfm.platform.FytHeadunitPlatform
import at.planqton.fytfm.platform.RadioPlatform
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Controller für FM/AM Radio Logik.
 * Kapselt FM/AM-State und Tuner-Operationen aus MainActivity.
 * Integriert TWUtil für FYT Head Unit MCU-Kommunikation.
 */
class FmAmController(
    private val context: Context,
    private val fmNative: FmNativeApi,
    private val rdsManager: RdsManager,
    private val presetRepository: PresetRepository,
    private val twUtil: TWUtilHelper? = null,
    private val platform: RadioPlatform = FytHeadunitPlatform(context),
) {
    companion object {
        private const val TAG = "FmAmController"
        private const val PREFS_NAME = "fytfm_fmam"
        private const val KEY_LAST_FM_FREQUENCY = "last_fm_frequency"
        private const val KEY_LAST_AM_FREQUENCY = "last_am_frequency"

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
    /** Mode cache. **Single source of truth** for radio-mode persistence is
     *  [RadioController]; this field is updated synchronously via [setMode]
     *  when the orchestrator switches modes. (Resolves the §1.3 roadmap
     *  dual-persistence bug — there's no longer a separate `loadLastMode()`
     *  here.) Public-read for tests that verify mode propagation. */
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

    /**
     * Multi-listener event stream — replaces the old `var on...` lambda
     * properties. Subscribers `collect` from this; emissions are non-blocking
     * via [extraBufferCapacity] so a slow consumer never blocks the
     * tuner-callback thread that called us.
     */
    private val _events = MutableSharedFlow<FmAmEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<FmAmEvent> = _events.asSharedFlow()

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Initialisiert den Controller und lädt gespeicherte Einstellungen.
     */
    fun initialize() {
        loadLastFrequency()
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
        _events.tryEmit(FmAmEvent.RdsUpdate(currentPs, currentRt, currentRssi, currentPi, currentPty))
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

                _events.tryEmit(FmAmEvent.RadioStateChanged(true))
                return true
            } else {
                Log.e(TAG, "powerUp failed")
                _events.tryEmit(FmAmEvent.Error("Radio konnte nicht gestartet werden"))
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "powerOn error: ${e.message}", e)
            _events.tryEmit(FmAmEvent.Error("Tuner Error: ${e.message}"))
            return false
        }
    }

    fun powerOff() {
        Log.i(TAG, "Powering OFF")

        try {
            rdsManager.stopPolling()
            fmNative.powerOff()
            // Audio-Mux der MCU wieder freigeben — der gleiche Hook, den
            // die Original-FYT-Apps in onPause() anstoßen. Sonst bleibt der
            // Mux auf FM und andere Apps (Spotify, BT-Audio) hören sich an.
            platform.releaseFmAudioRouting()
            isRadioOn = false
            _events.tryEmit(FmAmEvent.RadioStateChanged(false))
        } catch (e: Exception) {
            Log.e(TAG, "powerOff error: ${e.message}", e)
            _events.tryEmit(FmAmEvent.Error("Tuner Error (power off): ${e.message}"))
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

            // Step 0 + 0b: Platform-specific audio-routing prep (FYT headunit by
            // default; non-FYT can inject NoopRadioPlatform).
            Log.i(TAG, "Step 0: Platform audio-routing prep")
            platform.prepareFmAudioRouting()

            // Step 1+2: TWUtil MCU Initialisierung
            // MCU verwirft Befehle, die zu früh nach State-Wechseln kommen
            // (Race: CMD_AUDIO_SOURCE in radioOn() verpufft, wenn MCU noch
            // CMD_RADIO_POWER(1) verarbeitet). Daher kurze Pausen zwischen den Phasen.
            // Der Audio-Source-Code hängt vom aktuellen Modus ab (FM vs AM) —
            // sonst leitet die MCU im AM-Modus den FM-Audio-Pfad an die
            // Lautsprecher und der User hört nichts vom AM-Tuner.
            val isAm = currentMode == FrequencyScaleView.RadioMode.AM
            if (twUtil?.isAvailable == true) {
                Log.i(TAG, "Step 1: TWUtil.initRadioSequence()")
                twUtil.initRadioSequence()
                Thread.sleep(150)

                Log.i(TAG, "Step 2: TWUtil.radioOn${if (isAm) "Am" else "Fm"}()")
                if (isAm) twUtil.radioOnAm() else twUtil.radioOnFm()
                Thread.sleep(100)

                Log.i(TAG, "Step 2b: TWUtil.unmute()")
                twUtil.unmute()
                Thread.sleep(50)
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

            // Step 5c: Audio-Source-Retry — MCU kann den ersten Befehl in radioOn()
            // verworfen haben, falls sie noch im Init war. Drei Versuche mit 100 ms
            // Abstand reichen erfahrungsgemäß, um den Audio-Pfad zuverlässig auf
            // den Tuner zu schalten. Mode-aware: AM-Code im AM-Modus.
            if (twUtil?.isAvailable == true) {
                Log.i(TAG, "Step 5c: TWUtil.setAudioSource${if (isAm) "Am" else "Fm"}() retry (3x à 100ms)")
                repeat(3) { attempt ->
                    if (isAm) twUtil.setAudioSourceAm() else twUtil.setAudioSourceFm()
                    if (attempt < 2) Thread.sleep(100)
                }
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

                _events.tryEmit(FmAmEvent.RadioStateChanged(true))
            } else {
                Log.e(TAG, "RADIO FAILED TO START!")
                _events.tryEmit(FmAmEvent.Error("Radio konnte nicht gestartet werden"))
            }

            Log.i(TAG, "======= powerOnFull() done =======")
            return isRadioOn

        } catch (e: Exception) {
            Log.e(TAG, "powerOnFull error: ${e.message}", e)
            isRadioOn = false
            _events.tryEmit(FmAmEvent.Error("Tuner Error: ${e.message}"))
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
            _events.tryEmit(FmAmEvent.RadioStateChanged(false))
            Log.i(TAG, "======= powerOffFull() done =======")
        } catch (e: Exception) {
            Log.e(TAG, "powerOffFull error: ${e.message}", e)
            _events.tryEmit(FmAmEvent.Error("Tuner Error (power off): ${e.message}"))
        }
    }

    /**
     * Wendet Tuner-Settings an (LOC, Mono, Area). Internal helper used
     * during powerOnFull — was public for legacy callers but no production
     * code calls it externally now.
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
            Log.e(TAG, "applyTunerSettings error: ${e.message}", e)
            _events.tryEmit(FmAmEvent.Error("Tuner Settings Error: ${e.message}"))
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
                _events.tryEmit(FmAmEvent.FrequencyChanged(clampedFreq))

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
            _events.tryEmit(FmAmEvent.Error("Tuner Error: ${e.message}"))
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
                _events.tryEmit(FmAmEvent.SeekComplete(foundFreq))
                _events.tryEmit(FmAmEvent.FrequencyChanged(foundFreq))
            }
        } catch (e: Exception) {
            Log.e(TAG, "seek error: ${e.message}", e)
            _events.tryEmit(FmAmEvent.Error("Seek Error: ${e.message}"))
        }
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

        if (wasOn) {
            powerOn()
        }
    }

    // Tuner Einstellungen
    fun setMonoMode(enabled: Boolean) {
        try {
            fmNative.setMonoMode(enabled)
        } catch (e: Exception) {
            Log.e(TAG, "setMonoMode error: ${e.message}", e)
            _events.tryEmit(FmAmEvent.Error("Mono Mode Error: ${e.message}"))
        }
    }

    fun setLocalMode(enabled: Boolean) {
        try {
            fmNative.setLocalMode(enabled)
        } catch (e: Exception) {
            Log.e(TAG, "setLocalMode error: ${e.message}", e)
            _events.tryEmit(FmAmEvent.Error("Local Mode Error: ${e.message}"))
        }
    }

    fun setRadioArea(area: Int) {
        try {
            fmNative.setRadioArea(area)
        } catch (e: Exception) {
            Log.e(TAG, "setRadioArea error: ${e.message}", e)
            _events.tryEmit(FmAmEvent.Error("Radio Area Error: ${e.message}"))
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
            Log.e(TAG, "setMute error: ${e.message}", e)
            _events.tryEmit(FmAmEvent.Error("Mute Error: ${e.message}"))
            -1
        }
    }

    /** Whether libfmjni.so loaded — exposed for [RadioController.isTunerAvailable]. */
    fun isLibraryLoaded(): Boolean = fmNative.isLibraryLoaded()

    /**
     * Gibt den aktuellen RSSI-Wert zurück.
     */
    fun getRssi(): Int {
        return try {
            fmNative.getrssi()
        } catch (e: Exception) {
            Log.e(TAG, "getRssi error: ${e.message}", e)
            0
        }
    }

    /**
     * Tuned zu einer Frequenz ohne Speicherung (für Seek-Operationen).
     */
    fun tuneRaw(frequency: Float): Boolean {
        return try {
            fmNative.tune(frequency)
        } catch (e: Exception) {
            Log.e(TAG, "tuneRaw error: ${e.message}", e)
            false
        }
    }

    // Helper
    private fun clampFrequency(frequency: Float): Float {
        return if (currentMode == FrequencyScaleView.RadioMode.AM) {
            frequency.coerceIn(AM_MIN, AM_MAX)
        } else {
            frequency.coerceIn(FM_MIN, FM_MAX)
        }
    }

    /**
     * Speichert die übergebene Frequenz als letzte aktive Frequenz für den
     * aktuellen Modus (FM oder AM). Verwendet von UI-tune-Pfaden (Slider /
     * Prev-Next / Karusell-Tap), die nicht über `tune(...)` gehen, weil sie
     * direkt `RdsManager.tune` / `FmNative.tune` aufrufen — sonst würde die
     * letzte UI-Frequenz nie persistiert und nach Mode-Wechsel ginge der
     * Tuner immer auf den Default (98.4 / 1008).
     */
    fun persistFrequency(frequency: Float) {
        currentFrequency = clampFrequency(frequency)
        saveLastFrequency()
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

}
