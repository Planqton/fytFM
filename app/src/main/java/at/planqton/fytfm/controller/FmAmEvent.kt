package at.planqton.fytfm.controller

/**
 * Events emitted by [FmAmController]. Replaces the old `var on...` lambda
 * properties — multiple subscribers can collect the [FmAmController.events]
 * flow independently. [RadioController] is one such subscriber, but tests
 * (and future ViewModels that want direct sub-controller access) can attach
 * without stepping on each other's callback assignments.
 */
sealed class FmAmEvent {
    data class RadioStateChanged(val isOn: Boolean) : FmAmEvent()
    data class FrequencyChanged(val frequency: Float) : FmAmEvent()
    data class RdsUpdate(
        val ps: String,
        val rt: String,
        val rssi: Int,
        val pi: Int,
        val pty: Int,
    ) : FmAmEvent()
    data class SeekComplete(val frequency: Float) : FmAmEvent()
    data class Error(val message: String) : FmAmEvent()
}
