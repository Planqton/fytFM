package at.planqton.fytfm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import at.planqton.fytfm.data.PresetRepository

class StationChangeOverlayService : Service() {

    companion object {
        const val ACTION_SHOW_OVERLAY = "at.planqton.fytfm.SHOW_STATION_OVERLAY"
        const val EXTRA_FREQUENCY = "frequency"
        private const val CHANNEL_ID = "station_change_overlay"
        private const val NOTIFICATION_ID = 2001
        private const val OVERLAY_DURATION_MS = 2500L
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var isOverlayVisible = false
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var presetRepository: PresetRepository

    private val hideOverlayRunnable = Runnable {
        hideOverlay()
    }

    private val stationChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_SHOW_OVERLAY) {
                val frequency = intent.getFloatExtra(EXTRA_FREQUENCY, 0f)
                if (frequency > 0 && presetRepository.isShowStationChangeToast()) {
                    showOverlay(frequency)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        presetRepository = PresetRepository(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        // Register broadcast receiver
        val filter = IntentFilter(ACTION_SHOW_OVERLAY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stationChangeReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stationChangeReceiver, filter)
        }

        android.util.Log.i("fytFM", "StationChangeOverlayService started")
    }

    override fun onDestroy() {
        super.onDestroy()
        hideOverlay()
        try {
            unregisterReceiver(stationChangeReceiver)
        } catch (e: Exception) {
            // Receiver not registered
        }
        android.util.Log.i("fytFM", "StationChangeOverlayService stopped")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            if (it.action == ACTION_SHOW_OVERLAY) {
                val frequency = it.getFloatExtra(EXTRA_FREQUENCY, 0f)
                if (frequency > 0 && presetRepository.isShowStationChangeToast()) {
                    showOverlay(frequency)
                }
            }
        }
        return START_STICKY
    }

    private fun showOverlay(frequency: Float) {
        handler.post {
            try {
                // Cancel any pending hide
                handler.removeCallbacks(hideOverlayRunnable)

                if (overlayView == null) {
                    createOverlayView()
                }

                // Update frequency text
                overlayView?.findViewById<TextView>(R.id.tvFrequency)?.text =
                    String.format("%.1f MHz", frequency)

                if (!isOverlayVisible) {
                    addOverlayToWindow()
                }

                // Schedule hide
                handler.postDelayed(hideOverlayRunnable, OVERLAY_DURATION_MS)

            } catch (e: Exception) {
                android.util.Log.e("fytFM", "Error showing overlay: ${e.message}")
            }
        }
    }

    private fun createOverlayView() {
        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.overlay_station_change, null)
    }

    private fun addOverlayToWindow() {
        if (overlayView == null || isOverlayVisible) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.y = 50 // Abstand vom oberen Rand

        try {
            windowManager?.addView(overlayView, params)
            isOverlayVisible = true
            android.util.Log.d("fytFM", "Overlay shown")
        } catch (e: Exception) {
            android.util.Log.e("fytFM", "Failed to add overlay: ${e.message}")
        }
    }

    private fun hideOverlay() {
        handler.post {
            if (isOverlayVisible && overlayView != null) {
                try {
                    windowManager?.removeView(overlayView)
                    isOverlayVisible = false
                    android.util.Log.d("fytFM", "Overlay hidden")
                } catch (e: Exception) {
                    android.util.Log.e("fytFM", "Failed to remove overlay: ${e.message}")
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Senderwechsel Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Zeigt Overlay bei Senderwechsel"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("fytFM")
                .setContentText("Lenkradtasten aktiv")
                .setSmallIcon(R.drawable.ic_radio)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("fytFM")
                .setContentText("Lenkradtasten aktiv")
                .setSmallIcon(R.drawable.ic_radio)
                .setOngoing(true)
                .build()
        }
    }
}
