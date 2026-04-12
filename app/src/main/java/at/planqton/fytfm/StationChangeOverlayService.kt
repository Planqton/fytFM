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
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import at.planqton.fytfm.data.PresetRepository
import coil.dispose
import coil.load
import java.io.File

class StationChangeOverlayService : Service() {

    companion object {
        const val ACTION_SHOW_OVERLAY = "at.planqton.fytfm.SHOW_STATION_OVERLAY"
        const val ACTION_SHOW_PERMANENT = "at.planqton.fytfm.SHOW_PERMANENT_OVERLAY"
        const val ACTION_HIDE_OVERLAY = "at.planqton.fytfm.HIDE_OVERLAY"
        const val EXTRA_FREQUENCY = "frequency"
        const val EXTRA_OLD_FREQUENCY = "old_frequency"
        const val EXTRA_STATIONS = "stations"
        const val EXTRA_IS_AM = "is_am"
        const val EXTRA_APP_IN_FOREGROUND = "app_in_foreground"
        // DAB extras
        const val EXTRA_IS_DAB = "is_dab"
        const val EXTRA_DAB_SERVICE_ID = "dab_service_id"
        const val EXTRA_DAB_OLD_SERVICE_ID = "dab_old_service_id"
        private const val CHANNEL_ID = "station_change_overlay"
        private const val NOTIFICATION_ID = 2001
    }

    data class StationData(
        val frequency: Float,
        val name: String?,
        val logoPath: String?,
        val isAM: Boolean = false,
        val isDab: Boolean = false,
        val serviceId: Int = 0
    )

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var recyclerView: RecyclerView? = null
    private var carouselAdapter: OverlayCarouselAdapter? = null
    private var isOverlayVisible = false
    private var isPermanentMode = false
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var presetRepository: PresetRepository

    private var currentStations: List<StationData> = emptyList()

    private val hideOverlayRunnable = Runnable {
        hideOverlay()
    }

    private var scrollToNewRunnable: Runnable? = null
    private var centerRunnable: Runnable? = null

    // Anker-Position: Der Sender der zentriert war als Overlay ERSTMALS aufging
    private var anchorPosition: Int = -1

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
        } catch (e: Exception) {}
        android.util.Log.i("fytFM", "StationChangeOverlayService stopped")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            when (it.action) {
                ACTION_SHOW_OVERLAY -> {
                    val frequency = it.getFloatExtra(EXTRA_FREQUENCY, 0f)
                    val oldFrequency = it.getFloatExtra(EXTRA_OLD_FREQUENCY, 0f)
                    val isAM = it.getBooleanExtra(EXTRA_IS_AM, false)
                    val isDab = it.getBooleanExtra(EXTRA_IS_DAB, false)
                    val dabServiceId = it.getIntExtra(EXTRA_DAB_SERVICE_ID, 0)
                    val dabOldServiceId = it.getIntExtra(EXTRA_DAB_OLD_SERVICE_ID, 0)
                    val appInForeground = it.getBooleanExtra(EXTRA_APP_IN_FOREGROUND, false)

                    // Don't show overlay if app is in foreground (unless permanent mode)
                    if (appInForeground && !isPermanentMode) {
                        return@let
                    }

                    // Parse stations from intent
                    val stationsJson = it.getStringExtra(EXTRA_STATIONS)
                    if (!stationsJson.isNullOrBlank()) {
                        parseStations(stationsJson)
                    }

                    if (isDab && dabServiceId > 0 && (presetRepository.isShowStationChangeToast() || isPermanentMode)) {
                        showDabOverlay(dabServiceId, dabOldServiceId)
                    } else if (frequency > 0 && (presetRepository.isShowStationChangeToast() || isPermanentMode)) {
                        // Don't change isPermanentMode here - keep it as is
                        showOverlay(frequency, isAM, oldFrequency)
                    }
                }
                ACTION_SHOW_PERMANENT -> {
                    val frequency = it.getFloatExtra(EXTRA_FREQUENCY, 0f)
                    val isAM = it.getBooleanExtra(EXTRA_IS_AM, false)

                    // Parse stations from intent
                    val stationsJson = it.getStringExtra(EXTRA_STATIONS)
                    if (!stationsJson.isNullOrBlank()) {
                        parseStations(stationsJson)
                    }

                    if (frequency > 0) {
                        isPermanentMode = true
                        showOverlay(frequency, isAM)
                    }
                }
                ACTION_HIDE_OVERLAY -> {
                    isPermanentMode = false
                    hideOverlay()
                }
            }
        }
        return START_STICKY
    }

    private fun parseStations(json: String) {
        try {
            val stations = mutableListOf<StationData>()
            val jsonArray = org.json.JSONArray(json)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                stations.add(StationData(
                    frequency = obj.getDouble("frequency").toFloat(),
                    name = obj.optString("name").takeIf { it.isNotBlank() },
                    logoPath = obj.optString("logoPath").takeIf { it.isNotBlank() },
                    isAM = obj.optBoolean("isAM", false),
                    isDab = obj.optBoolean("isDab", false),
                    serviceId = obj.optInt("serviceId", 0)
                ))
            }
            currentStations = stations
        } catch (e: Exception) {
            android.util.Log.e("fytFM", "Error parsing stations: ${e.message}")
        }
    }

    private fun showOverlay(frequency: Float, isAM: Boolean = false, oldFrequency: Float = 0f) {
        handler.post {
            try {
                // Cancel all previous callbacks
                handler.removeCallbacks(hideOverlayRunnable)
                scrollToNewRunnable?.let { handler.removeCallbacks(it) }
                centerRunnable?.let { handler.removeCallbacks(it) }

                if (overlayView == null) {
                    createOverlayView()
                }

                // Update adapter - mark the NEW station as selected
                carouselAdapter?.setStations(currentStations, frequency, isAM)

                // Find position of NEW station (aktuell gewählt, große Kachel)
                val newPosition = currentStations.indexOfFirst {
                    Math.abs(it.frequency - frequency) < 0.05f && it.isAM == isAM
                }

                val wasAlreadyVisible = isOverlayVisible

                if (!wasAlreadyVisible) {
                    // ERSTMALIGES Öffnen - Anker = vorheriger Sender
                    anchorPosition = if (oldFrequency > 0) {
                        currentStations.indexOfFirst {
                            Math.abs(it.frequency - oldFrequency) < 0.05f && it.isAM == isAM
                        }
                    } else {
                        newPosition
                    }

                    // Overlay unsichtbar hinzufügen
                    overlayView?.alpha = 0f
                    addOverlayToWindow()

                    // Nach Layout: Scroll so dass BEIDE (Anker und neuer) sichtbar sind
                    recyclerView?.post {
                        val layoutManager = recyclerView?.layoutManager as? LinearLayoutManager ?: return@post
                        // Scroll zum Anker (der sollte links/mitte sein, neuer rechts davon)
                        if (anchorPosition >= 0) {
                            // Berechne Position so dass Anker etwa 1/3 vom linken Rand ist
                            val cardWidth = (216 * resources.displayMetrics.density).toInt()
                            val rvWidth = recyclerView?.width ?: 0
                            val offset = rvWidth / 4  // ~1/4 vom linken Rand
                            layoutManager.scrollToPositionWithOffset(anchorPosition, offset)
                        }
                        overlayView?.alpha = 1f
                    }
                } else {
                    // Netflix-Style: Nur scrollen wenn neues Item am Rand oder außerhalb
                    recyclerView?.let { rv ->
                        val layoutManager = rv.layoutManager as? LinearLayoutManager ?: return@let
                        val firstComplete = layoutManager.findFirstCompletelyVisibleItemPosition()
                        val lastComplete = layoutManager.findLastCompletelyVisibleItemPosition()

                        // Scroll nur wenn außerhalb des KOMPLETT sichtbaren Bereichs
                        if (newPosition < firstComplete || newPosition > lastComplete) {
                            rv.smoothScrollToPosition(newPosition)
                        }
                        // Sonst: Fokus wandert einfach, kein Scroll
                    }
                }

                // Nach 2 Sek: Fokussiertes Item in die Mitte scrollen
                centerRunnable = Runnable {
                    if (newPosition >= 0) {
                        recyclerView?.let { rv ->
                            val layoutManager = rv.layoutManager as? LinearLayoutManager ?: return@let

                            // Erst sicherstellen dass Item sichtbar ist
                            val targetView = layoutManager.findViewByPosition(newPosition)
                            if (targetView != null) {
                                // Item ist sichtbar - berechne Scroll zum Zentrieren
                                val rvCenter = rv.width / 2
                                val viewCenter = targetView.left + targetView.width / 2
                                val scrollBy = viewCenter - rvCenter
                                rv.smoothScrollBy(scrollBy, 0)
                            } else {
                                // Item nicht sichtbar - erst hinscrollenund dann zentrieren
                                rv.smoothScrollToPosition(newPosition)
                                // Nach dem Scroll nochmal zentrieren
                                rv.postDelayed({
                                    val view = layoutManager.findViewByPosition(newPosition)
                                    if (view != null) {
                                        val rvCenter = rv.width / 2
                                        val viewCenter = view.left + view.width / 2
                                        val scrollBy = viewCenter - rvCenter
                                        rv.smoothScrollBy(scrollBy, 0)
                                    }
                                }, 300)
                            }
                        }
                    }
                }
                handler.postDelayed(centerRunnable!!, 2000)

                // Step 4: After 2 + 1 = 3 sec total, hide overlay (unless permanent mode)
                if (!isPermanentMode) {
                    handler.postDelayed(hideOverlayRunnable, 3000)
                }

            } catch (e: Exception) {
                android.util.Log.e("fytFM", "Error showing overlay: ${e.message}")
            }
        }
    }

    private fun showDabOverlay(serviceId: Int, oldServiceId: Int = 0) {
        handler.post {
            try {
                // Cancel all previous callbacks
                handler.removeCallbacks(hideOverlayRunnable)
                scrollToNewRunnable?.let { handler.removeCallbacks(it) }
                centerRunnable?.let { handler.removeCallbacks(it) }

                if (overlayView == null) {
                    createOverlayView()
                }

                // Update adapter - mark the NEW station as selected (use serviceId for DAB)
                carouselAdapter?.setDabStations(currentStations, serviceId)

                // Find position of NEW station by serviceId
                val newPosition = currentStations.indexOfFirst { it.isDab && it.serviceId == serviceId }

                val wasAlreadyVisible = isOverlayVisible

                if (!wasAlreadyVisible) {
                    // ERSTMALIGES Öffnen - Anker = vorheriger Sender
                    anchorPosition = if (oldServiceId > 0) {
                        currentStations.indexOfFirst { it.isDab && it.serviceId == oldServiceId }
                    } else {
                        newPosition
                    }

                    // Overlay unsichtbar hinzufügen
                    overlayView?.alpha = 0f
                    addOverlayToWindow()

                    // Nach Layout: Scroll so dass BEIDE (Anker und neuer) sichtbar sind
                    recyclerView?.post {
                        val layoutManager = recyclerView?.layoutManager as? LinearLayoutManager ?: return@post
                        if (anchorPosition >= 0) {
                            val rvWidth = recyclerView?.width ?: 0
                            val offset = rvWidth / 4
                            layoutManager.scrollToPositionWithOffset(anchorPosition, offset)
                        }
                        overlayView?.alpha = 1f
                    }
                } else {
                    // Netflix-Style: Nur scrollen wenn neues Item am Rand oder außerhalb
                    recyclerView?.let { rv ->
                        val layoutManager = rv.layoutManager as? LinearLayoutManager ?: return@let
                        val firstComplete = layoutManager.findFirstCompletelyVisibleItemPosition()
                        val lastComplete = layoutManager.findLastCompletelyVisibleItemPosition()

                        if (newPosition < firstComplete || newPosition > lastComplete) {
                            rv.smoothScrollToPosition(newPosition)
                        }
                    }
                }

                // Nach 2 Sek: Fokussiertes Item in die Mitte scrollen
                centerRunnable = Runnable {
                    if (newPosition >= 0) {
                        recyclerView?.let { rv ->
                            val layoutManager = rv.layoutManager as? LinearLayoutManager ?: return@let
                            val targetView = layoutManager.findViewByPosition(newPosition)
                            if (targetView != null) {
                                val rvCenter = rv.width / 2
                                val viewCenter = targetView.left + targetView.width / 2
                                val scrollBy = viewCenter - rvCenter
                                rv.smoothScrollBy(scrollBy, 0)
                            } else {
                                rv.smoothScrollToPosition(newPosition)
                            }
                        }
                    }
                }
                handler.postDelayed(centerRunnable!!, 2000)

                if (!isPermanentMode) {
                    handler.postDelayed(hideOverlayRunnable, 3000)
                }

            } catch (e: Exception) {
                android.util.Log.e("fytFM", "Error showing DAB overlay: ${e.message}")
            }
        }
    }

    private fun createOverlayView() {
        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.overlay_station_carousel, null)

        recyclerView = overlayView?.findViewById(R.id.carouselRecyclerView)
        recyclerView?.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        recyclerView?.clipToPadding = false

        // Netflix-Style: Mehrere Items sichtbar, Fokus wandert, scrollt nur am Rand
        // Kleines Padding nur für Abstand zum Bildschirmrand
        val edgePadding = (24 * resources.displayMetrics.density).toInt()
        recyclerView?.setPadding(edgePadding, 0, edgePadding, 0)

        carouselAdapter = OverlayCarouselAdapter()
        recyclerView?.adapter = carouselAdapter
    }

    private fun addOverlayToWindow() {
        if (overlayView == null || isOverlayVisible) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
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
        params.y = 20

        try {
            windowManager?.addView(overlayView, params)
            isOverlayVisible = true
            android.util.Log.d("fytFM", "Carousel overlay shown")
        } catch (e: Exception) {
            android.util.Log.e("fytFM", "Failed to add overlay: ${e.message}")
        }
    }

    private fun hideOverlay() {
        handler.post {
            if (isOverlayVisible && overlayView != null) {
                try {
                    windowManager?.removeView(overlayView)
                    overlayView = null
                    recyclerView = null
                    carouselAdapter = null
                    isOverlayVisible = false
                    anchorPosition = -1  // Anker zurücksetzen
                    android.util.Log.d("fytFM", "Carousel overlay hidden")
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
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.steering_wheel_active))
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.steering_wheel_active))
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setOngoing(true)
                .build()
        }
    }

    // Inner Adapter for Carousel
    inner class OverlayCarouselAdapter : RecyclerView.Adapter<OverlayCarouselAdapter.ViewHolder>() {

        private var stations: List<StationData> = emptyList()
        private var selectedFrequency: Float = 0f
        private var selectedIsAM: Boolean = false
        private var selectedServiceId: Int = 0
        private var isDabMode: Boolean = false

        fun setStations(newStations: List<StationData>, frequency: Float, isAM: Boolean) {
            stations = newStations
            selectedFrequency = frequency
            selectedIsAM = isAM
            isDabMode = false
            notifyDataSetChanged()
        }

        fun setDabStations(newStations: List<StationData>, serviceId: Int) {
            stations = newStations
            selectedServiceId = serviceId
            isDabMode = true
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_station_card, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val station = stations[position]
            val isSelected = if (isDabMode) {
                station.isDab && station.serviceId == selectedServiceId
            } else {
                Math.abs(station.frequency - selectedFrequency) < 0.05f && station.isAM == selectedIsAM
            }

            // Format frequency/name based on mode
            if (station.isDab) {
                holder.frequencyText.text = station.name ?: "DAB+"
                holder.bandLabel.text = "DAB+"
                holder.stationNameInline.text = ""
            } else {
                val freqText = if (station.isAM) {
                    station.frequency.toInt().toString()
                } else {
                    "%.2f".format(station.frequency).replace(".", ",")
                }
                holder.frequencyText.text = freqText
                holder.bandLabel.text = if (station.isAM) "AM" else "FM"
                holder.stationNameInline.text = if (!station.name.isNullOrBlank()) " ${station.name}" else ""
            }

            // Scaling for selected
            val scale = if (isSelected) 1.0f else 0.75f
            holder.itemView.scaleX = scale
            holder.itemView.scaleY = scale
            holder.itemView.alpha = if (isSelected) 1.0f else 0.5f

            // Station name
            if (!station.name.isNullOrBlank() && !station.isDab) {
                holder.stationName.text = station.name
                holder.stationName.visibility = View.VISIBLE
            } else {
                holder.stationName.visibility = View.GONE
            }

            // Logo - dispose any pending load first to prevent caching issues
            holder.stationLogo.dispose()
            holder.stationLogo.visibility = View.VISIBLE
            if (!station.logoPath.isNullOrBlank()) {
                holder.stationLogo.load(File(station.logoPath)) {
                    crossfade(true)
                }
            } else {
                // Use FM placeholder for DAB as well (no dedicated DAB placeholder)
                val placeholder = if (station.isAM) R.drawable.placeholder_am else R.drawable.placeholder_fm
                holder.stationLogo.setImageResource(placeholder)
            }
        }

        override fun getItemCount(): Int = stations.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val frequencyText: TextView = view.findViewById(R.id.frequencyText)
            val bandLabel: TextView = view.findViewById(R.id.bandLabel)
            val stationName: TextView = view.findViewById(R.id.stationName)
            val stationNameInline: TextView = view.findViewById(R.id.stationNameInline)
            val stationLogo: ImageView = view.findViewById(R.id.stationLogo)
        }
    }
}
