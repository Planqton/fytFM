package at.planqton.fytfm.dab

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.omri.radio.Radio
import org.omri.radio.RadioStatusListener
import org.omri.radioservice.RadioService
import org.omri.radioservice.RadioServiceDab
import org.omri.tuner.ReceptionQuality
import org.omri.tuner.Tuner
import org.omri.tuner.TunerListener
import org.omri.tuner.TunerStatus
import org.omri.tuner.TunerType
import java.util.Date

/**
 * Verwaltet den USB DAB+ Tuner.
 * Nutzt die OMRI Radio API (libirtdab.so) aus dem DAB-Z Projekt.
 */
class DabTunerManager : TunerListener, RadioStatusListener {

    companion object {
        private const val TAG = "DabTunerManager"
        // VID/PID für unterstützte DAB-Dongles (XTRONS, Joying, Pumpkin)
        private const val DAB_VENDOR_ID = 0x16C0
        private const val DAB_PRODUCT_ID = 0x05DC
    }

    private var isInitialized = false
    private var currentTuner: Tuner? = null
    private var currentService: RadioServiceDab? = null
    private var scanListener: DabScanListener? = null
    private val scannedServices = mutableListOf<DabStation>()
    private val mainHandler = Handler(Looper.getMainLooper())

    var onServiceStarted: ((DabStation) -> Unit)? = null
    var onServiceStopped: (() -> Unit)? = null
    var onTunerReady: (() -> Unit)? = null
    var onTunerError: ((String) -> Unit)? = null

    val isDabOn: Boolean get() = isInitialized && currentTuner != null

    /**
     * Initialisiert die OMRI Radio API und sucht nach USB DAB-Dongles.
     */
    fun initialize(context: Context): Boolean {
        if (isInitialized) return true

        try {
            Log.i(TAG, "Initializing DAB+ tuner...")

            // OMRI Radio API initialisieren
            val radio = Radio.getInstance()
            // Listener ZUERST registrieren, damit tunerAttached callback ankommt
            radio.registerRadioStatusListener(this)

            val opts = Bundle()
            opts.putBoolean("verbose_native_logs", false)
            val result = radio.initialize(context, opts)
            Log.i(TAG, "Radio.initialize result: $result")

            isInitialized = true
            Log.i(TAG, "hasTuner after init: ${hasTuner()}, currentTuner=$currentTuner")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize DAB: ${e.message}", e)
            onTunerError?.invoke("DAB Initialisierung fehlgeschlagen: ${e.message}")
            return false
        }
    }

    /**
     * Initialisiert einen gefundenen Tuner.
     */
    fun initTuner() {
        val radio = Radio.getInstance()
        val tuners = radio.getAvailableTuners(TunerType.TUNER_TYPE_DAB)
        if (tuners.isNotEmpty()) {
            val tuner = tuners[0]
            currentTuner = tuner
            tuner.subscribe(this)
            tuner.initializeTuner()
            Log.i(TAG, "Tuner initialized")
        } else {
            Log.w(TAG, "No DAB tuners available to initialize")
        }
    }

    /**
     * Deinitialisiert den DAB Tuner und gibt Ressourcen frei.
     */
    fun deinitialize() {
        try {
            Log.i(TAG, "Deinitializing DAB+ tuner...")
            stopService()
            currentTuner?.let {
                it.deInitializeTuner()
                it.unsubscribe(this)
            }
            currentTuner = null

            val radio = Radio.getInstance()
            radio.unregisterRadioStatusListener(this)
            radio.deInitialize()

            isInitialized = false
            Log.i(TAG, "DAB+ tuner deinitialized")
        } catch (e: Exception) {
            Log.e(TAG, "Error deinitializing DAB: ${e.message}", e)
        }
    }

    /**
     * Startet den DAB+ Ensemble-Scan.
     */
    fun startScan(listener: DabScanListener) {
        val tuner = currentTuner
        if (tuner == null) {
            listener.onScanError("Kein DAB+ Tuner verfügbar")
            return
        }

        if (tuner.tunerStatus == TunerStatus.TUNER_STATUS_SCANNING) {
            listener.onScanError("Scan läuft bereits")
            return
        }

        scanListener = listener
        scannedServices.clear()

        // Laufenden Service stoppen vor dem Scan
        if (currentService != null) {
            stopService()
            Thread.sleep(300)
        }

        Log.i(TAG, "Starting DAB+ ensemble scan...")
        tuner.startRadioServiceScan()
    }

    /**
     * Stoppt den laufenden Scan.
     */
    fun stopScan() {
        currentTuner?.stopRadioServiceScan()
    }

    /**
     * Spielt einen DAB+ Service ab.
     */
    fun tuneService(serviceId: Int, ensembleId: Int): Boolean {
        val tuner = currentTuner ?: return false
        val services = tuner.radioServices

        val service = services.filterIsInstance<RadioServiceDab>().find {
            it.serviceId == serviceId && it.ensembleId == ensembleId
        }

        if (service != null) {
            Log.i(TAG, "Tuning to service: ${service.serviceLabel} (SID: $serviceId, EID: $ensembleId)")
            tuner.startRadioService(service)
            return true
        }

        Log.w(TAG, "Service not found: SID=$serviceId, EID=$ensembleId")
        return false
    }

    /**
     * Stoppt den laufenden Service.
     */
    fun stopService() {
        currentTuner?.stopRadioService()
        currentService = null
    }

    /**
     * Gibt alle bekannten DAB-Services zurück.
     */
    fun getServices(): List<DabStation> {
        val tuner = currentTuner ?: return emptyList()
        return tuner.radioServices
            .filterIsInstance<RadioServiceDab>()
            .filter { it.isProgrammeService }
            .map { service ->
                DabStation(
                    serviceId = service.serviceId,
                    ensembleId = service.ensembleId,
                    serviceLabel = service.serviceLabel ?: "Unknown",
                    ensembleLabel = service.ensembleLabel ?: "",
                    ensembleFrequencyKHz = service.ensembleFrequency
                )
            }
    }

    fun getCurrentService(): DabStation? {
        val service = currentService ?: return null
        return DabStation(
            serviceId = service.serviceId,
            ensembleId = service.ensembleId,
            serviceLabel = service.serviceLabel ?: "Unknown",
            ensembleLabel = service.ensembleLabel ?: "",
            ensembleFrequencyKHz = service.ensembleFrequency
        )
    }

    fun hasTuner(): Boolean = currentTuner != null

    fun getTunerStatus(): TunerStatus? = currentTuner?.tunerStatus

    // === TunerListener callbacks ===

    override fun tunerStatusChanged(tuner: Tuner, status: TunerStatus) {
        Log.i(TAG, "Tuner status changed: $status")
        mainHandler.post {
            when (status) {
                TunerStatus.TUNER_STATUS_INITIALIZED -> onTunerReady?.invoke()
                TunerStatus.TUNER_STATUS_ERROR -> onTunerError?.invoke("DAB Tuner Fehler")
                else -> {}
            }
        }
    }

    override fun tunerScanStarted(tuner: Tuner) {
        Log.i(TAG, "Scan started")
        mainHandler.post { scanListener?.onScanStarted() }
    }

    override fun tunerScanProgress(tuner: Tuner, progress: Int, total: Int) {
        val percent = if (total > 0) (progress * 100) / total else 0
        val blockLabel = "Block $progress/$total"
        Log.d(TAG, "Scan progress: $percent% ($blockLabel)")
        mainHandler.post { scanListener?.onScanProgress(percent, blockLabel) }
    }

    override fun tunerScanServiceFound(tuner: Tuner, service: RadioService) {
        if (service is RadioServiceDab && service.isProgrammeService) {
            val dabStation = DabStation(
                serviceId = service.serviceId,
                ensembleId = service.ensembleId,
                serviceLabel = service.serviceLabel ?: "Unknown",
                ensembleLabel = service.ensembleLabel ?: "",
                ensembleFrequencyKHz = service.ensembleFrequency
            )
            scannedServices.add(dabStation)
            Log.i(TAG, "Service found: ${dabStation.serviceLabel} (${dabStation.ensembleLabel})")
            mainHandler.post { scanListener?.onServiceFound(dabStation) }
        }
    }

    override fun tunerScanFinished(tuner: Tuner) {
        Log.i(TAG, "Scan finished. Found ${scannedServices.size} services.")
        val results = scannedServices.toList()
        mainHandler.post {
            scanListener?.onScanFinished(results)
            scanListener = null
        }
    }

    override fun radioServiceStarted(tuner: Tuner, service: RadioService) {
        if (service is RadioServiceDab) {
            currentService = service
            val dabStation = DabStation(
                serviceId = service.serviceId,
                ensembleId = service.ensembleId,
                serviceLabel = service.serviceLabel ?: "Unknown",
                ensembleLabel = service.ensembleLabel ?: "",
                ensembleFrequencyKHz = service.ensembleFrequency
            )
            Log.i(TAG, "Service started: ${dabStation.serviceLabel}")
            mainHandler.post { onServiceStarted?.invoke(dabStation) }
        }
    }

    override fun radioServiceStopped(tuner: Tuner, service: RadioService) {
        currentService = null
        Log.i(TAG, "Service stopped")
        mainHandler.post { onServiceStopped?.invoke() }
    }

    override fun tunerReceptionStatistics(tuner: Tuner, sync: Boolean, quality: ReceptionQuality, snr: Int) {
        // Empfangsqualität - könnte für UI genutzt werden
    }

    override fun dabDateTime(tuner: Tuner, date: Date) {
        // DAB Zeitinfo
    }

    override fun tunerRawData(tuner: Tuner, data: ByteArray) {
        // Raw-Daten
    }

    // === RadioStatusListener callbacks ===

    override fun tunerAttached(tuner: Tuner) {
        Log.i(TAG, "Tuner attached: ${tuner.javaClass.simpleName}")
        currentTuner = tuner
        tuner.subscribe(this)
        tuner.initializeTuner()
    }

    override fun tunerDetached(tuner: Tuner) {
        Log.i(TAG, "Tuner detached")
        if (currentTuner == tuner) {
            currentTuner = null
            currentService = null
            mainHandler.post { onTunerError?.invoke("DAB+ Gerät getrennt") }
        }
    }
}
