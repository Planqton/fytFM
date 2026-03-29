package at.planqton.fytfm.tuner

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import at.planqton.fytfm.DabManager
import at.planqton.fytfm.RdsManager
import at.planqton.fytfm.TWUtilHelper
import at.planqton.fytfm.data.PresetRepository
import at.planqton.fytfm.plugin.api.DabDataCallback
import at.planqton.fytfm.plugin.api.RdsDataCallback
import at.planqton.fytfm.plugin.api.TunerHostApi
import com.android.fmradio.FmNative
import com.syu.jni.SyuJniNative
import org.omri.radioservice.RadioService
import org.omri.radioservice.RadioServiceDab

class TunerHostApiImpl(
    private val context: Context,
    private val fmNative: FmNative,
    private val rdsManager: RdsManager,
    private val twUtil: TWUtilHelper?,
    private val dabManager: DabManager
) : TunerHostApi {

    companion object {
        private const val TAG = "TunerHostApiImpl"
    }

    private var dabStations: List<RadioService> = emptyList()

    // === Hardware Detection ===

    override fun isFmChipAvailable(): Boolean {
        return FmNative.isLibraryLoaded()
    }

    override fun isDabDeviceAvailable(): Boolean {
        try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as? android.hardware.usb.UsbManager
                ?: return false
            return usbManager.deviceList.values.any { device ->
                // IRT DAB+ USB Stick: Vendor 0x04D8 (1240), Products 0xFAFD-0xFAFF
                (device.vendorId == 1240 && device.productId in 64253..64255) ||
                // DAB USB Dongle: Vendor 0x16C0 (5824), Product 0x05DC (1500)
                (device.vendorId == 5824 && device.productId == 1500)
            }
        } catch (_: Exception) {
            return false
        }
    }

    override fun isMcuAvailable(): Boolean {
        return twUtil?.isAvailable == true
    }

    // === FM/AM Chip ===

    override fun openDevice(): Boolean = fmNative.openDev()

    override fun closeDevice() {
        fmNative.closeDev()
    }

    override fun powerUp(frequency: Float): Boolean = fmNative.powerUp(frequency)

    override fun powerOff() {
        fmNative.powerOff()
    }

    override fun tune(frequency: Float) {
        rdsManager.setUiFrequency(frequency)
        rdsManager.tune(frequency)
    }

    override fun getSignalStrength(): Int = fmNative.getrssi()

    override fun setMute(mute: Boolean) {
        fmNative.setMute(mute)
    }

    override fun setLocalMode(local: Boolean) {
        fmNative.setLocalMode(local)
    }

    override fun setMonoMode(mono: Boolean) {
        fmNative.setMonoMode(mono)
    }

    override fun setRadioArea(area: Int) {
        fmNative.setRadioArea(area)
    }

    // === RDS ===

    override fun enableRds() {
        rdsManager.enableRds()
    }

    override fun clearRds() {
        rdsManager.clearRds()
    }

    override fun startRdsPolling(callback: RdsDataCallback) {
        rdsManager.startPolling(object : RdsManager.RdsCallback {
            override fun onRdsUpdate(
                ps: String?, rt: String?, rssi: Int, pi: Int,
                pty: Int, tp: Int, ta: Int, afList: ShortArray?
            ) {
                callback.onRdsUpdate(ps, rt, rssi, pi, pty, tp, ta, afList)
            }
        })
    }

    override fun stopRdsPolling() {
        rdsManager.stopPolling()
    }

    // === Audio Routing / MCU ===

    override fun initRadioSequence() {
        twUtil?.initRadioSequence()
    }

    override fun radioOn() {
        twUtil?.radioOn()
    }

    override fun radioOff() {
        twUtil?.radioOff()
    }

    override fun setAudioSourceFm() {
        twUtil?.setAudioSourceFm()
    }

    override fun unmuteMcu() {
        twUtil?.unmute()
    }

    override fun muteAmplifier(mute: Boolean) {
        try {
            val systemPropertiesClass = Class.forName("android.os.SystemProperties")
            val setMethod = systemPropertiesClass.getMethod("set", String::class.java, String::class.java)
            setMethod.invoke(null, "sys.radio.mute", if (mute) "1" else "0")
        } catch (_: Exception) {}

        if (SyuJniNative.isLibraryLoaded()) {
            SyuJniNative.getInstance().muteAmp(mute)
        }
    }

    // === System ===

    override fun sendBroadcast(action: String) {
        context.sendBroadcast(Intent(action))
    }

    override fun startFmService() {
        try {
            val serviceIntent = Intent()
            serviceIntent.setClassName("com.syu.music", "com.android.fmradio.FmService")
            context.startService(serviceIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not start FmService: ${e.message}")
        }
    }

    override fun setSystemProperty(key: String, value: String) {
        try {
            val systemPropertiesClass = Class.forName("android.os.SystemProperties")
            val setMethod = systemPropertiesClass.getMethod("set", String::class.java, String::class.java)
            setMethod.invoke(null, key, value)
        } catch (_: Exception) {}
    }

    // === Settings ===

    override fun isLocalMode(): Boolean {
        return PresetRepository(context).isLocalMode()
    }

    override fun isMonoMode(): Boolean {
        return PresetRepository(context).isMonoMode()
    }

    override fun getRadioArea(): Int {
        return PresetRepository(context).getRadioArea()
    }

    // === DAB ===

    override fun initializeDab() {
        dabManager.initialize()
    }

    override fun shutdownDab() {
        dabManager.shutdown()
    }

    override fun startDabScan() {
        dabManager.startScan()
    }

    override fun stopDabScan() {
        dabManager.stopScan()
    }

    override fun startDabPlayback(serviceIndex: Int) {
        if (serviceIndex in dabStations.indices) {
            dabManager.startPlayback(dabStations[serviceIndex])
        }
    }

    override fun stopDabPlayback() {
        dabManager.stopPlayback()
    }

    override fun loadDabStations() {
        dabManager.loadStations()
    }

    override fun getDabStationCount(): Int = dabStations.size

    override fun setDabCallback(callback: DabDataCallback) {
        dabManager.callback = object : DabManager.DabCallback {
            override fun onTunerReady() = callback.onTunerReady()
            override fun onTunerError() = callback.onTunerError()

            override fun onStationsLoaded(stations: List<RadioService>) {
                dabStations = stations.filter { s ->
                    s is RadioServiceDab && s.isProgrammeService()
                }.sortedBy { it.serviceLabel?.trim()?.lowercase() ?: "" }
                callback.onStationsLoaded(dabStations.size)
            }

            override fun onScanStarted() = callback.onScanStarted()
            override fun onScanProgress(percent: Int) = callback.onScanProgress(percent)
            override fun onScanFinished(count: Int) = callback.onScanFinished(count)

            override fun onScanServiceFound(service: RadioService) {
                // Update internal list
                dabStations = dabStations + service
            }

            override fun onServiceStarted(service: RadioService) {
                callback.onServiceStarted(service.serviceLabel?.trim() ?: "DAB+")
            }

            override fun onServiceStopped() = callback.onServiceStopped()
            override fun onDynamicLabel(text: String) = callback.onDynamicLabel(text)
            override fun onDlPlus(artist: String?, title: String?) = callback.onDlPlus(artist, title)
            override fun onSlideshow(bitmap: Bitmap) = callback.onSlideshow(bitmap)
            override fun onLogo(bitmap: Bitmap) = callback.onLogo(bitmap)
        }
    }

    /**
     * Hilfsmethode: gibt die DAB-Stationen als RadioStation-Liste zurück
     */
    fun getDabRadioStations(): List<at.planqton.fytfm.plugin.api.RadioStation> {
        return dabStations.map { service ->
            val label = service.serviceLabel?.trim() ?: "???"
            val ensemble = if (service is RadioServiceDab) {
                service.ensembleLabel?.trim() ?: ""
            } else ""
            at.planqton.fytfm.plugin.api.RadioStation(
                frequency = 0f,
                name = if (ensemble.isNotEmpty()) "$label ($ensemble)" else label,
                rssi = 0,
                isAM = false,
                isFavorite = false
            )
        }
    }

    fun isTwUtilAvailable(): Boolean = twUtil?.isAvailable == true
}
