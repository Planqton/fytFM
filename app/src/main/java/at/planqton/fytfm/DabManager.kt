package at.planqton.fytfm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import org.omri.radio.Radio
import org.omri.radio.RadioErrorCode
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
import org.omri.tuner.ReceptionQuality
import org.omri.tuner.Tuner
import org.omri.tuner.TunerListener
import org.omri.tuner.TunerStatus
import org.omri.tuner.TunerType
import java.util.Date

class DabManager(private val context: Context) :
    RadioServiceAudiodataListener, TextualMetadataListener, VisualMetadataListener, TunerListener, RadioStatusListener {

    interface DabCallback {
        fun onTunerReady()
        fun onTunerError()
        fun onStationsLoaded(stations: List<RadioService>)
        fun onScanStarted()
        fun onScanProgress(percent: Int)
        fun onScanFinished(count: Int)
        fun onScanServiceFound(service: RadioService)
        fun onServiceStarted(service: RadioService)
        fun onServiceStopped()
        fun onDynamicLabel(text: String)
        fun onDlPlus(artist: String?, title: String?)
        fun onSlideshow(bitmap: Bitmap)
        fun onLogo(bitmap: Bitmap)
    }

    var callback: DabCallback? = null
    private var activeTuner: Tuner? = null
    private var currentService: RadioService? = null
    private var audioTrack: AudioTrack? = null
    private var currentSampleRate = 0
    private var currentChannels = 0
    private var initialized = false
    var isScanning = false
        private set

    fun initialize() {
        if (initialized) return
        val radio = Radio.getInstance()
        val result = radio.initialize(context.applicationContext)
        if (result == RadioErrorCode.ERROR_INIT_OK) {
            initialized = true
            radio.registerRadioStatusListener(this)
            checkForTuners()
        } else {
            callback?.onTunerError()
        }
    }

    fun shutdown() {
        stopPlayback()
        activeTuner?.unsubscribe(this)
        Radio.getInstance().unregisterRadioStatusListener(this)
        initialized = false
    }

    private fun checkForTuners() {
        val tuners = Radio.getInstance().getAvailableTuners(TunerType.TUNER_TYPE_DAB)
        if (tuners != null && tuners.isNotEmpty()) {
            activeTuner = tuners[0]
            activeTuner?.subscribe(this)
            if (activeTuner?.tunerStatus == TunerStatus.TUNER_STATUS_NOT_INITIALIZED) {
                activeTuner?.initializeTuner()
            } else {
                loadStations()
            }
        }
    }

    fun loadStations() {
        val tuner = activeTuner ?: return
        val services = tuner.radioServices ?: return
        val dabStations = services.filter { s ->
            s is RadioServiceDab && s.isProgrammeService()
        }.sortedBy { it.serviceLabel?.trim()?.lowercase() ?: "" }
        callback?.onStationsLoaded(dabStations)
    }

    fun startScan() {
        if (isScanning) return
        activeTuner?.startRadioServiceScan()
    }

    fun stopScan() {
        if (!isScanning) return
        activeTuner?.stopRadioServiceScan()
    }

    fun startPlayback(service: RadioService) {
        stopPlayback()
        currentService = service
        service.subscribe(this)
        Radio.getInstance().startRadioService(service)
        callback?.onServiceStarted(service)

        // Load logo async
        Thread {
            try {
                val logos = service.logos
                if (logos != null && logos.isNotEmpty()) {
                    val data = logos[0].visualData
                    if (data != null && data.isNotEmpty()) {
                        val bmp = BitmapFactory.decodeByteArray(data, 0, data.size)
                        if (bmp != null) callback?.onLogo(bmp)
                    }
                }
            } catch (_: Exception) {}
        }.start()
    }

    fun stopPlayback() {
        currentService?.let {
            it.unsubscribe(this)
            Radio.getInstance().stopRadioService(it)
        }
        currentService = null
        releaseAudioTrack()
        callback?.onServiceStopped()
    }

    fun isPlaying() = currentService != null
    fun getCurrentService() = currentService

    // --- RadioServiceAudiodataListener ---
    override fun pcmAudioData(data: ByteArray, channels: Int, sampleRate: Int) {
        if (sampleRate <= 0 || channels <= 0) return
        if (audioTrack == null || sampleRate != currentSampleRate || channels != currentChannels) {
            createAudioTrack(sampleRate, channels)
        }
        audioTrack?.write(data, 0, data.size)
    }

    // --- TextualMetadataListener ---
    override fun newTextualMetadata(textual: Textual?) {
        textual ?: return
        val text = textual.text
        if (text != null) callback?.onDynamicLabel(text.trim())

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
            if (artist != null || title != null) callback?.onDlPlus(artist, title)
        }
    }

    // --- VisualMetadataListener ---
    override fun newVisualMetadata(visual: Visual?) {
        visual ?: return
        val data = visual.visualData
        if (data != null && data.isNotEmpty()) {
            val bmp = BitmapFactory.decodeByteArray(data, 0, data.size)
            if (bmp != null) callback?.onSlideshow(bmp)
        }
    }

    // --- TunerListener ---
    override fun tunerStatusChanged(tuner: Tuner, status: TunerStatus) {
        when (status) {
            TunerStatus.TUNER_STATUS_INITIALIZED -> {
                callback?.onTunerReady()
                loadStations()
            }
            TunerStatus.SERVICES_LIST_READY -> loadStations()
            TunerStatus.TUNER_STATUS_ERROR -> callback?.onTunerError()
            else -> {}
        }
    }

    override fun tunerScanStarted(tuner: Tuner) {
        isScanning = true
        callback?.onScanStarted()
    }

    override fun tunerScanProgress(tuner: Tuner, percent: Int, found: Int) {
        callback?.onScanProgress(percent)
    }

    override fun tunerScanServiceFound(tuner: Tuner, service: RadioService) {
        if (service is RadioServiceDab && service.isProgrammeService()) {
            callback?.onScanServiceFound(service)
        }
    }

    override fun tunerScanFinished(tuner: Tuner) {
        isScanning = false
        loadStations()
        callback?.onScanFinished(activeTuner?.radioServices?.size ?: 0)
    }

    override fun radioServiceStarted(tuner: Tuner, service: RadioService) {}
    override fun radioServiceStopped(tuner: Tuner, service: RadioService) {}
    override fun tunerReceptionStatistics(tuner: Tuner, z: Boolean, q: ReceptionQuality, i: Int) {}
    override fun tunerRawData(tuner: Tuner, data: ByteArray) {}
    override fun dabDateTime(tuner: Tuner, date: Date) {}

    // --- RadioStatusListener ---
    override fun tunerAttached(tuner: Tuner) {
        if (activeTuner == null && tuner.tunerType == TunerType.TUNER_TYPE_DAB) {
            activeTuner = tuner
            tuner.subscribe(this)
            tuner.initializeTuner()
        }
    }

    override fun tunerDetached(tuner: Tuner) {
        if (tuner == activeTuner) {
            stopPlayback()
            activeTuner = null
        }
    }

    private fun createAudioTrack(sampleRate: Int, channels: Int) {
        releaseAudioTrack()
        currentSampleRate = sampleRate
        currentChannels = channels
        val channelMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        if (bufferSize < 1) return
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .build())
            .setBufferSizeInBytes(bufferSize)
            .build()
        audioTrack?.play()
    }

    private fun releaseAudioTrack() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
    }
}
