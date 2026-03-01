package at.planqton.fytfm.steering

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

/**
 * Receiver der versucht com.syu.radio Broadcasts abzufangen.
 */
class SyuRadioReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SyuRadioReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        Log.i(TAG, "=== RECEIVED BROADCAST ===")
        Log.i(TAG, "Action: ${intent?.action}")
        Log.i(TAG, "Extras: ${intent?.extras}")

        intent?.extras?.keySet()?.forEach { key ->
            Log.i(TAG, "  Extra: $key = ${intent.extras?.get(key)}")
        }

        Toast.makeText(context, "SyuRadio Broadcast empfangen!", Toast.LENGTH_SHORT).show()
    }
}
