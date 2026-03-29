package at.planqton.fytfm.tuner

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import at.planqton.fytfm.plugin.api.*

class DabTunerPlugin : TunerPlugin {

    companion object {
        private const val TAG = "DabTunerPlugin"
    }

    override val pluginId = "hikity_dab"
    override val type = TunerType.DAB
    override val displayName = "Hikity DAB+"
    override val hardwareGroup = HardwareGroup.DAB_USB
    override val frequencyRange: ClosedFloatingPointRange<Float>? = null
    override val frequencyStep: Float? = null

    private var callback: TunerPluginCallback? = null
    private var hostApi: TunerHostApi? = null
    private var context: Context? = null
    override var isActive = false; private set
    override var isPoweredOn = false; private set
    override var isScanning = false; private set
    private var currentServiceLabel: String? = null

    override fun initialize(hostApi: TunerHostApi) {
        this.hostApi = hostApi
    }

    override fun isHardwareAvailable(): Boolean {
        return hostApi?.isDabDeviceAvailable() ?: false
    }

    override fun setCallback(callback: TunerPluginCallback?) {
        this.callback = callback
    }

    override fun activate(context: Context, tunerId: String) {
        this.context = context
        isActive = true
        isPoweredOn = true

        hostApi?.setDabCallback(object : DabDataCallback {
            override fun onTunerReady() { callback?.onError("DAB+ Tuner bereit") }
            override fun onTunerError() { callback?.onError("DAB+ Tuner Fehler") }

            override fun onStationsLoaded(count: Int) {
                val stations = (hostApi as? TunerHostApiImpl)?.getDabRadioStations() ?: emptyList()
                callback?.onStationsChanged(stations)
            }

            override fun onScanStarted() { isScanning = true; callback?.onScanStarted() }
            override fun onScanProgress(percent: Int) { callback?.onScanProgress(percent) }
            override fun onScanFinished(count: Int) {
                isScanning = false
                hostApi?.loadDabStations()
                callback?.onScanFinished(count)
            }

            override fun onServiceStarted(label: String) {
                currentServiceLabel = label
                callback?.onMetadataUpdate(TunerMetadata(stationName = label))
            }

            override fun onServiceStopped() {
                currentServiceLabel = null
                callback?.onMetadataUpdate(TunerMetadata())
            }

            override fun onDynamicLabel(text: String) {
                callback?.onMetadataUpdate(TunerMetadata(stationName = currentServiceLabel, radioText = text))
            }

            override fun onDlPlus(artist: String?, title: String?) {
                callback?.onMetadataUpdate(TunerMetadata(stationName = currentServiceLabel, artist = artist, title = title))
            }

            override fun onSlideshow(bitmap: Bitmap) {
                callback?.onMetadataUpdate(TunerMetadata(stationName = currentServiceLabel, coverBitmap = bitmap))
            }

            override fun onLogo(bitmap: Bitmap) {
                callback?.onMetadataUpdate(TunerMetadata(stationName = currentServiceLabel, coverBitmap = bitmap))
            }
        })

        // DAB wird erst bei powerOn() initialisiert (USB-Verbindung)
    }

    override fun deactivate() {
        hostApi?.stopDabPlayback()
        if (isPoweredOn) {
            hostApi?.shutdownDab()
        }
        isActive = false; isPoweredOn = false
    }

    override fun powerOn(): Boolean {
        // USB-DAB-Stick verbinden und initialisieren
        hostApi?.initializeDab()
        isPoweredOn = true
        callback?.onPlaybackStateChanged(true)
        return true
    }

    override fun powerOff() {
        hostApi?.stopDabPlayback()
        hostApi?.shutdownDab()
        isPoweredOn = false
        callback?.onPlaybackStateChanged(false)
    }

    override fun tune(frequency: Float) {} // DAB hat kein Frequenz-Tuning
    override fun getCurrentFrequency(): Float = 0f
    override fun mute() { callback?.onPlaybackStateChanged(false) }
    override fun unmute() { callback?.onPlaybackStateChanged(true) }
    override fun seekToStation(seekUp: Boolean, onProgress: ((Float) -> Unit)?, onResult: ((Float?) -> Unit)?) { onResult?.invoke(null) }
    override fun startScan(config: ScanConfig?) { hostApi?.startDabScan() }
    override fun stopScan() { hostApi?.stopDabScan() }
    override fun applySettings(settings: Map<String, Any>) {}

    override fun startDabService(serviceIndex: Int) { hostApi?.startDabPlayback(serviceIndex) }
    override fun stopDabService() { hostApi?.stopDabPlayback() }
}
