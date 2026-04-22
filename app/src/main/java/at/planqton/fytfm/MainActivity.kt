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
import at.planqton.fytfm.databinding.ActivityMainBinding
import androidx.appcompat.app.AppCompatDelegate
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.widget.ProgressBar
import android.widget.SeekBar
import at.planqton.fytfm.data.PresetRepository
import at.planqton.fytfm.data.UpdateRepository
import at.planqton.fytfm.data.UpdateState
import at.planqton.fytfm.data.rdslog.RdsLogRepository
import at.planqton.fytfm.data.rdslog.RdsDatabase
import at.planqton.fytfm.data.rdslog.RtCorrection
import at.planqton.fytfm.data.rdslog.RtCorrectionDao
import at.planqton.fytfm.media.FytFMMediaService
import at.planqton.fytfm.ui.CorrectionsAdapter
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
import at.planqton.fytfm.dab.MockDabTunerManager
import at.planqton.fytfm.dab.EpgData
import at.planqton.fytfm.ui.EpgDialog
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import coil.load

class MainActivity : AppCompatActivity(),
    at.planqton.fytfm.ui.dlslog.DlsLogDialogFragment.DlsLogCallback,
    at.planqton.fytfm.ui.settings.SettingsDialogFragment.SettingsCallback,
    at.planqton.fytfm.ui.corrections.CorrectionsViewerDialogFragment.CorrectionsCallback,
    at.planqton.fytfm.ui.cache.DeezerCacheDialogFragment.DeezerCacheCallback,
    at.planqton.fytfm.ui.bugreport.BugReportDialogFragment.BugReportCallback,
    at.planqton.fytfm.ui.editor.RadioEditorDialogFragment.RadioEditorCallback,
    at.planqton.fytfm.ui.logotemplate.LogoTemplateDialogFragment.LogoTemplateCallback {

    companion object {
        private const val TAG = "fytFM"
    }

    // Helper für kürzere Toast-Aufrufe
    private fun toast(message: String, long: Boolean = false) {
        android.widget.Toast.makeText(
            this, message,
            if (long) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    private fun toast(@androidx.annotation.StringRes resId: Int, long: Boolean = false) {
        toast(getString(resId), long)
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase))
    }

    // ViewBinding für Haupt-UI (schrittweise Migration)
    private lateinit var binding: ActivityMainBinding

    // Helper-Properties für Mode-Checks (reduziert Boilerplate)
    private val currentMode: FrequencyScaleView.RadioMode
        get() = binding.frequencyScale.getMode()
    private val isAmMode: Boolean
        get() = currentMode == FrequencyScaleView.RadioMode.AM
    private val isFmMode: Boolean
        get() = currentMode == FrequencyScaleView.RadioMode.FM
    private val isDabMode: Boolean
        get() = currentMode == FrequencyScaleView.RadioMode.DAB
    private val isDabDevMode: Boolean
        get() = currentMode == FrequencyScaleView.RadioMode.DAB_DEV
    private val isAnyDabMode: Boolean
        get() = isDabMode || isDabDevMode

    // Helper zum Laden der DAB-Stationen je nach Modus
    private val dabStationsForCurrentMode: List<at.planqton.fytfm.data.RadioStation>
        get() = if (isDabDevMode) presetRepository.loadDabDevStations() else presetRepository.loadDabStations()

    // frequencyScale, btnPrevStation, btnNextStation, btnFavorite, btnPlayPause, btnPower, spinnerRadioMode now via binding
    private val dabTunerManager = DabTunerManager()
    private val mockDabTunerManager = at.planqton.fytfm.dab.MockDabTunerManager()
    private var isDabOn = false
    private var currentDabServiceId = 0
    private var currentDabEnsembleId = 0
    private var currentDabServiceLabel: String? = null
    private var currentDabEnsembleLabel: String? = null
    private var currentDabDls: String? = null
    private var lastDlsTimestamp: Long = 0L  // Timestamp when last DLS was received
    private var lastDeezerSearchedDls: String? = null  // Cache to avoid duplicate Deezer searches
    private var lastParsedDls: String? = null  // Cache for RT-DLS Parser log (DAB+)
    private var lastParsedFmRt: String? = null  // Cache for RT-DLS Parser log (FM)
    private var currentDabDeezerCoverPath: String? = null  // Current Deezer cover for DAB MediaSession
    private var currentDabDeezerCoverDls: String? = null  // DLS for which the cover was found
    private var lastLoggedDls: String? = null  // Cache to avoid duplicate DLS log entries
    private val dlsLogEntries = mutableListOf<String>()  // DLS Log entries
    private var currentDabSlideshow: android.graphics.Bitmap? = null

    // Cover source cycling (tap to switch between available sources)
    private enum class DabCoverSource { DAB_LOGO, STATION_LOGO, SLIDESHOW, DEEZER }
    private var selectedCoverSourceIndex: Int = -1  // -1 = auto (best available), 0+ = specific source index
    private var availableCoverSources: List<DabCoverSource> = emptyList()
    private var coverSourceLocked: Boolean = false  // Lock current selection as highest priority
    private var lockedCoverSource: DabCoverSource? = null  // The locked source
    private var suppressSpinnerCallback = false
    // All debug views now via binding (CheckBoxes, Overlays, TextViews)
    private var currentUiCoverSource: String = "(none)"
    private val swcLogEntries = mutableListOf<String>()
    private var tunerInfoUpdateHandler: android.os.Handler? = null
    private var tunerInfoUpdateRunnable: Runnable? = null
    private var uiInfoUpdateHandler: android.os.Handler? = null
    private var uiInfoUpdateRunnable: Runnable? = null
    private var debugParserOverlay: View? = null
    private var parserLogText: TextView? = null
    private var parserLogScrollView: android.widget.ScrollView? = null
    private var parserLogListener: ((at.planqton.fytfm.deezer.ParserLogger.ParserLogEntry) -> Unit)? = null
    private var currentParserTab = at.planqton.fytfm.deezer.ParserLogger.Source.FM  // FM or DAB

    // DLS Timestamp Update Timer
    private var dlsTimestampUpdateHandler: android.os.Handler? = null
    private var dlsTimestampUpdateRunnable: Runnable? = null

    // Auto-Background Timer
    private var autoBackgroundHandler: android.os.Handler? = null
    private var autoBackgroundRunnable: Runnable? = null
    private var wasStartedFromBoot = false
    private var autoBackgroundSecondsRemaining = 0
    private var autoBackgroundTimerStartTime = 0L
    private var autoBackgroundToast: android.widget.Toast? = null

    // Steering Wheel Key Handler
    private var steeringWheelKeyManager: SteeringWheelKeyManager? = null
    private var syuToolkitManager: SyuToolkitManager? = null

    // Preset Import via SYU Service Callbacks
    private var isCollectingPresets = false
    private val collectedPresets = mutableMapOf<Int, Pair<Float, String?>>()  // index -> (freq, name)
    private val presetImportHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var presetImportTimeoutRunnable: Runnable? = null

    // Now Playing, Carousel views now via binding (except CoverDots which are separate)
    private var nowPlayingCoverDots: LinearLayout? = null
    private var carouselCoverDots: LinearLayout? = null
    private var dabListCoverDots: LinearLayout? = null
    private var dabListDeezerWatermark: TextView? = null

    private var lastDisplayedTrackId: String? = null
    private var psTapCount = 0
    private var lastPsTapTime = 0L
    private var lastDebugTrackId: String? = null
    private var rtCorrectionDao: RtCorrectionDao? = null

    // View Mode Toggle (Equalizer vs Image/Carousel)
    private var mainContentArea: View? = null
    private var carouselContentArea: View? = null
    private var dabListContentArea: View? = null
    private var btnViewModeEqualizer: View? = null
    private var btnViewModeImage: View? = null

    // DAB List Mode Views
    private var dabListCover: ImageView? = null
    private var dabListStationName: TextView? = null
    private var dabListEnsemble: TextView? = null
    private var dabListRadiotext: TextView? = null
    private var dabListFavoriteBtn: ImageButton? = null
    private var dabListFilterBtn: ImageButton? = null
    private var dabListSearchBtn: ImageButton? = null
    private var dabListSettingsBtn: ImageButton? = null
    private var dabListRecordBtn: ImageButton? = null
    private var dabListEpgBtn: ImageButton? = null
    private var recordingBlinkHandler: android.os.Handler? = null
    private var recordingBlinkRunnable: Runnable? = null
    private var dabListStationStrip: RecyclerView? = null
    private var dabListMainArea: View? = null
    private var dabStripAdapter: at.planqton.fytfm.ui.DabStripAdapter? = null
    private var dabVisualizerView: at.planqton.fytfm.ui.AudioVisualizerView? = null
    private var pendingVisualizerSessionId: Int = 0
    private val REQUEST_RECORD_AUDIO_PERMISSION = 1001
    private var stationCarousel: RecyclerView? = null
    private var carouselFrequencyLabel: TextView? = null
    private var btnCarouselFavorite: ImageButton? = null
    private var btnCarouselRecord: ImageButton? = null
    private var btnCarouselEpg: ImageButton? = null
    private var epgDialog: EpgDialog? = null
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
    private lateinit var radioController: at.planqton.fytfm.controller.RadioController
    private lateinit var stationAdapter: StationAdapter
    private lateinit var fmNative: FmNative
    private lateinit var rdsManager: RdsManager
    private val lastSyncedPs = mutableMapOf<Int, String>()  // FreqKey -> letzter gesyncter PS
    private lateinit var radioScanner: at.planqton.fytfm.scanner.RadioScanner
    private lateinit var updateRepository: UpdateRepository
    private lateinit var rdsLogRepository: RdsLogRepository
    private lateinit var radioLogoRepository: at.planqton.fytfm.data.logo.RadioLogoRepository
    private lateinit var debugManager: at.planqton.fytfm.debug.DebugManager
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

    // Bug Report Export (for both crash reports and manual bug reports)
    private var pendingBugReportContent: String? = null
    private var pendingBugReportIsCrash: Boolean = false
    private val bugReportExportLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null && pendingBugReportContent != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(pendingBugReportContent!!.toByteArray())
                }
                toast(R.string.report_saved)
                if (pendingBugReportIsCrash) {
                    CrashHandler.clearCrashLog(this)
                }
            } catch (e: Exception) {
                toast(getString(R.string.error_prefix, e.message), long = true)
            }
        }
        pendingBugReportContent = null
        pendingBugReportIsCrash = false
    }

    // DAB Recording Folder Picker
    private var recordingPathCallback: ((String?) -> Unit)? = null
    private val recordingFolderPickerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            // Take persistable permission
            contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            val path = uri.toString()
            presetRepository.setDabRecordingPath(path)
            recordingPathCallback?.invoke(path)
            updateRecordButtonVisibility()
        } else {
            recordingPathCallback?.invoke(null)
        }
        recordingPathCallback = null
    }

    private var isPlaying = true
    private var isRadioOn = false
    private var showFavoritesOnly = false

    // Archive UI
    private lateinit var archiveAdapter: RdsLogAdapter
    private var archiveJob: Job? = null
    private var archiveSearchQuery: String = ""

    // Deezer search jobs (to cancel on station change)
    private var dabDeezerSearchJob: Job? = null
    private var fmDeezerSearchJob: Job? = null
    private var archiveFilterFrequency: Float? = null  // null = all

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Silently ignore com.syu.radio intents - we're already the active radio app
        if (intent?.action == "com.syu.radio") {
            android.util.Log.d(TAG, "Received com.syu.radio intent - ignoring (already active)")
        }
        // Debug commands via: adb shell am start -n at.planqton.fytfm/.MainActivity --es debug "settings"
        intent?.getStringExtra("debug")?.let { cmd ->
            android.util.Log.d(TAG, "Debug command: $cmd")
            when (cmd) {
                "settings" -> showSettingsDialogFragment()
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
                android.util.Log.d(TAG, "Screenshot saved: ${file.absolutePath}")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Screenshot failed: ${e.message}")
            }
        }
    }

    /**
     * Apply dark mode from saved preference before Activity is created.
     * Called early in onCreate before setContentView.
     */
    private fun applyDarkModeFromPreference() {
        // Must use same prefs name as PresetRepository ("settings")
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val mode = prefs.getInt("dark_mode_preference", 0)
        applyDarkMode(mode)
    }

    /**
     * Apply dark mode based on selection.
     * 0 = System, 1 = Light, 2 = Dark
     */
    private fun applyDarkMode(mode: Int) {
        val nightMode = when (mode) {
            1 -> AppCompatDelegate.MODE_NIGHT_NO      // Light
            2 -> AppCompatDelegate.MODE_NIGHT_YES    // Dark
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM  // System default
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
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

            android.util.Log.i(TAG, "Set as preferred activity for com.syu.radio")
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Could not set preferred activity: ${e.message}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply dark mode preference before setting content
        applyDarkModeFromPreference()
        super.onCreate(savedInstanceState)

        // ViewBinding initialisieren
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // DebugManager initialisieren
        debugManager = at.planqton.fytfm.debug.DebugManager(this, binding)

        // Initialize ParserLogger for persistent logs
        at.planqton.fytfm.deezer.ParserLogger.init(this)

        // Check if app was started from boot
        wasStartedFromBoot = intent?.getBooleanExtra(BootReceiver.EXTRA_FROM_BOOT, false) ?: false
        if (wasStartedFromBoot) {
            android.util.Log.i(TAG, "App started from boot")
        }

        // DEBUG: Test crash via ADB:
        // Verzögert: adb shell am start -n at.planqton.fytfm/.MainActivity --ez test_crash true
        // Sofort:    adb shell am start -n at.planqton.fytfm/.MainActivity --ez test_crash_now true
        if (intent?.getBooleanExtra("test_crash", false) == true) {
            android.os.Handler(mainLooper).postDelayed({
                throw RuntimeException("TEST CRASH - Dies ist ein Test des Crash Handlers!")
            }, 1000)
        }
        if (intent?.getBooleanExtra("test_crash_now", false) == true) {
            throw RuntimeException("TEST CRASH BEIM START - Sofortiger Crash in onCreate!")
        }

        // 1. Repositories und Controller initialisieren
        initRepositories()
        initRadioController()
        initExternalServices()

        // 2. Views initialisieren
        initViews()
        setupStationList()
        setupListeners()
        setupDialogListeners()

        // Check for previous crash
        checkForCrashReport()

        // Load last radio mode BEFORE setupViewModeToggle populates carousel
        val lastMode = loadLastRadioMode()
        android.util.Log.i(TAG, "=== APP START: loadLastRadioMode() returned $lastMode ===")

        // Nur Info-Toast wenn Tuner nicht verfügbar (blockiert nicht)
        if (!isTunerAvailable(lastMode)) {
            toast(getString(R.string.tuner_not_available, lastMode))
        }

        binding.frequencyScale.setMode(lastMode)
        android.util.Log.i(TAG, "=== APP START: binding.frequencyScale.getMode() is now ${binding.frequencyScale.getMode()} ===")

        // Start MediaService for Car Launcher integration
        startMediaService()

        // Initialize Steering Wheel Key Handler for FYT devices
        initSteeringWheelKeys()

        // Load last frequency from SharedPreferences (nur für FM/AM relevant)
        val lastFreq = loadLastFrequency()
        if (lastMode != FrequencyScaleView.RadioMode.DAB) {
            binding.frequencyScale.setFrequency(lastFreq)
            rdsManager.setUiFrequency(lastFreq)  // Für AF-Vergleich
            rdsLogRepository.setInitialFrequency(lastFreq, lastMode == FrequencyScaleView.RadioMode.AM)
            updateFrequencyDisplay(lastFreq)
        }
        updateModeSpinner()
        loadFavoritesFilterState()
        loadStationsForCurrentMode()

        // 4. Mode-spezifische Initialisierung
        if (lastMode == FrequencyScaleView.RadioMode.DAB) {
            setupDabModeOnStartup()
        } else if (isCarouselMode) {
            populateCarousel()
            updateCarouselSelection()
        }

        // 5. Finale UI-Updates
        checkAndOfferStationImport()
        updatePowerButton()
        updateFavoriteButton()
        updateInitialMediaSession(lastFreq)

        // 6. Autoplay für FM/AM
        if (!isRadioOn && lastMode != FrequencyScaleView.RadioMode.DAB && presetRepository.isAutoplayAtStartup()) {
            toggleRadioPower()
        }
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
                    android.util.Log.i(TAG, "SYU onNextPressed called! isRadioOn=$isRadioOn")
                    runOnUiThread {
                        // Always allow station changes via steering wheel
                        skipToNextStation()
                    }
                }

                override fun onPrevPressed() {
                    android.util.Log.i(TAG, "SYU onPrevPressed called! isRadioOn=$isRadioOn")
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
                    android.util.Log.d(TAG, "SYU: Raw key event: keyCode=$keyCode, intData=${intData?.toList()}")
                    val keyName = getKeyName(keyCode)
                    val display = if (keyName != null) "SYU: $keyCode ($keyName)" else "SYU: $keyCode"
                    runOnUiThread { logSwcEvent(display) }
                }

                override fun onFrequencyUpdate(frequencyKhz: Int) {
                    // Ignoriert - fytFM nutzt eigene Presets statt com.syu.music Frequenz-Sync
                    val frequencyMhz = frequencyKhz / 100.0f
                    android.util.Log.d(TAG, "SYU: Frequency update ignoriert: $frequencyMhz MHz")
                }

                override fun onRawCallback(type: Int, intData: IntArray?, floatData: FloatArray?, stringData: Array<String>?) {
                    // Debug: Log all raw callbacks to discover event types
                    android.util.Log.d(TAG, "SYU RAW: type=$type (0x${type.toString(16)}), intData=${intData?.toList()}, floatData=${floatData?.toList()}, stringData=${stringData?.toList()}")
                    runOnUiThread {
                        logSwcEvent("SYU RAW type=$type int=${intData?.firstOrNull()}")
                    }
                }

                override fun onPresetReceived(index: Int, frequencyMhz: Float, isAM: Boolean) {
                    if (!isAM && isCollectingPresets && frequencyMhz in 87.5f..108.0f) {
                        android.util.Log.i(TAG, "Import: Preset empfangen - index=$index, freq=$frequencyMhz MHz")
                        runOnUiThread {
                            val existing = collectedPresets[index]
                            collectedPresets[index] = Pair(frequencyMhz, existing?.second)
                        }
                    }
                }

                override fun onPresetNameReceived(index: Int, name: String, isAM: Boolean) {
                    if (!isAM && isCollectingPresets) {
                        android.util.Log.i(TAG, "Import: Preset-Name empfangen - index=$index, name='$name'")
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
        android.util.Log.i(TAG, "SYU Toolkit Manager connecting...")

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
            android.util.Log.i(TAG, "Steering wheel broadcast receiver registered (via $method)")
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
                android.util.Log.w(TAG, "Overlay permission not granted")
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
            android.util.Log.i(TAG, "StationChangeOverlayService started")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to start overlay service: ${e.message}")
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

        // Deezer doesn't need credentials - just create the client
        deezerClient = DeezerClient()
        rtCombiner = RtCombiner(
            deezerClient = deezerClient,
            deezerCache = deezerCache,
            isCacheEnabled = { presetRepository.isDeezerCacheEnabled() },
            isNetworkAvailable = { isNetworkAvailable() },
            correctionDao = rtCorrectionDao,
            onDebugUpdate = { status, originalRt, strippedRt, query, trackInfo ->
                runOnUiThread {
                    // Store for bug reports
                    currentDeezerStatus = status
                    currentDeezerOriginalRt = originalRt
                    currentDeezerStrippedRt = strippedRt
                    currentDeezerQuery = query
                    currentDeezerTrackInfo = trackInfo
                    updateDeezerDebugInfo(status, originalRt, strippedRt, query, trackInfo)

                    // Bei "Not found" oder "Waiting...": Cover auf Fallback zurücksetzen
                    if (status == "Not found" || status == "Waiting...") {
                        resetCoverToFallback()
                    }

                    // Bei keinem Match: Raw RT parsen und anzeigen
                    val displayInfo = trackInfo ?: strippedRt?.let { parseRawRtToTrackInfo(it) }
                    updateNowPlaying(displayInfo)
                    binding.nowPlayingRawRt.text = strippedRt ?: ""
                    binding.carouselNowPlayingRawRt.text = strippedRt ?: ""
                    updateIgnoredIndicator(strippedRt)
                    updatePipDisplay()
                }
            },
            onCoverDownloaded = { trackInfo ->
                // Cover wurde im Hintergrund heruntergeladen - UI und MediaSession aktualisieren
                runOnUiThread {
                    val localCover = trackInfo.coverUrl?.takeIf { it.startsWith("/") }
                    if (localCover != null) {
                        android.util.Log.d(TAG, "Cover downloaded: ${trackInfo.artist} - ${trackInfo.title} -> $localCover")

                        if (isDabMode) {
                            // DAB Modus - Neues Deezer Cover gefunden
                            currentDabDeezerCoverPath = localCover
                            currentDabDeezerCoverDls = currentDabDls

                            // Auto-Modus aktivieren -> höchste Priorität (Deezer) wird gewählt
                            selectedCoverSourceIndex = -1
                            updateDabCoverDisplay()

                            // Debug-Fenster Cover
                            loadCoverImage(localCover)

                            // MediaSession für DAB aktualisieren - mit "Artist - Title"
                            val radioLogoPath = getLogoForDabStation(currentDabServiceLabel, currentDabServiceId)
                            val displayDls = "${trackInfo.artist} - ${trackInfo.title}"
                            FytFMMediaService.instance?.updateDabMetadata(
                                serviceLabel = currentDabServiceLabel,
                                ensembleLabel = currentDabEnsembleLabel,
                                dls = displayDls,  // Formatiertes "Artist - Title"
                                slideshowBitmap = null,  // Deezer hat Vorrang vor Slideshow
                                radioLogoPath = radioLogoPath,
                                deezerCoverPath = localCover
                            )
                        } else {
                            // FM/AM Modus - updateMetadata verwenden
                            currentUiCoverSource = localCover

                            // UI Cover aktualisieren
                            val placeholderDrawable = R.drawable.ic_cover_placeholder
                            binding.nowPlayingCover.load(java.io.File(localCover)) {
                                crossfade(true)
                                placeholder(placeholderDrawable)
                            }
                            binding.carouselNowPlayingCover.load(java.io.File(localCover)) {
                                crossfade(true)
                                placeholder(placeholderDrawable)
                            }

                            // Debug-Fenster Cover
                            loadCoverImage(localCover)

                            // MediaSession aktualisieren
                            val isAM = isAmMode
                            val currentFreq = binding.frequencyScale.getFrequency()
                            val ps = rdsManager.ps
                            val radioLogoPath = radioLogoRepository.getLogoForStation(ps, rdsManager.pi, currentFreq)
                            FytFMMediaService.instance?.updateMetadata(
                                frequency = currentFreq,
                                ps = ps,
                                rt = "${trackInfo.artist} - ${trackInfo.title}",
                                isAM = isAM,
                                coverUrl = null,
                                localCoverPath = localCover,
                                radioLogoPath = radioLogoPath
                            )
                        }
                    }
                }
            }
        )
        android.util.Log.i(TAG, "Deezer integration initialized")
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
                // Log RDS data
                rdsLogRepository.onRdsUpdate(ps, rt, pi, pty, tp, ta, rssi, afList)

                // RT-DLS Parser Log
                if (!rt.isNullOrBlank()) {
                    handleRdsParserLog(rt, ps)
                }

                // Deezer Integration oder Raw-RT Update
                val deezerEnabled = presetRepository.isDeezerEnabledFm()
                if (rtCombiner != null && !rt.isNullOrBlank() && !debugDeezerBlocked && deezerEnabled) {
                    handleRdsDeezerSearch(rt, ps, pi)
                } else if (!rt.isNullOrBlank()) {
                    handleRdsRawRtUpdate(rt, ps, pi)
                }

                runOnUiThread {
                    // Auto-Sync PS zu Preset
                    if (!ps.isNullOrBlank()) {
                        handleRdsPsSync(ps)
                    }
                    // Debug-UI aktualisieren
                    handleRdsDebugUpdate(ps, rt, rssi, pi, pty, tp, ta, afList)
                }
            }
        })
    }

    private fun stopRdsPolling() {
        rdsManager.stopPolling()
    }

    // ========== RDS Handler Methods ==========

    /**
     * Loggt RT durch den DLS-Parser (für FM wie bei DAB+).
     */
    private fun handleRdsParserLog(rt: String, ps: String?) {
        if (rt.isBlank() || rt == lastParsedFmRt) return
        lastParsedFmRt = rt
        val station = ps ?: "FM"
        CoroutineScope(Dispatchers.IO).launch {
            val parseResult = at.planqton.fytfm.deezer.DlsParser.parse(rt, station)
            at.planqton.fytfm.deezer.ParserLogger.logFm(station, rt, parseResult.artist, parseResult.title)
        }
    }

    /**
     * Verarbeitet RT durch Deezer-Integration (Cover-Art, kombinierter RT).
     */
    private fun handleRdsDeezerSearch(rt: String, ps: String?, pi: Int) {
        val combiner = rtCombiner ?: return
        val currentFrequency = binding.frequencyScale.getFrequency()

        fmDeezerSearchJob?.cancel()
        fmDeezerSearchJob = CoroutineScope(Dispatchers.IO).launch {
            // Parse RT first (like DAB+ DLS)
            val parseResult = at.planqton.fytfm.deezer.DlsParser.parse(rt, ps)
            val searchText = parseResult.toSearchString() ?: rt

            val combinedRt = combiner.processRt(pi, searchText, currentFrequency, rawOriginal = rt)
            val finalRt = combinedRt ?: rt
            val trackInfo = if (combinedRt != null) combiner.getLastTrackInfo(pi) else null

            withContext(Dispatchers.Main) {
                // Skip UI update if nothing has changed
                val previousRt = lastDisplayedRt[pi]
                val currentTrackId = trackInfo?.trackId
                if (finalRt == previousRt && currentTrackId == lastDisplayedTrackId) return@withContext
                lastDisplayedRt[pi] = finalRt
                lastDisplayedTrackId = currentTrackId

                val isAM = isAmMode
                val currentFreq = binding.frequencyScale.getFrequency()
                val radioLogoPath = radioLogoRepository.getLogoForStation(ps, pi, currentFreq)
                val localCover = deezerCache?.getLocalCoverPath(trackInfo?.trackId)
                    ?: trackInfo?.coverUrl?.takeIf { it.startsWith("/") }

                FytFMMediaService.instance?.updateMetadata(
                    frequency = currentFreq,
                    ps = ps,
                    rt = finalRt,
                    isAM = isAM,
                    coverUrl = null,
                    localCoverPath = localCover,
                    radioLogoPath = radioLogoPath
                )
            }
        }
    }

    /**
     * Aktualisiert MediaService mit Raw-RT (ohne Deezer).
     */
    private fun handleRdsRawRtUpdate(rt: String, ps: String?, pi: Int) {
        val isAM = isAmMode
        val currentFreq = binding.frequencyScale.getFrequency()
        val radioLogoPath = radioLogoRepository.getLogoForStation(ps, pi, currentFreq)

        FytFMMediaService.instance?.updateMetadata(
            frequency = currentFreq,
            ps = ps,
            rt = rt,
            isAM = isAM,
            radioLogoPath = radioLogoPath
        )

        runOnUiThread {
            val displayInfo = parseRawRtToTrackInfo(rt)
            updateNowPlaying(displayInfo)
            binding.nowPlayingRawRt.text = rt
            binding.carouselNowPlayingRawRt.text = rt
            updateIgnoredIndicator(rt)
            updatePipDisplay()
        }
    }

    /**
     * Auto-Sync PS zu Preset wenn syncName aktiviert ist.
     */
    private fun handleRdsPsSync(ps: String) {
        val currentFreq = binding.frequencyScale.getFrequency()
        val freqKey = (currentFreq * 10).toInt()
        val lastPs = lastSyncedPs[freqKey]

        if (lastPs == ps) return  // Bereits gesynct

        val isAM = isAmMode
        val stations = if (isAM) presetRepository.loadAmStations() else presetRepository.loadFmStations()
        val currentStation = stations.find { Math.abs(it.frequency - currentFreq) < 0.05f }

        // Sync wenn: Station existiert UND (syncName aktiv ODER name leer)
        if (currentStation != null && (currentStation.syncName || currentStation.name.isNullOrBlank())) {
            if (currentStation.name != ps) {
                val updatedStations = stations.map {
                    if (Math.abs(it.frequency - currentFreq) < 0.05f) it.copy(name = ps) else it
                }
                if (isAM) presetRepository.saveAmStations(updatedStations)
                else presetRepository.saveFmStations(updatedStations)
                loadStationsForCurrentMode()
                android.util.Log.i(TAG, "Synced PS '$ps' to preset %.1f".format(currentFreq))
            }
        }
        lastSyncedPs[freqKey] = ps
    }

    /**
     * Aktualisiert die Debug-UI mit RDS-Daten.
     */
    private fun handleRdsDebugUpdate(ps: String?, rt: String?, rssi: Int, pi: Int, pty: Int, tp: Int, ta: Int, afList: ShortArray?) {
        // Alter für jeden Wert holen
        val psAge = rdsManager.psAgeMs
        val piAge = rdsManager.piAgeMs
        val ptyAge = rdsManager.ptyAgeMs
        val rtAge = rdsManager.rtAgeMs
        val rssiAge = rdsManager.rssiAgeMs
        val tpTaAge = rdsManager.tpTaAgeMs
        val afAge = rdsManager.afAgeMs

        // Labels mit Alter aktualisieren
        binding.labelPs.text = if (psAge >= 0) "(${psAge / 1000}s) PS:" else "PS:"
        binding.labelPi.text = if (piAge >= 0) "(${piAge / 1000}s) PI:" else "PI:"
        binding.labelPty.text = if (ptyAge >= 0) "(${ptyAge / 1000}s) PTY:" else "PTY:"
        binding.labelRt.text = if (rtAge >= 0) "(${rtAge / 1000}s) RT:" else "RT:"
        binding.labelRssi.text = if (rssiAge >= 0) "(${rssiAge / 1000}s) RSSI:" else "RSSI:"
        binding.labelAf.text = if (afAge >= 0) "(${afAge / 1000}s) AF:" else "AF:"
        binding.labelTpTa.text = if (tpTaAge >= 0) "(${tpTaAge / 1000}s) TP/TA:" else "TP/TA:"

        // Werte formatieren
        val psStr = ps ?: ""
        val piStr = if (pi != 0) String.format("0x%04X", pi and 0xFFFF) else ""
        val ptyStr = if (pty > 0) "$pty (${RdsManager.getPtyName(pty)})" else ""
        val rtStr = rt ?: ""
        val rssiStr = "$rssi"
        val tpTaStr = "TP=$tp TA=$ta"
        val afStr = if (afList != null && afList.isNotEmpty()) {
            afList.map { freq ->
                val freqMhz = if (freq > 875) freq / 10.0f else (87.5f + freq * 0.1f)
                String.format("%.1f", freqMhz)
            }.joinToString(", ")
        } else if (rdsManager.isAfEnabled) "enabled" else "disabled"

        // AF-Nutzung prüfen
        val hwFreq = rdsManager.hardwareFrequency
        val afUsingStr = if (hwFreq > 0) {
            if (rdsManager.isUsingAlternateFrequency) "Yes (${String.format("%.1f", hwFreq)} MHz)"
            else "No (${String.format("%.1f", hwFreq)} MHz)"
        } else "-- (not available)"

        binding.debugHeader.text = "RDS Debug"
        debugManager.updateDebugInfo(ps = psStr, rt = rtStr, rssiStr = rssiStr, pi = piStr, pty = ptyStr, tpTa = tpTaStr, af = afStr, afUsing = afUsingStr)
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
        // Start Auto-Background timer if enabled
        startAutoBackgroundTimerIfNeeded()
    }

    override fun onPause() {
        super.onPause()
        isAppInForeground = false
        // Cancel Auto-Background timer when app goes to background
        cancelAutoBackgroundTimer()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        // Cancel Auto-Background timer on user interaction (but only if it has been running for at least 1 second)
        if (autoBackgroundTimerStartTime > 0 && System.currentTimeMillis() - autoBackgroundTimerStartTime > 1000) {
            cancelAutoBackgroundTimer()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                // Permission granted, start visualizer with pending session ID
                if (pendingVisualizerSessionId > 0) {
                    dabVisualizerView?.visibility = View.VISIBLE
                    dabVisualizerView?.setAudioSessionId(pendingVisualizerSessionId)
                    pendingVisualizerSessionId = 0
                }
            } else {
                toast(R.string.audio_permission_visualizer)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Re-check when window focus changes (wichtig nach Sleep-Mode)
        if (hasFocus) {
            recheckPipMode("onWindowFocusChanged")
        }
    }

    private fun recheckPipMode(source: String) {
        val rootView = binding.rootLayout ?: return
        rootView.post {
            val width = rootView.width
            if (width > 0) {
                android.util.Log.d(TAG, "$source PiP check: width=$width, isPipMode=$isPipMode")
                checkForPipModeByViewSize(width)
            }
        }
    }

    private var isPipMode = false

    private fun setPipLayout(isPip: Boolean) {
        android.util.Log.i(TAG, "setPipLayout: isPip=$isPip (was: $isPipMode)")
        isPipMode = isPip
        if (isPip) {
            // Hide main UI elements
            binding.btnFavorite.visibility = View.GONE
            binding.btnPrevStation.visibility = View.GONE
            binding.btnNextStation.visibility = View.GONE
            binding.controlBar.visibility = View.GONE
            binding.stationBar.visibility = View.GONE
            binding.tvFrequency.visibility = View.GONE
            binding.frequencyScale.visibility = View.GONE

            // Hide all debug overlays
            binding.debugOverlay.visibility = View.GONE
            binding.debugChecklistOverlay.visibility = View.GONE
            binding.debugLayoutOverlay.visibility = View.GONE
            binding.debugBuildOverlay.visibility = View.GONE
            binding.debugDeezerOverlay.visibility = View.GONE
            binding.debugButtonsOverlay.visibility = View.GONE

            // Show PiP specific layout
            binding.pipLayout.root.visibility = View.VISIBLE

            updatePipDisplay()
        } else {
            // Show main UI elements
            binding.btnFavorite.visibility = View.VISIBLE
            binding.btnPrevStation.visibility = View.VISIBLE
            binding.btnNextStation.visibility = View.VISIBLE
            binding.controlBar.visibility = View.VISIBLE
            binding.stationBar.visibility = View.VISIBLE
            binding.tvFrequency.visibility = View.VISIBLE
            binding.frequencyScale.visibility = View.VISIBLE

            // Restore debug overlays based on checkbox states
            binding.debugOverlay.visibility = if (binding.checkRdsInfo.isChecked == true) View.VISIBLE else View.GONE
            binding.debugChecklistOverlay.visibility = View.VISIBLE
            binding.debugLayoutOverlay.visibility = if (binding.checkLayoutInfo.isChecked == true) View.VISIBLE else View.GONE
            binding.debugBuildOverlay.visibility = if (binding.checkBuildInfo.isChecked == true) View.VISIBLE else View.GONE
            binding.debugDeezerOverlay.visibility = if (binding.checkDeezerInfo.isChecked == true) View.VISIBLE else View.GONE
            binding.debugButtonsOverlay.visibility = if (binding.checkDebugButtons.isChecked == true) View.VISIBLE else View.GONE

            // Hide PiP specific layout
            binding.pipLayout.root.visibility = View.GONE
        }
    }

    /**
     * Initialisiert alle Repositories.
     */
    private fun initRepositories() {
        presetRepository = PresetRepository(this)

        // Cover Source Lock laden
        coverSourceLocked = presetRepository.isCoverSourceLocked()
        val savedLockedSource = presetRepository.getLockedCoverSource()
        lockedCoverSource = if (savedLockedSource != null) {
            try { DabCoverSource.valueOf(savedLockedSource) } catch (e: Exception) { null }
        } else null

        FmNative.initAudio(this)
        fmNative = FmNative.getInstance()
        rdsManager = RdsManager(fmNative)

        // Root-Fallback Listener für UIS7870/DUDU7 Geräte
        rdsManager.setRootRequiredListener {
            runOnUiThread { toast(R.string.root_required_message, long = true) }
        }

        radioScanner = at.planqton.fytfm.scanner.RadioScanner(rdsManager)
        updateRepository = UpdateRepository(this)
        rdsLogRepository = RdsLogRepository(this)
        rdsLogRepository.performCleanup()
        radioLogoRepository = at.planqton.fytfm.data.logo.RadioLogoRepository(this)
    }

    /**
     * Initialisiert den RadioController für unified hardware access.
     */
    private fun initRadioController() {
        radioController = at.planqton.fytfm.controller.RadioController(
            context = this,
            fmNative = fmNative,
            rdsManager = rdsManager,
            dabTunerManager = dabTunerManager,
            presetRepository = presetRepository,
            twUtil = twUtil
        )
        radioController.initialize()
        setupRadioControllerDabCallbacks()
        setupMockDabCallbacks()
    }

    /**
     * Initialisiert externe Services (Deezer, Update Badge, TWUtil, etc.).
     */
    private fun initExternalServices() {
        loadDlsLogFromFile()
        initDeezerIntegration()
        startOverlayServiceIfEnabled()

        // Update Badge auf Settings-Button
        updateBadge = binding.updateBadge
        updateRepository.setStateListener { state ->
            runOnUiThread {
                updateBadge.visibility = if (state is UpdateState.UpdateAvailable) View.VISIBLE else View.GONE
                settingsUpdateListener?.invoke(state)
            }
        }
        updateRepository.checkForUpdatesSilent()

        // TWUtil for MCU communication
        twUtil = TWUtilHelper()
        if (twUtil?.isAvailable == true) {
            android.util.Log.i(TAG, "TWUtil available, opening...")
            if (twUtil?.open() == true) {
                android.util.Log.i(TAG, "TWUtil opened successfully")
            }
        }
        DebugReceiver.setTwUtil(twUtil)

        setAsPreferredRadioApp()
    }

    /**
     * DAB-spezifische Initialisierung beim App-Start.
     */
    private fun setupDabModeOnStartup() {
        startDlsTimestampUpdates()
        val (lastServiceId, lastEnsembleId) = loadLastDabService()
        currentDabServiceId = lastServiceId
        currentDabEnsembleId = lastEnsembleId
        android.util.Log.i(TAG, "=== APP START DAB: currentDabServiceId=$currentDabServiceId ===")

        if (isCarouselMode) {
            populateCarousel()
            updateCarouselSelection()
            binding.controlBar.visibility = View.VISIBLE
            binding.stationBar.visibility = View.VISIBLE
            updateRecordButtonVisibility()
            updateEpgButtonVisibility()
        } else {
            mainContentArea?.visibility = View.GONE
            carouselContentArea?.visibility = View.GONE
            dabListContentArea?.visibility = View.VISIBLE
            dabListStationStrip?.visibility = View.GONE
            binding.controlBar.visibility = View.GONE
            binding.stationBar.visibility = View.VISIBLE
            populateDabListMode()
            updateDabListModeSelection()
        }
        initDabDisplay()
        if (!isDabOn && presetRepository.isAutoplayAtStartup()) {
            toggleDabPower()
        }
    }

    /**
     * Initiale MediaSession mit aktueller Station aktualisieren.
     */
    private fun updateInitialMediaSession(lastFreq: Float) {
        val isAM = isAmMode
        val stations = if (isAM) presetRepository.loadAmStations() else presetRepository.loadFmStations()
        val savedStation = stations.find { Math.abs(it.frequency - lastFreq) < 0.05f }
        val savedStationName = savedStation?.name
        val radioLogoPath = radioLogoRepository.getLogoForStation(savedStationName, null, lastFreq)
        android.util.Log.d(TAG, "Initial MediaSession: freq=$lastFreq, stationName=$savedStationName")

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
    }

    private fun initViews() {
        // tvFrequency, frequencyScale, btnPrevStation, btnNextStation, btnFavorite, btnPlayPause, btnPower, spinnerRadioMode now via binding
        setupRadioModeSpinner()
        // controlBar, stationBar, stationRecycler, tvAllList, debugOverlay, debugChecklistOverlay now via binding

        // All debug views now via binding (RDS, Layout, Build, Deezer, Buttons, SWC, Carousel, Station, Tuner, Parser overlays)

        // DLS Log Button
        binding.btnDlsLog.setOnClickListener {
            showDlsLogDialog()
        }

        setupParserLogOverlay()

        // Now Playing, Carousel, PiP views now via binding

        // PS triple-tap to rename station
        val psTapListener = View.OnClickListener {
            handlePsTripleTap()
        }
        binding.nowPlayingPs.setOnClickListener(psTapListener)
        binding.carouselNowPlayingPs.setOnClickListener(psTapListener)

        // Cover tap to toggle between Deezer and Slideshow (DAB only)
        val coverTapListener = View.OnClickListener {
            if (isDabMode) toggleDabCover()
        }
        binding.nowPlayingCover.setOnClickListener(coverTapListener)
        binding.carouselNowPlayingCover.setOnClickListener(coverTapListener)

        // Long press (5 seconds) to lock/unlock cover source
        setupCoverLongPressListener(binding.nowPlayingCover)
        setupCoverLongPressListener(binding.carouselNowPlayingCover)

        // Cover source dot indicators (via binding)
        nowPlayingCoverDots = binding.nowPlayingCoverDots
        carouselCoverDots = binding.carouselCoverDots

        // PiP Layout (via binding.pipLayout.*)
        setupPipButtons()

        // Correction Helper Buttons and Deezer Toggles now via binding
        setupDeezerToggle()
        setupCorrectionHelpers()

        // View Mode Toggle (via binding)
        mainContentArea = binding.mainContentArea
        carouselContentArea = binding.carouselContentArea
        dabListContentArea = binding.dabListContentArea
        btnViewModeEqualizer = binding.btnViewModeEqualizer
        btnViewModeImage = binding.btnViewModeImage
        stationCarousel = binding.stationCarousel
        carouselFrequencyLabel = binding.carouselFrequencyLabel
        btnCarouselFavorite = binding.btnCarouselFavorite
        btnCarouselRecord = binding.btnCarouselRecord
        btnCarouselEpg = binding.btnCarouselEpg
        setupViewModeToggle()
        // NOTE: Carousel wird später in onCreate befüllt, NACH loadLastRadioMode()

        // DAB List Mode Setup
        setupDabListMode()

        // Debug Overlay Drag Setup (alle mit generischer Funktion)
        setupDebugOverlayDrag(binding.debugOverlay, "rds")
        setupDebugOverlayDrag(binding.debugBuildOverlay, "build")
        setupDebugOverlayDrag(binding.debugLayoutOverlay, "layout")
        setupDebugOverlayDrag(binding.debugDeezerOverlay, "deezer")
        setupDebugOverlayDrag(binding.debugButtonsOverlay, "buttons")
        setupDebugOverlayDrag(binding.debugSwcOverlay, "swc")
        setupDebugOverlayDrag(binding.debugCarouselOverlay, "carousel")
        setupDebugOverlayDrag(binding.debugStationOverlay, "stationoverlay")
        setupDebugOverlayDrag(binding.debugTunerOverlay, "tuner")
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
        val rootView = binding.rootLayout ?: return
        rootView.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            val newWidth = right - left
            val oldWidth = oldRight - oldLeft
            if (newWidth != oldWidth && newWidth != lastKnownWidth) {
                lastKnownWidth = newWidth
                android.util.Log.d(TAG, "Layout changed: newWidth=$newWidth, oldWidth=$oldWidth")
                checkForPipModeByViewSize(newWidth)
            }
        }

        // Initial check after view is laid out (wichtig für direkten Start im PiP-Fenster)
        rootView.post {
            val width = rootView.width
            if (width > 0 && width != lastKnownWidth) {
                lastKnownWidth = width
                android.util.Log.d(TAG, "Initial PiP check: width=$width")
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

        android.util.Log.d(TAG, "checkForPipModeByViewSize: viewWidth=$viewWidth, widthDp=$widthDp, density=$density, isPipLike=$isPipLike")

        if (isPipLike != isPipMode) {
            setPipLayout(isPipLike)
        }
    }

    private fun setupDebugChecklistDrag() {
        val checklist = binding.debugChecklistOverlay
        var dX = 0f
        var dY = 0f

        checklist.setOnTouchListener { view, event ->
            // Don't intercept clicks on checkboxes
            if (event.action == MotionEvent.ACTION_DOWN) {
                val checkbox = binding.checkRdsInfo
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
        binding.checkRdsInfo.setOnCheckedChangeListener { _, isChecked ->
            binding.debugOverlay.visibility = if (isChecked) View.VISIBLE else View.GONE
            presetRepository.setDebugWindowOpen("rds", isChecked)
            if (isChecked) {
                // Set current frequency immediately when showing overlay
                binding.debugFreq.text = String.format("%.1f MHz", binding.frequencyScale.getFrequency())
                binding.debugOverlay.post { restoreDebugWindowPosition("rds", binding.debugOverlay) }
            }
        }
        binding.checkLayoutInfo.setOnCheckedChangeListener { _, isChecked ->
            binding.debugLayoutOverlay.visibility = if (isChecked) View.VISIBLE else View.GONE
            presetRepository.setDebugWindowOpen("layout", isChecked)
            if (isChecked) {
                binding.debugLayoutOverlay.post { restoreDebugWindowPosition("layout", binding.debugLayoutOverlay) }
                startUiInfoUpdates()
            } else {
                stopUiInfoUpdates()
            }
        }
        binding.checkBuildInfo.setOnCheckedChangeListener { _, isChecked ->
            binding.debugBuildOverlay.visibility = if (isChecked) View.VISIBLE else View.GONE
            presetRepository.setDebugWindowOpen("build", isChecked)
            if (isChecked) {
                debugManager.updateBuildDebugInfo()
                binding.debugBuildOverlay.post { restoreDebugWindowPosition("build", binding.debugBuildOverlay) }
            }
        }
        binding.checkDeezerInfo.setOnCheckedChangeListener { _, isChecked ->
            binding.debugDeezerOverlay.visibility = if (isChecked) View.VISIBLE else View.GONE
            presetRepository.setDebugWindowOpen("spotify", isChecked)
            if (isChecked) {
                binding.debugDeezerOverlay.post { restoreDebugWindowPosition("spotify", binding.debugDeezerOverlay) }
            }
        }
        binding.checkDebugButtons.setOnCheckedChangeListener { _, isChecked ->
            binding.debugButtonsOverlay.visibility = if (isChecked) View.VISIBLE else View.GONE
            presetRepository.setDebugWindowOpen("buttons", isChecked)
            if (isChecked) {
                binding.debugButtonsOverlay.post { restoreDebugWindowPosition("buttons", binding.debugButtonsOverlay) }
            }
        }
        binding.checkSwcInfo.setOnCheckedChangeListener { _, isChecked ->
            binding.debugSwcOverlay.visibility = if (isChecked) View.VISIBLE else View.GONE
            presetRepository.setDebugWindowOpen("swc", isChecked)
            if (isChecked) {
                binding.debugSwcOverlay.post { restoreDebugWindowPosition("swc", binding.debugSwcOverlay) }
            }
        }
        binding.checkCarouselInfo.setOnCheckedChangeListener { _, isChecked ->
            binding.debugCarouselOverlay.visibility = if (isChecked) View.VISIBLE else View.GONE
            presetRepository.setDebugWindowOpen("carousel", isChecked)
            if (isChecked) {
                binding.debugCarouselOverlay.post { restoreDebugWindowPosition("carousel", binding.debugCarouselOverlay) }
            }
        }
        binding.checkStationOverlayDebug.setOnCheckedChangeListener { _, isChecked ->
            binding.debugStationOverlay.visibility = if (isChecked) View.VISIBLE else View.GONE
            presetRepository.setDebugWindowOpen("stationoverlay", isChecked)
            if (isChecked) {
                binding.debugStationOverlay.post { restoreDebugWindowPosition("stationoverlay", binding.debugStationOverlay) }
                // Update station count (only if adapter is initialized)
                if (::stationAdapter.isInitialized) {
                    binding.debugStationOverlayCount.text = stationAdapter.getStations().size.toString()
                }
            }
        }
        binding.checkStationOverlayPermanent.setOnCheckedChangeListener { _, isChecked ->
            presetRepository.setPermanentStationOverlay(isChecked)
            if (isChecked) {
                showPermanentStationOverlay()
                binding.debugStationOverlayStatus.text = "visible"
            } else {
                hidePermanentStationOverlay()
                binding.debugStationOverlayStatus.text = "hidden"
            }
        }
        binding.checkTunerInfo.setOnCheckedChangeListener { _, isChecked ->
            binding.debugTunerOverlay.visibility = if (isChecked) View.VISIBLE else View.GONE
            presetRepository.setDebugWindowOpen("tuner", isChecked)
            if (isChecked) {
                binding.debugTunerOverlay.post { restoreDebugWindowPosition("tuner", binding.debugTunerOverlay) }
                startTunerInfoUpdates()
            } else {
                stopTunerInfoUpdates()
            }
        }
        binding.checkParserInfo.setOnCheckedChangeListener { _, isChecked ->
            binding.debugParserOverlay.visibility = if (isChecked) View.VISIBLE else View.GONE
            presetRepository.setDebugWindowOpen("parser", isChecked)
            if (isChecked) {
                binding.debugParserOverlay.post { restoreDebugWindowPosition("parser", binding.debugParserOverlay) }
                updateParserLogDisplay()
            }
        }
    }

    private fun restoreDebugWindowStates() {
        // Restore checkbox states (this will trigger the listeners which set visibility)
        binding.checkRdsInfo.isChecked = presetRepository.isDebugWindowOpen("rds", false)
        binding.checkLayoutInfo.isChecked = presetRepository.isDebugWindowOpen("layout", false)
        binding.checkBuildInfo.isChecked = presetRepository.isDebugWindowOpen("build", false)
        binding.checkDeezerInfo.isChecked = presetRepository.isDebugWindowOpen("spotify", false)
        binding.checkDebugButtons.isChecked = presetRepository.isDebugWindowOpen("buttons", false)
        binding.checkSwcInfo.isChecked = presetRepository.isDebugWindowOpen("swc", false)
        binding.checkCarouselInfo.isChecked = presetRepository.isDebugWindowOpen("carousel", false)
        binding.checkStationOverlayDebug.isChecked = presetRepository.isDebugWindowOpen("stationoverlay", false)
        binding.checkStationOverlayPermanent.isChecked = presetRepository.isPermanentStationOverlay()
        binding.checkTunerInfo.isChecked = presetRepository.isDebugWindowOpen("tuner", false)
        binding.checkParserInfo.isChecked = presetRepository.isDebugWindowOpen("parser", false)

        // Restore positions (post to ensure views are laid out)
        binding.debugOverlay.post { restoreDebugWindowPosition("rds", binding.debugOverlay) }
        binding.debugLayoutOverlay.post { restoreDebugWindowPosition("layout", binding.debugLayoutOverlay) }
        binding.debugBuildOverlay.post { restoreDebugWindowPosition("build", binding.debugBuildOverlay) }
        binding.debugDeezerOverlay.post { restoreDebugWindowPosition("spotify", binding.debugDeezerOverlay) }
        binding.debugButtonsOverlay.post { restoreDebugWindowPosition("buttons", binding.debugButtonsOverlay) }
        binding.debugSwcOverlay.post { restoreDebugWindowPosition("swc", binding.debugSwcOverlay) }
        binding.debugCarouselOverlay.post { restoreDebugWindowPosition("carousel", binding.debugCarouselOverlay) }
        binding.debugStationOverlay.post { restoreDebugWindowPosition("stationoverlay", binding.debugStationOverlay) }
        binding.debugTunerOverlay.post { restoreDebugWindowPosition("tuner", binding.debugTunerOverlay) }
        binding.debugParserOverlay.post { restoreDebugWindowPosition("parser", binding.debugParserOverlay) }
        binding.debugChecklistOverlay.post { restoreDebugWindowPosition("checklist", binding.debugChecklistOverlay) }
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

    /**
     * Setzt das Cover auf Station Logo oder Fallback zurück (wenn Deezer nichts findet)
     */
    private fun resetCoverToFallback() {
        if (isDabMode) {
            // DAB+ Mode: Lock-Status beachten
            val isLockedToDabLogo = coverSourceLocked && lockedCoverSource == DabCoverSource.DAB_LOGO
            val isLockedToSlideshow = coverSourceLocked && lockedCoverSource == DabCoverSource.SLIDESHOW
            val isLockedToStationLogo = coverSourceLocked && lockedCoverSource == DabCoverSource.STATION_LOGO
            val radioLogoPath = getLogoForDabStation(currentDabServiceLabel, currentDabServiceId)

            when {
                isLockedToDabLogo -> {
                    // Gelockt auf DAB+ Logo
                    currentUiCoverSource = "drawable:ic_fytfm_dab_plus_light"
                    binding.nowPlayingCover.setImageResource(R.drawable.ic_cover_placeholder)
                    binding.carouselNowPlayingCover.setImageResource(R.drawable.ic_cover_placeholder)
                    dabListCover?.setImageResource(R.drawable.ic_fytfm_dab_plus_light)
                }
                isLockedToSlideshow && currentDabSlideshow != null -> {
                    // Gelockt auf Slideshow
                    currentUiCoverSource = "slideshow"
                    binding.nowPlayingCover.setImageBitmap(currentDabSlideshow)
                    binding.carouselNowPlayingCover.setImageBitmap(currentDabSlideshow)
                    dabListCover?.setImageBitmap(currentDabSlideshow)
                }
                isLockedToStationLogo && radioLogoPath != null -> {
                    // Gelockt auf Station Logo
                    currentUiCoverSource = radioLogoPath
                    binding.nowPlayingCover.load(java.io.File(radioLogoPath)) { crossfade(true) }
                    binding.carouselNowPlayingCover.load(java.io.File(radioLogoPath)) { crossfade(true) }
                    dabListCover?.load(java.io.File(radioLogoPath)) { crossfade(true) }
                }
                !coverSourceLocked && currentDabSlideshow != null -> {
                    // Nicht gelockt - Slideshow wenn verfügbar
                    currentUiCoverSource = "slideshow"
                    binding.nowPlayingCover.setImageBitmap(currentDabSlideshow)
                    binding.carouselNowPlayingCover.setImageBitmap(currentDabSlideshow)
                    dabListCover?.setImageBitmap(currentDabSlideshow)
                }
                !coverSourceLocked && radioLogoPath != null -> {
                    // Nicht gelockt - Station Logo wenn verfügbar
                    currentUiCoverSource = radioLogoPath
                    binding.nowPlayingCover.load(java.io.File(radioLogoPath)) { crossfade(true) }
                    binding.carouselNowPlayingCover.load(java.io.File(radioLogoPath)) { crossfade(true) }
                    dabListCover?.load(java.io.File(radioLogoPath)) { crossfade(true) }
                }
                else -> {
                    // Fallback auf DAB+ Icon
                    currentUiCoverSource = "drawable:ic_fytfm_dab_plus_light"
                    binding.nowPlayingCover.setImageResource(R.drawable.ic_cover_placeholder)
                    binding.carouselNowPlayingCover.setImageResource(R.drawable.ic_cover_placeholder)
                    dabListCover?.setImageResource(R.drawable.ic_fytfm_dab_plus_light)
                }
            }
            // Carousel auch zurücksetzen
            stationCarouselAdapter?.updateCurrentCover(null, null)
        } else {
            // FM/AM Mode: Station Logo oder Placeholder
            val currentFreq = binding.frequencyScale.getFrequency()
            val isAM = currentMode == FrequencyScaleView.RadioMode.AM
            val placeholderDrawable = if (isAM) R.drawable.placeholder_am else R.drawable.placeholder_fm
            val stationLogo = radioLogoRepository.getLogoForStation(
                ps = rdsManager.ps,
                pi = rdsManager.pi,
                frequency = currentFreq
            )
            if (stationLogo != null) {
                currentUiCoverSource = stationLogo
                binding.nowPlayingCover.load(java.io.File(stationLogo)) {
                    crossfade(true)
                    placeholder(placeholderDrawable)
                    error(placeholderDrawable)
                }
            } else {
                currentUiCoverSource = if (isAM) "drawable:placeholder_am" else "drawable:placeholder_fm"
                binding.nowPlayingCover.setImageResource(placeholderDrawable)
            }
            // Carousel auch zurücksetzen
            if (isCarouselMode) {
                stationCarouselAdapter?.updateCurrentCover(null, null)
            }
        }
    }

    private fun startUiInfoUpdates() {
        if (uiInfoUpdateHandler == null) {
            uiInfoUpdateHandler = android.os.Handler(android.os.Looper.getMainLooper())
        }
        uiInfoUpdateRunnable = object : Runnable {
            override fun run() {
                debugManager.updateLayoutDebugInfo(currentUiCoverSource)
                uiInfoUpdateHandler?.postDelayed(this, 500) // Update every 500ms
            }
        }
        uiInfoUpdateRunnable?.run()
    }

    private fun stopUiInfoUpdates() {
        uiInfoUpdateRunnable?.let { uiInfoUpdateHandler?.removeCallbacks(it) }
        uiInfoUpdateRunnable = null
    }

    private fun startTunerInfoUpdates() {
        if (tunerInfoUpdateHandler == null) {
            tunerInfoUpdateHandler = android.os.Handler(android.os.Looper.getMainLooper())
        }
        tunerInfoUpdateRunnable = object : Runnable {
            override fun run() {
                debugManager.updateTunerDebugInfo(currentMode, radioController.isDabAvailable())
                tunerInfoUpdateHandler?.postDelayed(this, 1000) // Update every second
            }
        }
        tunerInfoUpdateRunnable?.run()
    }

    private fun stopTunerInfoUpdates() {
        tunerInfoUpdateRunnable?.let { tunerInfoUpdateHandler?.removeCallbacks(it) }
        tunerInfoUpdateRunnable = null
    }

    private fun startDlsTimestampUpdates() {
        if (dlsTimestampUpdateHandler == null) {
            dlsTimestampUpdateHandler = android.os.Handler(android.os.Looper.getMainLooper())
        }
        dlsTimestampUpdateRunnable = object : Runnable {
            override fun run() {
                debugManager.lastDlsTimestamp = lastDlsTimestamp
                debugManager.updateDlsTimestampLabel()
                dlsTimestampUpdateHandler?.postDelayed(this, 1000)
            }
        }
        dlsTimestampUpdateRunnable?.run()
    }

    private fun stopDlsTimestampUpdates() {
        dlsTimestampUpdateRunnable?.let { dlsTimestampUpdateHandler?.removeCallbacks(it) }
        dlsTimestampUpdateRunnable = null
    }

    /**
     * Reset DLS/Artist/Title beim DAB-Senderwechsel
     */
    private fun resetDabNowPlaying() {
        currentDabDls = null
        binding.nowPlayingArtist.text = ""
        binding.nowPlayingArtist.visibility = View.GONE
        binding.nowPlayingTitle.text = ""
        binding.nowPlayingTitle.visibility = View.GONE
        binding.carouselNowPlayingArtist.text = ""
        binding.carouselNowPlayingArtist.visibility = View.GONE
        binding.carouselNowPlayingTitle.text = ""
        binding.carouselNowPlayingTitle.visibility = View.GONE
        updateDabListRadiotext("")
    }

    /**
     * Generische Drag-Setup Funktion für Debug-Overlays.
     * Ermöglicht das Verschieben von Debug-Fenstern per Touch.
     */
    private fun setupDebugOverlayDrag(overlay: View?, windowId: String) {
        overlay ?: return
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
                    saveDebugWindowPosition(windowId, view)
                    true
                }
                else -> false
            }
        }
    }

    private fun setupParserLogOverlay() {
        setupDebugOverlayDrag(debugParserOverlay, "parser")

        val btnFm = binding.btnParserTabFm
        val btnDab = binding.btnParserTabDab

        // Tab button click handlers
        btnFm?.setOnClickListener {
            currentParserTab = at.planqton.fytfm.deezer.ParserLogger.Source.FM
            updateParserTabButtons()
            updateParserLogDisplay()
        }

        btnDab?.setOnClickListener {
            currentParserTab = at.planqton.fytfm.deezer.ParserLogger.Source.DAB
            updateParserTabButtons()
            updateParserLogDisplay()
        }

        // Setup Clear button (clears current tab)
        binding.btnParserClear.setOnClickListener {
            if (currentParserTab == at.planqton.fytfm.deezer.ParserLogger.Source.FM) {
                at.planqton.fytfm.deezer.ParserLogger.clearFm()
            } else {
                at.planqton.fytfm.deezer.ParserLogger.clearDab()
            }
            updateParserLogDisplay()
        }

        // Setup Export button (exports current tab)
        binding.btnParserExport.setOnClickListener {
            exportParserLog()
        }

        // Setup listeners for real-time updates (both FM and DAB)
        parserLogListener = { _ ->
            runOnUiThread {
                if (binding.debugParserOverlay.visibility == View.VISIBLE) {
                    updateParserLogDisplay()
                }
            }
        }
        parserLogListener?.let {
            at.planqton.fytfm.deezer.ParserLogger.addFmListener(it)
            at.planqton.fytfm.deezer.ParserLogger.addDabListener(it)
        }
    }

    private fun updateParserTabButtons() {
        val btnFm = binding.btnParserTabFm
        val btnDab = binding.btnParserTabDab

        if (currentParserTab == at.planqton.fytfm.deezer.ParserLogger.Source.FM) {
            btnFm?.setBackgroundColor(0xFF4CAF50.toInt())  // Green = active
            btnDab?.setBackgroundColor(0xFF555555.toInt()) // Gray = inactive
        } else {
            btnFm?.setBackgroundColor(0xFF555555.toInt())  // Gray = inactive
            btnDab?.setBackgroundColor(0xFF4CAF50.toInt()) // Green = active
        }
    }

    private fun updateParserLogDisplay() {
        val entries = if (currentParserTab == at.planqton.fytfm.deezer.ParserLogger.Source.FM) {
            at.planqton.fytfm.deezer.ParserLogger.getFmEntries()
        } else {
            at.planqton.fytfm.deezer.ParserLogger.getDabEntries()
        }

        if (entries.isEmpty()) {
            val tabName = if (currentParserTab == at.planqton.fytfm.deezer.ParserLogger.Source.FM) "FM" else "DAB+"
            binding.parserLogText.text = getString(R.string.no_log_entries, tabName)
        } else {
            // Show most recent entries at top (reverse order) with color coding
            val coloredText = android.text.SpannableStringBuilder()
            val greenColor = android.graphics.Color.parseColor("#4CAF50")
            val redColor = android.graphics.Color.parseColor("#F44336")

            entries.reversed().forEachIndexed { index, entry ->
                if (index > 0) coloredText.append("\n")

                val formatted = entry.format()
                val start = coloredText.length
                coloredText.append(formatted)
                val end = coloredText.length

                // Rot wenn fehlgeschlagen (→ X), sonst grün
                val color = if (entry.parsedResult == null) redColor else greenColor
                coloredText.setSpan(
                    android.text.style.ForegroundColorSpan(color),
                    start, end,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            binding.parserLogText.text = coloredText
        }
        // Auto-scroll to top (most recent)
        binding.parserLogScrollView.post { binding.parserLogScrollView.scrollTo(0, 0) }
    }

    private var pendingParserLogExport: String? = null

    private val parserLogExportLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        uri?.let { saveUri ->
            try {
                val content = pendingParserLogExport ?: return@let
                contentResolver.openOutputStream(saveUri)?.use { out ->
                    out.write(content.toByteArray())
                }
                toast(R.string.parser_log_exported)
            } catch (e: Exception) {
                toast(getString(R.string.export_failed, e.message))
            } finally {
                pendingParserLogExport = null
            }
        }
    }

    private fun exportParserLog() {
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
        val tabName = if (currentParserTab == at.planqton.fytfm.deezer.ParserLogger.Source.FM) "fm" else "dab"

        pendingParserLogExport = if (currentParserTab == at.planqton.fytfm.deezer.ParserLogger.Source.FM) {
            at.planqton.fytfm.deezer.ParserLogger.exportFm()
        } else {
            at.planqton.fytfm.deezer.ParserLogger.exportDab()
        }

        parserLogExportLauncher.launch("fytfm_parser_${tabName}_log_$timestamp.txt")
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

        binding.debugSwcLog.text = swcLogEntries.joinToString("\n")
    }

    private fun setupDebugOverlayAlphaSliders() {
        // Global Alpha Slider for all Debug Overlays
        binding.debugOverlaysAlphaSlider.apply {
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
        binding.debugOverlay.alpha = alpha
        binding.debugLayoutOverlay.alpha = alpha
        binding.debugBuildOverlay.alpha = alpha
        binding.debugDeezerOverlay.alpha = alpha
        binding.debugButtonsOverlay.alpha = alpha
        binding.debugSwcOverlay.alpha = alpha
        binding.debugCarouselOverlay.alpha = alpha
        binding.debugTunerOverlay.alpha = alpha
        binding.debugParserOverlay.alpha = alpha
        binding.debugChecklistOverlay.alpha = alpha
    }

    private fun setupDebugButtonsListeners() {
        binding.btnDebugKillInternet.setOnClickListener {
            debugInternetDisabled = !debugInternetDisabled
            // SpotifyClient Flag synchronisieren
            DeezerClient.debugInternetDisabled = debugInternetDisabled
            val btn = it as android.widget.Button
            if (debugInternetDisabled) {
                btn.text = "App Internet: OFF"
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF666666.toInt())
                toast(R.string.app_internet_disabled)
            } else {
                btn.text = "Kill App Internet Connection"
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFCC3333.toInt())
                toast(R.string.app_internet_enabled)
            }
        }

        // View Reports Button
        binding.btnDebugViewReports.setOnClickListener {
            startActivity(android.content.Intent(this, BugReportActivity::class.java))
        }

        // Block Spotify/Local Toggle Button
        binding.btnDebugBlockDeezer.setOnCheckedChangeListener { btn, isChecked ->
            debugDeezerBlocked = isChecked
            if (isChecked) {
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF993333.toInt())
                toast(R.string.deezer_local_blocked)
            } else {
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFCC9933.toInt())
                toast(R.string.deezer_local_enabled)
            }
        }

        // Kill App Button
        binding.btnDebugKillApp.setOnClickListener {
            android.os.Process.killProcess(android.os.Process.myPid())
        }

        // Crash App Button
        binding.btnDebugCrashApp.setOnClickListener {
            throw RuntimeException("Debug crash triggered by user")
        }
    }

    private fun setupPipButtons() {
        fun getStep() = if (isFmMode) FrequencyScaleView.FM_FREQUENCY_STEP else FrequencyScaleView.AM_FREQUENCY_STEP

        binding.pipLayout.pipBtnPrev.setOnClickListener {
            binding.frequencyScale.setFrequency(binding.frequencyScale.getFrequency() - getStep())
        }
        binding.pipLayout.pipBtnNext.setOnClickListener {
            binding.frequencyScale.setFrequency(binding.frequencyScale.getFrequency() + getStep())
        }
        binding.pipLayout.pipBtnPlayPause.setOnClickListener {
            binding.btnPlayPause.performClick()
        }
    }

    private fun updatePipDisplay() {
        if (!isPipMode) return

        // Update frequency/station name
        val ps = rdsManager.ps
        val frequency = binding.frequencyScale.getFrequency()
        binding.pipLayout.pipTitle.text = if (!ps.isNullOrBlank()) ps else "FM ${String.format("%.2f", frequency)}"

        // Always show raw RT from RDS
        val rawRt = rdsManager.rt
        binding.pipLayout.pipRawRt.text = rawRt ?: ""

        // Update artist (from Spotify/Local track info)
        val trackInfo = currentDeezerTrackInfo
        if (trackInfo != null) {
            binding.pipLayout.pipArtist.text = "${trackInfo.artist} - ${trackInfo.title}"
            // Load cover image - nur lokale Pfade, keine HTTP URLs
            val localCover = trackInfo.coverUrl?.takeIf { it.startsWith("/") }
            if (!localCover.isNullOrBlank()) {
                binding.pipLayout.pipCoverImage.load(java.io.File(localCover)) {
                    crossfade(true)
                    placeholder(R.drawable.ic_cover_placeholder)
                    error(R.drawable.ic_cover_placeholder)
                }
            } else {
                binding.pipLayout.pipCoverImage.setImageResource(R.drawable.ic_cover_placeholder)
            }
        } else {
            binding.pipLayout.pipArtist.text = ""
            binding.pipLayout.pipCoverImage.setImageResource(R.drawable.ic_cover_placeholder)
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
            currentFrequency = binding.frequencyScale.getFrequency(),

            // Spotify Data
            spotifyStatus = currentDeezerStatus,
            spotifyOriginalRt = currentDeezerOriginalRt,
            spotifyStrippedRt = currentDeezerStrippedRt,
            spotifyQuery = currentDeezerQuery,
            spotifyTrackInfo = currentDeezerTrackInfo
        )

        val reportPath = bugReportHelper.createBugReport(appState)
        if (reportPath != null) {
            toast(R.string.bug_report_created)
        } else {
            toast(R.string.bug_report_failed)
        }
    }

    // Methode zum Aktualisieren der Spotify Debug-Anzeige
    fun updateDeezerDebugInfo(status: String, originalRt: String?, strippedRt: String?, query: String?, trackInfo: TrackInfo?) {
        if (binding.debugDeezerOverlay.visibility != View.VISIBLE) return

        // Status und Input immer setzen
        debugManager.updateDeezerStatusAndInput(status, originalRt, strippedRt)

        // Bei "Waiting..." explizit alles clearen (Senderwechsel)
        if (status == "Waiting...") {
            lastDebugTrackId = null
            debugManager.clearDeezerDebugFields()
            resetCoverToFallback()
            return
        }

        // Bei "Not found" - Status anzeigen und Cover auf Fallback zurücksetzen
        if (status == "Not found") {
            debugManager.updateDeezerNotFound()
            resetCoverToFallback()
            return
        }

        // Ignoriere null trackInfo (während "Processing...", "Searching..." etc.)
        if (trackInfo == null) return

        // Source-Anzeige aktualisieren
        debugManager.updateDeezerSource(status)

        // Prüfen ob sich der Track geändert hat
        val newTrackId = trackInfo.trackId
        if (newTrackId == lastDebugTrackId) return
        lastDebugTrackId = newTrackId

        // Load cover image - nur lokale Pfade
        loadCoverImage(trackInfo.coverUrl?.takeIf { it.startsWith("/") })

        // Track-Info aktualisieren
        debugManager.updateDeezerTrackInfo(
            artist = trackInfo.artist,
            title = trackInfo.title,
            allArtists = trackInfo.allArtists,
            durationMs = trackInfo.durationMs,
            popularity = trackInfo.popularity,
            explicit = trackInfo.explicit,
            trackNumber = trackInfo.trackNumber,
            discNumber = trackInfo.discNumber,
            isrc = trackInfo.isrc,
            album = trackInfo.album,
            albumType = trackInfo.albumType,
            totalTracks = trackInfo.totalTracks,
            releaseDate = trackInfo.releaseDate,
            trackId = trackInfo.trackId,
            albumId = trackInfo.albumId,
            deezerUrl = trackInfo.deezerUrl,
            albumUrl = trackInfo.albumUrl,
            previewUrl = trackInfo.previewUrl,
            coverUrl = trackInfo.coverUrl,
            coverUrlMedium = trackInfo.coverUrlMedium
        )
    }

    /**
     * Ermittelt die aktuell verfügbaren Cover-Quellen.
     * Reihenfolge: DAB_LOGO (immer), STATION_LOGO (wenn vorhanden), SLIDESHOW (wenn vorhanden), DEEZER (wenn vorhanden)
     */
    private fun getAvailableCoverSources(): List<DabCoverSource> {
        val sources = mutableListOf<DabCoverSource>()
        sources.add(DabCoverSource.DAB_LOGO)  // Immer verfügbar

        val radioLogoPath = getLogoForDabStation(currentDabServiceLabel, currentDabServiceId)
        if (radioLogoPath != null) sources.add(DabCoverSource.STATION_LOGO)

        if (currentDabSlideshow != null) sources.add(DabCoverSource.SLIDESHOW)

        // Deezer nur wenn aktiviert UND Cover vorhanden
        val deezerEnabled = presetRepository.isDeezerEnabledDab()
        if (deezerEnabled && !currentDabDeezerCoverPath.isNullOrBlank()) sources.add(DabCoverSource.DEEZER)

        return sources
    }

    /**
     * Aktualisiert die Cover-Anzeige basierend auf der ausgewählten Quelle.
     * Bei selectedCoverSourceIndex = -1 wird automatisch die beste verfügbare Quelle gewählt.
     */
    private fun updateDabCoverDisplay() {
        if (!isDabMode) return

        availableCoverSources = getAvailableCoverSources()
        val radioLogoPath = getLogoForDabStation(currentDabServiceLabel, currentDabServiceId)

        // Bestimme welche Quelle angezeigt werden soll
        val sourceToShow = when {
            // Wenn gesperrt und gesperrte Quelle verfügbar -> diese verwenden
            coverSourceLocked && lockedCoverSource != null && availableCoverSources.contains(lockedCoverSource) -> {
                lockedCoverSource!!
            }
            // Wenn manuell ausgewählt -> diese verwenden
            selectedCoverSourceIndex >= 0 && selectedCoverSourceIndex < availableCoverSources.size -> {
                availableCoverSources[selectedCoverSourceIndex]
            }
            // Auto-Modus: Beste verfügbare Quelle (Deezer > Slideshow > StationLogo > DAB)
            else -> when {
                availableCoverSources.contains(DabCoverSource.DEEZER) -> DabCoverSource.DEEZER
                availableCoverSources.contains(DabCoverSource.SLIDESHOW) -> DabCoverSource.SLIDESHOW
                availableCoverSources.contains(DabCoverSource.STATION_LOGO) -> DabCoverSource.STATION_LOGO
                else -> DabCoverSource.DAB_LOGO
            }
        }

        when (sourceToShow) {
            DabCoverSource.DEEZER -> {
                val localCover = currentDabDeezerCoverPath!!
                currentUiCoverSource = localCover
                binding.nowPlayingCover.load(java.io.File(localCover)) { crossfade(true) }
                binding.carouselNowPlayingCover.load(java.io.File(localCover)) { crossfade(true) }
                dabListCover?.load(java.io.File(localCover)) { crossfade(true) }
                loadCoverImage(localCover)

                FytFMMediaService.instance?.updateDabMetadata(
                    serviceLabel = currentDabServiceLabel,
                    ensembleLabel = currentDabEnsembleLabel,
                    dls = currentDabDls,
                    slideshowBitmap = null,
                    radioLogoPath = radioLogoPath,
                    deezerCoverPath = localCover
                )
                android.util.Log.d(TAG, "Cover: Deezer")
            }
            DabCoverSource.SLIDESHOW -> {
                val bitmap = currentDabSlideshow!!
                currentUiCoverSource = "slideshow"
                binding.nowPlayingCover.setImageBitmap(bitmap)
                binding.carouselNowPlayingCover.setImageBitmap(bitmap)
                dabListCover?.setImageBitmap(bitmap)

                FytFMMediaService.instance?.updateDabMetadata(
                    serviceLabel = currentDabServiceLabel,
                    ensembleLabel = currentDabEnsembleLabel,
                    dls = currentDabDls,
                    slideshowBitmap = bitmap,
                    radioLogoPath = radioLogoPath,
                    deezerCoverPath = null
                )
                android.util.Log.d(TAG, "Cover: Slideshow")
            }
            DabCoverSource.STATION_LOGO -> {
                currentUiCoverSource = radioLogoPath!!
                binding.nowPlayingCover.load(java.io.File(radioLogoPath)) { crossfade(true) }
                binding.carouselNowPlayingCover.load(java.io.File(radioLogoPath)) { crossfade(true) }
                dabListCover?.load(java.io.File(radioLogoPath)) { crossfade(true) }

                FytFMMediaService.instance?.updateDabMetadata(
                    serviceLabel = currentDabServiceLabel,
                    ensembleLabel = currentDabEnsembleLabel,
                    dls = currentDabDls,
                    slideshowBitmap = null,
                    radioLogoPath = radioLogoPath,
                    deezerCoverPath = null
                )
                android.util.Log.d(TAG, "Cover: Station Logo")
            }
            DabCoverSource.DAB_LOGO -> {
                currentUiCoverSource = "drawable:ic_fytfm_dab_plus_light"
                binding.nowPlayingCover.setImageResource(R.drawable.ic_fytfm_dab_plus_light)
                binding.carouselNowPlayingCover.setImageResource(R.drawable.ic_fytfm_dab_plus_light)
                dabListCover?.setImageResource(R.drawable.ic_fytfm_dab_plus_light)

                FytFMMediaService.instance?.updateDabMetadata(
                    serviceLabel = currentDabServiceLabel,
                    ensembleLabel = currentDabEnsembleLabel,
                    dls = currentDabDls,
                    slideshowBitmap = null,
                    radioLogoPath = null,
                    deezerCoverPath = null
                )
                android.util.Log.d(TAG, "Cover: DAB Logo")
            }
        }

        // Deezer Wasserzeichen anzeigen/verstecken
        updateDeezerWatermarks(sourceToShow == DabCoverSource.DEEZER)

        // Zeige Indikator immer im DAB-Modus
        updateSlideshowIndicators(true)
    }

    /**
     * Aktualisiert die Sichtbarkeit der Deezer-Wasserzeichen.
     */
    private fun updateDeezerWatermarks(showDeezer: Boolean) {
        val visibility = if (showDeezer) View.VISIBLE else View.GONE
        binding.nowPlayingDeezerWatermark.visibility = visibility
        binding.carouselDeezerWatermark.visibility = visibility
        dabListDeezerWatermark?.visibility = visibility
    }

    /**
     * Aktualisiert die Dot-Indikatoren für Cover-Quellen.
     * Zeigt Punkte an für jede verfügbare Quelle, aktive Quelle ist grün.
     */
    private fun updateSlideshowIndicators(canToggle: Boolean) {
        val containers = listOf(nowPlayingCoverDots, carouselCoverDots, dabListCoverDots)

        // Feste Reihenfolge: DAB_LOGO, STATION_LOGO, SLIDESHOW, DEEZER (nur wenn aktiviert)
        val deezerEnabled = presetRepository.isDeezerEnabledDab()
        val allSources = if (deezerEnabled) {
            listOf(DabCoverSource.DAB_LOGO, DabCoverSource.STATION_LOGO, DabCoverSource.SLIDESHOW, DabCoverSource.DEEZER)
        } else {
            listOf(DabCoverSource.DAB_LOGO, DabCoverSource.STATION_LOGO, DabCoverSource.SLIDESHOW)
        }

        if (!canToggle) {
            // Nicht im DAB-Modus - verstecken
            containers.forEach { it?.visibility = View.GONE }
            return
        }

        // Farben aus Theme holen (respektiert Day/Night Mode)
        val accentColor = androidx.core.content.ContextCompat.getColor(this, R.color.radio_accent)
        val inactiveColor = androidx.core.content.ContextCompat.getColor(this, R.color.radio_text_secondary)

        // Bestimme die aktuell ausgewählte Quelle
        val selectedSource: DabCoverSource = when {
            // Wenn gesperrt und gesperrte Quelle verfügbar
            coverSourceLocked && lockedCoverSource != null && availableCoverSources.contains(lockedCoverSource) -> {
                lockedCoverSource!!
            }
            selectedCoverSourceIndex >= 0 && selectedCoverSourceIndex < availableCoverSources.size -> {
                availableCoverSources[selectedCoverSourceIndex]
            }
            else -> {
                // Auto-Modus: Beste verfügbare Quelle
                when {
                    availableCoverSources.contains(DabCoverSource.DEEZER) -> DabCoverSource.DEEZER
                    availableCoverSources.contains(DabCoverSource.SLIDESHOW) -> DabCoverSource.SLIDESHOW
                    availableCoverSources.contains(DabCoverSource.STATION_LOGO) -> DabCoverSource.STATION_LOGO
                    else -> DabCoverSource.DAB_LOGO
                }
            }
        }

        // Dots für jeden Container aktualisieren
        containers.forEach { container ->
            if (container == null) return@forEach

            container.removeAllViews()
            container.visibility = View.VISIBLE

            val dotSize = (6 * resources.displayMetrics.density).toInt()  // 6dp
            val ringSize = (10 * resources.displayMetrics.density).toInt()  // 10dp
            val spacing = (4 * resources.displayMetrics.density).toInt()  // 4dp

            for ((i, source) in allSources.withIndex()) {
                val isAvailable = availableCoverSources.contains(source)
                val isSelected = source == selectedSource

                // FrameLayout für Dot + Ring
                val frame = android.widget.FrameLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(ringSize, ringSize).apply {
                        marginStart = if (i > 0) spacing else 0
                    }
                }

                // Der Dot (Akzentfarbe wenn verfügbar, grau wenn nicht)
                val dotDrawable = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(if (isAvailable) accentColor else inactiveColor)
                    setSize(dotSize, dotSize)
                }
                val dot = View(this).apply {
                    val params = android.widget.FrameLayout.LayoutParams(dotSize, dotSize)
                    params.gravity = android.view.Gravity.CENTER
                    layoutParams = params
                    background = dotDrawable
                }
                frame.addView(dot)

                // Ring wenn ausgewählt (in Akzentfarbe)
                if (isSelected) {
                    val ringDrawable = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setStroke((1 * resources.displayMetrics.density).toInt(), accentColor)
                        setSize(ringSize, ringSize)
                    }
                    val ring = View(this).apply {
                        val params = android.widget.FrameLayout.LayoutParams(ringSize, ringSize)
                        params.gravity = android.view.Gravity.CENTER
                        layoutParams = params
                        background = ringDrawable
                    }
                    frame.addView(ring)
                }

                container.addView(frame)
            }

            // Lock-Icon hinzufügen wenn gesperrt
            if (coverSourceLocked) {
                val lockIcon = android.widget.TextView(this).apply {
                    text = "🔒"
                    textSize = 8f
                    includeFontPadding = false
                    gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
                    val params = LinearLayout.LayoutParams(
                        ringSize,
                        ringSize
                    )
                    params.marginStart = spacing
                    params.topMargin = (-2 * resources.displayMetrics.density).toInt()  // Nach oben verschieben
                    layoutParams = params
                }
                container.addView(lockIcon)
            }
        }
    }

    /**
     * Wechselt zur nächsten verfügbaren Cover-Quelle.
     * Wird aufgerufen wenn der User auf das Cover tippt.
     */
    private fun toggleDabCover() {
        // Bei Tap wird Lock aufgehoben
        if (coverSourceLocked) {
            coverSourceLocked = false
            lockedCoverSource = null
            presetRepository.setCoverSourceLocked(false)
            presetRepository.setLockedCoverSource(null)
        }

        availableCoverSources = getAvailableCoverSources()

        if (availableCoverSources.size <= 1) return  // Nur eine Quelle, kein Toggle möglich

        // Zum nächsten Index wechseln (mit wrap-around)
        selectedCoverSourceIndex = if (selectedCoverSourceIndex < 0) {
            // War im Auto-Modus, starte bei Index 0
            0
        } else {
            (selectedCoverSourceIndex + 1) % availableCoverSources.size
        }

        updateDabCoverDisplay()

        // Feste Reihenfolge für Position-Anzeige (ohne Deezer wenn deaktiviert)
        val deezerEnabled = presetRepository.isDeezerEnabledDab()
        val allSources = if (deezerEnabled) {
            listOf(DabCoverSource.DAB_LOGO, DabCoverSource.STATION_LOGO, DabCoverSource.SLIDESHOW, DabCoverSource.DEEZER)
        } else {
            listOf(DabCoverSource.DAB_LOGO, DabCoverSource.STATION_LOGO, DabCoverSource.SLIDESHOW)
        }
        val currentSource = availableCoverSources[selectedCoverSourceIndex]
        val fixedPosition = allSources.indexOf(currentSource) + 1  // 1-basiert

        // Toast mit aktuellem Source-Namen und Position in fester Reihenfolge
        val sourceName = when (currentSource) {
            DabCoverSource.DAB_LOGO -> "DAB+ Logo"
            DabCoverSource.STATION_LOGO -> "Sender Logo"
            DabCoverSource.SLIDESHOW -> "Slideshow"
            DabCoverSource.DEEZER -> "Deezer Cover"
        }
        // toast("$sourceName ($fixedPosition/${allSources.size})")
    }

    /**
     * Setzt einen 5-Sekunden Long-Press Listener auf ein Cover-View.
     * Bei erfolgreichem Long-Press wird die aktuelle Quelle gesperrt/entsperrt.
     */
    private fun setupCoverLongPressListener(view: View?) {
        if (view == null) return

        var pressStartTime = 0L
        var startY = 0f
        var startX = 0f
        val longPressDuration = 2500L  // 2.5 Sekunden
        val swipeThreshold = 100f  // Minimale Swipe-Distanz in Pixeln
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var longPressRunnable: Runnable? = null
        var isSwipe = false

        view.setOnTouchListener { v, event ->
            if (!isAnyDabMode) return@setOnTouchListener false

            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    pressStartTime = System.currentTimeMillis()
                    startY = event.y
                    startX = event.x
                    isSwipe = false
                    longPressRunnable = Runnable {
                        toggleCoverSourceLock()
                    }
                    handler.postDelayed(longPressRunnable!!, longPressDuration)
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val deltaY = event.y - startY
                    val deltaX = Math.abs(event.x - startX)
                    // Wenn nach unten gewischt wird (mehr vertikal als horizontal)
                    if (deltaY > swipeThreshold && deltaY > deltaX) {
                        longPressRunnable?.let { handler.removeCallbacks(it) }
                        isSwipe = true
                    }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let { handler.removeCallbacks(it) }
                    val deltaY = event.y - startY
                    val deltaX = Math.abs(event.x - startX)
                    val pressDuration = System.currentTimeMillis() - pressStartTime

                    android.util.Log.d(TAG, "Touch UP: deltaY=$deltaY, deltaX=$deltaX, threshold=$swipeThreshold")

                    if (deltaY > swipeThreshold && deltaY > deltaX && event.action == android.view.MotionEvent.ACTION_UP) {
                        // Swipe nach unten erkannt -> Cover als Sender-Logo speichern
                        android.util.Log.i(TAG, "Swipe-Down erkannt! Speichere Cover als Logo...")
                        saveCurrentCoverAsStationLogo(v as ImageView)
                    } else if (pressDuration < longPressDuration && !isSwipe) {
                        // Kurzer Tap -> Click-Event durchlassen
                        if (event.action == android.view.MotionEvent.ACTION_UP) {
                            v.performClick()
                        }
                    }
                }
            }
            true  // Event konsumieren für Long-Press/Swipe Erkennung
        }
    }

    /**
     * Animiert das Cover mit "Fall"-Effekt als visuelles Feedback beim Speichern.
     */
    private fun animateCoverSaveGesture(imageView: ImageView) {
        imageView.animate()
            .translationY(imageView.height.toFloat() * 0.3f)
            .scaleX(0.8f)
            .scaleY(0.8f)
            .alpha(0.5f)
            .setDuration(150)
            .withEndAction {
                imageView.animate()
                    .translationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(150)
                    .start()
            }
            .start()
    }

    /**
     * Ermittelt die aktuell ausgewählte Cover-Quelle.
     */
    private fun getSelectedCoverSource(): DabCoverSource? {
        return if (selectedCoverSourceIndex >= 0 && selectedCoverSourceIndex < availableCoverSources.size) {
            availableCoverSources[selectedCoverSourceIndex]
        } else {
            lockedCoverSource
        }
    }

    /**
     * Speichert die Cover-Quelle in eine Logo-Datei.
     * @return true wenn erfolgreich gespeichert
     */
    private fun saveCoverSourceToFile(source: DabCoverSource, logoFile: java.io.File): Boolean {
        return when (source) {
            DabCoverSource.SLIDESHOW -> {
                if (currentDabSlideshow != null) {
                    java.io.FileOutputStream(logoFile).use { out ->
                        currentDabSlideshow!!.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, out)
                    }
                    android.util.Log.i(TAG, "Saved slideshow as logo")
                    true
                } else false
            }
            DabCoverSource.DEEZER -> {
                val deezerPath = currentDabDeezerCoverPath
                if (!deezerPath.isNullOrBlank()) {
                    val sourceFile = java.io.File(deezerPath)
                    if (sourceFile.exists()) {
                        sourceFile.copyTo(logoFile, overwrite = true)
                        android.util.Log.i(TAG, "Saved Deezer cover as logo")
                        true
                    } else false
                } else false
            }
            else -> false
        }
    }

    /**
     * Aktualisiert das RadioLogoRepository mit dem neuen Logo.
     */
    private fun updateLogoInRepository(stationName: String, logoFile: java.io.File) {
        val templateName = "Custom-DAB"
        val activeTemplateName = radioLogoRepository.getActiveTemplateName()
        val targetTemplateName = activeTemplateName ?: templateName

        val existingTemplate = radioLogoRepository.getTemplates().find { it.name == targetTemplateName }
        val existingStations = existingTemplate?.stations?.toMutableList() ?: mutableListOf()

        existingStations.removeAll { it.ps.equals(stationName, ignoreCase = true) }

        val stationLogo = at.planqton.fytfm.data.logo.StationLogo(
            ps = stationName,
            logoUrl = "local://${logoFile.name}",
            localPath = logoFile.absolutePath
        )
        existingStations.add(stationLogo)

        val newTemplate = at.planqton.fytfm.data.logo.RadioLogoTemplate(
            name = targetTemplateName,
            area = existingTemplate?.area ?: 2,
            stations = existingStations
        )
        radioLogoRepository.saveTemplate(newTemplate)

        if (activeTemplateName == null) {
            radioLogoRepository.setActiveTemplate(targetTemplateName)
        }

        android.util.Log.i(TAG, "Added logo to template '$targetTemplateName': ${logoFile.absolutePath}")
    }

    /**
     * Aktualisiert die UI nach dem Speichern eines Logos.
     */
    private fun refreshUiAfterLogoSave() {
        loadStationsForCurrentMode()
        populateCarousel()
        refreshDabStationStrip()
        updateDabListModeSelection()
        availableCoverSources = getAvailableCoverSources()
        updateSlideshowIndicators(true)
        updateDabCoverDisplay()
    }

    /**
     * Speichert das aktuelle Cover als permanentes Sender-Logo.
     * Wird durch Swipe-Down auf dem Cover ausgelöst.
     */
    private fun saveCurrentCoverAsStationLogo(imageView: ImageView) {
        val stationName = currentDabServiceLabel ?: run {
            android.util.Log.w(TAG, "Kein currentDabServiceLabel!")
            return
        }
        if (currentDabServiceId <= 0) {
            android.util.Log.w(TAG, "ServiceID ungültig: $currentDabServiceId")
            return
        }

        animateCoverSaveGesture(imageView)

        Thread {
            try {
                val templateName = "Custom-DAB"
                val logosDir = java.io.File(filesDir, "logos/$templateName")
                if (!logosDir.exists()) logosDir.mkdirs()

                val hash = java.security.MessageDigest.getInstance("MD5")
                    .digest(stationName.toByteArray())
                    .joinToString("") { "%02x".format(it) }
                val logoFile = java.io.File(logosDir, "$hash.png")

                val currentSource = getSelectedCoverSource()
                if (currentSource == null || (currentSource != DabCoverSource.SLIDESHOW && currentSource != DabCoverSource.DEEZER)) {
                    runOnUiThread { toast("Bitte erst Slideshow oder Deezer auswählen") }
                    return@Thread
                }

                val saved = saveCoverSourceToFile(currentSource, logoFile)

                if (saved) {
                    updateLogoInRepository(stationName, logoFile)
                    runOnUiThread {
                        toast("✓ Logo für \"$stationName\" gespeichert")
                        refreshUiAfterLogoSave()
                    }
                } else {
                    runOnUiThread { toast("Kein Cover zum Speichern verfügbar") }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to save custom logo: ${e.message}")
                runOnUiThread { toast("Fehler beim Speichern des Logos") }
            }
        }.start()
    }

    /**
     * Sperrt/entsperrt die aktuelle Cover-Quelle.
     */
    private fun toggleCoverSourceLock() {
        if (coverSourceLocked) {
            // Entsperren
            coverSourceLocked = false
            lockedCoverSource = null
            // Speichern
            presetRepository.setCoverSourceLocked(false)
            presetRepository.setLockedCoverSource(null)
            // toast("🔓 Cover-Auswahl entsperrt")
        } else {
            // Sperren - aktuelle Auswahl merken
            if (selectedCoverSourceIndex >= 0 && selectedCoverSourceIndex < availableCoverSources.size) {
                lockedCoverSource = availableCoverSources[selectedCoverSourceIndex]
            } else {
                // Auto-Modus - beste verfügbare Quelle sperren
                lockedCoverSource = when {
                    availableCoverSources.contains(DabCoverSource.DEEZER) -> DabCoverSource.DEEZER
                    availableCoverSources.contains(DabCoverSource.SLIDESHOW) -> DabCoverSource.SLIDESHOW
                    availableCoverSources.contains(DabCoverSource.STATION_LOGO) -> DabCoverSource.STATION_LOGO
                    else -> DabCoverSource.DAB_LOGO
                }
            }
            coverSourceLocked = true
            // Speichern
            presetRepository.setCoverSourceLocked(true)
            presetRepository.setLockedCoverSource(lockedCoverSource?.name)
            val sourceName = when (lockedCoverSource) {
                DabCoverSource.DAB_LOGO -> "DAB+ Logo"
                DabCoverSource.STATION_LOGO -> "Sender Logo"
                DabCoverSource.SLIDESHOW -> "Slideshow"
                DabCoverSource.DEEZER -> "Deezer Cover"
                null -> "?"
            }
            // toast("🔒 $sourceName gesperrt")
        }
        // Indikatoren aktualisieren (Lock-Symbol anzeigen/verstecken)
        updateSlideshowIndicators(true)
    }

    private fun loadCoverImage(coverPath: String?) {
        val coverImageView = binding.debugDeezerCoverImage

        if (coverPath != null && coverPath.startsWith("/")) {
            // Local file path only - keine HTTP URLs
            val file = java.io.File(coverPath)
            if (file.exists()) {
                val bitmap = android.graphics.BitmapFactory.decodeFile(coverPath)
                if (bitmap != null) {
                    coverImageView.setImageBitmap(bitmap)
                    android.util.Log.d(TAG, "Debug cover loaded: $coverPath (${bitmap.width}x${bitmap.height})")
                } else {
                    android.util.Log.w(TAG, "Debug cover decode failed: $coverPath")
                    coverImageView.setImageResource(android.R.drawable.ic_menu_gallery)
                }
            } else {
                android.util.Log.w(TAG, "Debug cover file not found: $coverPath")
                coverImageView.setImageResource(android.R.drawable.ic_menu_gallery)
            }
        } else {
            if (coverPath != null) {
                android.util.Log.d(TAG, "Debug cover is URL (waiting for download): $coverPath")
            }
            coverImageView.setImageResource(android.R.drawable.ic_menu_gallery)
        }
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
                binding.nowPlayingPs.text = ps
                binding.nowPlayingPs.visibility = View.VISIBLE

                // Auto-name station if name is null
                autoNameStationIfNeeded(ps)
            } else {
                binding.nowPlayingPs.visibility = View.GONE
            }

            // Update text - bei leerem Artist nur Titel zeigen
            if (trackInfo.artist.isBlank()) {
                binding.nowPlayingArtist.visibility = View.GONE
                binding.nowPlayingTitle.text = trackInfo.title
            } else {
                binding.nowPlayingArtist.visibility = View.VISIBLE
                binding.nowPlayingArtist.text = trackInfo.artist
                binding.nowPlayingTitle.text = trackInfo.title
            }

            // Load cover image with Coil - NUR lokaler Cache, nie HTTP
            val currentFreq = binding.frequencyScale.getFrequency()
            val isAM = isAmMode
            val placeholderDrawable = if (isAM) R.drawable.placeholder_am else R.drawable.placeholder_fm
            // Nur lokale Pfade verwenden (starten mit "/")
            val localCover = trackInfo.coverUrl?.takeIf { it.startsWith("/") }
            val deezerEnabled = presetRepository.isDeezerEnabledForFrequency(currentFreq)

            // Try station logo first (always available as fallback)
            val stationLogo = radioLogoRepository.getLogoForStation(
                ps = rdsManager.ps,
                pi = rdsManager.pi,
                frequency = currentFreq
            )

            if (deezerEnabled && !localCover.isNullOrBlank()) {
                // Lokaler Deezer Cache verfügbar
                currentUiCoverSource = localCover
                binding.nowPlayingCover.load(java.io.File(localCover)) {
                    crossfade(true)
                    placeholder(placeholderDrawable)
                    error(placeholderDrawable)
                }
            } else if (stationLogo != null) {
                // Station logo available
                currentUiCoverSource = stationLogo
                binding.nowPlayingCover.load(java.io.File(stationLogo)) {
                    crossfade(true)
                    placeholder(placeholderDrawable)
                    error(placeholderDrawable)
                }
            } else {
                // Fallback: Radio icon
                currentUiCoverSource = if (isAM) "drawable:placeholder_am" else "drawable:placeholder_fm"
                binding.nowPlayingCover.setImageResource(placeholderDrawable)
            }

            // Update carousel if in carousel mode
            if (isCarouselMode) {
                // Update carousel card cover - clear cover when Spotify is disabled for this station
                if (!presetRepository.isDeezerEnabledForFrequency(currentFreq)) {
                    stationCarouselAdapter?.updateCurrentCover(null, null)
                } else {
                    // Nur lokale Pfade verwenden - keine HTTP URLs
                    val localCover = trackInfo.coverUrl?.takeIf { it.startsWith("/") }
                    stationCarouselAdapter?.updateCurrentCover(
                        coverUrl = null,                 // Keine HTTP URLs
                        localCoverPath = localCover      // Nur lokaler Cache
                    )
                }

                // Update carousel now playing bar
                updateCarouselNowPlayingBar(trackInfo)
            }

            // Show with animation
            if (binding.nowPlayingBar.visibility != View.VISIBLE) {
                showNowPlayingBar(binding.nowPlayingBar)
            }
        }
    }

    // ========== DAB Callback Handlers ==========

    /**
     * Handler für DAB Service Started Callback.
     * Gemeinsam genutzt von toggleDabPower() und toggleMockDabPower().
     */
    /**
     * Handler für DAB Service Started - Vollversion mit Deezer-Cleanup und MediaSession.
     */
    private fun handleDabServiceStartedFull(dabStation: at.planqton.fytfm.dab.DabStation) {
        android.util.Log.i(TAG, "DAB Service gestartet: ${dabStation.serviceLabel}")

        playTickSound()

        // Cancel pending Deezer search from previous station
        dabDeezerSearchJob?.cancel()
        dabDeezerSearchJob = null

        currentDabServiceId = dabStation.serviceId
        currentDabEnsembleId = dabStation.ensembleId
        currentDabServiceLabel = dabStation.serviceLabel
        currentDabEnsembleLabel = dabStation.ensembleLabel
        currentDabDls = null
        lastDlsTimestamp = 0L
        lastDeezerSearchedDls = null
        lastParsedDls = null
        currentDabDeezerCoverPath = null
        currentDabDeezerCoverDls = null
        currentDabSlideshow = null

        runOnUiThread {
            updateDeezerDebugInfo("Waiting...", null, null, null, null)
            availableCoverSources = getAvailableCoverSources()
            updateSlideshowIndicators(true)

            // Cover basierend auf Lock-Status setzen
            val isLockedToDabLogo = coverSourceLocked && lockedCoverSource == DabCoverSource.DAB_LOGO
            val stationLogo = getLogoForDabStation(dabStation.serviceLabel, dabStation.serviceId)

            if (isLockedToDabLogo) {
                currentUiCoverSource = "drawable:ic_fytfm_dab_plus_light"
                dabListCover?.setImageResource(R.drawable.ic_fytfm_dab_plus_light)
                binding.nowPlayingCover.setImageResource(R.drawable.ic_cover_placeholder)
                binding.carouselNowPlayingCover.setImageResource(R.drawable.ic_cover_placeholder)
            } else if (stationLogo != null && (!coverSourceLocked || lockedCoverSource == DabCoverSource.STATION_LOGO)) {
                currentUiCoverSource = stationLogo
                dabListCover?.load(java.io.File(stationLogo)) { crossfade(true) }
                binding.nowPlayingCover.load(java.io.File(stationLogo)) { crossfade(true) }
                binding.carouselNowPlayingCover.load(java.io.File(stationLogo)) { crossfade(true) }
            } else {
                currentUiCoverSource = "drawable:ic_fytfm_dab_plus_light"
                dabListCover?.setImageResource(R.drawable.ic_fytfm_dab_plus_light)
                binding.nowPlayingCover.setImageResource(R.drawable.ic_cover_placeholder)
                binding.carouselNowPlayingCover.setImageResource(R.drawable.ic_cover_placeholder)
            }
        }

        saveLastDabService(dabStation.serviceId, dabStation.ensembleId)
        updateCarouselSelection()
        updateDabListModeSelection()
        stationAdapter.setSelectedDabService(dabStation.serviceId)
        updateFavoriteButton()
        updateDabNowPlaying(dabStation)
        debugManager.updateDabDebugInfo(dabStation)

        // MediaSession für DAB aktualisieren
        val radioLogoPath = getLogoForDabStation(dabStation.serviceLabel, dabStation.serviceId)
        FytFMMediaService.instance?.updateDabMetadata(
            serviceLabel = dabStation.serviceLabel,
            ensembleLabel = dabStation.ensembleLabel,
            dls = null,
            slideshowBitmap = null,
            radioLogoPath = radioLogoPath
        )
    }

    /**
     * Handler für DAB Service Started - Basis-Variante für Mock-Tuner.
     */
    private fun handleDabServiceStartedBase(dabStation: at.planqton.fytfm.dab.DabStation) {
        playTickSound()

        currentDabServiceId = dabStation.serviceId
        currentDabEnsembleId = dabStation.ensembleId
        currentDabServiceLabel = dabStation.serviceLabel
        currentDabEnsembleLabel = dabStation.ensembleLabel
        currentDabDls = null
        lastDlsTimestamp = 0L
        currentDabSlideshow = null

        runOnUiThread {
            currentUiCoverSource = "drawable:ic_fytfm_dab_plus_light"
            dabListCover?.setImageResource(R.drawable.ic_fytfm_dab_plus_light)
            binding.nowPlayingCover.setImageResource(R.drawable.ic_cover_placeholder)
            binding.carouselNowPlayingCover.setImageResource(R.drawable.ic_cover_placeholder)
            availableCoverSources = getAvailableCoverSources()
            updateSlideshowIndicators(true)
        }

        updateCarouselSelection()
        updateDabListModeSelection()
        stationAdapter.setSelectedDabService(dabStation.serviceId)
        updateFavoriteButton()
        updateDabNowPlaying(dabStation)
        debugManager.updateDabDebugInfo(dabStation)
    }

    /**
     * Setzt DAB-Callbacks auf RadioController.
     * Wird einmal bei Initialisierung aufgerufen, nicht bei jedem Power-Toggle.
     */
    private fun setupRadioControllerDabCallbacks() {
        radioController.onDabTunerReady = {
            android.util.Log.i(TAG, "DAB Tuner ready via controller!")
            // UI aktualisieren
            val dabStations = presetRepository.loadDabStations()
            if (dabStations.isNotEmpty()) {
                populateCarousel()
                updateCarouselSelection()
            }
            isDabOn = true
            updatePowerButton()
        }

        radioController.onDabServiceStarted = { dabStation ->
            handleDabServiceStartedFull(dabStation)
        }

        radioController.onDabDynamicLabel = { dls ->
            android.util.Log.d(TAG, "DLS received via controller: $dls")
            handleDabDynamicLabelBase(dls)
            android.util.Log.d(TAG, "DAB DLS: '$dls' (Station: $currentDabServiceLabel)")
            handleDabDlsLogging(dls)
            handleDabDlsParserLog(dls)
            processDabDeezerSearch(dls)
        }

        radioController.onDabDlPlus = { artist, title ->
            android.util.Log.d(TAG, "DL+ received via controller: artist=$artist, title=$title")
            handleDabDlPlus(artist, title)
        }

        radioController.onDabSlideshow = { bitmap ->
            android.util.Log.d(TAG, "DAB Slideshow via controller: ${bitmap.width}x${bitmap.height}")
            handleDabSlideshowBase(bitmap, "slideshow")
        }

        radioController.onDabServiceStopped = {
            android.util.Log.i(TAG, "DAB Service stopped via controller")
            stopDabVisualizer()
        }

        radioController.onDabAudioStarted = { audioSessionId ->
            android.util.Log.i(TAG, "DAB Audio started via controller: session=$audioSessionId")
            if (presetRepository.isDabVisualizerEnabled() && audioSessionId > 0) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M &&
                    checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    pendingVisualizerSessionId = audioSessionId
                    requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO_PERMISSION)
                } else {
                    dabVisualizerView?.visibility = View.VISIBLE
                    dabVisualizerView?.setAudioSessionId(audioSessionId)
                }
            }
        }

        radioController.onDabReceptionStats = { sync, quality, snr ->
            debugManager.updateDabReceptionStats(sync, quality, snr)
        }

        radioController.onDabRecordingStarted = {
            android.util.Log.i(TAG, "DAB Recording started via controller")
            runOnUiThread {
                updateRecordingButton(true)
                toast(R.string.recording_started)
            }
        }

        radioController.onDabRecordingStopped = { file ->
            android.util.Log.i(TAG, "DAB Recording stopped via controller: ${file?.absolutePath}")
            runOnUiThread {
                updateRecordingButton(false)
                file?.let {
                    toast(getString(R.string.recording_saved, it.name), long = true)
                }
            }
        }

        radioController.onDabRecordingError = { error ->
            android.util.Log.e(TAG, "DAB Recording error via controller: $error")
            runOnUiThread {
                updateRecordingButton(false)
                toast(error, long = true)
            }
        }

        radioController.onDabEpgReceived = { epgData ->
            @Suppress("UNCHECKED_CAST")
            (epgData as? at.planqton.fytfm.dab.EpgData)?.let { updateEpgDialog(it) }
        }

        radioController.onError = { error ->
            android.util.Log.e(TAG, "Controller error: $error")
            runOnUiThread {
                toast(error, long = true)
            }
        }
    }

    /**
     * Setzt Mock-DAB-Callbacks auf mockDabTunerManager.
     * Wird einmal bei Initialisierung aufgerufen, nicht bei jedem Power-Toggle.
     */
    private fun setupMockDabCallbacks() {
        mockDabTunerManager.onTunerReady = {
            android.util.Log.i(TAG, "Mock DAB Tuner ready!")
            val mockStations = mockDabTunerManager.getServices()
            if (mockStations.isNotEmpty()) {
                val radioStations = mockStations.map { dabStation ->
                    at.planqton.fytfm.data.RadioStation(
                        frequency = dabStation.ensembleFrequencyKHz.toFloat(),
                        name = dabStation.serviceLabel,
                        rssi = 0,
                        isAM = false,
                        isDab = true,
                        isFavorite = false,
                        serviceId = dabStation.serviceId,
                        ensembleId = dabStation.ensembleId,
                        ensembleLabel = dabStation.ensembleLabel
                    )
                }
                presetRepository.saveDabStations(radioStations)
                populateCarousel()

                val targetStation = mockStations.first()
                android.util.Log.i(TAG, "Mock DAB Tuner ready: tuning to ${targetStation.serviceLabel}")
                currentDabServiceId = targetStation.serviceId
                currentDabEnsembleId = targetStation.ensembleId
                mockDabTunerManager.tuneService(targetStation.serviceId, targetStation.ensembleId)
                updateCarouselSelection()
            }
            isDabOn = true
            updatePowerButton()
        }

        mockDabTunerManager.onServiceStarted = { dabStation ->
            android.util.Log.i(TAG, "Mock DAB Service gestartet: ${dabStation.serviceLabel}")
            handleDabServiceStartedBase(dabStation)
        }

        mockDabTunerManager.onServiceStopped = {
            android.util.Log.i(TAG, "Mock DAB Service gestoppt")
        }

        mockDabTunerManager.onTunerError = { error ->
            android.util.Log.e(TAG, "Mock DAB Tuner Error: $error")
            isDabOn = false
            updatePowerButton()
            toast(error, long = true)
        }

        mockDabTunerManager.onDynamicLabel = { dls ->
            android.util.Log.d(TAG, "Mock DLS received: $dls")
            handleDabDynamicLabelBase(dls)
        }

        mockDabTunerManager.onDlPlus = { artist, title ->
            android.util.Log.d(TAG, "Mock DL+ received: artist=$artist, title=$title")
            handleDabDlPlus(artist, title)
        }

        mockDabTunerManager.onSlideshow = { bitmap ->
            android.util.Log.d(TAG, "Mock DAB Slideshow received: ${bitmap.width}x${bitmap.height}")
            handleDabSlideshowBase(bitmap, "mock_slideshow")
        }

        mockDabTunerManager.onReceptionStats = { sync, quality, snr ->
            debugManager.updateDabReceptionStats(sync, quality, snr)
        }
    }

    /**
     * Handler für DAB DLS (Dynamic Label) Callback - Basis-Variante.
     * Aktualisiert UI ohne Deezer-Integration.
     */
    private fun handleDabDynamicLabelBase(dls: String) {
        if (dls != currentDabDls) {
            lastDlsTimestamp = System.currentTimeMillis()
        }
        currentDabDls = dls
        binding.nowPlayingTitle.text = dls
        binding.nowPlayingTitle.visibility = if (dls.isNotBlank()) View.VISIBLE else View.GONE
        binding.carouselNowPlayingTitle.text = dls
        binding.carouselNowPlayingTitle.visibility = if (dls.isNotBlank()) View.VISIBLE else View.GONE
        updateDabListRadiotext(dls)
        debugManager.updateDabDebugInfo(dls = dls)
    }

    /**
     * Handler für DAB DL+ (Dynamic Label Plus) Callback.
     * Gemeinsam genutzt von toggleDabPower() und toggleMockDabPower().
     */
    private fun handleDabDlPlus(artist: String?, title: String?) {
        if (artist != null || title != null) {
            binding.nowPlayingArtist.text = artist ?: ""
            binding.nowPlayingArtist.visibility = if (artist != null) View.VISIBLE else View.GONE
            binding.nowPlayingTitle.text = title ?: ""
            binding.nowPlayingTitle.visibility = if (title != null) View.VISIBLE else View.GONE
            binding.carouselNowPlayingArtist.text = artist ?: ""
            binding.carouselNowPlayingArtist.visibility = if (artist != null) View.VISIBLE else View.GONE
            binding.carouselNowPlayingTitle.text = title ?: ""
            binding.carouselNowPlayingTitle.visibility = if (title != null) View.VISIBLE else View.GONE
        }
    }

    /**
     * Handler für DAB Slideshow Callback.
     * Gemeinsam genutzt von toggleDabPower() und toggleMockDabPower().
     */
    private fun handleDabSlideshowBase(bitmap: android.graphics.Bitmap, sourceLabel: String = "slideshow") {
        currentDabSlideshow = bitmap

        availableCoverSources = getAvailableCoverSources()
        updateSlideshowIndicators(true)

        val isLockedToOtherSource = coverSourceLocked &&
            lockedCoverSource != null &&
            lockedCoverSource != DabCoverSource.SLIDESHOW
        val hasDeezerCover = !currentDabDeezerCoverPath.isNullOrBlank()
        val slideshowSelected = selectedCoverSourceIndex >= 0 &&
            selectedCoverSourceIndex < availableCoverSources.size &&
            availableCoverSources[selectedCoverSourceIndex] == DabCoverSource.SLIDESHOW
        val showSlideshow = !isLockedToOtherSource &&
            (slideshowSelected || (selectedCoverSourceIndex < 0 && !hasDeezerCover))

        if (showSlideshow) {
            currentUiCoverSource = sourceLabel
            binding.nowPlayingCover.setImageBitmap(bitmap)
            binding.carouselNowPlayingCover.setImageBitmap(bitmap)
            if (dabListContentArea?.visibility == View.VISIBLE) {
                dabListCover?.setImageBitmap(bitmap)
            }
            // MediaSession mit Slideshow aktualisieren
            val radioLogoPath = getLogoForDabStation(currentDabServiceLabel, currentDabServiceId)
            FytFMMediaService.instance?.updateDabMetadata(
                serviceLabel = currentDabServiceLabel,
                ensembleLabel = currentDabEnsembleLabel,
                dls = currentDabDls,
                slideshowBitmap = bitmap,
                radioLogoPath = radioLogoPath
            )
        }
    }

    /**
     * Handler für DAB DLS Logging.
     * Speichert DLS-Einträge in die Log-Liste und Datei.
     */
    private fun handleDabDlsLogging(dls: String) {
        if (dls.isNotBlank() && dls != lastLoggedDls) {
            lastLoggedDls = dls
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            val station = currentDabServiceLabel ?: "?"
            dlsLogEntries.add("[$timestamp] $station: $dls")
            saveDlsLogToFile()
        }
    }

    /**
     * Handler für DAB DLS Parser Logging.
     * Parst DLS und loggt das Ergebnis.
     */
    private fun handleDabDlsParserLog(dls: String) {
        if (dls.isNotBlank() && dls != lastParsedDls) {
            lastParsedDls = dls
            val station = currentDabServiceLabel ?: "?"
            CoroutineScope(Dispatchers.IO).launch {
                val parseResult = at.planqton.fytfm.deezer.DlsParser.parse(dls, currentDabServiceLabel)
                at.planqton.fytfm.deezer.ParserLogger.logDab(station, dls, parseResult.artist, parseResult.title)
            }
        }
    }

    /**
     * Verarbeitet Deezer-Suche für DAB DLS.
     * Sucht nach Track-Info und aktualisiert Cover/MediaSession.
     */
    private fun processDabDeezerSearch(dls: String) {
        val dlsChanged = dls != lastDeezerSearchedDls
        if (dlsChanged && dls.isNotBlank()) {
            lastDeezerSearchedDls = dls
        }

        val combiner = rtCombiner
        val deezerEnabledDab = presetRepository.isDeezerEnabledDab()
        android.util.Log.d(TAG, "Deezer check: enabled=$deezerEnabledDab, changed=$dlsChanged, combiner=${combiner != null}, blocked=$debugDeezerBlocked")

        if (dlsChanged && combiner != null && dls.isNotBlank() && !debugDeezerBlocked && deezerEnabledDab) {
            dabDeezerSearchJob?.cancel()
            dabDeezerSearchJob = CoroutineScope(Dispatchers.IO).launch {
                val parseResult = at.planqton.fytfm.deezer.DlsParser.parse(dls, currentDabServiceLabel)
                val searchText = parseResult.toSearchString() ?: dls
                android.util.Log.d(TAG, "Deezer search: '$searchText'")

                val dabFreq = radioController.getCurrentDabService()?.ensembleFrequencyKHz?.toFloat() ?: 0f
                android.util.Log.d(TAG, "Calling processRt with: '$searchText', freq=$dabFreq")
                val combinedDls = try {
                    combiner.processRt(0, searchText, dabFreq, rawOriginal = dls, skipBuffer = true)
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "processRt failed: ${e.message}", e)
                    null
                }
                android.util.Log.d(TAG, "processRt returned: $combinedDls")
                val trackInfo = if (combinedDls != null) combiner.getLastTrackInfo(0) else null

                withContext(Dispatchers.Main) {
                    handleDabDeezerResult(dls, trackInfo)
                }
            }
        } else {
            // DLS hat sich nicht geändert oder Deezer deaktiviert
            val radioLogoPath = getLogoForDabStation(currentDabServiceLabel, currentDabServiceId)
            val useDeezerCover = deezerEnabledDab &&
                                 currentDabDeezerCoverPath != null &&
                                 currentDabDeezerCoverDls == dls
            FytFMMediaService.instance?.updateDabMetadata(
                serviceLabel = currentDabServiceLabel,
                ensembleLabel = currentDabEnsembleLabel,
                dls = dls,
                slideshowBitmap = if (useDeezerCover) null else currentDabSlideshow,
                radioLogoPath = radioLogoPath,
                deezerCoverPath = if (useDeezerCover) currentDabDeezerCoverPath else null
            )
        }
    }

    /**
     * Verarbeitet das Deezer-Suchergebnis für DAB.
     * Aktualisiert UI und MediaSession mit Track-Info.
     */
    private fun handleDabDeezerResult(dls: String, trackInfo: at.planqton.fytfm.deezer.TrackInfo?) {
        val radioLogoPath = getLogoForDabStation(currentDabServiceLabel, currentDabServiceId)

        if (trackInfo != null) {
            android.util.Log.d(TAG, "DAB Deezer found: ${trackInfo.artist} - ${trackInfo.title}")

            binding.nowPlayingArtist.text = trackInfo.artist ?: ""
            binding.nowPlayingArtist.visibility = if (!trackInfo.artist.isNullOrBlank()) View.VISIBLE else View.GONE
            binding.nowPlayingTitle.text = trackInfo.title ?: dls
            binding.nowPlayingTitle.visibility = View.VISIBLE
            binding.carouselNowPlayingArtist.text = trackInfo.artist ?: ""
            binding.carouselNowPlayingArtist.visibility = if (!trackInfo.artist.isNullOrBlank()) View.VISIBLE else View.GONE
            binding.carouselNowPlayingTitle.text = trackInfo.title ?: dls
            binding.carouselNowPlayingTitle.visibility = View.VISIBLE

            val localCover = deezerCache?.getLocalCoverPath(trackInfo.trackId)
                ?: trackInfo.coverUrl?.takeIf { it.startsWith("/") }

            currentDabDeezerCoverPath = localCover
            currentDabDeezerCoverDls = dls
            selectedCoverSourceIndex = -1
            updateDabCoverDisplay()

            val displayDls = "${trackInfo.artist} - ${trackInfo.title}"
            FytFMMediaService.instance?.updateDabMetadata(
                serviceLabel = currentDabServiceLabel,
                ensembleLabel = currentDabEnsembleLabel,
                dls = displayDls,
                slideshowBitmap = if (!localCover.isNullOrBlank()) null else currentDabSlideshow,
                radioLogoPath = radioLogoPath,
                deezerCoverPath = localCover
            )
        } else {
            currentDabDeezerCoverPath = null
            currentDabDeezerCoverDls = null
            selectedCoverSourceIndex = -1
            updateDabCoverDisplay()

            FytFMMediaService.instance?.updateDabMetadata(
                serviceLabel = currentDabServiceLabel,
                ensembleLabel = currentDabEnsembleLabel,
                dls = dls,
                slideshowBitmap = currentDabSlideshow,
                radioLogoPath = radioLogoPath
            )
        }
    }

    /**
     * Aktualisiert die Now Playing Bar für DAB-Sender
     */
    private fun updateDabNowPlaying(dabStation: at.planqton.fytfm.dab.DabStation) {
        // Hauptanzeige aktualisieren
        binding.tvFrequency.text = dabStation.serviceLabel ?: "DAB+"

        // Now Playing Bar aktualisieren
        binding.nowPlayingPs.text = dabStation.serviceLabel ?: "DAB+"
        binding.nowPlayingPs.visibility = View.VISIBLE
        binding.nowPlayingArtist.visibility = View.GONE
        binding.nowPlayingTitle.text = dabStation.ensembleLabel ?: ""
        binding.nowPlayingTitle.visibility = if (dabStation.ensembleLabel.isNullOrBlank()) View.GONE else View.VISIBLE

        // Cover: Lock-Status beachten
        val isLockedToDabLogo = coverSourceLocked && lockedCoverSource == DabCoverSource.DAB_LOGO
        val stationLogo = getLogoForDabStation(dabStation.serviceLabel, dabStation.serviceId)

        if (isLockedToDabLogo) {
            // Gelockt auf DAB+ Logo - Placeholder verwenden
            binding.nowPlayingCover.setImageResource(R.drawable.ic_cover_placeholder)
        } else if (stationLogo != null && (!coverSourceLocked || lockedCoverSource == DabCoverSource.STATION_LOGO)) {
            binding.nowPlayingCover.load(java.io.File(stationLogo)) {
                crossfade(true)
                placeholder(R.drawable.placeholder_fm)
                error(R.drawable.placeholder_fm)
            }
        } else {
            binding.nowPlayingCover.setImageResource(R.drawable.placeholder_fm)
        }

        // Now Playing Bar anzeigen
        binding.nowPlayingBar.let { bar ->
            if (bar.visibility != View.VISIBLE) {
                showNowPlayingBar(bar)
            }
        }

        // Carousel Now Playing Bar auch aktualisieren
        binding.carouselNowPlayingPs.text = dabStation.serviceLabel ?: "DAB+"
        binding.carouselNowPlayingPs.visibility = View.VISIBLE
        binding.carouselNowPlayingArtist.visibility = View.GONE
        binding.carouselNowPlayingTitle.text = dabStation.ensembleLabel ?: ""
        binding.carouselNowPlayingTitle.visibility = if (dabStation.ensembleLabel.isNullOrBlank()) View.GONE else View.VISIBLE

        if (isLockedToDabLogo) {
            binding.carouselNowPlayingCover.setImageResource(R.drawable.ic_cover_placeholder)
        } else if (stationLogo != null && (!coverSourceLocked || lockedCoverSource == DabCoverSource.STATION_LOGO)) {
            binding.carouselNowPlayingCover.load(java.io.File(stationLogo)) {
                crossfade(true)
                placeholder(R.drawable.placeholder_fm)
                error(R.drawable.placeholder_fm)
            }
        } else {
            binding.carouselNowPlayingCover.setImageResource(R.drawable.placeholder_fm)
        }

        binding.carouselNowPlayingBar.let { bar ->
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
        binding.debugHeader.text = "DAB Debug"

        if (currentDabServiceId != -1) {
            // Versuche den gespeicherten Sender zu finden
            val savedStations = dabStationsForCurrentMode
            val station = savedStations.find { it.serviceId == currentDabServiceId }
            if (station != null) {
                // Zeige Sendername statt Frequenz
                binding.tvFrequency.text = station.name ?: "DAB+"

                // Now Playing Bar mit Senderinfo aktualisieren
                binding.nowPlayingPs.text = station.name ?: "DAB+"
                binding.nowPlayingPs.visibility = View.VISIBLE
                binding.nowPlayingArtist.visibility = View.GONE
                binding.nowPlayingTitle.text = ""  // DLS wird später per Callback kommen
                binding.nowPlayingTitle.visibility = View.GONE

                // Cover: Versuche Custom Logo oder Station-Logo zu laden
                val stationLogo = getLogoForDabStation(station.name, station.serviceId)
                if (stationLogo != null) {
                    binding.nowPlayingCover.load(java.io.File(stationLogo)) {
                        crossfade(true)
                        placeholder(R.drawable.placeholder_fm)
                        error(R.drawable.placeholder_fm)
                    }
                } else {
                    binding.nowPlayingCover.setImageResource(R.drawable.placeholder_fm)
                }

                // Carousel Now Playing Bar auch aktualisieren
                binding.carouselNowPlayingPs.text = station.name ?: "DAB+"
                binding.carouselNowPlayingPs.visibility = View.VISIBLE
                binding.carouselNowPlayingArtist.visibility = View.GONE
                binding.carouselNowPlayingTitle.visibility = View.GONE

                if (stationLogo != null) {
                    binding.carouselNowPlayingCover.load(java.io.File(stationLogo)) {
                        crossfade(true)
                        placeholder(R.drawable.placeholder_fm)
                        error(R.drawable.placeholder_fm)
                    }
                } else {
                    binding.carouselNowPlayingCover.setImageResource(R.drawable.placeholder_fm)
                }
            } else {
                // Kein gespeicherter Sender gefunden
                binding.tvFrequency.text = "DAB+"
                binding.nowPlayingPs.text = "DAB+"
                binding.nowPlayingPs.visibility = View.VISIBLE
            }
        } else {
            // Kein Sender ausgewählt
            binding.tvFrequency.text = "DAB+"
        }
    }

    // ==================== DAB LIST MODE ====================

    /**
     * Setup DAB List Mode views and adapters
     */
    private fun setupDabListMode() {
        dabListCover = binding.dabListCover
        dabListCoverDots = binding.dabListCoverDots
        dabListDeezerWatermark = binding.dabListDeezerWatermark
        // Cover tap to cycle through available sources (DAB only)
        dabListCover?.setOnClickListener { toggleDabCover() }
        setupCoverLongPressListener(dabListCover)
        dabListStationName = binding.dabListStationName
        dabListEnsemble = binding.dabListEnsemble
        dabListRadiotext = binding.dabListRadiotext
        dabListFavoriteBtn = binding.dabListFavoriteBtn
        dabListFilterBtn = binding.dabListFilterBtn
        dabListSearchBtn = binding.dabListSearchBtn
        dabListSettingsBtn = binding.dabListSettingsBtn
        dabListRecordBtn = binding.dabListRecordBtn
        dabListEpgBtn = binding.dabListEpgBtn
        dabListStationStrip = binding.dabListStationStrip
        dabListMainArea = binding.dabListMainArea
        dabVisualizerView = binding.dabVisualizerView

        // Apply visualizer settings
        updateDabVisualizerSettings()

        // Tap on visualizer to cycle styles
        dabVisualizerView?.setOnClickListener {
            val currentStyle = presetRepository.getDabVisualizerStyle()
            val totalStyles = 12  // Number of available styles
            val newStyle = (currentStyle + 1) % totalStyles
            presetRepository.setDabVisualizerStyle(newStyle)
            dabVisualizerView?.setStyle(newStyle)
        }

        // Setup horizontal station strip
        dabStripAdapter = at.planqton.fytfm.ui.DabStripAdapter(
            onStationClick = { station ->
                // Station clicked - tune to it
                tuneToDabStation(station.serviceId, station.ensembleId)
            },
            getLogoPath = { name, serviceId -> getLogoForDabStation(name, serviceId) }
        )

        dabListStationStrip?.apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
                this@MainActivity,
                androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL,
                false
            )
            adapter = dabStripAdapter
        }

        // Favorite button
        dabListFavoriteBtn?.setOnClickListener {
            if (currentDabServiceId != -1) {
                val isFav = presetRepository.toggleDabFavorite(currentDabServiceId)
                updateDabListFavoriteIcon(isFav)
                // Reload both station adapters to show updated favorite
                refreshDabStationStrip()
                loadStationsForCurrentMode()
            }
        }

        // Favorites filter toggle button
        dabListFilterBtn?.setOnClickListener {
            toggleFavoritesFilter()
            updateDabListFilterIcon()
        }

        // Search/Scan button - opens station scan dialog
        dabListSearchBtn?.setOnClickListener {
            showStationScanDialog()
        }

        // Settings button
        dabListSettingsBtn?.setOnClickListener {
            showSettingsDialogFragment()
        }

        // Record button - nur sichtbar wenn Speicherort konfiguriert
        dabListRecordBtn?.setOnClickListener {
            toggleDabRecording()
        }
        updateRecordButtonVisibility()

        // EPG button - öffnet die Programmvorschau
        dabListEpgBtn?.setOnClickListener {
            showEpgDialog()
        }
        updateEpgButtonVisibility()

        // Mode spinner - dynamisch mit DAB Dev wenn aktiviert
        val dabListModeSpinner = binding.dabListModeSpinner
        dabListModeSpinner?.let { spinner ->
            val dabDevEnabled = presetRepository.isDabDevModeEnabled()
            val modes = mutableListOf("FM", "AM", "DAB+")
            if (dabDevEnabled) {
                modes.add("DAB Dev")
            }
            val adapter = android.widget.ArrayAdapter(this, R.layout.item_radio_mode_spinner, modes)
            adapter.setDropDownViewResource(R.layout.item_radio_mode_dropdown)
            spinner.adapter = adapter

            // Set correct selection based on current mode
            val currentModeForSelection = binding.frequencyScale.getMode()
            val currentSelection = when (currentModeForSelection) {
                FrequencyScaleView.RadioMode.DAB -> 2
                FrequencyScaleView.RadioMode.DAB_DEV -> if (dabDevEnabled) 3 else 2
                else -> 2
            }
            spinner.setSelection(currentSelection)

            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                private var isInitialSelection = true

                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (isInitialSelection) {
                        isInitialSelection = false
                        return
                    }

                    val newMode = when (position) {
                        0 -> FrequencyScaleView.RadioMode.FM
                        1 -> FrequencyScaleView.RadioMode.AM
                        2 -> FrequencyScaleView.RadioMode.DAB
                        3 -> FrequencyScaleView.RadioMode.DAB_DEV
                        else -> FrequencyScaleView.RadioMode.FM
                    }

                    // Allow switching between DAB and DAB_DEV, or to FM/AM
                    val currentModeNow = binding.frequencyScale.getMode()
                    if (newMode != currentModeNow) {
                        setRadioMode(newMode)
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

        // Setup swipe gestures on main area
        setupDabListSwipeGestures()
    }

    /**
     * Setup swipe gestures for DAB List Mode main area with visual feedback
     */
    private var dabSwipeFlingDetected = false

    private fun setupDabListSwipeGestures() {
        var startX = 0f
        var isSwiping = false
        val maxTranslation = 150f // Max pixels to translate during drag
        val swipeThreshold = 100
        val swipeVelocityThreshold = 100

        val gestureDetector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y

                if (Math.abs(diffX) > Math.abs(diffY) &&
                    Math.abs(diffX) > swipeThreshold &&
                    Math.abs(velocityX) > swipeVelocityThreshold) {

                    dabSwipeFlingDetected = true
                    val direction = if (diffX > 0) -1 else 1
                    // Animate out, change station, animate in
                    animateDabListStationChange(direction)
                    return true
                }
                return false
            }

            override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
                return true
            }
        })

        dabListMainArea?.setOnTouchListener { view, event ->
            gestureDetector.onTouchEvent(event)

            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    isSwiping = true
                    dabSwipeFlingDetected = false
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (isSwiping && !dabSwipeFlingDetected) {
                        val deltaX = event.x - startX
                        // Apply damping factor for smoother feel
                        val translation = (deltaX * 0.4f).coerceIn(-maxTranslation, maxTranslation)
                        view.translationX = translation
                        // Slight alpha change for feedback
                        view.alpha = 1f - (Math.abs(translation) / maxTranslation) * 0.15f
                    }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    if (isSwiping && !dabSwipeFlingDetected) {
                        // Animate back to original position (no fling detected)
                        view.animate()
                            .translationX(0f)
                            .alpha(1f)
                            .setDuration(200)
                            .setInterpolator(android.view.animation.DecelerateInterpolator())
                            .start()
                    }
                    isSwiping = false
                }
            }
            true
        }
    }

    /**
     * Animate station change with slide effect
     */
    private fun animateDabListStationChange(direction: Int) {
        val view = dabListMainArea ?: return
        val slideDistance = view.width.toFloat() * 0.3f

        // Animate out
        view.animate()
            .translationX(if (direction > 0) -slideDistance else slideDistance)
            .alpha(0.3f)
            .setDuration(150)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                // Change station
                navigateDabListStation(direction)

                // Reset position to opposite side
                view.translationX = if (direction > 0) slideDistance else -slideDistance

                // Animate in
                view.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(200)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
            .start()
    }

    /**
     * Navigate to next/previous DAB station in list mode
     */
    private fun navigateDabListStation(direction: Int) {
        val stations = dabStationsForCurrentMode
        if (stations.isEmpty()) return

        val currentIndex = stations.indexOfFirst { it.serviceId == currentDabServiceId }
        val newIndex = when {
            currentIndex < 0 -> 0
            else -> (currentIndex + direction).coerceIn(0, stations.size - 1)
        }

        if (newIndex != currentIndex && newIndex >= 0 && newIndex < stations.size) {
            val station = stations[newIndex]
            tuneToDabStation(station.serviceId, station.ensembleId)
        }
    }

    /**
     * Populate DAB List Mode with stations
     */
    private fun populateDabListMode() {
        dabStripAdapter?.setStations(dabStationsForCurrentMode)
    }

    /**
     * Refresh DAB station strip to reflect current favorite status
     */
    private fun refreshDabStationStrip() {
        val strip = dabListStationStrip ?: return
        val serviceId = currentDabServiceId

        // Load fresh data
        val stations = dabStationsForCurrentMode
        android.util.Log.d(TAG, "refreshDabStationStrip: ${stations.size} stations, current fav count: ${stations.count { it.isFavorite }}")

        // Create new adapter with fresh data
        val newAdapter = at.planqton.fytfm.ui.DabStripAdapter(
            onStationClick = { station ->
                tuneToDabStation(station.serviceId, station.ensembleId)
            },
            getLogoPath = { name, serviceId -> getLogoForDabStation(name, serviceId) }
        )
        newAdapter.setStations(stations)
        newAdapter.setSelectedStation(serviceId)

        // Replace adapter
        dabStripAdapter = newAdapter
        strip.adapter = newAdapter

        // Scroll to current station
        val position = newAdapter.getPositionForServiceId(serviceId)
        if (position >= 0) {
            strip.scrollToPosition(position)
        }
    }

    /**
     * Update DAB List Mode selection to current station
     */
    private fun updateDabListModeSelection() {
        if (dabListContentArea?.visibility != View.VISIBLE) return

        val station = dabStationsForCurrentMode.find { it.serviceId == currentDabServiceId }

        // Update station strip selection
        dabStripAdapter?.setSelectedStation(currentDabServiceId)

        // Scroll strip to selected station
        val position = dabStripAdapter?.getPositionForServiceId(currentDabServiceId) ?: -1
        if (position >= 0) {
            dabListStationStrip?.smoothScrollToPosition(position)
        }

        if (station != null) {
            // Update station info
            dabListStationName?.text = station.name ?: "Unknown"
            dabListEnsemble?.text = station.ensembleLabel ?: ""

            // Update favorite icon
            updateDabListFavoriteIcon(station.isFavorite)

            // Load cover image: Lock-Status beachten
            val isLockedToDabLogo = coverSourceLocked && lockedCoverSource == DabCoverSource.DAB_LOGO
            val isLockedToSlideshow = coverSourceLocked && lockedCoverSource == DabCoverSource.SLIDESHOW
            val isLockedToStationLogo = coverSourceLocked && lockedCoverSource == DabCoverSource.STATION_LOGO
            val stationLogo = getLogoForDabStation(station.name, station.serviceId)

            when {
                isLockedToDabLogo -> {
                    dabListCover?.setImageResource(R.drawable.ic_fytfm_dab_plus_light)
                }
                isLockedToStationLogo && stationLogo != null -> {
                    dabListCover?.load(java.io.File(stationLogo)) {
                        crossfade(true)
                        placeholder(R.drawable.ic_fytfm_dab_plus_light)
                        error(R.drawable.ic_fytfm_dab_plus_light)
                    }
                }
                isLockedToSlideshow && currentDabSlideshow != null -> {
                    dabListCover?.setImageBitmap(currentDabSlideshow)
                }
                !coverSourceLocked && stationLogo != null -> {
                    dabListCover?.load(java.io.File(stationLogo)) {
                        crossfade(true)
                        placeholder(R.drawable.ic_fytfm_dab_plus_light)
                        error(R.drawable.ic_fytfm_dab_plus_light)
                    }
                }
                !coverSourceLocked && currentDabSlideshow != null -> {
                    dabListCover?.setImageBitmap(currentDabSlideshow)
                }
                else -> {
                    dabListCover?.setImageResource(R.drawable.ic_fytfm_dab_plus_light)
                }
            }
        } else {
            dabListStationName?.text = getString(R.string.no_station)
            dabListEnsemble?.text = ""
            dabListRadiotext?.text = ""
            dabListCover?.setImageResource(R.drawable.ic_fytfm_dab_plus_light)
        }
    }

    /**
     * Update DAB List Mode radiotext/DLS
     */
    private fun updateDabListRadiotext(text: String?) {
        if (dabListContentArea?.visibility != View.VISIBLE) return
        dabListRadiotext?.text = text ?: ""
    }

    /**
     * Update DAB List Mode cover with slideshow image
     */
    /**
     * Update DAB Visualizer settings (style and visibility)
     */
    private fun updateDabVisualizerSettings() {
        val isEnabled = presetRepository.isDabVisualizerEnabled()
        val style = presetRepository.getDabVisualizerStyle()

        dabVisualizerView?.visibility = if (isEnabled) View.VISIBLE else View.GONE
        dabVisualizerView?.setStyle(style)
    }

    /**
     * Start the DAB visualizer with the audio session ID from DabTunerManager
     */
    private fun startDabVisualizer() {
        if (!presetRepository.isDabVisualizerEnabled()) {
            dabVisualizerView?.visibility = View.GONE
            return
        }

        val audioSessionId = radioController.getDabAudioSessionId()
        if (audioSessionId > 0) {
            dabVisualizerView?.visibility = View.VISIBLE
            dabVisualizerView?.setAudioSessionId(audioSessionId)
            android.util.Log.i(TAG, "DAB Visualizer started with session ID: $audioSessionId")
        } else {
            android.util.Log.w(TAG, "DAB Visualizer: No valid audio session ID")
        }
    }

    /**
     * Stop the DAB visualizer and release resources
     */
    private fun stopDabVisualizer() {
        dabVisualizerView?.release()
        android.util.Log.i(TAG, "DAB Visualizer stopped")
    }

    /**
     * Update favorite icon in DAB List Mode
     */
    private fun updateDabListFavoriteIcon(isFavorite: Boolean) {
        dabListFavoriteBtn?.setImageResource(
            if (isFavorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border
        )
    }

    /**
     * Update filter icon in DAB List Mode based on showFavoritesOnly state
     */
    private fun updateDabListFilterIcon() {
        dabListFilterBtn?.setImageResource(
            if (showFavoritesOnly) R.drawable.ic_folder else R.drawable.ic_folder_all
        )
    }

    /**
     * Tune to a specific DAB station
     */
    private fun tuneToDabStation(serviceId: Int, ensembleId: Int) {
        if (!isDabOn) {
            android.util.Log.w(TAG, "DAB tuner not on, cannot tune to station")
            return
        }

        try {
            currentDabServiceId = serviceId
            currentDabEnsembleId = ensembleId
            selectedCoverSourceIndex = -1  // Reset to auto mode on station change
            debugManager.resetDabDebugInfo()
            resetDabNowPlaying()

            val success = radioController.tuneDabService(serviceId, ensembleId)
            if (success) {
                android.util.Log.i(TAG, "Tuned to DAB service: $serviceId")
                saveLastDabService(serviceId, ensembleId)
            } else {
                android.util.Log.e(TAG, "Failed to tune to DAB service: $serviceId")
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error tuning DAB: ${e.message}")
        }
    }

    // ==================== END DAB LIST MODE ====================

    /**
     * Auto-names a station with PS value if the station has no name yet
     */
    private fun autoNameStationIfNeeded(ps: String) {
        val frequency = binding.frequencyScale.getFrequency()
        val isAM = isAmMode

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
            val frequency = binding.frequencyScale.getFrequency()
            val isAM = isAmMode

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
            toast(getString(R.string.station_renamed, ps))
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
        binding.nowPlayingPs.visibility = View.GONE
        binding.nowPlayingTitle.text = ""
        binding.nowPlayingArtist.text = ""
        binding.nowPlayingArtist.visibility = View.GONE
        val placeholderDrawable = if (isAmMode) R.drawable.placeholder_am else R.drawable.placeholder_fm
        binding.nowPlayingCover.setImageResource(placeholderDrawable)
        // Carousel bar zurücksetzen
        binding.carouselNowPlayingPs.visibility = View.GONE
        binding.carouselNowPlayingTitle.text = ""
        binding.carouselNowPlayingArtist.text = ""
        binding.carouselNowPlayingArtist.visibility = View.GONE
        binding.carouselNowPlayingCover.setImageResource(placeholderDrawable)
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
            binding.nowPlayingPs.text = ps
            binding.nowPlayingPs.visibility = View.VISIBLE
            binding.carouselNowPlayingPs.text = ps
            binding.carouselNowPlayingPs.visibility = View.VISIBLE
        } else {
            binding.nowPlayingPs.visibility = View.GONE
            binding.carouselNowPlayingPs.visibility = View.GONE
        }

        // Set text: Station name or frequency
        val displayName = stationName ?: freqDisplay
        binding.nowPlayingTitle.text = displayName
        binding.nowPlayingArtist.text = if (stationName != null) freqDisplay else ""
        binding.nowPlayingArtist.visibility = if (stationName != null) View.VISIBLE else View.GONE

        // Set cover: Station logo or radio icon
        val placeholderDrawable = if (isAM) R.drawable.placeholder_am else R.drawable.placeholder_fm
        if (logoPath != null) {
            binding.nowPlayingCover.load(java.io.File(logoPath)) {
                crossfade(true)
                placeholder(placeholderDrawable)
                error(placeholderDrawable)
            }
        } else {
            binding.nowPlayingCover.setImageResource(placeholderDrawable)
        }

        // Clear raw RT display and hide ignored indicator
        binding.nowPlayingRawRt.text = ""
        binding.carouselNowPlayingRawRt.text = ""
        binding.nowPlayingIgnoredIndicator.visibility = View.GONE
        binding.carouselIgnoredIndicator.visibility = View.GONE

        // Update carousel bar
        binding.carouselNowPlayingTitle.text = displayName
        binding.carouselNowPlayingArtist.text = if (stationName != null) freqDisplay else ""
        binding.carouselNowPlayingArtist.visibility = if (stationName != null) View.VISIBLE else View.GONE

        if (logoPath != null) {
            binding.carouselNowPlayingCover.load(java.io.File(logoPath)) {
                crossfade(true)
                placeholder(placeholderDrawable)
                error(placeholderDrawable)
            }
        } else {
            binding.carouselNowPlayingCover.setImageResource(placeholderDrawable)
        }

        // Clear carousel card cover
        stationCarouselAdapter?.updateCurrentCover(null, null)
    }

    /**
     * Updates the ignored indicator visibility based on whether the current RT is ignored
     */
    private fun updateIgnoredIndicator(rt: String?) {
        if (rt.isNullOrBlank()) {
            binding.nowPlayingIgnoredIndicator.visibility = View.GONE
            binding.carouselIgnoredIndicator.visibility = View.GONE
            return
        }

        val dao = rtCorrectionDao ?: return
        val normalizedRt = RtCorrection.normalizeRt(rt)

        CoroutineScope(Dispatchers.IO).launch {
            val isIgnored = dao.isRtIgnored(normalizedRt)
            withContext(Dispatchers.Main) {
                val visibility = if (isIgnored) View.VISIBLE else View.GONE
                binding.nowPlayingIgnoredIndicator.visibility = visibility
                binding.carouselIgnoredIndicator.visibility = visibility
            }
        }
    }

    /**
     * Update the carousel now playing bar with track info
     */
    private fun updateCarouselNowPlayingBar(trackInfo: TrackInfo) {
        // Update PS (Station Name)
        val ps = rdsManager.ps
        if (!ps.isNullOrBlank()) {
            binding.carouselNowPlayingPs.text = ps
            binding.carouselNowPlayingPs.visibility = View.VISIBLE
        } else {
            binding.carouselNowPlayingPs.visibility = View.GONE
        }

        // Update text
        if (trackInfo.artist.isBlank()) {
            binding.carouselNowPlayingArtist.visibility = View.GONE
            binding.carouselNowPlayingTitle.text = trackInfo.title
        } else {
            binding.carouselNowPlayingArtist.visibility = View.VISIBLE
            binding.carouselNowPlayingArtist.text = trackInfo.artist
            binding.carouselNowPlayingTitle.text = trackInfo.title
        }

        // Load cover image
        val currentFreq = binding.frequencyScale.getFrequency()
        val isAM = isAmMode
        val placeholderDrawable = if (isAM) R.drawable.placeholder_am else R.drawable.placeholder_fm
        // Nur lokale Pfade verwenden - keine HTTP URLs
        val localCover = trackInfo.coverUrl?.takeIf { it.startsWith("/") }
        val deezerEnabled = presetRepository.isDeezerEnabledForFrequency(currentFreq)

        // Try station logo first (always available as fallback)
        val stationLogo = radioLogoRepository.getLogoForStation(
            ps = rdsManager.ps,
            pi = rdsManager.pi,
            frequency = currentFreq
        )

        if (deezerEnabled && !localCover.isNullOrBlank()) {
            // Lokaler Deezer Cache verfügbar
            binding.carouselNowPlayingCover.load(java.io.File(localCover)) {
                crossfade(true)
                placeholder(placeholderDrawable)
                error(placeholderDrawable)
            }
        } else if (stationLogo != null) {
            // Station logo available
            binding.carouselNowPlayingCover.load(java.io.File(stationLogo)) {
                crossfade(true)
                placeholder(placeholderDrawable)
                error(placeholderDrawable)
            }
        } else {
            // Fallback: Radio icon
            binding.carouselNowPlayingCover.setImageResource(placeholderDrawable)
        }

        // Show bar
        if (binding.carouselNowPlayingBar.visibility != View.VISIBLE) {
            binding.carouselNowPlayingBar.visibility = View.VISIBLE
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
        binding.btnCorrectionTrash.setOnClickListener { handleCorrectionTrash() }

        // Refresh button - skip this track and search for another
        binding.btnCorrectionRefresh.setOnClickListener { handleCorrectionRefresh() }

        // Carousel versions - same logic
        binding.btnCarouselCorrectionTrash.setOnClickListener { handleCorrectionTrash() }
        binding.btnCarouselCorrectionRefresh.setOnClickListener { handleCorrectionRefresh() }
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
            android.util.Log.i(TAG, "RT ignored: $currentRt")

            withContext(Dispatchers.Main) {
                // Reset displayed track but keep bar visible
                lastDisplayedTrackId = null
                toast("RT wird ignoriert")
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
            android.util.Log.i(TAG, "Track skipped: ${currentTrack.artist} - ${currentTrack.title} for RT: $currentRt")

            withContext(Dispatchers.Main) {
                rtCombiner?.forceReprocess()
                toast("Suche anderen Track...")
            }
        }
    }

    /**
     * Update visibility of correction helper buttons based on setting
     */
    fun updateCorrectionHelpersVisibility() {
        val enabled = presetRepository.isCorrectionHelpersEnabled()
        binding.nowPlayingCorrectionButtons.visibility = if (enabled) View.VISIBLE else View.GONE
        binding.carouselCorrectionButtons.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    /**
     * Setup Spotify toggle button
     */
    private fun setupDeezerToggle() {
        // Load saved state for current frequency
        updateDeezerToggleForCurrentFrequency()

        // Click handlers for both toggles (normal and carousel)
        val toggleAction = {
            val currentFreq = binding.frequencyScale.getFrequency()
            val newState = !presetRepository.isDeezerEnabledForFrequency(currentFreq)
            presetRepository.setDeezerEnabledForFrequency(currentFreq, newState)
            updateDeezerToggleAppearance(newState)

            if (newState) {
                // Spotify enabled - trigger reprocessing of current RT
                rtCombiner?.forceReprocess()
                toast(R.string.deezer_enabled)
            } else {
                // Spotify disabled - immediately show raw RT with radio icon
                val currentRt = rdsManager.rt
                if (!currentRt.isNullOrBlank()) {
                    val displayInfo = parseRawRtToTrackInfo(currentRt)
                    lastDisplayedTrackId = null  // Force update
                    updateNowPlaying(displayInfo)
                    binding.nowPlayingRawRt.text = currentRt
                    binding.carouselNowPlayingRawRt.text = currentRt
                    updateIgnoredIndicator(currentRt)
                }
                // Clear carousel cover
                stationCarouselAdapter?.updateCurrentCover(null, null)
                toast(R.string.deezer_disabled)
            }
        }

        binding.btnDeezerToggle.setOnClickListener { toggleAction() }
        binding.btnCarouselDeezerToggle.setOnClickListener { toggleAction() }
    }

    /**
     * Update Spotify toggle button for current frequency
     * Call this when frequency changes
     */
    fun updateDeezerToggleForCurrentFrequency() {
        val currentFreq = binding.frequencyScale.getFrequency()
        val deezerEnabled = presetRepository.isDeezerEnabledForFrequency(currentFreq)
        updateDeezerToggleAppearance(deezerEnabled)
    }

    /**
     * Update Spotify toggle button appearance based on state
     */
    private fun updateDeezerToggleAppearance(enabled: Boolean) {
        val bg = if (enabled) R.drawable.toggle_selected else android.R.color.transparent
        binding.btnDeezerToggle.setBackgroundResource(bg)
        binding.btnCarouselDeezerToggle.setBackgroundResource(bg)
        // Also adjust alpha to indicate state
        val alpha = if (enabled) 1.0f else 0.4f
        binding.btnDeezerToggle.alpha = alpha
        binding.btnCarouselDeezerToggle.alpha = alpha
    }

    /**
     * Setup view mode toggle (Equalizer vs Carousel)
     */
    private fun setupViewModeToggle() {
        // Setup carousel adapter
        stationCarouselAdapter = at.planqton.fytfm.ui.StationCarouselAdapter { station ->
            // When user clicks a station in carousel, tune to it
            android.util.Log.i(TAG, "=== CAROUSEL CLICK: station=${station.name}, isDab=${station.isDab}, currentMode=$currentMode, serviceId=${station.serviceId} ===")
            if (station.isDab || isAnyDabMode) {
                android.util.Log.i(TAG, ">>> Using DAB logic (mode=$currentMode)")
                // DAB/DAB Dev: tune via appropriate TunerManager
                try {
                    currentDabServiceId = station.serviceId
                    currentDabEnsembleId = station.ensembleId
                    debugManager.resetDabDebugInfo()
                    resetDabNowPlaying()
                    val success = if (isDabDevMode) {
                        mockDabTunerManager.tuneService(station.serviceId, station.ensembleId)
                    } else {
                        radioController.tuneDabService(station.serviceId, station.ensembleId)
                    }
                    if (!success) {
                        toast(R.string.tuner_error_dab_not_found, long = true)
                    }
                    updateCarouselSelection()
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "DAB tune error: ${e.message}")
                    toast(getString(R.string.tuner_error, e.message), long = true)
                }
            } else {
                android.util.Log.i(TAG, ">>> Using FM/AM logic")
                try {
                    if (station.isAM) {
                        binding.frequencyScale.setMode(FrequencyScaleView.RadioMode.AM)
                    } else {
                        binding.frequencyScale.setMode(FrequencyScaleView.RadioMode.FM)
                    }
                    binding.frequencyScale.setFrequency(station.frequency)
                    fmNative?.tune(station.frequency)
                    updateCarouselSelection()
                    startCarouselCenterTimer(station.frequency, station.isAM)
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "FM tune error: ${e.message}")
                    toast(getString(R.string.tuner_error, e.message), long = true)
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
                android.util.Log.d(TAG, "Carousel padding set to: $padding (width=$width, cardWidth=$cardWidth)")

                // Initial scroll to current frequency AFTER padding is set
                if (carouselNeedsInitialScroll && isCarouselMode) {
                    val currentFreq = binding.frequencyScale.getFrequency()
                    val isAM = isAmMode
                    val position = stationCarouselAdapter?.getPositionForFrequency(currentFreq, isAM) ?: -1
                    android.util.Log.d(TAG, "Carousel post-padding scroll: freq=$currentFreq, position=$position")
                    if (position >= 0) {
                        carouselNeedsInitialScroll = false
                        // Use scrollToPositionWithOffset to center the item
                        val lm = layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
                        lm?.scrollToPositionWithOffset(position, padding)
                        android.util.Log.d(TAG, "Carousel scrollToPositionWithOffset $position, offset=$padding")
                    }
                }
            }
        }

        // Toggle button click handlers
        btnViewModeEqualizer?.setOnClickListener {
            setViewMode(false)
        }

        btnViewModeImage?.setOnClickListener {
            setViewMode(true)
        }

        // Carousel favorite button
        btnCarouselFavorite?.setOnClickListener {
            binding.btnFavorite.performClick()
            updateCarouselFavoriteIcon()
        }

        // Carousel record button
        btnCarouselRecord?.setOnClickListener {
            toggleDabRecording()
        }

        // Carousel EPG button
        btnCarouselEpg?.setOnClickListener {
            showEpgDialog()
        }

        // Restore saved view mode - aber Carousel wird später in onCreate befüllt
        val savedCarouselMode = presetRepository.isCarouselMode()
        android.util.Log.d(TAG, "setupViewModeToggle: savedCarouselMode=$savedCarouselMode")
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
        // Auch für aktuellen Radio-Modus speichern
        presetRepository.setCarouselModeForRadioMode(currentMode.name, carousel)

        if (carousel) {
            // Switch to carousel mode
            mainContentArea?.visibility = View.GONE
            dabListContentArea?.visibility = View.GONE
            carouselContentArea?.visibility = View.VISIBLE
            btnViewModeEqualizer?.background = null
            btnViewModeImage?.setBackgroundResource(R.drawable.toggle_selected)

            // Show control bar and station bar in carousel mode
            binding.controlBar.visibility = View.VISIBLE
            binding.stationBar.visibility = View.VISIBLE

            // Update button visibility for carousel mode
            updateRecordButtonVisibility()
            updateEpgButtonVisibility()

            // Populate carousel with stations
            populateCarousel()
            updateCarouselSelection()
            // Initial scroll will be triggered by frequency listener
            carouselNeedsInitialScroll = true
        } else {
            // Switch to equalizer/list mode
            carouselContentArea?.visibility = View.GONE

            if (isDabMode) {
                // DAB List Mode - special layout, hide control bar but show station bar
                mainContentArea?.visibility = View.GONE
                dabListContentArea?.visibility = View.VISIBLE
                dabListStationStrip?.visibility = View.GONE  // Hide the DAB strip, use stationBar instead
                binding.controlBar.visibility = View.GONE
                binding.stationBar.visibility = View.VISIBLE  // Show the original station bar
                populateDabListMode()
                updateDabListModeSelection()
            } else {
                // FM/AM Equalizer Mode
                mainContentArea?.visibility = View.VISIBLE
                dabListContentArea?.visibility = View.GONE
                binding.controlBar.visibility = View.VISIBLE
                binding.stationBar.visibility = View.VISIBLE
            }

            btnViewModeEqualizer?.setBackgroundResource(R.drawable.toggle_selected)
            btnViewModeImage?.background = null
        }
    }

    /**
     * Populate the carousel with saved stations
     */
    private fun populateCarousel() {
        android.util.Log.i(TAG, "=== populateCarousel: mode=$currentMode, isDab=$isDabMode ===")
        Thread.currentThread().stackTrace.take(8).forEach {
            android.util.Log.d(TAG, "  at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})")
        }
        val stations = when {
            isFmMode -> presetRepository.loadFmStations()
            isAmMode -> presetRepository.loadAmStations()
            isAnyDabMode -> dabStationsForCurrentMode
            else -> presetRepository.loadFmStations()
        }

        android.util.Log.d(TAG, "populateCarousel: ${stations.size} stations loaded, mode=$currentMode")

        val carouselItems = stations.map { station ->
            val logoPath = if (station.isDab || isAnyDabMode) {
                getLogoForDabStation(station.name, station.serviceId)
            } else {
                radioLogoRepository.getLogoForStation(station.name, null, station.frequency)
            }
            at.planqton.fytfm.ui.StationCarouselAdapter.StationItem(
                frequency = station.frequency,
                name = station.name,
                logoPath = logoPath,
                isAM = isAmMode,
                isDab = isAnyDabMode || station.isDab,
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
        if (isAnyDabMode) {
            // DAB/DAB Dev: select by serviceId
            stationCarouselAdapter?.setCurrentDabService(currentDabServiceId)
            val dabStation = dabStationsForCurrentMode.find { it.serviceId == currentDabServiceId }
            carouselFrequencyLabel?.text = dabStation?.name ?: if (isDabDevMode) "DAB Dev" else "DAB+"
            updateCarouselFavoriteIcon()
            val position = stationCarouselAdapter?.getPositionForDabService(currentDabServiceId) ?: -1
            android.util.Log.d(TAG, "updateCarouselSelection DAB: serviceId=$currentDabServiceId, position=$position")
            if (position >= 0) smoothScrollCarouselToCenter(position)
            return
        }

        val currentFreq = binding.frequencyScale.getFrequency()
        android.util.Log.d(TAG, "updateCarouselSelection: freq=$currentFreq, isAM=$isAmMode")
        stationCarouselAdapter?.setCurrentFrequency(currentFreq, isAmMode)
        carouselFrequencyLabel?.text = if (isAmMode) "AM ${currentFreq.toInt()}" else "FM %.2f".format(currentFreq).replace(".", ",")
        updateCarouselFavoriteIcon()
        val position = stationCarouselAdapter?.getPositionForFrequency(currentFreq, isAmMode) ?: -1
        android.util.Log.d(TAG, "updateCarouselSelection: position=$position")
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
        android.util.Log.d(TAG, "Carousel center timer started for position $position")
    }

    /**
     * Update carousel debug overlay with current state
     */
    private fun updateCarouselDebugInfo() {
        debugManager.updateCarouselDebugInfo(
            pendingPosition = carouselPendingCenterPosition,
            timerStart = carouselCenterTimerStart,
            currentFreq = binding.frequencyScale.getFrequency(),
            isAM = isAmMode,
            getPositionForFrequency = { freq, isAM -> stationCarouselAdapter?.getPositionForFrequency(freq, isAM) ?: -1 },
            recyclerView = stationCarousel
        )
    }

    /**
     * Smoothly scroll carousel to center the item at the given position
     */
    private fun smoothScrollCarouselToCenter(position: Int) {
        // Log disabled to reduce noise
        // android.util.Log.d(TAG, "smoothScrollCarouselToCenter called with position=$position, carousel=${stationCarousel != null}")
        if (position < 0) return

        stationCarousel?.let { recyclerView ->
            val layoutManager = recyclerView.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
            if (layoutManager == null) {
                android.util.Log.e(TAG, "LayoutManager is null!")
                return@let
            }

            // Calculate offset to center the item
            val cardWidth = (216 * resources.displayMetrics.density).toInt()
            val recyclerWidth = recyclerView.width

            // If RecyclerView not yet laid out, post the scroll
            if (recyclerWidth <= 0) {
                // Log disabled to reduce noise
                recyclerView.post {
                    smoothScrollCarouselToCenter(position)
                }
                return@let
            }

            // The RecyclerView already has padding for centering, so offset should be 0
            // This places the item at the start of the content area (after the left padding)
            android.util.Log.d(TAG, "Carousel scroll: pos=$position, recyclerWidth=$recyclerWidth, padding=${recyclerView.paddingLeft}")

            // Use scrollToPositionWithOffset with offset 0 - padding handles centering
            layoutManager.scrollToPositionWithOffset(position, 0)
            android.util.Log.d(TAG, "Carousel scrollToPositionWithOffset pos=$position, offset=0 DONE")
        }
    }

    /**
     * Start timer to return carousel to current station after 2 seconds of idle
     */
    private fun startCarouselReturnTimer() {
        cancelCarouselReturnTimer()

        // Set debug info variables
        val currentFreq = binding.frequencyScale.getFrequency()
        val isAM = isAmMode
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
            android.util.Log.d(TAG, "Carousel return timer: scrolling to position $pos")
            if (pos >= 0) {
                smoothScrollCarouselToCenter(pos)
            }
            carouselPendingCenterPosition = -1
            updateCarouselDebugInfo()
        }
        carouselCenterHandler.postDelayed(carouselReturnRunnable!!, 2000)
        android.util.Log.d(TAG, "Carousel return timer started (2s)")
    }

    /**
     * Cancel pending carousel return timer
     */
    private fun cancelCarouselReturnTimer() {
        carouselReturnRunnable?.let {
            carouselCenterHandler.removeCallbacks(it)
            android.util.Log.d(TAG, "Carousel return timer cancelled")
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
     * Start Auto-Background timer if enabled and conditions are met
     */
    private fun startAutoBackgroundTimerIfNeeded() {
        val enabled = presetRepository.isAutoBackgroundEnabled()
        val onlyOnBoot = presetRepository.isAutoBackgroundOnlyOnBoot()
        android.util.Log.d(TAG, "Auto-Background check: enabled=$enabled, onlyOnBoot=$onlyOnBoot, wasStartedFromBoot=$wasStartedFromBoot")

        if (!enabled) {
            android.util.Log.d(TAG, "Auto-Background: Disabled in settings")
            return
        }

        // Check if "only on boot" is enabled
        if (onlyOnBoot && !wasStartedFromBoot) {
            android.util.Log.d(TAG, "Auto-Background: Skipping - not started from boot")
            return
        }

        startAutoBackgroundTimer()
    }

    /**
     * Start the Auto-Background timer
     */
    private fun startAutoBackgroundTimer() {
        cancelAutoBackgroundTimer()

        autoBackgroundSecondsRemaining = presetRepository.getAutoBackgroundDelay()
        autoBackgroundTimerStartTime = System.currentTimeMillis()
        android.util.Log.d(TAG, "Auto-Background: Starting timer for $autoBackgroundSecondsRemaining seconds")

        // Show countdown toast
        showAutoBackgroundToast()

        autoBackgroundHandler = android.os.Handler(android.os.Looper.getMainLooper())
        autoBackgroundRunnable = object : Runnable {
            override fun run() {
                autoBackgroundSecondsRemaining--
                android.util.Log.d(TAG, "Auto-Background: Tick - ${autoBackgroundSecondsRemaining}s remaining")
                if (autoBackgroundSecondsRemaining <= 0) {
                    android.util.Log.i(TAG, "Auto-Background: Moving app to background")
                    autoBackgroundToast?.cancel()
                    moveTaskToBack(true)
                } else {
                    showAutoBackgroundToast()
                    autoBackgroundHandler?.postDelayed(this, 1000L)
                }
            }
        }
        autoBackgroundHandler?.postDelayed(autoBackgroundRunnable!!, 1000L)
    }

    private fun showAutoBackgroundToast() {
        autoBackgroundToast?.cancel()
        autoBackgroundToast = android.widget.Toast.makeText(
            this,
            "Auto-Background in ${autoBackgroundSecondsRemaining}s",
            android.widget.Toast.LENGTH_SHORT
        )
        autoBackgroundToast?.show()
    }

    /**
     * Cancel the Auto-Background timer
     */
    private fun cancelAutoBackgroundTimer() {
        autoBackgroundRunnable?.let {
            autoBackgroundHandler?.removeCallbacks(it)
        }
        autoBackgroundRunnable = null
        autoBackgroundHandler = null
        autoBackgroundSecondsRemaining = 0
        autoBackgroundTimerStartTime = 0L
        autoBackgroundToast?.cancel()
        autoBackgroundToast = null
    }

    /**
     * Update carousel favorite icon state
     */
    private fun updateCarouselFavoriteIcon() {
        val currentFreq = binding.frequencyScale.getFrequency()
        val isAM = isAmMode
        val stations = if (isAM) presetRepository.loadAmStations() else presetRepository.loadFmStations()
        val station = stations.find { Math.abs(it.frequency - currentFreq) < 0.05f }
        val isFavorite = station?.isFavorite == true

        btnCarouselFavorite?.setImageResource(
            if (isFavorite) R.drawable.ic_favorite_filled else R.drawable.ic_favorite_border
        )
    }

    private fun updateDebugOverlayVisibility() {
        val showDebug = presetRepository.isShowDebugInfos()
        binding.debugChecklistOverlay.visibility = if (showDebug) View.VISIBLE else View.GONE
        // RDS overlay visibility depends on both the setting AND the checkbox
        binding.debugOverlay.visibility = if (showDebug && binding.checkRdsInfo.isChecked == true) View.VISIBLE else View.GONE
    }

    /**
     * DLS Log aus Datei laden (beim App-Start)
     */
    private fun loadDlsLogFromFile() {
        try {
            val file = java.io.File(getExternalFilesDir(null), "dls_log.txt")
            if (file.exists()) {
                val lines = file.readLines().filter { it.isNotBlank() }
                dlsLogEntries.clear()
                dlsLogEntries.addAll(lines)
                android.util.Log.d(TAG, "Loaded ${lines.size} DLS log entries")
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to load DLS log: ${e.message}")
        }
    }

    // ========== DlsLogCallback Implementation ==========

    override fun getDlsLogEntries(): MutableList<String> = dlsLogEntries

    override fun getLastLoggedDls(): String? = lastLoggedDls

    override fun setLastLoggedDls(dls: String?) {
        lastLoggedDls = dls
    }

    override fun saveDlsLogToFile() {
        try {
            val file = java.io.File(getExternalFilesDir(null), "dls_log.txt")
            file.writeText(dlsLogEntries.joinToString("\n"))
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to save DLS log: ${e.message}")
        }
    }

    override fun getDlsLogFile(): java.io.File {
        return java.io.File(getExternalFilesDir(null), "dls_log.txt")
    }

    private fun showDlsLogDialog() {
        at.planqton.fytfm.ui.dlslog.DlsLogDialogFragment.newInstance()
            .show(supportFragmentManager, at.planqton.fytfm.ui.dlslog.DlsLogDialogFragment.TAG)
    }

    /**
     * Zeigt den Settings-Dialog als DialogFragment an.
     */
    private fun showSettingsDialogFragment() {
        at.planqton.fytfm.ui.settings.SettingsDialogFragment.newInstance()
            .show(supportFragmentManager, at.planqton.fytfm.ui.settings.SettingsDialogFragment.TAG)
    }

    // ========== CorrectionsCallback Implementation ==========

    override fun getRtCorrectionDao(): at.planqton.fytfm.data.rdslog.RtCorrectionDao? = rtCorrectionDao

    private fun showCorrectionsViewerDialogFragment() {
        at.planqton.fytfm.ui.corrections.CorrectionsViewerDialogFragment.newInstance()
            .show(supportFragmentManager, at.planqton.fytfm.ui.corrections.CorrectionsViewerDialogFragment.TAG)
    }

    // ========== DeezerCacheCallback Implementation ==========

    override fun getDeezerCache(): at.planqton.fytfm.deezer.DeezerCache? = deezerCache

    private fun showDeezerCacheDialogFragment() {
        at.planqton.fytfm.ui.cache.DeezerCacheDialogFragment.newInstance()
            .show(supportFragmentManager, at.planqton.fytfm.ui.cache.DeezerCacheDialogFragment.TAG)
    }

    // ========== BugReportCallback Implementation ==========

    override fun onBugReportTypeSelected(type: at.planqton.fytfm.ui.bugreport.BugReportDialogFragment.ReportType) {
        // Not used - DialogFragment handles type selection internally
    }

    override fun collectParserReportData(userDescription: String?): String {
        val bugReportHelper = BugReportHelper(this)
        return bugReportHelper.buildParserReportContent(userDescription)
    }

    override fun collectDeezerReportData(userDescription: String?): String {
        val bugReportHelper = BugReportHelper(this)
        return bugReportHelper.buildDeezerReportContent(
            userDescription = userDescription,
            deezerStatus = currentDeezerStatus,
            originalRt = currentDeezerOriginalRt,
            strippedRt = currentDeezerStrippedRt,
            query = currentDeezerQuery,
            trackInfo = currentDeezerTrackInfo,
            fmFrequency = binding.frequencyScale.getFrequency(),
            fmPs = rdsManager.ps,
            fmRt = rdsManager.rt,
            fmPi = rdsManager.pi,
            dabStation = currentDabServiceLabel,
            dabDls = currentDabDls
        )
    }

    override fun collectGeneralReportData(userDescription: String?): String {
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
            currentFrequency = binding.frequencyScale.getFrequency(),
            spotifyStatus = currentDeezerStatus,
            spotifyOriginalRt = currentDeezerOriginalRt,
            spotifyStrippedRt = currentDeezerStrippedRt,
            spotifyQuery = currentDeezerQuery,
            spotifyTrackInfo = currentDeezerTrackInfo,
            userDescription = userDescription,
            crashLog = null
        )
        val bugReportHelper = BugReportHelper(this)
        return bugReportHelper.buildReportContent(appState)
    }

    override fun onBugReportGenerated(content: String, isCrashReport: Boolean) {
        saveBugReportWithPicker(content, isCrashReport)
    }

    private fun showBugReportDialogFragment() {
        at.planqton.fytfm.ui.bugreport.BugReportDialogFragment.newInstance()
            .show(supportFragmentManager, at.planqton.fytfm.ui.bugreport.BugReportDialogFragment.TAG)
    }

    // ========== RadioEditorCallback Implementation ==========

    override fun getRadioLogoRepository(): at.planqton.fytfm.data.logo.RadioLogoRepository = radioLogoRepository

    override fun getLogoForStation(ps: String?, pi: Int?, freq: Float): String? =
        radioLogoRepository.getLogoForStation(ps, pi, freq)

    override fun getLogoForDabStation(name: String?, serviceId: Int): String? =
        radioLogoRepository.getLogoForStation(name, null, 0f)

    override fun onStationsUpdated() = loadStationsForCurrentMode()

    override fun showManualLogoSearchDialog(
        station: at.planqton.fytfm.data.RadioStation,
        mode: FrequencyScaleView.RadioMode,
        onComplete: () -> Unit
    ) = showManualLogoSearchDialogInternal(station, mode, onComplete)

    override fun showEditStationDialog(
        station: at.planqton.fytfm.data.RadioStation,
        onSave: (String, Boolean) -> Unit
    ) = showEditStationDialogInternal(station, onSave)

    override fun showEditDabStationDialog(
        station: at.planqton.fytfm.data.RadioStation,
        onSave: (String) -> Unit
    ) = showEditDabStationDialogInternal(station, onSave)

    override fun clearLastSyncedPs(freqKey: Int) {
        lastSyncedPs.remove(freqKey)
    }

    private fun showRadioEditorDialogFragment(mode: FrequencyScaleView.RadioMode? = null) {
        val targetMode = mode ?: binding.frequencyScale.getMode()
        at.planqton.fytfm.ui.editor.RadioEditorDialogFragment.newInstance(targetMode)
            .show(supportFragmentManager, at.planqton.fytfm.ui.editor.RadioEditorDialogFragment.TAG)
    }

    // ========== LogoTemplateCallback Implementation ==========
    // Note: getRadioAreaName() already implemented in SettingsCallback
    // Note: getRadioLogoRepository() already implemented in RadioEditorCallback

    override fun getCurrentRadioArea(): Int = presetRepository.getRadioArea()

    override fun onTemplateSelected() {
        radioLogoRepository.invalidateCache()
        loadStationsForCurrentMode()
    }

    override fun onLogosUpdated() {
        radioLogoRepository.invalidateCache()
        loadStationsForCurrentMode()
    }

    private fun showLogoTemplateDialogFragment(onDismiss: () -> Unit = {}) {
        at.planqton.fytfm.ui.logotemplate.LogoTemplateDialogFragment.newInstance()
            .show(supportFragmentManager, at.planqton.fytfm.ui.logotemplate.LogoTemplateDialogFragment.TAG)
    }

    private fun setupStationList() {
        stationAdapter = StationAdapter(
            onStationClick = { station ->
                // Tune to selected station
                try {
                    if (station.isDab || isDabMode) {
                        // DAB: tune via DabTunerManager
                        currentDabServiceId = station.serviceId
                        currentDabEnsembleId = station.ensembleId
                        debugManager.resetDabDebugInfo()
                        resetDabNowPlaying()
                        val success = radioController.tuneDabService(station.serviceId, station.ensembleId)
                        if (!success) {
                            toast(R.string.tuner_error_dab_not_found, long = true)
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
                            binding.frequencyScale.setMode(FrequencyScaleView.RadioMode.AM)
                        } else {
                            binding.frequencyScale.setMode(FrequencyScaleView.RadioMode.FM)
                        }
                        binding.frequencyScale.setFrequency(station.frequency)
                        stationAdapter.setSelectedFrequency(station.frequency)
                    }
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Station tune error: ${e.message}")
                    toast(getString(R.string.tuner_error, e.message), long = true)
                }
            },
            onStationLongClick = { station ->
                // Long press opens radio editor for the station's mode
                val mode = when {
                    station.isDab -> FrequencyScaleView.RadioMode.DAB
                    station.isAM -> FrequencyScaleView.RadioMode.AM
                    else -> FrequencyScaleView.RadioMode.FM
                }
                showRadioEditorDialogFragment(mode)
            },
            getLogoPath = { ps, pi, frequency ->
                if (presetRepository.isShowLogosInFavorites()) {
                    radioLogoRepository.getLogoForStation(ps, pi, frequency)
                } else {
                    null
                }
            }
        )

        binding.stationRecycler.layoutManager = LinearLayoutManager(
            this, LinearLayoutManager.HORIZONTAL, false
        )
        binding.stationRecycler.adapter = stationAdapter
    }

    private fun setupListeners() {
        binding.frequencyScale.setOnFrequencyChangeListener { frequency ->
            handleFrequencyChange(frequency)
        }

        binding.frequencyScale.setOnModeChangeListener { mode ->
            updateModeSpinner()
            loadFavoritesFilterState()  // Filter-Status für neuen Modus laden
            loadStationsForCurrentMode()
            updateFavoriteButton()
        }

        // Explizit longClickable aktivieren
        binding.btnPrevStation.isLongClickable = true
        binding.btnNextStation.isLongClickable = true

        binding.btnPrevStation.setOnClickListener {
            val step = if (isFmMode) FrequencyScaleView.FM_FREQUENCY_STEP else FrequencyScaleView.AM_FREQUENCY_STEP
            binding.frequencyScale.setFrequency(binding.frequencyScale.getFrequency() - step)
        }

        // Long-Press: Seek zum vorherigen Sender mit Signal
        binding.btnPrevStation.setOnLongClickListener {
            android.util.Log.i(TAG, "Long-Press PREV detected!")
            toast(R.string.seek_started_prev)
            if (isRadioOn && isFmMode) seekToStation(false)
            true
        }

        binding.btnNextStation.setOnClickListener {
            val step = if (isFmMode) FrequencyScaleView.FM_FREQUENCY_STEP else FrequencyScaleView.AM_FREQUENCY_STEP
            binding.frequencyScale.setFrequency(binding.frequencyScale.getFrequency() + step)
        }

        // Long-Press: Seek zum nächsten Sender mit Signal
        binding.btnNextStation.setOnLongClickListener {
            android.util.Log.i(TAG, "Long-Press NEXT detected!")
            toast(R.string.seek_started_next)
            if (isRadioOn && isFmMode) seekToStation(true)
            true
        }

        binding.btnFavorite.setOnClickListener {
            toggleCurrentStationFavorite()
        }

        binding.btnPlayPause.setOnClickListener {
            isPlaying = !isPlaying
            if (isRadioOn) {
                setRadioMuteState(!isPlaying)
                FytFMMediaService.instance?.updatePlaybackState(isPlaying)
            }
            updatePlayPauseButton()
        }

        // Radio mode spinner is set up in setupRadioModeSpinner()

        // Power button - toggle radio on/off
        binding.btnPower.setOnClickListener {
            toggleRadioPower()
        }

        // Search button - öffnet Sendersuche Dialog
        binding.btnSearch.setOnClickListener {
            showStationScanDialog()
        }

        // All list button
        binding.tvAllList.setOnClickListener {
            // TODO: Show full station list dialog
        }

        // Other bottom control buttons
        binding.btnSkipPrev.setOnClickListener {
            skipToPreviousStation()
        }
        binding.btnSkipNext.setOnClickListener {
            skipToNextStation()
        }
        binding.btnFolder.setOnClickListener {
            toggleFavoritesFilter()
        }
        binding.btnSettings.setOnClickListener {
            showSettingsDialogFragment()
        }
        binding.btnArchive.setOnClickListener {
            showArchiveOverlay()
        }
        binding.archiveOverlay.btnArchiveBack.setOnClickListener {
            hideArchiveOverlay()
        }

        // Long press on spinner for debug tune
        binding.spinnerRadioMode.setOnLongClickListener {
            debugTune()
            true
        }
    }

    /**
     * Richtet FragmentResultListener für DialogFragments ein.
     */
    private fun setupDialogListeners() {
        // Clear Archive Confirmation
        supportFragmentManager.setFragmentResultListener("clear_archive", this) { _, bundle ->
            if (bundle.getBoolean("confirmed", false)) {
                rdsLogRepository.clearAll()
                toast(R.string.archive_cleared)
            }
        }

        // Language Selection
        supportFragmentManager.setFragmentResultListener("language_selection", this) { _, bundle ->
            val index = bundle.getInt("selected_index", -1)
            if (index >= 0) {
                val languages = arrayOf(LocaleHelper.LANGUAGE_SYSTEM, LocaleHelper.LANGUAGE_ENGLISH, LocaleHelper.LANGUAGE_GERMAN)
                languages.getOrNull(index)?.let { lang ->
                    settingsLanguageCallback?.invoke(lang)
                }
            }
        }

        // Now Playing Animation Selection
        supportFragmentManager.setFragmentResultListener("animation_selection", this) { _, bundle ->
            val value = bundle.getInt("selected_value", -1)
            if (value >= 0) {
                settingsAnimationCallback?.invoke(value)
            }
        }

        // RDS Retention Selection
        supportFragmentManager.setFragmentResultListener("retention_selection", this) { _, bundle ->
            val value = bundle.getInt("selected_value", -1)
            if (value >= 0) {
                settingsRetentionCallback?.invoke(value)
            }
        }

        // Import Stations
        supportFragmentManager.setFragmentResultListener(
            at.planqton.fytfm.ui.dialogs.ImportStationsDialogFragment.REQUEST_KEY, this
        ) { _, bundle ->
            presetRepository.setAskedAboutImport(true)
            if (bundle.getBoolean("import", false)) {
                importStationsFromOriginalApp()
            } else {
                toast(R.string.skip_stations_hint, long = true)
            }
        }

        // Crash Report Action
        supportFragmentManager.setFragmentResultListener(
            at.planqton.fytfm.ui.dialogs.CrashReportDialogFragment.REQUEST_KEY, this
        ) { _, bundle ->
            val crashLog = CrashHandler.getCrashLog(this) ?: return@setFragmentResultListener
            when (bundle.getInt("action")) {
                at.planqton.fytfm.ui.dialogs.CrashReportDialogFragment.ACTION_BUG_REPORT -> {
                    showCrashBugReportDialog(crashLog)
                }
                at.planqton.fytfm.ui.dialogs.CrashReportDialogFragment.ACTION_CRASH_LOG -> {
                    showCrashLogPreview(crashLog)
                }
                at.planqton.fytfm.ui.dialogs.CrashReportDialogFragment.ACTION_IGNORE -> {
                    CrashHandler.clearCrashLog(this)
                }
            }
        }

        // Crash Bug Report Input
        supportFragmentManager.setFragmentResultListener("crash_description", this) { _, bundle ->
            if (bundle.getBoolean("cancelled", false)) {
                CrashHandler.clearCrashLog(this)
            } else {
                val description = bundle.getString("input", "")
                val crashLog = CrashHandler.getCrashLog(this) ?: return@setFragmentResultListener
                createCrashBugReport(crashLog, description)
            }
        }

        // Bug Report Preview
        supportFragmentManager.setFragmentResultListener("bug_report_preview", this) { _, bundle ->
            val isCrashReport = pendingBugReportIsCrash
            if (bundle.getBoolean("cancelled", false)) {
                if (isCrashReport) CrashHandler.clearCrashLog(this)
            } else if (bundle.getBoolean("save", false)) {
                val content = bundle.getString("content", "")
                saveBugReportWithPicker(content, isCrashReport)
            }
        }
    }

    // Callbacks für Settings-Dialog
    private var settingsLanguageCallback: ((String) -> Unit)? = null
    private var settingsAnimationCallback: ((Int) -> Unit)? = null
    private var settingsRetentionCallback: ((Int) -> Unit)? = null

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
                            if (isRadioOn) setRadioMuteState(false)
                            updatePlayPauseButton()
                        }
                    }
                }
                service.onPauseCallback = {
                    runOnUiThread {
                        if (isPlaying) {
                            isPlaying = false
                            if (isRadioOn) setRadioMuteState(true)
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
                        binding.frequencyScale.setFrequency(frequency)
                    }
                }
                android.util.Log.i(TAG, "MediaService callbacks registered")
            }
        }, 500)
    }

    /**
     * Debug-Funktion: Tune zu 90.4 MHz und zeige RDS-Daten
     */
    private fun debugTune() {
        val targetFreq = 90.4f
        android.util.Log.i(TAG, "=== DEBUG: Tuning to $targetFreq FM ===")

        // Stelle sicher dass Radio eingeschaltet ist
        if (!isRadioOn) {
            toggleRadioPower()
        }

        // Tune
        binding.frequencyScale.setFrequency(targetFreq)

        android.util.Log.i(TAG, "=== DEBUG COMPLETE ===")
    }

    /**
     * Zeigt das Archiv-Overlay an
     */
    private fun showArchiveOverlay() {
        binding.archiveOverlay.root.visibility = View.VISIBLE

        // Initialize adapter if needed
        if (!::archiveAdapter.isInitialized) {
            archiveAdapter = RdsLogAdapter()
            binding.archiveOverlay.archiveRecycler.layoutManager = LinearLayoutManager(this)
            binding.archiveOverlay.archiveRecycler.adapter = archiveAdapter
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
        binding.archiveOverlay.root.visibility = View.GONE
        archiveJob?.cancel()
        archiveJob = null
    }

    private fun setupArchiveUI() {
        val searchContainer = binding.archiveOverlay.archiveSearchContainer
        val etSearch = binding.archiveOverlay.etArchiveSearch

        binding.archiveOverlay.btnArchiveSearch.setOnClickListener {
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
        binding.archiveOverlay.btnArchiveClear.setOnClickListener {
            at.planqton.fytfm.ui.dialogs.ConfirmationDialogFragment.newInstance(
                title = R.string.clear_archive_title,
                message = R.string.clear_archive_message,
                positiveText = R.string.delete,
                requestKey = "clear_archive"
            ).show(supportFragmentManager, at.planqton.fytfm.ui.dialogs.ConfirmationDialogFragment.TAG)
        }

        // "All" chip click
        binding.archiveOverlay.chipAllFrequencies.setOnClickListener {
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
                binding.archiveOverlay.tvArchiveStats.text = getString(R.string.entries_format, entries.size)

                // Toggle empty state
                if (entries.isEmpty()) {
                    binding.archiveOverlay.archiveRecycler.visibility = View.GONE
                    binding.archiveOverlay.archiveEmptyState.visibility = View.VISIBLE
                } else {
                    binding.archiveOverlay.archiveRecycler.visibility = View.VISIBLE
                    binding.archiveOverlay.archiveEmptyState.visibility = View.GONE
                }
            }
        }
    }

    private fun updateFilterChipSelection() {
        val chipAll = binding.archiveOverlay.chipAllFrequencies

        if (archiveFilterFrequency == null) {
            chipAll.setBackgroundResource(R.drawable.chip_selected)
            chipAll.setTextColor(resources.getColor(android.R.color.white, null))
        } else {
            chipAll.setBackgroundResource(R.drawable.chip_unselected)
            chipAll.setTextColor(resources.getColor(android.R.color.black, null))
        }
    }


    /**
     * Beendet die App vollständig (Radio aus, Prozess beenden)
     */
    private fun closeApp() {
        // Radio ausschalten
        if (isRadioOn) {
            stopRdsPolling()
            radioController.powerOffFmAmFull()
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
                        toast("Cache exported successfully")
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        toast("Export failed")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Export failed", e)
                withContext(Dispatchers.Main) {
                    toast("Export failed: ${e.message}")
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
                        toast("Imported $importedCount tracks")
                    } else {
                        toast("Import failed")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Import failed", e)
                withContext(Dispatchers.Main) {
                    toast("Import failed: ${e.message}")
                }
            }
        }
    }

    override fun getRadioAreaName(area: Int): String {
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

        settingsLanguageCallback = onSelected
        at.planqton.fytfm.ui.dialogs.SingleChoiceDialogFragment.newInstance(
            title = R.string.language,
            options = options,
            selectedIndex = currentIndex,
            requestKey = "language_selection"
        ).show(supportFragmentManager, at.planqton.fytfm.ui.dialogs.SingleChoiceDialogFragment.TAG)
    }

    private fun showNowPlayingAnimationDialog(onSelected: (Int) -> Unit) {
        val options = arrayOf("Keine", "Slide", "Fade")
        val currentType = presetRepository.getNowPlayingAnimation()

        settingsAnimationCallback = onSelected
        at.planqton.fytfm.ui.dialogs.SingleChoiceDialogFragment.newInstance(
            title = R.string.now_playing_animation,
            options = options,
            selectedIndex = currentType,
            requestKey = "animation_selection"
        ).show(supportFragmentManager, at.planqton.fytfm.ui.dialogs.SingleChoiceDialogFragment.TAG)
    }

    private fun showRdsRetentionDialog(onSelected: (Int) -> Unit) {
        val values = intArrayOf(7, 14, 30, 90)
        val options = values.map { getString(R.string.days_format, it) }.toTypedArray()
        val currentDays = rdsLogRepository.retentionDays
        val currentIndex = values.indexOfFirst { it == currentDays }.coerceAtLeast(0)

        settingsRetentionCallback = onSelected
        at.planqton.fytfm.ui.dialogs.SingleChoiceDialogFragment.newInstance(
            title = R.string.archive_retention,
            options = options,
            values = values,
            selectedIndex = currentIndex,
            requestKey = "retention_selection"
        ).show(supportFragmentManager, at.planqton.fytfm.ui.dialogs.SingleChoiceDialogFragment.TAG)
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
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun downloadAndActivateTemplate(template: at.planqton.fytfm.data.logo.RadioLogoTemplate, parentDialog: AlertDialog, onComplete: () -> Unit) {
        val progressView = layoutInflater.inflate(android.R.layout.simple_list_item_1, null)
        val progressText = progressView.findViewById<TextView>(android.R.id.text1)
        progressText.text = getString(R.string.loading_logos)
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
                    progressText.text = getString(R.string.loading_logos_progress, current, total)
                }
            }

            progressDialog.dismiss()

            radioLogoRepository.saveTemplate(updatedTemplate)
            radioLogoRepository.setActiveTemplate(updatedTemplate.name)

            if (failed.isEmpty()) {
                toast("${updatedTemplate.stations.size} Logos geladen")
            } else {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.logos_loaded_title)
                    .setMessage("${updatedTemplate.stations.size - failed.size}/${updatedTemplate.stations.size} Logos geladen.\n\nFehlgeschlagen:\n${failed.joinToString("\n")}")
                    .setPositiveButton("OK", null)
                    .show()
            }

            onComplete()
        }
    }

    // NOTE: importLogoTemplate functions kept for launcher callback compatibility
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
                toast(getString(R.string.imported_template, template.name))
                logoTemplateImportCallback?.invoke(true)
            } ?: run {
                toast(R.string.file_read_error)
                logoTemplateImportCallback?.invoke(false)
            }
        } catch (e: Exception) {
            toast(getString(R.string.import_failed, e.message))
            logoTemplateImportCallback?.invoke(false)
        }
        logoTemplateImportCallback = null
    }

    /**
     * Zeigt einen Dialog zur manuellen Bildersuche für einen einzelnen Sender.
     * Verwendet DuckDuckGo Image Search.
     */
    private fun showManualLogoSearchDialogInternal(
        station: at.planqton.fytfm.data.RadioStation,
        mode: FrequencyScaleView.RadioMode,
        onComplete: () -> Unit
    ) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_manual_logo_search, null)
        val etSearch = dialogView.findViewById<android.widget.EditText>(R.id.etSearchQuery)
        val btnSearch = dialogView.findViewById<android.widget.ImageButton>(R.id.btnSearch)
        val progressBar = dialogView.findViewById<android.widget.ProgressBar>(R.id.progressBar)
        val tvStatus = dialogView.findViewById<android.widget.TextView>(R.id.tvStatus)
        val rvImages = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvImages)

        // Pre-fill with station name
        val searchTerm = station.name ?: station.ensembleLabel ?: ""
        etSearch.setText("$searchTerm logo")

        // Grid layout for images
        rvImages.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, 3)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setNegativeButton(R.string.cancel, null)
            .create()

        val imageAdapter = at.planqton.fytfm.ui.ImageSearchAdapter { imageResult ->
            // User selected an image - download and save
            dialog.dismiss()
            downloadAndSaveLogoForStation(imageResult.url, station, mode, onComplete)
        }
        rvImages.adapter = imageAdapter

        btnSearch.setOnClickListener {
            val query = etSearch.text.toString().trim()
            if (query.isBlank()) {
                toast(R.string.enter_search_please)
                return@setOnClickListener
            }

            progressBar.visibility = View.VISIBLE
            tvStatus.visibility = View.GONE
            imageAdapter.setImages(emptyList())

            // Search images using DuckDuckGo
            searchImagesWithDuckDuckGo(query) { results ->
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    if (results.isEmpty()) {
                        tvStatus.text = getString(R.string.no_images_found)
                        tvStatus.visibility = View.VISIBLE
                    } else {
                        tvStatus.visibility = View.GONE
                        imageAdapter.setImages(results)
                    }
                }
            }
        }

        // Also search on Enter key
        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                btnSearch.performClick()
                true
            } else false
        }

        dialog.show()
    }

    /**
     * Sucht Bilder mit DuckDuckGo Image Search.
     */
    private fun searchImagesWithDuckDuckGo(query: String, callback: (List<at.planqton.fytfm.ui.ImageResult>) -> Unit) {
        Thread {
            try {
                val results = mutableListOf<at.planqton.fytfm.ui.ImageResult>()
                val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")

                println("fytFM-Search: DuckDuckGo search for: $query")

                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                    .followRedirects(true)
                    .build()

                // Step 1: Get vqd token from DuckDuckGo
                val tokenUrl = "https://duckduckgo.com/?q=$encodedQuery&iax=images&ia=images"
                println("fytFM-Search: Getting token from: $tokenUrl")

                val tokenRequest = okhttp3.Request.Builder()
                    .url(tokenUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.5")
                    .build()

                val tokenResponse = client.newCall(tokenRequest).execute()
                val html = tokenResponse.body?.string() ?: ""
                tokenResponse.close()

                println("fytFM-Search: HTML length: ${html.length}")

                // Extract vqd token - try multiple patterns
                var vqd: String? = null
                val patterns = listOf(
                    Regex("""vqd=["']([^"']+)["']"""),
                    Regex("""vqd=([0-9]+-[0-9]+)"""),
                    Regex(""""vqd":"([^"]+)""""),
                    Regex("""vqd%3D([^&"']+)""")
                )

                for (pattern in patterns) {
                    val match = pattern.find(html)
                    if (match != null) {
                        vqd = match.groupValues[1]
                        println("fytFM-Search: Found vqd with pattern: $vqd")
                        break
                    }
                }

                if (vqd == null) {
                    println("fytFM-Search: No vqd token found, trying direct image extraction")

                    // Fallback: Extract image URLs directly from HTML
                    val imgPattern = Regex(""""ou":"(https?://[^"]+)"""")
                    val imgMatches = imgPattern.findAll(html)

                    for (match in imgMatches.take(30)) {
                        var url = match.groupValues[1]
                            .replace("\\u002F", "/")
                            .replace("\\/", "/")

                        if (url.contains(".jpg") || url.contains(".png") || url.contains(".jpeg") || url.contains(".webp")) {
                            results.add(at.planqton.fytfm.ui.ImageResult(url = url, name = query))
                        }
                    }

                    if (results.isNotEmpty()) {
                        println("fytFM-Search: Found ${results.size} images from HTML")
                        callback(results)
                        return@Thread
                    }
                }

                if (vqd != null) {
                    // Step 2: Fetch images using vqd token
                    val imageUrl = "https://duckduckgo.com/i.js?l=de-de&o=json&q=$encodedQuery&vqd=$vqd&f=,,,,,&p=1"
                    println("fytFM-Search: Fetching images from: $imageUrl")

                    val imageRequest = okhttp3.Request.Builder()
                        .url(imageUrl)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .header("Accept", "application/json")
                        .header("Referer", "https://duckduckgo.com/")
                        .build()

                    val imageResponse = client.newCall(imageRequest).execute()
                    val jsonStr = imageResponse.body?.string() ?: ""
                    imageResponse.close()

                    println("fytFM-Search: JSON response length: ${jsonStr.length}")

                    if (jsonStr.isNotEmpty()) {
                        val json = org.json.JSONObject(jsonStr)
                        val resultsArray = json.optJSONArray("results")

                        if (resultsArray != null) {
                            println("fytFM-Search: Found ${resultsArray.length()} results in JSON")

                            for (i in 0 until minOf(resultsArray.length(), 30)) {
                                val item = resultsArray.getJSONObject(i)
                                val imgUrl = item.optString("image", "")
                                val thumbnail = item.optString("thumbnail", "")
                                val title = item.optString("title", query)

                                // Prefer thumbnail (smaller, faster to load)
                                val finalUrl = if (thumbnail.isNotBlank()) thumbnail else imgUrl

                                if (finalUrl.isNotBlank() && finalUrl.startsWith("http")) {
                                    results.add(at.planqton.fytfm.ui.ImageResult(
                                        url = finalUrl,
                                        name = title
                                    ))
                                }
                            }
                        }
                    }
                }

                println("fytFM-Search: Total results: ${results.size}")
                callback(results)

            } catch (e: Exception) {
                println("fytFM-Search: Error: ${e.message}")
                e.printStackTrace()
                callback(emptyList())
            }
        }.start()
    }


    /**
     * Lädt ein Bild herunter und speichert es als Logo für den Sender.
     * Konvertiert webp/gif automatisch zu PNG.
     */
    private fun downloadAndSaveLogoForStation(
        imageUrl: String,
        station: at.planqton.fytfm.data.RadioStation,
        mode: FrequencyScaleView.RadioMode,
        onComplete: () -> Unit
    ) {
        val progressDialog = android.app.ProgressDialog(this).apply {
            setMessage(getString(R.string.logo_downloading_progress))
            setCancelable(false)
            show()
        }

        Thread {
            try {
                // Download image
                val connection = java.net.URL(imageUrl).openConnection() as java.net.HttpURLConnection
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                connection.connectTimeout = 15000
                connection.readTimeout = 15000

                val inputStream = connection.inputStream
                var bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                inputStream.close()
                connection.disconnect()

                if (bitmap == null) {
                    throw Exception("Bild konnte nicht dekodiert werden")
                }

                // Convert to software bitmap if it's a hardware bitmap
                if (bitmap.config == android.graphics.Bitmap.Config.HARDWARE) {
                    bitmap = bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                }

                // Resize if too large (max 512x512)
                if (bitmap.width > 512 || bitmap.height > 512) {
                    val scale = minOf(512f / bitmap.width, 512f / bitmap.height)
                    val newWidth = (bitmap.width * scale).toInt()
                    val newHeight = (bitmap.height * scale).toInt()
                    bitmap = android.graphics.Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
                }

                // Save as PNG
                val stationName = station.name ?: station.ensembleLabel ?: "Unknown"
                val templateName = radioLogoRepository.getActiveTemplateName() ?: "Manual-Search"

                val logosDir = java.io.File(filesDir, "logos/$templateName")
                if (!logosDir.exists()) logosDir.mkdirs()

                val hash = java.security.MessageDigest.getInstance("MD5")
                    .digest(stationName.toByteArray())
                    .joinToString("") { "%02x".format(it) }
                val logoFile = java.io.File(logosDir, "$hash.png")

                java.io.FileOutputStream(logoFile).use { out ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, out)
                }

                // Add to RadioLogoRepository
                var template = radioLogoRepository.getTemplates().find { it.name == templateName }
                val existingStations = template?.stations?.toMutableList() ?: mutableListOf()

                // Remove existing entry for this station
                existingStations.removeAll { existing ->
                    existing.ps.equals(stationName, ignoreCase = true)
                }

                val stationLogo = at.planqton.fytfm.data.logo.StationLogo(
                    ps = stationName,
                    logoUrl = "local://${logoFile.name}",
                    localPath = logoFile.absolutePath
                )
                existingStations.add(stationLogo)

                val newTemplate = at.planqton.fytfm.data.logo.RadioLogoTemplate(
                    name = templateName,
                    area = template?.area ?: 2,
                    stations = existingStations
                )
                radioLogoRepository.saveTemplate(newTemplate)

                if (radioLogoRepository.getActiveTemplateName() == null) {
                    radioLogoRepository.setActiveTemplate(templateName)
                }

                runOnUiThread {
                    progressDialog.dismiss()
                    toast("Logo für \"$stationName\" gespeichert")
                    loadStationsForCurrentMode()
                    // Indicator und Cover sofort aktualisieren falls aktueller Sender
                    availableCoverSources = getAvailableCoverSources()
                    updateSlideshowIndicators(true)
                    if (stationName.equals(currentDabServiceLabel, ignoreCase = true)) {
                        updateDabCoverDisplay()
                    }
                    onComplete()
                }

            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to download/save logo: ${e.message}", e)
                runOnUiThread {
                    progressDialog.dismiss()
                    toast("Fehler: ${e.message}")
                    onComplete()
                }
            }
        }.start()
    }

    private fun showEditStationDialogInternal(station: at.planqton.fytfm.data.RadioStation, onSave: (String, Boolean) -> Unit) {
        // Create container for name input and toggles
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        // Name input
        val input = android.widget.EditText(this).apply {
            setText(station.name ?: "")
            hint = getString(R.string.station_name_hint)
        }
        container.addView(input)

        // Sync Name checkbox
        val syncNameCheckbox = android.widget.CheckBox(this).apply {
            text = getString(R.string.sync_with_rds_ps)
            isChecked = station.syncName
            setPadding(0, 24, 0, 0)
        }
        container.addView(syncNameCheckbox)

        // Spotify toggle
        val deezerEnabled = presetRepository.isDeezerEnabledForFrequency(station.frequency)
        val deezerSwitch = android.widget.Switch(this).apply {
            text = getString(R.string.deezer_local_search)
            isChecked = deezerEnabled
            setPadding(0, 16, 0, 0)
        }
        container.addView(deezerSwitch)

        AlertDialog.Builder(this)
            .setTitle(R.string.edit_station)
            .setMessage(station.getDisplayFrequency())
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                // Save station name and syncName
                onSave(input.text.toString(), syncNameCheckbox.isChecked)
                // Save Spotify setting for this frequency
                presetRepository.setDeezerEnabledForFrequency(station.frequency, deezerSwitch.isChecked)
                // Update toggle if this is current frequency
                if (Math.abs(binding.frequencyScale.getFrequency() - station.frequency) < 0.05f) {
                    updateDeezerToggleForCurrentFrequency()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showEditDabStationDialogInternal(station: at.planqton.fytfm.data.RadioStation, onSave: (String) -> Unit) {
        // Create container for name input
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        // Info text (Ensemble)
        val infoText = android.widget.TextView(this).apply {
            text = getString(R.string.ensemble_format, station.ensembleLabel ?: getString(R.string.unknown))
            setTextColor(resources.getColor(android.R.color.darker_gray, theme))
            setPadding(0, 0, 0, 16)
        }
        container.addView(infoText)

        // Name input
        val input = android.widget.EditText(this).apply {
            setText(station.name ?: "")
            hint = getString(R.string.station_name_hint)
        }
        container.addView(input)

        AlertDialog.Builder(this)
            .setTitle(R.string.dab_edit_station)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                onSave(input.text.toString())
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showStationScanDialog() {
        // DAB-Modus: eigenen Scan-Dialog
        if (isDabMode) {
            showDabScanDialog()
            return
        }
        // DAB Dev-Modus: Mock-Scan-Dialog
        if (isDabDevMode) {
            showMockDabScanDialog()
            return
        }
        val highSensitivity = presetRepository.isAutoScanSensitivity()

        // Erst ScanOptionsDialog anzeigen für Methode + Filter-Einstellungen
        at.planqton.fytfm.ui.ScanOptionsDialog(this, presetRepository) { config ->
            // Nach Auswahl den eigentlichen Scan-Dialog öffnen
            val dialog = at.planqton.fytfm.ui.StationListDialog(
                this,
                radioScanner,
                onStationsAdded = { stations ->
                    // Gefundene Sender mit bestehenden zusammenführen (Favoriten schützen)
                    val isAM = isAmMode
                    presetRepository.mergeScannedStations(stations, isAM)
                    loadStationsForCurrentMode()
                },
                onStationSelected = { station ->
                    // Station ausgewählt - tune zur Frequenz
                    binding.frequencyScale.setFrequency(station.frequency)
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
        if (!radioController.isDabOn()) {
            val msg = if (!radioController.hasDabTuner()) {
                "Kein DAB+ USB-Dongle gefunden. Bitte anschließen."
            } else {
                "DAB+ Tuner ist nicht aktiv. Bitte zuerst einschalten."
            }
            toast(msg, long = true)
            return
        }

        at.planqton.fytfm.ui.DabScanDialog(this, dabTunerManager, presetRepository) { services ->
            val stations = services.map { it.toRadioStation() }
            presetRepository.mergeDabScannedStations(stations)
            loadStationsForCurrentMode()
            populateCarousel()
            toast(getString(R.string.dab_stations_found, services.size))
        }.show()
    }

    /**
     * Mock DAB+ Sendersuche für UI-Entwicklung.
     */
    private fun showMockDabScanDialog() {
        // Simuliere Scan-Fortschritt
        val progressDialog = android.app.ProgressDialog(this).apply {
            setTitle("DAB Dev Scan")
            setMessage("Simuliere Sendersuche...")
            setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            setCancelable(false)
        }
        progressDialog.show()

        // Mock-Scan starten
        mockDabTunerManager.startScan(object : at.planqton.fytfm.dab.DabScanListener {
            override fun onScanStarted() {
                runOnUiThread {
                    progressDialog.progress = 0
                }
            }

            override fun onScanProgress(progress: Int, currentFrequency: String) {
                runOnUiThread {
                    progressDialog.progress = progress
                    progressDialog.setMessage(getString(R.string.scanning_frequency, currentFrequency))
                }
            }

            override fun onServiceFound(service: at.planqton.fytfm.dab.DabStation) {
                // Optional: Fortschritt anzeigen
            }

            override fun onScanFinished(services: List<at.planqton.fytfm.dab.DabStation>) {
                runOnUiThread {
                    progressDialog.dismiss()

                    // Mock-Stationen speichern
                    val stations = services.map { dabStation ->
                        at.planqton.fytfm.data.RadioStation(
                            frequency = dabStation.ensembleFrequencyKHz.toFloat(),
                            name = dabStation.serviceLabel,
                            rssi = 0,
                            isAM = false,
                            isDab = true,
                            isFavorite = false,
                            serviceId = dabStation.serviceId,
                            ensembleId = dabStation.ensembleId,
                            ensembleLabel = dabStation.ensembleLabel
                        )
                    }
                    presetRepository.saveDabDevStations(stations)
                    loadStationsForCurrentMode()
                    populateCarousel()
                    toast("${services.size} Mock-Sender gefunden")
                }
            }

            override fun onScanError(error: String) {
                runOnUiThread {
                    progressDialog.dismiss()
                    toast(getString(R.string.scan_error, error), long = true)
                }
            }
        })
    }

    private fun loadStationsForCurrentMode() {
        android.util.Log.i(TAG, "=== loadStationsForCurrentMode: mode=$currentMode ===")

        val allStations = when {
            isFmMode -> presetRepository.loadFmStations()
            isAmMode -> presetRepository.loadAmStations()
            isAnyDabMode -> dabStationsForCurrentMode
            else -> presetRepository.loadFmStations()
        }

        android.util.Log.i(TAG, "=== loadStationsForCurrentMode: loaded ${allStations.size} stations for $currentMode ===")

        // Filter anwenden wenn aktiviert
        val stations = if (showFavoritesOnly) {
            allStations.filter { it.isFavorite }
        } else {
            allStations
        }

        stationAdapter.setStations(stations)
        if (isDabMode) stationAdapter.setSelectedDabService(currentDabServiceId)
        else stationAdapter.setSelectedFrequency(binding.frequencyScale.getFrequency())
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
        android.util.Log.d(TAG, "skipToPreviousStation: ${stations.size} stations available")
        if (stations.isEmpty()) return

        if (isAnyDabMode) { skipDabStation(forward = false); return }

        val currentFreq = binding.frequencyScale.getFrequency()
        val prevStation = stations.lastOrNull { it.frequency < currentFreq - 0.05f }
            ?: stations.lastOrNull()

        prevStation?.let {
            android.util.Log.i(TAG, "skipToPreviousStation: ${currentFreq} -> ${it.frequency} MHz")
            val oldFreq = currentFreq
            binding.frequencyScale.setFrequency(it.frequency)
            showStationChangeOverlay(it.frequency, oldFreq)
        }
    }

    private fun doSkipToNext() {
        val stations = stationAdapter.getStations()
        android.util.Log.d(TAG, "skipToNextStation: ${stations.size} stations available")
        if (stations.isEmpty()) return
        if (isAnyDabMode) { skipDabStation(forward = true); return }

        val currentFreq = binding.frequencyScale.getFrequency()
        val nextStation = stations.firstOrNull { it.frequency > currentFreq + 0.05f }
            ?: stations.firstOrNull()

        nextStation?.let {
            android.util.Log.i(TAG, "skipToNextStation: ${currentFreq} -> ${it.frequency} MHz")
            val oldFreq = currentFreq
            binding.frequencyScale.setFrequency(it.frequency)
            showStationChangeOverlay(it.frequency, oldFreq)
        }
    }

    private fun skipDabStation(forward: Boolean) {
        val dabStations = dabStationsForCurrentMode
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
            debugManager.resetDabDebugInfo()
            resetDabNowPlaying()

            val success = if (isDabDevMode) {
                mockDabTunerManager.tuneService(newStation.serviceId, newStation.ensembleId)
            } else {
                radioController.tuneDabService(newStation.serviceId, newStation.ensembleId)
            }
            if (!success) {
                toast(R.string.tuner_error_dab_not_found, long = true)
            }
            updateCarouselSelection()
            updateFavoriteButton()
            // Show station change overlay
            showDabStationChangeOverlay(newStation.serviceId, oldServiceId)
            android.util.Log.i(TAG, "skipDabStation: -> ${newStation.name} (SID=${newStation.serviceId})")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "skipDabStation error: ${e.message}")
            toast(getString(R.string.tuner_error, e.message), long = true)
        }
    }

    private fun showStationChangeOverlay(frequency: Float, oldFrequency: Float = 0f) {
        if (!presetRepository.isShowStationChangeToast()) return

        val isAM = isAmMode

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
            android.util.Log.e(TAG, "Error building stations JSON: ${e.message}")
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
            android.util.Log.e(TAG, "Failed to show overlay: ${e.message}")
        }
    }

    private fun showDabStationChangeOverlay(serviceId: Int, oldServiceId: Int = 0) {
        if (!presetRepository.isShowStationChangeToast()) return

        // Build DAB stations JSON
        val stationsJson = try {
            val jsonArray = org.json.JSONArray()
            dabStationsForCurrentMode.forEach { station ->
                val logoPath = getLogoForDabStation(station.name, station.serviceId)
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
            android.util.Log.e(TAG, "Error building DAB stations JSON: ${e.message}")
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
            android.util.Log.e(TAG, "Failed to show DAB overlay: ${e.message}")
        }
    }

    private fun showPermanentStationOverlay() {
        val frequency = binding.frequencyScale.getFrequency()
        val isAM = isAmMode

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
            android.util.Log.e(TAG, "Error building stations JSON: ${e.message}")
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
            android.util.Log.e(TAG, "Failed to show permanent overlay: ${e.message}")
        }
    }

    private fun hidePermanentStationOverlay() {
        val intent = android.content.Intent(this, StationChangeOverlayService::class.java).apply {
            action = StationChangeOverlayService.ACTION_HIDE_OVERLAY
        }
        try {
            startService(intent)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to hide overlay: ${e.message}")
        }
    }

    /**
     * Handler für Frequenzänderungen von FrequencyScaleView.
     * Aktualisiert UI, MediaService, speichert Frequenz und tuned Hardware.
     */
    private fun handleFrequencyChange(frequency: Float) {
        updateFrequencyDisplay(frequency)
        stationAdapter.setSelectedFrequency(frequency)

        // Clear RDS data on frequency change for fresh data
        rdsManager.clearRds()
        rtCombiner?.clearAll()
        updateDeezerDebugInfo("Waiting...", null, null, null, null)
        lastDisplayedTrackId = null

        val isAM = isAmMode
        rdsLogRepository.onStationChange(frequency, isAM)

        // Get saved station for this frequency
        val stations = if (isAM) presetRepository.loadAmStations() else presetRepository.loadFmStations()
        val savedStation = stations.find { Math.abs(it.frequency - frequency) < 0.05f }
        val savedStationName = savedStation?.name
        val radioLogoPath = radioLogoRepository.getLogoForStation(savedStationName, null, frequency)

        // Update Now Playing bar with station info
        resetNowPlayingBarForStation(savedStationName, radioLogoPath, frequency, isAM)

        // Update MediaService
        android.util.Log.d(TAG, "Station change: freq=$frequency, stationName=$savedStationName, radioLogoPath=$radioLogoPath")
        FytFMMediaService.instance?.updateMetadata(
            frequency = frequency,
            ps = savedStationName,
            rt = null,
            isAM = isAM,
            coverUrl = null,
            localCoverPath = null,
            radioLogoPath = radioLogoPath
        )

        tuneToFrequency(frequency)
        saveLastFrequency(frequency)
        updateFavoriteButton()
        updateDeezerToggleForCurrentFrequency()

        if (isCarouselMode) {
            updateCarouselSelection()
            startCarouselReturnTimer()
        }
    }

    private fun updateFrequencyDisplay(frequency: Float) {
        binding.tvFrequency.text = when {
            isFmMode -> String.format("FM %.2f", frequency)
            isAmMode -> String.format("AM %d", frequency.toInt())
            isAnyDabMode -> dabStationsForCurrentMode.find { it.serviceId == currentDabServiceId }?.name
                ?: if (isDabDevMode) "DAB Dev" else "DAB+"
            else -> String.format("FM %.2f", frequency)
        }
        if (!isAnyDabMode) debugManager.updateDebugInfo(freq = frequency)
    }

    private fun setupRadioModeSpinner() {
        updateRadioModeSpinnerAdapter()

        binding.spinnerRadioMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                android.util.Log.i(TAG, "=== SPINNER onItemSelected: position=$position, currentMode=$currentMode, suppressCallback=$suppressSpinnerCallback ===")

                if (suppressSpinnerCallback) {
                    android.util.Log.i(TAG, "=== SPINNER: Callback suppressed, skipping ===")
                    suppressSpinnerCallback = false
                    return
                }

                val dabDevEnabled = presetRepository.isDabDevModeEnabled()
                val newMode = when {
                    position == 0 -> FrequencyScaleView.RadioMode.FM
                    position == 1 -> FrequencyScaleView.RadioMode.AM
                    position == 2 -> FrequencyScaleView.RadioMode.DAB
                    position == 3 && dabDevEnabled -> FrequencyScaleView.RadioMode.DAB_DEV
                    else -> FrequencyScaleView.RadioMode.FM
                }

                android.util.Log.i(TAG, "=== SPINNER: User selected $newMode (current: $currentMode) ===")

                // Nur Info-Toast wenn Tuner nicht verfügbar (blockiert nicht mehr)
                if (!isTunerAvailable(newMode)) {
                    toast(getString(R.string.tuner_not_available, newMode))
                }

                setRadioMode(newMode)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /**
     * Updates the radio mode spinner adapter based on DAB Dev Mode setting.
     */
    private fun updateRadioModeSpinnerAdapter() {
        val dabDevEnabled = presetRepository.isDabDevModeEnabled()
        android.util.Log.i(TAG, "updateRadioModeSpinnerAdapter: dabDevEnabled=$dabDevEnabled")
        val modes = mutableListOf("FM", "AM", "DAB+")
        if (dabDevEnabled) {
            modes.add("DAB Dev")
        }
        android.util.Log.i(TAG, "updateRadioModeSpinnerAdapter: modes=$modes")
        val adapter = ArrayAdapter(this, R.layout.item_radio_mode_spinner, modes)
        adapter.setDropDownViewResource(R.layout.item_radio_mode_dropdown)
        binding.spinnerRadioMode.adapter = adapter
    }

    /**
     * Prüft ob ein bestimmter Tuner verfügbar ist.
     */
    private fun isTunerAvailable(mode: FrequencyScaleView.RadioMode): Boolean {
        return when (mode) {
            FrequencyScaleView.RadioMode.FM, FrequencyScaleView.RadioMode.AM -> FmNative.isLibraryLoaded()
            FrequencyScaleView.RadioMode.DAB -> radioController.isDabAvailable()
            FrequencyScaleView.RadioMode.DAB_DEV -> true  // Mock tuner is always available
        }
    }

    /**
     * Prüft ob der gewünschte Modus verfügbar ist und gibt einen alternativen zurück falls nicht.
     */
    private fun setRadioMode(mode: FrequencyScaleView.RadioMode, forceRefresh: Boolean = false) {
        val oldMode = binding.frequencyScale.getMode()
        android.util.Log.i(TAG, "=== setRadioMode: $oldMode -> $mode (forceRefresh=$forceRefresh) ===")

        if (oldMode == mode && !forceRefresh) {
            android.util.Log.i(TAG, "=== setRadioMode: Same mode, skipping ===")
            loadStationsForCurrentMode()
            if (isCarouselMode) {
                populateCarousel()
                updateCarouselSelection()
            }
            return
        }

        // 1. Alten Modus aufräumen
        cleanupOldRadioMode(oldMode)

        // 2. Mode setzen (wichtig für populateCarousel)
        binding.frequencyScale.setMode(mode)
        android.util.Log.i(TAG, "=== setRadioMode: mode is now ${binding.frequencyScale.getMode()} ===")

        // 3. Button-Sichtbarkeiten aktualisieren
        updateRecordButtonVisibility()
        updateEpgButtonVisibility()
        binding.viewModeToggle.visibility = View.VISIBLE

        // 4. Gespeicherte View-Einstellung wiederherstellen
        restoreViewModeForRadioMode(mode)

        // 5. Neuen Modus initialisieren
        initializeNewRadioMode(mode)

        // 6. UI aktualisieren
        updateUiAfterModeChange(mode)
    }

    private fun updateModeSpinner() {
        val position = when {
            isFmMode -> 0
            isAmMode -> 1
            isDabMode -> 2
            isDabDevMode -> 3
            else -> 0
        }
        // Nur supprimieren wenn sich die Position wirklich ändert
        if (binding.spinnerRadioMode.selectedItemPosition != position) {
            suppressSpinnerCallback = true
            binding.spinnerRadioMode.setSelection(position, false)
        }
        // Fallback: Nach kurzer Zeit wieder freigeben falls Callback verzögert kommt
        binding.spinnerRadioMode.removeCallbacks(resetSuppressRunnable)
        binding.spinnerRadioMode.postDelayed(resetSuppressRunnable, 150)
    }

    private val resetSuppressRunnable = Runnable {
        if (suppressSpinnerCallback) {
            android.util.Log.w(TAG, "=== SPINNER: Resetting suppressCallback via timeout ===")
            suppressSpinnerCallback = false
        }
    }

    private fun updatePlayPauseButton() {
        val iconRes = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        binding.btnPlayPause.setImageResource(iconRes)
        binding.pipLayout.pipBtnPlayPause.setImageResource(iconRes)
    }

    /**
     * Setzt den Mute-Status für alle Audio-Ausgänge.
     * Nutzt drei Methoden für maximale Kompatibilität auf FYT Head Units.
     */
    private fun setRadioMuteState(mute: Boolean) {
        // Method 1: sys.radio.mute system property
        try {
            val systemPropertiesClass = Class.forName("android.os.SystemProperties")
            val setMethod = systemPropertiesClass.getMethod("set", String::class.java, String::class.java)
            setMethod.invoke(null, "sys.radio.mute", if (mute) "1" else "0")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to set sys.radio.mute: ${e.message}")
        }
        // Method 2: SyuJniNative
        if (SyuJniNative.isLibraryLoaded()) {
            SyuJniNative.getInstance().muteAmp(mute)
        }
        // Method 3: RadioController (FmNative)
        radioController.setMute(mute)
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
        if (isDabMode) { toggleDabPower(); return }
        // DAB Dev-Modus: Mock-Tuner Power-Steuerung
        if (isDabDevMode) { toggleMockDabPower(); return }

        android.util.Log.i(TAG, "======= toggleRadioPower() =======")
        android.util.Log.i(TAG, "FmNative.isLibraryLoaded: ${FmNative.isLibraryLoaded()}")
        android.util.Log.i(TAG, "TWUtil available: ${twUtil?.isAvailable}")

        try {
            if (isRadioOn) {
                // Radio ausschalten
                android.util.Log.i(TAG, "--- Powering OFF ---")
                stopRdsPolling()
                radioController.powerOffFmAmFull()
                isRadioOn = false
                isPlaying = true  // Reset to "playing" state for next power on
                updatePlayPauseButton()
                FytFMMediaService.instance?.updatePlaybackState(false)
            } else {
                // Radio einschalten via Controller
                val frequency = binding.frequencyScale.getFrequency()
                android.util.Log.i(TAG, "--- Powering ON at $frequency MHz ---")

                isRadioOn = radioController.powerOnFmAmFull(frequency)
                android.util.Log.i(TAG, "isRadioOn = $isRadioOn")

                if (isRadioOn) {
                    // UI-Updates
                    isPlaying = true
                    updatePlayPauseButton()
                    FytFMMediaService.instance?.updatePlaybackState(true)
                    // RDS-Polling mit erweiterter Logik starten
                    startRdsPolling()
                } else {
                    android.util.Log.e(TAG, "RADIO FAILED TO START!")
                    toast(R.string.tuner_error_fm_start, long = true)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Radio power toggle failed: ${e.message}", e)
            isRadioOn = false
            toast(getString(R.string.tuner_error, e.message), long = true)
        }
        android.util.Log.i(TAG, "======= toggleRadioPower() done =======")
        updatePowerButton()
    }

    /**
     * Wendet die gespeicherten Tuner-Settings an (LOC, Mono, Area)
     */
    private fun applyTunerSettings() = radioController.applyTunerSettings()

    /**
     * Spielt einen Tick-Sound beim Senderwechsel (wenn aktiviert).
     */
    override fun playTickSound() {
        if (!presetRepository.isTickSoundEnabled()) return

        try {
            val volume = presetRepository.getTickSoundVolume() / 100f
            val toneGenerator = android.media.ToneGenerator(
                android.media.AudioManager.STREAM_MUSIC,
                (volume * 100).toInt()
            )
            toneGenerator.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 50)
            // ToneGenerator nach kurzer Zeit freigeben
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                toneGenerator.release()
            }, 100)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "playTickSound failed: ${e.message}")
        }
    }

    /**
     * Tune zu einer Frequenz.
     */
    private fun tuneToFrequency(frequency: Float) {
        android.util.Log.d(TAG, "Tuning to $frequency MHz")

        // Tick-Sound abspielen
        playTickSound()

        // Cancel any pending Deezer search from previous station
        fmDeezerSearchJob?.cancel()
        fmDeezerSearchJob = null

        // Parser-Cache zurücksetzen bei Frequenzwechsel
        lastParsedFmRt = null

        try {
            // UI-Frequenz setzen für AF-Vergleich
            rdsManager.setUiFrequency(frequency)
            rdsManager.tune(frequency)
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "tune failed: ${e.message}")
            toast(getString(R.string.tuner_error, e.message), long = true)
        }
    }

    /**
     * Seek zum nächsten/vorherigen Sender mit Signal.
     * Manueller Seek da native seek() JNI-Bugs hat.
     */
    private fun seekToStation(seekUp: Boolean) {
        val currentFreq = binding.frequencyScale.getFrequency()
        android.util.Log.i(TAG, "Seek ${if (seekUp) "UP" else "DOWN"} from $currentFreq MHz")

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
                        binding.tvFrequency.text = "%.1f".format(displayFreq)
                        binding.frequencyScale.setFrequencyVisualOnly(displayFreq)
                    }

                    // Tune und RSSI messen (2x für Stabilität)
                    radioController.tuneRaw(freq)
                    Thread.sleep(100)
                    val rssi1 = radioController.getRssi()
                    Thread.sleep(50)
                    val rssi2 = radioController.getRssi()

                    android.util.Log.d(TAG, "Seek: %.1f MHz -> RSSI %d/%d".format(freq, rssi1, rssi2))

                    // Beide Messungen müssen im gültigen Bereich sein
                    if (rssi1 in rssiMin..rssiMax && rssi2 in rssiMin..rssiMax) {
                        foundFreq = freq
                        android.util.Log.i(TAG, "Seek found: %.1f MHz (RSSI: %d/%d)".format(freq, rssi1, rssi2))
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
                        binding.frequencyScale.setFrequency(foundFreq)
                        saveLastFrequency(foundFreq)
                    } else {
                        android.util.Log.w(TAG, "Seek: No station found")
                        // Zurück zur ursprünglichen Frequenz
                        binding.frequencyScale.setFrequency(currentFreq)
                    }
                }
            } catch (e: Throwable) {
                android.util.Log.e(TAG, "Seek failed: ${e.message}")
                runOnUiThread {
                    binding.frequencyScale.setFrequency(currentFreq)
                }
            }
        }.start()
    }

    private fun updatePowerButton() {
        val isOn = if (isAnyDabMode) isDabOn else isRadioOn
        binding.btnPower.alpha = if (isOn) 1.0f else 0.5f
    }

    /**
     * DAB+ Tuner Ein/Aus schalten.
     */
    private fun toggleDabPower() {
        android.util.Log.i(TAG, "======= toggleDabPower() =======")

        try {
            if (isDabOn) {
                // DAB ausschalten via Controller
                android.util.Log.i(TAG, "--- DAB Powering OFF via controller ---")
                stopDabVisualizer()
                radioController.powerOffDab()
                isDabOn = false
                currentDabServiceId = 0
                currentDabEnsembleId = 0
            } else {
                // DAB einschalten via Controller
                android.util.Log.i(TAG, "--- DAB Powering ON via controller ---")
                val success = radioController.powerOnDab()
                isDabOn = success

                if (!success) {
                    toast(R.string.dab_init_failed, long = true)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "DAB power toggle failed: ${e.message}", e)
            isDabOn = false
            toast(getString(R.string.tuner_error, e.message), long = true)
        }

        android.util.Log.i(TAG, "======= toggleDabPower() done, isDabOn=$isDabOn =======")
        updatePowerButton()
    }

    /**
     * Mock DAB+ Tuner Ein/Aus schalten (für UI-Entwicklung ohne Hardware).
     */
    private fun toggleMockDabPower() {
        android.util.Log.i(TAG, "======= toggleMockDabPower() =======")

        try {
            if (isDabOn) {
                // Mock DAB ausschalten
                android.util.Log.i(TAG, "--- Mock DAB Powering OFF ---")
                mockDabTunerManager.stopService()
                mockDabTunerManager.deinitialize()
                isDabOn = false
                currentDabServiceId = 0
                currentDabEnsembleId = 0
            } else {
                // Mock DAB einschalten - Callbacks sind bereits in setupMockDabCallbacks() gesetzt
                android.util.Log.i(TAG, "--- Mock DAB Powering ON ---")
                val success = mockDabTunerManager.initialize(this)
                isDabOn = success

                if (!success) {
                    toast(R.string.mock_dab_init_failed, long = true)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Mock DAB power toggle failed: ${e.message}", e)
            isDabOn = false
            toast(getString(R.string.tuner_error, e.message), long = true)
        }

        android.util.Log.i(TAG, "======= toggleMockDabPower() done, isDabOn=$isDabOn =======")
        updatePowerButton()
    }

    /**
     * Lädt den Favoriten-Filter-Status für den aktuellen Modus (FM/AM)
     */
    private fun loadFavoritesFilterState() {
        showFavoritesOnly = when {
            isFmMode -> presetRepository.isShowFavoritesOnlyFm()
            isAmMode -> presetRepository.isShowFavoritesOnlyAm()
            else -> presetRepository.isShowFavoritesOnlyDab()
        }
        updateFolderButton()
    }

    /**
     * Startet oder stoppt die DAB+ Aufnahme
     */
    private fun toggleDabRecording() {
        if (radioController.isDabRecording()) {
            radioController.stopDabRecording()
        } else {
            val recordingPath = presetRepository.getDabRecordingPath()
            if (recordingPath == null) {
                toast(R.string.select_storage_first)
                return
            }
            val success = radioController.startDabRecording(this, recordingPath)
            if (!success) {
                toast(R.string.recording_could_not_start)
            }
        }
    }

    /**
     * Aktualisiert das Record-Button Icon basierend auf dem Recording-Status
     */
    private fun updateRecordingButton(isRecording: Boolean) {
        if (isRecording) {
            dabListRecordBtn?.setImageResource(R.drawable.ic_stop_record)
            btnCarouselRecord?.setImageResource(R.drawable.ic_stop_record)
            // Blinken starten
            startRecordingBlink()
        } else {
            dabListRecordBtn?.setImageResource(R.drawable.ic_record_inactive)
            btnCarouselRecord?.setImageResource(R.drawable.ic_record_inactive)
            // Blinken stoppen
            stopRecordingBlink()
        }
    }

    /**
     * Startet das Blinken des Record-Buttons während der Aufnahme
     */
    private fun startRecordingBlink() {
        if (recordingBlinkHandler == null) {
            recordingBlinkHandler = android.os.Handler(android.os.Looper.getMainLooper())
        }
        var visible = true
        recordingBlinkRunnable = object : Runnable {
            override fun run() {
                val alpha = if (visible) 1.0f else 0.3f
                dabListRecordBtn?.alpha = alpha
                btnCarouselRecord?.alpha = alpha
                visible = !visible
                recordingBlinkHandler?.postDelayed(this, 500)
            }
        }
        recordingBlinkHandler?.post(recordingBlinkRunnable!!)
    }

    /**
     * Stoppt das Blinken des Record-Buttons
     */
    private fun stopRecordingBlink() {
        recordingBlinkRunnable?.let { recordingBlinkHandler?.removeCallbacks(it) }
        recordingBlinkRunnable = null
        dabListRecordBtn?.alpha = 1.0f
        btnCarouselRecord?.alpha = 1.0f
    }

    /**
     * Zeigt/versteckt die Record-Buttons basierend auf der Konfiguration und dem Modus
     */
    private fun updateRecordButtonVisibility() {
        val isRecordingConfigured = presetRepository.isDabRecordingEnabled()
        dabListRecordBtn?.visibility = if (isRecordingConfigured) View.VISIBLE else View.GONE
        btnCarouselRecord?.visibility = if (isRecordingConfigured && isDabMode) View.VISIBLE else View.GONE
    }

    /**
     * Zeigt/versteckt die EPG-Buttons basierend auf dem Modus
     */
    private fun updateEpgButtonVisibility() {
        // DAB List Mode - EPG immer sichtbar im DAB-Modus
        dabListEpgBtn?.visibility = View.VISIBLE

        // Carousel Mode - nur im DAB-Modus sichtbar
        btnCarouselEpg?.visibility = if (isDabMode) View.VISIBLE else View.GONE
    }

    /**
     * Zeigt den EPG-Dialog für den aktuellen DAB-Sender
     */
    private fun showEpgDialog() {
        val stationName = currentDabServiceLabel ?: "DAB+"
        val epgData = radioController.getDabEpgData()

        epgDialog = EpgDialog(this)
        epgDialog?.show(epgData, stationName)
    }

    /**
     * Aktualisiert den EPG-Dialog wenn er offen ist
     */
    private fun updateEpgDialog(epgData: EpgData?) {
        if (epgDialog?.isShowing() == true) {
            epgDialog?.updateEpgData(epgData)
        }
    }

    /**
     * Aktualisiert das Herz-Icon basierend auf dem aktuellen Sender
     */
    private fun updateFavoriteButton() {
        val isFavorite = if (isAnyDabMode) {
            currentDabServiceId > 0 && presetRepository.isDabFavorite(currentDabServiceId)
        } else {
            presetRepository.isFavorite(binding.frequencyScale.getFrequency(), isAmMode)
        }
        binding.btnFavorite.setImageResource(if (isFavorite) R.drawable.ic_favorite_filled else R.drawable.ic_favorite_border)
    }

    /**
     * Aktualisiert das Folder-Icon basierend auf dem Filter-Status
     */
    private fun updateFolderButton() {
        val iconRes = if (showFavoritesOnly) R.drawable.ic_folder else R.drawable.ic_folder_all
        binding.btnFolder.setImageResource(iconRes)
        binding.tvAllList.text = if (showFavoritesOnly) "Favoriten" else "Alle Sender"
    }

    /**
     * Favorisiert/Unfavorisiert den aktuellen Sender
     */
    private fun toggleCurrentStationFavorite() {
        if (isAnyDabMode) {
            if (currentDabServiceId > 0) presetRepository.toggleDabFavorite(currentDabServiceId) else return
        } else {
            presetRepository.toggleFavorite(binding.frequencyScale.getFrequency(), isAmMode)
        }
        updateFavoriteButton()
        loadStationsForCurrentMode()
    }

    /**
     * Schaltet zwischen "Alle Sender" und "Nur Favoriten" um
     */
    private fun toggleFavoritesFilter() {
        showFavoritesOnly = !showFavoritesOnly

        // Filter-Status speichern (FM, AM und DAB getrennt)
        when {
            isFmMode -> presetRepository.setShowFavoritesOnlyFm(showFavoritesOnly)
            isAmMode -> presetRepository.setShowFavoritesOnlyAm(showFavoritesOnly)
            else -> presetRepository.setShowFavoritesOnlyDab(showFavoritesOnly)
        }

        // UI aktualisieren
        updateFolderButton()
        loadStationsForCurrentMode()

        // Toast anzeigen
        val message = if (showFavoritesOnly) "Nur Favoriten" else "Alle Sender"
        toast(message)
    }

    // === Key Event Handler für Lenkradtasten ===

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        android.util.Log.i(TAG, "=== dispatchKeyEvent: keyCode=${event.keyCode}, action=${event.action}, source=${event.source} ===")
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        android.util.Log.d(TAG, "onKeyDown: keyCode=$keyCode")

        return when (keyCode) {
            android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> {
                android.util.Log.i(TAG, "MEDIA_NEXT pressed")
                toast("NEXT")
                seekToStation(seekUp = true)
                true
            }
            android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                android.util.Log.i(TAG, "MEDIA_PREVIOUS pressed")
                toast("PREV")
                seekToStation(seekUp = false)
                true
            }
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY,
            android.view.KeyEvent.KEYCODE_MEDIA_PAUSE,
            android.view.KeyEvent.KEYCODE_HEADSETHOOK -> {
                android.util.Log.i(TAG, "PLAY_PAUSE pressed")
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
        stopDlsTimestampUpdates()
        stopRecordingBlink()
        twUtil?.close()
        updateRepository.destroy()
        rdsLogRepository.destroy()
        // steeringWheelKeyManager?.unregister()  // Fallback disabled
        syuToolkitManager?.disconnect()
        // Cleanup ParserLogger listeners
        parserLogListener?.let {
            at.planqton.fytfm.deezer.ParserLogger.removeFmListener(it)
            at.planqton.fytfm.deezer.ParserLogger.removeDabListener(it)
        }
        // Radio NICHT ausschalten - läuft im MediaService weiter (auch im Sleep)
        // fmNative.powerOff() wird nur vom User manuell ausgelöst
    }

    private fun saveLastFrequency(frequency: Float) {
        val prefs = getSharedPreferences("fytfm_prefs", Context.MODE_PRIVATE)
        val key = if (isAmMode) "last_frequency_am" else "last_frequency_fm"
        prefs.edit().putFloat(key, frequency).putFloat("last_frequency", frequency).apply()
    }

    private fun loadLastFrequency(): Float {
        val prefs = getSharedPreferences("fytfm_prefs", Context.MODE_PRIVATE)
        val (key, default) = if (isAmMode) "last_frequency_am" to 1000f else "last_frequency_fm" to 90.4f
        val modeSpecific = prefs.getFloat(key, -1f)
        return if (modeSpecific > 0) modeSpecific else prefs.getFloat("last_frequency", default)
    }

    private fun saveLastRadioMode(mode: FrequencyScaleView.RadioMode) {
        val prefs = getSharedPreferences("fytfm_prefs", Context.MODE_PRIVATE)
        val modeString = when (mode) {
            FrequencyScaleView.RadioMode.FM -> "FM"
            FrequencyScaleView.RadioMode.AM -> "AM"
            FrequencyScaleView.RadioMode.DAB -> "DAB"
            FrequencyScaleView.RadioMode.DAB_DEV -> "DAB_DEV"
        }
        android.util.Log.i(TAG, "=== SAVING MODE: $modeString ===")
        prefs.edit().putString("last_radio_mode", modeString).apply()
    }

    private fun loadLastRadioMode(): FrequencyScaleView.RadioMode {
        val prefs = getSharedPreferences("fytfm_prefs", Context.MODE_PRIVATE)
        return when (prefs.getString("last_radio_mode", "FM")) {
            "AM" -> FrequencyScaleView.RadioMode.AM
            "DAB" -> FrequencyScaleView.RadioMode.DAB
            "DAB_DEV" -> if (presetRepository.isDabDevModeEnabled()) FrequencyScaleView.RadioMode.DAB_DEV else FrequencyScaleView.RadioMode.FM
            else -> FrequencyScaleView.RadioMode.FM
        }
    }

    /**
     * Alten Radio-Modus aufräumen bevor gewechselt wird.
     */
    private fun cleanupOldRadioMode(oldMode: FrequencyScaleView.RadioMode) {
        if (oldMode == FrequencyScaleView.RadioMode.DAB) {
            stopDlsTimestampUpdates()
            android.util.Log.i(TAG, "Stopping DAB tuner via controller...")
            if (isDabOn) {
                radioController.powerOffDab()
                isDabOn = false
                currentDabServiceId = 0
                currentDabEnsembleId = 0
            }
            debugManager.resetDebugToRds()
        } else if (oldMode == FrequencyScaleView.RadioMode.DAB_DEV) {
            stopDlsTimestampUpdates()
            android.util.Log.i(TAG, "Stopping Mock DAB tuner...")
            if (isDabOn) {
                try {
                    mockDabTunerManager.stopService()
                    mockDabTunerManager.deinitialize()
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Mock DAB shutdown error: ${e.message}")
                }
                isDabOn = false
                currentDabServiceId = 0
                currentDabEnsembleId = 0
            }
            debugManager.resetDebugToRds()
        } else {
            android.util.Log.i(TAG, "Stopping FM/AM radio...")
            stopRdsPolling()
            if (isRadioOn) {
                radioController.powerOffFmAm()
                isRadioOn = false
            }
        }
    }

    /**
     * Neuen Radio-Modus initialisieren.
     */
    private fun initializeNewRadioMode(mode: FrequencyScaleView.RadioMode) {
        if (mode == FrequencyScaleView.RadioMode.DAB) {
            initDabDisplay()
            startDlsTimestampUpdates()
            android.util.Log.i(TAG, "Starting DAB tuner...")
            if (!isDabOn) {
                toggleDabPower()
            }
        } else if (mode == FrequencyScaleView.RadioMode.DAB_DEV) {
            initDabDisplay()
            startDlsTimestampUpdates()
            android.util.Log.i(TAG, "Starting Mock DAB tuner...")
            if (!isDabOn) {
                toggleMockDabPower()
            }
        } else {
            updateSlideshowIndicators(false)
            updateDeezerWatermarks(false)
            android.util.Log.i(TAG, "Starting FM/AM radio...")
            if (!isRadioOn) {
                val lastFreq = loadLastFrequency()
                val success = radioController.powerOnFmAmFull(lastFreq)
                if (success) {
                    isRadioOn = true
                    binding.frequencyScale.setFrequency(lastFreq)
                } else {
                    toast(R.string.tuner_error_fm_start, long = true)
                }
            }
        }
    }

    /**
     * Gespeicherte View-Einstellung für Radio-Modus wiederherstellen.
     */
    private fun restoreViewModeForRadioMode(mode: FrequencyScaleView.RadioMode) {
        val savedCarouselForMode = presetRepository.isCarouselModeForRadioMode(mode.name)
        if (savedCarouselForMode != isCarouselMode) {
            isCarouselMode = savedCarouselForMode
            presetRepository.setCarouselMode(savedCarouselForMode)
            if (savedCarouselForMode) {
                btnViewModeEqualizer?.background = null
                btnViewModeImage?.setBackgroundResource(R.drawable.toggle_selected)
            } else {
                btnViewModeEqualizer?.setBackgroundResource(R.drawable.toggle_selected)
                btnViewModeImage?.background = null
            }
        }
    }

    /**
     * UI nach Moduswechsel aktualisieren.
     */
    private fun updateUiAfterModeChange(mode: FrequencyScaleView.RadioMode) {
        updateModeSpinner()
        loadFavoritesFilterState()
        loadStationsForCurrentMode()

        if (isCarouselMode) {
            populateCarousel()
            updateCarouselSelection()
            binding.controlBar.visibility = View.VISIBLE
            binding.stationBar.visibility = View.VISIBLE
        } else if (mode == FrequencyScaleView.RadioMode.DAB || mode == FrequencyScaleView.RadioMode.DAB_DEV) {
            mainContentArea?.visibility = View.GONE
            dabListContentArea?.visibility = View.VISIBLE
            dabListStationStrip?.visibility = View.GONE
            binding.controlBar.visibility = View.GONE
            binding.stationBar.visibility = View.VISIBLE
            populateDabListMode()
            updateDabListModeSelection()
        } else {
            mainContentArea?.visibility = View.VISIBLE
            dabListContentArea?.visibility = View.GONE
            binding.controlBar.visibility = View.VISIBLE
            binding.stationBar.visibility = View.VISIBLE
        }

        updateFavoriteButton()
        updatePowerButton()
        saveLastRadioMode(mode)
    }

    private fun saveLastDabService(serviceId: Int, ensembleId: Int) {
        val prefs = getSharedPreferences("fytfm_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putInt("last_dab_service_id", serviceId)
            .putInt("last_dab_ensemble_id", ensembleId)
            .apply()
        android.util.Log.i(TAG, "=== SAVING DAB SERVICE: serviceId=$serviceId, ensembleId=$ensembleId ===")
    }

    private fun loadLastDabService(): Pair<Int, Int> {
        val prefs = getSharedPreferences("fytfm_prefs", Context.MODE_PRIVATE)
        val serviceId = prefs.getInt("last_dab_service_id", 0)
        val ensembleId = prefs.getInt("last_dab_ensemble_id", 0)
        android.util.Log.i(TAG, "=== LOADING DAB SERVICE: serviceId=$serviceId, ensembleId=$ensembleId ===")
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

        android.util.Log.i(TAG, "checkAndOfferStationImport: fmStations.size=${fmStations.size}, hasAsked=$hasAsked")

        if (fmStations.isEmpty() && !hasAsked) {
            android.util.Log.i(TAG, "checkAndOfferStationImport: Zeige Import-Dialog")
            showImportStationsDialog()
        } else {
            android.util.Log.d(TAG, "checkAndOfferStationImport: Dialog nicht nötig")
        }
    }

    private fun showImportStationsDialog() {
        at.planqton.fytfm.ui.dialogs.ImportStationsDialogFragment.newInstance()
            .show(supportFragmentManager, at.planqton.fytfm.ui.dialogs.ImportStationsDialogFragment.TAG)
    }

    /**
     * Import stations from the original FYT radio app via SYU Service Callbacks.
     * Uses Type 4 (preset frequencies) and Type 14 (preset names) callbacks.
     */
    private fun importStationsFromOriginalApp() {
        android.util.Log.i(TAG, "Import: Starte Preset-Sammlung via SYU Callbacks...")
        toast(R.string.collecting_syu_stations)

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
            android.util.Log.e(TAG, "Import: Error querying presets", e)
        }
    }

    /**
     * Called after timeout to finish collecting presets and save them.
     */
    private fun finishPresetCollection() {
        isCollectingPresets = false
        presetImportTimeoutRunnable = null

        android.util.Log.i(TAG, "Import: Sammlung beendet, ${collectedPresets.size} Presets empfangen")

        if (collectedPresets.isEmpty()) {
            toast(R.string.no_stations_from_original, long = true)
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
            toast(R.string.no_valid_stations_found, long = true)
            return
        }

        // Save and reload
        presetRepository.saveFmStations(stations)
        loadStationsForCurrentMode()

        toast(getString(R.string.stations_imported, stations.size))
        android.util.Log.i(TAG, "Import: ${stations.size} Sender gespeichert")

        // Log imported stations
        stations.forEach { station ->
            android.util.Log.d(TAG, "Import: ${station.frequency} MHz - ${station.name ?: "(kein Name)"}")
        }
    }

    // ========== CRASH HANDLER ==========

    private fun checkForCrashReport() {
        if (!CrashHandler.hasCrashLog(this)) return
        CrashHandler.getCrashLog(this) ?: return

        at.planqton.fytfm.ui.dialogs.CrashReportDialogFragment.newInstance()
            .show(supportFragmentManager, at.planqton.fytfm.ui.dialogs.CrashReportDialogFragment.TAG)
    }

    private fun showCrashBugReportDialog(crashLog: String) {
        at.planqton.fytfm.ui.dialogs.InputDialogFragment.newInstance(
            title = R.string.create_bug_report_title,
            message = R.string.bug_report_describe,
            hint = R.string.crash_hint,
            requestKey = "crash_description"
        ).show(supportFragmentManager, at.planqton.fytfm.ui.dialogs.InputDialogFragment.TAG)
    }

    private fun createCrashBugReport(crashLog: String, userDescription: String) {
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
            currentFrequency = binding.frequencyScale.getFrequency(),
            spotifyStatus = currentDeezerStatus,
            spotifyOriginalRt = currentDeezerOriginalRt,
            spotifyStrippedRt = currentDeezerStrippedRt,
            spotifyQuery = currentDeezerQuery,
            spotifyTrackInfo = currentDeezerTrackInfo,
            userDescription = userDescription.ifEmpty { null },
            crashLog = crashLog
        )

        val reportContent = bugReportHelper.buildReportContent(appState)
        showBugReportPreview(reportContent, isCrashReport = true)
    }

    private fun showCrashLogPreview(crashLog: String) {
        showBugReportPreview(crashLog, isCrashReport = true)
    }

    private fun showBugReportPreview(content: String, isCrashReport: Boolean = false) {
        pendingBugReportContent = content
        pendingBugReportIsCrash = isCrashReport
        at.planqton.fytfm.ui.dialogs.TextPreviewDialogFragment.newInstance(
            title = R.string.report_preview_title,
            content = content,
            requestKey = "bug_report_preview"
        ).show(supportFragmentManager, at.planqton.fytfm.ui.dialogs.TextPreviewDialogFragment.TAG)
    }

    private fun saveBugReportWithPicker(content: String, isCrashReport: Boolean) {
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
        val prefix = if (isCrashReport) "fytfm_crash_report" else "fytfm_bugreport"
        val fileName = "${prefix}_$timestamp.txt"
        pendingBugReportContent = content
        pendingBugReportIsCrash = isCrashReport
        bugReportExportLauncher.launch(fileName)
    }

    // ========== SettingsCallback Implementation ==========

    override fun onRadioEditorRequested(mode: FrequencyScaleView.RadioMode) {
        showRadioEditorDialogFragment(mode)
    }

    override fun onLogoTemplateRequested(onDismiss: () -> Unit) {
        showLogoTemplateDialogFragment(onDismiss)
    }

    override fun onLanguageDialogRequested(onSelected: (String) -> Unit) {
        showLanguageDialog(onSelected)
    }

    override fun onNowPlayingAnimationRequested(onSelected: (Int) -> Unit) {
        showNowPlayingAnimationDialog(onSelected)
    }

    override fun onCorrectionsViewerRequested() {
        showCorrectionsViewerDialogFragment()
    }

    override fun onRdsRetentionDialogRequested(onSelected: (Int) -> Unit) {
        showRdsRetentionDialog(onSelected)
    }

    override fun onRadioAreaDialogRequested(onSelected: (Int) -> Unit) {
        showRadioAreaDialog(onSelected)
    }

    override fun onDeezerCacheDialogRequested() {
        showDeezerCacheDialogFragment()
    }

    override fun onBugReportRequested() {
        showBugReportDialogFragment()
    }

    override fun onCloseAppRequested() {
        closeApp()
    }

    override fun onDarkModeChanged(mode: Int) {
        applyDarkMode(mode)
    }

    override fun onDebugVisibilityChanged(show: Boolean) {
        updateDebugOverlayVisibility()
    }

    override fun onLocalModeChanged(enabled: Boolean) {
        fmNative?.setLocalMode(enabled)
    }

    override fun onMonoModeChanged(enabled: Boolean) {
        fmNative?.setMonoMode(enabled)
    }

    override fun onDabVisualizerToggled(enabled: Boolean) {
        updateDabVisualizerSettings()
        if (enabled && isDabOn && currentDabServiceId > 0) {
            startDabVisualizer()
        } else if (!enabled) {
            stopDabVisualizer()
        }
    }

    override fun onDabVisualizerStyleChanged(style: Int) {
        dabVisualizerView?.setStyle(style)
    }

    override fun onRecordingPathRequested() {
        recordingFolderPickerLauncher.launch(null)
    }

    override fun onRadioModeSpinnerNeedsUpdate() {
        updateRadioModeSpinnerAdapter()
        if (!presetRepository.isDabDevModeEnabled() && isDabDevMode) {
            setRadioMode(FrequencyScaleView.RadioMode.FM)
        }
    }

    override fun onStationChangeToastToggled(enabled: Boolean) {
        // Preference already saved by ViewModel
    }

    override fun onStationListNeedsRefresh() {
        loadStationsForCurrentMode()
    }

    override fun getActiveLogoTemplateName(): String? {
        return radioLogoRepository.getActiveTemplateName()
    }

    override fun getLogoTemplateCount(name: String): Int {
        return radioLogoRepository.getTemplates().find { it.name == name }?.stations?.size ?: 0
    }

    override fun getCurrentRadioMode(): FrequencyScaleView.RadioMode {
        return binding.frequencyScale.getMode()
    }

    override fun onDabCoverDisplayNeedsUpdate() {
        if (isDabMode) {
            updateDabCoverDisplay()
        }
    }

    override fun isRdsLoggingEnabled(): Boolean {
        return rdsLogRepository.loggingEnabled
    }

    override fun setRdsLoggingEnabled(enabled: Boolean) {
        rdsLogRepository.loggingEnabled = enabled
    }

    override fun getRdsRetentionDays(): Int {
        return rdsLogRepository.retentionDays
    }

    override fun clearRdsArchive() {
        rdsLogRepository.clearAll()
    }

    override fun getDeezerCacheCount(): Int {
        return deezerCache?.getCacheStats()?.first ?: 0
    }

    override fun clearDeezerCache() {
        deezerCache?.clearCache()
    }

    override fun onRecordingPathCallbackSet(callback: (String?) -> Unit) {
        recordingPathCallback = callback
    }

    override fun getPresetRepository(): at.planqton.fytfm.data.PresetRepository {
        return presetRepository
    }

    override fun getUpdateRepository(): at.planqton.fytfm.data.UpdateRepository {
        return updateRepository
    }
}
