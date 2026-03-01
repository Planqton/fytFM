package at.planqton.fytfm.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast

/**
 * BroadcastReceiver für Media Button Events (Lenkradtasten).
 * Wird vom System aufgerufen wenn Media-Buttons gedrückt werden.
 */
class MediaButtonReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "MediaButtonReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        Log.i(TAG, "onReceive: action=${intent?.action}")

        val validActions = listOf(
            Intent.ACTION_MEDIA_BUTTON,
            "byd.intent.action.MEDIA_BUTTON",
            "byd.intent.action.MEDIA_MODE"
        )

        if (intent == null || intent.action !in validActions) {
            return
        }

        val keyEvent = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
        if (keyEvent == null) {
            Log.d(TAG, "No KeyEvent in intent")
            return
        }

        Log.i(TAG, "=== MEDIA BUTTON RECEIVED ===")
        Log.i(TAG, "KeyCode: ${keyEvent.keyCode}, Action: ${keyEvent.action}")

        // Nur auf KEY_UP reagieren
        if (keyEvent.action != KeyEvent.ACTION_UP) {
            return
        }

        when (keyEvent.keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                Log.i(TAG, "NEXT pressed")
                Toast.makeText(context, "fytFM: NEXT", Toast.LENGTH_SHORT).show()
                // TODO: Callback an MainActivity
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                Log.i(TAG, "PREVIOUS pressed")
                Toast.makeText(context, "fytFM: PREV", Toast.LENGTH_SHORT).show()
                // TODO: Callback an MainActivity
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK -> {
                Log.i(TAG, "PLAY/PAUSE pressed")
                Toast.makeText(context, "fytFM: PLAY/PAUSE", Toast.LENGTH_SHORT).show()
                // TODO: Callback an MainActivity
            }
            else -> {
                Log.d(TAG, "Unknown keyCode: ${keyEvent.keyCode}")
            }
        }
    }
}
