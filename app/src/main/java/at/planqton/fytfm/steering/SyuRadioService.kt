package at.planqton.fytfm.steering

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import android.widget.Toast

/**
 * Service der versucht als com.syu.radio.broadcast.MyService zu fungieren.
 * Das FYT-System versucht diesen Service zu binden wenn Lenkradtasten gedrückt werden.
 */
class SyuRadioService : Service() {

    companion object {
        private const val TAG = "SyuRadioService"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "=== SERVICE CREATED ===")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "=== SERVICE START COMMAND ===")
        Log.i(TAG, "Action: ${intent?.action}")
        Log.i(TAG, "Extras: ${intent?.extras}")

        intent?.extras?.keySet()?.forEach { key ->
            Log.i(TAG, "  Extra: $key = ${intent.extras?.get(key)}")
        }

        Toast.makeText(this, "SyuRadio Service gestartet!", Toast.LENGTH_SHORT).show()

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.i(TAG, "=== SERVICE BIND ===")
        Log.i(TAG, "Action: ${intent?.action}")
        Log.i(TAG, "Extras: ${intent?.extras}")

        intent?.extras?.keySet()?.forEach { key ->
            Log.i(TAG, "  Extra: $key = ${intent.extras?.get(key)}")
        }

        Toast.makeText(this, "SyuRadio Service gebunden!", Toast.LENGTH_SHORT).show()

        // Return null - wir wollen nur loggen was passiert
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "=== SERVICE DESTROYED ===")
    }
}
