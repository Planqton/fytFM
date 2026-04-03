package at.planqton.fytfm.dab

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.omri.radio.Radio
import org.omri.radio.RadioStatusListener
import org.omri.radioservice.RadioService
import org.omri.radioservice.RadioServiceAudiodataListener
import org.omri.radioservice.RadioServiceDab
import org.omri.radioservice.metadata.Textual
import org.omri.radioservice.metadata.TextualDabDynamicLabel
import org.omri.radioservice.metadata.TextualDabDynamicLabelPlusContentType
import org.omri.radioservice.metadata.TextualMetadataListener
import org.omri.radioservice.metadata.Visual
import org.omri.radioservice.metadata.VisualMetadataListener
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
class DabTunerManager : TunerListener, RadioStatusListener, RadioServiceAudiodataListener, TextualMetadataListener, VisualMetadataListener {

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

    // Audio playback
    private var audioTrack: AudioTrack? = null
    private var currentSampleRate = 0
    private var currentChannels = 0

    var onServiceStarted: ((DabStation) -> Unit)? = null
    var onServiceStopped: (() -> Unit)? = null
    var onTunerReady: (() -> Unit)? = null
    var onTunerError: ((String) -> Unit)? = null
    var onDynamicLabel: ((String) -> Unit)? = null  // DLS - DAB Äquivalent zu RDS RT
    var onDlPlus: ((artist: String?, title: String?) -> Unit)? = null  // DL+ Tags für Artist/Title
    var onSlideshow: ((Bitmap) -> Unit)? = null  // MOT Slideshow - Bilder vom DAB-Sender
    var onReceptionStats: ((sync: Boolean, quality: String, snr: Int) -> Unit)? = null  // Empfangsqualität

    val isDabOn: Boolean get() = isInitialized && currentTuner != null

    /**
     * Initialisiert die OMRI Radio API und sucht nach USB DAB-Dongles.
     */
    fun initialize(context: Context): Boolean {
        try {
            Log.i(TAG, "Initializing DAB+ tuner... (isInitialized=$isInitialized)")

            // Falls schon initialisiert, erst aufräumen
            if (isInitialized) {
                Log.i(TAG, "Already initialized, cleaning up first...")
                try {
                    val radio = Radio.getInstance()
                    radio.unregisterRadioStatusListener(this)
                    currentTuner?.let {
                        it.stopRadioService()
                        it.unsubscribe(this)
                    }
                    currentTuner = null
                    currentService = null
                } catch (e: Exception) {
                    Log.w(TAG, "Cleanup error (ignored): ${e.message}")
                }
                isInitialized = false
            }

            // OMRI Radio API initialisieren
            val radio = Radio.getInstance()
            // Listener ZUERST registrieren, damit tunerAttached callback ankommt
            radio.registerRadioStatusListener(this)

            val opts = Bundle()
            opts.putBoolean("verbose_native_logs", false)
            val result = radio.initialize(context, opts)
            Log.i(TAG, "Radio.initialize result: $result")

            isInitialized = true

            // Aktiv nach vorhandenen Tunern suchen
            val tuners = radio.getAvailableTuners(TunerType.TUNER_TYPE_DAB)
            Log.i(TAG, "Found ${tuners?.size ?: 0} DAB tuners")
            if (tuners != null && tuners.isNotEmpty()) {
                val tuner = tuners[0]
                currentTuner = tuner
                tuner.subscribe(this)
                Log.i(TAG, "Tuner status: ${tuner.tunerStatus}")
                if (tuner.tunerStatus == TunerStatus.TUNER_STATUS_NOT_INITIALIZED) {
                    Log.i(TAG, "Initializing tuner...")
                    tuner.initializeTuner()
                } else if (tuner.tunerStatus == TunerStatus.TUNER_STATUS_INITIALIZED) {
                    Log.i(TAG, "Tuner already initialized, triggering callback")
                    mainHandler.post { onTunerReady?.invoke() }
                }
            }

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
        try {
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
        } catch (e: Exception) {
            Log.e(TAG, "Error starting scan: ${e.message}", e)
            listener.onScanError("Tuner Error: ${e.message}")
        }
    }

    /**
     * Stoppt den laufenden Scan.
     */
    fun stopScan() {
        try {
            currentTuner?.stopRadioServiceScan()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping scan: ${e.message}", e)
        }
    }

    /**
     * Spielt einen DAB+ Service ab.
     */
    fun tuneService(serviceId: Int, ensembleId: Int): Boolean {
        try {
            val tuner = currentTuner ?: return false
            val services = tuner.radioServices

            val service = services.filterIsInstance<RadioServiceDab>().find {
                it.serviceId == serviceId && it.ensembleId == ensembleId
            }

            if (service != null) {
                Log.i(TAG, "Tuning to service: ${service.serviceLabel} (SID: $serviceId, EID: $ensembleId)")

                // Unsubscribe from old service
                currentService?.unsubscribe(this)

                // Subscribe to new service for audio data
                service.subscribe(this)

                tuner.startRadioService(service)
                return true
            }

            Log.w(TAG, "Service not found: SID=$serviceId, EID=$ensembleId")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error tuning to service: ${e.message}", e)
            mainHandler.post { onTunerError?.invoke("Tuner Error: ${e.message}") }
            return false
        }
    }

    /**
     * Stoppt den laufenden Service.
     */
    fun stopService() {
        try {
            currentService?.unsubscribe(this)
            currentTuner?.stopRadioService()
            releaseAudioTrack()
            currentService = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping service: ${e.message}", e)
            releaseAudioTrack()
            currentService = null
        }
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

    /**
     * Prüft ob ein DAB-Tuner verfügbar ist (USB-Gerät angeschlossen).
     * Kann auch ohne Initialisierung aufgerufen werden.
     */
    fun isDabAvailable(context: Context): Boolean {
        try {
            // Prüfe ob OMRI-Library verfügbar ist
            val radio = Radio.getInstance()

            // Falls schon initialisiert, prüfe direkt
            if (isInitialized) {
                val tuners = radio.getAvailableTuners(TunerType.TUNER_TYPE_DAB)
                return tuners != null && tuners.isNotEmpty()
            }

            // Falls nicht initialisiert, kurz initialisieren um zu prüfen
            val opts = Bundle()
            opts.putBoolean("verbose_native_logs", false)
            radio.initialize(context, opts)
            val tuners = radio.getAvailableTuners(TunerType.TUNER_TYPE_DAB)
            val available = tuners != null && tuners.isNotEmpty()

            // Wieder deinitialisieren wenn wir nicht aktiv sind
            if (!isInitialized) {
                radio.deInitialize()
            }

            Log.d(TAG, "isDabAvailable: $available (tuners: ${tuners?.size ?: 0})")
            return available
        } catch (e: Exception) {
            Log.e(TAG, "Error checking DAB availability: ${e.message}")
            return false
        }
    }

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
            Log.i(TAG, "Service started: ${dabStation.serviceLabel}, freq=${service.ensembleFrequency}, freqKhz=${dabStation.ensembleFrequencyKHz}")
            mainHandler.post { onServiceStarted?.invoke(dabStation) }
        }
    }

    override fun radioServiceStopped(tuner: Tuner, service: RadioService) {
        currentService = null
        Log.i(TAG, "Service stopped")
        mainHandler.post { onServiceStopped?.invoke() }
    }

    override fun tunerReceptionStatistics(tuner: Tuner, sync: Boolean, quality: ReceptionQuality, snr: Int) {
        // Convert enum to readable string (name format: RECEPTION_QUALITY_EXCELLENT -> Excellent)
        val qualityStr = quality.name
            .removePrefix("RECEPTION_QUALITY_")
            .lowercase()
            .replaceFirstChar { it.uppercase() }
        mainHandler.post { onReceptionStats?.invoke(sync, qualityStr, snr) }
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
            releaseAudioTrack()
            currentTuner = null
            currentService = null
            mainHandler.post { onTunerError?.invoke("DAB+ Gerät getrennt") }
        }
    }

    // === RadioServiceAudiodataListener ===

    override fun pcmAudioData(data: ByteArray, channels: Int, sampleRate: Int) {
        if (sampleRate <= 0 || channels <= 0) return
        if (audioTrack == null || sampleRate != currentSampleRate || channels != currentChannels) {
            createAudioTrack(sampleRate, channels)
        }
        try {
            audioTrack?.write(data, 0, data.size)
        } catch (e: Exception) {
            Log.e(TAG, "Error writing audio data: ${e.message}")
        }
    }

    private fun createAudioTrack(sampleRate: Int, channels: Int) {
        releaseAudioTrack()
        currentSampleRate = sampleRate
        currentChannels = channels

        val channelMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        if (bufferSize < 1) {
            Log.e(TAG, "Invalid buffer size: $bufferSize")
            return
        }

        try {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize * 4)  // Larger buffer for smoother playback
                .build()
            audioTrack?.play()
            Log.i(TAG, "AudioTrack created: sampleRate=$sampleRate, channels=$channels, bufferSize=$bufferSize")
        } catch (e: Exception) {
            Log.e(TAG, "Error creating AudioTrack: ${e.message}", e)
        }
    }

    private fun releaseAudioTrack() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioTrack: ${e.message}")
        }
        audioTrack = null
        currentSampleRate = 0
        currentChannels = 0
    }

    // === TextualMetadataListener (DLS - Dynamic Label Segment) ===

    override fun newTextualMetadata(textual: Textual?) {
        textual ?: return
        val text = textual.text
        if (text != null) {
            Log.d(TAG, "DLS received: $text")
            mainHandler.post { onDynamicLabel?.invoke(text.trim()) }
        }

        // DL+ (Dynamic Label Plus) für Artist/Title Tags
        if (textual is TextualDabDynamicLabel && textual.hasTags()) {
            var artist: String? = null
            var title: String? = null
            for (item in textual.dlPlusItems) {
                val content = item.dlPlusContentText ?: continue
                when (item.dynamicLabelPlusContentType) {
                    TextualDabDynamicLabelPlusContentType.ITEM_ARTIST -> artist = content.trim()
                    TextualDabDynamicLabelPlusContentType.ITEM_TITLE -> title = content.trim()
                    else -> {}
                }
            }
            if (artist != null || title != null) {
                Log.d(TAG, "DL+ received: artist=$artist, title=$title")
                mainHandler.post { onDlPlus?.invoke(artist, title) }
            }
        }
    }

    // === VisualMetadataListener (MOT Slideshow - Bilder) ===

    override fun newVisualMetadata(visual: Visual?) {
        visual ?: return
        val data = visual.visualData
        if (data != null && data.isNotEmpty()) {
            try {
                val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
                if (bitmap != null) {
                    Log.d(TAG, "Slideshow received: ${bitmap.width}x${bitmap.height}")
                    mainHandler.post { onSlideshow?.invoke(bitmap) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decode slideshow image: ${e.message}")
            }
        }
    }
}
