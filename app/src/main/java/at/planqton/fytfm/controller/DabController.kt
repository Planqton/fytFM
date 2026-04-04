package at.planqton.fytfm.controller

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import at.planqton.fytfm.dab.DabStation
import at.planqton.fytfm.dab.DabTunerManager
import at.planqton.fytfm.data.PresetRepository
import at.planqton.fytfm.data.RadioStation

/**
 * Controller für DAB+ Logik.
 * Kapselt DAB-State und Tuner-Callbacks aus MainActivity.
 */
class DabController(
    private val context: Context,
    private val dabTunerManager: DabTunerManager,
    private val presetRepository: PresetRepository
) {
    companion object {
        private const val TAG = "DabController"
        private const val PREFS_NAME = "fytfm_dab"
        private const val KEY_LAST_SERVICE_ID = "last_dab_service_id"
        private const val KEY_LAST_ENSEMBLE_ID = "last_dab_ensemble_id"
    }

    // State
    var isDabOn = false
        private set
    var currentServiceId = 0
        private set
    var currentEnsembleId = 0
        private set
    var currentServiceLabel: String? = null
        private set
    var currentEnsembleLabel: String? = null
        private set
    var currentDls: String? = null
        private set
    var currentSlideshow: Bitmap? = null
        private set

    // Callbacks für UI-Updates
    var onTunerReady: (() -> Unit)? = null
    var onServiceStarted: ((DabStation) -> Unit)? = null
    var onServiceStopped: (() -> Unit)? = null
    var onTunerError: ((String) -> Unit)? = null
    var onDynamicLabel: ((String) -> Unit)? = null
    var onDlPlus: ((artist: String?, title: String?) -> Unit)? = null
    var onSlideshow: ((Bitmap) -> Unit)? = null
    var onReceptionStats: ((sync: Boolean, quality: String, snr: Int) -> Unit)? = null

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Initialisiert die DAB-Callbacks.
     * Muss einmal aufgerufen werden bevor togglePower() verwendet wird.
     */
    fun initialize() {
        setupCallbacks()
    }

    private fun setupCallbacks() {
        dabTunerManager.onTunerReady = {
            Log.i(TAG, "DAB Tuner ready!")
            onTunerReady?.invoke()
        }

        dabTunerManager.onServiceStarted = { dabStation ->
            Log.i(TAG, "DAB Service started: ${dabStation.serviceLabel}")
            currentServiceId = dabStation.serviceId
            currentEnsembleId = dabStation.ensembleId
            currentServiceLabel = dabStation.serviceLabel
            currentEnsembleLabel = dabStation.ensembleLabel
            currentDls = null
            currentSlideshow = null
            saveLastService(dabStation.serviceId, dabStation.ensembleId)
            onServiceStarted?.invoke(dabStation)
        }

        dabTunerManager.onServiceStopped = {
            Log.i(TAG, "DAB Service stopped")
            onServiceStopped?.invoke()
        }

        dabTunerManager.onTunerError = { error ->
            Log.e(TAG, "DAB Tuner Error: $error")
            isDabOn = false
            onTunerError?.invoke(error)
        }

        dabTunerManager.onDynamicLabel = { dls ->
            Log.d(TAG, "DLS received: $dls")
            currentDls = dls
            onDynamicLabel?.invoke(dls)
        }

        dabTunerManager.onDlPlus = { artist, title ->
            Log.d(TAG, "DL+ received: artist=$artist, title=$title")
            onDlPlus?.invoke(artist, title)
        }

        dabTunerManager.onSlideshow = { bitmap ->
            Log.d(TAG, "Slideshow received: ${bitmap.width}x${bitmap.height}")
            currentSlideshow = bitmap
            onSlideshow?.invoke(bitmap)
        }

        dabTunerManager.onReceptionStats = { sync, quality, snr ->
            onReceptionStats?.invoke(sync, quality, snr)
        }
    }

    /**
     * Schaltet DAB ein/aus.
     */
    fun togglePower(): Boolean {
        Log.i(TAG, "togglePower() isDabOn=$isDabOn")

        return if (isDabOn) {
            powerOff()
            false
        } else {
            powerOn()
            true
        }
    }

    fun powerOn(): Boolean {
        Log.i(TAG, "Powering ON DAB")
        val success = dabTunerManager.initialize(context)
        if (success) {
            isDabOn = true
        }
        return success
    }

    fun powerOff() {
        Log.i(TAG, "Powering OFF DAB")
        dabTunerManager.stopService()
        dabTunerManager.deinitialize()
        isDabOn = false
        currentServiceId = 0
        currentEnsembleId = 0
        currentServiceLabel = null
        currentEnsembleLabel = null
        currentDls = null
        currentSlideshow = null
    }

    /**
     * Tuned zu einem DAB-Service.
     */
    fun tuneService(serviceId: Int, ensembleId: Int): Boolean {
        Log.i(TAG, "tuneService: SID=$serviceId, EID=$ensembleId")
        currentServiceId = serviceId
        currentEnsembleId = ensembleId
        return dabTunerManager.tuneService(serviceId, ensembleId)
    }

    /**
     * Tuned zu einer RadioStation (DAB).
     */
    fun tuneStation(station: RadioStation): Boolean {
        return tuneService(station.serviceId, station.ensembleId)
    }

    /**
     * Zum nächsten/vorherigen DAB-Sender wechseln.
     */
    fun skipStation(forward: Boolean): RadioStation? {
        val stations = presetRepository.loadDabStations()
        if (stations.isEmpty()) return null

        val currentIndex = stations.indexOfFirst { it.serviceId == currentServiceId }
        val newIndex = if (currentIndex == -1) {
            0
        } else if (forward) {
            (currentIndex + 1) % stations.size
        } else {
            (currentIndex - 1 + stations.size) % stations.size
        }

        val targetStation = stations[newIndex]
        val success = tuneStation(targetStation)
        return if (success) targetStation else null
    }

    /**
     * Startet einen DAB-Scan.
     */
    fun startScan(listener: at.planqton.fytfm.dab.DabScanListener) {
        dabTunerManager.startScan(listener)
    }

    fun stopScan() {
        dabTunerManager.stopScan()
    }

    /**
     * Gibt alle bekannten DAB-Services zurück.
     */
    fun getServices(): List<DabStation> {
        return dabTunerManager.getServices()
    }

    fun getCurrentService(): DabStation? {
        return dabTunerManager.getCurrentService()
    }

    fun hasTuner(): Boolean = dabTunerManager.hasTuner()

    /**
     * Prüft ob DAB verfügbar ist.
     */
    fun isDabAvailable(): Boolean {
        return dabTunerManager.isDabAvailable(context)
    }

    // Persistence
    fun saveLastService(serviceId: Int, ensembleId: Int) {
        prefs.edit()
            .putInt(KEY_LAST_SERVICE_ID, serviceId)
            .putInt(KEY_LAST_ENSEMBLE_ID, ensembleId)
            .apply()
    }

    fun loadLastService(): Pair<Int, Int> {
        return Pair(
            prefs.getInt(KEY_LAST_SERVICE_ID, 0),
            prefs.getInt(KEY_LAST_ENSEMBLE_ID, 0)
        )
    }

    /**
     * Lädt und tuned zum letzten Service oder zum ersten verfügbaren.
     */
    fun tuneToLastOrFirst() {
        val dabStations = presetRepository.loadDabStations()
        if (dabStations.isEmpty()) return

        val (lastServiceId, lastEnsembleId) = loadLastService()
        val targetStation = if (lastServiceId > 0) {
            dabStations.find { it.serviceId == lastServiceId } ?: dabStations.first()
        } else {
            dabStations.first()
        }

        Log.i(TAG, "tuneToLastOrFirst: ${targetStation.name} (SID=${targetStation.serviceId})")
        tuneStation(targetStation)
    }
}
