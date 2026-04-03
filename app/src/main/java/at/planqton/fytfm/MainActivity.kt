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
import at.planqton.fytfm.deezer.DeezerClient
import at.planqton.fytfm.deezer.DeezerCache
import at.planqton.fytfm.deezer.RtCombiner
import at.planqton.fytfm.deezer.TrackInfo
import at.planqton.fytfm.steering.SteeringWheelKeyManager
import at.planqton.fytfm.steering.SyuToolkitManager
import at.planqton.fytfm.dab.DabTunerManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
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
    private lateinit var spinnerRadioMode: Spinner
    private lateinit var btnPower: ImageButton
    private val dabTunerManager = DabTunerManager()
    private var isDabOn = false
    private var currentDabServiceId = 0
    private var currentDabEnsembleId = 0
    private var currentDabServiceLabel: String? = null
    private var currentDabEnsembleLabel: String? = null
    private var currentDabDls: String? = null
    private var currentDabSlideshow: android.graphics.Bitmap? = null
    private var suppressSpinnerCallback = false
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
    // Debug labels (for changing FM/DAB terminology)
    private var debugPsLabel: TextView? = null
    private var debugPiLabel: TextView? = null
    private var debugPtyLabel: TextView? = null
    private var debugRtLabel: TextView? = null
    private var debugRssiLabel: TextView? = null
    private var debugAfLabel: TextView? = null
    private var debugAf: TextView? = null
    private var debugAfUsing: TextView? = null
    private var debugTpTa: TextView? = null
    private var checkRdsInfo: CheckBox? = null
    private var checkLayoutInfo: CheckBox? = null
    private var checkBuildInfo: CheckBox? = null
    private var checkDeezerInfo: CheckBox? = null
    private var debugLayoutOverlay: View? = null
    private var debugBuildOverlay: View? = null
    private var debugDeezerOverlay: View? = null
    private var debugScreenInfo: TextView? = null
    private var debugDensityInfo: TextView? = null
    private var checkDebugButtons: CheckBox? = null
    private var checkSwcInfo: CheckBox? = null
    private var checkCarouselInfo: CheckBox? = null
    private var checkStationOverlayDebug: CheckBox? = null
    private var debugStationOverlay: View? = null
    private var checkStationOverlayPermanent: CheckBox? = null
    private var debugStationOverlayStatus: TextView? = null
    private var debugStationOverlayCount: TextView? = null
    private var debugButtonsOverlay: View? = null
    private var debugSwcOverlay: View? = null
    private var debugSwcLog: TextView? = null
    private val swcLogEntries = mutableListOf<String>()
    private var debugCarouselOverlay: View? = null
    private var debugCarouselTimer: TextView? = null
    private var debugCarouselActiveTile: TextView? = null
    private var debugCarouselPosition: TextView? = null
    private var debugCarouselPadding: TextView? = null

    // Tuner Info Debug
    private var checkTunerInfo: CheckBox? = null
    private var debugTunerOverlay: View? = null
    private var debugTunerActive: TextView? = null
    private var debugTunerFm: TextView? = null
    private var debugTunerAm: TextView? = null
    private var debugTunerDab: TextView? = null
    private var tunerInfoUpdateHandler: android.os.Handler? = null
    private var tunerInfoUpdateRunnable: Runnable? = null

    // Steering Wheel Key Handler
    private var steeringWheelKeyManager: SteeringWheelKeyManager? = null
    private var syuToolkitManager: SyuToolkitManager? = null

    // Preset Import via SYU Service Callbacks
    private var isCollectingPresets = false
    private val collectedPresets = mutableMapOf<Int, Pair<Float, String?>>()  // index -> (freq, name)
    private val presetImportHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var presetImportTimeoutRunnable: Runnable? = null

    // Now Playing Bar
    private var nowPlayingBar: View? = null
    private var nowPlayingCover: ImageView? = null
    private var nowPlayingPs: TextView? = null
    private var nowPlayingArtist: TextView? = null
    private var nowPlayingTitle: TextView? = null
    private var nowPlayingRawRt: TextView? = null
    private var lastDisplayedTrackId: String? = null
    private var psTapCount = 0
    private var lastPsTapTime = 0L
    private var lastDebugTrackId: String? = null

    // Carousel Now Playing Bar
    private var carouselNowPlayingBar: View? = null
    private var carouselNowPlayingCover: ImageView? = null
    private var carouselNowPlayingPs: TextView? = null
    private var carouselNowPlayingArtist: TextView? = null
    private var carouselNowPlayingTitle: TextView? = null
    private var carouselNowPlayingRawRt: TextView? = null

    // Ignored RT indicators
    private var nowPlayingIgnoredIndicator: View? = null
    private var carouselIgnoredIndicator: View? = null

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
    private var carouselCorrectionButtons: View? = null
    private var btnCarouselCorrectionRefresh: ImageButton? = null
    private var btnCarouselCorrectionTrash: ImageButton? = null
    private var btnDeezerToggle: ImageView? = null
    private var btnCarouselDeezerToggle: ImageView? = null
    private var rtCorrectionDao: RtCorrectionDao? = null
    private var editStringDao: EditStringDao? = null

    // View Mode Toggle (Equalizer vs Image/Carousel)
    private var mainContentArea: View? = null
    private var carouselContentArea: View? = null
    private var btnViewModeEqualizer: View? = null
    private var btnViewModeImage: View? = null
    private var stationCarousel: RecyclerView? = null
    private var carouselFrequencyLabel: TextView? = null
    private var btnCarouselFavorite: ImageButton? = null
    private var stationCarouselAdapter: at.planqton.fytfm.ui.StationCarouselAdapter? = null
    private var isCarouselMode = false
    private var carouselNeedsInitialScroll = true  // Flag for first scroll after app start
    private var isAppInForeground = false

    // Carousel auto-centering
    private val carouselCenterHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var carouselCenterRunnable: Runnable? = null
    private var carouselReturnRunnable: Runnable? = null  // For auto-return after manual scroll
    private var carouselCenterTimerStart: Long = 0
    private var carouselPendingCenterPosition: Int = -1

    // Debug: Internet-Simulation deaktivieren
    var debugInternetDisabled = false
        private set

    // Debug: Spotify/Local blockieren (nur RDS anzeigen)
    var debugDeezerBlocked = false
        private set

    private lateinit var presetRepository: PresetRepository
    private lateinit var stationAdapter: StationAdapter
    private lateinit var fmNative: FmNative
    private lateinit var rdsManager: RdsManager
    private val lastSyncedPs = mutableMapOf<Int, String>()  // FreqKey -> letzter gesyncter PS
    private lateinit var radioScanner: at.planqton.fytfm.scanner.RadioScanner
    private lateinit var updateRepository: UpdateRepository
    private lateinit var rdsLogRepository: RdsLogRepository
    private lateinit var radioLogoRepository: at.planqton.fytfm.data.logo.RadioLogoRepository
    private lateinit var updateBadge: View
    private var settingsUpdateListener: ((UpdateState) -> Unit)? = null
    private var twUtil: TWUtilHelper? = null

    // Deezer Integration
    private var deezerClient: DeezerClient? = null
    private var deezerCache: DeezerCache? = null
    private var rtCombiner: RtCombiner? = null

    // RDS UI Update Cache - Verhindert unnötige UI-Updates wenn sich nichts geändert hat
    private val lastDisplayedRt = mutableMapOf<Int, String>()  // PI -> letztes angezeigtes RT

    // Bug Report: Aktuelle Deezer-Status-Daten
    private var currentDeezerStatus: String? = null
    private var currentDeezerOriginalRt: String? = null
    private var currentDeezerStrippedRt: String? = null
    private var currentDeezerQuery: String? = null
    private var currentDeezerTrackInfo: TrackInfo? = null

    // Deezer Cache Export/Import launchers
    private val deezerCacheExportLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                exportDeezerCacheToUri(uri)
            }
        }
    }

    private val deezerCacheImportLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                importDeezerCacheFromUri(uri)
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
        FmNative.initAudio(this) // Audio-Routing initialisieren
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
        initDeezerIntegration()

        // Start Overlay Service for steering wheel controls
        startOverlayServiceIfEnabled()

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

        // Load last radio mode BEFORE setupViewModeToggle populates carousel
        val lastMode = loadLastRadioMode()
        android.util.Log.i("fytFM", "=== APP START: loadLastRadioMode() returned $lastMode ===")

        // Nur Info-Toast wenn Tuner nicht verfügbar (blockiert nicht)
        if (!isTunerAvailable(lastMode)) {
            android.widget.Toast.makeText(this, "Tuner für $lastMode nicht verfügbar", android.widget.Toast.LENGTH_SHORT).show()
        }

        frequencyScale.setMode(lastMode)
        android.util.Log.i("fytFM", "=== APP START: frequencyScale.getMode() is now ${frequencyScale.getMode()} ===")

        // Start MediaService for Car Launcher integration
        startMediaService()

        // Initialize Steering Wheel Key Handler for FYT devices
        initSteeringWheelKeys()

        // Load last frequency from SharedPreferences (nur für FM/AM relevant)
        val lastFreq = loadLastFrequency()
        if (lastMode != FrequencyScaleView.RadioMode.DAB) {
            frequencyScale.setFrequency(lastFreq)
            rdsManager.setUiFrequency(lastFreq)  // Für AF-Vergleich
            rdsLogRepository.setInitialFrequency(lastFreq, lastMode == FrequencyScaleView.RadioMode.AM)
            updateFrequencyDisplay(lastFreq)
        }
        updateModeSpinner()
        loadFavoritesFilterState()
        loadStationsForCurrentMode()

        // Carousel mit richtigem Modus befüllen (Modus ist jetzt bekannt)
        if (lastMode == FrequencyScaleView.RadioMode.DAB) {
            // Letzten DAB-Service laden
            val (lastServiceId, lastEnsembleId) = loadLastDabService()
            currentDabServiceId = lastServiceId
            currentDabEnsembleId = lastEnsembleId
            android.util.Log.i("fytFM", "=== APP START DAB: currentDabServiceId=$currentDabServiceId ===")

            if (!isCarouselMode) {
                setViewMode(true)
            } else {
                populateCarousel()
                updateCarouselSelection()
            }
            findViewById<View>(R.id.viewModeToggle)?.visibility = View.GONE
            // DAB-Anzeige initialisieren (Sendername statt Frequenz)
            initDabDisplay()
            // DAB-Tuner automatisch einschalten wenn in Settings aktiviert
            if (!isDabOn && presetRepository.isPowerOnStartupDab()) {
                toggleDabPower()
            }
        } else if (isCarouselMode) {
            // FM/AM mit Carousel-Modus
            populateCarousel()
            updateCarouselSelection()
        }

        // Check if user wants to import stations from original app (via SYU callbacks)
        checkAndOfferStationImport()

        updatePowerButton()
        updateFavoriteButton()

        // Initialize MediaSession with current station immediately after service is ready
        val isAM = frequencyScale.getMode() == FrequencyScaleView.RadioMode.AM
        val stations = if (isAM) presetRepository.loadAmStations() else presetRepository.loadFmStations()
        val savedStation = stations.find { Math.abs(it.frequency - lastFreq) < 0.05f }
        val savedStationName = savedStation?.name
        val radioLogoPath = radioLogoRepository.getLogoForStation(savedStationName, null, lastFreq)
        android.util.Log.d("fytFM", "Initial MediaSession: freq=$lastFreq, stationName=$savedStationName, radioLogoPath=$radioLogoPath")
        // Delay slightly to ensure MediaService is ready
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            FytFMMediaService.instance?.updateMetadata(
                frequency = lastFreq,
                ps = savedStationName,
                rt = null,
                isAM = isAM,
                coverUrl = null,
                localCoverPath = null,
                radioLogoPath = radioLogoPath
            )
        }, 500)

        // Auto power on if enabled in settings (mode-specific)
        if (!isRadioOn && lastMode != FrequencyScaleView.RadioMode.DAB) {
            val shouldPowerOn = when (lastMode) {
                FrequencyScaleView.RadioMode.FM -> presetRepository.isPowerOnStartupFm()
                FrequencyScaleView.RadioMode.AM -> presetRepository.isPowerOnStartupAm()
                else -> false
            }
            if (shouldPowerOn) {
                toggleRadioPower()
            }
        }

        // Debug DAB test removed
    }

    /**
     * Initialisiert Lenkradtasten-Handler für FYT-Geräte.
     * Verwendet den FYT-spezifischen SYU Toolkit Service für direkte Key-Events.
     */
    private fun initSteeringWheelKeys() {
        // Primary method: SYU Toolkit Service (like navradio uses)
        syuToolkitManager = SyuToolkitManager(
            context = this,
            listener = object : SyuToolkitManager.KeyEventListener {
                override fun onNextPressed() {
                    android.util.Log.i("fytFM", "SYU onNextPressed called! isRadioOn=$isRadioOn")
                    runOnUiThread {
                        // Always allow station changes via steering wheel
                        skipToNextStation()
                    }
                }

                override fun onPrevPressed() {
                    android.util.Log.i("fytFM", "SYU onPrevPressed called! isRadioOn=$isRadioOn")
                    runOnUiThread {
                        // Always allow station changes via steering wheel
                        skipToPreviousStation()
                    }
                }

                override fun onPlayPausePressed() {
                    runOnUiThread {
                        toggleRadioPower()
                    }
                }

                override fun onVolumeUp() {
                    // Volume handled by system
                }

                override fun onVolumeDown() {
                    // Volume handled by system
                }

                override fun onKeyEvent(keyCode: Int, intData: IntArray?) {
                    android.util.Log.d("fytFM", "SYU: Raw key event: keyCode=$keyCode, intData=${intData?.toList()}")
                    val keyName = getKeyName(keyCode)
                    val display = if (keyName != null) "SYU: $keyCode ($keyName)" else "SYU: $keyCode"
                    runOnUiThread { logSwcEvent(display) }
                }

                override fun onFrequencyUpdate(frequencyKhz: Int) {
                    // Ignoriert - fytFM nutzt eigene Presets statt com.syu.music Frequenz-Sync
                    val frequencyMhz = frequencyKhz / 100.0f
                    android.util.Log.d("fytFM", "SYU: Frequency update ignoriert: $frequencyMhz MHz")
                }

                override fun onRawCallback(type: Int, intData: IntArray?, floatData: FloatArray?, stringData: Array<String>?) {
                    // Debug: Log all raw callbacks to discover event types
                    android.util.Log.d("fytFM", "SYU RAW: type=$type (0x${type.toString(16)}), intData=${intData?.toList()}, floatData=${floatData?.toList()}, stringData=${stringData?.toList()}")
                    runOnUiThread {
                        logSwcEvent("SYU RAW type=$type int=${intData?.firstOrNull()}")
                    }
                }

                override fun onPresetReceived(index: Int, frequencyMhz: Float, isAM: Boolean) {
                    if (!isAM && isCollectingPresets && frequencyMhz in 87.5f..108.0f) {
                        android.util.Log.i("fytFM", "Import: Preset empfangen - index=$index, freq=$frequencyMhz MHz")
                        runOnUiThread {
                            val existing = collectedPresets[index]
                            collectedPresets[index] = Pair(frequencyMhz, existing?.second)
                        }
                    }
                }

                override fun onPresetNameReceived(index: Int, name: String, isAM: Boolean) {
                    if (!isAM && isCollectingPresets) {
                        android.util.Log.i("fytFM", "Import: Preset-Name empfangen - index=$index, name='$name'")
                        runOnUiThread {
                            val existing = collectedPresets[index]
                            if (existing != null) {
                                collectedPresets[index] = Pair(existing.first, name)
                            }
                        }
                    }
                }
            }
        )
        syuToolkitManager?.connect()
        android.util.Log.i("fytFM", "SYU Toolkit Manager connecting...")

        // Fallback method: DISABLED - using SyuToolkitManager only
        /*
        steeringWheelKeyManager = SteeringWheelKeyManager(
            context = this,
            listener = object : SteeringWheelKeyManager.KeyEventListener {
                override fun onNextPressed() {
                    runOnUiThread {
                        if (isRadioOn) skipToNextStation()
                    }
                }

                override fun onPrevPressed() {
                    runOnUiThread {
                        if (isRadioOn) skipToPreviousStation()
                    }
                }

                override fun onPlayPausePressed() {
                    runOnUiThread {
                        toggleRadioPower()
                    }
                }

                override fun onVolumeUp() {
                    // Volume handled by system
                }

                override fun onVolumeDown() {
                    // Volume handled by system
                }

                override fun onRawKeyEvent(keyCode: Int, keyName: String?) {
                    val display = if (keyName != null) "BC: $keyCode ($keyName)" else "BC: $keyCode"
                    runOnUiThread { logSwcEvent(display) }
                }
            }
        )
        steeringWheelKeyManager?.register()

        if (steeringWheelKeyManager?.isServiceAvailable() == true) {
            val method = if (steeringWheelKeyManager?.isUtilServiceAvailable() == true) "util_service" else "broadcast"
            android.util.Log.i("fytFM", "Steering wheel broadcast receiver registered (via $method)")
        }
        */
    }

    /**
     * Startet den Overlay-Service wenn Setting aktiviert und Permission vorhanden
     */
    private fun startOverlayServiceIfEnabled() {
        if (!presetRepository.isShowStationChangeToast()) return

        // Prüfe Overlay-Permission
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            if (!android.provider.Settings.canDrawOverlays(this)) {
                android.util.Log.w("fytFM", "Overlay permission not granted")
                return
            }
        }

        try {
            val intent = android.content.Intent(this, StationChangeOverlayService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            android.util.Log.i("fytFM", "StationChangeOverlayService started")
        } catch (e: Exception) {
            android.util.Log.e("fytFM", "Failed to start overlay service: ${e.message}")
        }
    }

    /**
     * Initialisiert Deezer Client und RT Combiner
     * Deezer benötigt keine API-Credentials!
     */
    private fun initDeezerIntegration() {
        // Always create cache (for offline fallback)
        if (deezerCache == null) {
            deezerCache = DeezerCache(this)
        }

        // Initialize correction DAOs
        if (rtCorrectionDao == null) {
            rtCorrectionDao = RdsDatabase.getInstance(this).rtCorrectionDao()
        }
        if (editStringDao == null) {
            editStringDao = RdsDatabase.getInstance(this).editStringDao()
        }

        // Deezer doesn't need credentials - just create the client
        deezerClient = DeezerClient()
        rtCombiner = RtCombiner(
            deezerClient = deezerClient,
            deezerCache = deezerCache,
            isCacheEnabled = { presetRepository.isDeezerCacheEnabled() },
            isNetworkAvailable = { isNetworkAvailable() },
            correctionDao = rtCorrectionDao,
            editStringDao = editStringDao
        ) { status, originalRt, strippedRt, query, trackInfo ->
            runOnUiThread {
                // Store for bug reports
                currentDeezerStatus = status
                currentDeezerOriginalRt = originalRt
                currentDeezerStrippedRt = strippedRt
                currentDeezerQuery = query
                currentDeezerTrackInfo = trackInfo
                updateDeezerDebugInfo(status, originalRt, strippedRt, query, trackInfo)
                // Bei keinem Match: Raw RT parsen und anzeigen
                val displayInfo = trackInfo ?: strippedRt?.let { parseRawRtToTrackInfo(it) }
                updateNowPlaying(displayInfo)
                nowPlayingRawRt?.text = strippedRt ?: ""
                carouselNowPlayingRawRt?.text = strippedRt ?: ""
                updateIgnoredIndicator(strippedRt)
                updatePipDisplay()
            }
        }
        android.util.Log.i("fytFM", "Deezer integration initialized")
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Reinitialize Deezer integration
     */
    fun reinitDeezerIntegration() {
        rtCombiner?.destroy()
        initDeezerIntegration()
    }

    /**
     * Startet das RDS-Polling mit Callback für PS, RT, RSSI, PI, PTY, TP, TA, AF.
     */
    private fun startRdsPolling() {
        rdsManager.startPolling(object : RdsManager.RdsCallback {
            override fun onRdsUpdate(ps: String?, rt: String?, rssi: Int, pi: Int, pty: Int, tp: Int, ta: Int, afList: ShortArray?) {
                // Log RDS data (only on RT change)
                rdsLogRepository.onRdsUpdate(ps, rt, pi, pty, tp, ta, rssi, afList)

                // Process RT through Deezer integration if available (unless blocked or disabled for FM)
                val combiner = rtCombiner
                val currentFrequency = frequencyScale.getFrequency()
                val deezerEnabled = presetRepository.isDeezerEnabledFm()
                if (combiner != null && !rt.isNullOrBlank() && !debugDeezerBlocked && deezerEnabled) {
                    CoroutineScope(Dispatchers.IO).launch {
                        val combinedRt = combiner.processRt(pi, rt, currentFrequency)
                        val finalRt = combinedRt ?: rt

                        // Get track info for cover art
                        // Only use trackInfo if we actually found a match for the current song
                        // (combinedRt is non-null only when Spotify found a match)
                        val trackInfo = if (combinedRt != null) combiner.getLastTrackInfo(pi) else null

                        // Update MediaService with combined RT (must be on Main thread!)
                        withContext(Dispatchers.Main) {
                            // Skip UI update if nothing has changed
                            val previousRt = lastDisplayedRt[pi]
                            val currentTrackId = trackInfo?.trackId
                            if (finalRt == previousRt && currentTrackId == lastDisplayedTrackId) {
                                return@withContext
                            }
                            lastDisplayedRt[pi] = finalRt
                            lastDisplayedTrackId = currentTrackId

                            val isAM = frequencyScale.getMode() == FrequencyScaleView.RadioMode.AM
                            val currentFreq = frequencyScale.getFrequency()
                            val radioLogoPath = radioLogoRepository.getLogoForStation(ps, pi, currentFreq)
                            // Lokalen Cover-Pfad aus Cache holen (zuverlässiger als trackInfo.coverUrl)
                            val localCover = deezerCache?.getLocalCoverPath(trackInfo?.trackId)
                                ?: trackInfo?.coverUrl?.takeIf { it.startsWith("/") }
                            FytFMMediaService.instance?.updateMetadata(
                                frequency = currentFreq,
                                ps = ps,
                                rt = finalRt,
                                isAM = isAM,
                                coverUrl = trackInfo?.coverUrlMedium,  // Spotify URL (300px)
                                localCoverPath = localCover,           // Local cached path
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
                        carouselNowPlayingRawRt?.text = rt
                        updateIgnoredIndicator(rt)
                        updatePipDisplay()
                    }
                }

                runOnUiThread {
                    // Auto-Sync PS zu Preset wenn syncName aktiviert ist
                    if (!ps.isNullOrBlank()) {
                        val currentFreq = frequencyScale.getFrequency()
                        val freqKey = (currentFreq * 10).toInt()
                        val lastPs = lastSyncedPs[freqKey]

                        // Nur synchen wenn PS sich geändert hat
                        if (lastPs != ps) {
                            val isAM = frequencyScale.getMode() == FrequencyScaleView.RadioMode.AM
                            val stations = if (isAM) presetRepository.loadAmStations() else presetRepository.loadFmStations()
                            val currentStation = stations.find { Math.abs(it.frequency - currentFreq) < 0.05f }

                            // Sync wenn: Station existiert UND (syncName aktiv ODER name leer)
                            if (currentStation != null && (currentStation.syncName || currentStation.name.isNullOrBlank())) {
                                // Nur speichern wenn Name tatsächlich anders ist
                                if (currentStation.name != ps) {
                                    val updatedStations = stations.map {
                                        if (Math.abs(it.frequency - currentFreq) < 0.05f) {
                                            it.copy(name = ps)
                                        } else it
                                    }
                                    if (isAM) {
                                        presetRepository.saveAmStations(updatedStations)
                                    } else {
                                        presetRepository.saveFmStations(updatedStations)
                                    }
                                    loadStationsForCurrentMode()
                                    android.util.Log.i("MainActivity", "Synced PS '$ps' to preset %.1f".format(currentFreq))
                                }
                            }
                            // PS als gesynct markieren
                            lastSyncedPs[freqKey] = ps
                        }
                    }

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
        isAppInForeground = true
        // Re-check PiP mode when resuming (wichtig für Start im kleinen Fenster)
        recheckPipMode("onResume")
    }

    override fun onPause() {
        super.onPause()
        isAppInForeground = false
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
            debugDeezerOverlay?.visibility = View.GONE
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
            debugDeezerOverlay?.visibility = if (checkDeezerInfo?.isChecked == true) View.VISIBLE else View.GONE
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
        spinnerRadioMode = findViewById(R.id.spinnerRadioMode)
        setupRadioModeSpinner()
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
        // Debug labels
        debugPsLabel = findViewById(R.id.labelPs)
        debugPiLabel = findViewById(R.id.labelPi)
        debugPtyLabel = findViewById(R.id.labelPty)
        debugRtLabel = findViewById(R.id.labelRt)
        debugRssiLabel = findViewById(R.id.labelRssi)
        debugAfLabel = findViewById(R.id.labelAf)
        debugAfUsing = findViewById(R.id.debugAfUsing)
        debugTpTa = findViewById(R.id.debugTpTa)
        checkRdsInfo = findViewById(R.id.checkRdsInfo)
        checkLayoutInfo = findViewById(R.id.checkLayoutInfo)
        checkBuildInfo = findViewById(R.id.checkBuildInfo)
        checkDeezerInfo = findViewById(R.id.checkDeezerInfo)
        debugLayoutOverlay = findViewById(R.id.debugLayoutOverlay)
        debugBuildOverlay = findViewById(R.id.debugBuildOverlay)
        debugDeezerOverlay = findViewById(R.id.debugDeezerOverlay)
        debugScreenInfo = findViewById(R.id.debugScreenInfo)
        debugDensityInfo = findViewById(R.id.debugDensityInfo)
        checkDebugButtons = findViewById(R.id.checkDebugButtons)
        checkSwcInfo = findViewById(R.id.checkSwcInfo)
        checkCarouselInfo = findViewById(R.id.checkCarouselInfo)
        checkStationOverlayDebug = findViewById(R.id.checkStationOverlayDebug)
        debugStationOverlay = findViewById(R.id.debugStationOverlay)
        checkStationOverlayPermanent = findViewById(R.id.checkStationOverlayPermanent)
        debugStationOverlayStatus = findViewById(R.id.debugStationOverlayStatus)
        debugStationOverlayCount = findViewById(R.id.debugStationOverlayCount)
        debugButtonsOverlay = findViewById(R.id.debugButtonsOverlay)
        debugSwcOverlay = findViewById(R.id.debugSwcOverlay)
        debugSwcLog = findViewById(R.id.debugSwcLog)
        debugCarouselOverlay = findViewById(R.id.debugCarouselOverlay)
        debugCarouselTimer = findViewById(R.id.debugCarouselTimer)
        debugCarouselActiveTile = findViewById(R.id.debugCarouselActiveTile)
        debugCarouselPosition = findViewById(R.id.debugCarouselPosition)
        debugCarouselPadding = findViewById(R.id.debugCarouselPadding)

        // Tuner Info Debug
        checkTunerInfo = findViewById(R.id.checkTunerInfo)
        debugTunerOverlay = findViewById(R.id.debugTunerOverlay)
        debugTunerActive = findViewById(R.id.debugTunerActive)
        debugTunerFm = findViewById(R.id.debugTunerFm)
        debugTunerAm = findViewById(R.id.debugTunerAm)
        debugTunerDab = findViewById(R.id.debugTunerDab)

        // Now Playing Bar
        nowPlayingBar = findViewById(R.id.nowPlayingBar)
        nowPlayingCover = findViewById(R.id.nowPlayingCover)
        nowPlayingPs = findViewById(R.id.nowPlayingPs)
        nowPlayingArtist = findViewById(R.id.nowPlayingArtist)
        nowPlayingTitle = findViewById(R.id.nowPlayingTitle)
        nowPlayingRawRt = findViewById(R.id.nowPlayingRawRt)

        // Carousel Now Playing Bar
        carouselNowPlayingBar = findViewById(R.id.carouselNowPlayingBar)
        carouselNowPlayingCover = findViewById(R.id.carouselNowPlayingCover)
        carouselNowPlayingPs = findViewById(R.id.carouselNowPlayingPs)
        carouselNowPlayingArtist = findViewById(R.id.carouselNowPlayingArtist)
        carouselNowPlayingTitle = findViewById(R.id.carouselNowPlayingTitle)
        carouselNowPlayingRawRt = findViewById(R.id.carouselNowPlayingRawRt)

        // Ignored RT indicators
        nowPlayingIgnoredIndicator = findViewById(R.id.nowPlayingIgnoredIndicator)
        carouselIgnoredIndicator = findViewById(R.id.carouselIgnoredIndicator)

        // PS triple-tap to rename station
        val psTapListener = View.OnClickListener {
            handlePsTripleTap()
        }
        nowPlayingPs?.setOnClickListener(psTapListener)
        carouselNowPlayingPs?.setOnClickListener(psTapListener)

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
        // Carousel versions
        carouselCorrectionButtons = findViewById(R.id.carouselCorrectionButtons)
        btnCarouselCorrectionRefresh = findViewById(R.id.btnCarouselCorrectionRefresh)
        btnCarouselCorrectionTrash = findViewById(R.id.btnCarouselCorrectionTrash)
        // Spotify Toggles
        btnDeezerToggle = findViewById(R.id.btnDeezerToggle)
        btnCarouselDeezerToggle = findViewById(R.id.btnCarouselDeezerToggle)
        setupDeezerToggle()
        setupCorrectionHelpers()

        // View Mode Toggle
        mainContentArea = findViewById(R.id.mainContentArea)
        carouselContentArea = findViewById(R.id.carouselContentArea)
        btnViewModeEqualizer = findViewById(R.id.btnViewModeEqualizer)
        btnViewModeImage = findViewById(R.id.btnViewModeImage)
        stationCarousel = findViewById(R.id.stationCarousel)
        carouselFrequencyLabel = findViewById(R.id.carouselFrequencyLabel)
        btnCarouselFavorite = findViewById(R.id.btnCarouselFavorite)
        setupViewModeToggle()
        // NOTE: Carousel wird später in onCreate befüllt, NACH loadLastRadioMode()

        setupDebugOverlayDrag()
        setupDebugBuildOverlayDrag()
        setupDebugLayoutOverlayDrag()
        setupDebugDeezerOverlayDrag()
        setupDebugButtonsOverlayDrag()
        setupDebugSwcOverlayDrag()
        setupDebugCarouselOverlayDrag()
        setupDebugStationOverlayDrag()
        setupDebugTunerOverlayDrag()
        setupDebugChecklistDrag()
        setupDebugChecklistListeners()
        setupDebugButtonsListeners()
        setupDebugOverlayAlphaSliders()
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
                // Set current frequency immediately when showing overlay
                debugFreq?.text = String.format("%.1f MHz", frequencyScale.getFrequency())
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
        checkDeezerInfo?.setOnCheckedChangeListener { _, isChecked ->
            debugDeezerOverlay?.visibility = if (isChecked) View.VISIBLE else View.GONE
            presetRepository.setDebugWindowOpen("spotify", isChecked)
            if (isChecked) {
                debugDeezerOverlay?.post { restoreDebugWindowPosition("spotify", debugDeezerOverlay) }
            }
        }
        checkDebugButtons?.setOnCheckedChangeListener { _, isChecked ->
            debugButtonsOverlay?.visibility = if (isChecked) View.VISIBLE else View.GONE
            presetRepository.setDebugWindowOpen("buttons", isChecked)
            if (isChecked) {
                debugButtonsOverlay?.post { restoreDebugWindowPosition("buttons", debugButtonsOverlay) }
            }
        }
        checkSwcInfo?.setOnCheckedChangeListener { _, isChecked ->
            debugSwcOverlay?.visibility = if (isChecked) View.VISIBLE else View.GONE
            presetRepository.setDebugWindowOpen("swc", isChecked)
            if (isChecked) {
                debugSwcOverlay?.post { restoreDebugWindowPosition("swc", debugSwcOverlay) }
            }
        }
        checkCarouselInfo?.setOnCheckedChangeListener { _, isChecked ->
            debugCarouselOverlay?.visibility = if (isChecked) View.VISIBLE else View.GONE
            presetRepository.setDebugWindowOpen("carousel", isChecked)
            if (isChecked) {
                debugCarouselOverlay?.post { restoreDebugWindowPosition("carousel", debugCarouselOverlay) }
            }
        }
        checkStationOverlayDebug?.setOnCheckedChangeListener { _, isChecked ->
            debugStationOverlay?.visibility = if (isChecked) View.VISIBLE else View.GONE
            presetRepository.setDebugWindowOpen("stationoverlay", isChecked)
            if (isChecked) {
                debugStationOverlay?.post { restoreDebugWindowPosition("stationoverlay", debugStationOverlay) }
                // Update station count (only if adapter is initialized)
                if (::stationAdapter.isInitialized) {
                    debugStationOverlayCount?.text = stationAdapter.getStations().size.toString()
                }
            }
        }
        checkStationOverlayPermanent?.setOnCheckedChangeListener { _, isChecked ->
            presetRepository.setPermanentStationOverlay(isChecked)
            if (isChecked) {
                showPermanentStationOverlay()
                debugStationOverlayStatus?.text = "visible"
            } else {
                hidePermanentStationOverlay()
                debugStationOverlayStatus?.text = "hidden"
            }
        }
        checkTunerInfo?.setOnCheckedChangeListener { _, isChecked ->
            debugTunerOverlay?.visibility = if (isChecked) View.VISIBLE else View.GONE
            presetRepository.setDebugWindowOpen("tuner", isChecked)
            if (isChecked) {
                debugTunerOverlay?.post { restoreDebugWindowPosition("tuner", debugTunerOverlay) }
                startTunerInfoUpdates()
            } else {
                stopTunerInfoUpdates()
            }
        }
    }

    private fun restoreDebugWindowStates() {
        // Restore checkbox states (this will trigger the listeners which set visibility)
        checkRdsInfo?.isChecked = presetRepository.isDebugWindowOpen("rds", false)
        checkLayoutInfo?.isChecked = presetRepository.isDebugWindowOpen("layout", false)
        checkBuildInfo?.isChecked = presetRepository.isDebugWindowOpen("build", false)
        checkDeezerInfo?.isChecked = presetRepository.isDebugWindowOpen("spotify", false)
        checkDebugButtons?.isChecked = presetRepository.isDebugWindowOpen("buttons", false)
        checkSwcInfo?.isChecked = presetRepository.isDebugWindowOpen("swc", false)
        checkCarouselInfo?.isChecked = presetRepository.isDebugWindowOpen("carousel", false)
        checkStationOverlayDebug?.isChecked = presetRepository.isDebugWindowOpen("stationoverlay", false)
        checkStationOverlayPermanent?.isChecked = presetRepository.isPermanentStationOverlay()
        checkTunerInfo?.isChecked = presetRepository.isDebugWindowOpen("tuner", false)

        // Restore positions (post to ensure views are laid out)
        debugOverlay?.post { restoreDebugWindowPosition("rds", debugOverlay) }
        debugLayoutOverlay?.post { restoreDebugWindowPosition("layout", debugLayoutOverlay) }
        debugBuildOverlay?.post { restoreDebugWindowPosition("build", debugBuildOverlay) }
        debugDeezerOverlay?.post { restoreDebugWindowPosition("spotify", debugDeezerOverlay) }
        debugButtonsOverlay?.post { restoreDebugWindowPosition("buttons", debugButtonsOverlay) }
        debugSwcOverlay?.post { restoreDebugWindowPosition("swc", debugSwcOverlay) }
        debugCarouselOverlay?.post { restoreDebugWindowPosition("carousel", debugCarouselOverlay) }
        debugStationOverlay?.post { restoreDebugWindowPosition("stationoverlay", debugStationOverlay) }
        debugTunerOverlay?.post { restoreDebugWindowPosition("tuner", debugTunerOverlay) }
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

    private fun startTunerInfoUpdates() {
        if (tunerInfoUpdateHandler == null) {
            tunerInfoUpdateHandler = android.os.Handler(android.os.Looper.getMainLooper())
        }
        tunerInfoUpdateRunnable = object : Runnable {
            override fun run() {
                updateTunerDebugInfo()
                tunerInfoUpdateHandler?.postDelayed(this, 1000) // Update every second
            }
        }
        tunerInfoUpdateRunnable?.run()
    }

    private fun stopTunerInfoUpdates() {
        tunerInfoUpdateRunnable?.let { tunerInfoUpdateHandler?.removeCallbacks(it) }
        tunerInfoUpdateRunnable = null
    }

    private fun updateTunerDebugInfo() {
        // Current active tuner
        val currentMode = frequencyScale.getMode()
        debugTunerActive?.text = currentMode.name
        debugTunerActive?.setTextColor(android.graphics.Color.parseColor("#00FFFF"))

        // FM availability
        val fmAvailable = FmNative.isLibraryLoaded()
        if (fmAvailable) {
            debugTunerFm?.text = "Verfügbar"
            debugTunerFm?.setTextColor(android.graphics.Color.parseColor("#00FF00"))
        } else {
            debugTunerFm?.text = "Nicht verfügbar"
            debugTunerFm?.setTextColor(android.graphics.Color.parseColor("#FF4444"))
        }

        // AM availability (same as FM - shares tuner)
        val amAvailable = FmNative.isLibraryLoaded()
        if (amAvailable) {
            debugTunerAm?.text = "Verfügbar"
            debugTunerAm?.setTextColor(android.graphics.Color.parseColor("#00FF00"))
        } else {
            debugTunerAm?.text = "Nicht verfügbar"
            debugTunerAm?.setTextColor(android.graphics.Color.parseColor("#FF4444"))
        }

        // DAB availability
        val dabAvailable = dabTunerManager.isDabAvailable(this)
        if (dabAvailable) {
            debugTunerDab?.text = "Verfügbar"
            debugTunerDab?.setTextColor(android.graphics.Color.parseColor("#00FF00"))
        } else {
            debugTunerDab?.text = "Nicht verfügbar"
            debugTunerDab?.setTextColor(android.graphics.Color.parseColor("#FF4444"))
        }
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

    private fun setupDebugDeezerOverlayDrag() {
        val overlay = debugDeezerOverlay ?: return
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

    private fun setupDebugSwcOverlayDrag() {
        val overlay = debugSwcOverlay ?: return
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
                    saveDebugWindowPosition("swc", view)
                    true
                }
                else -> false
            }
        }
    }

    private fun setupDebugCarouselOverlayDrag() {
        val overlay = debugCarouselOverlay ?: return
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
                    saveDebugWindowPosition("carousel", view)
                    true
                }
                else -> false
            }
        }
    }

    private fun setupDebugStationOverlayDrag() {
        val overlay = debugStationOverlay ?: return
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
                    saveDebugWindowPosition("stationoverlay", view)
                    true
                }
                else -> false
            }
        }
    }

    private fun setupDebugTunerOverlayDrag() {
        val overlay = debugTunerOverlay ?: return
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
                    saveDebugWindowPosition("tuner", view)
                    true
                }
                else -> false
            }
        }
    }

    private fun getKeyName(keyCode: Int): String? {
        return when (keyCode) {
            87 -> "NEXT"
            88 -> "PREV"
            85 -> "PLAY/PAUSE"
            126 -> "PLAY"
            127 -> "PAUSE"
            79 -> "HOOK"
            24 -> "VOL+"
            25 -> "VOL-"
            else -> null
        }
    }

    private fun logSwcEvent(button: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val logEntry = "$timestamp - $button"

        swcLogEntries.add(0, logEntry)  // Add at beginning
        if (swcLogEntries.size > 10) {
            swcLogEntries.removeAt(swcLogEntries.size - 1)  // Remove oldest
        }

        debugSwcLog?.text = swcLogEntries.joinToString("\n")
    }

    private fun setupDebugOverlayAlphaSliders() {
        // Global Alpha Slider for all Debug Overlays
        findViewById<android.widget.SeekBar>(R.id.debugOverlaysAlphaSlider)?.apply {
            // Load saved alpha value (default 80%)
            val savedProgress = getSharedPreferences("fytfm_prefs", MODE_PRIVATE)
                .getInt("debug_overlay_alpha", 80)
            progress = savedProgress
            // Apply immediately
            applyDebugOverlayAlpha(savedProgress)

            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    applyDebugOverlayAlpha(progress)
                }
                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                    // Save when user stops dragging
                    seekBar?.let {
                        getSharedPreferences("fytfm_prefs", MODE_PRIVATE).edit()
                            .putInt("debug_overlay_alpha", it.progress)
                            .apply()
                    }
                }
            })
        }
    }

    private fun applyDebugOverlayAlpha(progress: Int) {
        // Minimum 10%, maximum 100% -> map 0-100 to 0.1-1.0
        val alpha = 0.1f + (progress / 100f) * 0.9f
        // Apply alpha to all debug overlays
        debugOverlay?.alpha = alpha
        debugLayoutOverlay?.alpha = alpha
        debugBuildOverlay?.alpha = alpha
        debugDeezerOverlay?.alpha = alpha
        debugButtonsOverlay?.alpha = alpha
        debugSwcOverlay?.alpha = alpha
        debugCarouselOverlay?.alpha = alpha
        debugTunerOverlay?.alpha = alpha
        debugChecklist?.alpha = alpha
    }

    private fun setupDebugButtonsListeners() {
        findViewById<android.widget.Button>(R.id.btnDebugKillInternet)?.setOnClickListener {
            debugInternetDisabled = !debugInternetDisabled
            // SpotifyClient Flag synchronisieren
            DeezerClient.debugInternetDisabled = debugInternetDisabled
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

        // View Reports Button
        findViewById<android.widget.Button>(R.id.btnDebugViewReports)?.setOnClickListener {
            startActivity(android.content.Intent(this, BugReportActivity::class.java))
        }

        // Block Spotify/Local Toggle Button
        findViewById<android.widget.ToggleButton>(R.id.btnDebugBlockDeezer)?.setOnCheckedChangeListener { btn, isChecked ->
            debugDeezerBlocked = isChecked
            if (isChecked) {
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF993333.toInt())
                android.widget.Toast.makeText(this, "Deezer/Local blocked - RDS only", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFCC9933.toInt())
                android.widget.Toast.makeText(this, "Deezer/Local enabled", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        // Kill App Button
        findViewById<android.widget.Button>(R.id.btnDebugKillApp)?.setOnClickListener {
            android.os.Process.killProcess(android.os.Process.myPid())
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
        val trackInfo = currentDeezerTrackInfo
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
            spotifyStatus = currentDeezerStatus,
            spotifyOriginalRt = currentDeezerOriginalRt,
            spotifyStrippedRt = currentDeezerStrippedRt,
            spotifyQuery = currentDeezerQuery,
            spotifyTrackInfo = currentDeezerTrackInfo
        )

        val reportPath = bugReportHelper.createBugReport(appState)
        if (reportPath != null) {
            android.widget.Toast.makeText(this, "Bug report created", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            android.widget.Toast.makeText(this, "Failed to create bug report", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun showBugReportInputDialog() {
        val editText = android.widget.EditText(this).apply {
            hint = "Was ist das Problem?"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            maxLines = 6
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            setPadding(32, 24, 32, 24)
        }

        AlertDialog.Builder(this)
            .setTitle("Bug Report erstellen")
            .setMessage("Beschreibe das Problem kurz. Die App wird danach Logs sammeln und in Downloads speichern.")
            .setView(editText)
            .setPositiveButton("Erstellen") { _, _ ->
                val userDescription = editText.text.toString()
                createBugReportToDownloads(userDescription)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun createBugReportToDownloads(userDescription: String) {
        val bugReportHelper = BugReportHelper(this)
        val appState = BugReportHelper.AppState(
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
            spotifyStatus = currentDeezerStatus,
            spotifyOriginalRt = currentDeezerOriginalRt,
            spotifyStrippedRt = currentDeezerStrippedRt,
            spotifyQuery = currentDeezerQuery,
            spotifyTrackInfo = currentDeezerTrackInfo,
            userDescription = userDescription.ifEmpty { null }
        )

        val reportPath = bugReportHelper.createBugReportToDownloads(appState)
        if (reportPath != null) {
            android.widget.Toast.makeText(this, "Bug Report gespeichert in Downloads/fytFM/", android.widget.Toast.LENGTH_LONG).show()
        } else {
            android.widget.Toast.makeText(this, "Fehler beim Erstellen des Bug Reports", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // Methode zum Aktualisieren der Spotify Debug-Anzeige
    fun updateDeezerDebugInfo(status: String, originalRt: String?, strippedRt: String?, query: String?, trackInfo: TrackInfo?) {
        if (debugDeezerOverlay?.visibility != View.VISIBLE) return

        // Status und Input immer setzen
        findViewById<TextView>(R.id.debugDeezerStatus)?.text = status
        // Show original RT and search string (always both)
        val orig = originalRt ?: "--"
        val search = strippedRt ?: "--"
        val rtDisplay = "Orig: $orig\nSuche: $search"
        findViewById<TextView>(R.id.debugDeezerRtInput)?.text = rtDisplay

        // Bei "Waiting..." explizit alles clearen (Senderwechsel)
        if (status == "Waiting...") {
            lastDebugTrackId = null
            clearDeezerDebugFields()
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
        val isFromDeezerOnline = status == "Found!"

        val sourceText = when {
            isFromLocalCache -> "LOKAL"
            isFromDeezerOnline -> "DEEZER"
            else -> "..."
        }
        val sourceColor = when {
            isFromLocalCache -> android.graphics.Color.parseColor("#FFAA00")  // Orange
            isFromDeezerOnline -> android.graphics.Color.parseColor("#FEAA2D")  // Deezer Orange
            else -> android.graphics.Color.parseColor("#AAAAAA")
        }
        findViewById<TextView>(R.id.debugDeezerSource)?.text = sourceText
        findViewById<TextView>(R.id.debugDeezerSource)?.setTextColor(sourceColor)

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
            findViewById<TextView>(R.id.debugDeezerArtist)?.text = trackInfo.artist
            findViewById<TextView>(R.id.debugDeezerTitle)?.text = trackInfo.title
            findViewById<TextView>(R.id.debugDeezerAllArtists)?.text =
                if (trackInfo.allArtists.isNotEmpty()) trackInfo.allArtists.joinToString(", ") else "--"
            findViewById<TextView>(R.id.debugDeezerDuration)?.text = formatDuration(trackInfo.durationMs)
            findViewById<TextView>(R.id.debugDeezerPopularity)?.text = "${trackInfo.popularity}/100"
            findViewById<TextView>(R.id.debugDeezerExplicit)?.text = if (trackInfo.explicit) "Yes" else "No"
            findViewById<TextView>(R.id.debugDeezerTrackDisc)?.text = "${trackInfo.trackNumber}/${trackInfo.discNumber}"
            findViewById<TextView>(R.id.debugDeezerISRC)?.text = trackInfo.isrc ?: "--"

            // ALBUM Section
            findViewById<TextView>(R.id.debugDeezerAlbum)?.text = trackInfo.album ?: "--"
            findViewById<TextView>(R.id.debugDeezerAlbumType)?.text = trackInfo.albumType ?: "--"
            findViewById<TextView>(R.id.debugDeezerTotalTracks)?.text =
                if (trackInfo.totalTracks > 0) trackInfo.totalTracks.toString() else "--"
            findViewById<TextView>(R.id.debugDeezerReleaseDate)?.text = trackInfo.releaseDate ?: "--"

            // IDs & URLs Section
            findViewById<TextView>(R.id.debugDeezerTrackId)?.text = trackInfo.trackId ?: "--"
            findViewById<TextView>(R.id.debugDeezerAlbumId)?.text = trackInfo.albumId ?: "--"
            findViewById<TextView>(R.id.debugDeezerUrl)?.text = trackInfo.deezerUrl ?: "--"
            findViewById<TextView>(R.id.debugDeezerAlbumUrl)?.text = trackInfo.albumUrl ?: "--"
            findViewById<TextView>(R.id.debugDeezerPreviewUrl)?.text = trackInfo.previewUrl ?: "--"
            findViewById<TextView>(R.id.debugDeezerCoverUrl)?.text = trackInfo.coverUrl ?: trackInfo.coverUrlMedium ?: "--"
        }
    }

    private fun loadCoverImage(coverPath: String?) {
        val coverImageView = findViewById<android.widget.ImageView>(R.id.debugDeezerCoverImage) ?: return

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

    private fun clearDeezerDebugFields() {
        findViewById<TextView>(R.id.debugDeezerSource)?.text = "--"
        findViewById<TextView>(R.id.debugDeezerSource)?.setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
        findViewById<android.widget.ImageView>(R.id.debugDeezerCoverImage)?.setImageResource(android.R.drawable.ic_menu_gallery)

        // Track fields
        findViewById<TextView>(R.id.debugDeezerArtist)?.text = "--"
        findViewById<TextView>(R.id.debugDeezerTitle)?.text = "--"
        findViewById<TextView>(R.id.debugDeezerAllArtists)?.text = "--"
        findViewById<TextView>(R.id.debugDeezerDuration)?.text = "--"
        findViewById<TextView>(R.id.debugDeezerPopularity)?.text = "--"
        findViewById<TextView>(R.id.debugDeezerExplicit)?.text = "--"
        findViewById<TextView>(R.id.debugDeezerTrackDisc)?.text = "--"
        findViewById<TextView>(R.id.debugDeezerISRC)?.text = "--"

        // Album fields
        findViewById<TextView>(R.id.debugDeezerAlbum)?.text = "--"
        findViewById<TextView>(R.id.debugDeezerAlbumType)?.text = "--"
        findViewById<TextView>(R.id.debugDeezerTotalTracks)?.text = "--"
        findViewById<TextView>(R.id.debugDeezerReleaseDate)?.text = "--"

        // IDs & URLs
        findViewById<TextView>(R.id.debugDeezerTrackId)?.text = "--"
        findViewById<TextView>(R.id.debugDeezerAlbumId)?.text = "--"
        findViewById<TextView>(R.id.debugDeezerUrl)?.text = "--"
        findViewById<TextView>(R.id.debugDeezerAlbumUrl)?.text = "--"
        findViewById<TextView>(R.id.debugDeezerPreviewUrl)?.text = "--"
        findViewById<TextView>(R.id.debugDeezerCoverUrl)?.text = "--"
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
            // Update PS (Station Name)
            val ps = rdsManager.ps
            if (!ps.isNullOrBlank()) {
                nowPlayingPs?.text = ps
                nowPlayingPs?.visibility = View.VISIBLE

                // Auto-name station if name is null
                autoNameStationIfNeeded(ps)
            } else {
                nowPlayingPs?.visibility = View.GONE
            }

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
            val currentFreq = frequencyScale.getFrequency()
            val isAM = frequencyScale.getMode() == FrequencyScaleView.RadioMode.AM
            val placeholderDrawable = if (isAM) R.drawable.placeholder_am else R.drawable.placeholder_fm
            val coverUrl = trackInfo.coverUrl ?: trackInfo.coverUrlMedium
            val deezerEnabled = presetRepository.isDeezerEnabledForFrequency(currentFreq)

            // Try station logo first (always available as fallback)
            val stationLogo = radioLogoRepository.getLogoForStation(
                ps = rdsManager.ps,
                pi = rdsManager.pi,
                frequency = currentFreq
            )

            if (deezerEnabled && !coverUrl.isNullOrBlank()) {
                // Spotify cover available
                if (coverUrl.startsWith("/")) {
                    nowPlayingCover?.load(java.io.File(coverUrl)) {
                        crossfade(true)
                        placeholder(placeholderDrawable)
                        error(placeholderDrawable)
                    }
                } else {
                    nowPlayingCover?.load(coverUrl) {
                        crossfade(true)
                        placeholder(placeholderDrawable)
                        error(placeholderDrawable)
                    }
                }
            } else if (stationLogo != null) {
                // Station logo available
                nowPlayingCover?.load(java.io.File(stationLogo)) {
                    crossfade(true)
                    placeholder(placeholderDrawable)
                    error(placeholderDrawable)
                }
            } else {
                // Fallback: Radio icon
                nowPlayingCover?.setImageResource(placeholderDrawable)
            }

            // Update carousel if in carousel mode
            if (isCarouselMode) {
                // Update carousel card cover - clear cover when Spotify is disabled for this station
                if (!presetRepository.isDeezerEnabledForFrequency(currentFreq)) {
                    stationCarouselAdapter?.updateCurrentCover(null, null)
                } else {
                    stationCarouselAdapter?.updateCurrentCover(
                        coverUrl = trackInfo.coverUrlMedium,
                        localCoverPath = if (trackInfo.coverUrl?.startsWith("/") == true) trackInfo.coverUrl else null
                    )
                }

                // Update carousel now playing bar
                updateCarouselNowPlayingBar(trackInfo)
            }

            // Show with animation
            if (bar.visibility != View.VISIBLE) {
                showNowPlayingBar(bar)
            }
        }
    }

    /**
     * Aktualisiert die Now Playing Bar für DAB-Sender
     */
    private fun updateDabNowPlaying(dabStation: at.planqton.fytfm.dab.DabStation) {
        // Hauptanzeige aktualisieren
        tvFrequency.text = dabStation.serviceLabel ?: "DAB+"

        // Now Playing Bar aktualisieren
        nowPlayingPs?.text = dabStation.serviceLabel ?: "DAB+"
        nowPlayingPs?.visibility = View.VISIBLE
        nowPlayingArtist?.visibility = View.GONE
        nowPlayingTitle?.text = dabStation.ensembleLabel ?: ""
        nowPlayingTitle?.visibility = if (dabStation.ensembleLabel.isNullOrBlank()) View.GONE else View.VISIBLE

        // Cover: Versuche Station-Logo zu laden
        val stationLogo = radioLogoRepository.getLogoForStation(
            ps = dabStation.serviceLabel,
            pi = null,
            frequency = 0f
        )
        if (stationLogo != null) {
            nowPlayingCover?.load(java.io.File(stationLogo)) {
                crossfade(true)
                placeholder(R.drawable.placeholder_fm)
                error(R.drawable.placeholder_fm)
            }
        } else {
            nowPlayingCover?.setImageResource(R.drawable.placeholder_fm)
        }

        // Now Playing Bar anzeigen
        nowPlayingBar?.let { bar ->
            if (bar.visibility != View.VISIBLE) {
                showNowPlayingBar(bar)
            }
        }

        // Carousel Now Playing Bar auch aktualisieren
        carouselNowPlayingPs?.text = dabStation.serviceLabel ?: "DAB+"
        carouselNowPlayingPs?.visibility = View.VISIBLE
        carouselNowPlayingArtist?.visibility = View.GONE
        carouselNowPlayingTitle?.text = dabStation.ensembleLabel ?: ""
        carouselNowPlayingTitle?.visibility = if (dabStation.ensembleLabel.isNullOrBlank()) View.GONE else View.VISIBLE

        if (stationLogo != null) {
            carouselNowPlayingCover?.load(java.io.File(stationLogo)) {
                crossfade(true)
                placeholder(R.drawable.placeholder_fm)
                error(R.drawable.placeholder_fm)
            }
        } else {
            carouselNowPlayingCover?.setImageResource(R.drawable.placeholder_fm)
        }

        carouselNowPlayingBar?.let { bar ->
            if (bar.visibility != View.VISIBLE) {
                showNowPlayingBar(bar)
            }
        }
    }

    /**
     * Initialisiert die Anzeige für DAB-Modus beim App-Start
     */
    private fun initDabDisplay() {
        // Debug-Header auf DAB Debug setzen
        findViewById<android.widget.TextView>(R.id.debugHeader)?.text = "DAB Debug"

        if (currentDabServiceId != -1) {
            // Versuche den gespeicherten Sender zu finden
            val savedStations = presetRepository.loadDabStations()
            val station = savedStations.find { it.serviceId == currentDabServiceId }
            if (station != null) {
                // Zeige Sendername statt Frequenz
                tvFrequency.text = station.name ?: "DAB+"

                // Now Playing Bar mit Senderinfo aktualisieren
                nowPlayingPs?.text = station.name ?: "DAB+"
                nowPlayingPs?.visibility = View.VISIBLE
                nowPlayingArtist?.visibility = View.GONE
                nowPlayingTitle?.text = ""  // DLS wird später per Callback kommen
                nowPlayingTitle?.visibility = View.GONE

                // Cover: Versuche Station-Logo zu laden
                val stationLogo = radioLogoRepository.getLogoForStation(
                    ps = station.name,
                    pi = null,
                    frequency = 0f
                )
                if (stationLogo != null) {
                    nowPlayingCover?.load(java.io.File(stationLogo)) {
                        crossfade(true)
                        placeholder(R.drawable.placeholder_fm)
                        error(R.drawable.placeholder_fm)
                    }
                } else {
                    nowPlayingCover?.setImageResource(R.drawable.placeholder_fm)
                }

                // Carousel Now Playing Bar auch aktualisieren
                carouselNowPlayingPs?.text = station.name ?: "DAB+"
                carouselNowPlayingPs?.visibility = View.VISIBLE
                carouselNowPlayingArtist?.visibility = View.GONE
                carouselNowPlayingTitle?.visibility = View.GONE

                if (stationLogo != null) {
                    carouselNowPlayingCover?.load(java.io.File(stationLogo)) {
                        crossfade(true)
                        placeholder(R.drawable.placeholder_fm)
                        error(R.drawable.placeholder_fm)
                    }
                } else {
                    carouselNowPlayingCover?.setImageResource(R.drawable.placeholder_fm)
                }
            } else {
                // Kein gespeicherter Sender gefunden
                tvFrequency.text = "DAB+"
                nowPlayingPs?.text = "DAB+"
                nowPlayingPs?.visibility = View.VISIBLE
            }
        } else {
            // Kein Sender ausgewählt
            tvFrequency.text = "DAB+"
        }
    }

    /**
     * Auto-names a station with PS value if the station has no name yet
     */
    private fun autoNameStationIfNeeded(ps: String) {
        val frequency = frequencyScale.getFrequency()
        val isAM = frequencyScale.getMode() == FrequencyScaleView.RadioMode.AM

        // Load stations and check if current frequency has no name
        val stations = if (isAM) presetRepository.loadAmStations() else presetRepository.loadFmStations()
        val existingStation = stations.find { Math.abs(it.frequency - frequency) < 0.05f }

        // Only auto-name if station exists but has no name
        if (existingStation != null && existingStation.name.isNullOrBlank()) {
            val updatedStations = stations.map {
                if (Math.abs(it.frequency - frequency) < 0.05f) {
                    it.copy(name = ps)
                } else it
            }

            // Save updated stations
            if (isAM) {
                presetRepository.saveAmStations(updatedStations)
            } else {
                presetRepository.saveFmStations(updatedStations)
            }

            // Reload station list
            loadStationsForCurrentMode()
        }
    }

    /**
     * Handles triple-tap on PS to rename station with PS value
     */
    private fun handlePsTripleTap() {
        val currentTime = System.currentTimeMillis()
        val ps = rdsManager.ps

        // Reset counter if more than 500ms since last tap
        if (currentTime - lastPsTapTime > 500) {
            psTapCount = 0
        }

        psTapCount++
        lastPsTapTime = currentTime

        if (psTapCount >= 3 && !ps.isNullOrBlank()) {
            psTapCount = 0

            // Get current frequency and mode
            val frequency = frequencyScale.getFrequency()
            val isAM = frequencyScale.getMode() == FrequencyScaleView.RadioMode.AM

            // Load stations and update the name
            val stations = if (isAM) presetRepository.loadAmStations() else presetRepository.loadFmStations()
            val existingStation = stations.find { Math.abs(it.frequency - frequency) < 0.05f }

            val updatedStations = if (existingStation != null) {
                // Update existing station name
                stations.map {
                    if (Math.abs(it.frequency - frequency) < 0.05f) {
                        it.copy(name = ps)
                    } else it
                }
            } else {
                // Add new station with PS name
                val newStation = at.planqton.fytfm.data.RadioStation(
                    frequency = frequency,
                    name = ps,
                    rssi = 0,
                    isAM = isAM,
                    isFavorite = false
                )
                (stations + newStation).sortedBy { it.frequency }
            }

            // Save updated stations
            if (isAM) {
                presetRepository.saveAmStations(updatedStations)
            } else {
                presetRepository.saveFmStations(updatedStations)
            }

            // Reload station list
            loadStationsForCurrentMode()

            // Show confirmation toast
            android.widget.Toast.makeText(this, "Sender umbenannt: $ps", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Setzt die Now Playing Bar zurück (ohne sie zu verstecken, da permanent sichtbar)
     */
    fun hideNowPlayingBarExplicit() {
        lastDisplayedTrackId = null
        // Clear carousel cover
        stationCarouselAdapter?.updateCurrentCover(null, null)
        // Bar bleibt sichtbar, nur Inhalt zurücksetzen
        nowPlayingPs?.visibility = View.GONE
        nowPlayingTitle?.text = ""
        nowPlayingArtist?.text = ""
        nowPlayingArtist?.visibility = View.GONE
        val placeholderDrawable = if (frequencyScale.getMode() == FrequencyScaleView.RadioMode.AM) R.drawable.placeholder_am else R.drawable.placeholder_fm
        nowPlayingCover?.setImageResource(placeholderDrawable)
        // Carousel bar zurücksetzen
        carouselNowPlayingPs?.visibility = View.GONE
        carouselNowPlayingTitle?.text = ""
        carouselNowPlayingArtist?.text = ""
        carouselNowPlayingArtist?.visibility = View.GONE
        carouselNowPlayingCover?.setImageResource(placeholderDrawable)
    }

    /**
     * Setzt die Now Playing Bar mit Sender-Infos zurück (bei Frequenzwechsel)
     * Zeigt Station Logo + Sendername + Frequenz an
     */
    private fun resetNowPlayingBarForStation(stationName: String?, logoPath: String?, frequency: Float, isAM: Boolean) {
        lastDisplayedTrackId = null

        // Format frequency display
        val freqDisplay = if (isAM) {
            "${frequency.toInt()} kHz"
        } else {
            "FM ${String.format("%.1f", frequency)}"
        }

        // Update PS (Station Name from RDS)
        val ps = rdsManager.ps
        if (!ps.isNullOrBlank()) {
            nowPlayingPs?.text = ps
            nowPlayingPs?.visibility = View.VISIBLE
            carouselNowPlayingPs?.text = ps
            carouselNowPlayingPs?.visibility = View.VISIBLE
        } else {
            nowPlayingPs?.visibility = View.GONE
            carouselNowPlayingPs?.visibility = View.GONE
        }

        // Set text: Station name or frequency
        val displayName = stationName ?: freqDisplay
        nowPlayingTitle?.text = displayName
        nowPlayingArtist?.text = if (stationName != null) freqDisplay else ""
        nowPlayingArtist?.visibility = if (stationName != null) View.VISIBLE else View.GONE

        // Set cover: Station logo or radio icon
        val placeholderDrawable = if (isAM) R.drawable.placeholder_am else R.drawable.placeholder_fm
        if (logoPath != null) {
            nowPlayingCover?.load(java.io.File(logoPath)) {
                crossfade(true)
                placeholder(placeholderDrawable)
                error(placeholderDrawable)
            }
        } else {
            nowPlayingCover?.setImageResource(placeholderDrawable)
        }

        // Clear raw RT display and hide ignored indicator
        nowPlayingRawRt?.text = ""
        carouselNowPlayingRawRt?.text = ""
        nowPlayingIgnoredIndicator?.visibility = View.GONE
        carouselIgnoredIndicator?.visibility = View.GONE

        // Update carousel bar
        carouselNowPlayingTitle?.text = displayName
        carouselNowPlayingArtist?.text = if (stationName != null) freqDisplay else ""
        carouselNowPlayingArtist?.visibility = if (stationName != null) View.VISIBLE else View.GONE

        if (logoPath != null) {
            carouselNowPlayingCover?.load(java.io.File(logoPath)) {
                crossfade(true)
                placeholder(placeholderDrawable)
                error(placeholderDrawable)
            }
        } else {
            carouselNowPlayingCover?.setImageResource(placeholderDrawable)
        }

        // Clear carousel card cover
        stationCarouselAdapter?.updateCurrentCover(null, null)
    }

    /**
     * Updates the ignored indicator visibility based on whether the current RT is ignored
     */
    private fun updateIgnoredIndicator(rt: String?) {
        if (rt.isNullOrBlank()) {
            nowPlayingIgnoredIndicator?.visibility = View.GONE
            carouselIgnoredIndicator?.visibility = View.GONE
            return
        }

        val dao = rtCorrectionDao ?: return
        val normalizedRt = RtCorrection.normalizeRt(rt)

        CoroutineScope(Dispatchers.IO).launch {
            val isIgnored = dao.isRtIgnored(normalizedRt)
            withContext(Dispatchers.Main) {
                val visibility = if (isIgnored) View.VISIBLE else View.GONE
                nowPlayingIgnoredIndicator?.visibility = visibility
                carouselIgnoredIndicator?.visibility = visibility
            }
        }
    }

    /**
     * Update the carousel now playing bar with track info
     */
    private fun updateCarouselNowPlayingBar(trackInfo: TrackInfo) {
        val bar = carouselNowPlayingBar ?: return

        // Update PS (Station Name)
        val ps = rdsManager.ps
        if (!ps.isNullOrBlank()) {
            carouselNowPlayingPs?.text = ps
            carouselNowPlayingPs?.visibility = View.VISIBLE
        } else {
            carouselNowPlayingPs?.visibility = View.GONE
        }

        // Update text
        if (trackInfo.artist.isBlank()) {
            carouselNowPlayingArtist?.visibility = View.GONE
            carouselNowPlayingTitle?.text = trackInfo.title
        } else {
            carouselNowPlayingArtist?.visibility = View.VISIBLE
            carouselNowPlayingArtist?.text = trackInfo.artist
            carouselNowPlayingTitle?.text = trackInfo.title
        }

        // Load cover image
        val currentFreq = frequencyScale.getFrequency()
        val isAM = frequencyScale.getMode() == FrequencyScaleView.RadioMode.AM
        val placeholderDrawable = if (isAM) R.drawable.placeholder_am else R.drawable.placeholder_fm
        val coverUrl = trackInfo.coverUrl ?: trackInfo.coverUrlMedium
        val deezerEnabled = presetRepository.isDeezerEnabledForFrequency(currentFreq)

        // Try station logo first (always available as fallback)
        val stationLogo = radioLogoRepository.getLogoForStation(
            ps = rdsManager.ps,
            pi = rdsManager.pi,
            frequency = currentFreq
        )

        if (deezerEnabled && !coverUrl.isNullOrBlank()) {
            // Spotify cover available
            if (coverUrl.startsWith("/")) {
                carouselNowPlayingCover?.load(java.io.File(coverUrl)) {
                    crossfade(true)
                    placeholder(placeholderDrawable)
                    error(placeholderDrawable)
                }
            } else {
                carouselNowPlayingCover?.load(coverUrl) {
                    crossfade(true)
                    placeholder(placeholderDrawable)
                    error(placeholderDrawable)
                }
            }
        } else if (stationLogo != null) {
            // Station logo available
            carouselNowPlayingCover?.load(java.io.File(stationLogo)) {
                crossfade(true)
                placeholder(placeholderDrawable)
                error(placeholderDrawable)
            }
        } else {
            // Fallback: Radio icon
            carouselNowPlayingCover?.setImageResource(placeholderDrawable)
        }

        // Show bar
        if (bar.visibility != View.VISIBLE) {
            bar.visibility = View.VISIBLE
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
        btnCorrectionTrash?.setOnClickListener { handleCorrectionTrash() }

        // Refresh button - skip this track and search for another
        btnCorrectionRefresh?.setOnClickListener { handleCorrectionRefresh() }

        // Carousel versions - same logic
        btnCarouselCorrectionTrash?.setOnClickListener { handleCorrectionTrash() }
        btnCarouselCorrectionRefresh?.setOnClickListener { handleCorrectionRefresh() }
    }

    private fun handleCorrectionTrash() {
        val currentRt = rtCombiner?.getCurrentRt() ?: return
        val dao = rtCorrectionDao ?: return

        CoroutineScope(Dispatchers.IO).launch {
            val correction = RtCorrection(
                rtNormalized = RtCorrection.normalizeRt(currentRt),
                rtOriginal = currentRt,
                type = RtCorrection.TYPE_IGNORED
            )
            dao.insert(correction)
            android.util.Log.i("fytFM", "RT ignored: $currentRt")

            withContext(Dispatchers.Main) {
                // Reset displayed track but keep bar visible
                lastDisplayedTrackId = null
                android.widget.Toast.makeText(
                    this@MainActivity,
                    "RT wird ignoriert",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun handleCorrectionRefresh() {
        val currentRt = rtCombiner?.getCurrentRt() ?: return
        val currentTrack = rtCombiner?.getCurrentTrackInfo() ?: return
        val dao = rtCorrectionDao ?: return

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
                rtCombiner?.forceReprocess()
                android.widget.Toast.makeText(
                    this@MainActivity,
                    "Suche anderen Track...",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * Update visibility of correction helper buttons based on setting
     */
    fun updateCorrectionHelpersVisibility() {
        val enabled = presetRepository.isCorrectionHelpersEnabled()
        nowPlayingCorrectionButtons?.visibility = if (enabled) View.VISIBLE else View.GONE
        carouselCorrectionButtons?.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    /**
     * Setup Spotify toggle button
     */
    private fun setupDeezerToggle() {
        // Load saved state for current frequency
        updateDeezerToggleForCurrentFrequency()

        // Click handlers for both toggles (normal and carousel)
        val toggleAction = {
            val currentFreq = frequencyScale.getFrequency()
            val newState = !presetRepository.isDeezerEnabledForFrequency(currentFreq)
            presetRepository.setDeezerEnabledForFrequency(currentFreq, newState)
            updateDeezerToggleAppearance(newState)

            if (newState) {
                // Spotify enabled - trigger reprocessing of current RT
                rtCombiner?.forceReprocess()
                android.widget.Toast.makeText(this, "Deezer aktiviert", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                // Spotify disabled - immediately show raw RT with radio icon
                val currentRt = rdsManager.rt
                if (!currentRt.isNullOrBlank()) {
                    val displayInfo = parseRawRtToTrackInfo(currentRt)
                    lastDisplayedTrackId = null  // Force update
                    updateNowPlaying(displayInfo)
                    nowPlayingRawRt?.text = currentRt
                    carouselNowPlayingRawRt?.text = currentRt
                    updateIgnoredIndicator(currentRt)
                }
                // Clear carousel cover
                stationCarouselAdapter?.updateCurrentCover(null, null)
                android.widget.Toast.makeText(this, "Deezer deaktiviert", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        btnDeezerToggle?.setOnClickListener { toggleAction() }
        btnCarouselDeezerToggle?.setOnClickListener { toggleAction() }
    }

    /**
     * Update Spotify toggle button for current frequency
     * Call this when frequency changes
     */
    fun updateDeezerToggleForCurrentFrequency() {
        val currentFreq = frequencyScale.getFrequency()
        val deezerEnabled = presetRepository.isDeezerEnabledForFrequency(currentFreq)
        updateDeezerToggleAppearance(deezerEnabled)
    }

    /**
     * Update Spotify toggle button appearance based on state
     */
    private fun updateDeezerToggleAppearance(enabled: Boolean) {
        val bg = if (enabled) R.drawable.toggle_selected else android.R.color.transparent
        btnDeezerToggle?.setBackgroundResource(bg)
        btnCarouselDeezerToggle?.setBackgroundResource(bg)
        // Also adjust alpha to indicate state
        val alpha = if (enabled) 1.0f else 0.4f
        btnDeezerToggle?.alpha = alpha
        btnCarouselDeezerToggle?.alpha = alpha
    }

    /**
     * Setup view mode toggle (Equalizer vs Carousel)
     */
    private fun setupViewModeToggle() {
        // Setup carousel adapter
        stationCarouselAdapter = at.planqton.fytfm.ui.StationCarouselAdapter { station ->
            // When user clicks a station in carousel, tune to it
            val currentMode = frequencyScale.getMode()
            android.util.Log.i("fytFM", "=== CAROUSEL CLICK: station=${station.name}, isDab=${station.isDab}, currentMode=$currentMode, serviceId=${station.serviceId} ===")
            if (station.isDab || currentMode == FrequencyScaleView.RadioMode.DAB) {
                android.util.Log.i("fytFM", ">>> Using DAB logic")
                // DAB: tune via DabTunerManager
                try {
                    currentDabServiceId = station.serviceId
                    currentDabEnsembleId = station.ensembleId
                    val success = dabTunerManager.tuneService(station.serviceId, station.ensembleId)
                    if (!success) {
                        android.widget.Toast.makeText(this, "Tuner Error: DAB Sender nicht gefunden", android.widget.Toast.LENGTH_LONG).show()
                    }
                    updateCarouselSelection()
                } catch (e: Exception) {
                    android.util.Log.e("fytFM", "DAB tune error: ${e.message}")
                    android.widget.Toast.makeText(this, "Tuner Error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            } else {
                android.util.Log.i("fytFM", ">>> Using FM/AM logic")
                try {
                    if (station.isAM) {
                        frequencyScale.setMode(FrequencyScaleView.RadioMode.AM)
                    } else {
                        frequencyScale.setMode(FrequencyScaleView.RadioMode.FM)
                    }
                    frequencyScale.setFrequency(station.frequency)
                    fmNative?.tune(station.frequency)
                    updateCarouselSelection()
                    startCarouselCenterTimer(station.frequency, station.isAM)
                } catch (e: Exception) {
                    android.util.Log.e("fytFM", "FM tune error: ${e.message}")
                    android.widget.Toast.makeText(this, "Tuner Error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }

        stationCarousel?.apply {
            adapter = stationCarouselAdapter
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
                this@MainActivity,
                androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL,
                false
            )
            // Allow edge items to scroll to center
            clipToPadding = false

            // Add snap helper for center snapping
            val snapHelper = androidx.recyclerview.widget.LinearSnapHelper()
            snapHelper.attachToRecyclerView(this)

            // Add scroll listener to auto-return to current station after 2 seconds of idle
            addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: androidx.recyclerview.widget.RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)
                    if (newState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE) {
                        // User stopped scrolling - start 2 second timer to return to current station
                        startCarouselReturnTimer()
                    } else if (newState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_DRAGGING) {
                        // User started dragging - cancel any pending return timer
                        cancelCarouselReturnTimer()
                    }
                }
            })

            // Set dynamic padding after layout to allow edge items to center
            post {
                val cardWidth = (216 * resources.displayMetrics.density).toInt()  // ~200dp card + margins
                val padding = (width - cardWidth) / 2
                setPadding(padding, paddingTop, padding, paddingBottom)
                android.util.Log.d("fytFM", "Carousel padding set to: $padding (width=$width, cardWidth=$cardWidth)")

                // Initial scroll to current frequency AFTER padding is set
                if (carouselNeedsInitialScroll && isCarouselMode) {
                    val currentFreq = frequencyScale.getFrequency()
                    val isAM = frequencyScale.getMode() == FrequencyScaleView.RadioMode.AM
                    val position = stationCarouselAdapter?.getPositionForFrequency(currentFreq, isAM) ?: -1
                    android.util.Log.d("fytFM", "Carousel post-padding scroll: freq=$currentFreq, position=$position")
                    if (position >= 0) {
                        carouselNeedsInitialScroll = false
                        // Use scrollToPositionWithOffset to center the item
                        val lm = layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
                        lm?.scrollToPositionWithOffset(position, padding)
                        android.util.Log.d("fytFM", "Carousel scrollToPositionWithOffset $position, offset=$padding")
                    }
                }
            }
        }

        // Toggle button click handlers
        btnViewModeEqualizer?.setOnClickListener {
            // In DAB mode, only carousel view is allowed
            if (frequencyScale.getMode() == FrequencyScaleView.RadioMode.DAB) return@setOnClickListener
            setViewMode(false)
        }

        btnViewModeImage?.setOnClickListener {
            setViewMode(true)
        }

        // Carousel favorite button
        btnCarouselFavorite?.setOnClickListener {
            btnFavorite.performClick()
            updateCarouselFavoriteIcon()
        }

        // Restore saved view mode - aber Carousel wird später in onCreate befüllt
        val savedCarouselMode = presetRepository.isCarouselMode()
        android.util.Log.d("fytFM", "setupViewModeToggle: savedCarouselMode=$savedCarouselMode")
        if (savedCarouselMode) {
            // Nur UI umschalten, NICHT populateCarousel() aufrufen (Modus noch nicht geladen)
            isCarouselMode = true
            presetRepository.setCarouselMode(true)
            mainContentArea?.visibility = View.GONE
            carouselContentArea?.visibility = View.VISIBLE
            btnViewModeEqualizer?.background = null
            btnViewModeImage?.setBackgroundResource(R.drawable.toggle_selected)
        }
    }

    /**
     * Switch between equalizer (normal) and carousel (image) mode
     */
    private fun setViewMode(carousel: Boolean) {
        isCarouselMode = carousel
        presetRepository.setCarouselMode(carousel)

        if (carousel) {
            // Switch to carousel mode
            mainContentArea?.visibility = View.GONE
            carouselContentArea?.visibility = View.VISIBLE
            btnViewModeEqualizer?.background = null
            btnViewModeImage?.setBackgroundResource(R.drawable.toggle_selected)

            // Populate carousel with stations
            populateCarousel()
            updateCarouselSelection()
            // Initial scroll will be triggered by frequency listener
            carouselNeedsInitialScroll = true
        } else {
            // Switch to equalizer mode
            mainContentArea?.visibility = View.VISIBLE
            carouselContentArea?.visibility = View.GONE
            btnViewModeEqualizer?.setBackgroundResource(R.drawable.toggle_selected)
            btnViewModeImage?.background = null
        }
    }

    /**
     * Populate the carousel with saved stations
     */
    private fun populateCarousel() {
        val mode = frequencyScale.getMode()
        val isAM = mode == FrequencyScaleView.RadioMode.AM
        val isDab = mode == FrequencyScaleView.RadioMode.DAB
        android.util.Log.i("fytFM", "=== populateCarousel: mode=$mode, isDab=$isDab ===")
        Thread.currentThread().stackTrace.take(8).forEach {
            android.util.Log.d("fytFM", "  at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})")
        }
        val stations = when (mode) {
            FrequencyScaleView.RadioMode.FM -> presetRepository.loadFmStations()
            FrequencyScaleView.RadioMode.AM -> presetRepository.loadAmStations()
            FrequencyScaleView.RadioMode.DAB -> presetRepository.loadDabStations()
        }

        android.util.Log.d("fytFM", "populateCarousel: ${stations.size} stations loaded, mode=$mode")

        val carouselItems = stations.map { station ->
            val logoPath = radioLogoRepository.getLogoForStation(station.name, null, station.frequency)
            at.planqton.fytfm.ui.StationCarouselAdapter.StationItem(
                frequency = station.frequency,
                name = station.name,
                logoPath = logoPath,
                isAM = isAM,
                isDab = isDab || station.isDab,
                serviceId = station.serviceId,
                ensembleId = station.ensembleId
            )
        }

        stationCarouselAdapter?.setStations(carouselItems)
    }

    /**
     * Update carousel selection to match current frequency
     */
    private fun updateCarouselSelection() {
        val mode = frequencyScale.getMode()

        if (mode == FrequencyScaleView.RadioMode.DAB) {
            // DAB: select by serviceId
            stationCarouselAdapter?.setCurrentDabService(currentDabServiceId)
            val dabStation = presetRepository.loadDabStations().find { it.serviceId == currentDabServiceId }
            carouselFrequencyLabel?.text = dabStation?.name ?: "DAB+"
            updateCarouselFavoriteIcon()
            val position = stationCarouselAdapter?.getPositionForDabService(currentDabServiceId) ?: -1
            android.util.Log.d("fytFM", "updateCarouselSelection DAB: serviceId=$currentDabServiceId, position=$position")
            if (position >= 0) {
                smoothScrollCarouselToCenter(position)
            }
            return
        }

        val currentFreq = frequencyScale.getFrequency()
        val isAM = mode == FrequencyScaleView.RadioMode.AM

        android.util.Log.d("fytFM", "updateCarouselSelection: freq=$currentFreq, isAM=$isAM")
        stationCarouselAdapter?.setCurrentFrequency(currentFreq, isAM)

        val freqText = if (isAM) {
            "AM ${currentFreq.toInt()}"
        } else {
            "FM %.2f".format(currentFreq).replace(".", ",")
        }
        carouselFrequencyLabel?.text = freqText

        updateCarouselFavoriteIcon()

        val position = stationCarouselAdapter?.getPositionForFrequency(currentFreq, isAM) ?: -1
        android.util.Log.d("fytFM", "updateCarouselSelection: position=$position")
    }

    private var carouselDebugUpdateRunnable: Runnable? = null

    /**
     * Start 2-second timer to smoothly center the carousel on the selected station
     */
    private fun startCarouselCenterTimer(frequency: Float, isAM: Boolean) {
        // Cancel any existing timers
        carouselCenterRunnable?.let { carouselCenterHandler.removeCallbacks(it) }
        carouselDebugUpdateRunnable?.let { carouselCenterHandler.removeCallbacks(it) }

        val position = stationCarouselAdapter?.getPositionForFrequency(frequency, isAM) ?: -1
        if (position < 0) return

        carouselPendingCenterPosition = position
        carouselCenterTimerStart = System.currentTimeMillis()

        // Update debug display
        updateCarouselDebugInfo()

        // Start repeating update for debug display
        carouselDebugUpdateRunnable = object : Runnable {
            override fun run() {
                updateCarouselDebugInfo()
                if (carouselPendingCenterPosition >= 0) {
                    carouselCenterHandler.postDelayed(this, 100)
                }
            }
        }
        carouselCenterHandler.postDelayed(carouselDebugUpdateRunnable!!, 100)

        carouselCenterRunnable = Runnable {
            smoothScrollCarouselToCenter(carouselPendingCenterPosition)
            carouselPendingCenterPosition = -1
            updateCarouselDebugInfo()
        }

        carouselCenterHandler.postDelayed(carouselCenterRunnable!!, 2000)
        android.util.Log.d("fytFM", "Carousel center timer started for position $position")
    }

    /**
     * Update carousel debug overlay with current state
     */
    private fun updateCarouselDebugInfo() {
        // Timer countdown
        if (carouselPendingCenterPosition >= 0) {
            val elapsed = System.currentTimeMillis() - carouselCenterTimerStart
            val remaining = ((2000 - elapsed) / 100) / 10f
            debugCarouselTimer?.text = "%.1fs".format(remaining.coerceAtLeast(0f))
        } else {
            debugCarouselTimer?.text = "idle"
        }

        // Active tile - show current frequency's position
        val currentFreq = frequencyScale.getFrequency()
        val isAM = frequencyScale.getMode() == FrequencyScaleView.RadioMode.AM
        val currentPos = stationCarouselAdapter?.getPositionForFrequency(currentFreq, isAM) ?: -1
        debugCarouselActiveTile?.text = if (currentPos >= 0) {
            "Pos $currentPos (${if (carouselPendingCenterPosition >= 0) "scrolling" else "idle"})"
        } else {
            "no match"
        }

        // Current scroll position
        stationCarousel?.let { recyclerView ->
            val layoutManager = recyclerView.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
            val firstVisible = layoutManager?.findFirstVisibleItemPosition() ?: -1
            val lastVisible = layoutManager?.findLastVisibleItemPosition() ?: -1
            debugCarouselPosition?.text = "$firstVisible - $lastVisible"

            // Padding info
            debugCarouselPadding?.text = "${recyclerView.paddingStart}px"
        }
    }

    /**
     * Smoothly scroll carousel to center the item at the given position
     */
    private fun smoothScrollCarouselToCenter(position: Int) {
        android.util.Log.d("fytFM", "smoothScrollCarouselToCenter called with position=$position, carousel=${stationCarousel != null}")
        if (position < 0) return

        stationCarousel?.let { recyclerView ->
            val layoutManager = recyclerView.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
            if (layoutManager == null) {
                android.util.Log.e("fytFM", "LayoutManager is null!")
                return@let
            }

            // Calculate offset to center the item
            val cardWidth = (216 * resources.displayMetrics.density).toInt()
            val recyclerWidth = recyclerView.width

            // If RecyclerView not yet laid out, post the scroll
            if (recyclerWidth <= 0) {
                android.util.Log.d("fytFM", "Carousel not yet laid out, posting scroll")
                recyclerView.post {
                    smoothScrollCarouselToCenter(position)
                }
                return@let
            }

            // The RecyclerView already has padding for centering, so offset should be 0
            // This places the item at the start of the content area (after the left padding)
            android.util.Log.d("fytFM", "Carousel scroll: pos=$position, recyclerWidth=$recyclerWidth, padding=${recyclerView.paddingLeft}")

            // Use scrollToPositionWithOffset with offset 0 - padding handles centering
            layoutManager.scrollToPositionWithOffset(position, 0)
            android.util.Log.d("fytFM", "Carousel scrollToPositionWithOffset pos=$position, offset=0 DONE")
        }
    }

    /**
     * Start timer to return carousel to current station after 2 seconds of idle
     */
    private fun startCarouselReturnTimer() {
        cancelCarouselReturnTimer()

        // Set debug info variables
        val currentFreq = frequencyScale.getFrequency()
        val isAM = frequencyScale.getMode() == FrequencyScaleView.RadioMode.AM
        carouselPendingCenterPosition = stationCarouselAdapter?.getPositionForFrequency(currentFreq, isAM) ?: -1
        carouselCenterTimerStart = System.currentTimeMillis()
        updateCarouselDebugInfo()

        // Start periodic debug update
        carouselDebugUpdateRunnable = object : Runnable {
            override fun run() {
                updateCarouselDebugInfo()
                if (carouselPendingCenterPosition >= 0) {
                    carouselCenterHandler.postDelayed(this, 100)
                }
            }
        }
        carouselCenterHandler.postDelayed(carouselDebugUpdateRunnable!!, 100)

        carouselReturnRunnable = Runnable {
            val pos = carouselPendingCenterPosition
            android.util.Log.d("fytFM", "Carousel return timer: scrolling to position $pos")
            if (pos >= 0) {
                smoothScrollCarouselToCenter(pos)
            }
            carouselPendingCenterPosition = -1
            updateCarouselDebugInfo()
        }
        carouselCenterHandler.postDelayed(carouselReturnRunnable!!, 2000)
        android.util.Log.d("fytFM", "Carousel return timer started (2s)")
    }

    /**
     * Cancel pending carousel return timer
     */
    private fun cancelCarouselReturnTimer() {
        carouselReturnRunnable?.let {
            carouselCenterHandler.removeCallbacks(it)
            android.util.Log.d("fytFM", "Carousel return timer cancelled")
        }
        carouselReturnRunnable = null

        // Also cancel debug update runnable
        carouselDebugUpdateRunnable?.let {
            carouselCenterHandler.removeCallbacks(it)
        }
        carouselDebugUpdateRunnable = null

        carouselPendingCenterPosition = -1
        updateCarouselDebugInfo()
    }

    /**
     * Update carousel favorite icon state
     */
    private fun updateCarouselFavoriteIcon() {
        val currentFreq = frequencyScale.getFrequency()
        val isAM = frequencyScale.getMode() == FrequencyScaleView.RadioMode.AM
        val stations = if (isAM) presetRepository.loadAmStations() else presetRepository.loadFmStations()
        val station = stations.find { Math.abs(it.frequency - currentFreq) < 0.05f }
        val isFavorite = station?.isFavorite == true

        btnCarouselFavorite?.setImageResource(
            if (isFavorite) R.drawable.ic_favorite_filled else R.drawable.ic_favorite_border
        )
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

    /**
     * Aktualisiert das Debug-Overlay für DAB-Modus mit DAB-spezifischen Infos
     */
    fun updateDabDebugInfo(dabStation: at.planqton.fytfm.dab.DabStation? = null, dls: String? = null) {
        if (debugOverlay?.visibility != View.VISIBLE) return

        // Header auf DAB Debug ändern
        findViewById<android.widget.TextView>(R.id.debugHeader)?.text = "DAB+ Debug"

        // Labels für DAB ändern
        debugPsLabel?.text = "Label:"
        debugPiLabel?.text = "SID:"
        debugPtyLabel?.text = "Block:"
        debugRtLabel?.text = "DLS:"
        debugRssiLabel?.text = "SNR:"
        debugAfLabel?.text = "EID:"

        dabStation?.let { station ->
            // Service Label
            debugPs?.text = station.serviceLabel.ifEmpty { "--------" }
            // Service ID (hex format)
            debugPi?.text = String.format("0x%04X", station.serviceId)
            // Channel Block (5A, 5C, 6A, etc.)
            val block = frequencyToChannelBlock(station.ensembleFrequencyKHz)
            debugPty?.text = block
            // Ensemble Frequenz mit Block (ensembleFrequencyKHz is actually in Hz)
            debugFreq?.text = "$block (${String.format("%.3f", station.ensembleFrequencyKHz / 1000000.0f)} MHz)"
            // Ensemble ID und Label
            debugAf?.text = String.format("0x%04X %s", station.ensembleId, station.ensembleLabel)
            // TP/TA wird für Quality genutzt
            debugTpTa?.text = ""
            debugAfUsing?.text = ""
        }

        // DLS (Dynamic Label)
        dls?.let { debugRt?.text = it.ifEmpty { "--------------------------------" } }
    }

    /**
     * Aktualisiert die DAB Empfangsstatistiken im Debug-Overlay
     */
    private fun updateDabReceptionStats(sync: Boolean, quality: String, snr: Int) {
        if (debugOverlay?.visibility != View.VISIBLE) return
        // SNR im RSSI-Feld anzeigen
        val syncStatus = if (sync) "✓" else "✗"
        debugRssi?.text = "$snr dB $syncStatus"
        // Quality im TP/TA-Feld
        debugTpTa?.text = quality
    }

    /**
     * Convert DAB frequency (kHz) to channel block name (e.g., 5A, 5B, 5C, 6A, etc.)
     * Band III: 5A starts at 174.928 MHz, channel spacing is 1.712 MHz
     */
    private fun frequencyToChannelBlock(frequencyHz: Int): String {
        // Note: Despite the old name "kHz", the value from OMRI is actually in Hz
        val freqMhz = frequencyHz / 1000000.0
        val index = kotlin.math.round((freqMhz - 174.928) / 1.712).toInt()

        if (index < 0 || index > 40) return "?"

        val channelNumber = 5 + (index / 4)
        val subChannel = "ABCD"[index % 4]

        return "$channelNumber$subChannel"
    }

    /**
     * Setzt das Debug-Overlay zurück auf FM/AM RDS-Modus
     */
    fun resetDebugToRds() {
        findViewById<android.widget.TextView>(R.id.debugHeader)?.text = "RDS Debug"
        // Labels zurück auf RDS
        debugPsLabel?.text = "PS:"
        debugPiLabel?.text = "PI:"
        debugPtyLabel?.text = "PTY:"
        debugRtLabel?.text = "RT:"
        debugRssiLabel?.text = "RSSI:"
        debugAfLabel?.text = "AF:"
        // Werte zurücksetzen
        debugPs?.text = "--------"
        debugPi?.text = "----"
        debugPty?.text = "--"
        debugRt?.text = "--------------------------------"
        debugRssi?.text = "-- dBm"
        debugFreq?.text = "-- MHz"
        debugAf?.text = "----"
        debugTpTa?.text = "--/--"
        debugAfUsing?.text = ""
    }

    private fun setupStationList() {
        stationAdapter = StationAdapter(
            onStationClick = { station ->
                // Tune to selected station
                val currentMode = frequencyScale.getMode()
                try {
                    if (station.isDab || currentMode == FrequencyScaleView.RadioMode.DAB) {
                        // DAB: tune via DabTunerManager
                        currentDabServiceId = station.serviceId
                        currentDabEnsembleId = station.ensembleId
                        val success = dabTunerManager.tuneService(station.serviceId, station.ensembleId)
                        if (!success) {
                            android.widget.Toast.makeText(this, "Tuner Error: DAB Sender nicht gefunden", android.widget.Toast.LENGTH_LONG).show()
                        }
                        updateCarouselSelection()
                        stationAdapter.setSelectedDabService(station.serviceId)
                        // Carousel zum Sender scrollen
                        val carouselPosition = stationCarouselAdapter?.getPositionForDabService(station.serviceId) ?: -1
                        if (carouselPosition >= 0) {
                            smoothScrollCarouselToCenter(carouselPosition)
                        }
                    } else {
                        if (station.isAM) {
                            frequencyScale.setMode(FrequencyScaleView.RadioMode.AM)
                        } else {
                            frequencyScale.setMode(FrequencyScaleView.RadioMode.FM)
                        }
                        frequencyScale.setFrequency(station.frequency)
                        stationAdapter.setSelectedFrequency(station.frequency)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("fytFM", "Station tune error: ${e.message}")
                    android.widget.Toast.makeText(this, "Tuner Error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            },
            onStationLongClick = { station ->
                // Long press opens radio editor for the station's mode
                val mode = when {
                    station.isDab -> FrequencyScaleView.RadioMode.DAB
                    station.isAM -> FrequencyScaleView.RadioMode.AM
                    else -> FrequencyScaleView.RadioMode.FM
                }
                showRadioEditorDialog(mode)
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
            // Reset Spotify debug overlay
            updateDeezerDebugInfo("Waiting...", null, null, null, null)
            // Reset displayed track
            lastDisplayedTrackId = null
            // Log station change
            val isAM = frequencyScale.getMode() == FrequencyScaleView.RadioMode.AM
            rdsLogRepository.onStationChange(frequency, isAM)

            // Get saved station for this frequency
            val stations = if (isAM) presetRepository.loadAmStations() else presetRepository.loadFmStations()
            val savedStation = stations.find { Math.abs(it.frequency - frequency) < 0.05f }
            val savedStationName = savedStation?.name

            // Get radio logo for this station
            val radioLogoPath = radioLogoRepository.getLogoForStation(savedStationName, null, frequency)

            // Update Now Playing bar with station info (logo + name)
            resetNowPlayingBarForStation(savedStationName, radioLogoPath, frequency, isAM)

            // Update MediaService with new frequency, saved station name and radio logo immediately
            android.util.Log.d("fytFM", "Station change: freq=$frequency, stationName=$savedStationName, radioLogoPath=$radioLogoPath")
            FytFMMediaService.instance?.updateMetadata(
                frequency = frequency,
                ps = savedStationName,  // Use saved station name instead of null
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
            // Update Spotify toggle for this station
            updateDeezerToggleForCurrentFrequency()
            // Update carousel selection if in carousel mode
            if (isCarouselMode) {
                updateCarouselSelection()
                // Start timer to scroll to new station after 2 seconds
                startCarouselReturnTimer()
            }
        }

        frequencyScale.setOnModeChangeListener { mode ->
            updateModeSpinner()
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

        // Radio mode spinner is set up in setupRadioModeSpinner()

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

        // Long press on spinner for debug tune
        spinnerRadioMode.setOnLongClickListener {
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

        // === FM Settings ===
        // FM Power on startup toggle
        val switchPowerFm = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchPowerOnStartupFm)
        switchPowerFm.isChecked = presetRepository.isPowerOnStartupFm()
        switchPowerFm.setOnCheckedChangeListener { _, isChecked ->
            presetRepository.setPowerOnStartupFm(isChecked)
        }

        // FM Radio Editor click
        dialogView.findViewById<View>(R.id.itemRadioEditorFm).setOnClickListener {
            dialog.dismiss()
            showRadioEditorDialog(FrequencyScaleView.RadioMode.FM)
        }

        // === AM Settings ===
        // AM Power on startup toggle
        val switchPowerAm = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchPowerOnStartupAm)
        switchPowerAm.isChecked = presetRepository.isPowerOnStartupAm()
        switchPowerAm.setOnCheckedChangeListener { _, isChecked ->
            presetRepository.setPowerOnStartupAm(isChecked)
        }

        // AM Radio Editor click
        dialogView.findViewById<View>(R.id.itemRadioEditorAm).setOnClickListener {
            dialog.dismiss()
            showRadioEditorDialog(FrequencyScaleView.RadioMode.AM)
        }

        // === DAB Settings ===
        // DAB Power on startup toggle
        val switchPowerDab = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchPowerOnStartupDab)
        switchPowerDab.isChecked = presetRepository.isPowerOnStartupDab()
        switchPowerDab.setOnCheckedChangeListener { _, isChecked ->
            presetRepository.setPowerOnStartupDab(isChecked)
        }

        // DAB Radio Editor click
        dialogView.findViewById<View>(R.id.itemRadioEditorDab).setOnClickListener {
            dialog.dismiss()
            showRadioEditorDialog(FrequencyScaleView.RadioMode.DAB)
        }

        // Deezer für DAB toggle
        val switchDeezerDab = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchDeezerDab)
        switchDeezerDab.isChecked = presetRepository.isDeezerEnabledDab()
        switchDeezerDab.setOnCheckedChangeListener { _, isChecked ->
            presetRepository.setDeezerEnabledDab(isChecked)
        }

        // === Universal Settings ===
        // Show debug infos toggle
        val switchDebug = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchShowDebug)
        switchDebug.isChecked = presetRepository.isShowDebugInfos()
        switchDebug.setOnCheckedChangeListener { _, isChecked ->
            presetRepository.setShowDebugInfos(isChecked)
            updateDebugOverlayVisibility()
        }

        // Station Change Toast toggle
        val switchStationChangeToast = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchStationChangeToast)
        switchStationChangeToast.isChecked = presetRepository.isShowStationChangeToast()
        switchStationChangeToast.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // Prüfe Overlay-Permission
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M &&
                    !android.provider.Settings.canDrawOverlays(this)) {
                    // Permission fehlt - Benutzer zu Settings leiten
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Overlay-Berechtigung")
                        .setMessage("fytFM benötigt die Berechtigung \"Über anderen Apps einblenden\" um das Popup anzuzeigen.")
                        .setPositiveButton("Einstellungen öffnen") { _, _ ->
                            val intent = android.content.Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:$packageName")
                            )
                            startActivity(intent)
                        }
                        .setNegativeButton("Abbrechen") { _, _ ->
                            switchStationChangeToast.isChecked = false
                        }
                        .show()
                    return@setOnCheckedChangeListener
                }
                presetRepository.setShowStationChangeToast(true)
                startOverlayServiceIfEnabled()
            } else {
                presetRepository.setShowStationChangeToast(false)
                // Service stoppen
                stopService(android.content.Intent(this, StationChangeOverlayService::class.java))
            }
        }

        // Revert Prev/Next toggle
        val switchRevertPrevNext = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchRevertPrevNext)
        switchRevertPrevNext.isChecked = presetRepository.isRevertPrevNext()
        switchRevertPrevNext.setOnCheckedChangeListener { _, isChecked ->
            presetRepository.setRevertPrevNext(isChecked)
        }

        // LOC Local Mode toggle
        val switchLocalMode = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchLocalMode)
        switchLocalMode.isChecked = presetRepository.isLocalMode()
        switchLocalMode.setOnCheckedChangeListener { _, isChecked ->
            presetRepository.setLocalMode(isChecked)
            fmNative?.setLocalMode(isChecked)
        }

        // Mono Mode toggle
        val switchMonoMode = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchMonoMode)
        switchMonoMode.isChecked = presetRepository.isMonoMode()
        switchMonoMode.setOnCheckedChangeListener { _, isChecked ->
            presetRepository.setMonoMode(isChecked)
            fmNative?.setMonoMode(isChecked)
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

        // Deezer für FM toggle
        val switchDeezerFm = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchDeezerFm)
        switchDeezerFm.isChecked = presetRepository.isDeezerEnabledFm()
        switchDeezerFm.setOnCheckedChangeListener { _, isChecked ->
            presetRepository.setDeezerEnabledFm(isChecked)
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

        // Deezer Cache Enable Switch (Deezer braucht keine API-Credentials!)
        val switchDeezerCache = dialogView.findViewById<android.widget.Switch>(R.id.switchDeezerCache)
        switchDeezerCache?.isChecked = presetRepository.isDeezerCacheEnabled()
        switchDeezerCache?.setOnCheckedChangeListener { _, isChecked ->
            presetRepository.setDeezerCacheEnabled(isChecked)
        }

        // Deezer Cache Stats and Export/Import/Clear
        val tvDeezerCacheStats = dialogView.findViewById<TextView>(R.id.tvDeezerCacheStats)
        val btnExportCache = dialogView.findViewById<TextView>(R.id.btnExportDeezerCache)
        val btnImportCache = dialogView.findViewById<TextView>(R.id.btnImportDeezerCache)
        val btnClearCache = dialogView.findViewById<TextView>(R.id.btnClearDeezerCache)

        // Update cache stats display
        fun updateCacheStats() {
            deezerCache?.let { cache ->
                val (trackCount, coverSize) = cache.getCacheStats()
                val sizeStr = if (coverSize > 1024 * 1024) {
                    "%.1f MB".format(coverSize / 1024.0 / 1024.0)
                } else {
                    "%.1f KB".format(coverSize / 1024.0)
                }
                tvDeezerCacheStats?.text = "Cache: $trackCount Tracks ($sizeStr)"
            }
        }
        updateCacheStats()

        // Clear cache button
        btnClearCache?.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Cache löschen")
                .setMessage("Alle gecachten Tracks und Cover-Bilder werden gelöscht. Fortfahren?")
                .setPositiveButton("Löschen") { _, _ ->
                    deezerCache?.clearCache()
                    updateCacheStats()
                    android.widget.Toast.makeText(this, "Cache gelöscht", android.widget.Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Abbrechen", null)
                .show()
        }

        // Export cache button
        btnExportCache?.setOnClickListener {
            val intent = android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(android.content.Intent.CATEGORY_OPENABLE)
                type = "application/zip"
                putExtra(android.content.Intent.EXTRA_TITLE, "deezer_cache_${System.currentTimeMillis()}.zip")
            }
            deezerCacheExportLauncher.launch(intent)
        }

        // Import cache button
        btnImportCache?.setOnClickListener {
            val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(android.content.Intent.CATEGORY_OPENABLE)
                type = "application/zip"
            }
            deezerCacheImportLauncher.launch(intent)
        }

        // View cache button
        dialogView.findViewById<TextView>(R.id.btnViewDeezerCache)?.setOnClickListener {
            dialog.dismiss()
            showDeezerCacheDialog()
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

        // Bug Report item
        dialogView.findViewById<View>(R.id.itemBugReport).setOnClickListener {
            dialog.dismiss()
            showBugReportInputDialog()
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

    private fun exportDeezerCacheToUri(uri: android.net.Uri) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Create temp file for zip
                val tempFile = java.io.File(cacheDir, "deezer_cache_export.zip")
                val success = deezerCache?.exportToZip(tempFile) ?: false

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

    private fun importDeezerCacheFromUri(uri: android.net.Uri) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Copy user file to temp location
                val tempFile = java.io.File(cacheDir, "deezer_cache_import.zip")
                contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                val importedCount = deezerCache?.importFromZip(tempFile) ?: -1
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

    private fun showDeezerCacheDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_deezer_cache, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        val tvCount = dialogView.findViewById<TextView>(R.id.tvCacheCount)
        val etSearch = dialogView.findViewById<android.widget.EditText>(R.id.etCacheSearch)
        val rvTracks = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvCacheTracks)
        val tvEmpty = dialogView.findViewById<TextView>(R.id.tvCacheEmpty)
        val btnClose = dialogView.findViewById<TextView>(R.id.btnCloseCache)

        val adapter = at.planqton.fytfm.deezer.CachedTrackAdapter { track ->
            // On track click - open Deezer URL if available
            track.deezerUrl?.let { url ->
                try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    startActivity(intent)
                } catch (e: Exception) {
                    android.widget.Toast.makeText(this, "Kann Deezer nicht öffnen", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }

        rvTracks.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        rvTracks.adapter = adapter

        // Load tracks
        val tracks = deezerCache?.getAllCachedTracks() ?: emptyList()
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
                val findText = inputFind.text.toString()  // Don't trim - spaces are intentional (e.g. " mit ")
                if (findText.isNotBlank()) {
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

    private fun showRadioEditorDialog(mode: FrequencyScaleView.RadioMode? = null) {
        val targetMode = mode ?: frequencyScale.getMode()

        val dialogView = layoutInflater.inflate(R.layout.dialog_radio_editor, null)

        val dialog = AlertDialog.Builder(this, R.style.TransparentDialog)
            .setView(dialogView)
            .create()

        val rvStations = dialogView.findViewById<RecyclerView>(R.id.rvStations)
        rvStations.layoutManager = LinearLayoutManager(this)

        val stations = when (targetMode) {
            FrequencyScaleView.RadioMode.FM -> presetRepository.loadFmStations()
            FrequencyScaleView.RadioMode.AM -> presetRepository.loadAmStations()
            FrequencyScaleView.RadioMode.DAB -> presetRepository.loadDabStations()
        }

        val adapter = at.planqton.fytfm.ui.StationEditorAdapter(
            getLogoPath = { ps, pi, freq -> radioLogoRepository.getLogoForStation(ps, pi, freq) },
            onEdit = { station ->
                if (targetMode == FrequencyScaleView.RadioMode.DAB) {
                    showEditDabStationDialog(station) { newName ->
                        // Update DAB station name
                        val updatedStations = stations.map {
                            if (it.serviceId == station.serviceId) it.copy(name = newName) else it
                        }
                        presetRepository.saveDabStations(updatedStations)
                        loadStationsForCurrentMode()
                        dialog.dismiss()
                        showRadioEditorDialog(targetMode)
                    }
                } else {
                    showEditStationDialog(station) { newName, syncName ->
                        // Update station name and syncName
                        val updatedStations = stations.map {
                            if (it.frequency == station.frequency) it.copy(name = newName, syncName = syncName) else it
                        }
                        saveStationsForMode(updatedStations, targetMode)
                        loadStationsForCurrentMode()
                        // Wenn Sync aktiviert wurde, Cache leeren damit nächster PS-Update synct
                        if (syncName) {
                            val freqKey = (station.frequency * 10).toInt()
                            lastSyncedPs.remove(freqKey)
                        }
                        // Dialog neu öffnen mit aktualisierter Liste
                        dialog.dismiss()
                        showRadioEditorDialog(targetMode)
                    }
                }
            },
            onFavorite = { station ->
                if (targetMode == FrequencyScaleView.RadioMode.DAB) {
                    val updatedStations = stations.map {
                        if (it.serviceId == station.serviceId) it.copy(isFavorite = !it.isFavorite) else it
                    }
                    presetRepository.saveDabStations(updatedStations)
                } else {
                    val updatedStations = stations.map {
                        if (it.frequency == station.frequency) it.copy(isFavorite = !it.isFavorite) else it
                    }
                    saveStationsForMode(updatedStations, targetMode)
                }
                loadStationsForCurrentMode()
                // Dialog neu öffnen mit aktualisierter Liste
                dialog.dismiss()
                showRadioEditorDialog(targetMode)
            },
            onDelete = { station ->
                val stationName = if (targetMode == FrequencyScaleView.RadioMode.DAB) {
                    station.name ?: station.ensembleLabel ?: "Unbekannt"
                } else {
                    station.getDisplayFrequency()
                }
                AlertDialog.Builder(this)
                    .setTitle("Sender löschen")
                    .setMessage("$stationName wirklich löschen?")
                    .setPositiveButton("Ja") { _, _ ->
                        if (targetMode == FrequencyScaleView.RadioMode.DAB) {
                            val updatedStations = stations.filter { it.serviceId != station.serviceId }
                            presetRepository.saveDabStations(updatedStations)
                        } else {
                            val updatedStations = stations.filter { it.frequency != station.frequency }
                            saveStationsForMode(updatedStations, targetMode)
                        }
                        loadStationsForCurrentMode()
                        dialog.dismiss()
                        showRadioEditorDialog(targetMode) // Reopen with updated list
                    }
                    .setNegativeButton("Nein", null)
                    .show()
            }
        )
        adapter.setStations(stations)
        rvStations.adapter = adapter

        // EXPERIMENTAL_LOGO_SEARCH: Logo Search Button
        val btnLogoSearch = dialogView.findViewById<android.widget.Button>(R.id.btnLogoSearch)
        btnLogoSearch.setOnClickListener {
            dialog.dismiss()
            showLogoSearchDialog(stations, targetMode)
        }

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    // EXPERIMENTAL_LOGO_SEARCH: Logo Search Dialog
    private fun showLogoSearchDialog(stations: List<at.planqton.fytfm.data.RadioStation>, mode: FrequencyScaleView.RadioMode) {
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Logo Suche")
            .setMessage("Suche Logos für ${stations.size} Sender...")
            .setCancelable(false)
            .create()
        progressDialog.show()

        val logoSearchService = at.planqton.fytfm.data.logo.LogoSearchService()

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val results = logoSearchService.searchLogos(stations) { current, total, stationName ->
                    runOnUiThread {
                        progressDialog.setMessage("$current/$total: $stationName")
                    }
                }

                progressDialog.dismiss()

                // Filter results with found logos
                val foundLogos = results.filter { it.logoUrl != null }

                if (foundLogos.isEmpty()) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Keine Logos gefunden")
                        .setMessage("Für keinen der ${stations.size} Sender konnte ein Logo gefunden werden.")
                        .setPositiveButton("OK") { _, _ ->
                            showRadioEditorDialog(mode)
                        }
                        .show()
                    return@launch
                }

                // Show results dialog
                showLogoSearchResultsDialog(foundLogos, results.size, mode)

            } catch (e: Exception) {
                progressDialog.dismiss()
                android.util.Log.e("fytFM", "Logo search failed: ${e.message}", e)
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Fehler")
                    .setMessage("Logo-Suche fehlgeschlagen: ${e.message}")
                    .setPositiveButton("OK") { _, _ ->
                        showRadioEditorDialog(mode)
                    }
                    .show()
            }
        }
    }

    // EXPERIMENTAL_LOGO_SEARCH: Results Dialog - Tap to accept, item disappears
    private fun showLogoSearchResultsDialog(
        foundLogos: List<at.planqton.fytfm.data.logo.LogoSearchService.LogoSearchResult>,
        totalSearched: Int,
        mode: FrequencyScaleView.RadioMode
    ) {
        // Mutable list to track remaining logos
        val remainingLogos = foundLogos.toMutableList()
        var savedCount = 0

        fun updateAndShowDialog() {
            if (remainingLogos.isEmpty()) {
                // All done
                if (savedCount > 0) {
                    android.widget.Toast.makeText(
                        this,
                        "$savedCount Logo(s) gespeichert",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                showRadioEditorDialog(mode)
                return
            }

            val container = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(32, 16, 32, 16)
            }

            // Summary
            val summaryText = android.widget.TextView(this).apply {
                text = "Tippe zum Übernehmen (${remainingLogos.size} übrig, $savedCount gespeichert)"
                textSize = 13f
                setTextColor(0xFF666666.toInt())
                setPadding(0, 0, 0, 16)
            }
            container.addView(summaryText)

            // ScrollView for results
            val scrollView = android.widget.ScrollView(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    400.dpToPx()
                )
            }

            val resultsList = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
            }

            for (result in remainingLogos.toList()) {
                val itemLayout = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    setPadding(16, 12, 16, 12)
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    isClickable = true
                    isFocusable = true
                    background = getDrawable(android.R.drawable.list_selector_background)
                }

                // Preview image
                val imageView = android.widget.ImageView(this).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(56.dpToPx(), 56.dpToPx()).apply {
                        marginEnd = 16.dpToPx()
                    }
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(0xFFEEEEEE.toInt())
                }
                imageView.load(result.logoUrl) {
                    crossfade(true)
                    placeholder(R.drawable.ic_cover_placeholder)
                    error(R.drawable.ic_cover_placeholder)
                }
                itemLayout.addView(imageView)

                // Station info
                val infoLayout = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val nameText = android.widget.TextView(this).apply {
                    text = result.stationName
                    textSize = 16f
                    setTextColor(0xFF333333.toInt())
                }
                infoLayout.addView(nameText)

                val sourceText = android.widget.TextView(this).apply {
                    text = result.source
                    textSize = 12f
                    setTextColor(0xFF888888.toInt())
                }
                infoLayout.addView(sourceText)

                itemLayout.addView(infoLayout)

                // Arrow icon to indicate clickable
                val arrowIcon = android.widget.ImageView(this).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(24.dpToPx(), 24.dpToPx())
                    setImageResource(android.R.drawable.ic_input_add)
                    setColorFilter(0xFF4CAF50.toInt())
                }
                itemLayout.addView(arrowIcon)

                resultsList.addView(itemLayout)

                // Divider
                val divider = android.view.View(this).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1
                    )
                    setBackgroundColor(0xFFEEEEEE.toInt())
                }
                resultsList.addView(divider)
            }

            scrollView.addView(resultsList)
            container.addView(scrollView)

            val dialog = AlertDialog.Builder(this)
                .setTitle("Logo Suchergebnisse")
                .setView(container)
                .setNegativeButton("Fertig") { dlg, _ ->
                    dlg.dismiss()
                    if (savedCount > 0) {
                        android.widget.Toast.makeText(
                            this,
                            "$savedCount Logo(s) gespeichert",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    showRadioEditorDialog(mode)
                }
                .setOnCancelListener {
                    if (savedCount > 0) {
                        android.widget.Toast.makeText(
                            this,
                            "$savedCount Logo(s) gespeichert",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    showRadioEditorDialog(mode)
                }
                .create()

            // Set click listeners for each item after dialog is created
            var itemIndex = 0
            for (i in 0 until resultsList.childCount step 2) { // Skip dividers
                val itemLayout = resultsList.getChildAt(i)
                val resultIndex = itemIndex
                if (resultIndex < remainingLogos.size) {
                    val result = remainingLogos[resultIndex]
                    itemLayout.setOnClickListener {
                        // Save this logo
                        saveSingleLogo(result, mode)
                        savedCount++
                        remainingLogos.remove(result)
                        dialog.dismiss()
                        updateAndShowDialog()
                    }
                }
                itemIndex++
            }

            dialog.show()
        }

        updateAndShowDialog()
    }

    // EXPERIMENTAL_LOGO_SEARCH: Save a single logo
    private fun saveSingleLogo(
        result: at.planqton.fytfm.data.logo.LogoSearchService.LogoSearchResult,
        mode: FrequencyScaleView.RadioMode
    ) {
        val templateName = "Auto-Search-${mode.name}"
        val logoUrl = result.logoUrl ?: return
        val station = result.station

        // Get or create template
        var template = radioLogoRepository.getTemplates().find { it.name == templateName }
        val existingStations = template?.stations?.toMutableList() ?: mutableListOf()

        // Create StationLogo entry
        val stationLogo = if (station.isDab) {
            at.planqton.fytfm.data.logo.StationLogo(
                ps = station.name,
                logoUrl = logoUrl
            )
        } else {
            at.planqton.fytfm.data.logo.StationLogo(
                ps = station.name,
                frequencies = listOf(station.frequency),
                logoUrl = logoUrl
            )
        }

        // Remove existing entry for this station (if any)
        existingStations.removeAll { existing ->
            if (station.isDab) {
                existing.ps == station.name
            } else {
                existing.frequencies?.any { Math.abs(it - station.frequency) < 0.05f } == true
            }
        }

        existingStations.add(stationLogo)

        // Save template
        val newTemplate = at.planqton.fytfm.data.logo.RadioLogoTemplate(
            name = templateName,
            area = 2,
            stations = existingStations
        )
        radioLogoRepository.saveTemplate(newTemplate)

        // Set as active if no active template
        if (radioLogoRepository.getActiveTemplateName() == null) {
            radioLogoRepository.setActiveTemplate(templateName)
        }

        // Download this single logo in background
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val (updatedTemplate, _) = radioLogoRepository.downloadLogos(newTemplate) { _, _ -> }
                radioLogoRepository.saveTemplate(updatedTemplate)
            } catch (e: Exception) {
                android.util.Log.e("fytFM", "Failed to download logo: ${e.message}", e)
            }
        }
    }

    // Helper extension for dp to px conversion
    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    private fun showEditStationDialog(station: at.planqton.fytfm.data.RadioStation, onSave: (String, Boolean) -> Unit) {
        // Create container for name input and toggles
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        // Name input
        val input = android.widget.EditText(this).apply {
            setText(station.name ?: "")
            hint = "Sendername"
        }
        container.addView(input)

        // Sync Name checkbox
        val syncNameCheckbox = android.widget.CheckBox(this).apply {
            text = "Sync mit RDS (PS)"
            isChecked = station.syncName
            setPadding(0, 24, 0, 0)
        }
        container.addView(syncNameCheckbox)

        // Spotify toggle
        val deezerEnabled = presetRepository.isDeezerEnabledForFrequency(station.frequency)
        val deezerSwitch = android.widget.Switch(this).apply {
            text = "Deezer/Lokal Suche"
            isChecked = deezerEnabled
            setPadding(0, 16, 0, 0)
        }
        container.addView(deezerSwitch)

        AlertDialog.Builder(this)
            .setTitle("Sender bearbeiten")
            .setMessage(station.getDisplayFrequency())
            .setView(container)
            .setPositiveButton("Speichern") { _, _ ->
                // Save station name and syncName
                onSave(input.text.toString(), syncNameCheckbox.isChecked)
                // Save Spotify setting for this frequency
                presetRepository.setDeezerEnabledForFrequency(station.frequency, deezerSwitch.isChecked)
                // Update toggle if this is current frequency
                if (Math.abs(frequencyScale.getFrequency() - station.frequency) < 0.05f) {
                    updateDeezerToggleForCurrentFrequency()
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun showEditDabStationDialog(station: at.planqton.fytfm.data.RadioStation, onSave: (String) -> Unit) {
        // Create container for name input
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        // Info text (Ensemble)
        val infoText = android.widget.TextView(this).apply {
            text = "Ensemble: ${station.ensembleLabel ?: "Unbekannt"}"
            setTextColor(resources.getColor(android.R.color.darker_gray, theme))
            setPadding(0, 0, 0, 16)
        }
        container.addView(infoText)

        // Name input
        val input = android.widget.EditText(this).apply {
            setText(station.name ?: "")
            hint = "Sendername"
        }
        container.addView(input)

        AlertDialog.Builder(this)
            .setTitle("DAB Sender bearbeiten")
            .setView(container)
            .setPositiveButton("Speichern") { _, _ ->
                onSave(input.text.toString())
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun showStationScanDialog() {
        // DAB-Modus: eigenen Scan-Dialog
        if (frequencyScale.getMode() == FrequencyScaleView.RadioMode.DAB) {
            showDabScanDialog()
            return
        }
        val isFmMode = frequencyScale.getMode() == FrequencyScaleView.RadioMode.FM
        val highSensitivity = presetRepository.isAutoScanSensitivity()

        // Erst ScanOptionsDialog anzeigen für Methode + Filter-Einstellungen
        at.planqton.fytfm.ui.ScanOptionsDialog(this, presetRepository) { config ->
            // Nach Auswahl den eigentlichen Scan-Dialog öffnen
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
                highSensitivity = highSensitivity,
                config = config
            )
            dialog.show()
        }.show()
    }

    private fun showDabScanDialog() {
        if (!dabTunerManager.isDabOn) {
            val msg = if (!dabTunerManager.hasTuner()) {
                "Kein DAB+ USB-Dongle gefunden. Bitte anschließen."
            } else {
                "DAB+ Tuner ist nicht aktiv. Bitte zuerst einschalten."
            }
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
            return
        }

        at.planqton.fytfm.ui.DabScanDialog(this, dabTunerManager) { services ->
            val stations = services.map { it.toRadioStation() }
            presetRepository.mergeDabScannedStations(stations)
            loadStationsForCurrentMode()
            populateCarousel()
            android.widget.Toast.makeText(this, "${services.size} DAB+ Sender gefunden", android.widget.Toast.LENGTH_SHORT).show()
        }.show()
    }

    private fun saveStations(stations: List<at.planqton.fytfm.data.RadioStation>) {
        when (frequencyScale.getMode()) {
            FrequencyScaleView.RadioMode.FM -> presetRepository.saveFmStations(stations)
            FrequencyScaleView.RadioMode.AM -> presetRepository.saveAmStations(stations)
            FrequencyScaleView.RadioMode.DAB -> presetRepository.saveDabStations(stations)
        }
    }

    private fun saveStationsForMode(stations: List<at.planqton.fytfm.data.RadioStation>, mode: FrequencyScaleView.RadioMode) {
        when (mode) {
            FrequencyScaleView.RadioMode.FM -> presetRepository.saveFmStations(stations)
            FrequencyScaleView.RadioMode.AM -> presetRepository.saveAmStations(stations)
            FrequencyScaleView.RadioMode.DAB -> presetRepository.saveDabStations(stations)
        }
    }

    private fun loadStationsForCurrentMode() {
        val mode = frequencyScale.getMode()
        android.util.Log.i("fytFM", "=== loadStationsForCurrentMode: mode=$mode ===")

        val allStations = when (mode) {
            FrequencyScaleView.RadioMode.FM -> presetRepository.loadFmStations()
            FrequencyScaleView.RadioMode.AM -> presetRepository.loadAmStations()
            FrequencyScaleView.RadioMode.DAB -> presetRepository.loadDabStations()
        }

        android.util.Log.i("fytFM", "=== loadStationsForCurrentMode: loaded ${allStations.size} stations for $mode ===")

        // Filter anwenden wenn aktiviert
        val stations = if (showFavoritesOnly) {
            allStations.filter { it.isFavorite }
        } else {
            allStations
        }

        stationAdapter.setStations(stations)
        if (mode == FrequencyScaleView.RadioMode.DAB) {
            stationAdapter.setSelectedDabService(currentDabServiceId)
        } else {
            stationAdapter.setSelectedFrequency(frequencyScale.getFrequency())
        }
    }

    // Scanner-Funktionen deaktiviert

    private fun skipToPreviousStation() {
        if (presetRepository.isRevertPrevNext()) {
            doSkipToNext()
        } else {
            doSkipToPrevious()
        }
    }

    private fun skipToNextStation() {
        if (presetRepository.isRevertPrevNext()) {
            doSkipToPrevious()
        } else {
            doSkipToNext()
        }
    }

    private fun doSkipToPrevious() {
        val stations = stationAdapter.getStations()
        android.util.Log.d("fytFM", "skipToPreviousStation: ${stations.size} stations available")
        if (stations.isEmpty()) return

        if (frequencyScale.getMode() == FrequencyScaleView.RadioMode.DAB) {
            skipDabStation(forward = false)
            return
        }

        val currentFreq = frequencyScale.getFrequency()
        val prevStation = stations.lastOrNull { it.frequency < currentFreq - 0.05f }
            ?: stations.lastOrNull()

        prevStation?.let {
            android.util.Log.i("fytFM", "skipToPreviousStation: ${currentFreq} -> ${it.frequency} MHz")
            val oldFreq = currentFreq
            frequencyScale.setFrequency(it.frequency)
            showStationChangeOverlay(it.frequency, oldFreq)
        }
    }

    private fun doSkipToNext() {
        val stations = stationAdapter.getStations()
        android.util.Log.d("fytFM", "skipToNextStation: ${stations.size} stations available")
        if (stations.isEmpty()) return

        if (frequencyScale.getMode() == FrequencyScaleView.RadioMode.DAB) {
            skipDabStation(forward = true)
            return
        }

        val currentFreq = frequencyScale.getFrequency()
        val nextStation = stations.firstOrNull { it.frequency > currentFreq + 0.05f }
            ?: stations.firstOrNull()

        nextStation?.let {
            android.util.Log.i("fytFM", "skipToNextStation: ${currentFreq} -> ${it.frequency} MHz")
            val oldFreq = currentFreq
            frequencyScale.setFrequency(it.frequency)
            showStationChangeOverlay(it.frequency, oldFreq)
        }
    }

    private fun skipDabStation(forward: Boolean) {
        val dabStations = presetRepository.loadDabStations()
        if (dabStations.isEmpty()) return

        val oldServiceId = currentDabServiceId
        val currentIndex = dabStations.indexOfFirst { it.serviceId == currentDabServiceId }
        val newIndex = if (forward) {
            if (currentIndex < 0 || currentIndex >= dabStations.size - 1) 0 else currentIndex + 1
        } else {
            if (currentIndex <= 0) dabStations.size - 1 else currentIndex - 1
        }

        val newStation = dabStations[newIndex]
        try {
            currentDabServiceId = newStation.serviceId
            currentDabEnsembleId = newStation.ensembleId
            val success = dabTunerManager.tuneService(newStation.serviceId, newStation.ensembleId)
            if (!success) {
                android.widget.Toast.makeText(this, "Tuner Error: DAB Sender nicht gefunden", android.widget.Toast.LENGTH_LONG).show()
            }
            updateCarouselSelection()
            updateFavoriteButton()
            // Show station change overlay
            showDabStationChangeOverlay(newStation.serviceId, oldServiceId)
            android.util.Log.i("fytFM", "skipDabStation: -> ${newStation.name} (SID=${newStation.serviceId})")
        } catch (e: Exception) {
            android.util.Log.e("fytFM", "skipDabStation error: ${e.message}")
            android.widget.Toast.makeText(this, "Tuner Error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun showStationChangeOverlay(frequency: Float, oldFrequency: Float = 0f) {
        if (!presetRepository.isShowStationChangeToast()) return

        val isAM = frequencyScale.getMode() == FrequencyScaleView.RadioMode.AM

        // Build stations JSON
        val stationsJson = try {
            val jsonArray = org.json.JSONArray()
            val stations = stationAdapter.getStations()
            stations.forEach { station ->
                val logoPath = radioLogoRepository.getLogoForStation(station.name, null, station.frequency)
                val obj = org.json.JSONObject().apply {
                    put("frequency", station.frequency.toDouble())
                    put("name", station.name ?: "")
                    put("logoPath", logoPath ?: "")
                    put("isAM", station.isAM)
                }
                jsonArray.put(obj)
            }
            jsonArray.toString()
        } catch (e: Exception) {
            android.util.Log.e("fytFM", "Error building stations JSON: ${e.message}")
            null
        }

        val intent = android.content.Intent(this, StationChangeOverlayService::class.java).apply {
            action = StationChangeOverlayService.ACTION_SHOW_OVERLAY
            putExtra(StationChangeOverlayService.EXTRA_FREQUENCY, frequency)
            putExtra(StationChangeOverlayService.EXTRA_OLD_FREQUENCY, oldFrequency)
            putExtra(StationChangeOverlayService.EXTRA_IS_AM, isAM)
            putExtra(StationChangeOverlayService.EXTRA_APP_IN_FOREGROUND, isAppInForeground)
            stationsJson?.let { putExtra(StationChangeOverlayService.EXTRA_STATIONS, it) }
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            android.util.Log.e("fytFM", "Failed to show overlay: ${e.message}")
        }
    }

    private fun showDabStationChangeOverlay(serviceId: Int, oldServiceId: Int = 0) {
        if (!presetRepository.isShowStationChangeToast()) return

        // Build DAB stations JSON
        val stationsJson = try {
            val jsonArray = org.json.JSONArray()
            val dabStations = presetRepository.loadDabStations()
            dabStations.forEach { station ->
                val logoPath = radioLogoRepository.getLogoForStation(station.name, null, 0f)
                val obj = org.json.JSONObject().apply {
                    put("frequency", 0.0)
                    put("name", station.name ?: "")
                    put("logoPath", logoPath ?: "")
                    put("isAM", false)
                    put("isDab", true)
                    put("serviceId", station.serviceId)
                }
                jsonArray.put(obj)
            }
            jsonArray.toString()
        } catch (e: Exception) {
            android.util.Log.e("fytFM", "Error building DAB stations JSON: ${e.message}")
            null
        }

        val intent = android.content.Intent(this, StationChangeOverlayService::class.java).apply {
            action = StationChangeOverlayService.ACTION_SHOW_OVERLAY
            putExtra(StationChangeOverlayService.EXTRA_IS_DAB, true)
            putExtra(StationChangeOverlayService.EXTRA_DAB_SERVICE_ID, serviceId)
            putExtra(StationChangeOverlayService.EXTRA_DAB_OLD_SERVICE_ID, oldServiceId)
            putExtra(StationChangeOverlayService.EXTRA_APP_IN_FOREGROUND, isAppInForeground)
            stationsJson?.let { putExtra(StationChangeOverlayService.EXTRA_STATIONS, it) }
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            android.util.Log.e("fytFM", "Failed to show DAB overlay: ${e.message}")
        }
    }

    private fun showPermanentStationOverlay() {
        val frequency = frequencyScale.getFrequency()
        val isAM = frequencyScale.getMode() == FrequencyScaleView.RadioMode.AM

        // Build stations JSON
        val stationsJson = try {
            val jsonArray = org.json.JSONArray()
            val stations = stationAdapter.getStations()
            stations.forEach { station ->
                val logoPath = radioLogoRepository.getLogoForStation(station.name, null, station.frequency)
                val obj = org.json.JSONObject().apply {
                    put("frequency", station.frequency.toDouble())
                    put("name", station.name ?: "")
                    put("logoPath", logoPath ?: "")
                    put("isAM", station.isAM)
                }
                jsonArray.put(obj)
            }
            jsonArray.toString()
        } catch (e: Exception) {
            android.util.Log.e("fytFM", "Error building stations JSON: ${e.message}")
            null
        }

        val intent = android.content.Intent(this, StationChangeOverlayService::class.java).apply {
            action = StationChangeOverlayService.ACTION_SHOW_PERMANENT
            putExtra(StationChangeOverlayService.EXTRA_FREQUENCY, frequency)
            putExtra(StationChangeOverlayService.EXTRA_IS_AM, isAM)
            stationsJson?.let { putExtra(StationChangeOverlayService.EXTRA_STATIONS, it) }
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            android.util.Log.e("fytFM", "Failed to show permanent overlay: ${e.message}")
        }
    }

    private fun hidePermanentStationOverlay() {
        val intent = android.content.Intent(this, StationChangeOverlayService::class.java).apply {
            action = StationChangeOverlayService.ACTION_HIDE_OVERLAY
        }
        try {
            startService(intent)
        } catch (e: Exception) {
            android.util.Log.e("fytFM", "Failed to hide overlay: ${e.message}")
        }
    }

    private fun updateFrequencyDisplay(frequency: Float) {
        val mode = frequencyScale.getMode()
        tvFrequency.text = when (mode) {
            FrequencyScaleView.RadioMode.FM -> String.format("FM %.2f", frequency)
            FrequencyScaleView.RadioMode.AM -> String.format("AM %d", frequency.toInt())
            FrequencyScaleView.RadioMode.DAB -> {
                // Für DAB: Zeige Sendername statt Frequenz
                val station = presetRepository.loadDabStations().find { it.serviceId == currentDabServiceId }
                station?.name ?: "DAB+"
            }
        }
        // Update debug overlay frequency
        if (mode != FrequencyScaleView.RadioMode.DAB) {
            updateDebugInfo(freq = frequency)
        }
    }

    private fun setupRadioModeSpinner() {
        val adapter = android.widget.ArrayAdapter.createFromResource(
            this, R.array.radio_modes, R.layout.item_radio_mode_spinner
        )
        adapter.setDropDownViewResource(R.layout.item_radio_mode_dropdown)
        spinnerRadioMode.adapter = adapter

        spinnerRadioMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val currentMode = frequencyScale.getMode()
                android.util.Log.i("fytFM", "=== SPINNER onItemSelected: position=$position, currentMode=$currentMode, suppressCallback=$suppressSpinnerCallback ===")

                if (suppressSpinnerCallback) {
                    android.util.Log.i("fytFM", "=== SPINNER: Callback suppressed, skipping ===")
                    suppressSpinnerCallback = false
                    return
                }

                val newMode = when (position) {
                    0 -> FrequencyScaleView.RadioMode.FM
                    1 -> FrequencyScaleView.RadioMode.AM
                    2 -> FrequencyScaleView.RadioMode.DAB
                    else -> FrequencyScaleView.RadioMode.FM
                }

                android.util.Log.i("fytFM", "=== SPINNER: User selected $newMode (current: $currentMode) ===")

                // Nur Info-Toast wenn Tuner nicht verfügbar (blockiert nicht mehr)
                if (!isTunerAvailable(newMode)) {
                    android.widget.Toast.makeText(this@MainActivity, "Tuner für $newMode nicht verfügbar", android.widget.Toast.LENGTH_SHORT).show()
                }

                setRadioMode(newMode)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /**
     * Prüft ob ein bestimmter Tuner verfügbar ist.
     */
    private fun isTunerAvailable(mode: FrequencyScaleView.RadioMode): Boolean {
        return when (mode) {
            FrequencyScaleView.RadioMode.FM, FrequencyScaleView.RadioMode.AM -> FmNative.isLibraryLoaded()
            FrequencyScaleView.RadioMode.DAB -> dabTunerManager.isDabAvailable(this)
        }
    }

    /**
     * Prüft ob der gewünschte Modus verfügbar ist und gibt einen alternativen zurück falls nicht.
     */
    private fun getAvailableMode(preferredMode: FrequencyScaleView.RadioMode): FrequencyScaleView.RadioMode {
        // Falls bevorzugter Modus verfügbar, nutze ihn
        if (isTunerAvailable(preferredMode)) {
            return preferredMode
        }

        android.util.Log.w("fytFM", "Preferred mode $preferredMode not available, searching fallback...")

        // Ansonsten finde ersten verfügbaren (FM -> AM -> DAB)
        if (isTunerAvailable(FrequencyScaleView.RadioMode.FM)) {
            return FrequencyScaleView.RadioMode.FM
        }
        if (isTunerAvailable(FrequencyScaleView.RadioMode.AM)) {
            return FrequencyScaleView.RadioMode.AM
        }
        if (isTunerAvailable(FrequencyScaleView.RadioMode.DAB)) {
            return FrequencyScaleView.RadioMode.DAB
        }

        // Fallback zu FM auch wenn nicht verfügbar
        return FrequencyScaleView.RadioMode.FM
    }

    private fun setRadioMode(mode: FrequencyScaleView.RadioMode, forceRefresh: Boolean = false) {
        val oldMode = frequencyScale.getMode()
        android.util.Log.i("fytFM", "=== setRadioMode: $oldMode -> $mode (forceRefresh=$forceRefresh) ===")

        if (oldMode == mode && !forceRefresh) {
            android.util.Log.i("fytFM", "=== setRadioMode: Same mode, skipping (use forceRefresh=true to override) ===")
            // Trotzdem Stationen aktualisieren, falls es ein Sync-Problem gibt
            loadStationsForCurrentMode()
            if (isCarouselMode) {
                populateCarousel()
                updateCarouselSelection()
            }
            return
        }

        // Alten Modus ordentlich aufräumen
        if (oldMode == FrequencyScaleView.RadioMode.DAB) {
            // Von DAB weg: DAB-Tuner komplett stoppen und schließen
            android.util.Log.i("fytFM", "Stopping DAB tuner...")
            if (isDabOn) {
                try {
                    dabTunerManager.stopService()
                    dabTunerManager.deinitialize()
                } catch (e: Exception) {
                    android.util.Log.e("fytFM", "DAB shutdown error: ${e.message}")
                    android.widget.Toast.makeText(this, "Tuner Error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
                isDabOn = false
                currentDabServiceId = 0
                currentDabEnsembleId = 0
            }
            // Debug-Overlay zurück auf RDS setzen
            resetDebugToRds()
        } else {
            // Von FM/AM weg: FM Radio stoppen
            android.util.Log.i("fytFM", "Stopping FM/AM radio...")
            if (isRadioOn) {
                try {
                    fmNative.powerOff()
                } catch (e: Exception) {
                    android.util.Log.e("fytFM", "FM powerOff error: ${e.message}")
                    android.widget.Toast.makeText(this, "Tuner Error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
                isRadioOn = false
            }
        }

        // Mode ZUERST setzen (wichtig für populateCarousel)
        frequencyScale.setMode(mode)
        android.util.Log.i("fytFM", "=== setRadioMode: frequencyScale.getMode() is now ${frequencyScale.getMode()} (expected: $mode) ===")

        // Neuen Modus initialisieren
        if (mode == FrequencyScaleView.RadioMode.DAB) {
            // Zu DAB: Carousel aktivieren und DAB-Tuner starten
            if (!isCarouselMode) {
                setViewMode(true)
            }
            findViewById<View>(R.id.viewModeToggle)?.visibility = View.GONE

            // DAB-Anzeige initialisieren (Sendername statt Frequenz)
            initDabDisplay()

            // DAB-Tuner starten
            android.util.Log.i("fytFM", "Starting DAB tuner...")
            if (!isDabOn) {
                toggleDabPower()
            }
        } else {
            // Zu FM/AM: View-Mode-Toggle einblenden, FM Radio starten
            findViewById<View>(R.id.viewModeToggle)?.visibility = View.VISIBLE

            // FM Radio starten
            android.util.Log.i("fytFM", "Starting FM/AM radio...")
            if (!isRadioOn) {
                try {
                    val lastFreq = loadLastFrequency()
                    val success = fmNative.powerOn(lastFreq)
                    if (success) {
                        isRadioOn = true
                        frequencyScale.setFrequency(lastFreq)
                    } else {
                        android.widget.Toast.makeText(this, "Tuner Error: FM Radio konnte nicht gestartet werden", android.widget.Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("fytFM", "FM powerOn error: ${e.message}")
                    android.widget.Toast.makeText(this, "Tuner Error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }

        // Spinner aktualisieren ohne Callback
        updateModeSpinner()

        // Stationen laden
        loadFavoritesFilterState()
        loadStationsForCurrentMode()

        // Carousel aktualisieren wenn aktiv
        if (isCarouselMode) {
            populateCarousel()
            updateCarouselSelection()
        }

        updateFavoriteButton()
        updatePowerButton()

        // Modus für nächsten App-Start speichern
        saveLastRadioMode(mode)
    }

    private fun updateModeSpinner() {
        val position = when (frequencyScale.getMode()) {
            FrequencyScaleView.RadioMode.FM -> 0
            FrequencyScaleView.RadioMode.AM -> 1
            FrequencyScaleView.RadioMode.DAB -> 2
        }
        // Nur supprimieren wenn sich die Position wirklich ändert
        if (spinnerRadioMode.selectedItemPosition != position) {
            suppressSpinnerCallback = true
            spinnerRadioMode.setSelection(position, false)
        }
        // Fallback: Nach kurzer Zeit wieder freigeben falls Callback verzögert kommt
        spinnerRadioMode.removeCallbacks(resetSuppressRunnable)
        spinnerRadioMode.postDelayed(resetSuppressRunnable, 150)
    }

    private val resetSuppressRunnable = Runnable {
        if (suppressSpinnerCallback) {
            android.util.Log.w("fytFM", "=== SPINNER: Resetting suppressCallback via timeout ===")
            suppressSpinnerCallback = false
        }
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
        // DAB-Modus: eigene Power-Steuerung
        if (frequencyScale.getMode() == FrequencyScaleView.RadioMode.DAB) {
            toggleDabPower()
            return
        }

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
                    android.widget.Toast.makeText(this, "Tuner Error: FM Radio konnte nicht gestartet werden", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("fytFM", "Radio power toggle failed: ${e.message}", e)
            isRadioOn = false
            android.widget.Toast.makeText(this, "Tuner Error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
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
            android.widget.Toast.makeText(this, "Tuner Error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
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
                        lastDisplayedTrackId = null  // Reset but keep bar visible
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
        val isOn = if (frequencyScale.getMode() == FrequencyScaleView.RadioMode.DAB) isDabOn else isRadioOn
        btnPower.alpha = if (isOn) 1.0f else 0.5f
    }

    /**
     * DAB+ Tuner Ein/Aus schalten.
     */
    private fun toggleDabPower() {
        android.util.Log.i("fytFM", "======= toggleDabPower() =======")

        try {
            if (isDabOn) {
                // DAB ausschalten
                android.util.Log.i("fytFM", "--- DAB Powering OFF ---")
                dabTunerManager.stopService()
                dabTunerManager.deinitialize()
                isDabOn = false
                currentDabServiceId = 0
                currentDabEnsembleId = 0
            } else {
                // DAB einschalten
                android.util.Log.i("fytFM", "--- DAB Powering ON ---")

                // DAB Tuner Manager Callbacks setzen
                dabTunerManager.onTunerReady = {
                    android.util.Log.i("fytFM", "DAB Tuner ready!")
                    android.widget.Toast.makeText(this, "DAB+ Tuner bereit", android.widget.Toast.LENGTH_SHORT).show()

                    // Falls gespeicherte Sender vorhanden, lade sie
                    val dabStations = presetRepository.loadDabStations()
                    if (dabStations.isNotEmpty()) {
                        populateCarousel()

                        // Letzten Sender tunen (oder ersten falls kein letzter)
                        val (lastServiceId, lastEnsembleId) = loadLastDabService()
                        val targetStation = if (lastServiceId > 0) {
                            dabStations.find { it.serviceId == lastServiceId } ?: dabStations.first()
                        } else {
                            dabStations.first()
                        }
                        android.util.Log.i("fytFM", "DAB Tuner ready: tuning to ${targetStation.name} (SID=${targetStation.serviceId})")

                        try {
                            currentDabServiceId = targetStation.serviceId
                            currentDabEnsembleId = targetStation.ensembleId
                            val success = dabTunerManager.tuneService(targetStation.serviceId, targetStation.ensembleId)
                            if (!success) {
                                android.widget.Toast.makeText(this, "Tuner Error: DAB Sender nicht gefunden", android.widget.Toast.LENGTH_LONG).show()
                            }
                            updateCarouselSelection()
                        } catch (e: Exception) {
                            android.util.Log.e("fytFM", "DAB initial tune error: ${e.message}")
                            android.widget.Toast.makeText(this, "Tuner Error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }

                dabTunerManager.onServiceStarted = { dabStation ->
                    android.util.Log.i("fytFM", "DAB Service gestartet: ${dabStation.serviceLabel}")
                    currentDabServiceId = dabStation.serviceId
                    currentDabEnsembleId = dabStation.ensembleId
                    currentDabServiceLabel = dabStation.serviceLabel
                    currentDabEnsembleLabel = dabStation.ensembleLabel
                    currentDabDls = null  // DLS zurücksetzen bei Senderwechsel
                    currentDabSlideshow = null  // Slideshow zurücksetzen
                    saveLastDabService(dabStation.serviceId, dabStation.ensembleId)
                    updateCarouselSelection()
                    stationAdapter.setSelectedDabService(dabStation.serviceId)
                    updateFavoriteButton()
                    // Now Playing Bar für DAB aktualisieren
                    updateDabNowPlaying(dabStation)
                    // Debug Overlay für DAB aktualisieren
                    updateDabDebugInfo(dabStation)
                    // MediaSession für DAB aktualisieren
                    val radioLogoPath = radioLogoRepository.getLogoForStation(dabStation.serviceLabel, null, 0f)
                    FytFMMediaService.instance?.updateDabMetadata(
                        serviceLabel = dabStation.serviceLabel,
                        ensembleLabel = dabStation.ensembleLabel,
                        dls = null,
                        slideshowBitmap = null,
                        radioLogoPath = radioLogoPath
                    )
                }

                dabTunerManager.onServiceStopped = {
                    android.util.Log.i("fytFM", "DAB Service gestoppt")
                }

                dabTunerManager.onTunerError = { error ->
                    android.util.Log.e("fytFM", "DAB Tuner Error: $error")
                    isDabOn = false
                    updatePowerButton()
                    android.widget.Toast.makeText(this, error, android.widget.Toast.LENGTH_LONG).show()
                }

                // DLS (Dynamic Label Segment) - DAB Äquivalent zu RDS RT
                dabTunerManager.onDynamicLabel = { dls ->
                    android.util.Log.d("fytFM", "DLS received: $dls")
                    currentDabDls = dls
                    // Now Playing Bar Title aktualisieren (wie RT bei FM)
                    nowPlayingTitle?.text = dls
                    nowPlayingTitle?.visibility = if (dls.isNotBlank()) View.VISIBLE else View.GONE
                    carouselNowPlayingTitle?.text = dls
                    carouselNowPlayingTitle?.visibility = if (dls.isNotBlank()) View.VISIBLE else View.GONE
                    // Debug Overlay RT-Feld aktualisieren
                    updateDabDebugInfo(dls = dls)

                    // Deezer-Integration für DAB
                    val combiner = rtCombiner
                    val deezerEnabledDab = presetRepository.isDeezerEnabledDab()
                    if (combiner != null && dls.isNotBlank() && !debugDeezerBlocked && deezerEnabledDab) {
                        CoroutineScope(Dispatchers.IO).launch {
                            // PI=0 für DAB, frequency aus aktuellem DAB-Service
                            val dabFreq = dabTunerManager.getCurrentService()?.ensembleFrequencyKHz?.toFloat() ?: 0f
                            val combinedDls = combiner.processRt(0, dls, dabFreq)
                            val trackInfo = if (combinedDls != null) combiner.getLastTrackInfo(0) else null

                            withContext(Dispatchers.Main) {
                                if (trackInfo != null) {
                                    // Deezer hat einen Track gefunden
                                    android.util.Log.d("fytFM", "DAB Deezer found: ${trackInfo.artist} - ${trackInfo.title}")

                                    // Artist/Title in Now Playing Bar anzeigen
                                    nowPlayingArtist?.text = trackInfo.artist ?: ""
                                    nowPlayingArtist?.visibility = if (!trackInfo.artist.isNullOrBlank()) View.VISIBLE else View.GONE
                                    nowPlayingTitle?.text = trackInfo.title ?: dls
                                    nowPlayingTitle?.visibility = View.VISIBLE
                                    carouselNowPlayingArtist?.text = trackInfo.artist ?: ""
                                    carouselNowPlayingArtist?.visibility = if (!trackInfo.artist.isNullOrBlank()) View.VISIBLE else View.GONE
                                    carouselNowPlayingTitle?.text = trackInfo.title ?: dls
                                    carouselNowPlayingTitle?.visibility = View.VISIBLE

                                    // Cover aus Deezer laden (überschreibt Slideshow wenn vorhanden)
                                    val localCover = deezerCache?.getLocalCoverPath(trackInfo.trackId)
                                        ?: trackInfo.coverUrl?.takeIf { it.startsWith("/") }
                                    val coverUrl = trackInfo.coverUrlMedium ?: trackInfo.coverUrl

                                    val placeholderDrawable = R.drawable.ic_cover_placeholder
                                    if (!localCover.isNullOrBlank()) {
                                        nowPlayingCover?.load(java.io.File(localCover)) {
                                            crossfade(true)
                                            placeholder(placeholderDrawable)
                                        }
                                        carouselNowPlayingCover?.load(java.io.File(localCover)) {
                                            crossfade(true)
                                            placeholder(placeholderDrawable)
                                        }
                                    } else if (!coverUrl.isNullOrBlank()) {
                                        nowPlayingCover?.load(coverUrl) {
                                            crossfade(true)
                                            placeholder(placeholderDrawable)
                                        }
                                        carouselNowPlayingCover?.load(coverUrl) {
                                            crossfade(true)
                                            placeholder(placeholderDrawable)
                                        }
                                    }
                                    // Deezer Debug Info aktualisieren
                                    updateDeezerDebugInfo("Found", dls, dls, "${trackInfo.artist} - ${trackInfo.title}", trackInfo)
                                }

                                // MediaSession für DAB aktualisieren (mit Deezer Cover wenn vorhanden)
                                val radioLogoPath = radioLogoRepository.getLogoForStation(currentDabServiceLabel, null, 0f)
                                FytFMMediaService.instance?.updateDabMetadata(
                                    serviceLabel = currentDabServiceLabel,
                                    ensembleLabel = currentDabEnsembleLabel,
                                    dls = dls,
                                    slideshowBitmap = currentDabSlideshow,
                                    radioLogoPath = radioLogoPath
                                )
                            }
                        }
                    } else {
                        // Kein Deezer - nur MediaSession aktualisieren
                        val radioLogoPath = radioLogoRepository.getLogoForStation(currentDabServiceLabel, null, 0f)
                        FytFMMediaService.instance?.updateDabMetadata(
                            serviceLabel = currentDabServiceLabel,
                            ensembleLabel = currentDabEnsembleLabel,
                            dls = dls,
                            slideshowBitmap = currentDabSlideshow,
                            radioLogoPath = radioLogoPath
                        )
                    }
                }

                // DL+ (Dynamic Label Plus) für Artist/Title Tags
                dabTunerManager.onDlPlus = { artist, title ->
                    android.util.Log.d("fytFM", "DL+ received: artist=$artist, title=$title")
                    // Artist/Title in Now Playing Bar anzeigen (wie bei Deezer/Spotify)
                    if (artist != null || title != null) {
                        nowPlayingArtist?.text = artist ?: ""
                        nowPlayingArtist?.visibility = if (artist != null) View.VISIBLE else View.GONE
                        nowPlayingTitle?.text = title ?: ""
                        nowPlayingTitle?.visibility = if (title != null) View.VISIBLE else View.GONE
                        carouselNowPlayingArtist?.text = artist ?: ""
                        carouselNowPlayingArtist?.visibility = if (artist != null) View.VISIBLE else View.GONE
                        carouselNowPlayingTitle?.text = title ?: ""
                        carouselNowPlayingTitle?.visibility = if (title != null) View.VISIBLE else View.GONE
                    }
                }

                // MOT Slideshow - Bilder vom DAB-Sender
                dabTunerManager.onSlideshow = { bitmap ->
                    android.util.Log.d("fytFM", "DAB Slideshow received: ${bitmap.width}x${bitmap.height}")
                    currentDabSlideshow = bitmap
                    // Bild in Now Playing Bar Cover anzeigen
                    nowPlayingCover?.setImageBitmap(bitmap)
                    carouselNowPlayingCover?.setImageBitmap(bitmap)
                    // MediaSession für DAB mit Slideshow aktualisieren
                    val radioLogoPath = radioLogoRepository.getLogoForStation(currentDabServiceLabel, null, 0f)
                    FytFMMediaService.instance?.updateDabMetadata(
                        serviceLabel = currentDabServiceLabel,
                        ensembleLabel = currentDabEnsembleLabel,
                        dls = currentDabDls,
                        slideshowBitmap = bitmap,
                        radioLogoPath = radioLogoPath
                    )
                }

                // Empfangsqualität (SNR, Sync, Quality)
                dabTunerManager.onReceptionStats = { sync, quality, snr ->
                    updateDabReceptionStats(sync, quality, snr)
                }

                val success = dabTunerManager.initialize(this)
                isDabOn = success  // Button aktivieren, Tuner-Check passiert bei Sendersuche

                if (!success) {
                    android.widget.Toast.makeText(this, "DAB+ Initialisierung fehlgeschlagen", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("fytFM", "DAB power toggle failed: ${e.message}", e)
            isDabOn = false
            android.widget.Toast.makeText(this, "Tuner Error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }

        android.util.Log.i("fytFM", "======= toggleDabPower() done, isDabOn=$isDabOn =======")
        updatePowerButton()
    }

    /**
     * Lädt den Favoriten-Filter-Status für den aktuellen Modus (FM/AM)
     */
    private fun loadFavoritesFilterState() {
        showFavoritesOnly = when (frequencyScale.getMode()) {
            FrequencyScaleView.RadioMode.FM -> presetRepository.isShowFavoritesOnlyFm()
            FrequencyScaleView.RadioMode.AM -> presetRepository.isShowFavoritesOnlyAm()
            FrequencyScaleView.RadioMode.DAB -> presetRepository.isShowFavoritesOnlyDab()
        }
        updateFolderButton()
    }

    /**
     * Aktualisiert das Herz-Icon basierend auf dem aktuellen Sender
     */
    private fun updateFavoriteButton() {
        val mode = frequencyScale.getMode()
        val isFavorite = if (mode == FrequencyScaleView.RadioMode.DAB) {
            if (currentDabServiceId > 0) presetRepository.isDabFavorite(currentDabServiceId) else false
        } else {
            val frequency = frequencyScale.getFrequency()
            val isAM = mode == FrequencyScaleView.RadioMode.AM
            presetRepository.isFavorite(frequency, isAM)
        }

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
        val mode = frequencyScale.getMode()
        val isFavoriteNow = if (mode == FrequencyScaleView.RadioMode.DAB) {
            if (currentDabServiceId > 0) presetRepository.toggleDabFavorite(currentDabServiceId) else return
        } else {
            val frequency = frequencyScale.getFrequency()
            val isAM = mode == FrequencyScaleView.RadioMode.AM
            presetRepository.toggleFavorite(frequency, isAM)
        }

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

        // Filter-Status speichern (FM, AM und DAB getrennt)
        when (frequencyScale.getMode()) {
            FrequencyScaleView.RadioMode.FM -> presetRepository.setShowFavoritesOnlyFm(showFavoritesOnly)
            FrequencyScaleView.RadioMode.AM -> presetRepository.setShowFavoritesOnlyAm(showFavoritesOnly)
            FrequencyScaleView.RadioMode.DAB -> presetRepository.setShowFavoritesOnlyDab(showFavoritesOnly)
        }

        // UI aktualisieren
        updateFolderButton()
        loadStationsForCurrentMode()

        // Toast anzeigen
        val message = if (showFavoritesOnly) "Nur Favoriten" else "Alle Sender"
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    // === Key Event Handler für Lenkradtasten ===

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        android.util.Log.i("MainActivity", "=== dispatchKeyEvent: keyCode=${event.keyCode}, action=${event.action}, source=${event.source} ===")
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        android.util.Log.d("MainActivity", "onKeyDown: keyCode=$keyCode")

        return when (keyCode) {
            android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> {
                android.util.Log.i("MainActivity", "MEDIA_NEXT pressed")
                android.widget.Toast.makeText(this, "NEXT", android.widget.Toast.LENGTH_SHORT).show()
                seekToStation(seekUp = true)
                true
            }
            android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                android.util.Log.i("MainActivity", "MEDIA_PREVIOUS pressed")
                android.widget.Toast.makeText(this, "PREV", android.widget.Toast.LENGTH_SHORT).show()
                seekToStation(seekUp = false)
                true
            }
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY,
            android.view.KeyEvent.KEYCODE_MEDIA_PAUSE,
            android.view.KeyEvent.KEYCODE_HEADSETHOOK -> {
                android.util.Log.i("MainActivity", "PLAY_PAUSE pressed")
                toggleRadioPower()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRdsPolling()
        stopTunerInfoUpdates()
        twUtil?.close()
        updateRepository.destroy()
        rdsLogRepository.destroy()
        // steeringWheelKeyManager?.unregister()  // Fallback disabled
        syuToolkitManager?.disconnect()
        // Radio NICHT ausschalten - läuft im MediaService weiter (auch im Sleep)
        // fmNative.powerOff() wird nur vom User manuell ausgelöst
    }

    private fun saveLastFrequency(frequency: Float) {
        val prefs = getSharedPreferences("fytfm_prefs", Context.MODE_PRIVATE)
        prefs.edit().putFloat("last_frequency", frequency).apply()
    }

    private fun loadLastFrequency(): Float {
        val prefs = getSharedPreferences("fytfm_prefs", Context.MODE_PRIVATE)
        return prefs.getFloat("last_frequency", 90.4f) // Default: 90.4 MHz
    }

    private fun saveLastRadioMode(mode: FrequencyScaleView.RadioMode) {
        val prefs = getSharedPreferences("fytfm_prefs", Context.MODE_PRIVATE)
        val modeString = when (mode) {
            FrequencyScaleView.RadioMode.FM -> "FM"
            FrequencyScaleView.RadioMode.AM -> "AM"
            FrequencyScaleView.RadioMode.DAB -> "DAB"
        }
        android.util.Log.i("fytFM", "=== SAVING MODE: $modeString ===")
        prefs.edit().putString("last_radio_mode", modeString).apply()
    }

    private fun loadLastRadioMode(): FrequencyScaleView.RadioMode {
        val prefs = getSharedPreferences("fytfm_prefs", Context.MODE_PRIVATE)
        return when (prefs.getString("last_radio_mode", "FM")) {
            "AM" -> FrequencyScaleView.RadioMode.AM
            "DAB" -> FrequencyScaleView.RadioMode.DAB
            else -> FrequencyScaleView.RadioMode.FM
        }
    }

    private fun saveLastDabService(serviceId: Int, ensembleId: Int) {
        val prefs = getSharedPreferences("fytfm_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putInt("last_dab_service_id", serviceId)
            .putInt("last_dab_ensemble_id", ensembleId)
            .apply()
        android.util.Log.i("fytFM", "=== SAVING DAB SERVICE: serviceId=$serviceId, ensembleId=$ensembleId ===")
    }

    private fun loadLastDabService(): Pair<Int, Int> {
        val prefs = getSharedPreferences("fytfm_prefs", Context.MODE_PRIVATE)
        val serviceId = prefs.getInt("last_dab_service_id", 0)
        val ensembleId = prefs.getInt("last_dab_ensemble_id", 0)
        android.util.Log.i("fytFM", "=== LOADING DAB SERVICE: serviceId=$serviceId, ensembleId=$ensembleId ===")
        return Pair(serviceId, ensembleId)
    }

    // === Station Import from Original Radio App ===

    /**
     * Checks if station list is empty and if we haven't asked yet.
     * Shows dialog to offer importing from original radio app.
     */
    private fun checkAndOfferStationImport() {
        // Only check FM stations for now
        val fmStations = presetRepository.loadFmStations()
        val hasAsked = presetRepository.hasAskedAboutImport()

        android.util.Log.i("fytFM", "checkAndOfferStationImport: fmStations.size=${fmStations.size}, hasAsked=$hasAsked")

        if (fmStations.isEmpty() && !hasAsked) {
            android.util.Log.i("fytFM", "checkAndOfferStationImport: Zeige Import-Dialog")
            showImportStationsDialog()
        } else {
            android.util.Log.d("fytFM", "checkAndOfferStationImport: Dialog nicht nötig")
        }
    }

    private fun showImportStationsDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Sender importieren?")
            .setMessage("Die Senderliste ist leer.\n\nMöchtest du die Sender von der Original-Radio-App importieren?")
            .setPositiveButton("Ja") { _, _ ->
                presetRepository.setAskedAboutImport(true)
                importStationsFromOriginalApp()
            }
            .setNegativeButton("Nein") { _, _ ->
                presetRepository.setAskedAboutImport(true)
                android.widget.Toast.makeText(this, "Du kannst später Sender scannen oder manuell hinzufügen", android.widget.Toast.LENGTH_LONG).show()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Import stations from the original FYT radio app via SYU Service Callbacks.
     * Uses Type 4 (preset frequencies) and Type 14 (preset names) callbacks.
     */
    private fun importStationsFromOriginalApp() {
        android.util.Log.i("fytFM", "Import: Starte Preset-Sammlung via SYU Callbacks...")
        android.widget.Toast.makeText(this, "Sammle Sender vom SYU Service...", android.widget.Toast.LENGTH_SHORT).show()

        // Clear previous data and start collecting
        collectedPresets.clear()
        isCollectingPresets = true

        // Cancel any existing timeout
        presetImportTimeoutRunnable?.let { presetImportHandler.removeCallbacks(it) }

        // Set timeout to finish collecting after 3 seconds
        presetImportTimeoutRunnable = Runnable {
            finishPresetCollection()
        }
        presetImportHandler.postDelayed(presetImportTimeoutRunnable!!, 3000)

        // Request presets from SYU service (may trigger callbacks)
        try {
            // Query each preset slot 0-11 for FM
            for (i in 0..11) {
                syuToolkitManager?.getModuleData(1, 4, intArrayOf(i), null, null)
                syuToolkitManager?.getModuleData(1, 14, intArrayOf(i), null, null)
            }
        } catch (e: Exception) {
            android.util.Log.e("fytFM", "Import: Error querying presets", e)
        }
    }

    /**
     * Called after timeout to finish collecting presets and save them.
     */
    private fun finishPresetCollection() {
        isCollectingPresets = false
        presetImportTimeoutRunnable = null

        android.util.Log.i("fytFM", "Import: Sammlung beendet, ${collectedPresets.size} Presets empfangen")

        if (collectedPresets.isEmpty()) {
            android.widget.Toast.makeText(this, "Keine Sender von der Original-App empfangen", android.widget.Toast.LENGTH_LONG).show()
            return
        }

        // Convert collected presets to RadioStation list
        val stations = collectedPresets.values
            .filter { (freq, _) -> freq in 87.5f..108.0f }
            .map { (freq, name) ->
                at.planqton.fytfm.data.RadioStation(
                    frequency = freq,
                    name = name,
                    rssi = 0,
                    isAM = false,
                    isFavorite = true  // Imported presets are favorites
                )
            }
            .sortedBy { it.frequency }
            .distinctBy { (it.frequency * 10).toInt() }

        if (stations.isEmpty()) {
            android.widget.Toast.makeText(this, "Keine gültigen Sender gefunden", android.widget.Toast.LENGTH_LONG).show()
            return
        }

        // Save and reload
        presetRepository.saveFmStations(stations)
        loadStationsForCurrentMode()

        android.widget.Toast.makeText(this, "${stations.size} Sender importiert!", android.widget.Toast.LENGTH_SHORT).show()
        android.util.Log.i("fytFM", "Import: ${stations.size} Sender gespeichert")

        // Log imported stations
        stations.forEach { station ->
            android.util.Log.d("fytFM", "Import: ${station.frequency} MHz - ${station.name ?: "(kein Name)"}")
        }
    }
}
