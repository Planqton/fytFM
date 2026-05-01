package at.planqton.fytfm.ui.lifecycle

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import at.planqton.fytfm.data.PresetRepository

/**
 * Runs the "auto-move-to-background" countdown when enabled in settings.
 * The Activity drives it from onResume / onPause / onUserInteraction; the
 * timer, tick callbacks and countdown toast all live here.
 */
class AutoBackgroundController(
    private val context: Context,
    private val presetRepository: PresetRepository,
    private val onTimerExpired: () -> Unit,
) {
    companion object {
        private const val TAG = "AutoBackgroundCtrl"
        private const val USER_INTERACTION_GRACE_MS = 1000L
    }

    private var handler: Handler? = null
    private var runnable: Runnable? = null
    private var secondsRemaining = 0
    private var timerStartTime = 0L
    private var toast: Toast? = null

    fun startIfNeeded(wasStartedFromBoot: Boolean) {
        val enabled = presetRepository.isAutoBackgroundEnabled()
        val onlyOnBoot = presetRepository.isAutoBackgroundOnlyOnBoot()
        Log.d(TAG, "check: enabled=$enabled, onlyOnBoot=$onlyOnBoot, wasStartedFromBoot=$wasStartedFromBoot")

        if (!enabled) return
        if (onlyOnBoot && !wasStartedFromBoot) {
            Log.d(TAG, "Skipping - not started from boot")
            return
        }
        startTimer()
    }

    /**
     * Cancel the timer if the user has interacted AND the timer has been
     * running for at least the grace window — avoids the "countdown starts
     * and the first onUserInteraction from the resume gesture kills it" race.
     */
    fun onUserInteraction() {
        if (timerStartTime > 0 &&
            SystemClock.uptimeMillis() - timerStartTime > USER_INTERACTION_GRACE_MS) {
            cancel()
        }
    }

    fun cancel() {
        runnable?.let { handler?.removeCallbacks(it) }
        runnable = null
        handler = null
        secondsRemaining = 0
        timerStartTime = 0L
        toast?.cancel()
        toast = null
    }

    private fun startTimer() {
        cancel()
        secondsRemaining = presetRepository.getAutoBackgroundDelay()
        timerStartTime = SystemClock.uptimeMillis()
        Log.d(TAG, "Starting timer for ${secondsRemaining}s")
        showToast()

        handler = Handler(Looper.getMainLooper())
        runnable = object : Runnable {
            override fun run() {
                secondsRemaining--
                Log.d(TAG, "Tick - ${secondsRemaining}s remaining")
                if (secondsRemaining <= 0) {
                    Log.i(TAG, "Moving app to background")
                    toast?.cancel()
                    onTimerExpired()
                } else {
                    showToast()
                    handler?.postDelayed(this, 1000L)
                }
            }
        }
        handler?.postDelayed(runnable!!, 1000L)
    }

    private fun showToast() {
        toast?.cancel()
        toast = Toast.makeText(
            context,
            "Auto-Background in ${secondsRemaining}s",
            Toast.LENGTH_SHORT,
        ).also { it.show() }
    }
}
