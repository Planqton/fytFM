package at.planqton.fytfm.controller

import android.graphics.Bitmap
import at.planqton.fytfm.dab.DabStation
import at.planqton.fytfm.dab.EpgData

/**
 * Events emitted by [DabController]. Replaces the old `var on...` lambda
 * properties — multiple subscribers can collect the [DabController.events]
 * flow independently. The 13-callback API previously exposed (TunerReady,
 * ServiceStarted, DLS, DL+, Slideshow, ReceptionStats, AudioStarted, three
 * Recording lifecycle events, EpgData, TunerError, ServiceStopped) is
 * mirrored here as data classes so existing wiring in [RadioController]
 * (and the test suite) can `when`-branch on the type.
 */
sealed class DabEvent {
    object TunerReady : DabEvent()
    data class ServiceStarted(val station: DabStation) : DabEvent()
    object ServiceStopped : DabEvent()
    data class TunerError(val message: String) : DabEvent()
    data class DynamicLabel(val dls: String) : DabEvent()
    data class DlPlus(val artist: String?, val title: String?) : DabEvent()
    data class Slideshow(val bitmap: Bitmap) : DabEvent()
    data class ReceptionStats(val sync: Boolean, val quality: String, val snr: Int) : DabEvent()
    data class AudioStarted(val audioSessionId: Int) : DabEvent()
    object RecordingStarted : DabEvent()
    data class RecordingStopped(val file: java.io.File?) : DabEvent()
    data class RecordingError(val error: String) : DabEvent()
    data class EpgReceived(val data: EpgData?) : DabEvent()
}
