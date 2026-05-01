package at.planqton.fytfm.controller

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import at.planqton.fytfm.dab.DabStation
import at.planqton.fytfm.dab.DabTunerBackend
import at.planqton.fytfm.dab.MockDabTunerManager
import at.planqton.fytfm.data.PresetRepository
import at.planqton.fytfm.data.RadioStation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Controller für DAB+ Logik.
 * Kapselt DAB-State und Tuner-Callbacks aus MainActivity.
 *
 * Ist gegen eine [DabTunerBackend]-Schnittstelle programmiert; die konkrete
 * Hintergrund-Implementierung (echter OMRI-Tuner vs. Mock-DAB für UI-Dev)
 * wird per [setBackend] zur Laufzeit gewechselt — z. B. wenn der Nutzer
 * zwischen DAB-Modus und DAB_DEV-Modus umschaltet. Wird das Backend
 * gewechselt, müssen die UI-Callback-Properties NICHT neu gesetzt werden;
 * [setBackend] re-attached die Tuner-Callbacks intern.
 */
class DabController(
    private val context: Context,
    initialBackend: DabTunerBackend,
    private val presetRepository: PresetRepository
) {

    private var dabTunerManager: DabTunerBackend = initialBackend
    companion object {
        private const val TAG = "DabController"
        private const val PREFS_NAME = "fytfm_dab"
        // Real-DAB last-tuned service. Kept under the historical key so a
        // user upgrading from a real-tuner-only build keeps their last station.
        private const val KEY_LAST_SERVICE_ID_REAL = "last_dab_service_id"
        private const val KEY_LAST_ENSEMBLE_ID_REAL = "last_dab_ensemble_id"
        // Demo-mode last-tuned service. Strict separation: switching backends
        // must not let demo IDs leak into the real tuner (or vice versa).
        private const val KEY_LAST_SERVICE_ID_DEMO = "last_dab_service_id_demo"
        private const val KEY_LAST_ENSEMBLE_ID_DEMO = "last_dab_ensemble_id_demo"
    }

    private val isDemoBackend: Boolean
        get() = dabTunerManager is MockDabTunerManager

    private val keyLastServiceId: String
        get() = if (isDemoBackend) KEY_LAST_SERVICE_ID_DEMO else KEY_LAST_SERVICE_ID_REAL

    private val keyLastEnsembleId: String
        get() = if (isDemoBackend) KEY_LAST_ENSEMBLE_ID_DEMO else KEY_LAST_ENSEMBLE_ID_REAL

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

    /**
     * Multi-listener event stream — replaces the old `var on...` lambda
     * properties. Subscribers `collect` from this; emissions are non-blocking
     * via [extraBufferCapacity] so a slow consumer never blocks the
     * tuner-callback thread that called us. RadioController wires its own
     * events flow as one such subscriber; tests can observe directly.
     */
    private val _events = MutableSharedFlow<DabEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<DabEvent> = _events.asSharedFlow()

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Initialisiert die DAB-Callbacks.
     * Muss einmal aufgerufen werden bevor togglePower() verwendet wird.
     *
     * Pre-populates [currentServiceId]/[currentEnsembleId] from the persisted
     * last-tuned service so observers (incl. MainActivity reading via the
     * Phase-B computed property) get the right value on app start, before
     * the user has powered DAB on.
     */
    fun initialize() {
        setupCallbacks()
        val (lastServiceId, lastEnsembleId) = loadLastService()
        if (lastServiceId > 0) {
            currentServiceId = lastServiceId
            currentEnsembleId = lastEnsembleId
        }
    }

    /**
     * Switch the underlying tuner implementation. Caller must ensure the
     * previously-active backend is powered off first (via [powerOff] or
     * [RadioController.powerOffDab]) — this method does NOT call powerOff
     * itself, because the backend may already be in an unrecoverable state
     * that another deinitialize() call would worsen.
     *
     * Wires the same UI-facing callbacks ([onTunerReady], [onServiceStarted],
     * etc.) to the new backend, and clears the OLD backend's callback slots
     * so any stale firings during teardown can't reach the UI.
     */
    fun setBackend(backend: DabTunerBackend) {
        if (backend === dabTunerManager) return
        clearBackendCallbacks(dabTunerManager)
        dabTunerManager = backend
        // Reset transient state — service IDs from the old backend mean
        // nothing on the new one.
        currentServiceId = 0
        currentEnsembleId = 0
        currentServiceLabel = null
        currentEnsembleLabel = null
        currentDls = null
        currentSlideshow = null
        // Now that the new backend is active, the per-backend last-service
        // pref keys point to the right slot — pre-populate the IDs so
        // downstream UI (mode spinner, station list) lights up the matching
        // entry without waiting for tuneToLastOrFirst().
        val (lastServiceId, lastEnsembleId) = loadLastService()
        if (lastServiceId > 0) {
            currentServiceId = lastServiceId
            currentEnsembleId = lastEnsembleId
        }
        setupCallbacks()
    }

    /** The currently-active backend (real or mock). */
    val backend: DabTunerBackend get() = dabTunerManager

    private fun clearBackendCallbacks(backend: DabTunerBackend) {
        backend.onTunerReady = null
        backend.onServiceStarted = null
        backend.onServiceStopped = null
        backend.onTunerError = null
        backend.onDynamicLabel = null
        backend.onDlPlus = null
        backend.onSlideshow = null
        backend.onReceptionStats = null
        backend.onAudioStarted = null
        backend.onRecordingStarted = null
        backend.onRecordingStopped = null
        backend.onRecordingError = null
        backend.onEpgDataReceived = null
    }

    private fun setupCallbacks() {
        dabTunerManager.onTunerReady = {
            Log.i(TAG, "DAB Tuner ready!")
            _events.tryEmit(DabEvent.TunerReady)
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
            _events.tryEmit(DabEvent.ServiceStarted(dabStation))
        }

        dabTunerManager.onServiceStopped = {
            Log.i(TAG, "DAB Service stopped")
            _events.tryEmit(DabEvent.ServiceStopped)
        }

        dabTunerManager.onTunerError = { error ->
            Log.e(TAG, "DAB Tuner Error: $error")
            isDabOn = false
            _events.tryEmit(DabEvent.TunerError(error))
        }

        dabTunerManager.onDynamicLabel = { dls ->
            Log.d(TAG, "DLS received: $dls")
            currentDls = dls
            _events.tryEmit(DabEvent.DynamicLabel(dls))
        }

        dabTunerManager.onDlPlus = { artist, title ->
            Log.d(TAG, "DL+ received: artist=$artist, title=$title")
            _events.tryEmit(DabEvent.DlPlus(artist, title))
        }

        dabTunerManager.onSlideshow = { bitmap ->
            Log.d(TAG, "Slideshow received: ${bitmap.width}x${bitmap.height}")
            currentSlideshow = bitmap
            _events.tryEmit(DabEvent.Slideshow(bitmap))
        }

        dabTunerManager.onReceptionStats = { sync, quality, snr ->
            _events.tryEmit(DabEvent.ReceptionStats(sync, quality, snr))
        }

        dabTunerManager.onAudioStarted = { audioSessionId ->
            Log.d(TAG, "Audio started with session ID: $audioSessionId")
            _events.tryEmit(DabEvent.AudioStarted(audioSessionId))
        }

        dabTunerManager.onRecordingStarted = { _events.tryEmit(DabEvent.RecordingStarted) }
        dabTunerManager.onRecordingStopped = { file -> _events.tryEmit(DabEvent.RecordingStopped(file)) }
        dabTunerManager.onRecordingError = { error -> _events.tryEmit(DabEvent.RecordingError(error)) }

        dabTunerManager.onEpgDataReceived = { epgData ->
            Log.d(TAG, "EPG data received")
            _events.tryEmit(DabEvent.EpgReceived(epgData))
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

    /**
     * Gibt die Audio Session ID zurück.
     */
    fun getAudioSessionId(): Int = dabTunerManager.getAudioSessionId()

    // ========== Recording ==========
    // Recording lifecycle events fire on the [events] flow as
    // [DabEvent.RecordingStarted] / [DabEvent.RecordingStopped] /
    // [DabEvent.RecordingError]. The backend's recording callbacks are
    // wired in [setupCallbacks] so subsequent backend swaps re-attach
    // them automatically.

    fun isRecording(): Boolean = dabTunerManager.isRecording()

    fun startRecording(context: Context, path: String): Boolean {
        return dabTunerManager.startRecording(context, path)
    }

    fun stopRecording() = dabTunerManager.stopRecording()

    // ========== EPG ==========
    // EPG events fire on [events] as [DabEvent.EpgReceived].

    fun getCurrentEpgData(): at.planqton.fytfm.dab.EpgData? = dabTunerManager.getCurrentEpgData()

    // ========== Audio ==========
    // AudioStarted events fire on [events] as [DabEvent.AudioStarted].

    // Persistence
    fun saveLastService(serviceId: Int, ensembleId: Int) {
        prefs.edit()
            .putInt(keyLastServiceId, serviceId)
            .putInt(keyLastEnsembleId, ensembleId)
            .apply()
    }

    fun loadLastService(): Pair<Int, Int> {
        return Pair(
            prefs.getInt(keyLastServiceId, 0),
            prefs.getInt(keyLastEnsembleId, 0)
        )
    }

    /**
     * Lädt und tuned zum letzten Service oder zum ersten verfügbaren.
     * Loads from the demo-station list when the demo backend is active so a
     * real-tuner station-id can never be passed to the mock (and vice versa).
     */
    fun tuneToLastOrFirst() {
        val dabStations = if (isDemoBackend) {
            presetRepository.loadDabDevStations()
        } else {
            presetRepository.loadDabStations()
        }
        if (dabStations.isEmpty()) return

        val (lastServiceId, _) = loadLastService()
        val targetStation = if (lastServiceId > 0) {
            dabStations.find { it.serviceId == lastServiceId } ?: dabStations.first()
        } else {
            dabStations.first()
        }

        Log.i(TAG, "tuneToLastOrFirst: ${targetStation.name} (SID=${targetStation.serviceId})")
        tuneStation(targetStation)
    }
}
