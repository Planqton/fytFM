package at.planqton.fytfm.dab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import at.planqton.fytfm.R
import java.util.Timer
import java.util.TimerTask
import kotlin.random.Random

/**
 * Mock DAB tuner that powers the user-facing "DAB Demo" mode.
 *
 * Plays one of three bundled MP3s (`res/raw/demo_track_*`) on every tune,
 * loops to a random next track when one ends, and feeds the active track
 * into the DLS / DL+ callbacks so the radio-text UI shows real "now playing"
 * data. Shares the audio pool across all five demo stations — same songs,
 * different metadata.
 *
 * Strict separation from the real DAB backend:
 *  - own service-IDs in the 2000+ range (real DAB ensembles never produce
 *    these)
 *  - own logo resources (`R.drawable.demo_logo_*`)
 *  - any MediaPlayer is released on every state transition (tune, stop,
 *    deinitialize) so a backend switch can never leak audio
 */
class MockDabTunerManager : DabTunerBackend {

    companion object {
        private const val TAG = "MockDabTunerManager"
        const val SERVICE_ID_OFFSET = 2000
    }

    private var appContext: Context? = null
    private var isInitialized = false
    private var currentService: DabStation? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Timers for metadata simulation
    private var dlsRepeatTimer: Timer? = null
    private var slideshowTimer: Timer? = null
    private var receptionTimer: Timer? = null
    private var scanTimer: Timer? = null

    // Audio
    private var mediaPlayer: MediaPlayer? = null
    private var currentTrack: DemoTrack? = null
    private var lastTrackResId: Int = 0

    // Callbacks (same as DabTunerManager)
    override var onServiceStarted: ((DabStation) -> Unit)? = null
    override var onServiceStopped: (() -> Unit)? = null
    override var onTunerReady: (() -> Unit)? = null
    override var onTunerError: ((String) -> Unit)? = null
    override var onDynamicLabel: ((String) -> Unit)? = null
    override var onDlPlus: ((artist: String?, title: String?) -> Unit)? = null
    override var onAudioStarted: ((audioSessionId: Int) -> Unit)? = null
    override var onSlideshow: ((Bitmap) -> Unit)? = null
    override var onReceptionStats: ((sync: Boolean, quality: String, snr: Int) -> Unit)? = null

    // Recording callbacks (mock - writes empty file)
    override var onRecordingStarted: (() -> Unit)? = null
    override var onRecordingStopped: ((java.io.File) -> Unit)? = null
    override var onRecordingError: ((String) -> Unit)? = null
    override var onRecordingProgress: ((durationSeconds: Long) -> Unit)? = null

    // EPG callbacks
    override var onEpgDataReceived: ((EpgData) -> Unit)? = null

    val isDabOn: Boolean get() = isInitialized && currentService != null

    /** Demo station definition — kept private; exposed only via [getServices]. */
    private data class DemoStation(
        val serviceId: Int,
        val ensembleId: Int,
        val serviceLabel: String,
        val ensembleLabel: String,
        val ensembleFrequencyKHz: Int,
        val logoResId: Int,
        val accentColor: Int,
    )

    /** Audio track in the demo pool — shared across all demo stations. */
    private data class DemoTrack(val resId: Int, val artist: String, val title: String)

    private val demoStations = listOf(
        DemoStation(SERVICE_ID_OFFSET + 1, 1, "Demo Wave",  "Demo Multiplex", 223936, R.drawable.demo_logo_1, 0xFF1976D2.toInt()),
        DemoStation(SERVICE_ID_OFFSET + 2, 1, "Demo Pulse", "Demo Multiplex", 223936, R.drawable.demo_logo_2, 0xFFE53935.toInt()),
        DemoStation(SERVICE_ID_OFFSET + 3, 1, "Demo Chill", "Demo Multiplex", 223936, R.drawable.demo_logo_3, 0xFF43A047.toInt()),
        DemoStation(SERVICE_ID_OFFSET + 4, 2, "Demo Beat",  "Demo Klassik",   216928, R.drawable.demo_logo_4, 0xFFFB8C00.toInt()),
        DemoStation(SERVICE_ID_OFFSET + 5, 2, "Demo Retro", "Demo Klassik",   216928, R.drawable.demo_logo_5, 0xFF8E24AA.toInt()),
    )

    // Track metadata is hand-curated and must match the audio in the
    // matching res/raw/demo_track_N.mp3. When swapping audio files, update
    // both at once — DLS/DL+ rely on this mapping being correct.
    private val demoTracks = listOf(
        DemoTrack(R.raw.demo_track_1, "C418",              "Aria Math"),
        DemoTrack(R.raw.demo_track_2, "David Wise",        "Hot Head Bop"),
        DemoTrack(R.raw.demo_track_3, "Masterlink",        "Few Paths Forbidden"),
        DemoTrack(R.raw.demo_track_4, "Eagle-Eye Cherry",  "Save Tonight"),
        DemoTrack(R.raw.demo_track_5, "Toto",              "Africa"),
        DemoTrack(R.raw.demo_track_6, "Lighthouse Family", "High"),
    )

    /** Public so callers (e.g. MainActivity scan flow) can render station logos. */
    fun getLogoResId(serviceId: Int): Int? =
        demoStations.find { it.serviceId == serviceId }?.logoResId

    private fun mockStationsAsDab(): List<DabStation> = demoStations.map {
        DabStation(
            serviceId = it.serviceId,
            ensembleId = it.ensembleId,
            serviceLabel = it.serviceLabel,
            ensembleLabel = it.ensembleLabel,
            ensembleFrequencyKHz = it.ensembleFrequencyKHz,
        )
    }

    /**
     * Attach a long-lived application context independent of the
     * initialize/deinitialize cycle. Called once from FytFMApplication.onCreate
     * so the demo backend can play audio even when activated via setBackend()
     * without a prior initialize() call.
     */
    fun attachApplicationContext(context: Context) {
        appContext = context.applicationContext
    }

    override fun initialize(context: Context): Boolean {
        Log.i(TAG, "Initializing demo DAB tuner")
        appContext = context.applicationContext
        isInitialized = true

        mainHandler.postDelayed({
            Log.i(TAG, "Demo tuner ready")
            onTunerReady?.invoke()
        }, 500)

        return true
    }

    override fun deinitialize() {
        Log.i(TAG, "Deinitializing demo DAB tuner")
        stopScan()
        stopService()
        isInitialized = false
        // Keep appContext: setBackend() doesn't re-call initialize(), so a
        // power-cycle (deinit → switch backend → tune without powerOn) would
        // hit playRandomTrack() with a null context and silently drop audio.
    }

    override fun startScan(listener: DabScanListener) {
        Log.i(TAG, "Starting demo scan")

        if (currentService != null) {
            stopService()
        }

        listener.onScanStarted()

        var progress = 0
        scanTimer?.cancel()
        val timer = Timer()
        scanTimer = timer
        val frequencies = listOf(
            174928 to "5A",
            180640 to "6A",
            188928 to "7D",
            216928 to "10A",
            223936 to "11D",
            227360 to "12A",
        )
        val foundStations = mockStationsAsDab()

        timer.scheduleAtFixedRate(object : TimerTask() {
            var currentIndex = 0

            override fun run() {
                if (currentIndex >= frequencies.size) {
                    timer.cancel()
                    if (scanTimer === timer) scanTimer = null
                    mainHandler.post {
                        Log.i(TAG, "Demo scan finished. Found ${foundStations.size} services.")
                        listener.onScanFinished(foundStations)
                    }
                    return
                }

                val (freq, block) = frequencies[currentIndex]
                progress = ((currentIndex + 1) * 100) / frequencies.size

                mainHandler.post {
                    listener.onScanProgress(progress, "Block $block (${freq / 1000.0} MHz)")
                }

                val stationsOnFreq = foundStations.filter { it.ensembleFrequencyKHz == freq }
                for (station in stationsOnFreq) {
                    mainHandler.post { listener.onServiceFound(station) }
                }

                currentIndex++
            }
        }, 300, 500)
    }

    override fun stopScan() {
        Log.i(TAG, "Stopping demo scan")
        scanTimer?.cancel()
        scanTimer = null
    }

    override fun tuneService(serviceId: Int, ensembleId: Int): Boolean {
        val demo = demoStations.find { it.serviceId == serviceId && it.ensembleId == ensembleId }
        if (demo == null) {
            Log.w(TAG, "Demo station not found: SID=$serviceId, EID=$ensembleId")
            return false
        }
        val station = mockStationsAsDab().find { it.serviceId == serviceId } ?: return false

        Log.i(TAG, "Tuning to demo service: ${station.serviceLabel}")

        stopService()
        currentService = station

        mainHandler.post { onServiceStarted?.invoke(station) }

        // Audio + DLS pipeline
        playRandomTrack()
        startDlsRepeatTimer()
        startSlideshowSimulation()
        startReceptionSimulation()

        return true
    }

    override fun stopService() {
        stopDlsRepeatTimer()
        stopSlideshowSimulation()
        stopReceptionSimulation()
        releaseMediaPlayer()
        currentTrack = null

        if (currentService != null) {
            currentService = null
            mainHandler.post { onServiceStopped?.invoke() }
        }
    }

    override fun getServices(): List<DabStation> = mockStationsAsDab()

    override fun getCurrentService(): DabStation? = currentService

    override fun hasTuner(): Boolean = isInitialized

    override fun isDabAvailable(context: Context): Boolean = true

    override fun getAudioSessionId(): Int = mediaPlayer?.audioSessionId ?: 0

    // === Audio loop ===

    private fun playRandomTrack() {
        val ctx = appContext ?: run {
            Log.w(TAG, "No context — cannot start audio")
            return
        }
        releaseMediaPlayer()

        // Pick a track different from the previous one whenever possible.
        val pool = demoTracks.filter { it.resId != lastTrackResId }
        val track = (if (pool.isNotEmpty()) pool else demoTracks).random()
        lastTrackResId = track.resId
        currentTrack = track

        val player = MediaPlayer.create(ctx, track.resId) ?: run {
            Log.e(TAG, "MediaPlayer.create returned null for ${track.title}")
            return
        }
        player.setVolume(1.0f, 1.0f)
        player.isLooping = false
        player.setOnCompletionListener {
            Log.d(TAG, "Track finished, queuing next")
            // Stay on the current station, just pull the next random track.
            if (currentService != null) {
                playRandomTrack()
            }
        }
        player.setOnErrorListener { _, what, extra ->
            Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
            false
        }
        mediaPlayer = player
        player.start()
        Log.i(TAG, "Now playing: ${track.artist} - ${track.title}")

        // Tell the controller about the new audio session and current track.
        mainHandler.post {
            onAudioStarted?.invoke(player.audioSessionId)
            onDynamicLabel?.invoke("${track.artist} - ${track.title}")
            onDlPlus?.invoke(track.artist, track.title)
        }
    }

    private fun releaseMediaPlayer() {
        mediaPlayer?.let { mp ->
            try {
                if (mp.isPlaying) mp.stop()
            } catch (_: IllegalStateException) {
                // already released or in error state — fine
            }
            try {
                mp.release()
            } catch (_: Exception) {
            }
        }
        mediaPlayer = null
    }

    // === DLS repeat (real DAB sends the same DLS string periodically) ===

    private fun startDlsRepeatTimer() {
        stopDlsRepeatTimer()
        val timer = Timer()
        dlsRepeatTimer = timer
        timer.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                val track = currentTrack ?: return
                val ctx = appContext

                mainHandler.post {
                    if (ctx != null && Random.nextFloat() < 0.3f) {
                        // 30% info-style filler
                        val msgRes = listOf(
                            R.string.demo_info_message_1,
                            R.string.demo_info_message_2,
                            R.string.demo_info_message_3,
                            R.string.demo_info_message_4,
                        ).random()
                        onDynamicLabel?.invoke(ctx.getString(msgRes))
                    } else {
                        onDynamicLabel?.invoke("${track.artist} - ${track.title}")
                    }
                }
            }
        }, 8000, 8000) // First fill after 8 s, then every 8 s
    }

    private fun stopDlsRepeatTimer() {
        dlsRepeatTimer?.cancel()
        dlsRepeatTimer = null
    }

    // === Slideshow ===

    private fun startSlideshowSimulation() {
        stopSlideshowSimulation()
        val timer = Timer()
        slideshowTimer = timer
        timer.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                val bitmap = generateMockSlideshow() ?: return
                mainHandler.post { onSlideshow?.invoke(bitmap) }
            }
        }, 2000, 15000)
    }

    private fun stopSlideshowSimulation() {
        slideshowTimer?.cancel()
        slideshowTimer = null
    }

    private fun startReceptionSimulation() {
        stopReceptionSimulation()
        val timer = Timer()
        receptionTimer = timer
        timer.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                val sync = !isSignalLossSimulated
                // Match the real OMRI ReceptionQuality enum values: BEST / GOOD / OKAY / POOR.
                // The previous "Excellent"/"Medium" labels never matched — they fell through
                // every consumer's case-mapping (icon, banner) and registered as "no signal".
                val quality = if (isSignalLossSimulated) "Bad" else listOf("Best", "Good", "Okay").random()
                val snr = if (isSignalLossSimulated) 0 else Random.nextInt(15, 30)
                mainHandler.post {
                    onReceptionStats?.invoke(sync, quality, snr)
                }
            }
        }, 1000, 5000)
    }

    private fun stopReceptionSimulation() {
        receptionTimer?.cancel()
        receptionTimer = null
    }

    /**
     * Vom UI getoggelter Signal-Loss-Test. Wenn aktiv, liefert die nächste
     * Reception-Stats-Emission `sync=false, quality="Bad", snr=0`, sodass
     * die UI das Empfangsverlust-Banner einfahren kann. Wird auch sofort
     * einmal explizit gefeuert, damit der Wechsel ohne Wartezeit ankommt.
     */
    @Volatile
    private var isSignalLossSimulated: Boolean = false

    fun isSignalLossSimulated(): Boolean = isSignalLossSimulated

    fun setSignalLossSimulated(simulated: Boolean) {
        if (isSignalLossSimulated == simulated) return
        isSignalLossSimulated = simulated
        Log.i(TAG, "Signal-loss simulation = $simulated")
        // Sofort einmal explizit feuern, damit der UI-Wechsel ohne Verzögerung ankommt
        if (currentService != null) {
            val sync = !simulated
            val quality = if (simulated) "Bad" else "Excellent"
            val snr = if (simulated) 0 else 25
            mainHandler.post { onReceptionStats?.invoke(sync, quality, snr) }
        }
    }

    // === Recording (Demo stub: writes a zero-byte placeholder file) ===

    private var demoRecordingFile: java.io.File? = null

    override fun startRecording(context: Context, folderUri: String): Boolean {
        val track = currentTrack
        val station = currentService
        if (track == null || station == null) {
            mainHandler.post { onRecordingError?.invoke("No service active") }
            return false
        }

        val outDir = context.filesDir.resolve("demo_recordings").apply { mkdirs() }
        val safeStation = station.serviceLabel.replace(Regex("[^A-Za-z0-9]+"), "_")
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val out = java.io.File(outDir, "${safeStation}_${timestamp}_demo.mp3")

        // Empty placeholder — we don't legally redistribute the audio loop.
        try {
            out.outputStream().use { /* zero-byte */ }
            demoRecordingFile = out
            mainHandler.post { onRecordingStarted?.invoke() }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Demo recording stub failed: ${e.message}", e)
            mainHandler.post {
                onRecordingError?.invoke(
                    appContext?.getString(R.string.dab_demo_recording_failed)
                        ?: "Demo recording failed"
                )
            }
            return false
        }
    }

    override fun stopRecording(): String? {
        val file = demoRecordingFile ?: return null
        demoRecordingFile = null
        mainHandler.post { onRecordingStopped?.invoke(file) }
        return file.absolutePath
    }

    override fun isRecording(): Boolean = demoRecordingFile != null

    // === EPG ===

    override fun getCurrentEpgData(): EpgData? {
        val service = currentService ?: return null
        val track = currentTrack
        return EpgData(
            serviceId = service.serviceId,
            serviceName = service.serviceLabel,
            currentItem = EpgItem(
                title = track?.title ?: "Demo Stream",
                description = track?.let { "${it.artist} — currently playing on demo loop" }
                    ?: "Demo broadcast — fytFM showcase mode",
                startTime = System.currentTimeMillis() / 1000 - 1800,
                endTime = System.currentTimeMillis() / 1000 + 1800,
                isLive = true,
            ),
            upcomingItems = listOf(
                EpgItem(
                    title = "Demo Continues",
                    description = "Random track from the demo pool plays next",
                    startTime = System.currentTimeMillis() / 1000 + 1800,
                    endTime = System.currentTimeMillis() / 1000 + 5400,
                    isLive = false,
                )
            ),
        )
    }

    /**
     * Generate a colored slideshow bitmap that includes the current track
     * info — gives the demo more "live" feel than fixed placeholder imagery.
     */
    private fun generateMockSlideshow(): Bitmap? {
        val service = currentService ?: return null
        val demo = demoStations.find { it.serviceId == service.serviceId } ?: return null

        val width = 480
        val height = 360
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(demo.accentColor)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 36f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        canvas.drawText(service.serviceLabel, width / 2f, height * 0.35f, paint)

        paint.textSize = 22f
        paint.isFakeBoldText = false
        val track = currentTrack
        if (track != null) {
            canvas.drawText(track.title, width / 2f, height * 0.55f, paint)
            paint.textSize = 18f
            canvas.drawText(track.artist, width / 2f, height * 0.65f, paint)
        }

        paint.textSize = 14f
        canvas.drawText("fytFM Demo", width / 2f, height * 0.85f, paint)

        return bitmap
    }
}
