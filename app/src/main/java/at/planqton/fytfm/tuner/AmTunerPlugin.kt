package at.planqton.fytfm.tuner

import android.content.Context
import android.util.Log
import at.planqton.fytfm.plugin.api.*

class AmTunerPlugin : TunerPlugin {

    companion object {
        private const val TAG = "AmTunerPlugin"
    }

    override val pluginId = "fyt_am"
    override val type = TunerType.AM
    override val displayName = "FYT AM"
    override val hardwareGroup = HardwareGroup.FM_CHIP
    override val frequencyRange = 522f..1620f
    override val frequencyStep = 9f

    private var callback: TunerPluginCallback? = null
    private var hostApi: TunerHostApi? = null
    private var context: Context? = null
    override var isActive = false; private set
    override var isPoweredOn = false; private set
    override var isScanning = false; private set
    private var currentFrequency = 522f

    override fun initialize(hostApi: TunerHostApi) { this.hostApi = hostApi }

    override fun isHardwareAvailable(): Boolean {
        return hostApi?.isFmChipAvailable() ?: false
    }

    override fun setCallback(callback: TunerPluginCallback?) { this.callback = callback }

    override fun activate(context: Context, tunerId: String) {
        this.context = context; isActive = true
    }

    override fun deactivate() {
        if (isPoweredOn) hostApi?.stopRdsPolling()
        isActive = false
    }

    override fun powerOn(): Boolean {
        val api = hostApi ?: return false
        try {
            api.startFmService()
            api.sendBroadcast("com.action.ACTION_OPEN_RADIO")
            if ((api as? TunerHostApiImpl)?.isTwUtilAvailable() == true) {
                api.initRadioSequence(); api.radioOn(); api.unmuteMcu(); Thread.sleep(100)
            }
            val openResult = api.openDevice()
            val powerResult = api.powerUp(currentFrequency)
            api.tune(currentFrequency); api.setMute(false)
            if ((api as? TunerHostApiImpl)?.isTwUtilAvailable() == true) api.setAudioSourceFm()
            isPoweredOn = openResult && powerResult
            if (isPoweredOn) {
                api.enableRds(); applySettings(emptyMap()); startRdsPolling()
                callback?.onPlaybackStateChanged(true)
            }
        } catch (e: Exception) { Log.e(TAG, "Power on failed: ${e.message}", e); isPoweredOn = false }
        return isPoweredOn
    }

    override fun powerOff() {
        hostApi?.stopRdsPolling(); hostApi?.powerOff(); hostApi?.radioOff()
        isPoweredOn = false; callback?.onPlaybackStateChanged(false)
    }

    override fun tune(frequency: Float) {
        currentFrequency = frequency
        try { hostApi?.tune(frequency) } catch (e: Throwable) { Log.w(TAG, "tune failed: ${e.message}") }
    }

    override fun getCurrentFrequency(): Float = currentFrequency

    override fun mute() {
        hostApi?.muteAmplifier(true); hostApi?.setMute(true); callback?.onPlaybackStateChanged(false)
    }

    override fun unmute() {
        hostApi?.muteAmplifier(false); hostApi?.setMute(false); callback?.onPlaybackStateChanged(true)
    }

    override fun seekToStation(seekUp: Boolean, onProgress: ((Float) -> Unit)?, onResult: ((Float?) -> Unit)?) {
        onResult?.invoke(null) // AM hat kein Seek
    }

    override fun startScan(config: ScanConfig?) { isScanning = true; callback?.onScanStarted() }
    override fun stopScan() { isScanning = false }

    override fun applySettings(settings: Map<String, Any>) {
        val api = hostApi ?: return
        try {
            api.setLocalMode(api.isLocalMode()); api.setMonoMode(api.isMonoMode()); api.setRadioArea(api.getRadioArea())
        } catch (e: Exception) { Log.e(TAG, "Error applying settings: ${e.message}") }
    }

    override fun getSettings(): List<PluginSetting> = listOf(
        PluginSetting(key = "local_mode", label = "LOC (Local Mode)", type = SettingType.TOGGLE, defaultValue = false),
        PluginSetting(key = "mono_mode", label = "Mono", type = SettingType.TOGGLE, defaultValue = false)
    )

    override fun enableRds() { hostApi?.enableRds() }
    override fun clearRds() { hostApi?.clearRds() }

    private fun startRdsPolling() {
        hostApi?.startRdsPolling(object : RdsDataCallback {
            override fun onRdsUpdate(ps: String?, rt: String?, rssi: Int, pi: Int, pty: Int, tp: Int, ta: Int, afList: ShortArray?) {
                callback?.onMetadataUpdate(TunerMetadata(
                    stationName = ps, radioText = rt, frequency = currentFrequency,
                    rssi = rssi, pi = pi, pty = pty, tp = tp, ta = ta,
                    afList = afList, isAM = true
                ))
            }
        })
    }
}
