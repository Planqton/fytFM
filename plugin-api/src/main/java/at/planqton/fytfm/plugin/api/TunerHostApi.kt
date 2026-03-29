package at.planqton.fytfm.plugin.api

import android.graphics.Bitmap

/**
 * Hardware-Abstraktionsschicht die vom Host bereitgestellt wird.
 * Plugins nutzen dieses Interface statt direkter Hardware-Zugriffe.
 */
interface TunerHostApi {
    // Hardware Detection
    fun isFmChipAvailable(): Boolean
    fun isDabDeviceAvailable(): Boolean
    fun isMcuAvailable(): Boolean

    // FM/AM Chip
    fun openDevice(): Boolean
    fun closeDevice()
    fun powerUp(frequency: Float): Boolean
    fun powerOff()
    fun tune(frequency: Float)
    fun getSignalStrength(): Int
    fun setMute(mute: Boolean)
    fun setLocalMode(local: Boolean)
    fun setMonoMode(mono: Boolean)
    fun setRadioArea(area: Int)

    // RDS
    fun enableRds()
    fun clearRds()
    fun startRdsPolling(callback: RdsDataCallback)
    fun stopRdsPolling()

    // Audio Routing / MCU
    fun initRadioSequence()
    fun radioOn()
    fun radioOff()
    fun setAudioSourceFm()
    fun unmuteMcu()
    fun muteAmplifier(mute: Boolean)

    // System
    fun sendBroadcast(action: String)
    fun startFmService()
    fun setSystemProperty(key: String, value: String)

    // Settings
    fun isLocalMode(): Boolean
    fun isMonoMode(): Boolean
    fun getRadioArea(): Int

    // DAB
    fun initializeDab()
    fun shutdownDab()
    fun startDabScan()
    fun stopDabScan()
    fun startDabPlayback(serviceIndex: Int)
    fun stopDabPlayback()
    fun loadDabStations()
    fun getDabStationCount(): Int
    fun setDabCallback(callback: DabDataCallback)
}

interface RdsDataCallback {
    fun onRdsUpdate(
        ps: String?, rt: String?, rssi: Int, pi: Int,
        pty: Int, tp: Int, ta: Int, afList: ShortArray?
    )
}

interface DabDataCallback {
    fun onStationsLoaded(count: Int)
    fun onServiceStarted(label: String)
    fun onServiceStopped()
    fun onDynamicLabel(text: String)
    fun onDlPlus(artist: String?, title: String?)
    fun onSlideshow(bitmap: Bitmap)
    fun onLogo(bitmap: Bitmap)
    fun onScanStarted()
    fun onScanProgress(percent: Int)
    fun onScanFinished(count: Int)
    fun onTunerReady()
    fun onTunerError()
}
