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

    // Correction Helper Buttons
    private var nowPlayingCorrectionButtons: View? = null
    private var btnCorrectionRefresh: ImageButton? = null
    private var btnCorrectionTrash: ImageButton? = null
    private var rtCorrectionDao: RtCorrectionDao? = null
    private var editStringDao: EditStringDao? = null

    // Debug: Internet-Simulation deaktivieren
    var debugInternetDisabled = false
        private set

    private lateinit var presetRepository: PresetRepository
    private lateinit var stationAdapter: StationAdapter
    private lateinit var fmNative: FmNative
    private lateinit var rdsManager: RdsManager
    private lateinit var radioScanner: at.planqton.fytfm.scanner.RadioScanner
    private lateinit var updateRepository: UpdateRepository
    private lateinit var rdsLogRepository: RdsLogRepository
    private lateinit var updateBadge: View
    private var settingsUpdateListener: ((UpdateState) -> Unit)? = null
    private var twUtil: TWUtilHelper? = null

    // Spotify Integration
    private var spotifyClient: SpotifyClient? = null
    private var spotifyCache: SpotifyCache? = null
    private var rtCombiner: RtCombiner? = null

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
                    updateSpotifyDebugInfo(status, originalRt, strippedRt, query, trackInfo)
                    updateNowPlaying(trackInfo)
                    nowPlayingRawRt?.text = strippedRt ?: ""
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
                    updateSpotifyDebugInfo(status, originalRt, strippedRt, query, trackInfo)
                    updateNowPlaying(trackInfo)
                    nowPlayingRawRt?.text = strippedRt ?: ""
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

                // Process RT through Spotify integration if available
                val combiner = rtCombiner
                if (combiner != null && !rt.isNullOrBlank()) {
                    val currentFrequency = frequencyScale.getFrequency()
                    CoroutineScope(Dispatchers.IO).launch {
                        val combinedRt = combiner.processRt(pi, rt, currentFrequency)
                        val finalRt = combinedRt ?: rt

                        // Get track info for cover art
                        val trackInfo = combiner.getLastTrackInfo(pi)

                        // Update MediaService with combined RT (must be on Main thread!)
                        withContext(Dispatchers.Main) {
                            val isAM = frequencyScale.getMode() == FrequencyScaleView.RadioMode.AM
                            FytFMMediaService.instance?.updateMetadata(
                                frequency = frequencyScale.getFrequency(),
                                ps = ps,
                                rt = finalRt,
                                isAM = isAM,
                                coverUrl = trackInfo?.coverUrlMedium,  // Spotify URL (300px)
                                localCoverPath = trackInfo?.coverUrl   // Local cached path
                            )
                        }
                    }
                } else {
                    // No Spotify - use raw RT, no cover
                    val isAM = frequencyScale.getMode() == FrequencyScaleView.RadioMode.AM
                    FytFMMediaService.instance?.updateMetadata(
                        frequency = frequencyScale.getFrequency(),
                        ps = ps,
                        rt = rt,
                        isAM = isAM
                    )
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        setPipLayout(!hasFocus)
    }

    private fun setPipLayout(isPip: Boolean) {
        if (isPip) {
            btnFavorite.visibility = View.GONE
            btnPrevStation.visibility = View.GONE
            btnNextStation.visibility = View.GONE
            controlBar.visibility = View.GONE
            findViewById<LinearLayout>(R.id.stationBar)?.visibility = View.GONE
            tvFrequency.textSize = 36f
        } else {
            btnFavorite.visibility = View.VISIBLE
            btnPrevStation.visibility = View.VISIBLE
            btnNextStation.visibility = View.VISIBLE
            controlBar.visibility = View.VISIBLE
            findViewById<LinearLayout>(R.id.stationBar)?.visibility = View.VISIBLE
            tvFrequency.textSize = 99f
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
        }
        checkLayoutInfo?.setOnCheckedChangeListener { _, isChecked ->
            debugLayoutOverlay?.visibility = if (isChecked) View.VISIBLE else View.GONE
            presetRepository.setDebugWindowOpen("layout", isChecked)
            if (isChecked) {
                updateLayoutDebugInfo()
            }
        }
        checkBuildInfo?.setOnCheckedChangeListener { _, isChecked ->
            debugBuildOverlay?.visibility = if (isChecked) View.VISIBLE else View.GONE
            presetRepository.setDebugWindowOpen("build", isChecked)
            if (isChecked) {
                updateBuildDebugInfo()
            }
        }
        checkSpotifyInfo?.setOnCheckedChangeListener { _, isChecked ->
            debugSpotifyOverlay?.visibility = if (isChecked) View.VISIBLE else View.GONE
            presetRepository.setDebugWindowOpen("spotify", isChecked)
        }
        checkDebugButtons?.setOnCheckedChangeListener { _, isChecked ->
            debugButtonsOverlay?.visibility = if (isChecked) View.VISIBLE else View.GONE
            presetRepository.setDebugWindowOpen("buttons", isChecked)
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
    }

    // Methode zum Aktualisieren der Spotify Debug-Anzeige
    fun updateSpotifyDebugInfo(status: String, originalRt: String?, strippedRt: String?, query: String?, trackInfo: TrackInfo?) {
        if (debugSpotifyOverlay?.visibility != View.VISIBLE) return

        // Status und Input immer setzen
        findViewById<TextView>(R.id.debugSpotifyStatus)?.text = status
        // Show both original and stripped RT
        val rtDisplay = if (originalRt != null && strippedRt != null) {
            "$originalRt\n→ $strippedRt"
        } else {
            originalRt ?: strippedRt ?: "--"
        }
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

        // Prüfen ob sich der Track geändert hat
        val newTrackId = trackInfo.trackId
        if (newTrackId == lastDebugTrackId) {
            return // Gleicher Track, keine Aktualisierung der Track-Details nötig
        }
        lastDebugTrackId = newTrackId

        // Determine source based on status
        val isFromLocalCache = status.contains("Cached", ignoreCase = true) ||
                               status.contains("offline", ignoreCase = true)
        val isFromSpotifyOnline = status == "Found!"

        if (trackInfo != null) {
            // Source Header
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
            // Update text
            nowPlayingArtist?.text = trackInfo.artist
            nowPlayingTitle?.text = trackInfo.title

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
                nowPlayingCover?.setImageResource(android.R.drawable.ic_menu_gallery)
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
        stationAdapter = StationAdapter { station ->
            // Tune to selected station
            if (station.isAM) {
                frequencyScale.setMode(FrequencyScaleView.RadioMode.AM)
            } else {
                frequencyScale.setMode(FrequencyScaleView.RadioMode.FM)
            }
            frequencyScale.setFrequency(station.frequency)
            stationAdapter.setSelectedFrequency(station.frequency)
        }

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
            // Update MediaService with new frequency (RDS callback will update PS/RT later)
            FytFMMediaService.instance?.updateMetadata(frequency, null, null, isAM)
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

        // Radio Area item (opens selection dialog)
        val textRadioAreaValue = dialogView.findViewById<TextView>(R.id.textRadioAreaValue)
        textRadioAreaValue.text = getRadioAreaName(presetRepository.getRadioArea())
        dialogView.findViewById<View>(R.id.itemRadioArea).setOnClickListener {
            showRadioAreaDialog { selectedArea ->
                presetRepository.setRadioArea(selectedArea)
                fmNative?.setRadioArea(selectedArea)
                textRadioAreaValue.text = getRadioAreaName(selectedArea)
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

        // Click handlers
        dialogView.findViewById<View>(R.id.itemAreaUSA).setOnClickListener {
            onSelected(0)
            dialog.dismiss()
        }
        dialogView.findViewById<View>(R.id.itemAreaLatinAmerica).setOnClickListener {
            onSelected(1)
            dialog.dismiss()
        }
        dialogView.findViewById<View>(R.id.itemAreaEurope).setOnClickListener {
            onSelected(2)
            dialog.dismiss()
        }
        dialogView.findViewById<View>(R.id.itemAreaRussia).setOnClickListener {
            onSelected(3)
            dialog.dismiss()
        }
        dialogView.findViewById<View>(R.id.itemAreaJapan).setOnClickListener {
            onSelected(4)
            dialog.dismiss()
        }

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
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
