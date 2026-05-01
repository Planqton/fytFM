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
class DabTunerManager :
    DabTunerBackend,
    TunerListener,
    RadioStatusListener,
    RadioServiceAudiodataListener,
    TextualMetadataListener,
    VisualMetadataListener {

    companion object {
        private const val TAG = "DabTunerManager"
        // VID/PID für unterstützte DAB-Dongles (XTRONS, Joying, Pumpkin)
    }

    private var isInitialized = false
    private var currentTuner: Tuner? = null
    private var currentService: RadioServiceDab? = null
    private var scanListener: DabScanListener? = null
    private val scannedServices = mutableListOf<DabStation>()
    private val mainHandler = Handler(Looper.getMainLooper())
    // Application-Context wird von initialize() gespeichert, damit Error-Callbacks
    // außerhalb dieser Methode lokalisierte Strings ausliefern können.
    private var appContext: Context? = null

    // Audio playback
    private var audioTrack: AudioTrack? = null
    private var currentSampleRate = 0
    private var currentChannels = 0
    private var pendingAudioStartedNotify = false

    // Recording
    private var recorder: DabRecorder? = null

    override var onServiceStarted: ((DabStation) -> Unit)? = null
    override var onServiceStopped: (() -> Unit)? = null
    override var onTunerReady: (() -> Unit)? = null
    override var onTunerError: ((String) -> Unit)? = null
    override var onDynamicLabel: ((String) -> Unit)? = null  // DLS - DAB Äquivalent zu RDS RT
    override var onDlPlus: ((artist: String?, title: String?) -> Unit)? = null  // DL+ Tags für Artist/Title
    override var onAudioStarted: ((audioSessionId: Int) -> Unit)? = null  // Fires when AudioTrack is created
    override var onSlideshow: ((Bitmap) -> Unit)? = null  // MOT Slideshow - Bilder vom DAB-Sender
    override var onReceptionStats: ((sync: Boolean, quality: String, snr: Int) -> Unit)? = null  // Empfangsqualität

    // Recording callbacks
    override var onRecordingStarted: (() -> Unit)? = null
    override var onRecordingStopped: ((java.io.File) -> Unit)? = null
    override var onRecordingError: ((String) -> Unit)? = null
    override var onRecordingProgress: ((durationSeconds: Long) -> Unit)? = null

    // EPG callbacks
    override var onEpgDataReceived: ((EpgData) -> Unit)? = null

    // EPG State
    private var currentEpgData: EpgData? = null

    val isDabOn: Boolean get() = isInitialized && currentTuner != null

    /**
     * Initialisiert die OMRI Radio API und sucht nach USB DAB-Dongles.
     */
    override fun initialize(context: Context): Boolean {
        appContext = context.applicationContext
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
            onTunerError?.invoke(
                context.getString(at.planqton.fytfm.R.string.dab_init_failed_format, e.message ?: "")
            )
            return false
        }
    }

    /**
     * Deinitialisiert den DAB Tuner und gibt Ressourcen frei.
     */
    override fun deinitialize() {
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
    override fun startScan(listener: DabScanListener) {
        try {
            val tuner = currentTuner
            if (tuner == null) {
                listener.onScanError("Kein DAB+ Tuner verfügbar")
                return
            }

            if (tuner.tunerStatus == TunerStatus.TUNER_STATUS_SCANNING) {
                listener.onScanError(
                    appContext?.getString(at.planqton.fytfm.R.string.dab_scan_already_running)
                        ?: "Scan running"
                )
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
    override fun stopScan() {
        try {
            currentTuner?.stopRadioServiceScan()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping scan: ${e.message}", e)
        }
    }

    /**
     * Spielt einen DAB+ Service ab.
     */
    override fun tuneService(serviceId: Int, ensembleId: Int): Boolean {
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

                // Clear EPG data from old service
                clearEpgData()

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
    override fun stopService() {
        try {
            // Stop recording if active
            if (isRecording()) {
                stopRecording()
            }
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
    override fun getServices(): List<DabStation> {
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

    override fun getCurrentService(): DabStation? {
        val service = currentService ?: return null
        return DabStation(
            serviceId = service.serviceId,
            ensembleId = service.ensembleId,
            serviceLabel = service.serviceLabel ?: "Unknown",
            ensembleLabel = service.ensembleLabel ?: "",
            ensembleFrequencyKHz = service.ensembleFrequency
        )
    }

    override fun hasTuner(): Boolean = currentTuner != null

    /**
     * Returns the audio session ID from the AudioTrack for use with Visualizer API.
     * Returns 0 if no AudioTrack is active.
     */
    override fun getAudioSessionId(): Int {
        return audioTrack?.audioSessionId ?: 0
    }

    // ==================== RECORDING ====================

    /**
     * Start recording the current DAB audio to MP3.
     * @param folderUri The SAF tree URI for the recording folder
     */
    override fun startRecording(context: android.content.Context, folderUri: String): Boolean {
        if (recorder?.isRecording() == true) {
            Log.w(TAG, "Already recording")
            return false
        }

        val stationName = currentService?.let {
            (it as? RadioServiceDab)?.serviceLabel
        } ?: "DAB"

        recorder = DabRecorder(context).apply {
            onRecordingStarted = { mainHandler.post { this@DabTunerManager.onRecordingStarted?.invoke() } }
            onRecordingStopped = { file -> mainHandler.post { this@DabTunerManager.onRecordingStopped?.invoke(file) } }
            onRecordingError = { error -> mainHandler.post { this@DabTunerManager.onRecordingError?.invoke(error) } }
            onRecordingProgress = { duration -> mainHandler.post { this@DabTunerManager.onRecordingProgress?.invoke(duration) } }
        }

        return recorder?.startRecording(stationName, folderUri) ?: false
    }

    /**
     * Stop recording and return the recorded file name.
     */
    override fun stopRecording(): String? {
        val fileName = recorder?.stopRecording()
        recorder = null
        return fileName
    }

    /**
     * Check if currently recording.
     */
    override fun isRecording(): Boolean = recorder?.isRecording() ?: false

    // ==================== EPG ====================

    /**
     * Get current EPG data for the active service.
     */
    override fun getCurrentEpgData(): EpgData? = currentEpgData

    /**
     * Clear EPG data (called when service changes).
     * sbtItems-based EPG aggregation was removed along with addEpgItem/updateEpgData
     * — they were never wired up (the SBT callback path for parsing EPG slots
     * never shipped). currentEpgData is still useful for clearing on service change.
     */
    private fun clearEpgData() {
        currentEpgData = null
    }

    /**
     * Prüft ob ein DAB-Tuner verfügbar ist (USB-Gerät angeschlossen).
     * Kann auch ohne Initialisierung aufgerufen werden.
     */
    override fun isDabAvailable(context: Context): Boolean {
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
            Log.e(TAG, "Error checking DAB availability: ${e.message}", e)
            return false
        }
    }

    // === TunerListener callbacks ===

    override fun tunerStatusChanged(tuner: Tuner, status: TunerStatus) {
        Log.i(TAG, "Tuner status changed: $status")
        mainHandler.post {
            when (status) {
                TunerStatus.TUNER_STATUS_INITIALIZED -> onTunerReady?.invoke()
                TunerStatus.TUNER_STATUS_ERROR -> onTunerError?.invoke(
                    appContext?.getString(at.planqton.fytfm.R.string.dab_tuner_error)
                        ?: "DAB tuner error"
                )
                else -> {}
            }
        }
    }

    override fun tunerScanStarted(tuner: Tuner) {
        Log.i(TAG, "Scan started")
        mainHandler.post { scanListener?.onScanStarted() }
    }

    override fun tunerScanProgress(tuner: Tuner, progress: Int, total: Int) {
        // total = aktuelle Frequenz in Hz, progress = Block-Index
        val freqHz = total.toLong()

        // Ignoriere ungültige Frequenzen (Reset-Callbacks vom Tuner)
        if (freqHz < 170_000_000L) {
            Log.d(TAG, "Scan progress: ignoring invalid freq $freqHz")
            return
        }

        val freqMHz = freqHz / 1_000_000.0

        // DAB Band III: 174.928 MHz (5A) bis 239.200 MHz (13F)
        val startFreq = 174_928_000L
        val endFreq = 239_200_000L
        val percent = ((freqHz - startFreq) * 100L / (endFreq - startFreq)).toInt().coerceIn(0, 100)

        val blockLabel = "Block $progress (%.1f MHz)".format(freqMHz)
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
            pendingAudioStartedNotify = true  // Will trigger onAudioStarted when audio data arrives
            mainHandler.post { onServiceStarted?.invoke(dabStation) }
        }
    }

    override fun radioServiceStopped(tuner: Tuner, service: RadioService) {
        currentService = null
        Log.i(TAG, "Service stopped")
        mainHandler.post { onServiceStopped?.invoke() }
    }

    private var lastReceptionStatsTimestamp: Long = 0L

    override fun tunerReceptionStatistics(tuner: Tuner, sync: Boolean, quality: ReceptionQuality, snr: Int) {
        // Convert enum to readable string (name format: RECEPTION_QUALITY_EXCELLENT -> Excellent)
        val qualityStr = quality.name
            .removePrefix("RECEPTION_QUALITY_")
            .lowercase()
            .replaceFirstChar { it.uppercase() }
        val now = android.os.SystemClock.elapsedRealtime()
        val deltaMs = if (lastReceptionStatsTimestamp > 0) now - lastReceptionStatsTimestamp else -1L
        lastReceptionStatsTimestamp = now
        Log.d(TAG, "tunerReceptionStatistics: sync=$sync quality=$qualityStr snr=$snr Δ=${deltaMs}ms")
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
        } else if (pendingAudioStartedNotify && audioTrack != null) {
            // AudioTrack already exists but we need to notify (e.g., after station change)
            pendingAudioStartedNotify = false
            audioTrack?.audioSessionId?.let { sessionId ->
                mainHandler.post { onAudioStarted?.invoke(sessionId) }
            }
        }
        try {
            audioTrack?.write(data, 0, data.size)
            // Send data to recorder if recording
            recorder?.writePcmData(data, channels, sampleRate)
        } catch (e: Exception) {
            Log.e(TAG, "Error writing audio data: ${e.message}", e)
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
            // Notify that audio has started with the session ID
            pendingAudioStartedNotify = false
            audioTrack?.audioSessionId?.let { sessionId ->
                mainHandler.post { onAudioStarted?.invoke(sessionId) }
            }
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
                Log.e(TAG, "Failed to decode slideshow image: ${e.message}", e)
            }
        }
    }
}
