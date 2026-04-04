package at.planqton.fytfm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * BroadcastReceiver für Autostart bei System-Boot.
 * Startet die MainActivity wenn Autostart in den Settings aktiviert ist.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        const val EXTRA_FROM_BOOT = "from_boot"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {

            Log.i(TAG, "Boot completed received")

            val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val autoStartEnabled = prefs.getBoolean("auto_start_enabled", false)

            if (autoStartEnabled) {
                Log.i(TAG, "Autostart enabled - launching MainActivity")
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(EXTRA_FROM_BOOT, true)
                }
                context.startActivity(launchIntent)
            } else {
                Log.d(TAG, "Autostart disabled - not launching")
            }
        }
    }
}
