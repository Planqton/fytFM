package at.planqton.fytfm.controller

import android.graphics.Bitmap
import at.planqton.fytfm.FrequencyScaleView
import at.planqton.fytfm.dab.DabStation

sealed class RadioEvent {
    data class ModeChanged(val mode: FrequencyScaleView.RadioMode) : RadioEvent()
    data class RadioStateChanged(val isOn: Boolean) : RadioEvent()
    data class FrequencyChanged(val frequency: Float) : RadioEvent()
    data class RdsUpdate(
        val ps: String,
        val rt: String,
        val rssi: Int,
        val pi: Int,
        val pty: Int,
    ) : RadioEvent()

    object DabTunerReady : RadioEvent()
    data class DabServiceStarted(val station: DabStation) : RadioEvent()
    object DabServiceStopped : RadioEvent()
    data class DabDynamicLabel(val dls: String) : RadioEvent()
    data class DabDlPlus(val artist: String?, val title: String?) : RadioEvent()
    data class DabSlideshow(val bitmap: Bitmap) : RadioEvent()
    data class DabAudioStarted(val sessionId: Int) : RadioEvent()
    data class DabReceptionStats(val sync: Boolean, val quality: String, val snr: Int) : RadioEvent()
    object DabRecordingStarted : RadioEvent()
    data class DabRecordingStopped(val file: java.io.File?) : RadioEvent()
    data class DabRecordingError(val error: String) : RadioEvent()
    data class DabEpgReceived(val data: Any?) : RadioEvent()

    data class ErrorEvent(val message: String) : RadioEvent()
}
