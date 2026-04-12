package at.planqton.fytfm.dab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Timer
import java.util.TimerTask
import kotlin.random.Random

/**
 * Mock DAB Tuner for UI development without real hardware.
 * Simulates all DAB features: stations, DLS, DL+, slideshow, scan.
 */
class MockDabTunerManager {

    companion object {
        private const val TAG = "MockDabTunerManager"
    }

    private var isInitialized = false
    private var currentService: DabStation? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Timers for metadata simulation
    private var metadataTimer: Timer? = null
    private var slideshowTimer: Timer? = null
    private var receptionTimer: Timer? = null

    // Callbacks (same as DabTunerManager)
    var onServiceStarted: ((DabStation) -> Unit)? = null
    var onServiceStopped: (() -> Unit)? = null
    var onTunerReady: (() -> Unit)? = null
    var onTunerError: ((String) -> Unit)? = null
    var onDynamicLabel: ((String) -> Unit)? = null
    var onDlPlus: ((artist: String?, title: String?) -> Unit)? = null
    var onAudioStarted: ((audioSessionId: Int) -> Unit)? = null
    var onSlideshow: ((Bitmap) -> Unit)? = null
    var onReceptionStats: ((sync: Boolean, quality: String, snr: Int) -> Unit)? = null

    // Recording callbacks (mock - not functional)
    var onRecordingStarted: (() -> Unit)? = null
    var onRecordingStopped: ((java.io.File) -> Unit)? = null
    var onRecordingError: ((String) -> Unit)? = null
    var onRecordingProgress: ((durationSeconds: Long) -> Unit)? = null

    // EPG callbacks
    var onEpgDataReceived: ((EpgData) -> Unit)? = null

    val isDabOn: Boolean get() = isInitialized && currentService != null

    // Mock stations
    private val mockStations = listOf(
        DabStation(serviceId = 1001, ensembleId = 1, serviceLabel = "Mock FM4", ensembleLabel = "Mock MUX Wien", ensembleFrequencyKHz = 223936),
        DabStation(serviceId = 1002, ensembleId = 1, serviceLabel = "Mock Ö3", ensembleLabel = "Mock MUX Wien", ensembleFrequencyKHz = 223936),
        DabStation(serviceId = 1003, ensembleId = 1, serviceLabel = "Mock Ö1", ensembleLabel = "Mock MUX Wien", ensembleFrequencyKHz = 223936),
        DabStation(serviceId = 1004, ensembleId = 1, serviceLabel = "Mock Radio Wien", ensembleLabel = "Mock MUX Wien", ensembleFrequencyKHz = 223936),
        DabStation(serviceId = 1005, ensembleId = 2, serviceLabel = "Mock Hitradio Ö3", ensembleLabel = "Mock MUX Steiermark", ensembleFrequencyKHz = 216928),
        DabStation(serviceId = 1006, ensembleId = 2, serviceLabel = "Mock Radio Steiermark", ensembleLabel = "Mock MUX Steiermark", ensembleFrequencyKHz = 216928),
        DabStation(serviceId = 1007, ensembleId = 3, serviceLabel = "Mock Klassik Radio", ensembleLabel = "Mock MUX Deutschland", ensembleFrequencyKHz = 227360),
        DabStation(serviceId = 1008, ensembleId = 3, serviceLabel = "Mock Deutschlandfunk", ensembleLabel = "Mock MUX Deutschland", ensembleFrequencyKHz = 227360)
    )

    // Mock songs for DL+ simulation
    private val mockSongs = listOf(
        Pair("Mock Artist 1", "Test Song Alpha"),
        Pair("Mock Artist 2", "Test Song Beta"),
        Pair("Test Band", "Development Track"),
        Pair("UI Test Artist", "Debug Mode Anthem"),
        Pair("Simulation Orchestra", "Mock Symphony No. 1"),
        Pair("Dev Team", "Building the Future"),
        Pair("Sample Singer", "Lorem Ipsum Groove"),
        Pair("Mock DJ", "Beat Generator v2")
    )

    // Mock DLS messages
    private val mockDlsMessages = listOf(
        "Mock DAB+ - UI Development Mode",
        "Testing DLS functionality...",
        "This is a simulated broadcast",
        "No real tuner required!",
        "Perfect for UI development",
        "DAB Dev Mode active"
    )

    fun initialize(context: Context): Boolean {
        Log.i(TAG, "Initializing Mock DAB+ tuner...")
        isInitialized = true

        // Simulate tuner ready after short delay
        mainHandler.postDelayed({
            Log.i(TAG, "Mock tuner ready")
            onTunerReady?.invoke()
        }, 500)

        return true
    }

    fun deinitialize() {
        Log.i(TAG, "Deinitializing Mock DAB+ tuner...")
        stopService()
        isInitialized = false
    }

    fun startScan(listener: DabScanListener) {
        Log.i(TAG, "Starting mock DAB+ scan...")

        // Stop current service if playing
        if (currentService != null) {
            stopService()
        }

        listener.onScanStarted()

        // Simulate scan progress
        var progress = 0
        val scanTimer = Timer()
        val frequencies = listOf(
            Pair(174928, "5A"),
            Pair(180640, "6A"),
            Pair(188928, "7D"),
            Pair(216928, "10A"),
            Pair(223936, "11D"),
            Pair(227360, "12A")
        )

        scanTimer.scheduleAtFixedRate(object : TimerTask() {
            var currentIndex = 0

            override fun run() {
                if (currentIndex >= frequencies.size) {
                    scanTimer.cancel()
                    mainHandler.post {
                        Log.i(TAG, "Mock scan finished. Found ${mockStations.size} services.")
                        listener.onScanFinished(mockStations)
                    }
                    return
                }

                val (freq, block) = frequencies[currentIndex]
                progress = ((currentIndex + 1) * 100) / frequencies.size

                mainHandler.post {
                    listener.onScanProgress(progress, "Block $block (${freq / 1000.0} MHz)")
                }

                // Report stations found on this frequency
                val stationsOnFreq = mockStations.filter { it.ensembleFrequencyKHz == freq }
                for (station in stationsOnFreq) {
                    mainHandler.post {
                        listener.onServiceFound(station)
                    }
                }

                currentIndex++
            }
        }, 300, 500) // Start after 300ms, repeat every 500ms
    }

    fun stopScan() {
        Log.i(TAG, "Stopping mock scan")
        // In real implementation, would cancel the timer
    }

    fun tuneService(serviceId: Int, ensembleId: Int): Boolean {
        val station = mockStations.find { it.serviceId == serviceId && it.ensembleId == ensembleId }

        if (station == null) {
            Log.w(TAG, "Mock station not found: SID=$serviceId, EID=$ensembleId")
            return false
        }

        Log.i(TAG, "Tuning to mock service: ${station.serviceLabel}")

        // Stop previous service
        stopService()

        currentService = station

        mainHandler.post {
            onServiceStarted?.invoke(station)
            // Simulate audio session ID
            onAudioStarted?.invoke(12345)
        }

        // Start metadata simulation
        startMetadataSimulation()
        startSlideshowSimulation()
        startReceptionSimulation()

        return true
    }

    fun stopService() {
        stopMetadataSimulation()
        stopSlideshowSimulation()
        stopReceptionSimulation()

        if (currentService != null) {
            currentService = null
            mainHandler.post { onServiceStopped?.invoke() }
        }
    }

    fun getServices(): List<DabStation> = mockStations

    fun getCurrentService(): DabStation? = currentService

    fun hasTuner(): Boolean = isInitialized

    fun getAudioSessionId(): Int = if (currentService != null) 12345 else 0

    // Mock recording (not functional, but provides feedback)
    fun startRecording(context: Context, folderUri: String): Boolean {
        Log.i(TAG, "Mock recording started (not actually recording)")
        mainHandler.post { onRecordingError?.invoke("Recording not available in DAB Dev mode") }
        return false
    }

    fun stopRecording(): String? = null
    fun isRecording(): Boolean = false
    fun getRecordingDuration(): Long = 0

    // EPG
    fun getCurrentEpgData(): EpgData? {
        val service = currentService ?: return null
        return EpgData(
            serviceId = service.serviceId,
            serviceName = service.serviceLabel,
            currentItem = EpgItem(
                title = "Mock Show",
                description = "This is a simulated EPG entry for UI development",
                startTime = System.currentTimeMillis() / 1000 - 1800,
                endTime = System.currentTimeMillis() / 1000 + 1800,
                isLive = true
            ),
            upcomingItems = listOf(
                EpgItem(
                    title = "Next Mock Show",
                    description = "Coming up next in the simulation",
                    startTime = System.currentTimeMillis() / 1000 + 1800,
                    endTime = System.currentTimeMillis() / 1000 + 5400,
                    isLive = false
                )
            )
        )
    }

    fun hasEpgData(): Boolean = currentService != null

    // === Simulation methods ===

    private fun startMetadataSimulation() {
        metadataTimer = Timer()
        metadataTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                val song = mockSongs.random()
                mainHandler.post {
                    onDlPlus?.invoke(song.first, song.second)
                    onDynamicLabel?.invoke("${song.first} - ${song.second}")
                }
            }
        }, 0, 8000) // Every 8 seconds

        // Also send DLS messages periodically
        Timer().scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                if (Random.nextFloat() < 0.3f) { // 30% chance for info message instead of song
                    val message = mockDlsMessages.random()
                    mainHandler.post {
                        onDynamicLabel?.invoke(message)
                    }
                }
            }
        }, 4000, 12000) // Offset from song updates
    }

    private fun stopMetadataSimulation() {
        metadataTimer?.cancel()
        metadataTimer = null
    }

    private fun startSlideshowSimulation() {
        slideshowTimer = Timer()
        slideshowTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                val bitmap = generateMockSlideshow()
                mainHandler.post {
                    onSlideshow?.invoke(bitmap)
                }
            }
        }, 2000, 15000) // Every 15 seconds, start after 2 seconds
    }

    private fun stopSlideshowSimulation() {
        slideshowTimer?.cancel()
        slideshowTimer = null
    }

    private fun startReceptionSimulation() {
        receptionTimer = Timer()
        receptionTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                val qualities = listOf("Excellent", "Good", "Medium")
                val quality = qualities.random()
                val snr = Random.nextInt(15, 30)
                mainHandler.post {
                    onReceptionStats?.invoke(true, quality, snr)
                }
            }
        }, 1000, 5000) // Every 5 seconds
    }

    private fun stopReceptionSimulation() {
        receptionTimer?.cancel()
        receptionTimer = null
    }

    /**
     * Generate a colored mock slideshow image with text.
     */
    private fun generateMockSlideshow(): Bitmap {
        val width = 320
        val height = 240
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Random background color
        val colors = listOf(
            Color.rgb(52, 152, 219),   // Blue
            Color.rgb(46, 204, 113),   // Green
            Color.rgb(155, 89, 182),   // Purple
            Color.rgb(231, 76, 60),    // Red
            Color.rgb(241, 196, 15),   // Yellow
            Color.rgb(26, 188, 156)    // Teal
        )
        canvas.drawColor(colors.random())

        // Draw text
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 24f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }

        val stationName = currentService?.serviceLabel ?: "DAB Dev"
        canvas.drawText(stationName, width / 2f, height / 2f - 20f, paint)

        paint.textSize = 16f
        paint.isFakeBoldText = false
        canvas.drawText("Mock Slideshow", width / 2f, height / 2f + 20f, paint)

        paint.textSize = 12f
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        canvas.drawText(timestamp, width / 2f, height / 2f + 50f, paint)

        return bitmap
    }
}
