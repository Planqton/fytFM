package at.planqton.fytfm

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.widget.ProgressBar
import at.planqton.fytfm.data.PresetRepository
import at.planqton.fytfm.data.UpdateRepository
import at.planqton.fytfm.data.UpdateState
import at.planqton.fytfm.data.rdslog.RdsLogRepository
import at.planqton.fytfm.data.rdslog.RdsDatabase
import at.planqton.fytfm.data.rdslog.RtCorrection
import at.planqton.fytfm.data.rdslog.RtCorrectionDao
import at.planqton.fytfm.data.rdslog.EditString
import at.planqton.fytfm.data.rdslog.EditStringDao
import at.planqton.fytfm.media.FytFMMediaService
import at.planqton.fytfm.ui.CorrectionsAdapter
import at.planqton.fytfm.ui.EditStringsAdapter
import at.planqton.fytfm.ui.RdsLogAdapter
import at.planqton.fytfm.ui.StationAdapter
import android.widget.EditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.android.fmradio.FmNative
import com.syu.jni.SyuJniNative
import at.planqton.fytfm.spotify.SpotifyClient
import at.planqton.fytfm.spotify.SpotifyCache
import at.planqton.fytfm.spotify.RtCombiner
import at.planqton.fytfm.spotify.TrackInfo
import coil.load

class MainActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase))
    }

    private lateinit var tvFrequency: TextView
    private lateinit var frequencyScale: FrequencyScaleView
    private lateinit var btnPrevStation: ImageButton
    private lateinit var btnNextStation: ImageButton
    private lateinit var btnFavorite: ImageButton
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnFM: ImageButton
    private lateinit var btnPower: ImageButton
    private lateinit var controlBar: LinearLayout
    private lateinit var stationRecycler: RecyclerView
    private lateinit var tvAllList: TextView
    private var debugOverlay: View? = null
    private var debugChecklist: View? = null
    private var debugPs: TextView? = null
    private var debugPi: TextView? = null
    private var debugPty: TextView? = null
    private var debugRt: TextView? = null
    private var debugRssi: TextView? = null
    private var debugFreq: TextView? = null
    private var debugAf: TextView? = null
    private var debugAfUsing: TextView? = null
    private var debugTpTa: TextView? = null
    private var checkRdsInfo: CheckBox? = null
    private var checkLayoutInfo: CheckBox? = null
    private var checkBuildInfo: CheckBox? = null
    private var checkSpotifyInfo: CheckBox? = null
    private var debugLayoutOverlay: View? = null
    private var debugBuildOverlay: View? = null
    private var debugSpotifyOverlay: View? = null
    private var debugScreenInfo: TextView? = null
    private var debugDensityInfo: TextView? = null
    private var checkDebugButtons: CheckBox? = null
    private var debugButtonsOverlay: View? = null

    // Now Playing Bar
    private var nowPlayingBar: View? = null
    private var nowPlayingCover: ImageView? = null
    private var nowPlayingArtist: TextView? = null
    private var nowPlayingTitle: TextView? = null
    private var nowPlayingRawRt: TextView? = null
    private var lastDisplayedTrackId: String? = null
    private var lastDebugTrackId: String? = null

    // PiP Layout
    private var pipLayout: View? = null
    private var pipCoverImage: ImageView? = null
    private var pipTitle: TextView? = null
    private var pipArtist: TextView? = null
    private var pipBtnPlayPause: ImageButton? = null
    private var pipRawRt: TextView? = null

    // Correction Helper Buttons
    private var nowPlayingCorrectionButtons: View? = null
    private var btnCorrectionRefresh: ImageButton? = null
    private var btnCorrectionTrash: ImageButton? = null
    private var rtCorrectionDao: RtCorrectionDao? = null
    private var editStringDao: EditStringDao? = null

    // Debug: Internet-Simulation deaktivieren
    var debugInternetDisabled = false
        private set

    // Debug: Spotify/Local blockieren (nur RDS anzeigen)
    var debugSpotifyBlocked = false
        private set

    private lateinit var presetRepository: PresetRepository
    private lateinit var stationAdapter: StationAdapter
    private lateinit var fmNative: FmNative
    private lateinit var rdsManager: RdsManager
    private lateinit var radioScanner: at.planqton.fytfm.scanner.RadioScanner
    private lateinit var updateRepository: UpdateRepository
    private lateinit var rdsLogRepository: RdsLogRepository
    private lateinit var radioLogoRepository: at.planqton.fytfm.data.logo.RadioLogoRepository
    private lateinit var updateBadge: View
    private var settingsUpdateListener: ((UpdateState) -> Unit)? = null
    private var twUtil: TWUtilHelper? = null

    // Spotify Integration
    private var spotifyClient: SpotifyClient? = null
    private var spotifyCache: SpotifyCache? = null
    private var rtCombiner: RtCombiner? = null

    // Bug Report: Aktuelle Spotify-Status-Daten
    private var currentSpotifyStatus: String? = null
    private var currentSpotifyOriginalRt: String? = null
    private var currentSpotifyStrippedRt: String? = null
    private var currentSpotifyQuery: String? = null
    private var currentSpotifyTrackInfo: TrackInfo? = null

    // Spotify Cache Export/Import launchers
    private val spotifyCacheExportLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                exportSpotifyCacheToUri(uri)
            }
        }
    }

    private val spotifyCacheImportLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                importSpotifyCacheFromUri(uri)
            }
        }
    }

    // Logo Template Import
    private var logoTemplateImportCallback: ((Boolean) -> Unit)? = null
    private val logoTemplateImportLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                importLogoTemplateFromUri(uri)
            }
        } else {
            logoTemplateImportCallback?.invoke(false)
            logoTemplateImportCallback = null
        }
    }

    private var isPlaying = true
    private var isRadioOn = false
    private var showFavoritesOnly = false

    // Archive UI
    private lateinit var archiveAdapter: RdsLogAdapter
    private var archiveJob: Job? = null
    private var archiveSearchQuery: String = ""
    private var archiveFilterFrequency: Float? = null  // null = all

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Silently ignore com.syu.radio intents - we're already the active radio app
        if (intent?.action == "com.syu.radio") {
            android.util.Log.d("fytFM", "Received com.syu.radio intent - ignoring (already active)")
        }
        // Debug commands via: adb shell am start -n at.planqton.fytfm/.MainActivity --es debug "settings"
        intent?.getStringExtra("debug")?.let { cmd ->
            android.util.Log.d("fytFM", "Debug command: $cmd")
            when (cmd) {
                "settings" -> showSettingsDialog()
                "screenshot" -> takeDebugScreenshot()
            }
        }
    }

    private fun takeDebugScreenshot() {
        val dir = java.io.File("/sdcard/debugscreenshots")
        if (!dir.exists()) dir.mkdirs()
        val file = java.io.File(dir, "screenshot_${System.currentTimeMillis()}.png")

        window.decorView.rootView.let { view ->
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            view.draw(canvas)
            try {
                java.io.FileOutputStream(file).use { out ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                }
                android.util.Log.d("fytFM", "Screenshot saved: ${file.absolutePath}")
            } catch (e: Exception) {
                android.util.Log.e("fytFM", "Screenshot failed: ${e.message}")
            }
        }
    }

    /**
     * Setzt uns als bevorzugte App für com.syu.radio Intent.
     * Funktioniert nur als System-App.
     */
    private fun setAsPreferredRadioApp() {
        try {
            val pm = packageManager

            // Erst alle bestehenden Präferenzen für diesen Intent löschen
            val intent = Intent("com.syu.radio")
            pm.clearPackagePreferredActivities(packageName)

            // IntentFilter für com.syu.radio
            val filter = IntentFilter("com.syu.radio")
            filter.addCategory(Intent.CATEGORY_DEFAULT)

            // Alle Activities die diesen Intent handeln können
            val resolveInfos = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            val componentNames = resolveInfos.map {
                ComponentName(it.activityInfo.packageName, it.activityInfo.name)
            }.toTypedArray()

            // Uns als bevorzugte Activity setzen
            val myComponent = ComponentName(this, MainActivity::class.java)

            @Suppress("DEPRECATION")
            pm.addPreferredActivity(filter, IntentFilter.MATCH_CATEGORY_EMPTY, componentNames, myComponent)

            android.util.Log.i("fytFM", "Set as preferred activity for com.syu.radio")
        } catch (e: Exception) {
            android.util.Log.w("fytFM", "Could not set preferred activity: ${e.message}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        presetRepository = PresetRepository(this)
        fmNative = FmNative.getInstance()
        rdsManager = RdsManager(fmNative)

        // Root-Fallback Listener für UIS7870/DUDU7 Geräte
        // Zeigt einmalige Meldung wenn Root für FM-Zugriff benötigt wird
        rdsManager.setRootRequiredListener {
            runOnUiThread {
                android.widget.Toast.makeText(
                    this,
                    getString(R.string.root_required_message),
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }

        radioScanner = at.planqton.fytfm.scanner.RadioScanner(rdsManager)
        updateRepository = UpdateRepository(this)
        rdsLogRepository = RdsLogRepository(this)
        rdsLogRepository.performCleanup()  // Cleanup old entries on app start
        radioLogoRepository = at.planqton.fytfm.data.logo.RadioLogoRepository(this)

        // Initialize Spotify Integration
        initSpotifyIntegration()

        // Update Badge auf Settings-Button
        updateBadge = findViewById(R.id.updateBadge)
        updateRepository.setStateListener { state ->
            runOnUiThread {
                // Badge anzeigen wenn Update verfügbar
                updateBadge.visibility = if (state is UpdateState.UpdateAvailable) View.VISIBLE else View.GONE
                // Settings-Dialog Listener aufrufen falls aktiv
                settingsUpdateListener?.invoke(state)
            }
        }
        updateRepository.checkForUpdatesSilent()  // Prüft still auf Updates beim Start

        // Initialize TWUtil for MCU communication - critical for RDS!
        twUtil = TWUtilHelper()
        if (twUtil?.isAvailable == true) {
            android.util.Log.i("fytFM", "TWUtil available, opening...")
            if (twUtil?.open() == true) {
                android.util.Log.i("fytFM", "TWUtil opened successfully")
            }
        }

        // Pass TWUtil to DebugReceiver for ADB debugging
        DebugReceiver.setTwUtil(twUtil)

        // Als bevorzugte Radio-App setzen (verhindert Chooser-Dialog)
        setAsPreferredRadioApp()

        initViews()
        setupStationList()
        setupListeners()

        // Start MediaService for Car Launcher integration
        startMediaService()

        // Load last frequency from SharedPreferences
        val lastFreq = loadLastFrequency()
        frequencyScale.setFrequency(lastFreq)
        rdsManager.setUiFrequency(lastFreq)  // Für AF-Vergleich
        rdsLogRepository.setInitialFrequency(lastFreq, frequencyScale.getMode() == FrequencyScaleView.RadioMode.AM)
        updateFrequencyDisplay(lastFreq)
        updateModeButton()
        loadFavoritesFilterState()
        loadStationsForCurrentMode()
        updatePowerButton()
        updateFavoriteButton()

        // Auto power on if enabled in settings
        if (presetRepository.isPowerOnStartup() && !isRadioOn) {
            toggleRadioPower()
        }
    }

    /**
     * Initialisiert Spotify Client und RT Combiner wenn Credentials vorhanden
     */
    private fun initSpotifyIntegration() {
        val clientId = presetRepository.getSpotifyClientId()
        val clientSecret = presetRepository.getSpotifyClientSecret()

        // Always create cache (for offline fallback)
        if (spotifyCache == null) {
            spotifyCache = SpotifyCache(this)
        }

        // Initialize correction DAOs
        if (rtCorrectionDao == null) {
            rtCorrectionDao = RdsDatabase.getInstance(this).rtCorrectionDao()
        }
        if (editStringDao == null) {
            editStringDao = RdsDatabase.getInstance(this).editStringDao()
        }

        if (clientId.isNotBlank() && clientSecret.isNotBlank()) {
            spotifyClient = SpotifyClient(clientId, clientSecret)
            rtCombiner = RtCombiner(
                spotifyClient = spotifyClient,
                spotifyCache = spotifyCache,
                isCacheEnabled = { presetRepository.isSpotifyCacheEnabled() },
                isNetworkAvailable = { isNetworkAvailable() },
                correctionDao = rtCorrectionDao,
                editStringDao = editStringDao
            ) { status, originalRt, strippedRt, query, trackInfo ->
                runOnUiThread {
                    // Store for bug reports
                    currentSpotifyStatus = status
                    currentSpotifyOriginalRt = originalRt
                    currentSpotifyStrippedRt = strippedRt
                    currentSpotifyQuery = query
                    currentSpotifyTrackInfo = trackInfo
                    updateSpotifyDebugInfo(status, originalRt, strippedRt, query, trackInfo)
                    // Bei keinem Match: Raw RT parsen und anzeigen
                    val displayInfo = trackInfo ?: strippedRt?.let { parseRawRtToTrackInfo(it) }
                    updateNowPlaying(displayInfo)
                    nowPlayingRawRt?.text = strippedRt ?: ""
                    updatePipDisplay()
                }
            }
            android.util.Log.i("fytFM", "Spotify integration initialized")
        } else {
            spotifyClient = null
            // Still create RtCombiner with cache only for offline mode
            rtCombiner = RtCombiner(
                spotifyClient = null,
                spotifyCache = spotifyCache,
                isCacheEnabled = { presetRepository.isSpotifyCacheEnabled() },
                isNetworkAvailable = { isNetworkAvailable() },
                correctionDao = rtCorrectionDao,
                editStringDao = editStringDao
            ) { status, originalRt, strippedRt, query, trackInfo ->
                runOnUiThread {
                    // Store for bug reports
                    currentSpotifyStatus = status
                    currentSpotifyOriginalRt = originalRt
                    currentSpotifyStrippedRt = strippedRt
                    currentSpotifyQuery = query
                    currentSpotifyTrackInfo = trackInfo
                    updateSpotifyDebugInfo(status, originalRt, strippedRt, query, trackInfo)
                    // Bei keinem Match: Raw RT parsen und anzeigen
                    val displayInfo = trackInfo ?: strippedRt?.let { parseRawRtToTrackInfo(it) }
                    updateNowPlaying(displayInfo)
                    nowPlayingRawRt?.text = strippedRt ?: ""
                    updatePipDisplay()
                }
            }
            android.util.Log.i("fytFM", "Spotify credentials not configured, using cache only")
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Reinitialize Spotify integration (called when credentials change)
     */
    fun reinitSpotifyIntegration() {
        rtCombiner?.destroy()
        initSpotifyIntegration()
    }

    /**
     * Startet das RDS-Polling mit Callback für PS, RT, RSSI, PI, PTY, TP, TA, AF.
     */
    private fun startRdsPolling() {
        rdsManager.startPolling(object : RdsManager.RdsCallback {
            override fun onRdsUpdate(ps: String?, rt: String?, rssi: Int, pi: Int, pty: Int, tp: Int, ta: Int, afList: ShortArray?) {
                // Log RDS data (only on RT change)
                rdsLogRepository.onRdsUpdate(ps, rt, pi, pty, tp, ta, rssi, afList)

                // Process RT through Spotify integration if available (unless blocked)
                val combiner = rtCombiner
                if (combiner != null && !rt.isNullOrBlank() && !debugSpotifyBlocked) {
                    val currentFrequency = frequencyScale.getFrequency()
                    CoroutineScope(Dispatchers.IO).launch {
                        val combinedRt = combiner.processRt(pi, rt, currentFrequency)
                        val finalRt = combinedRt ?: rt

                        // Get track info for cover art
                        // Only use trackInfo if we actually found a match for the current song
                        // (combinedRt is non-null only when Spotify found a match)
                        val trackInfo = if (combinedRt != null) combiner.getLastTrackInfo(pi) else null

                        // Update MediaService with combined RT (must be on Main thread!)
                        withContext(Dispatchers.Main) {
                            val isAM = frequencyScale.getMode() == FrequencyScaleView.RadioMode.AM
                            val currentFreq = frequencyScale.getFrequency()
                            val radioLogoPath = radioLogoRepository.getLogoForStation(ps, pi, currentFreq)
                            FytFMMediaService.instance?.updateMetadata(
                                frequency = currentFreq,
                                ps = ps,
                                rt = finalRt,
                                isAM = isAM,
                                coverUrl = trackInfo?.coverUrlMedium,  // Spotify URL (300px)
                                localCoverPath = trackInfo?.coverUrl,  // Local cached path
                                radioLogoPath = radioLogoPath          // Radio logo as fallback
                            )
                        }
                    }
                } else if (!rt.isNullOrBlank()) {
                    // No Spotify - use raw RT, with radio logo as fallback
                    val isAM = frequencyScale.getMode() == FrequencyScaleView.RadioMode.AM
                    val currentFreq = frequencyScale.getFrequency()
                    val radioLogoPath = radioLogoRepository.getLogoForStation(ps, pi, currentFreq)
                    FytFMMediaService.instance?.updateMetadata(
                        frequency = currentFreq,
                        ps = ps,
                        rt = rt,
                        isAM = isAM,
                        radioLogoPath = radioLogoPath
                    )
                    // Trotzdem RT parsen und in UI anzeigen
                    runOnUiThread {
                        val displayInfo = parseRawRtToTrackInfo(rt)
                        updateNowPlaying(displayInfo)
                        nowPlayingRawRt?.text = rt
                        updatePipDisplay()
                    }
                }

                runOnUiThread {
                    // Alter für jeden Wert holen
                    val psAge = rdsManager.psAgeMs
                    val piAge = rdsManager.piAgeMs
                    val ptyAge = rdsManager.ptyAgeMs
                    val rtAge = rdsManager.rtAgeMs
                    val rssiAge = rdsManager.rssiAgeMs
                    val tpTaAge = rdsManager.tpTaAgeMs
                    val afAge = rdsManager.afAgeMs

                    // Labels mit Alter aktualisieren (Format: "(Xs) Label:")
                    findViewById<android.widget.TextView>(R.id.labelPs)?.text =
                        if (psAge >= 0) "(${psAge / 1000}s) PS:" else "PS:"
                    findViewById<android.widget.TextView>(R.id.labelPi)?.text =
                        if (piAge >= 0) "(${piAge / 1000}s) PI:" else "PI:"
                    findViewById<android.widget.TextView>(R.id.labelPty)?.text =
                        if (ptyAge >= 0) "(${ptyAge / 1000}s) PTY:" else "PTY:"
                    findViewById<android.widget.TextView>(R.id.labelRt)?.text =
                        if (rtAge >= 0) "(${rtAge / 1000}s) RT:" else "RT:"
                    findViewById<android.widget.TextView>(R.id.labelRssi)?.text =
                        if (rssiAge >= 0) "(${rssiAge / 1000}s) RSSI:" else "RSSI:"
                    findViewById<android.widget.TextView>(R.id.labelAf)?.text =
                        if (afAge >= 0) "(${afAge / 1000}s) AF:" else "AF:"
                    findViewById<android.widget.TextView>(R.id.labelTpTa)?.text =
                        if (tpTaAge >= 0) "(${tpTaAge / 1000}s) TP/TA:" else "TP/TA:"

                    // Werte ohne Alter-Präfix
                    val psStr = ps ?: ""
                    val piStr = if (pi != 0) String.format("0x%04X", pi and 0xFFFF) else ""
                    val ptyStr = if (pty > 0) "$pty (${RdsManager.getPtyName(pty)})" else ""
                    val rtStr = rt ?: ""
                    // RSSI: Relativer Wert 0-255 (kein echtes dBm)
                    val rssiStr = "$rssi"
                    val tpTaStr = "TP=$tp TA=$ta"
                    val afStr = if (afList != null && afList.isNotEmpty()) {
                        afList.map { freq ->
                            val freqMhz = if (freq > 875) freq / 10.0f else (87.5f + freq * 0.1f)
                            String.format("%.1f", freqMhz)
                        }.joinToString(", ")
                    } else if (rdsManager.isAfEnabled) {
                        "enabled"
                    } else {
                        "disabled"
                    }

                    // AF-Nutzung: Prüfen ob Hardware auf anderer Frequenz ist
                    // CMD_CURRENTFREQ gibt leider keine Daten zurück, daher können wir
                    // AF-Switches nicht erkennen. Zeigen stattdessen Hardware-Frequenz wenn verfügbar.
                    val hwFreq = rdsManager.hardwareFrequency
                    val afUsingStr = if (hwFreq > 0) {
                        if (rdsManager.isUsingAlternateFrequency) {
                            "Yes (${String.format("%.1f", hwFreq)} MHz)"
                        } else {
                            "No (${String.format("%.1f", hwFreq)} MHz)"
                        }
                    } else {
                        "-- (not available)"
                    }

                    // Header statisch
                    findViewById<android.widget.TextView>(R.id.debugHeader)?.text = "RDS Debug"

                    updateDebugInfo(
                        ps = psStr,
                        rt = rtStr,
                        rssiStr = rssiStr,
                        pi = piStr,
                        pty = ptyStr,
                        tpTa = tpTaStr,
                        af = afStr,
                        afUsing = afUsingStr
                    )
                }
            }
        })
    }

    private fun stopRdsPolling() {
        rdsManager.stopPolling()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        setPipLayout(isInPictureInPictureMode)
    }

    override fun onResume() {
        super.onResume()
        // Re-check PiP mode when resuming (wichtig für Start im kleinen Fenster)
        recheckPipMode("onResume")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Re-check when window focus changes (wichtig nach Sleep-Mode)
        if (hasFocus) {
            recheckPipMode("onWindowFocusChanged")
        }
    }

    private fun recheckPipMode(source: String) {
        val rootView = findViewById<View>(R.id.rootLayout) ?: return
        rootView.post {
            val width = rootView.width
            if (width > 0) {
                android.util.Log.d("fytFM", "$source PiP check: width=$width, isPipMode=$isPipMode")
                checkForPipModeByViewSize(width)
            }
        }
    }

    private var isPipMode = false

    private fun setPipLayout(isPip: Boolean) {
        android.util.Log.i("fytFM", "setPipLayout: isPip=$isPip (was: $isPipMode)")
        isPipMode = isPip
        if (isPip) {
            // Hide main UI elements
            btnFavorite.visibility = View.GONE
            btnPrevStation.visibility = View.GONE
            btnNextStation.visibility = View.GONE
            controlBar.visibility = View.GONE
            findViewById<LinearLayout>(R.id.stationBar)?.visibility = View.GONE
            tvFrequency.visibility = View.GONE
            frequencyScale.visibility = View.GONE

            // Hide all debug overlays
            debugOverlay?.visibility = View.GONE
            debugChecklist?.visibility = View.GONE
            debugLayoutOverlay?.visibility = View.GONE
            debugBuildOverlay?.visibility = View.GONE
            debugSpotifyOverlay?.visibility = View.GONE
            debugButtonsOverlay?.visibility = View.GONE

            // Show PiP specific layout
            findViewById<View>(R.id.pipLayout)?.visibility = View.VISIBLE

            updatePipDisplay()
        } else {
            // Show main UI elements
            btnFavorite.visibility = View.VISIBLE
            btnPrevStation.visibility = View.VISIBLE
            btnNextStation.visibility = View.VISIBLE
            controlBar.visibility = View.VISIBLE
            findViewById<LinearLayout>(R.id.stationBar)?.visibility = View.VISIBLE
            tvFrequency.visibility = View.VISIBLE
            frequencyScale.visibility = View.VISIBLE

            // Restore debug overlays based on checkbox states
            debugOverlay?.visibility = if (checkRdsInfo?.isChecked == true) View.VISIBLE else View.GONE
            debugChecklist?.visibility = View.VISIBLE
            debugLayoutOverlay?.visibility = if (checkLayoutInfo?.isChecked == true) View.VISIBLE else View.GONE
            debugBuildOverlay?.visibility = if (checkBuildInfo?.isChecked == true) View.VISIBLE else View.GONE
            debugSpotifyOverlay?.visibility = if (checkSpotifyInfo?.isChecked == true) View.VISIBLE else View.GONE
            debugButtonsOverlay?.visibility = if (checkDebugButtons?.isChecked == true) View.VISIBLE else View.GONE

            // Hide PiP specific layout
            findViewById<View>(R.id.pipLayout)?.visibility = View.GONE
        }
    }

    private fun initViews() {
        tvFrequency = findViewById(R.id.tvFrequency)
        frequencyScale = findViewById(R.id.frequencyScale)
        btnPrevStation = findViewById(R.id.btnPrevStation)
        btnNextStation = findViewById(R.id.btnNextStation)
        btnFavorite = findViewById(R.id.btnFavorite)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnFM = findViewById(R.id.btnFM)
        btnPower = findViewById(R.id.btnPower)
        controlBar = findViewById(R.id.controlBar)
        stationRecycler = findViewById(R.id.stationRecycler)
        tvAllList = findViewById(R.id.tvAllList)

        // Debug overlays
        debugOverlay = findViewById(R.id.debugOverlay)
        debugChecklist = findViewById(R.id.debugChecklistOverlay)
        debugPs = findViewById(R.id.debugPs)
        debugPi = findViewById(R.id.debugPi)
        debugPty = findViewById(R.id.debugPty)
        debugRt = findViewById(R.id.debugRt)
        debugRssi = findViewById(R.id.debugRssi)
        debugFreq = findViewById(R.id.debugFreq)
        debugAf = findViewById(R.id.debugAf)
        debugAfUsing = findViewById(R.id.debugAfUsing)
        debugTpTa = findViewById(R.id.debugTpTa)
        checkRdsInfo = findViewById(R.id.checkRdsInfo)
        checkLayoutInfo = findViewById(R.id.checkLayoutInfo)
        checkBuildInfo = findViewById(R.id.checkBuildInfo)
        checkSpotifyInfo = findViewById(R.id.checkSpotifyInfo)
        debugLayoutOverlay = findViewById(R.id.debugLayoutOverlay)
        debugBuildOverlay = findViewById(R.id.debugBuildOverlay)
        debugSpotifyOverlay = findViewById(R.id.debugSpotifyOverlay)
        debugScreenInfo = findViewById(R.id.debugScreenInfo)
        debugDensityInfo = findViewById(R.id.debugDensityInfo)
        checkDebugButtons = findViewById(R.id.checkDebugButtons)
        debugButtonsOverlay = findViewById(R.id.debugButtonsOverlay)

        // Now Playing Bar
        nowPlayingBar = findViewById(R.id.nowPlayingBar)
        nowPlayingCover = findViewById(R.id.nowPlayingCover)
        nowPlayingArtist = findViewById(R.id.nowPlayingArtist)
        nowPlayingTitle = findViewById(R.id.nowPlayingTitle)
        nowPlayingRawRt = findViewById(R.id.nowPlayingRawRt)

        // PiP Layout
        pipLayout = findViewById(R.id.pipLayout)
        pipCoverImage = findViewById(R.id.pipCoverImage)
        pipTitle = findViewById(R.id.pipTitle)
        pipArtist = findViewById(R.id.pipArtist)
        pipBtnPlayPause = findViewById(R.id.pipBtnPlayPause)
        setupPipButtons()

        // PiP Raw RT display
        pipRawRt = findViewById(R.id.pipRawRt)

        // Correction Helper Buttons
        nowPlayingCorrectionButtons = findViewById(R.id.nowPlayingCorrectionButtons)
        btnCorrectionRefresh = findViewById(R.id.btnCorrectionRefresh)
        btnCorrectionTrash = findViewById(R.id.btnCorrectionTrash)
        setupCorrectionHelpers()

        setupDebugOverlayDrag()
        setupDebugBuildOverlayDrag()
        setupDebugLayoutOverlayDrag()
        setupDebugSpotifyOverlayDrag()
        setupDebugButtonsOverlayDrag()
        setupDebugChecklistDrag()
        setupDebugChecklistListeners()
        setupDebugButtonsListeners()
        restoreDebugWindowStates()
        updateDebugOverlayVisibility()

        // Setup layout change listener for PiP detection
        setupPipDetection()
    }

    private var lastKnownWidth = 0

    private fun setupPipDetection() {
        val rootView = findViewById<View>(R.id.rootLayout) ?: return
        rootView.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            val newWidth = right - left
            val oldWidth = oldRight - oldLeft
            if (newWidth != oldWidth && newWidth != lastKnownWidth) {
                lastKnownWidth = newWidth
                android.util.Log.d("fytFM", "Layout changed: newWidth=$newWidth, oldWidth=$oldWidth")
                checkForPipModeByViewSize(newWidth)
            }
        }

        // Initial check after view is laid out (wichtig für direkten Start im PiP-Fenster)
        rootView.post {
            val width = rootView.width
            if (width > 0 && width != lastKnownWidth) {
                lastKnownWidth = width
                android.util.Log.d("fytFM", "Initial PiP check: width=$width")
                checkForPipModeByViewSize(width)
            }
        }
    }

    private fun checkForPipModeByViewSize(viewWidth: Int) {
        // Use absolute width in dp - if less than 800dp, consider it PiP-like
        val density = resources.displayMetrics.density
        val widthDp = viewWidth / density

        // PiP-like if width is less than 800dp (typical split-screen/floating window)
        val isPipLike = widthDp < 800f

        android.util.Log.d("fytFM", "checkForPipModeByViewSize: viewWidth=$viewWidth, widthDp=$widthDp, density=$density, isPipLike=$isPipLike")

        if (isPipLike != isPipMode) {
            setPipLayout(isPipLike)
        }
    }

    private fun setupDebugChecklistDrag() {
        val checklist = debugChecklist ?: return
        var dX = 0f
        var dY = 0f

        checklist.setOnTouchListener { view, event ->
            // Don't intercept clicks on checkboxes
            if (event.action == MotionEvent.ACTION_DOWN) {
                val checkbox = checkRdsInfo ?: return@setOnTouchListener false
                val location = IntArray(2)
                checkbox.getLocationOnScreen(location)
                val checkboxRect = android.graphics.Rect(
                    location[0], location[1],
                    location[0] + checkbox.width,
                    location[1] + checkbox.height
                )
                val screenX = event.rawX.toInt()
                val screenY = event.rawY.toInt()
                if (checkboxRect.contains(screenX, screenY)) {
                    return@setOnTouchListener false
                }
            }

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dX = view.x - event.rawX
                    dY = view.y - event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val newX = (event.rawX + dX).coerceIn(0f, (view.parent as View).width - view.width.toFloat())
                    val newY = (event.rawY + dY).coerceIn(0f, (view.parent as View).height - view.height.toFloat())
                    view.x = newX
                    view.y = newY
                    true
                }
                MotionEvent.ACTION_UP -> {
                    saveDebugWindowPosition("checklist", view)
                    true
                }
                else -> false
            }
        }
    }

    private fun setupDebugChecklistListeners() {
        checkRdsInfo?.setOnCheckedChangeListener { _, isChecked ->
            debugOverlay?.visibility = if (isChecked) View.VISIBLE else View.GONE
            presetRepository.setDebugWindowOpen("rds", isChecked)
            if (isChecked) {
                debugOverlay?.post { restoreDebugWindowPosition("rds", debugOverlay) }
            }
        }
        checkLayoutInfo?.setOnCheckedChangeListener { _, isChecked ->
            debugLayoutOverlay?.visibility = if (isChecked) View.VISIBLE else View.GONE
            presetRepository.setDebugWindowOpen("layout", isChecked)
            if (isChecked) {
                updateLayoutDebugInfo()
                debugLayoutOverlay?.post { restoreDebugWindowPosition("layout", debugLayoutOverlay) }
            }
        }
        checkBuildInfo?.setOnCheckedChangeListener { _, isChecked ->
            debugBuildOverlay?.visibility = if (isChecked) View.VISIBLE else View.GONE
            presetRepository.setDebugWindowOpen("build", isChecked)
            if (isChecked) {
                updateBuildDebugInfo()
                debugBuildOverlay?.post { restoreDebugWindowPosition("build", debugBuildOverlay) }
            }
        }
        checkSpotifyInfo?.setOnCheckedChangeListener { _, isChecked ->
            debugSpotifyOverlay?.visibility = if (isChecked) View.VISIBLE else View.GONE
            presetRepository.setDebugWindowOpen("spotify", isChecked)
            if (isChecked) {
                debugSpotifyOverlay?.post { restoreDebugWindowPosition("spotify", debugSpotifyOverlay) }
            }
        }
        checkDebugButtons?.setOnCheckedChangeListener { _, isChecked ->
            debugButtonsOverlay?.visibility = if (isChecked) View.VISIBLE else View.GONE
            presetRepository.setDebugWindowOpen("buttons", isChecked)
            if (isChecked) {
                debugButtonsOverlay?.post { restoreDebugWindowPosition("buttons", debugButtonsOverlay) }
            }
        }
    }

    private fun restoreDebugWindowStates() {
        // Restore checkbox states (this will trigger the listeners which set visibility)
        checkRdsInfo?.isChecked = presetRepository.isDebugWindowOpen("rds", false)
        checkLayoutInfo?.isChecked = presetRepository.isDebugWindowOpen("layout", false)
        checkBuildInfo?.isChecked = presetRepository.isDebugWindowOpen("build", false)
        checkSpotifyInfo?.isChecked = presetRepository.isDebugWindowOpen("spotify", false)
        checkDebugButtons?.isChecked = presetRepository.isDebugWindowOpen("buttons", false)

        // Restore positions (post to ensure views are laid out)
        debugOverlay?.post { restoreDebugWindowPosition("rds", debugOverlay) }
        debugLayoutOverlay?.post { restoreDebugWindowPosition("layout", debugLayoutOverlay) }
        debugBuildOverlay?.post { restoreDebugWindowPosition("build", debugBuildOverlay) }
        debugSpotifyOverlay?.post { restoreDebugWindowPosition("spotify", debugSpotifyOverlay) }
        debugButtonsOverlay?.post { restoreDebugWindowPosition("buttons", debugButtonsOverlay) }
        debugChecklist?.post { restoreDebugWindowPosition("checklist", debugChecklist) }
    }

    private fun restoreDebugWindowPosition(windowId: String, view: View?) {
        view ?: return
        val x = presetRepository.getDebugWindowPositionX(windowId)
        val y = presetRepository.getDebugWindowPositionY(windowId)
        if (x >= 0 && y >= 0) {
            view.x = x
            view.y = y
        }
    }

    private fun saveDebugWindowPosition(windowId: String, view: View) {
        presetRepository.setDebugWindowPosition(windowId, view.x, view.y)
    }

    private fun updateBuildDebugInfo() {
        findViewById<TextView>(R.id.debugBuildVersion)?.text = BuildConfig.VERSION_NAME
        findViewById<TextView>(R.id.debugBuildVersionCode)?.text = BuildConfig.VERSION_CODE.toString()
        findViewById<TextView>(R.id.debugBuildDate)?.text = BuildConfig.BUILD_DATE
        findViewById<TextView>(R.id.debugBuildTime)?.text = BuildConfig.BUILD_TIME
        findViewById<TextView>(R.id.debugBuildType)?.text = BuildConfig.BUILD_TYPE
        findViewById<TextView>(R.id.debugBuildPackage)?.text = BuildConfig.APPLICATION_ID
        findViewById<TextView>(R.id.debugBuildMinSdk)?.text = "API ${applicationInfo.minSdkVersion}"
        findViewById<TextView>(R.id.debugBuildTargetSdk)?.text = "API ${applicationInfo.targetSdkVersion}"
        findViewById<TextView>(R.id.debugBuildDeviceSdk)?.text = "API ${android.os.Build.VERSION.SDK_INT} (${android.os.Build.VERSION.RELEASE})"
    }

    private fun updateLayoutDebugInfo() {
        val metrics = resources.displayMetrics
        debugScreenInfo?.text = "Screen: ${metrics.widthPixels}x${metrics.heightPixels}"
        debugDensityInfo?.text = "Density: ${metrics.density} (${metrics.densityDpi}dpi)"
    }

    private fun setupDebugOverlayDrag() {
        val overlay = debugOverlay ?: return
        var dX = 0f
        var dY = 0f

        overlay.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dX = view.x - event.rawX
                    dY = view.y - event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val newX = (event.rawX + dX).coerceIn(0f, (view.parent as View).width - view.width.toFloat())
                    val newY = (event.rawY + dY).coerceIn(0f, (view.parent as View).height - view.height.toFloat())
                    view.x = newX
                    view.y = newY
                    true
                }
                MotionEvent.ACTION_UP -> {
                    saveDebugWindowPosition("rds", view)
                    true
                }
                else -> false
            }
        }
    }

    private fun setupDebugBuildOverlayDrag() {
        val overlay = debugBuildOverlay ?: return
        var dX = 0f
        var dY = 0f

        overlay.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dX = view.x - event.rawX
                    dY = view.y - event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val newX = (event.rawX + dX).coerceIn(0f, (view.parent as View).width - view.width.toFloat())
                    val newY = (event.rawY + dY).coerceIn(0f, (view.parent as View).height - view.height.toFloat())
                    view.x = newX
                    view.y = newY
                    true
                }
                MotionEvent.ACTION_UP -> {
                    saveDebugWindowPosition("build", view)
                    true
                }
                else -> false
            }
        }
    }

    private fun setupDebugLayoutOverlayDrag() {
        val overlay = debugLayoutOverlay ?: return
        var dX = 0f
        var dY = 0f

        overlay.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dX = view.x - event.rawX
                    dY = view.y - event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val newX = (event.rawX + dX).coerceIn(0f, (view.parent as View).width - view.width.toFloat())
                    val newY = (event.rawY + dY).coerceIn(0f, (view.parent as View).height - view.height.toFloat())
                    view.x = newX
                    view.y = newY
                    true
                }
                MotionEvent.ACTION_UP -> {
                    saveDebugWindowPosition("layout", view)
                    true
                }
                else -> false
            }
        }
    }

    private fun setupDebugSpotifyOverlayDrag() {
        val overlay = debugSpotifyOverlay ?: return
        var dX = 0f
        var dY = 0f

        overlay.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dX = view.x - event.rawX
                    dY = view.y - event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val newX = (event.rawX + dX).coerceIn(0f, (view.parent as View).width - view.width.toFloat())
                    val newY = (event.rawY + dY).coerceIn(0f, (view.parent as View).height - view.height.toFloat())
                    view.x = newX
                    view.y = newY
                    true
                }
                MotionEvent.ACTION_UP -> {
                    saveDebugWindowPosition("spotify", view)
                    true
                }
                else -> false
            }
        }
    }

    private fun setupDebugButtonsOverlayDrag() {
        val overlay = debugButtonsOverlay ?: return
        var dX = 0f
        var dY = 0f

        overlay.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dX = view.x - event.rawX
                    dY = view.y - event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val newX = (event.rawX + dX).coerceIn(0f, (view.parent as View).width - view.width.toFloat())
                    val newY = (event.rawY + dY).coerceIn(0f, (view.parent as View).height - view.height.toFloat())
                    view.x = newX
                    view.y = newY
                    true
                }
                MotionEvent.ACTION_UP -> {
                    saveDebugWindowPosition("buttons", view)
                    true
                }
                else -> false
            }
        }
    }

    private fun setupDebugButtonsListeners() {
        findViewById<android.widget.Button>(R.id.btnDebugKillInternet)?.setOnClickListener {
            debugInternetDisabled = !debugInternetDisabled
            // SpotifyClient Flag synchronisieren
            SpotifyClient.debugInternetDisabled = debugInternetDisabled
            val btn = it as android.widget.Button
            if (debugInternetDisabled) {
                btn.text = "App Internet: OFF"
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF666666.toInt())
                android.widget.Toast.makeText(this, "App Internet disabled (debug)", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                btn.text = "Kill App Internet Connection"
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFCC3333.toInt())
                android.widget.Toast.makeText(this, "App Internet enabled", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        // Bug Report Button
        findViewById<android.widget.Button>(R.id.btnDebugBugReport)?.setOnClickListener {
            createBugReport()
        }

        // View Reports Button
        findViewById<android.widget.Button>(R.id.btnDebugViewReports)?.setOnClickListener {
            startActivity(android.content.Intent(this, BugReportActivity::class.java))
        }

        // Block Spotify/Local Toggle Button
        findViewById<android.widget.ToggleButton>(R.id.btnDebugBlockSpotify)?.setOnCheckedChangeListener { btn, isChecked ->
            debugSpotifyBlocked = isChecked
            if (isChecked) {
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF993333.toInt())
                android.widget.Toast.makeText(this, "Spotify/Local blocked - RDS only", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFCC9933.toInt())
                android.widget.Toast.makeText(this, "Spotify/Local enabled", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupPipButtons() {
        findViewById<ImageButton>(R.id.pipBtnPrev)?.setOnClickListener {
            // Same as btnPrevStation - step down frequency
            val step = if (frequencyScale.getMode() == FrequencyScaleView.RadioMode.FM) {
                FrequencyScaleView.FM_FREQUENCY_STEP
            } else {
                FrequencyScaleView.AM_FREQUENCY_STEP
            }
            val newFreq = frequencyScale.getFrequency() - step
            frequencyScale.setFrequency(newFreq)
        }
        findViewById<ImageButton>(R.id.pipBtnNext)?.setOnClickListener {
            // Same as btnNextStation - step up frequency
            val step = if (frequencyScale.getMode() == FrequencyScaleView.RadioMode.FM) {
                FrequencyScaleView.FM_FREQUENCY_STEP
            } else {
                FrequencyScaleView.AM_FREQUENCY_STEP
            }
            val newFreq = frequencyScale.getFrequency() + step
            frequencyScale.setFrequency(newFreq)
        }
        findViewById<ImageButton>(R.id.pipBtnPlayPause)?.setOnClickListener {
            btnPlayPause.performClick()
        }
    }

    private fun updatePipDisplay() {
        if (!isPipMode) return

        // Update frequency/station name
        val ps = rdsManager.ps
        val frequency = frequencyScale.getFrequency()
        pipTitle?.text = if (!ps.isNullOrBlank()) ps else "FM ${String.format("%.2f", frequency)}"

        // Always show raw RT from RDS
        val rawRt = rdsManager.rt
        pipRawRt?.text = rawRt ?: ""

        // Update artist (from Spotify/Local track info)
        val trackInfo = currentSpotifyTrackInfo
        if (trackInfo != null) {
            pipArtist?.text = "${trackInfo.artist} - ${trackInfo.title}"
            // Load cover image
            val coverUrl = trackInfo.coverUrl ?: trackInfo.coverUrlMedium
            if (coverUrl != null) {
                pipCoverImage?.load(coverUrl) {
                    crossfade(true)
                    placeholder(R.drawable.ic_cover_placeholder)
                    error(R.drawable.ic_cover_placeholder)
                }
            } else {
                pipCoverImage?.setImageResource(R.drawable.ic_cover_placeholder)
            }
        } else {
            pipArtist?.text = ""
            pipCoverImage?.setImageResource(R.drawable.ic_cover_placeholder)
        }
    }

    private fun createBugReport() {
        val bugReportHelper = BugReportHelper(this)
        val appState = BugReportHelper.AppState(
            // RDS Data
            rdsPs = rdsManager.ps,
            rdsRt = rdsManager.rt,
            rdsPi = rdsManager.pi,
            rdsPty = rdsManager.pty,
            rdsRssi = rdsManager.rssi,
            rdsTp = rdsManager.tp,
            rdsTa = rdsManager.ta,
            rdsAfEnabled = rdsManager.isAfEnabled,
            rdsAfList = rdsManager.afList?.toList(),
            currentFrequency = frequencyScale.getFrequency(),

            // Spotify Data
            spotifyStatus = currentSpotifyStatus,
            spotifyOriginalRt = currentSpotifyOriginalRt,
            spotifyStrippedRt = currentSpotifyStrippedRt,
            spotifyQuery = currentSpotifyQuery,
            spotifyTrackInfo = currentSpotifyTrackInfo
        )

        val reportPath = bugReportHelper.createBugReport(appState)
        if (reportPath != null) {
            android.widget.Toast.makeText(this, "Bug report created", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            android.widget.Toast.makeText(this, "Failed to create bug report", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // Methode zum Aktualisieren der Spotify Debug-Anzeige
    fun updateSpotifyDebugInfo(status: String, originalRt: String?, strippedRt: String?, query: String?, trackInfo: TrackInfo?) {
        if (debugSpotifyOverlay?.visibility != View.VISIBLE) return

        // Status und Input immer setzen
        findViewById<TextView>(R.id.debugSpotifyStatus)?.text = status
        // Show original RT and search string (always both)
        val orig = originalRt ?: "--"
        val search = strippedRt ?: "--"
        val rtDisplay = "Orig: $orig\nSuche: $search"
        findViewById<TextView>(R.id.debugSpotifyRtInput)?.text = rtDisplay

        // Bei "Waiting..." explizit alles clearen (Senderwechsel)
        if (status == "Waiting...") {
            lastDebugTrackId = null
            clearSpotifyDebugFields()
            return
        }

        // Ignoriere null trackInfo (während "Processing...", "Searching..." etc.)
        // Behalte den letzten angezeigten Track
        if (trackInfo == null) {
            return
        }

        // Determine source based on status (immer aktualisieren!)
        val isFromLocalCache = status.contains("Cached", ignoreCase = true) ||
                               status.contains("offline", ignoreCase = true)
        val isFromSpotifyOnline = status == "Found!"

        val sourceText = when {
            isFromLocalCache -> "LOKAL"
            isFromSpotifyOnline -> "SPOTIFY"
            else -> "..."
        }
        val sourceColor = when {
            isFromLocalCache -> android.graphics.Color.parseColor("#FFAA00")  // Orange
            isFromSpotifyOnline -> android.graphics.Color.parseColor("#1DB954")  // Spotify Green
            else -> android.graphics.Color.parseColor("#AAAAAA")
        }
        findViewById<TextView>(R.id.debugSpotifySource)?.text = sourceText
        findViewById<TextView>(R.id.debugSpotifySource)?.setTextColor(sourceColor)

        // Prüfen ob sich der Track geändert hat
        val newTrackId = trackInfo.trackId
        if (newTrackId == lastDebugTrackId) {
            return // Gleicher Track, keine Aktualisierung der Track-Details nötig
        }
        lastDebugTrackId = newTrackId

        if (trackInfo != null) {

            // Load cover image
            loadCoverImage(trackInfo.coverUrl ?: trackInfo.coverUrlMedium)

            // TRACK Section
            findViewById<TextView>(R.id.debugSpotifyArtist)?.text = trackInfo.artist
            findViewById<TextView>(R.id.debugSpotifyTitle)?.text = trackInfo.title
            findViewById<TextView>(R.id.debugSpotifyAllArtists)?.text =
                if (trackInfo.allArtists.isNotEmpty()) trackInfo.allArtists.joinToString(", ") else "--"
            findViewById<TextView>(R.id.debugSpotifyDuration)?.text = formatDuration(trackInfo.durationMs)
            findViewById<TextView>(R.id.debugSpotifyPopularity)?.text = "${trackInfo.popularity}/100"
            findViewById<TextView>(R.id.debugSpotifyExplicit)?.text = if (trackInfo.explicit) "Yes" else "No"
            findViewById<TextView>(R.id.debugSpotifyTrackDisc)?.text = "${trackInfo.trackNumber}/${trackInfo.discNumber}"
            findViewById<TextView>(R.id.debugSpotifyISRC)?.text = trackInfo.isrc ?: "--"

            // ALBUM Section
            findViewById<TextView>(R.id.debugSpotifyAlbum)?.text = trackInfo.album ?: "--"
            findViewById<TextView>(R.id.debugSpotifyAlbumType)?.text = trackInfo.albumType ?: "--"
            findViewById<TextView>(R.id.debugSpotifyTotalTracks)?.text =
                if (trackInfo.totalTracks > 0) trackInfo.totalTracks.toString() else "--"
            findViewById<TextView>(R.id.debugSpotifyReleaseDate)?.text = trackInfo.releaseDate ?: "--"

            // IDs & URLs Section
            findViewById<TextView>(R.id.debugSpotifyTrackId)?.text = trackInfo.trackId ?: "--"
            findViewById<TextView>(R.id.debugSpotifyAlbumId)?.text = trackInfo.albumId ?: "--"
            findViewById<TextView>(R.id.debugSpotifyUrl)?.text = trackInfo.spotifyUrl ?: "--"
            findViewById<TextView>(R.id.debugSpotifyAlbumUrl)?.text = trackInfo.albumUrl ?: "--"
            findViewById<TextView>(R.id.debugSpotifyPreviewUrl)?.text = trackInfo.previewUrl ?: "--"
            findViewById<TextView>(R.id.debugSpotifyCoverUrl)?.text = trackInfo.coverUrl ?: trackInfo.coverUrlMedium ?: "--"
        }
    }

    private fun loadCoverImage(coverPath: String?) {
        val coverImageView = findViewById<android.widget.ImageView>(R.id.debugSpotifyCoverImage) ?: return

        if (coverPath != null && coverPath.startsWith("/")) {
            // Local file path
            val file = java.io.File(coverPath)
            if (file.exists()) {
                val bitmap = android.graphics.BitmapFactory.decodeFile(coverPath)
                coverImageView.setImageBitmap(bitmap)
            } else {
                coverImageView.setImageResource(android.R.drawable.ic_menu_gallery)
            }
        } else if (!coverPath.isNullOrBlank()) {
            // URL - load in background
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    val url = java.net.URL(coverPath)
                    val bitmap = android.graphics.BitmapFactory.decodeStream(url.openStream())
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        coverImageView.setImageBitmap(bitmap)
                    }
                } catch (e: Exception) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        coverImageView.setImageResource(android.R.drawable.ic_menu_gallery)
                    }
                }
            }
        } else {
            coverImageView.setImageResource(android.R.drawable.ic_menu_gallery)
        }
    }

    private fun clearSpotifyDebugFields() {
        findViewById<TextView>(R.id.debugSpotifySource)?.text = "--"
        findViewById<TextView>(R.id.debugSpotifySource)?.setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
        findViewById<android.widget.ImageView>(R.id.debugSpotifyCoverImage)?.setImageResource(android.R.drawable.ic_menu_gallery)

        // Track fields
        findViewById<TextView>(R.id.debugSpotifyArtist)?.text = "--"
        findViewById<TextView>(R.id.debugSpotifyTitle)?.text = "--"
        findViewById<TextView>(R.id.debugSpotifyAllArtists)?.text = "--"
        findViewById<TextView>(R.id.debugSpotifyDuration)?.text = "--"
        findViewById<TextView>(R.id.debugSpotifyPopularity)?.text = "--"
        findViewById<TextView>(R.id.debugSpotifyExplicit)?.text = "--"
        findViewById<TextView>(R.id.debugSpotifyTrackDisc)?.text = "--"
        findViewById<TextView>(R.id.debugSpotifyISRC)?.text = "--"

        // Album fields
        findViewById<TextView>(R.id.debugSpotifyAlbum)?.text = "--"
        findViewById<TextView>(R.id.debugSpotifyAlbumType)?.text = "--"
        findViewById<TextView>(R.id.debugSpotifyTotalTracks)?.text = "--"
        findViewById<TextView>(R.id.debugSpotifyReleaseDate)?.text = "--"

        // IDs & URLs
        findViewById<TextView>(R.id.debugSpotifyTrackId)?.text = "--"
        findViewById<TextView>(R.id.debugSpotifyAlbumId)?.text = "--"
        findViewById<TextView>(R.id.debugSpotifyUrl)?.text = "--"
        findViewById<TextView>(R.id.debugSpotifyAlbumUrl)?.text = "--"
        findViewById<TextView>(R.id.debugSpotifyPreviewUrl)?.text = "--"
        findViewById<TextView>(R.id.debugSpotifyCoverUrl)?.text = "--"
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0) return "--"
        val seconds = (ms / 1000) % 60
        val minutes = (ms / 1000) / 60
        return String.format("%d:%02d", minutes, seconds)
    }

    /**
     * Parst einen Raw-RT String und versucht Artist/Title zu extrahieren.
     * Erkennt Formate wie "Artist - Title", "Artist / Title", etc.
     */
    private fun parseRawRtToTrackInfo(rawRt: String): TrackInfo? {
        if (rawRt.isBlank()) return null

        // Versuche verschiedene Trennzeichen
        val separators = listOf(" - ", " – ", " — ", " / ", " | ")

        for (separator in separators) {
            if (rawRt.contains(separator)) {
                val parts = rawRt.split(separator, limit = 2)
                if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                    return TrackInfo(
                        artist = parts[0].trim(),
                        title = parts[1].trim(),
                        trackId = "raw_rt_${rawRt.hashCode()}" // Unique ID für Vergleich
                    )
                }
            }
        }

        // Kein Trennzeichen gefunden - ganzer Text als Titel
        return TrackInfo(
            artist = "",
            title = rawRt.trim(),
            trackId = "raw_rt_${rawRt.hashCode()}"
        )
    }

    /**
     * Aktualisiert die Now Playing Bar mit Track-Informationen.
     * Ignoriert null (behält letzten Track). Nur hideNowPlayingBarExplicit() versteckt die Bar.
     */
    fun updateNowPlaying(trackInfo: TrackInfo?) {
        val bar = nowPlayingBar ?: return

        // Ignoriere null - behalte letzten Track
        // (null kommt während "Processing..." oder zwischen RT-Teilen)
        if (trackInfo == null) {
            return
        }

        // Prüfen ob sich der Track geändert hat
        val newTrackId = trackInfo.trackId
        if (newTrackId == lastDisplayedTrackId) {
            return // Gleicher Track, keine Aktualisierung nötig
        }
        lastDisplayedTrackId = newTrackId

        if (trackInfo != null) {
            // Update text - bei leerem Artist nur Titel zeigen
            if (trackInfo.artist.isBlank()) {
                nowPlayingArtist?.visibility = View.GONE
                nowPlayingTitle?.text = trackInfo.title
            } else {
                nowPlayingArtist?.visibility = View.VISIBLE
                nowPlayingArtist?.text = trackInfo.artist
                nowPlayingTitle?.text = trackInfo.title
            }

            // Load cover image with Coil
            val coverUrl = trackInfo.coverUrl ?: trackInfo.coverUrlMedium
            if (!coverUrl.isNullOrBlank()) {
                if (coverUrl.startsWith("/")) {
                    // Local file
                    nowPlayingCover?.load(java.io.File(coverUrl)) {
                        crossfade(true)
                        placeholder(android.R.drawable.ic_menu_gallery)
                        error(android.R.drawable.ic_menu_gallery)
                    }
                } else {
                    // URL
                    nowPlayingCover?.load(coverUrl) {
                        crossfade(true)
                        placeholder(android.R.drawable.ic_menu_gallery)
                        error(android.R.drawable.ic_menu_gallery)
                    }
                }
            } else {
                // No Spotify cover - try station logo from template
                val stationLogo = radioLogoRepository.getLogoForStation(
                    ps = rdsManager.ps,
                    pi = rdsManager.pi,
                    frequency = frequencyScale.getFrequency()
                )
                if (stationLogo != null) {
                    nowPlayingCover?.load(java.io.File(stationLogo)) {
                        crossfade(true)
                        placeholder(android.R.drawable.ic_menu_gallery)
                        error(android.R.drawable.ic_menu_gallery)
                    }
                } else {
                    nowPlayingCover?.setImageResource(android.R.drawable.ic_menu_gallery)
                }
            }

            // Show with animation
            if (bar.visibility != View.VISIBLE) {
                showNowPlayingBar(bar)
            }
        }
    }

    /**
     * Versteckt die Now Playing Bar explizit (z.B. bei Senderwechsel)
     */
    fun hideNowPlayingBarExplicit() {
        val bar = nowPlayingBar ?: return
        lastDisplayedTrackId = null
        if (bar.visibility == View.VISIBLE) {
            hideNowPlayingBar(bar)
        }
    }

    private fun showNowPlayingBar(bar: View) {
        val animationType = presetRepository.getNowPlayingAnimation()
        when (animationType) {
            0 -> { // None
                bar.visibility = View.VISIBLE
            }
            1 -> { // Slide
                bar.visibility = View.VISIBLE
                bar.translationY = bar.height.toFloat()
                bar.alpha = 0f
                bar.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(300)
                    .start()
            }
            2 -> { // Fade
                bar.visibility = View.VISIBLE
                bar.alpha = 0f
                bar.animate()
                    .alpha(1f)
                    .setDuration(300)
                    .start()
            }
        }
    }

    private fun hideNowPlayingBar(bar: View) {
        val animationType = presetRepository.getNowPlayingAnimation()
        when (animationType) {
            0 -> { // None
                bar.visibility = View.GONE
            }
            1 -> { // Slide
                bar.animate()
                    .translationY(bar.height.toFloat())
                    .alpha(0f)
                    .setDuration(300)
                    .withEndAction { bar.visibility = View.GONE }
                    .start()
            }
            2 -> { // Fade
                bar.animate()
                    .alpha(0f)
                    .setDuration(300)
                    .withEndAction { bar.visibility = View.GONE }
                    .start()
            }
        }
    }

    private fun setupCorrectionHelpers() {
        // Show/hide correction buttons based on setting
        updateCorrectionHelpersVisibility()

        // Trash button - ignore this RT completely
        btnCorrectionTrash?.setOnClickListener {
            val currentRt = rtCombiner?.getCurrentRt() ?: return@setOnClickListener
            val dao = rtCorrectionDao ?: return@setOnClickListener

            CoroutineScope(Dispatchers.IO).launch {
                val correction = RtCorrection(
                    rtNormalized = RtCorrection.normalizeRt(currentRt),
                    rtOriginal = currentRt,
                    type = RtCorrection.TYPE_IGNORED
                )
                dao.insert(correction)
                android.util.Log.i("fytFM", "RT ignored: $currentRt")

                withContext(Dispatchers.Main) {
                    // Hide the Now Playing bar
                    hideNowPlayingBarExplicit()
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        "RT wird ignoriert",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        // Refresh button - skip this track and search for another
        btnCorrectionRefresh?.setOnClickListener {
            val currentRt = rtCombiner?.getCurrentRt() ?: return@setOnClickListener
            val currentTrack = rtCombiner?.getCurrentTrackInfo() ?: return@setOnClickListener
            val dao = rtCorrectionDao ?: return@setOnClickListener

            CoroutineScope(Dispatchers.IO).launch {
                val correction = RtCorrection(
                    rtNormalized = RtCorrection.normalizeRt(currentRt),
                    rtOriginal = currentRt,
                    type = RtCorrection.TYPE_SKIP_TRACK,
                    skipTrackId = currentTrack.trackId,
                    skipTrackArtist = currentTrack.artist,
                    skipTrackTitle = currentTrack.title
                )
                dao.insert(correction)
                android.util.Log.i("fytFM", "Track skipped: ${currentTrack.artist} - ${currentTrack.title} for RT: $currentRt")

                withContext(Dispatchers.Main) {
                    // Force re-process to find a different track
                    rtCombiner?.forceReprocess()
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        "Suche anderen Track...",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /**
     * Update visibility of correction helper buttons based on setting
     */
    fun updateCorrectionHelpersVisibility() {
        val enabled = presetRepository.isCorrectionHelpersEnabled()
        nowPlayingCorrectionButtons?.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    private fun updateDebugOverlayVisibility() {
        val showDebug = presetRepository.isShowDebugInfos()
        debugChecklist?.visibility = if (showDebug) View.VISIBLE else View.GONE
        // RDS overlay visibility depends on both the setting AND the checkbox
        debugOverlay?.visibility = if (showDebug && checkRdsInfo?.isChecked == true) View.VISIBLE else View.GONE
    }

    fun updateDebugInfo(ps: String? = null, pi: String? = null, pty: String? = null,
                        rt: String? = null, rssiStr: String? = null, freq: Float? = null,
                        af: String? = null, tpTa: String? = null, afUsing: String? = null) {
        if (debugOverlay?.visibility != View.VISIBLE) return

        ps?.let { debugPs?.text = it.ifEmpty { "--------" } }
        pi?.let { debugPi?.text = if (it.isNotEmpty()) it else "----" }
        pty?.let { debugPty?.text = if (it.isNotEmpty()) it else "--" }
        rt?.let { debugRt?.text = it.ifEmpty { "--------------------------------" } }
        rssiStr?.let { debugRssi?.text = it }
        freq?.let { debugFreq?.text = String.format("%.1f MHz", it) }
        af?.let { debugAf?.text = it.ifEmpty { "----" } }
        afUsing?.let { debugAfUsing?.text = it }
        tpTa?.let { debugTpTa?.text = it }
    }

    private fun setupStationList() {
        stationAdapter = StationAdapter(
            onStationClick = { station ->
                // Tune to selected station
                if (station.isAM) {
                    frequencyScale.setMode(FrequencyScaleView.RadioMode.AM)
                } else {
                    frequencyScale.setMode(FrequencyScaleView.RadioMode.FM)
                }
                frequencyScale.setFrequency(station.frequency)
                stationAdapter.setSelectedFrequency(station.frequency)
            },
            getLogoPath = { ps, pi, frequency ->
                if (presetRepository.isShowLogosInFavorites()) {
                    radioLogoRepository.getLogoForStation(ps, pi, frequency)
                } else {
                    null
                }
            }
        )

        stationRecycler.layoutManager = LinearLayoutManager(
            this, LinearLayoutManager.HORIZONTAL, false
        )
        stationRecycler.adapter = stationAdapter
    }

    private fun setupListeners() {
        frequencyScale.setOnFrequencyChangeListener { frequency ->
            updateFrequencyDisplay(frequency)
            stationAdapter.setSelectedFrequency(frequency)
            // Clear RDS data on frequency change for fresh data
            rdsManager.clearRds()
            rtCombiner?.clearAll()
            // Reset Spotify debug overlay and Now Playing Bar
            updateSpotifyDebugInfo("Waiting...", null, null, null, null)
            hideNowPlayingBarExplicit()
            // Log station change
            val isAM = frequencyScale.getMode() == FrequencyScaleView.RadioMode.AM
            rdsLogRepository.onStationChange(frequency, isAM)
            // Update MediaService with new frequency and radio logo immediately
            val radioLogoPath = radioLogoRepository.getLogoForStation(null, null, frequency)
            android.util.Log.d("fytFM", "Station change: freq=$frequency, radioLogoPath=$radioLogoPath")
            FytFMMediaService.instance?.updateMetadata(
                frequency = frequency,
                ps = null,
                rt = null,
                isAM = isAM,
                coverUrl = null,
                localCoverPath = null,
                radioLogoPath = radioLogoPath
            )
            // Actually tune the radio hardware!
            tuneToFrequency(frequency)
            // Save frequency to SharedPreferences
            saveLastFrequency(frequency)
            // Update favorite button
            updateFavoriteButton()
        }

        frequencyScale.setOnModeChangeListener { mode ->
            updateModeButton()
            loadFavoritesFilterState()  // Filter-Status für neuen Modus laden
            loadStationsForCurrentMode()
            updateFavoriteButton()
        }

        // Explizit longClickable aktivieren
        btnPrevStation.isLongClickable = true
        btnNextStation.isLongClickable = true

        btnPrevStation.setOnClickListener {
            val step = if (frequencyScale.getMode() == FrequencyScaleView.RadioMode.FM) {
                FrequencyScaleView.FM_FREQUENCY_STEP
            } else {
                FrequencyScaleView.AM_FREQUENCY_STEP
            }
            val newFreq = frequencyScale.getFrequency() - step
            frequencyScale.setFrequency(newFreq)
        }

        // Long-Press: Seek zum vorherigen Sender mit Signal
        btnPrevStation.setOnLongClickListener {
            android.util.Log.i("fytFM", "Long-Press PREV detected!")
            android.widget.Toast.makeText(this, "Seek ◀ gestartet...", android.widget.Toast.LENGTH_SHORT).show()
            if (isRadioOn && frequencyScale.getMode() == FrequencyScaleView.RadioMode.FM) {
                seekToStation(false)
            }
            true
        }

        btnNextStation.setOnClickListener {
            val step = if (frequencyScale.getMode() == FrequencyScaleView.RadioMode.FM) {
                FrequencyScaleView.FM_FREQUENCY_STEP
            } else {
                FrequencyScaleView.AM_FREQUENCY_STEP
            }
            val newFreq = frequencyScale.getFrequency() + step
            frequencyScale.setFrequency(newFreq)
        }

        // Long-Press: Seek zum nächsten Sender mit Signal
        btnNextStation.setOnLongClickListener {
            android.util.Log.i("fytFM", "Long-Press NEXT detected!")
            android.widget.Toast.makeText(this, "Seek ▶ gestartet...", android.widget.Toast.LENGTH_SHORT).show()
            if (isRadioOn && frequencyScale.getMode() == FrequencyScaleView.RadioMode.FM) {
                seekToStation(true)
            }
            true
        }

        btnFavorite.setOnClickListener {
            toggleCurrentStationFavorite()
        }

        android.util.Log.i("fytFM", "Setting up btnPlayPause click listener")
        btnPlayPause.setOnClickListener {
            android.util.Log.i("fytFM", "PlayPause button clicked! isPlaying=$isPlaying isRadioOn=$isRadioOn")
            isPlaying = !isPlaying
            // Mute/Unmute the radio
            if (isRadioOn) {
                val shouldMute = !isPlaying

                // Method 1: sys.radio.mute system property (works as system app)
                try {
                    val systemPropertiesClass = Class.forName("android.os.SystemProperties")
                    val setMethod = systemPropertiesClass.getMethod("set", String::class.java, String::class.java)
                    setMethod.invoke(null, "sys.radio.mute", if (shouldMute) "1" else "0")
                    android.util.Log.i("fytFM", "sys.radio.mute = ${if (shouldMute) "1" else "0"}")
                } catch (e: Exception) {
                    android.util.Log.e("fytFM", "Failed to set sys.radio.mute: ${e.message}")
                }

                // Method 2: Direct SyuJniNative (libsyu_jni.so)
                if (SyuJniNative.isLibraryLoaded()) {
                    val result = SyuJniNative.getInstance().muteAmp(shouldMute)
                    android.util.Log.i("fytFM", "SyuJniNative.muteAmp($shouldMute) = $result")
                } else {
                    android.util.Log.w("fytFM", "libsyu_jni.so not loaded")
                }

                // Method 3: FmNative setMute
                val fmResult = fmNative.setMute(shouldMute)
                android.util.Log.i("fytFM", "FmNative.setMute($shouldMute) = $fmResult")

                // Update MediaService playback state
                FytFMMediaService.instance?.updatePlaybackState(isPlaying)
            }
            updatePlayPauseButton()
        }

        // FM/AM toggle button
        btnFM.setOnClickListener {
            frequencyScale.toggleMode()
        }

        // Power button - toggle radio on/off
        btnPower.setOnClickListener {
            toggleRadioPower()
        }

        // Search button - öffnet Sendersuche Dialog
        findViewById<ImageButton>(R.id.btnSearch).setOnClickListener {
            showStationScanDialog()
        }

        // All list button
        tvAllList.setOnClickListener {
            // TODO: Show full station list dialog
        }

        // Other bottom control buttons
        findViewById<ImageButton>(R.id.btnSkipPrev).setOnClickListener {
            skipToPreviousStation()
        }
        findViewById<ImageButton>(R.id.btnSkipNext).setOnClickListener {
            skipToNextStation()
        }
        findViewById<ImageButton>(R.id.btnFolder).setOnClickListener {
            toggleFavoritesFilter()
        }
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            showSettingsDialog()
        }
        findViewById<ImageButton>(R.id.btnArchive).setOnClickListener {
            showArchiveOverlay()
        }
        findViewById<ImageButton>(R.id.btnArchiveBack).setOnClickListener {
            hideArchiveOverlay()
        }

        // Long press on FM button for debug tune
        btnFM.setOnLongClickListener {
            debugTune()
            true
        }
    }

    /**
     * Startet den MediaService für Car Launcher Integration
     */
    private fun startMediaService() {
        val intent = Intent(this, FytFMMediaService::class.java)
        startService(intent)  // Normal service, Media3 handles foreground internally

        // Setup callbacks für Service-Steuerung (wenn Service bereit)
        android.os.Handler(mainLooper).postDelayed({
            FytFMMediaService.instance?.let { service ->
                service.onPlayCallback = {
                    runOnUiThread {
                        if (!isPlaying) {
                            isPlaying = true
                            if (isRadioOn) {
                                // Gleiche Logik wie btnPlayPause - Unmute
                                try {
                                    val systemPropertiesClass = Class.forName("android.os.SystemProperties")
                                    val setMethod = systemPropertiesClass.getMethod("set", String::class.java, String::class.java)
                                    setMethod.invoke(null, "sys.radio.mute", "0")
                                } catch (e: Exception) { }
                                if (SyuJniNative.isLibraryLoaded()) {
                                    SyuJniNative.getInstance().muteAmp(false)
                                }
                                fmNative.setMute(false)
                            }
                            updatePlayPauseButton()
                        }
                    }
                }
                service.onPauseCallback = {
                    runOnUiThread {
                        if (isPlaying) {
                            isPlaying = false
                            if (isRadioOn) {
                                // Gleiche Logik wie btnPlayPause - Mute
                                try {
                                    val systemPropertiesClass = Class.forName("android.os.SystemProperties")
                                    val setMethod = systemPropertiesClass.getMethod("set", String::class.java, String::class.java)
                                    setMethod.invoke(null, "sys.radio.mute", "1")
                                } catch (e: Exception) { }
                                if (SyuJniNative.isLibraryLoaded()) {
                                    SyuJniNative.getInstance().muteAmp(true)
                                }
                                fmNative.setMute(true)
                            }
                            updatePlayPauseButton()
                        }
                    }
                }
                service.onSkipNextCallback = {
                    runOnUiThread { skipToNextStation() }
                }
                service.onSkipPrevCallback = {
                    runOnUiThread { skipToPreviousStation() }
                }
                service.onTuneCallback = { frequency ->
                    runOnUiThread {
                        frequencyScale.setFrequency(frequency)
                    }
                }
                android.util.Log.i("fytFM", "MediaService callbacks registered")
            }
        }, 500)
    }

    /**
     * Debug-Funktion: Tune zu 90.4 MHz und zeige RDS-Daten
     */
    private fun debugTune() {
        val targetFreq = 90.4f
        android.util.Log.i("fytFM", "=== DEBUG: Tuning to $targetFreq FM ===")

        // Stelle sicher dass Radio eingeschaltet ist
        if (!isRadioOn) {
            toggleRadioPower()
        }

        // Tune
        frequencyScale.setFrequency(targetFreq)

        android.util.Log.i("fytFM", "=== DEBUG COMPLETE ===")
    }

    /**
     * Zeigt das Archiv-Overlay an
     */
    private fun showArchiveOverlay() {
        findViewById<View>(R.id.archiveOverlay).visibility = View.VISIBLE

        // Initialize adapter if needed
        if (!::archiveAdapter.isInitialized) {
            archiveAdapter = RdsLogAdapter()
            val recycler = findViewById<RecyclerView>(R.id.archiveRecycler)
            recycler.layoutManager = LinearLayoutManager(this)
            recycler.adapter = archiveAdapter
        }

        // Setup archive UI
        setupArchiveUI()

        // Start collecting data
        loadArchiveData()
    }

    /**
     * Versteckt das Archiv-Overlay
     */
    private fun hideArchiveOverlay() {
        findViewById<View>(R.id.archiveOverlay).visibility = View.GONE
        archiveJob?.cancel()
        archiveJob = null
    }

    private fun setupArchiveUI() {
        // Search toggle
        val searchContainer = findViewById<View>(R.id.archiveSearchContainer)
        val etSearch = findViewById<android.widget.EditText>(R.id.etArchiveSearch)

        findViewById<ImageButton>(R.id.btnArchiveSearch).setOnClickListener {
            if (searchContainer.visibility == View.VISIBLE) {
                searchContainer.visibility = View.GONE
                archiveSearchQuery = ""
                loadArchiveData()
            } else {
                searchContainer.visibility = View.VISIBLE
                etSearch.requestFocus()
            }
        }

        // Search text change listener
        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                archiveSearchQuery = s?.toString() ?: ""
                loadArchiveData()
            }
        })

        // Clear button
        findViewById<ImageButton>(R.id.btnArchiveClear).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.clear_archive_title)
                .setMessage(R.string.clear_archive_message)
                .setPositiveButton(R.string.delete) { _, _ ->
                    rdsLogRepository.clearAll()
                    android.widget.Toast.makeText(this, R.string.archive_cleared, android.widget.Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        // "All" chip click
        findViewById<TextView>(R.id.chipAllFrequencies).setOnClickListener {
            archiveFilterFrequency = null
            updateFilterChipSelection()
            loadArchiveData()
        }
    }

    private fun loadArchiveData() {
        archiveJob?.cancel()

        val flow = when {
            archiveSearchQuery.isNotBlank() -> rdsLogRepository.searchRt(archiveSearchQuery)
            archiveFilterFrequency != null -> rdsLogRepository.getEntriesForFrequency(archiveFilterFrequency!!)
            else -> rdsLogRepository.getAllEntries()
        }

        archiveJob = CoroutineScope(Dispatchers.Main).launch {
            flow.collectLatest { entries ->
                archiveAdapter.setEntries(entries)

                // Update stats
                findViewById<TextView>(R.id.tvArchiveStats).text = getString(R.string.entries_format, entries.size)

                // Toggle empty state
                val recycler = findViewById<RecyclerView>(R.id.archiveRecycler)
                val emptyState = findViewById<View>(R.id.archiveEmptyState)
                if (entries.isEmpty()) {
                    recycler.visibility = View.GONE
                    emptyState.visibility = View.VISIBLE
                } else {
                    recycler.visibility = View.VISIBLE
                    emptyState.visibility = View.GONE
                }
            }
        }
    }

    private fun updateFilterChipSelection() {
        val chipAll = findViewById<TextView>(R.id.chipAllFrequencies)

        if (archiveFilterFrequency == null) {
            chipAll.setBackgroundResource(R.drawable.chip_selected)
            chipAll.setTextColor(resources.getColor(android.R.color.white, null))
        } else {
            chipAll.setBackgroundResource(R.drawable.chip_unselected)
            chipAll.setTextColor(resources.getColor(android.R.color.black, null))
        }
    }

    private fun showSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)

        val dialog = AlertDialog.Builder(this, R.style.TransparentDialog)
            .setView(dialogView)
            .create()

        // Radio Editor click
        dialogView.findViewById<View>(R.id.itemRadioEditor).setOnClickListener {
            dialog.dismiss()
            showRadioEditorDialog()
        }

        // Power on startup toggle
        val switchPower = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchPowerOnStartup)
        switchPower.isChecked = presetRepository.isPowerOnStartup()
        switchPower.setOnCheckedChangeListener { _, isChecked ->
            presetRepository.setPowerOnStartup(isChecked)
        }
        dialogView.findViewById<ImageButton>(R.id.btnPowerOnStartupInfo).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.power_on_startup)
                .setMessage(R.string.power_on_startup_info)
                .setPositiveButton(R.string.confirm, null)
                .show()
        }

        // Show debug infos toggle
        val switchDebug = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchShowDebug)
        switchDebug.isChecked = presetRepository.isShowDebugInfos()
        switchDebug.setOnCheckedChangeListener { _, isChecked ->
            presetRepository.setShowDebugInfos(isChecked)
            updateDebugOverlayVisibility()
        }
        dialogView.findViewById<ImageButton>(R.id.btnShowDebugInfo).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.show_debug_infos)
                .setMessage(R.string.show_debug_infos_info)
                .setPositiveButton(R.string.confirm, null)
                .show()
        }

        // LOC Local Mode toggle
        val switchLocalMode = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchLocalMode)
        switchLocalMode.isChecked = presetRepository.isLocalMode()
        switchLocalMode.setOnCheckedChangeListener { _, isChecked ->
            presetRepository.setLocalMode(isChecked)
            fmNative?.setLocalMode(isChecked)
        }
        dialogView.findViewById<ImageButton>(R.id.btnLocalModeInfo).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.loc_local_only)
                .setMessage(R.string.loc_local_only_info)
                .setPositiveButton(R.string.confirm, null)
                .show()
        }

        // Mono Mode toggle
        val switchMonoMode = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchMonoMode)
        switchMonoMode.isChecked = presetRepository.isMonoMode()
        switchMonoMode.setOnCheckedChangeListener { _, isChecked ->
            presetRepository.setMonoMode(isChecked)
            fmNative?.setMonoMode(isChecked)
        }
        dialogView.findViewById<ImageButton>(R.id.btnMonoModeInfo).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.mono_noise_reduction)
                .setMessage(R.string.mono_noise_reduction_info)
                .setPositiveButton(R.string.confirm, null)
                .show()
        }

        // Logo Template item (declare first so it can be updated by radio area change)
        val textLogoTemplateValue = dialogView.findViewById<TextView>(R.id.textLogoTemplateValue)
        fun updateLogoTemplateText() {
            val activeTemplate = radioLogoRepository.getActiveTemplateName()
            textLogoTemplateValue.text = if (activeTemplate != null) {
                val template = radioLogoRepository.getTemplates().find { it.name == activeTemplate }
                "${activeTemplate} (${template?.stations?.size ?: 0} Sender)"
            } else {
                "Kein Template"
            }
        }
        updateLogoTemplateText()
        dialogView.findViewById<View>(R.id.itemLogoTemplate).setOnClickListener {
            showLogoTemplateDialog { updateLogoTemplateText() }
        }

        // Show Logos in Favorites toggle
        val switchShowLogosInFavorites = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchShowLogosInFavorites)
        switchShowLogosInFavorites.isChecked = presetRepository.isShowLogosInFavorites()
        switchShowLogosInFavorites.setOnCheckedChangeListener { _, isChecked ->
            presetRepository.setShowLogosInFavorites(isChecked)
            // Refresh station list to apply change
            stationAdapter.notifyDataSetChanged()
        }

        // Radio Area item (opens selection dialog)
        val textRadioAreaValue = dialogView.findViewById<TextView>(R.id.textRadioAreaValue)
        fun updateRadioAreaText() {
            val areaName = getRadioAreaName(presetRepository.getRadioArea())
            val templateName = radioLogoRepository.getActiveTemplateName()
            textRadioAreaValue.text = if (templateName != null) {
                "$areaName / $templateName"
            } else {
                areaName
            }
        }
        updateRadioAreaText()
        dialogView.findViewById<View>(R.id.itemRadioArea).setOnClickListener {
            showRadioAreaDialog { selectedArea ->
                presetRepository.setRadioArea(selectedArea)
                fmNative?.setRadioArea(selectedArea)
                updateRadioAreaText()
                updateLogoTemplateText()
            }
        }

        // Auto Scan Sensitivity toggle
        val switchAutoScanSensitivity = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchAutoScanSensitivity)
        switchAutoScanSensitivity.isChecked = presetRepository.isAutoScanSensitivity()
        switchAutoScanSensitivity.setOnCheckedChangeListener { _, isChecked ->
            presetRepository.setAutoScanSensitivity(isChecked)
        }

        // Auto Scan Sensitivity info button
        dialogView.findViewById<ImageButton>(R.id.btnAutoScanSensitivityInfo).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.auto_scan_sensitivity)
                .setMessage(R.string.auto_scan_sensitivity_info)
                .setPositiveButton(R.string.confirm, null)
                .show()
        }

        // Overwrite Favorites toggle
        val switchOverwriteFavorites = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchOverwriteFavorites)
        switchOverwriteFavorites.isChecked = presetRepository.isOverwriteFavorites()
        switchOverwriteFavorites.setOnCheckedChangeListener { _, isChecked ->
            presetRepository.setOverwriteFavorites(isChecked)
        }
        dialogView.findViewById<ImageButton>(R.id.btnOverwriteFavoritesInfo).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.overwrite_favorites)
                .setMessage(R.string.overwrite_favorites_info)
                .setPositiveButton(R.string.confirm, null)
                .show()
        }

        // App Version / Update
        val itemAppVersion = dialogView.findViewById<View>(R.id.itemAppVersion)
        val textVersionValue = dialogView.findViewById<TextView>(R.id.textVersionValue)
        val progressUpdate = dialogView.findViewById<ProgressBar>(R.id.progressUpdate)
        val ivVersionChevron = dialogView.findViewById<ImageView>(R.id.ivVersionChevron)

        // Hilfsfunktion für UI-Update basierend auf State
        fun updateVersionUI(state: UpdateState) {
            when (state) {
                is UpdateState.Idle -> {
                    progressUpdate.visibility = View.GONE
                    ivVersionChevron.visibility = View.VISIBLE
                    textVersionValue.text = "v${updateRepository.getCurrentVersion()} - Tippen zum Prüfen"
                }
                is UpdateState.Checking -> {
                    progressUpdate.visibility = View.VISIBLE
                    ivVersionChevron.visibility = View.GONE
                    textVersionValue.text = "Prüfe auf Updates..."
                }
                is UpdateState.NoUpdate -> {
                    progressUpdate.visibility = View.GONE
                    ivVersionChevron.visibility = View.VISIBLE
                    textVersionValue.text = "Aktuell (v${updateRepository.getCurrentVersion()})"
                }
                is UpdateState.UpdateAvailable -> {
                    progressUpdate.visibility = View.GONE
                    ivVersionChevron.visibility = View.VISIBLE
                    textVersionValue.text = "Update verfügbar: v${state.info.latestVersion}"
                }
                is UpdateState.Downloading -> {
                    progressUpdate.visibility = View.VISIBLE
                    ivVersionChevron.visibility = View.GONE
                    textVersionValue.text = "Wird heruntergeladen..."
                }
                is UpdateState.DownloadComplete -> {
                    progressUpdate.visibility = View.GONE
                    ivVersionChevron.visibility = View.GONE
                    textVersionValue.text = "Download fertig - Tippe Benachrichtigung"
                }
                is UpdateState.Error -> {
                    progressUpdate.visibility = View.GONE
                    ivVersionChevron.visibility = View.VISIBLE
                    textVersionValue.text = "Fehler: ${state.message}"
                }
            }
        }

        // Aktuellen State anzeigen (falls schon ein Update gefunden wurde)
        updateVersionUI(updateRepository.updateState)

        // Dialog-Listener für UI-Updates setzen
        settingsUpdateListener = { state -> updateVersionUI(state) }

        itemAppVersion.setOnClickListener {
            when (val state = updateRepository.updateState) {
                is UpdateState.Idle, is UpdateState.NoUpdate, is UpdateState.Error -> {
                    updateRepository.checkForUpdates()
                }
                is UpdateState.UpdateAvailable -> {
                    // Download starten
                    AlertDialog.Builder(this)
                        .setTitle("Update verfügbar")
                        .setMessage("Version ${state.info.latestVersion} ist verfügbar.\n\nJetzt herunterladen?")
                        .setPositiveButton("Herunterladen") { _, _ ->
                            updateRepository.downloadUpdate(state.info.downloadUrl, state.info.latestVersion)
                        }
                        .setNegativeButton("Später", null)
                        .show()
                }
                is UpdateState.DownloadComplete -> {
                    // Download fertig - Benachrichtigung tippen zum Installieren
                    updateRepository.resetState()
                }
                else -> {
                    // Checking or Downloading - nichts tun
                }
            }
        }

        // RDS Logging toggle
        val switchRdsLogging = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchRdsLogging)
        switchRdsLogging.isChecked = rdsLogRepository.loggingEnabled
        switchRdsLogging.setOnCheckedChangeListener { _, isChecked ->
            rdsLogRepository.loggingEnabled = isChecked
        }
        dialogView.findViewById<ImageButton>(R.id.btnRdsLoggingInfo).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.rds_logging)
                .setMessage(R.string.rds_logging_info)
                .setPositiveButton(R.string.confirm, null)
                .show()
        }

        // RDS Retention item (opens dialog)
        val textRdsRetentionValue = dialogView.findViewById<TextView>(R.id.textRdsRetentionValue)
        textRdsRetentionValue.text = getString(R.string.days_format, rdsLogRepository.retentionDays)
        dialogView.findViewById<View>(R.id.itemRdsRetention).setOnClickListener {
            showRdsRetentionDialog { selectedDays ->
                rdsLogRepository.retentionDays = selectedDays
                textRdsRetentionValue.text = getString(R.string.days_format, selectedDays)
                rdsLogRepository.performCleanup()
            }
        }
        dialogView.findViewById<ImageButton>(R.id.btnArchiveRetentionInfo).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.archive_retention)
                .setMessage(R.string.archive_retention_info)
                .setPositiveButton(R.string.confirm, null)
                .show()
        }

        // Clear Archive item
        val textArchiveCount = dialogView.findViewById<TextView>(R.id.textArchiveCount)
        CoroutineScope(Dispatchers.Main).launch {
            val count = withContext(Dispatchers.IO) { rdsLogRepository.getEntryCount() }
            textArchiveCount.text = getString(R.string.entries_format, count)
        }
        dialogView.findViewById<View>(R.id.itemClearArchive).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.clear_archive_title)
                .setMessage(R.string.clear_archive_message)
                .setPositiveButton(R.string.delete) { _, _ ->
                    rdsLogRepository.clearAll()
                    textArchiveCount.text = getString(R.string.entries_format, 0)
                    android.widget.Toast.makeText(this, R.string.archive_cleared, android.widget.Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        // Spotify API Credentials
        val editSpotifyClientId = dialogView.findViewById<android.widget.EditText>(R.id.editSpotifyClientId)
        val editSpotifyClientSecret = dialogView.findViewById<android.widget.EditText>(R.id.editSpotifyClientSecret)

        // Flag to prevent saving during initial load
        var isLoadingSpotifyCredentials = true

        // Add TextWatchers BEFORE setting text (they won't trigger during load due to flag)
        editSpotifyClientId?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (!isLoadingSpotifyCredentials) {
                    presetRepository.setSpotifyClientId(s?.toString() ?: "")
                    android.util.Log.d("fytFM", "Saved Spotify Client ID: ${s?.toString()?.take(8)}...")
                    // Spotify sofort neu initialisieren
                    initSpotifyIntegration()
                }
            }
        })
        editSpotifyClientSecret?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (!isLoadingSpotifyCredentials) {
                    presetRepository.setSpotifyClientSecret(s?.toString() ?: "")
                    android.util.Log.d("fytFM", "Saved Spotify Client Secret")
                    // Spotify sofort neu initialisieren
                    initSpotifyIntegration()
                }
            }
        })

        // Load saved values (TextWatchers won't save due to flag being true)
        editSpotifyClientId?.setText(presetRepository.getSpotifyClientId())
        editSpotifyClientSecret?.setText(presetRepository.getSpotifyClientSecret())

        // Enable saving after initial load
        isLoadingSpotifyCredentials = false

        // Spotify Cache Enable Switch
        val switchSpotifyCache = dialogView.findViewById<android.widget.Switch>(R.id.switchSpotifyCache)
        switchSpotifyCache?.isChecked = presetRepository.isSpotifyCacheEnabled()
        switchSpotifyCache?.setOnCheckedChangeListener { _, isChecked ->
            presetRepository.setSpotifyCacheEnabled(isChecked)
        }

        // Spotify Cache Stats and Export/Import
        val tvSpotifyCacheStats = dialogView.findViewById<TextView>(R.id.tvSpotifyCacheStats)
        val btnExportCache = dialogView.findViewById<TextView>(R.id.btnExportSpotifyCache)
        val btnImportCache = dialogView.findViewById<TextView>(R.id.btnImportSpotifyCache)

        // Update cache stats display
        fun updateCacheStats() {
            spotifyCache?.let { cache ->
                val (trackCount, coverSize) = cache.getCacheStats()
                val sizeStr = if (coverSize > 1024 * 1024) {
                    "%.1f MB".format(coverSize / 1024.0 / 1024.0)
                } else {
                    "%.1f KB".format(coverSize / 1024.0)
                }
                tvSpotifyCacheStats?.text = "Cache: $trackCount Tracks ($sizeStr)"
            }
        }
        updateCacheStats()

        // Export cache button
        btnExportCache?.setOnClickListener {
            val intent = android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(android.content.Intent.CATEGORY_OPENABLE)
                type = "application/zip"
                putExtra(android.content.Intent.EXTRA_TITLE, "spotify_cache_${System.currentTimeMillis()}.zip")
            }
            spotifyCacheExportLauncher.launch(intent)
        }

        // Import cache button
        btnImportCache?.setOnClickListener {
            val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(android.content.Intent.CATEGORY_OPENABLE)
                type = "application/zip"
            }
            spotifyCacheImportLauncher.launch(intent)
        }

        // View cache button
        dialogView.findViewById<TextView>(R.id.btnViewSpotifyCache)?.setOnClickListener {
            dialog.dismiss()
            showSpotifyCacheDialog()
        }

        // Language item (opens dialog)
        val textLanguageValue = dialogView.findViewById<TextView>(R.id.textLanguageValue)
        textLanguageValue.text = LocaleHelper.getLanguageDisplayName(this, LocaleHelper.getLanguage(this))
        dialogView.findViewById<View>(R.id.itemLanguage).setOnClickListener {
            showLanguageDialog { selectedLanguage ->
                LocaleHelper.setLocale(this, selectedLanguage)
                dialog.dismiss()
                recreate() // Restart activity to apply new language
            }
        }

        // Now Playing Animation item
        val textNowPlayingAnimationValue = dialogView.findViewById<TextView>(R.id.textNowPlayingAnimationValue)
        textNowPlayingAnimationValue.text = getNowPlayingAnimationName(presetRepository.getNowPlayingAnimation())
        dialogView.findViewById<View>(R.id.itemNowPlayingAnimation).setOnClickListener {
            showNowPlayingAnimationDialog { selectedAnimation ->
                presetRepository.setNowPlayingAnimation(selectedAnimation)
                textNowPlayingAnimationValue.text = getNowPlayingAnimationName(selectedAnimation)
            }
        }

        // Correction Helpers toggle
        val switchCorrectionHelpers = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchCorrectionHelpers)
        switchCorrectionHelpers.isChecked = presetRepository.isCorrectionHelpersEnabled()
        switchCorrectionHelpers.setOnCheckedChangeListener { _, isChecked ->
            presetRepository.setCorrectionHelpersEnabled(isChecked)
            updateCorrectionHelpersVisibility()
        }
        dialogView.findViewById<ImageButton>(R.id.btnCorrectionHelpersInfo).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Correction Helpers")
                .setMessage("Zeigt Trash und Refresh Buttons in der Now Playing Bar.\n\n" +
                    "Trash: Ignoriert diesen RT-Text komplett (keine Suche mehr)\n\n" +
                    "Refresh: Überspringt den aktuellen Track und sucht einen anderen")
                .setPositiveButton(R.string.confirm, null)
                .show()
        }

        // View Corrections item
        val textCorrectionsCount = dialogView.findViewById<TextView>(R.id.textCorrectionsCount)
        CoroutineScope(Dispatchers.IO).launch {
            val count = rtCorrectionDao?.getCount() ?: 0
            withContext(Dispatchers.Main) {
                textCorrectionsCount.text = "$count Korrekturen"
            }
        }
        dialogView.findViewById<View>(R.id.itemViewCorrections).setOnClickListener {
            dialog.dismiss()
            showCorrectionsViewerDialog()
        }

        // Close App button
        dialogView.findViewById<TextView>(R.id.btnCloseApp).setOnClickListener {
            dialog.dismiss()
            closeApp()
        }

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setOnDismissListener { settingsUpdateListener = null }
        dialog.show()

        // Dialog breiter machen (70% der Bildschirmbreite)
        val targetWidth = (resources.displayMetrics.widthPixels * 0.7).toInt()
        android.util.Log.d("fytFM", "Settings Dialog: screenWidth=${resources.displayMetrics.widthPixels}, targetWidth=$targetWidth")

        dialog.window?.let { window ->
            val params = window.attributes
            android.util.Log.d("fytFM", "Settings Dialog BEFORE: width=${params.width}, height=${params.height}")
            params.width = targetWidth
            params.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT
            window.attributes = params
            android.util.Log.d("fytFM", "Settings Dialog AFTER: width=${window.attributes.width}")
        }
    }

    /**
     * Beendet die App vollständig (Radio aus, Prozess beenden)
     */
    private fun closeApp() {
        // Radio ausschalten
        if (isRadioOn) {
            stopRdsPolling()
            fmNative.powerOff()
            twUtil?.radioOff()
        }
        twUtil?.close()

        // Activity beenden
        finishAffinity()

        // Prozess beenden
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun exportSpotifyCacheToUri(uri: android.net.Uri) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Create temp file for zip
                val tempFile = java.io.File(cacheDir, "spotify_cache_export.zip")
                val success = spotifyCache?.exportToZip(tempFile) ?: false

                if (success) {
                    // Copy temp file to user-selected location
                    contentResolver.openOutputStream(uri)?.use { output ->
                        tempFile.inputStream().use { input ->
                            input.copyTo(output)
                        }
                    }
                    tempFile.delete()

                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            this@MainActivity,
                            "Cache exported successfully",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            this@MainActivity,
                            "Export failed",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("fytFM", "Export failed", e)
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        "Export failed: ${e.message}",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun importSpotifyCacheFromUri(uri: android.net.Uri) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Copy user file to temp location
                val tempFile = java.io.File(cacheDir, "spotify_cache_import.zip")
                contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                val importedCount = spotifyCache?.importFromZip(tempFile) ?: -1
                tempFile.delete()

                withContext(Dispatchers.Main) {
                    if (importedCount >= 0) {
                        android.widget.Toast.makeText(
                            this@MainActivity,
                            "Imported $importedCount tracks",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        android.widget.Toast.makeText(
                            this@MainActivity,
                            "Import failed",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("fytFM", "Import failed", e)
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        "Import failed: ${e.message}",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun showSpotifyCacheDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_spotify_cache, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        val tvCount = dialogView.findViewById<TextView>(R.id.tvCacheCount)
        val etSearch = dialogView.findViewById<android.widget.EditText>(R.id.etCacheSearch)
        val rvTracks = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvCacheTracks)
        val tvEmpty = dialogView.findViewById<TextView>(R.id.tvCacheEmpty)
        val btnClose = dialogView.findViewById<TextView>(R.id.btnCloseCache)

        val adapter = at.planqton.fytfm.spotify.CachedTrackAdapter { track ->
            // On track click - open Spotify URL if available
            track.spotifyUrl?.let { url ->
                try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    startActivity(intent)
                } catch (e: Exception) {
                    android.widget.Toast.makeText(this, "Kann Spotify nicht öffnen", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }

        rvTracks.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        rvTracks.adapter = adapter

        // Load tracks
        val tracks = spotifyCache?.getAllCachedTracks() ?: emptyList()
        tvCount.text = "${tracks.size} Tracks"

        if (tracks.isEmpty()) {
            rvTracks.visibility = View.GONE
            tvEmpty.visibility = View.VISIBLE
        } else {
            rvTracks.visibility = View.VISIBLE
            tvEmpty.visibility = View.GONE
            adapter.setTracks(tracks)
        }

        // Search filter
        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                adapter.filter(s?.toString() ?: "")
            }
        })

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    private fun getRadioAreaName(area: Int): String {
        return when (area) {
            0 -> "USA, Korea"
            1 -> "Latin America"
            2 -> "Europe"
            3 -> "Russia"
            4 -> "Japan"
            else -> "Europe"
        }
    }

    private fun showLanguageDialog(onSelected: (String) -> Unit) {
        val options = arrayOf(
            getString(R.string.language_system),
            getString(R.string.language_english),
            getString(R.string.language_german)
        )
        val values = arrayOf(
            LocaleHelper.LANGUAGE_SYSTEM,
            LocaleHelper.LANGUAGE_ENGLISH,
            LocaleHelper.LANGUAGE_GERMAN
        )
        val currentLang = LocaleHelper.getLanguage(this)
        val currentIndex = values.indexOfFirst { it == currentLang }.coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(R.string.language)
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                onSelected(values[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun getNowPlayingAnimationName(type: Int): String {
        return when (type) {
            0 -> "Keine"
            1 -> "Slide"
            2 -> "Fade"
            else -> "Slide"
        }
    }

    private fun showNowPlayingAnimationDialog(onSelected: (Int) -> Unit) {
        val options = arrayOf("Keine", "Slide", "Fade")
        val currentType = presetRepository.getNowPlayingAnimation()

        AlertDialog.Builder(this)
            .setTitle("Now Playing Animation")
            .setSingleChoiceItems(options, currentType) { dialog, which ->
                onSelected(which)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showCorrectionsViewerDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_corrections_viewer, null)

        val dialog = AlertDialog.Builder(this, R.style.TransparentDialog)
            .setView(dialogView)
            .create()

        // Edit strings section
        val recyclerEditStrings = dialogView.findViewById<RecyclerView>(R.id.recyclerEditStrings)
        val textEmptyEditStrings = dialogView.findViewById<TextView>(R.id.textEmptyEditStrings)
        val btnAddEditString = dialogView.findViewById<ImageButton>(R.id.btnAddEditString)

        recyclerEditStrings.layoutManager = LinearLayoutManager(this)
        val editStringsAdapter = EditStringsAdapter(
            onEditClick = { editString -> showEditStringDialog(editString) },
            onDeleteClick = { editString ->
                CoroutineScope(Dispatchers.IO).launch {
                    editStringDao?.delete(editString)
                }
            },
            onToggleEnabled = { editString, enabled ->
                CoroutineScope(Dispatchers.IO).launch {
                    editStringDao?.setEnabled(editString.id, enabled)
                }
            }
        )
        recyclerEditStrings.adapter = editStringsAdapter

        // Corrections section
        val recyclerCorrections = dialogView.findViewById<RecyclerView>(R.id.recyclerCorrections)
        val textEmptyCorrections = dialogView.findViewById<TextView>(R.id.textEmptyCorrections)
        val btnClearAll = dialogView.findViewById<TextView>(R.id.btnClearAllCorrections)

        recyclerCorrections.layoutManager = LinearLayoutManager(this)
        val correctionsAdapter = CorrectionsAdapter { correction ->
            CoroutineScope(Dispatchers.IO).launch {
                rtCorrectionDao?.delete(correction)
            }
        }
        recyclerCorrections.adapter = correctionsAdapter

        // Add edit string button
        btnAddEditString.setOnClickListener {
            showEditStringDialog(null)
        }

        // Observe edit strings
        val job1 = CoroutineScope(Dispatchers.Main).launch {
            editStringDao?.getAll()?.collectLatest { editStrings ->
                editStringsAdapter.submitList(editStrings)
                if (editStrings.isEmpty()) {
                    textEmptyEditStrings.visibility = View.VISIBLE
                    recyclerEditStrings.visibility = View.GONE
                } else {
                    textEmptyEditStrings.visibility = View.GONE
                    recyclerEditStrings.visibility = View.VISIBLE
                }
            }
        }

        // Observe corrections
        val job2 = CoroutineScope(Dispatchers.Main).launch {
            rtCorrectionDao?.getAllCorrections()?.collectLatest { corrections ->
                correctionsAdapter.submitList(corrections)
                if (corrections.isEmpty()) {
                    textEmptyCorrections.visibility = View.VISIBLE
                    recyclerCorrections.visibility = View.GONE
                } else {
                    textEmptyCorrections.visibility = View.GONE
                    recyclerCorrections.visibility = View.VISIBLE
                }
            }
        }

        // Clear all button
        btnClearAll.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Alle löschen?")
                .setMessage("Alle Edit Strings und RT-Korrekturen werden gelöscht.")
                .setPositiveButton("Löschen") { _, _ ->
                    CoroutineScope(Dispatchers.IO).launch {
                        editStringDao?.deleteAll()
                        rtCorrectionDao?.deleteAll()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        dialog.setOnDismissListener {
            job1.cancel()
            job2.cancel()
        }

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    private fun showEditStringDialog(existingEditString: EditString?) {
        val isEdit = existingEditString != null
        val title = if (isEdit) "Edit Regel bearbeiten" else "Edit Regel hinzufügen"

        // Create scrollable container
        val scrollView = android.widget.ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Create custom layout
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }
        scrollView.addView(layout)

        // Find text input
        val findLabel = TextView(this).apply {
            text = "Suchen nach:"
            setTextColor(android.graphics.Color.parseColor("#666666"))
        }
        layout.addView(findLabel)

        val inputFind = EditText(this).apply {
            hint = "z.B. \"Jetzt On Air:\" oder \" mit \""
            setText(existingEditString?.textOriginal ?: "")
        }
        layout.addView(inputFind)

        // Case sensitive checkbox for find text (small)
        val checkCaseSensitiveFind = android.widget.CheckBox(this).apply {
            text = "Groß-/Kleinschreibung"
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#888888"))
            isChecked = existingEditString?.caseSensitiveFind ?: false
        }
        layout.addView(checkCaseSensitiveFind)

        // Replace text input
        val replaceLabel = TextView(this).apply {
            text = "Ersetzen durch:"
            setPadding(0, 24, 0, 0)
            setTextColor(android.graphics.Color.parseColor("#666666"))
        }
        layout.addView(replaceLabel)

        val inputReplace = EditText(this).apply {
            hint = "(leer = löschen, oder z.B. \" - \")"
            setText(existingEditString?.replaceWith ?: "")
        }
        layout.addView(inputReplace)

        // Position label
        val positionLabel = TextView(this).apply {
            text = "Position:"
            setPadding(0, 24, 0, 8)
            setTextColor(android.graphics.Color.parseColor("#666666"))
        }
        layout.addView(positionLabel)

        // RadioGroup for position selection
        val radioGroup = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.VERTICAL
        }

        val positions = listOf(
            EditString.POSITION_PREFIX to "Am Anfang",
            EditString.POSITION_SUFFIX to "Am Ende",
            EditString.POSITION_EITHER to "Anfang oder Ende",
            EditString.POSITION_ANYWHERE to "Überall"
        )

        positions.forEachIndexed { index, (_, label) ->
            val radioButton = android.widget.RadioButton(this).apply {
                text = label
                id = index
            }
            radioGroup.addView(radioButton)
        }

        // Set current position
        val currentPositionIndex = positions.indexOfFirst { it.first == existingEditString?.position }
        radioGroup.check(if (currentPositionIndex >= 0) currentPositionIndex else 0)
        layout.addView(radioGroup)

        // Condition: RT contains
        val conditionLabel = TextView(this).apply {
            text = "Nur wenn RT enthält (optional):"
            setPadding(0, 24, 0, 0)
            setTextColor(android.graphics.Color.parseColor("#666666"))
        }
        layout.addView(conditionLabel)

        val inputCondition = EditText(this).apply {
            hint = "z.B. \"Jetzt On Air:\" (leer = immer)"
            setText(existingEditString?.conditionContains ?: "")
        }
        layout.addView(inputCondition)

        // Case sensitive checkbox for condition (small)
        val checkCaseSensitiveCondition = android.widget.CheckBox(this).apply {
            text = "Groß-/Kleinschreibung"
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#888888"))
            isChecked = existingEditString?.caseSensitiveCondition ?: false
        }
        layout.addView(checkCaseSensitiveCondition)

        // Frequency filter
        val frequencyLabel = TextView(this).apply {
            text = "Nur für Frequenz (optional):"
            setPadding(0, 24, 0, 0)
            setTextColor(android.graphics.Color.parseColor("#666666"))
        }
        layout.addView(frequencyLabel)

        val inputFrequency = EditText(this).apply {
            hint = "z.B. 93.4 (leer = alle)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            existingEditString?.forFrequency?.let { setText(it.toString()) }
        }
        layout.addView(inputFrequency)

        // Fallback checkbox
        val checkFallback = android.widget.CheckBox(this).apply {
            text = "Nur wenn nichts gefunden (Fallback)"
            setPadding(0, 24, 0, 0)
            isChecked = existingEditString?.onlyIfNotFound ?: false
        }
        layout.addView(checkFallback)

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scrollView)
            .setPositiveButton(if (isEdit) "Speichern" else "Hinzufügen") { _, _ ->
                val findText = inputFind.text.toString().trim()
                if (findText.isNotEmpty()) {
                    val replaceText = inputReplace.text.toString()
                    val selectedPosition = positions.getOrNull(radioGroup.checkedRadioButtonId)?.first
                        ?: EditString.POSITION_PREFIX
                    val isFallback = checkFallback.isChecked
                    val isCaseSensitiveFind = checkCaseSensitiveFind.isChecked
                    val isCaseSensitiveCondition = checkCaseSensitiveCondition.isChecked
                    val condition = inputCondition.text.toString().trim().takeIf { it.isNotEmpty() }
                    val frequency = inputFrequency.text.toString().toFloatOrNull()

                    CoroutineScope(Dispatchers.IO).launch {
                        if (isEdit && existingEditString != null) {
                            // Update existing
                            val updated = existingEditString.copy(
                                textOriginal = findText,
                                textNormalized = EditString.normalize(findText),
                                replaceWith = replaceText,
                                position = selectedPosition,
                                onlyIfNotFound = isFallback,
                                conditionContains = condition,
                                caseSensitiveFind = isCaseSensitiveFind,
                                caseSensitiveCondition = isCaseSensitiveCondition,
                                forFrequency = frequency
                            )
                            editStringDao?.update(updated)
                        } else {
                            // Insert new
                            editStringDao?.insert(
                                EditString.create(
                                    text = findText,
                                    replaceWith = replaceText,
                                    position = selectedPosition,
                                    onlyIfNotFound = isFallback,
                                    conditionContains = condition,
                                    caseSensitiveFind = isCaseSensitiveFind,
                                    caseSensitiveCondition = isCaseSensitiveCondition,
                                    forFrequency = frequency
                                )
                            )
                        }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showRdsRetentionDialog(onSelected: (Int) -> Unit) {
        val values = intArrayOf(7, 14, 30, 90)
        val options = values.map { getString(R.string.days_format, it) }.toTypedArray()
        val currentDays = rdsLogRepository.retentionDays
        val currentIndex = values.indexOfFirst { it == currentDays }.coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(R.string.archive_retention)
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                onSelected(values[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showRadioAreaDialog(onSelected: (Int) -> Unit) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_radio_area, null)

        val dialog = AlertDialog.Builder(this, R.style.TransparentDialog)
            .setView(dialogView)
            .create()

        val currentArea = presetRepository.getRadioArea()

        // Checkmarks basierend auf aktuellem Wert setzen
        val checkUSA = dialogView.findViewById<ImageView>(R.id.checkUSA)
        val checkLatinAmerica = dialogView.findViewById<ImageView>(R.id.checkLatinAmerica)
        val checkEurope = dialogView.findViewById<ImageView>(R.id.checkEurope)
        val checkRussia = dialogView.findViewById<ImageView>(R.id.checkRussia)
        val checkJapan = dialogView.findViewById<ImageView>(R.id.checkJapan)

        checkUSA.visibility = if (currentArea == 0) View.VISIBLE else View.GONE
        checkLatinAmerica.visibility = if (currentArea == 1) View.VISIBLE else View.GONE
        checkEurope.visibility = if (currentArea == 2) View.VISIBLE else View.GONE
        checkRussia.visibility = if (currentArea == 3) View.VISIBLE else View.GONE
        checkJapan.visibility = if (currentArea == 4) View.VISIBLE else View.GONE

        // Chevrons anzeigen wenn Templates für die Region existieren
        val chevronUSA = dialogView.findViewById<ImageView>(R.id.chevronUSA)
        val chevronLatinAmerica = dialogView.findViewById<ImageView>(R.id.chevronLatinAmerica)
        val chevronEurope = dialogView.findViewById<ImageView>(R.id.chevronEurope)
        val chevronRussia = dialogView.findViewById<ImageView>(R.id.chevronRussia)
        val chevronJapan = dialogView.findViewById<ImageView>(R.id.chevronJapan)

        chevronUSA.visibility = if (radioLogoRepository.getTemplatesForArea(0).isNotEmpty()) View.VISIBLE else View.GONE
        chevronLatinAmerica.visibility = if (radioLogoRepository.getTemplatesForArea(1).isNotEmpty()) View.VISIBLE else View.GONE
        chevronEurope.visibility = if (radioLogoRepository.getTemplatesForArea(2).isNotEmpty()) View.VISIBLE else View.GONE
        chevronRussia.visibility = if (radioLogoRepository.getTemplatesForArea(3).isNotEmpty()) View.VISIBLE else View.GONE
        chevronJapan.visibility = if (radioLogoRepository.getTemplatesForArea(4).isNotEmpty()) View.VISIBLE else View.GONE

        // Click handler mit Template-Auswahl
        fun handleAreaClick(areaId: Int) {
            val templates = radioLogoRepository.getTemplatesForArea(areaId)
            if (templates.isNotEmpty()) {
                // Zeige Template-Auswahl für diese Region
                showAreaTemplateDialog(areaId, dialog) { selectedTemplate ->
                    onSelected(areaId)
                    dialog.dismiss()
                }
            } else {
                // Keine Templates - direkt Region wählen
                radioLogoRepository.setActiveTemplate(null)
                onSelected(areaId)
                dialog.dismiss()
            }
        }

        dialogView.findViewById<View>(R.id.itemAreaUSA).setOnClickListener { handleAreaClick(0) }
        dialogView.findViewById<View>(R.id.itemAreaLatinAmerica).setOnClickListener { handleAreaClick(1) }
        dialogView.findViewById<View>(R.id.itemAreaEurope).setOnClickListener { handleAreaClick(2) }
        dialogView.findViewById<View>(R.id.itemAreaRussia).setOnClickListener { handleAreaClick(3) }
        dialogView.findViewById<View>(R.id.itemAreaJapan).setOnClickListener { handleAreaClick(4) }

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    /**
     * Zeigt Template-Auswahl für eine bestimmte Region
     */
    private fun showAreaTemplateDialog(areaId: Int, parentDialog: AlertDialog, onComplete: (String?) -> Unit) {
        val templates = radioLogoRepository.getTemplatesForArea(areaId)
        val activeTemplate = radioLogoRepository.getActiveTemplateName()

        // Template-Namen + "Kein Template" Option
        val options = mutableListOf("Kein Template")
        options.addAll(templates.map { "${it.name} (${it.stations.size} Sender)" })

        val currentIndex = if (activeTemplate != null) {
            val templateIndex = templates.indexOfFirst { it.name == activeTemplate }
            if (templateIndex >= 0) templateIndex + 1 else 0
        } else 0

        AlertDialog.Builder(this)
            .setTitle("${getRadioAreaName(areaId)} - Template")
            .setSingleChoiceItems(options.toTypedArray(), currentIndex) { dlg, which ->
                if (which == 0) {
                    // Kein Template
                    radioLogoRepository.setActiveTemplate(null)
                    onComplete(null)
                    dlg.dismiss()
                } else {
                    // Template ausgewählt - downloaden falls nötig
                    val template = templates[which - 1]
                    val hasLocalLogos = template.stations.all { it.localPath != null }

                    if (hasLocalLogos) {
                        // Logos bereits vorhanden
                        radioLogoRepository.setActiveTemplate(template.name)
                        onComplete(template.name)
                        dlg.dismiss()
                    } else {
                        // Logos downloaden
                        dlg.dismiss()
                        downloadAndActivateTemplate(template, parentDialog) {
                            onComplete(template.name)
                        }
                    }
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun downloadAndActivateTemplate(template: at.planqton.fytfm.data.logo.RadioLogoTemplate, parentDialog: AlertDialog, onComplete: () -> Unit) {
        val progressView = layoutInflater.inflate(android.R.layout.simple_list_item_1, null)
        val progressText = progressView.findViewById<TextView>(android.R.id.text1)
        progressText.text = "Lade Logos..."
        progressText.gravity = android.view.Gravity.CENTER
        progressText.setPadding(48, 48, 48, 48)

        val progressDialog = AlertDialog.Builder(this)
            .setView(progressView)
            .setCancelable(false)
            .create()
        progressDialog.show()

        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            val (updatedTemplate, failed) = radioLogoRepository.downloadLogos(template) { current, total ->
                runOnUiThread {
                    progressText.text = "Lade Logos... ($current/$total)"
                }
            }

            progressDialog.dismiss()

            radioLogoRepository.saveTemplate(updatedTemplate)
            radioLogoRepository.setActiveTemplate(updatedTemplate.name)

            if (failed.isEmpty()) {
                android.widget.Toast.makeText(this@MainActivity,
                    "${updatedTemplate.stations.size} Logos geladen",
                    android.widget.Toast.LENGTH_SHORT).show()
            } else {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Logos geladen")
                    .setMessage("${updatedTemplate.stations.size - failed.size}/${updatedTemplate.stations.size} Logos geladen.\n\nFehlgeschlagen:\n${failed.joinToString("\n")}")
                    .setPositiveButton("OK", null)
                    .show()
            }

            onComplete()
        }
    }

    private fun showLogoTemplateDialog(onDismiss: () -> Unit) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_logo_template, null)

        val dialog = AlertDialog.Builder(this, R.style.TransparentDialog)
            .setView(dialogView)
            .create()

        val currentArea = presetRepository.getRadioArea()
        val textCurrentArea = dialogView.findViewById<TextView>(R.id.textCurrentArea)
        textCurrentArea.text = "Region: ${getRadioAreaName(currentArea)}"

        val recyclerTemplates = dialogView.findViewById<RecyclerView>(R.id.recyclerTemplates)
        val textEmptyTemplates = dialogView.findViewById<TextView>(R.id.textEmptyTemplates)

        recyclerTemplates.layoutManager = LinearLayoutManager(this)

        val templates = radioLogoRepository.getTemplatesForArea(currentArea)
        val activeTemplateName = radioLogoRepository.getActiveTemplateName()

        val adapter = at.planqton.fytfm.data.logo.LogoTemplateAdapter(
            templates = templates,
            selectedName = activeTemplateName,
            onSelect = { template ->
                // Nur aktivieren, Download erfolgt über Region Settings
                radioLogoRepository.setActiveTemplate(template.name)
                dialog.dismiss()
                onDismiss()
            },
            onEdit = { template ->
                showTemplateEditorDialog(template) {
                    // Refresh list after editing
                    val newTemplates = radioLogoRepository.getTemplatesForArea(currentArea)
                    (recyclerTemplates.adapter as? at.planqton.fytfm.data.logo.LogoTemplateAdapter)
                        ?.updateTemplates(newTemplates, radioLogoRepository.getActiveTemplateName())
                    updateEmptyState(newTemplates, recyclerTemplates, textEmptyTemplates)
                    // Refresh main station adapter to show updated logos
                    radioLogoRepository.invalidateCache()
                    stationAdapter.notifyDataSetChanged()
                }
            },
            onExport = { template ->
                exportLogoTemplate(template)
            },
            onDelete = { template ->
                AlertDialog.Builder(this)
                    .setTitle("Template löschen")
                    .setMessage("Template '${template.name}' wirklich löschen?")
                    .setPositiveButton("Löschen") { _, _ ->
                        radioLogoRepository.deleteTemplate(template.name)
                        // Refresh list
                        val newTemplates = radioLogoRepository.getTemplatesForArea(currentArea)
                        (recyclerTemplates.adapter as? at.planqton.fytfm.data.logo.LogoTemplateAdapter)
                            ?.updateTemplates(newTemplates, radioLogoRepository.getActiveTemplateName())
                        updateEmptyState(newTemplates, recyclerTemplates, textEmptyTemplates)
                    }
                    .setNegativeButton("Abbrechen", null)
                    .show()
            }
        )

        recyclerTemplates.adapter = adapter
        updateEmptyState(templates, recyclerTemplates, textEmptyTemplates)

        // Import button
        dialogView.findViewById<View>(R.id.btnImportTemplate).setOnClickListener {
            importLogoTemplate { imported ->
                if (imported) {
                    val newTemplates = radioLogoRepository.getTemplatesForArea(currentArea)
                    adapter.updateTemplates(newTemplates, radioLogoRepository.getActiveTemplateName())
                    updateEmptyState(newTemplates, recyclerTemplates, textEmptyTemplates)
                }
            }
        }

        // No template button
        dialogView.findViewById<View>(R.id.btnNoTemplate).setOnClickListener {
            radioLogoRepository.setActiveTemplate(null)
            dialog.dismiss()
            onDismiss()
        }

        dialog.setOnDismissListener { onDismiss() }
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    private fun updateEmptyState(templates: List<at.planqton.fytfm.data.logo.RadioLogoTemplate>, recycler: RecyclerView, emptyView: TextView) {
        if (templates.isEmpty()) {
            recycler.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
        } else {
            recycler.visibility = View.VISIBLE
            emptyView.visibility = View.GONE
        }
    }

    private fun selectLogoTemplate(template: at.planqton.fytfm.data.logo.RadioLogoTemplate, parentDialog: AlertDialog, onDismiss: () -> Unit) {
        // Show progress dialog
        val progressView = layoutInflater.inflate(android.R.layout.simple_list_item_1, null)
        val progressText = progressView.findViewById<TextView>(android.R.id.text1)
        progressText.text = "Lade Logos..."
        progressText.gravity = android.view.Gravity.CENTER
        progressText.setPadding(48, 48, 48, 48)

        val progressDialog = AlertDialog.Builder(this)
            .setView(progressView)
            .setCancelable(false)
            .create()
        progressDialog.show()

        // Download logos in background
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            val (updatedTemplate, failed) = radioLogoRepository.downloadLogos(template) { current, total ->
                runOnUiThread {
                    progressText.text = "Lade Logos... ($current/$total)"
                }
            }

            progressDialog.dismiss()

            // Save template and set as active
            radioLogoRepository.saveTemplate(updatedTemplate)
            radioLogoRepository.setActiveTemplate(updatedTemplate.name)

            if (failed.isEmpty()) {
                android.widget.Toast.makeText(this@MainActivity,
                    "${updatedTemplate.stations.size} Logos geladen",
                    android.widget.Toast.LENGTH_SHORT).show()
            } else {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Logos geladen")
                    .setMessage("${updatedTemplate.stations.size - failed.size}/${updatedTemplate.stations.size} Logos geladen.\n\nFehlgeschlagen:\n${failed.joinToString("\n")}")
                    .setPositiveButton("OK", null)
                    .show()
            }

            parentDialog.dismiss()
            onDismiss()
        }
    }

    private fun exportLogoTemplate(template: at.planqton.fytfm.data.logo.RadioLogoTemplate) {
        try {
            // Create file in cache directory
            val exportDir = java.io.File(cacheDir, "export")
            if (!exportDir.exists()) exportDir.mkdirs()
            val fileName = "${template.name.replace(" ", "_")}.json"
            val file = java.io.File(exportDir, fileName)
            radioLogoRepository.exportTemplateToFile(template, file)

            // Get URI via FileProvider
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )

            // Create share intent
            val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                putExtra(android.content.Intent.EXTRA_SUBJECT, "Logo Template: ${template.name}")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(shareIntent, "Template teilen"))
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "Export fehlgeschlagen: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun importLogoTemplate(onComplete: (Boolean) -> Unit) {
        logoTemplateImportCallback = onComplete
        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "application/json"
            // Fallback für alle Dateitypen falls JSON nicht erkannt wird
            putExtra(android.content.Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "*/*"))
        }
        logoTemplateImportLauncher.launch(intent)
    }

    private fun importLogoTemplateFromUri(uri: android.net.Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val jsonString = inputStream.bufferedReader().readText()
                val template = radioLogoRepository.importTemplate(jsonString)
                radioLogoRepository.saveTemplate(template)
                android.widget.Toast.makeText(this, "Importiert: ${template.name}", android.widget.Toast.LENGTH_SHORT).show()
                logoTemplateImportCallback?.invoke(true)
            } ?: run {
                android.widget.Toast.makeText(this, "Datei konnte nicht gelesen werden", android.widget.Toast.LENGTH_SHORT).show()
                logoTemplateImportCallback?.invoke(false)
            }
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "Import fehlgeschlagen: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            logoTemplateImportCallback?.invoke(false)
        }
        logoTemplateImportCallback = null
    }

    private fun showTemplateEditorDialog(template: at.planqton.fytfm.data.logo.RadioLogoTemplate, onSaved: () -> Unit) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_template_editor, null)

        val dialog = AlertDialog.Builder(this, R.style.TransparentDialog)
            .setView(dialogView)
            .create()

        val editTemplateName = dialogView.findViewById<EditText>(R.id.editTemplateName)
        val textStationCount = dialogView.findViewById<TextView>(R.id.textStationCount)
        val recyclerStations = dialogView.findViewById<RecyclerView>(R.id.recyclerStations)
        val textEmptyStations = dialogView.findViewById<TextView>(R.id.textEmptyStations)
        val btnAddStation = dialogView.findViewById<View>(R.id.btnAddStation)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancelEditor)
        val btnSave = dialogView.findViewById<View>(R.id.btnSaveTemplate)

        editTemplateName.setText(template.name)

        recyclerStations.layoutManager = LinearLayoutManager(this)

        val stationsList = template.stations.toMutableList()

        fun updateStationCount() {
            textStationCount.text = "Sender (${stationsList.size})"
            if (stationsList.isEmpty()) {
                recyclerStations.visibility = View.GONE
                textEmptyStations.visibility = View.VISIBLE
            } else {
                recyclerStations.visibility = View.VISIBLE
                textEmptyStations.visibility = View.GONE
            }
        }

        val stationAdapter = at.planqton.fytfm.data.logo.StationLogoAdapter(
            stations = stationsList,
            onEdit = { position, station ->
                val currentTemplateName = editTemplateName.text.toString().trim().ifBlank { template.name }
                showStationEditorDialog(station, currentTemplateName) { updatedStation ->
                    stationsList[position] = updatedStation
                    (recyclerStations.adapter as? at.planqton.fytfm.data.logo.StationLogoAdapter)?.updateStation(position, updatedStation)
                }
            },
            onDelete = { position, station ->
                AlertDialog.Builder(this)
                    .setTitle("Sender löschen")
                    .setMessage("Eintrag wirklich löschen?")
                    .setPositiveButton("Löschen") { _, _ ->
                        stationsList.removeAt(position)
                        (recyclerStations.adapter as? at.planqton.fytfm.data.logo.StationLogoAdapter)?.removeStation(position)
                        updateStationCount()
                    }
                    .setNegativeButton("Abbrechen", null)
                    .show()
            }
        )

        recyclerStations.adapter = stationAdapter
        updateStationCount()

        btnAddStation.setOnClickListener {
            val currentTemplateName = editTemplateName.text.toString().trim().ifBlank { template.name }
            showStationEditorDialog(null, currentTemplateName) { newStation ->
                stationsList.add(newStation)
                stationAdapter.addStation(newStation)
                updateStationCount()
            }
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnSave.setOnClickListener {
            val newName = editTemplateName.text.toString().trim()
            if (newName.isBlank()) {
                android.widget.Toast.makeText(this, "Name darf nicht leer sein", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Check if name changed and new name already exists
            if (newName != template.name && radioLogoRepository.getTemplates().any { it.name == newName }) {
                android.widget.Toast.makeText(this, "Template mit diesem Namen existiert bereits", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Delete old template if name changed
            if (newName != template.name) {
                radioLogoRepository.deleteTemplate(template.name)
            }

            // Save updated template
            val updatedTemplate = template.copy(
                name = newName,
                stations = stationsList.toList()
            )
            radioLogoRepository.saveTemplate(updatedTemplate)

            // If this was the active template, update the reference
            if (radioLogoRepository.getActiveTemplateName() == template.name ||
                radioLogoRepository.getActiveTemplateName() == newName) {
                radioLogoRepository.setActiveTemplate(newName)
            }

            android.widget.Toast.makeText(this, "Template gespeichert", android.widget.Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            onSaved()
        }

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    private fun showStationEditorDialog(station: at.planqton.fytfm.data.logo.StationLogo?, templateName: String, onSave: (at.planqton.fytfm.data.logo.StationLogo) -> Unit) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_station_editor, null)

        val dialog = AlertDialog.Builder(this, R.style.TransparentDialog)
            .setView(dialogView)
            .create()

        val editPs = dialogView.findViewById<EditText>(R.id.editStationPs)
        val editPi = dialogView.findViewById<EditText>(R.id.editStationPi)
        val editFrequencies = dialogView.findViewById<EditText>(R.id.editStationFrequencies)
        val editLogoUrl = dialogView.findViewById<EditText>(R.id.editStationLogoUrl)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancelStation)
        val btnSave = dialogView.findViewById<View>(R.id.btnSaveStation)
        val btnSearchLogo = dialogView.findViewById<ImageButton>(R.id.btnSearchLogo)
        val title = dialogView.findViewById<TextView>(R.id.textStationEditorTitle)
        val layoutSaveProgress = dialogView.findViewById<View>(R.id.layoutSaveProgress)

        title.text = if (station == null) "Sender hinzufügen" else "Sender bearbeiten"

        // Populate fields if editing
        station?.let {
            editPs.setText(it.ps ?: "")
            editPi.setText(it.pi ?: "")
            editFrequencies.setText(it.frequencies?.joinToString(", ") { f -> "%.1f".format(java.util.Locale.US, f) } ?: "")
            editLogoUrl.setText(it.logoUrl)
        }

        // Logo search button
        btnSearchLogo.setOnClickListener {
            val prefilledQuery = editPs.text.toString().trim().takeIf { it.isNotBlank() } ?: ""
            showImageSearchDialog(prefilledQuery) { selectedUrl ->
                editLogoUrl.setText(selectedUrl)
            }
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnSave.setOnClickListener {
            val ps = editPs.text.toString().trim().takeIf { it.isNotBlank() }
            val pi = editPi.text.toString().trim().takeIf { it.isNotBlank() }
            val frequenciesStr = editFrequencies.text.toString().trim()
            // Parse comma-separated frequencies
            val frequencies = frequenciesStr.split(",", ";", " ")
                .mapNotNull { it.trim().replace(",", ".").toFloatOrNull() }
                .takeIf { it.isNotEmpty() }
            val logoUrl = editLogoUrl.text.toString().trim()

            // Validate - at least one identifier and logo URL required
            if (ps == null && pi == null && frequencies == null) {
                android.widget.Toast.makeText(this, "Mindestens PS, PI oder Frequenz angeben", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (logoUrl.isBlank()) {
                android.widget.Toast.makeText(this, "Logo-URL erforderlich", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Check if URL changed - if so, download the new logo
            val urlChanged = station?.logoUrl != logoUrl

            if (urlChanged && logoUrl.startsWith("http")) {
                // Disable buttons and show progress
                btnSave.isEnabled = false
                btnCancel.isEnabled = false
                layoutSaveProgress.visibility = View.VISIBLE

                // Download logo in background
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                    var localPath: String? = station?.localPath

                    try {
                        localPath = withContext(Dispatchers.IO) {
                            // Create directory for template
                            val safeName = templateName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                            val templateDir = java.io.File(filesDir, "logos/$safeName").apply {
                                if (!exists()) mkdirs()
                            }

                            // Generate filename from URL hash
                            val digest = java.security.MessageDigest.getInstance("MD5")
                            val hash = digest.digest(logoUrl.toByteArray())
                            val filename = hash.joinToString("") { "%02x".format(it) } + ".png"
                            val localFile = java.io.File(templateDir, filename)

                            // Download
                            val client = okhttp3.OkHttpClient.Builder()
                                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                                .build()

                            val request = okhttp3.Request.Builder()
                                .url(logoUrl)
                                .build()

                            client.newCall(request).execute().use { response ->
                                if (response.isSuccessful) {
                                    response.body?.byteStream()?.use { input ->
                                        java.io.FileOutputStream(localFile).use { output ->
                                            input.copyTo(output)
                                        }
                                    }
                                    localFile.absolutePath
                                } else {
                                    throw Exception("HTTP ${response.code}")
                                }
                            }
                        }

                        if (!dialog.isShowing) return@launch

                        val newStation = at.planqton.fytfm.data.logo.StationLogo(
                            ps = ps,
                            pi = pi,
                            frequencies = frequencies,
                            logoUrl = logoUrl,
                            localPath = localPath
                        )

                        dialog.dismiss()
                        onSave(newStation)
                        android.widget.Toast.makeText(this@MainActivity, "Logo heruntergeladen", android.widget.Toast.LENGTH_SHORT).show()

                    } catch (e: Exception) {
                        android.util.Log.e("fytFM", "Failed to download logo: ${e.message}")
                        if (!dialog.isShowing) return@launch

                        layoutSaveProgress.visibility = View.GONE
                        btnSave.isEnabled = true
                        btnCancel.isEnabled = true
                        android.widget.Toast.makeText(this@MainActivity, "Logo-Download fehlgeschlagen: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                // No URL change or not HTTP, just save
                val newStation = at.planqton.fytfm.data.logo.StationLogo(
                    ps = ps,
                    pi = pi,
                    frequencies = frequencies,
                    logoUrl = logoUrl,
                    localPath = station?.localPath
                )

                dialog.dismiss()
                onSave(newStation)
            }
        }

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    private fun showImageSearchDialog(prefilledQuery: String, onSelect: (String) -> Unit) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_image_search, null)

        val dialog = AlertDialog.Builder(this, R.style.TransparentDialog)
            .setView(dialogView)
            .create()

        val editSearchQuery = dialogView.findViewById<EditText>(R.id.editSearchQuery)
        val btnSearch = dialogView.findViewById<ImageButton>(R.id.btnSearch)
        val progressLoading = dialogView.findViewById<android.widget.ProgressBar>(R.id.progressLoading)
        val recyclerImages = dialogView.findViewById<RecyclerView>(R.id.recyclerImages)
        val textEmptyResults = dialogView.findViewById<TextView>(R.id.textEmptyResults)
        val btnClose = dialogView.findViewById<View>(R.id.btnCloseSearch)

        editSearchQuery.setText(prefilledQuery)

        recyclerImages.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, 3)

        val imageAdapter = at.planqton.fytfm.ui.ImageSearchAdapter { image ->
            dialog.dismiss()
            onSelect(image.url)
        }
        recyclerImages.adapter = imageAdapter

        fun performSearch(query: String) {
            if (query.isBlank()) {
                android.widget.Toast.makeText(this, "Suchbegriff eingeben", android.widget.Toast.LENGTH_SHORT).show()
                return
            }

            progressLoading.visibility = View.VISIBLE
            recyclerImages.visibility = View.GONE
            textEmptyResults.visibility = View.GONE

            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                try {
                    val results = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        searchRadioLogos(query)
                    }

                    // Check if dialog is still showing before updating UI
                    if (!dialog.isShowing) return@launch

                    progressLoading.visibility = View.GONE

                    if (results.isEmpty()) {
                        textEmptyResults.text = "Keine Ergebnisse für \"$query\""
                        textEmptyResults.visibility = View.VISIBLE
                        recyclerImages.visibility = View.GONE
                    } else {
                        textEmptyResults.visibility = View.GONE
                        recyclerImages.visibility = View.VISIBLE
                        imageAdapter.setImages(results)
                    }
                } catch (e: Exception) {
                    if (!dialog.isShowing) return@launch
                    progressLoading.visibility = View.GONE
                    textEmptyResults.text = "Fehler: ${e.message}"
                    textEmptyResults.visibility = View.VISIBLE
                    recyclerImages.visibility = View.GONE
                }
            }
        }

        btnSearch.setOnClickListener {
            performSearch(editSearchQuery.text.toString().trim())
        }

        editSearchQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                performSearch(editSearchQuery.text.toString().trim())
                true
            } else false
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()

        // Auto-search if prefilled (after dialog is shown)
        if (prefilledQuery.isNotBlank()) {
            performSearch(prefilledQuery)
        }
    }

    private fun searchRadioLogos(query: String): List<at.planqton.fytfm.ui.ImageResult> {
        val results = mutableListOf<at.planqton.fytfm.ui.ImageResult>()
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")

        // Step 1: Get vqd token from DuckDuckGo
        val tokenUrl = "https://duckduckgo.com/?q=$encodedQuery"
        val tokenRequest = okhttp3.Request.Builder()
            .url(tokenUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()

        val vqd: String
        client.newCall(tokenRequest).execute().use { response ->
            if (!response.isSuccessful) return results
            val html = response.body?.string() ?: return results

            // Extract vqd token using regex
            val vqdMatch = Regex("vqd=([\"'])([^\"']+)\\1").find(html)
                ?: Regex("vqd=([\\d-]+)").find(html)
            vqd = vqdMatch?.groupValues?.lastOrNull() ?: return results
        }

        // Step 2: Search images with token
        val imageUrl = "https://duckduckgo.com/i.js?l=de-de&o=json&q=$encodedQuery&vqd=$vqd&f=,,,&p=1"
        val imageRequest = okhttp3.Request.Builder()
            .url(imageUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Referer", "https://duckduckgo.com/")
            .build()

        client.newCall(imageRequest).execute().use { response ->
            if (!response.isSuccessful) return results
            val body = response.body?.string() ?: return results

            val json = org.json.JSONObject(body)
            val resultsArray = json.optJSONArray("results") ?: return results

            val seenUrls = mutableSetOf<String>()
            val maxResults = minOf(resultsArray.length(), 30)

            for (i in 0 until maxResults) {
                val item = resultsArray.getJSONObject(i)
                val imageUrl = item.optString("image", "")
                val title = item.optString("title", "Bild")

                // Filter out SVG files (not supported by MediaSession)
                if (imageUrl.isNotBlank() &&
                    imageUrl.startsWith("http") &&
                    !imageUrl.lowercase().endsWith(".svg") &&
                    !imageUrl.lowercase().contains(".svg?") &&
                    !seenUrls.contains(imageUrl)) {
                    seenUrls.add(imageUrl)
                    results.add(at.planqton.fytfm.ui.ImageResult(imageUrl, title))
                }
            }
        }

        return results
    }

    private fun showRadioEditorDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_radio_editor, null)

        val dialog = AlertDialog.Builder(this, R.style.TransparentDialog)
            .setView(dialogView)
            .create()

        val rvStations = dialogView.findViewById<RecyclerView>(R.id.rvStations)
        rvStations.layoutManager = LinearLayoutManager(this)

        val stations = if (frequencyScale.getMode() == FrequencyScaleView.RadioMode.FM) {
            presetRepository.loadFmStations()
        } else {
            presetRepository.loadAmStations()
        }

        val adapter = at.planqton.fytfm.ui.StationEditorAdapter(
            onEdit = { station ->
                showEditStationDialog(station) { newName ->
                    // Update station name
                    val updatedStations = stations.map {
                        if (it.frequency == station.frequency) it.copy(name = newName) else it
                    }
                    saveStations(updatedStations)
                    loadStationsForCurrentMode()
                }
            },
            onFavorite = { station ->
                val updatedStations = stations.map {
                    if (it.frequency == station.frequency) it.copy(isFavorite = !it.isFavorite) else it
                }
                saveStations(updatedStations)
                rvStations.adapter?.notifyDataSetChanged()
            },
            onDelete = { station ->
                AlertDialog.Builder(this)
                    .setTitle("Sender löschen")
                    .setMessage("${station.getDisplayFrequency()} wirklich löschen?")
                    .setPositiveButton("Ja") { _, _ ->
                        val updatedStations = stations.filter { it.frequency != station.frequency }
                        saveStations(updatedStations)
                        loadStationsForCurrentMode()
                        dialog.dismiss()
                        showRadioEditorDialog() // Reopen with updated list
                    }
                    .setNegativeButton("Nein", null)
                    .show()
            }
        )
        adapter.setStations(stations)
        rvStations.adapter = adapter

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    private fun showEditStationDialog(station: at.planqton.fytfm.data.RadioStation, onSave: (String) -> Unit) {
        val input = android.widget.EditText(this).apply {
            setText(station.name ?: "")
            hint = "Sendername"
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("Sender bearbeiten")
            .setMessage(station.getDisplayFrequency())
            .setView(input)
            .setPositiveButton("Speichern") { _, _ ->
                onSave(input.text.toString())
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun showStationScanDialog() {
        val isFmMode = frequencyScale.getMode() == FrequencyScaleView.RadioMode.FM
        val highSensitivity = presetRepository.isAutoScanSensitivity()
        val dialog = at.planqton.fytfm.ui.StationListDialog(
            this,
            radioScanner,
            onStationsAdded = { stations ->
                // Gefundene Sender mit bestehenden zusammenführen (Favoriten schützen)
                val isAM = frequencyScale.getMode() == FrequencyScaleView.RadioMode.AM
                presetRepository.mergeScannedStations(stations, isAM)
                loadStationsForCurrentMode()
            },
            onStationSelected = { station ->
                // Station ausgewählt - tune zur Frequenz
                frequencyScale.setFrequency(station.frequency)
                updateFrequencyDisplay(station.frequency)
                rdsManager.tune(station.frequency)
            },
            initialMode = isFmMode,
            highSensitivity = highSensitivity
        )
        dialog.show()
    }

    private fun saveStations(stations: List<at.planqton.fytfm.data.RadioStation>) {
        if (frequencyScale.getMode() == FrequencyScaleView.RadioMode.FM) {
            presetRepository.saveFmStations(stations)
        } else {
            presetRepository.saveAmStations(stations)
        }
    }

    private fun loadStationsForCurrentMode() {
        val allStations = if (frequencyScale.getMode() == FrequencyScaleView.RadioMode.FM) {
            presetRepository.loadFmStations()
        } else {
            presetRepository.loadAmStations()
        }

        // Filter anwenden wenn aktiviert
        val stations = if (showFavoritesOnly) {
            allStations.filter { it.isFavorite }
        } else {
            allStations
        }

        stationAdapter.setStations(stations)
        stationAdapter.setSelectedFrequency(frequencyScale.getFrequency())
    }

    // Scanner-Funktionen deaktiviert

    private fun skipToPreviousStation() {
        val stations = stationAdapter.getStations()
        if (stations.isEmpty()) return

        val currentFreq = frequencyScale.getFrequency()
        val prevStation = stations.lastOrNull { it.frequency < currentFreq - 0.05f }
            ?: stations.lastOrNull()

        prevStation?.let {
            frequencyScale.setFrequency(it.frequency)
        }
    }

    private fun skipToNextStation() {
        val stations = stationAdapter.getStations()
        if (stations.isEmpty()) return

        val currentFreq = frequencyScale.getFrequency()
        val nextStation = stations.firstOrNull { it.frequency > currentFreq + 0.05f }
            ?: stations.firstOrNull()

        nextStation?.let {
            frequencyScale.setFrequency(it.frequency)
        }
    }

    private fun updateFrequencyDisplay(frequency: Float) {
        val mode = frequencyScale.getMode()
        tvFrequency.text = if (mode == FrequencyScaleView.RadioMode.FM) {
            String.format("FM %.2f", frequency)
        } else {
            String.format("AM %d", frequency.toInt())
        }
        // Update debug overlay frequency
        updateDebugInfo(freq = frequency)
    }

    private fun updateModeButton() {
        val mode = frequencyScale.getMode()
        val iconRes = if (mode == FrequencyScaleView.RadioMode.FM) {
            R.drawable.ic_fm
        } else {
            R.drawable.ic_am
        }
        btnFM.setImageResource(iconRes)
    }

    private fun updatePlayPauseButton() {
        val iconRes = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        btnPlayPause.setImageResource(iconRes)
        pipBtnPlayPause?.setImageResource(iconRes)
    }

    /**
     * Radio Ein/Aus schalten.
     *
     * WICHTIGE REIHENFOLGE für RDS:
     * 1. TWUtil.initRadioSequence() - MCU initialisieren
     * 2. TWUtil.radioOn() - Audio-Routing aktivieren
     * 3. FmNative.powerOn() - FM-Chip einschalten
     * 4. RdsManager.enableRds() - RDS aktivieren
     * 5. RdsManager.startPolling() - RDS-Daten abrufen
     */
    private fun toggleRadioPower() {
        android.util.Log.i("fytFM", "======= toggleRadioPower() =======")
        android.util.Log.i("fytFM", "FmNative.isLibraryLoaded: ${FmNative.isLibraryLoaded()}")
        android.util.Log.i("fytFM", "TWUtil available: ${twUtil?.isAvailable}")

        try {
            if (isRadioOn) {
                // Radio ausschalten
                android.util.Log.i("fytFM", "--- Powering OFF ---")
                stopRdsPolling()
                fmNative.powerOff()
                twUtil?.radioOff()
                isRadioOn = false
                isPlaying = true  // Reset to "playing" state for next power on
                updatePlayPauseButton()
                FytFMMediaService.instance?.updatePlaybackState(false)
            } else {
                // Radio einschalten - REIHENFOLGE KRITISCH!
                val frequency = frequencyScale.getFrequency()
                android.util.Log.i("fytFM", "--- Powering ON at $frequency MHz ---")

                // Schritt 0: FmService starten für Audio-Routing
                android.util.Log.i("fytFM", "Step 0: Starting FmService for audio routing")
                try {
                    val serviceIntent = android.content.Intent()
                    serviceIntent.setClassName("com.syu.music", "com.android.fmradio.FmService")
                    startService(serviceIntent)
                    android.util.Log.i("fytFM", "FmService start requested")
                } catch (e: Exception) {
                    android.util.Log.w("fytFM", "Could not start FmService: ${e.message}")
                }

                // Auch ACTION_OPEN_RADIO Broadcast senden
                android.util.Log.i("fytFM", "Step 0b: Sending ACTION_OPEN_RADIO broadcast")
                val radioIntent = android.content.Intent("com.action.ACTION_OPEN_RADIO")
                sendBroadcast(radioIntent)

                // Schritt 1+2: TWUtil (MCU + Audio)
                if (twUtil?.isAvailable == true) {
                    android.util.Log.i("fytFM", "Step 1: TWUtil.initRadioSequence()")
                    twUtil?.initRadioSequence()

                    android.util.Log.i("fytFM", "Step 2: TWUtil.radioOn()")
                    twUtil?.radioOn()

                    // Schritt 2b: MCU Unmute
                    android.util.Log.i("fytFM", "Step 2b: TWUtil.unmute()")
                    twUtil?.unmute()

                    // Kurze Pause damit MCU die Befehle verarbeiten kann
                    Thread.sleep(100)
                } else {
                    android.util.Log.w("fytFM", "TWUtil NOT available - skipping MCU init!")
                }

                // Schritt 3: FM-Chip einschalten
                android.util.Log.i("fytFM", "Step 3: FmNative.openDev()")
                val openResult = fmNative.openDev()
                android.util.Log.i("fytFM", "openDev result: $openResult")

                android.util.Log.i("fytFM", "Step 4: FmNative.powerUp($frequency)")
                val powerResult = fmNative.powerUp(frequency)
                android.util.Log.i("fytFM", "powerUp result: $powerResult")

                android.util.Log.i("fytFM", "Step 5: FmNative.tune($frequency)")
                val tuneResult = fmNative.tune(frequency)
                android.util.Log.i("fytFM", "tune result: $tuneResult")

                // Schritt 5b: Unmute - WICHTIG für Audio!
                android.util.Log.i("fytFM", "Step 5b: FmNative.setMute(false)")
                val muteResult = fmNative.setMute(false)
                android.util.Log.i("fytFM", "setMute(false) result: $muteResult")

                // Schritt 5c: Audio-Source nochmal setzen nach FM-Chip Init
                if (twUtil?.isAvailable == true) {
                    android.util.Log.i("fytFM", "Step 5c: TWUtil.setAudioSourceFm() (repeat)")
                    twUtil?.setAudioSourceFm()
                }

                isRadioOn = openResult && powerResult
                android.util.Log.i("fytFM", "isRadioOn = $isRadioOn")

                if (isRadioOn) {
                    // Reset play state to playing (unmuted) when radio turns on
                    isPlaying = true
                    updatePlayPauseButton()
                    FytFMMediaService.instance?.updatePlaybackState(true)
                    // Schritt 6: RDS aktivieren
                    android.util.Log.i("fytFM", "Step 6: RdsManager.enableRds()")
                    rdsManager.enableRds()

                    // Schritt 6b: Tuner Settings anwenden
                    android.util.Log.i("fytFM", "Step 6b: Apply tuner settings")
                    applyTunerSettings()

                    // Schritt 7: RDS-Polling starten
                    android.util.Log.i("fytFM", "Step 7: startRdsPolling()")
                    startRdsPolling()
                } else {
                    android.util.Log.e("fytFM", "RADIO FAILED TO START!")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("fytFM", "Radio power toggle failed: ${e.message}", e)
            isRadioOn = false
        }
        android.util.Log.i("fytFM", "======= toggleRadioPower() done =======")
        updatePowerButton()
    }

    /**
     * Wendet die gespeicherten Tuner-Settings an (LOC, Mono, Area)
     */
    private fun applyTunerSettings() {
        try {
            // LOC Local Mode
            val localMode = presetRepository.isLocalMode()
            fmNative?.setLocalMode(localMode)
            android.util.Log.d("fytFM", "Applied LOC mode: $localMode")

            // Mono Mode
            val monoMode = presetRepository.isMonoMode()
            fmNative?.setMonoMode(monoMode)
            android.util.Log.d("fytFM", "Applied Mono mode: $monoMode")

            // Radio Area
            val radioArea = presetRepository.getRadioArea()
            fmNative?.setRadioArea(radioArea)
            android.util.Log.d("fytFM", "Applied Radio Area: $radioArea")
        } catch (e: Exception) {
            android.util.Log.e("fytFM", "Error applying tuner settings: ${e.message}")
        }
    }

    /**
     * Tune zu einer Frequenz.
     */
    private fun tuneToFrequency(frequency: Float) {
        android.util.Log.d("fytFM", "Tuning to $frequency MHz")

        try {
            // UI-Frequenz setzen für AF-Vergleich
            rdsManager.setUiFrequency(frequency)
            rdsManager.tune(frequency)
        } catch (e: Throwable) {
            android.util.Log.w("fytFM", "tune failed: ${e.message}")
        }
    }

    /**
     * Seek zum nächsten/vorherigen Sender mit Signal.
     * Manueller Seek da native seek() JNI-Bugs hat.
     */
    private fun seekToStation(seekUp: Boolean) {
        val currentFreq = frequencyScale.getFrequency()
        android.util.Log.i("fytFM", "Seek ${if (seekUp) "UP" else "DOWN"} from $currentFreq MHz")

        // Seek in Background-Thread um UI nicht zu blockieren
        Thread {
            try {
                val step = 0.1f
                val minFreq = 87.5f
                val maxFreq = 108.0f
                val rssiMin = 25    // Minimum RSSI für gültigen Sender
                val rssiMax = 245   // 246-255 = ungültig (Rauschen/nicht eingerastet)

                var freq = if (seekUp) currentFreq + step else currentFreq - step
                var foundFreq: Float? = null
                var attempts = 0
                val maxAttempts = 205  // Ganzes Band durchsuchen

                while (attempts < maxAttempts && foundFreq == null) {
                    // Wrap around
                    if (freq > maxFreq) freq = minFreq
                    if (freq < minFreq) freq = maxFreq

                    // UI aktualisieren um Fortschritt zu zeigen (nur Anzeige, kein Tune)
                    val displayFreq = freq
                    runOnUiThread {
                        tvFrequency.text = "%.1f".format(displayFreq)
                        frequencyScale.setFrequencyVisualOnly(displayFreq)
                    }

                    // Tune und RSSI messen (2x für Stabilität)
                    fmNative.tune(freq)
                    Thread.sleep(100)
                    val rssi1 = fmNative.getrssi()
                    Thread.sleep(50)
                    val rssi2 = fmNative.getrssi()

                    android.util.Log.d("fytFM", "Seek: %.1f MHz -> RSSI %d/%d".format(freq, rssi1, rssi2))

                    // Beide Messungen müssen im gültigen Bereich sein
                    if (rssi1 in rssiMin..rssiMax && rssi2 in rssiMin..rssiMax) {
                        foundFreq = freq
                        android.util.Log.i("fytFM", "Seek found: %.1f MHz (RSSI: %d/%d)".format(freq, rssi1, rssi2))
                    } else {
                        freq = if (seekUp) freq + step else freq - step
                        attempts++
                    }
                }

                // Zurück zum UI-Thread
                runOnUiThread {
                    if (foundFreq != null) {
                        rdsManager.clearRds()
                        rtCombiner?.clearAll()
                        hideNowPlayingBarExplicit()
                        frequencyScale.setFrequency(foundFreq)
                        saveLastFrequency(foundFreq)
                    } else {
                        android.util.Log.w("fytFM", "Seek: No station found")
                        // Zurück zur ursprünglichen Frequenz
                        frequencyScale.setFrequency(currentFreq)
                    }
                }
            } catch (e: Throwable) {
                android.util.Log.e("fytFM", "Seek failed: ${e.message}")
                runOnUiThread {
                    frequencyScale.setFrequency(currentFreq)
                }
            }
        }.start()
    }

    private fun updatePowerButton() {
        // Update power button appearance based on radio state
        btnPower.alpha = if (isRadioOn) 1.0f else 0.5f
    }

    /**
     * Lädt den Favoriten-Filter-Status für den aktuellen Modus (FM/AM)
     */
    private fun loadFavoritesFilterState() {
        showFavoritesOnly = if (frequencyScale.getMode() == FrequencyScaleView.RadioMode.FM) {
            presetRepository.isShowFavoritesOnlyFm()
        } else {
            presetRepository.isShowFavoritesOnlyAm()
        }
        updateFolderButton()
    }

    /**
     * Aktualisiert das Herz-Icon basierend auf dem aktuellen Sender
     */
    private fun updateFavoriteButton() {
        val frequency = frequencyScale.getFrequency()
        val isAM = frequencyScale.getMode() == FrequencyScaleView.RadioMode.AM
        val isFavorite = presetRepository.isFavorite(frequency, isAM)

        val iconRes = if (isFavorite) R.drawable.ic_favorite_filled else R.drawable.ic_favorite_border
        btnFavorite.setImageResource(iconRes)
    }

    /**
     * Aktualisiert das Folder-Icon basierend auf dem Filter-Status
     */
    private fun updateFolderButton() {
        val btnFolder = findViewById<ImageButton>(R.id.btnFolder)
        val iconRes = if (showFavoritesOnly) R.drawable.ic_folder else R.drawable.ic_folder_all
        btnFolder.setImageResource(iconRes)
        tvAllList.text = if (showFavoritesOnly) "Favoriten" else "Alle Sender"
    }

    /**
     * Favorisiert/Unfavorisiert den aktuellen Sender
     */
    private fun toggleCurrentStationFavorite() {
        val frequency = frequencyScale.getFrequency()
        val isAM = frequencyScale.getMode() == FrequencyScaleView.RadioMode.AM

        val isFavoriteNow = presetRepository.toggleFavorite(frequency, isAM)

        // UI aktualisieren
        updateFavoriteButton()
        loadStationsForCurrentMode()

        // Toast anzeigen
        val message = if (isFavoriteNow) {
            "Sender zu Favoriten hinzugefügt"
        } else {
            "Sender aus Favoriten entfernt"
        }
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    /**
     * Schaltet zwischen "Alle Sender" und "Nur Favoriten" um
     */
    private fun toggleFavoritesFilter() {
        showFavoritesOnly = !showFavoritesOnly

        // Filter-Status speichern (FM und AM getrennt)
        if (frequencyScale.getMode() == FrequencyScaleView.RadioMode.FM) {
            presetRepository.setShowFavoritesOnlyFm(showFavoritesOnly)
        } else {
            presetRepository.setShowFavoritesOnlyAm(showFavoritesOnly)
        }

        // UI aktualisieren
        updateFolderButton()
        loadStationsForCurrentMode()

        // Toast anzeigen
        val message = if (showFavoritesOnly) "Nur Favoriten" else "Alle Sender"
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRdsPolling()
        twUtil?.close()
        updateRepository.destroy()
        rdsLogRepository.destroy()
        // Turn off radio when app closes
        if (isRadioOn) {
            fmNative.powerOff()
        }
    }

    private fun saveLastFrequency(frequency: Float) {
        val prefs = getSharedPreferences("fytfm_prefs", Context.MODE_PRIVATE)
        prefs.edit().putFloat("last_frequency", frequency).apply()
    }

    private fun loadLastFrequency(): Float {
        val prefs = getSharedPreferences("fytfm_prefs", Context.MODE_PRIVATE)
        return prefs.getFloat("last_frequency", 90.4f) // Default: 90.4 MHz
    }
}
