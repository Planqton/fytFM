package at.planqton.fytfm

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.content.res.ColorStateList
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.lifecycleScope
import at.planqton.fytfm.databinding.ActivityMainBinding
import at.planqton.fytfm.ui.archive.ArchiveOverlayController
import at.planqton.fytfm.ui.cover.CoverDisplayController
import at.planqton.fytfm.ui.cover.CoverViewTrio
import at.planqton.fytfm.ui.cover.DabCoverSource
import at.planqton.fytfm.ui.dialogs.AreaTemplateDialogFragment
import at.planqton.fytfm.ui.dialogs.RadioAreaDialogFragment
import at.planqton.fytfm.ui.edit.EditDabStationDialogFragment
import at.planqton.fytfm.ui.edit.EditStationDialogFragment
import at.planqton.fytfm.ui.lifecycle.AutoBackgroundController
import at.planqton.fytfm.ui.logosearch.ManualLogoSearchDialogFragment
import at.planqton.fytfm.ui.logosearch.StationLogoDownloader
import at.planqton.fytfm.ui.logotemplate.LogoTemplateDownloader
import at.planqton.fytfm.ui.overlay.StationOverlayRenderer
import at.planqton.fytfm.ui.pip.PipController
import at.planqton.fytfm.viewmodel.DeezerState
import at.planqton.fytfm.viewmodel.RadioUiState
import at.planqton.fytfm.viewmodel.RadioViewModel
import at.planqton.fytfm.viewmodel.RadioViewModelFactory
import androidx.appcompat.app.AppCompatDelegate
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.widget.SeekBar
import at.planqton.fytfm.data.PresetRepository
import at.planqton.fytfm.data.UpdateRepository
import at.planqton.fytfm.data.UpdateState
import at.planqton.fytfm.data.rdslog.RdsLogRepository
import at.planqton.fytfm.data.rdslog.RdsDatabase
import at.planqton.fytfm.data.rdslog.RtCorrection
import at.planqton.fytfm.data.rdslog.RtCorrectionDao
import at.planqton.fytfm.media.FytFMMediaService
import at.planqton.fytfm.ui.StationAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.android.fmradio.FmNative
import com.android.fmradio.FmService
import com.syu.jni.SyuJniNative
import at.planqton.fytfm.deezer.DeezerClient
import at.planqton.fytfm.deezer.DeezerCache
import at.planqton.fytfm.deezer.RtCombiner
import at.planqton.fytfm.deezer.TrackInfo
import at.planqton.fytfm.steering.SyuToolkitManager
import at.planqton.fytfm.dab.DabTunerManager
import at.planqton.fytfm.dab.MockDabTunerManager
import at.planqton.fytfm.dab.EpgData
import at.planqton.fytfm.ui.EpgDialog
import android.widget.AdapterView
import android.widget.ArrayAdapter
import coil.load
import at.planqton.fytfm.ui.helper.applyRoundedCorners
import at.planqton.fytfm.ui.helper.loadCover
import at.planqton.fytfm.ui.helper.loadCoverOrFallback

class MainActivity : AppCompatActivity(),
    at.planqton.fytfm.ui.dlslog.DlsLogDialogFragment.DlsLogCallback,
    at.planqton.fytfm.ui.settings.SettingsDialogFragment.SettingsCallback,
    at.planqton.fytfm.ui.settings.AccentColorEditorDialogFragment.Callback,
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
    /**
     * Audio-bearing controllers leben jetzt in [FytFMApplication] und
     * überleben darum Activity-Recreate (Theme-Switch, Konfigurationsänderungen).
     * Hier nur noch lesende Property-Delegation — keine Initialisierung.
     */
    private val app: FytFMApplication get() = application as FytFMApplication
    private val dabTunerManager: DabTunerManager get() = app.dabTunerManager
    private val mockDabTunerManager: at.planqton.fytfm.dab.MockDabTunerManager get() = app.mockDabTunerManager
    /** Phase B: VM/Controller-driven. The DabController owns the DAB
     *  power state for both real and mock backends since the mock-DAB
     *  routing through DabController landed; reading from the controller
     *  is now the single source of truth. */
    private val isDabOn: Boolean
        get() = radioController.isDabOn()
    /** Phase B: DabController owns the current service/ensemble IDs. It
     *  writes them both predictively (in tuneService, before the tuner
     *  confirms) and reactively (in onServiceStarted), and pre-populates
     *  from prefs in initialize(). MainActivity just reads. */
    private val currentDabServiceId: Int
        get() = radioController.dabController.currentServiceId
    private val currentDabEnsembleId: Int
        get() = radioController.dabController.currentEnsembleId
    // Phase B: serviceLabel and ensembleLabel are now read directly from
    // the ViewModel's dabState — VM updates them via handleDabServiceStarted
    // before MainActivity's collector runs, so reads are always fresh.
    // Computed properties keep all the existing read sites unchanged.
    private val currentDabServiceLabel: String?
        get() = (radioViewModel.uiState.value as? RadioUiState.Dab)?.dabState?.serviceLabel
    private val currentDabEnsembleLabel: String?
        get() = (radioViewModel.uiState.value as? RadioUiState.Dab)?.dabState?.ensembleLabel
    private val currentDabDls: String?
        get() = (radioViewModel.uiState.value as? RadioUiState.Dab)?.dabState?.dls
    /** Local snapshot of the last DLS [handleDabDynamicLabelBase] processed
     *  — needed because the VM updates dabState.dls *before* MainActivity's
     *  collector runs, so reading [currentDabDls] inside the handler always
     *  matches the new value (defeating change detection). */
    private var lastDlsSeenByMainActivity: String? = null
    private var lastDlsTimestamp: Long = 0L  // Timestamp when last DLS was received
    private var lastDeezerSearchedDls: String? = null  // Cache to avoid duplicate Deezer searches
    private var lastParsedDls: String? = null  // Cache for RT-DLS Parser log (DAB+)
    private var lastParsedFmRt: String? = null  // Cache for RT-DLS Parser log (FM)
    /** Phase B: VM-driven via deezerState.coverPath / coverSourceKey
     *  (cleared on DabServiceStarted by VM, set via vm.updateDeezerTrack). */
    private val currentDabDeezerCoverPath: String?
        get() = (radioViewModel.uiState.value as? RadioUiState.Dab)?.deezerState?.coverPath
    private val currentDabDeezerCoverDls: String?
        get() = (radioViewModel.uiState.value as? RadioUiState.Dab)?.deezerState?.coverSourceKey
    private var lastLoggedDls: String? = null  // Cache to avoid duplicate DLS log entries
    private val dlsLogEntries = mutableListOf<String>()  // DLS Log entries
    /** Phase B: VM-driven via dabState.slideshow (cleared on DabServiceStarted). */
    private val currentDabSlideshow: android.graphics.Bitmap?
        get() = (radioViewModel.uiState.value as? RadioUiState.Dab)?.dabState?.slideshow

    // Cover source state (coverDisplayController.selectedCoverSourceIndex, coverDisplayController.availableCoverSources,
    // coverDisplayController.coverSourceLocked, coverDisplayController.lockedCoverSource, coverDisplayController.currentUiCoverSource) now lives
    // in coverDisplayController — see below.
    private var suppressSpinnerCallback = false
    private val swcLogEntries = mutableListOf<String>()
    // Three debug-info polling loops — were Handler+Runnable pairs that
    // had to be hand-unregistered in onPause/onDestroy. Now coroutines
    // tied to lifecycleScope, which cancels them automatically when the
    // Activity is destroyed (onPause cancels manually so polling halts
    // when the user navigates away — same semantics as before).
    private var tunerInfoUpdateJob: Job? = null
    private var uiInfoUpdateJob: Job? = null
    /** Owns the parser-log debug overlay (state, tab buttons, log refresh,
     *  export). Lazy because it depends on [binding] which is set in onCreate. */
    private val parserOverlayBinder by lazy {
        at.planqton.fytfm.ui.debug.ParserOverlayBinder(
            binding = binding,
            context = this,
            onExportRequested = { filename, content ->
                pendingParserLogExport = content
                parserLogExportLauncher.launch(filename)
            },
        )
    }

    // DLS Timestamp Update Timer
    private var dlsTimestampUpdateJob: Job? = null

    // Auto-Background — state owned by AutoBackgroundController
    private var wasStartedFromBoot = false
    private val autoBackground by lazy {
        AutoBackgroundController(this, presetRepository) { moveTaskToBack(true) }
    }

    // Steering Wheel Key Handler
    private var syuToolkitManager: SyuToolkitManager? = null

    // Preset Import via SYU Service Callbacks
    private var isCollectingPresets = false
    private val collectedPresets = mutableMapOf<Int, Pair<Float, String?>>()  // index -> (freq, name)
    private var presetImportTimeoutJob: Job? = null

    // Now Playing, Carousel views now via binding (except CoverDots which are separate)
    private var nowPlayingCoverDots: LinearLayout? = null
    private var carouselCoverDots: LinearLayout? = null
    // dabListCoverDots / dabListDeezerWatermark live on DabListViewBinder.
    private val dabListCoverDots: LinearLayout? get() = dabListView.dabListCoverDots
    private val dabListDeezerWatermark: TextView? get() = dabListView.dabListDeezerWatermark

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

    // DAB List Mode views + button wiring + swipe gestures live in DabListViewBinder.
    // MainActivity reads view refs via the binder when other components need them.
    private val dabListView: at.planqton.fytfm.ui.dablist.DabListViewBinder by lazy {
        at.planqton.fytfm.ui.dablist.DabListViewBinder(
            activity = this,
            binding = binding,
            presetRepository = presetRepository,
            callbacks = at.planqton.fytfm.ui.dablist.DabListViewBinder.Callbacks(
                getCurrentDabServiceId = { currentDabServiceId },
                getDabStationsForCurrentMode = { dabStationsForCurrentMode },
                getCurrentDabSlideshow = { currentDabSlideshow },
                getCoverDisplayController = { coverDisplayController },
                getShowFavoritesOnly = { showFavoritesOnly },
                getLogoForDabStation = { name, sid -> getLogoForDabStation(name, sid) },
                toggleCurrentDabFavorite = { sid ->
                    if (isDabDevMode) presetRepository.toggleDabDevFavorite(sid)
                    else presetRepository.toggleDabFavorite(sid)
                },
                tuneToDabStation = { sid, eid -> tuneToDabStation(sid, eid) },
                toggleDabCover = { toggleDabCover() },
                setupCoverLongPressListener = { v -> setupCoverLongPressListener(v) },
                loadStationsForCurrentMode = { loadStationsForCurrentMode() },
                toggleFavoritesFilter = { toggleFavoritesFilter() },
                showStationScanDialog = { showStationScanDialog() },
                showSettingsDialogFragment = { showSettingsDialogFragment() },
                toggleDabRecording = { toggleDabRecording() },
                updateRecordButtonVisibility = { updateRecordButtonVisibility() },
                showEpgDialog = { showEpgDialog() },
                updateEpgButtonVisibility = { updateEpgButtonVisibility() },
                setRadioMode = { mode -> setRadioMode(mode) },
            ),
        )
    }
    // Read-only accessors so the rest of MainActivity (CoverDisplayController providers,
    // slideshow handler, Deezer paths) can keep using familiar names.
    private val dabListCover: ImageView? get() = dabListView.dabListCover
    private val dabListRecordBtn: ImageButton? get() = dabListView.dabListRecordBtn
    private val dabListEpgBtn: ImageButton? get() = dabListView.dabListEpgBtn
    private var recordingBlinkJob: Job? = null
    private val dabVisualizerView: at.planqton.fytfm.ui.AudioVisualizerView? get() = dabListView.dabVisualizerView
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

    // Carousel auto-centering — three coupled jobs sharing
    // [carouselPendingCenterPosition] as their pause/finish flag:
    // - centerJob: 2 s deadline that smooth-scrolls to the new selection
    // - returnJob: 2 s deadline that returns to the current station after
    //   manual scroll
    // - debugUpdateJob: 100 ms tick that refreshes the debug overlay
    //   while either of the above is pending
    private var carouselCenterJob: Job? = null
    private var carouselReturnJob: Job? = null
    private var carouselDebugUpdateJob: Job? = null
    private var carouselCenterTimerStart: Long = 0
    private var carouselPendingCenterPosition: Int = -1

    // Debug: Internet-Simulation deaktivieren
    var debugInternetDisabled = false
        private set

    // Debug: Spotify/Local blockieren (nur RDS anzeigen)
    var debugDeezerBlocked = false
        private set

    // Application-scoped (siehe FytFMApplication) — überleben Activity-Recreate.
    // `getPresetRepository()` ist via SettingsCallback-Interface schon als
    // Java-Style-Getter belegt → @JvmName umbenennen, damit der property-
    // Getter nicht JVM-seitig kollidiert.
    @get:JvmName("presetRepositoryKtAccessor")
    private val presetRepository: at.planqton.fytfm.data.PresetRepository get() = app.presetRepository
    private val radioController: at.planqton.fytfm.controller.RadioController get() = app.radioController

    // Passive VM — observes radioController.events and projects onto uiState.
    // MUST NOT be accessed before initRadioController() (factory captures the
    // lateinit radioController at first access).
    private val radioViewModel: RadioViewModel by viewModels {
        RadioViewModelFactory(radioController, presetRepository)
    }

    private val stationOverlay by lazy { StationOverlayRenderer(this, presetRepository) }

    // Dispatches the same cover operation to the 3 always-in-sync ImageViews.
    // dabListCover is inflated on demand, so it's read via provider lambda.
    private val coverTrio by lazy {
        CoverViewTrio(
            nowPlaying = binding.nowPlayingCover,
            carousel = binding.carouselNowPlayingCover,
            dabListProvider = { dabListCover },
        )
    }

    private val coverDisplayController by lazy {
        CoverDisplayController(
            context = this,
            coverTrio = coverTrio,
            dotContainersProvider = {
                // Compact = kleine Thumbnail-Cover (Now-Playing-Bar, Carousel-Bar).
                // Standard = großes dabListCover, dort wirken normale Dots passend.
                listOf(
                    at.planqton.fytfm.ui.cover.CoverDisplayController.DotContainerSpec(nowPlayingCoverDots, compact = true),
                    at.planqton.fytfm.ui.cover.CoverDisplayController.DotContainerSpec(carouselCoverDots, compact = true),
                    at.planqton.fytfm.ui.cover.CoverDisplayController.DotContainerSpec(dabListCoverDots, compact = false),
                )
            },
            deezerWatermarkProvider = {
                listOf(binding.nowPlayingDeezerWatermark, binding.carouselDeezerWatermark, dabListDeezerWatermark)
            },
            presetRepository = presetRepository,
            radioLogoPathProvider = { getLogoForDabStation(currentDabServiceLabel, currentDabServiceId) },
            currentSlideshowProvider = { currentDabSlideshow },
            currentDeezerCoverPathProvider = { currentDabDeezerCoverPath },
            onLocalCoverImageLoaded = { path -> loadCoverImage(path) },
            mediaSessionSync = { snap ->
                FytFMMediaService.instance?.updateDabMetadata(
                    serviceLabel = currentDabServiceLabel,
                    ensembleLabel = currentDabEnsembleLabel,
                    dls = currentDabDls,
                    slideshowBitmap = snap.slideshowBitmap,
                    radioLogoPath = snap.radioLogoPath,
                    deezerCoverPath = snap.deezerCoverPath,
                )
            },
        )
    }

    private val pipController by lazy {
        PipController(
            pipBinding = binding.pipLayout,
            frequencyStep = { if (isFmMode) presetRepository.getFmFrequencyStep() else presetRepository.getAmFrequencyStep() },
            frequencyGetter = { binding.frequencyScale.getFrequency() },
            frequencySetter = { freq -> binding.frequencyScale.setFrequency(freq) },
            onPlayPauseClicked = { binding.btnPlayPause.performClick() },
        )
    }

    private lateinit var stationAdapter: StationAdapter
    private val fmNative: FmNative get() = app.fmNative
    private val rdsManager: RdsManager get() = app.rdsManager
    private val lastSyncedPs = mutableMapOf<Int, String>()  // FreqKey -> letzter gesyncter PS
    private lateinit var radioScanner: at.planqton.fytfm.scanner.RadioScanner
    private lateinit var updateRepository: UpdateRepository
    private lateinit var rdsLogRepository: RdsLogRepository
    private lateinit var radioLogoRepository: at.planqton.fytfm.data.logo.RadioLogoRepository
    private lateinit var debugManager: at.planqton.fytfm.debug.DebugManager
    private lateinit var updateBadge: View
    private var settingsUpdateListener: ((UpdateState) -> Unit)? = null
    private val twUtil: TWUtilHelper? get() = app.twUtil

    // Deezer Integration
    private var deezerClient: DeezerClient? = null
    private var deezerCache: DeezerCache? = null
    private var rtCombiner: RtCombiner? = null

    // RDS UI Update Cache - Verhindert unnötige UI-Updates wenn sich nichts geändert hat
    private val lastDisplayedRt = mutableMapOf<Int, String>()  // PI -> letztes angezeigtes RT

    // Bug Report: Aktuelle Deezer-Status-Daten
    /** Phase B: VM-driven via deezerState. Updated atomically by
     *  [vm.updateDeezerDebugInfo] from RtCombiner's onDebugUpdate callback.
     *  Computed-property reads avoid touching call sites. */
    private val currentDeezerState: at.planqton.fytfm.viewmodel.DeezerState?
        get() = when (val s = radioViewModel.uiState.value) {
            is RadioUiState.FmAm -> s.deezerState
            is RadioUiState.Dab -> s.deezerState
            else -> null
        }
    private val currentDeezerStatus: String? get() = currentDeezerState?.debugStatus
    private val currentDeezerOriginalRt: String? get() = currentDeezerState?.debugOriginalRt
    private val currentDeezerStrippedRt: String? get() = currentDeezerState?.debugStrippedRt
    private val currentDeezerQuery: String? get() = currentDeezerState?.lastQuery
    private val currentDeezerTrackInfo: TrackInfo? get() = currentDeezerState?.currentTrack

    // Deezer Cache Export/Import launchers + their callbacks were dead —
    // the SettingsDialogFragment never wires Cache export/import buttons to
    // .launch() these. DeezerCache.exportToZip/importFromZip themselves are
    // still tested (Bug 1.1 regression coverage) and ready to wire up.

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

    /** Phase B: derived from VM `isMuted`. The semantic is "user wants audio
     *  audible" — true when not muted. For [RadioUiState.Off] we return true
     *  because that matches the historical default after a power-off (the
     *  power-off paths explicitly reset `vm.setMuted(false)` to keep the
     *  pause icon from sticking after the user unmutes-then-powers-off). */
    private val isPlaying: Boolean
        get() = when (val s = radioViewModel.uiState.value) {
            is RadioUiState.FmAm -> !s.isMuted
            is RadioUiState.Dab -> !s.isMuted
            else -> true
        }
    /** Phase B: FmAmController owns isRadioOn (set in powerOn/powerOff/catch).
     *  MainActivity reads it via this computed property to keep one source of
     *  truth. Note: this is FM/AM-specific; for "any radio on", use the
     *  mode-aware [radioController.isRadioOn] which routes to [isDabOn] in DAB. */
    private val isRadioOn: Boolean
        get() = radioController.fmAmController.isRadioOn
    /** Phase B: VM-driven via stationListState.showFavoritesOnly.
     *  Mode-specific persistence happens inside vm.toggleFavoritesOnlyForMode /
     *  loadFavoritesFilterForMode. */
    private val showFavoritesOnly: Boolean
        get() = radioViewModel.stationListState.value.showFavoritesOnly

    // Archive UI — state & handlers owned by ArchiveOverlayController (init lazy;
    // first access happens after layout inflation in onCreate).
    private val archiveOverlayController: ArchiveOverlayController by lazy {
        ArchiveOverlayController(
            binding = binding.archiveOverlay,
            rdsLogRepository = rdsLogRepository,
            fragmentManager = supportFragmentManager,
            coroutineScope = lifecycleScope,
        )
    }

    // Deezer search jobs (to cancel on station change)
    private var dabDeezerSearchJob: Job? = null
    private var fmDeezerSearchJob: Job? = null

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
                android.util.Log.e(TAG, "Screenshot failed: ${e.message}", e)
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
        // Da `uiMode` nicht mehr in configChanges steht, recreated Android
        // die Activity automatisch beim Theme-Wechsel — alle theme-bezogenen
        // Resources (Backgrounds, Textfarben, Drawables) werden frisch
        // aufgelöst. Audio läuft weiter, weil RadioController + TWUtil in
        // FytFMApplication leben (siehe FytFMApplication-Doc).
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
        // Must be initialized before super.onCreate(): on a config-change
        // recreate, FragmentManager restores SettingsDialogFragment during
        // super.onCreate() and its onAttach() reads updateRepository via
        // SettingsCallback. Initializing it later (in initRepositories())
        // crashed with UninitializedPropertyAccessException.
        updateRepository = UpdateRepository(this)
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

        // Load last radio mode from canonical source (RadioController persists in fytfm_fmam).
        // DAB_DEV is gated behind a setting — fall back to FM if user disabled dev mode.
        val lastMode = radioController.currentMode.let {
            if (it == FrequencyScaleView.RadioMode.DAB_DEV && !presetRepository.isDabDevModeEnabled()) {
                FrequencyScaleView.RadioMode.FM
            } else it
        }
        android.util.Log.i(TAG, "=== APP START: lastMode = $lastMode ===")

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

        // FmAmController loaded its persisted frequency during initialize().
        val lastFreq = radioController.getCurrentFrequency()
        if (lastMode != FrequencyScaleView.RadioMode.DAB) {
            binding.frequencyScale.setFrequency(lastFreq)
            rdsManager.setUiFrequency(lastFreq)  // Für AF-Vergleich
            rdsLogRepository.setInitialFrequency(lastFreq, lastMode == FrequencyScaleView.RadioMode.AM)
            updateFrequencyDisplay(lastFreq)
        }
        updateModeSpinner()
        loadFavoritesFilterState()
        loadStationsForCurrentMode()
        updateDemoSignalLossButtonVisibility()
        // Signal-Icon initialer Status (zeigt "none" wenn Radio off)
        updateSignalBars(0, -1)

        // 4. Mode-spezifische Initialisierung
        // Both DAB modes go through setupDabModeOnStartup; the backend is
        // selected here so DabController never tunes the wrong tuner type.
        if (lastMode == FrequencyScaleView.RadioMode.DAB) {
            radioController.useRealDabBackend()
            setupDabModeOnStartup()
        } else if (lastMode == FrequencyScaleView.RadioMode.DAB_DEV) {
            radioController.useMockDabBackend()
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

        // 6. Autoplay für FM/AM (DAB-Modi haben ihren eigenen Autoplay-Pfad
        // in setupDabModeOnStartup; toggleRadioPower hier würde im DAB_DEV-Mode
        // den eben eingeschalteten Mock-Tuner direkt wieder ausschalten, weil
        // isRadioOn nur FM/AM-spezifisch ist).
        if (!isRadioOn &&
            lastMode != FrequencyScaleView.RadioMode.DAB &&
            lastMode != FrequencyScaleView.RadioMode.DAB_DEV &&
            presetRepository.isAutoplayAtStartup()) {
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
            android.util.Log.e(TAG, "Failed to start overlay service: ${e.message}", e)
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
                    // Persist via VM — readers (bug-report builder, MediaSession update,
                    // debug overlay) reach the same state through computed properties.
                    radioViewModel.updateDeezerDebugInfo(status, originalRt, strippedRt, query, trackInfo)
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

                        if (isAnyDabMode) {
                            // DAB / DAB Demo Modus - Neues Deezer Cover gefunden
                            radioViewModel.updateDeezerTrack(trackInfo, localCover, currentDabDls)

                            // Auto-Modus aktivieren -> höchste Priorität (Deezer) wird gewählt
                            coverDisplayController.selectedCoverSourceIndex = -1
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
                            coverDisplayController.currentUiCoverSource = localCover

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
                    // Signal-Bars-Icon
                    updateSignalBars(rssi, rdsManager.rssiAgeMs)
                }
            }

            override fun onAfSwitch(rawFreq: Int) {
                runOnUiThread { handleAfSwitch(rawFreq) }
            }
        })
    }

    /**
     * Setzt das Signal-Bars-Icon (`ivFmSignalIcon`) basierend auf RSSI.
     *
     * Sichtbar nur in FM/AM (DAB hat eigenes Icon). Honoriert die per-Modus-
     * Toggles `signal_icon_enabled_fm` / `signal_icon_enabled_am`.
     *
     * Bei abgeschaltetem Radio oder fehlendem/zu altem RSSI bleibt das Icon
     * **sichtbar** und zeigt `none` (durchgestrichene Balken) — der User
     * sieht „kein Empfang" statt eines verschwundenen Indikators.
     *
     * Schwellwerte (Chip-Range 25–245):
     *  - <25 / >245 / Alter >5 s → none
     *  - 25..79  → 1 Balken
     *  - 80..139 → 2 Balken
     *  - ≥140    → 3 Balken
     */
    private fun updateSignalBars(rssi: Int, ageMs: Long) {
        val icon = binding.ivFmSignalIcon
        val carouselIcon = binding.ivCarouselSignalIcon
        val enabled = when {
            isFmMode -> presetRepository.isSignalIconEnabledFm()
            isAmMode -> presetRepository.isSignalIconEnabledAm()
            else -> false
        }
        if (!enabled) {
            icon.visibility = View.GONE
            carouselIcon.visibility = View.GONE
            return
        }
        val drawableRes = when {
            !isRadioOn -> R.drawable.ic_signal_bars_none
            ageMs < 0L || ageMs > 5000L -> R.drawable.ic_signal_bars_none
            rssi < 25 || rssi > 245 -> R.drawable.ic_signal_bars_none
            rssi < 80 -> R.drawable.ic_signal_bars_weak
            rssi < 140 -> R.drawable.ic_signal_bars_half
            else -> R.drawable.ic_signal_bars_full
        }
        val tint = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.radio_text_secondary))
        icon.setImageResource(drawableRes)
        ImageViewCompat.setImageTintList(icon, tint)
        icon.visibility = View.VISIBLE
        carouselIcon.setImageResource(drawableRes)
        ImageViewCompat.setImageTintList(carouselIcon, tint)
        carouselIcon.visibility = View.VISIBLE
    }

    /**
     * Spiegelt die DAB-Empfangs-Qualität auf das Carousel-Signal-Icon
     * (UI 2). Drawable-Logik identisch zu DabListViewBinder.updateSignalIndicator
     * — same Quellen (sync + quality), gleiche 3-Bar-Mapping.
     */
    private fun updateCarouselDabSignalIcon(sync: Boolean, quality: String) {
        val icon = binding.ivCarouselSignalIcon
        if (!presetRepository.isSignalIconEnabledDab()) {
            icon.visibility = View.GONE
            return
        }
        val drawableRes = if (!sync) {
            R.drawable.ic_signal_bars_none
        } else when (quality.lowercase()) {
            "best" -> R.drawable.ic_signal_bars_full
            "good" -> R.drawable.ic_signal_bars_half
            "okay" -> R.drawable.ic_signal_bars_weak
            else -> R.drawable.ic_signal_bars_none
        }
        icon.setImageResource(drawableRes)
        ImageViewCompat.setImageTintList(
            icon,
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.radio_text_secondary))
        )
        icon.visibility = View.VISIBLE
    }

    /**
     * Reagiert auf AF-Switch-Events vom FM-Chip (Event 9).
     * Der User bleibt visuell auf seiner gewählten Frequenz, sieht aber als
     * kleinen Hinweis welche AF gerade tatsächlich gespielt wird.
     */
    private fun handleAfSwitch(rawFreq: Int) {
        if (!isFmMode || !isRadioOn) {
            binding.tvAfIndicator.visibility = View.GONE
            return
        }
        if (rawFreq <= 0) {
            binding.tvAfIndicator.visibility = View.GONE
            return
        }
        val afMhz = if (rawFreq > 10000) rawFreq / 100.0f else rawFreq / 10.0f
        val userFreq = binding.frequencyScale.getFrequency()
        if (Math.abs(afMhz - userFreq) < 0.05f) {
            binding.tvAfIndicator.visibility = View.GONE
            return
        }
        binding.tvAfIndicator.text = getString(R.string.af_active_indicator, afMhz)
        binding.tvAfIndicator.visibility = View.VISIBLE
        android.util.Log.i(TAG, "AF Switch: chip retuned to %.1f MHz (user is on %.1f)".format(afMhz, userFreq))
    }

    private fun stopRdsPolling() {
        rdsManager.stopPolling()
        FmService.clearRds()
        binding.tvAfIndicator.visibility = View.GONE
        // Signal-Icon bleibt im FM/AM sichtbar als "kein Empfang" (none-Bars).
        updateSignalBars(0, -1)
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

        debugManager.setRdsHeaderTitle("RDS Debug")
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
        autoBackground.startIfNeeded(wasStartedFromBoot)
        // Akzentfarbe anwenden (Banner-Tint etc.) — beim Wieder-Aufrufen
        // gilt evtl. eine andere Day/Night-Variante.
        applyCurrentAccentColor()
        // After a config-change recreate (z.B. Day/Night-Wechsel), the new
        // AudioVisualizerView instance has no Visualizer attached — the
        // dabAudioStartedEvents one-shot already fired pre-recreate. Rebind
        // here using the live session ID from the controller.
        if (isAnyDabMode && isDabOn && presetRepository.isDabVisualizerEnabled()) {
            startDabVisualizer()
        }
    }

    override fun onPause() {
        super.onPause()
        isAppInForeground = false
        autoBackground.cancel()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        autoBackground.onUserInteraction()
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
     * Initialisiert Activity-scoped Repositories (Logos, Updates, RDS-Log).
     * Audio-bearing instanzen (presetRepository, fmNative, rdsManager,
     * radioController, twUtil) leben jetzt in [FytFMApplication] und sind
     * beim ersten Zugriff bereits da — siehe Property-Delegation oben.
     */
    private fun initRepositories() {
        // Cover Source Lock laden
        coverDisplayController.coverSourceLocked = presetRepository.isCoverSourceLocked()
        val savedLockedSource = presetRepository.getLockedCoverSource()
        coverDisplayController.lockedCoverSource = if (savedLockedSource != null) {
            try { DabCoverSource.valueOf(savedLockedSource) } catch (e: Exception) { null }
        } else null

        // Root-Fallback Listener für UIS7870/DUDU7 Geräte. Wird bei jedem
        // Activity-onCreate neu gesetzt (single-listener API) — alte
        // Activity-Refs sind dann tot, das neue Lambda gewinnt.
        rdsManager.setRootRequiredListener {
            runOnUiThread { toast(R.string.root_required_message, long = true) }
        }

        radioScanner = at.planqton.fytfm.scanner.RadioScanner(rdsManager)
        // updateRepository is now initialized in onCreate() before super.onCreate()
        // so it's available when restored fragments call back into the activity.
        rdsLogRepository = RdsLogRepository(this)
        rdsLogRepository.performCleanup()
        radioLogoRepository = at.planqton.fytfm.data.logo.RadioLogoRepository(this)
    }

    /**
     * RadioController-Wiring auf Activity-Seite. Der Controller selbst lebt
     * in [FytFMApplication] — hier hängen wir nur die Activity-spezifischen
     * Event-Subscriber an (lifecycleScope sorgt für Auto-Detach bei onDestroy).
     */
    private fun initRadioController() {
        setupRadioControllerDabCallbacks()
        // Phase A: activate RadioViewModel. First access triggers the factory and
        // the VM's init starts collecting radioController.events. No rendering is
        // migrated yet — this just keeps the VM alive so future callers can
        // observe uiState. See umstrukturierung.md §6 Phase A.
        android.util.Log.d(TAG, "RadioViewModel wired: ${radioViewModel.uiState.value::class.simpleName}")
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

        // TWUtil wird in FytFmApp.onCreate() einmalig geöffnet; hier nur die
        // statische DebugReceiver-Bridge nachreichen.
        DebugReceiver.setTwUtil(twUtil)

        setAsPreferredRadioApp()
    }

    /**
     * DAB-spezifische Initialisierung beim App-Start.
     */
    private fun setupDabModeOnStartup() {
        startDlsTimestampUpdates()
        // DabController.initialize() pre-populates currentServiceId/EnsembleId
        // from the persisted last service, so reading the computed property
        // here returns the right value before DAB is even powered on.
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
            dabListView.dabListStationStrip?.visibility = View.GONE
            binding.controlBar.visibility = View.GONE
            binding.stationBar.visibility = View.VISIBLE
            populateDabListMode()
            updateDabListModeSelection()
        }
        initDabDisplay()
        if (!isDabOn && presetRepository.isAutoplayAtStartup()) {
            // Route to the matching power toggle so the correct backend is
            // used (real DAB tuner vs MockDabTunerManager).
            if (isDabDevMode) toggleMockDabPower() else toggleDabPower()
        }
    }

    /**
     * Initiale MediaSession mit aktueller Station aktualisieren.
     */
    private fun updateInitialMediaSession(lastFreq: Float) {
        if (isAnyDabMode) {
            // DAB/DAB_DEV: serviceId is pre-populated by DabController.initialize()
            // from prefs. Pull label/ensemble from the saved DAB-station list so
            // the system MediaSession (auto launcher / lock screen) shows the
            // last-tuned DAB service instead of an unrelated FM frequency.
            val savedStation = dabStationsForCurrentMode.find { it.serviceId == currentDabServiceId }
            val serviceLabel = savedStation?.name
            val ensembleLabel = savedStation?.ensembleLabel
            val radioLogoPath = getLogoForDabStation(serviceLabel, currentDabServiceId)
            android.util.Log.d(TAG, "Initial MediaSession (DAB): sid=$currentDabServiceId, label=$serviceLabel")

            lifecycleScope.launch {
                delay(500)
                FytFMMediaService.instance?.updateDabMetadata(
                    serviceLabel = serviceLabel,
                    ensembleLabel = ensembleLabel,
                    dls = null,
                    slideshowBitmap = null,
                    radioLogoPath = radioLogoPath,
                    deezerCoverPath = null,
                )
            }
        } else {
            val isAM = isAmMode
            val stations = if (isAM) presetRepository.loadAmStations() else presetRepository.loadFmStations()
            val savedStation = stations.find { Math.abs(it.frequency - lastFreq) < 0.05f }
            val savedStationName = savedStation?.name
            val radioLogoPath = radioLogoRepository.getLogoForStation(savedStationName, null, lastFreq)
            android.util.Log.d(TAG, "Initial MediaSession: freq=$lastFreq, stationName=$savedStationName")

            lifecycleScope.launch {
                delay(500)
                FytFMMediaService.instance?.updateMetadata(
                    frequency = lastFreq,
                    ps = savedStationName,
                    rt = null,
                    isAM = isAM,
                    coverUrl = null,
                    localCoverPath = null,
                    radioLogoPath = radioLogoPath
                )
            }
        }
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

        // Cover tap to toggle between Deezer and Slideshow (any DAB mode)
        val coverTapListener = View.OnClickListener {
            if (isAnyDabMode) toggleDabCover()
        }
        binding.nowPlayingCover.setOnClickListener(coverTapListener)
        binding.carouselNowPlayingCover.setOnClickListener(coverTapListener)

        // Long press (5 seconds) to lock/unlock cover source
        setupCoverLongPressListener(binding.nowPlayingCover)
        setupCoverLongPressListener(binding.carouselNowPlayingCover)

        // Abgerundete Ecken auf alle Cover-ImageViews die nicht eh in einem
        // CardView sitzen — analog zur 12dp-Abrundung des großen DAB-List-
        // Covers (`dab_cover_placeholder.xml`). Klippt jeden Bildinhalt
        // (Placeholder + Loaded JPG + Tint-Background) auf rundes Rechteck.
        binding.nowPlayingCover.applyRoundedCorners(12f)
        binding.carouselNowPlayingCover.applyRoundedCorners(12f)
        binding.dabListCover.applyRoundedCorners(12f)

        // Cover source dot indicators (via binding)
        nowPlayingCoverDots = binding.nowPlayingCoverDots
        carouselCoverDots = binding.carouselCoverDots

        // PiP Layout (via binding.pipLayout.*)
        pipController.setupButtons()

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
        setupDemoSignalLossButton()

        // Debug Overlay Drag Setup (alle mit generischer Funktion)
        setupDebugOverlayDrag(binding.debugOverlay, "rds", binding.debugHeader)
        setupDebugOverlayDrag(binding.debugBuildOverlay, "build", binding.debugBuildHeader)
        setupDebugOverlayDrag(binding.debugLayoutOverlay, "layout", binding.debugLayoutHeader)
        setupDebugOverlayDrag(binding.debugDeezerOverlay, "deezer", binding.debugDeezerHeader)
        setupDebugOverlayDrag(binding.debugButtonsOverlay, "buttons", binding.debugButtonsHeader)
        setupDebugOverlayDrag(binding.debugSwcOverlay, "swc", binding.debugSwcHeader)
        setupDebugOverlayDrag(binding.debugCarouselOverlay, "carousel", binding.debugCarouselHeader)
        setupDebugOverlayDrag(binding.debugStationOverlay, "stationoverlay", binding.debugStationHeader)
        setupDebugOverlayDrag(binding.debugTunerOverlay, "tuner", binding.debugTunerHeader)
        // Bug fix: parser overlay was previously not draggable because
        // setupParserLogOverlay called setupDebugOverlayDrag with a
        // never-assigned local View? field (always null → early return).
        setupDebugOverlayDrag(binding.debugParserOverlay, "parser", binding.debugParserHeaderText)
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
        val headerView: View = binding.debugChecklistHeader
        var dX = 0f
        var dY = 0f
        var downX = 0f
        var downY = 0f
        var moved = false
        val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop

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
                    downX = event.rawX
                    downY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!moved &&
                        (kotlin.math.abs(event.rawX - downX) > touchSlop ||
                         kotlin.math.abs(event.rawY - downY) > touchSlop)) {
                        moved = true
                    }
                    if (moved) {
                        val newX = (event.rawX + dX).coerceIn(0f, (view.parent as View).width - view.width.toFloat())
                        val newY = (event.rawY + dY).coerceIn(0f, (view.parent as View).height - view.height.toFloat())
                        view.x = newX
                        view.y = newY
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        saveDebugWindowPosition("checklist", view)
                    } else {
                        val loc = IntArray(2)
                        headerView.getLocationOnScreen(loc)
                        val withinHeader =
                            event.rawX >= loc[0] && event.rawX <= loc[0] + headerView.width &&
                            event.rawY >= loc[1] && event.rawY <= loc[1] + headerView.height
                        if (withinHeader) debugManager.toggleCollapse(view.id)
                    }
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
        if (isAnyDabMode) {
            // DAB+ / DAB Demo: Lock-Status beachten
            val isLockedToDabLogo = coverDisplayController.coverSourceLocked && coverDisplayController.lockedCoverSource == DabCoverSource.DAB_LOGO
            val isLockedToSlideshow = coverDisplayController.coverSourceLocked && coverDisplayController.lockedCoverSource == DabCoverSource.SLIDESHOW
            val isLockedToStationLogo = coverDisplayController.coverSourceLocked && coverDisplayController.lockedCoverSource == DabCoverSource.STATION_LOGO
            val radioLogoPath = getLogoForDabStation(currentDabServiceLabel, currentDabServiceId)

            when {
                isLockedToDabLogo -> {
                    coverDisplayController.currentUiCoverSource = "drawable:ic_fytfm_dab_plus_light"
                    coverTrio.setDabIdlePlaceholder()
                }
                isLockedToSlideshow && currentDabSlideshow != null -> {
                    coverDisplayController.currentUiCoverSource = "slideshow"
                    coverTrio.setBitmap(currentDabSlideshow!!)
                }
                isLockedToStationLogo && radioLogoPath != null -> {
                    coverDisplayController.currentUiCoverSource = radioLogoPath
                    coverTrio.loadFromPath(radioLogoPath)
                }
                !coverDisplayController.coverSourceLocked && currentDabSlideshow != null -> {
                    coverDisplayController.currentUiCoverSource = "slideshow"
                    coverTrio.setBitmap(currentDabSlideshow!!)
                }
                !coverDisplayController.coverSourceLocked && radioLogoPath != null -> {
                    coverDisplayController.currentUiCoverSource = radioLogoPath
                    coverTrio.loadFromPath(radioLogoPath)
                }
                else -> {
                    coverDisplayController.currentUiCoverSource = "drawable:ic_fytfm_dab_plus_light"
                    coverTrio.setDabIdlePlaceholder()
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
            val accentTint = at.planqton.fytfm.ui.theme.AccentColors.current(this, presetRepository)
            if (stationLogo != null) {
                coverDisplayController.currentUiCoverSource = stationLogo
                binding.nowPlayingCover.loadCoverOrFallback(stationLogo, placeholderDrawable, accentTint)
            } else {
                coverDisplayController.currentUiCoverSource = if (isAM) "drawable:placeholder_am" else "drawable:placeholder_fm"
                binding.nowPlayingCover.loadCoverOrFallback(null, placeholderDrawable, accentTint)
            }
            // Carousel auch zurücksetzen
            if (isCarouselMode) {
                stationCarouselAdapter?.updateCurrentCover(null, null)
            }
        }
    }

    private fun startUiInfoUpdates() {
        uiInfoUpdateJob?.cancel()
        uiInfoUpdateJob = lifecycleScope.launch {
            while (isActive) {
                debugManager.updateLayoutDebugInfo(coverDisplayController.currentUiCoverSource)
                delay(500)
            }
        }
    }

    private fun stopUiInfoUpdates() {
        uiInfoUpdateJob?.cancel()
        uiInfoUpdateJob = null
    }

    private fun startTunerInfoUpdates() {
        tunerInfoUpdateJob?.cancel()
        tunerInfoUpdateJob = lifecycleScope.launch {
            while (isActive) {
                debugManager.updateTunerDebugInfo(currentMode, radioController.isDabAvailable())
                delay(1000)
            }
        }
    }

    private fun stopTunerInfoUpdates() {
        tunerInfoUpdateJob?.cancel()
        tunerInfoUpdateJob = null
    }

    private fun startDlsTimestampUpdates() {
        dlsTimestampUpdateJob?.cancel()
        dlsTimestampUpdateJob = lifecycleScope.launch {
            while (isActive) {
                debugManager.lastDlsTimestamp = lastDlsTimestamp
                debugManager.updateDlsTimestampLabel()
                delay(1000)
            }
        }
    }

    private fun stopDlsTimestampUpdates() {
        dlsTimestampUpdateJob?.cancel()
        dlsTimestampUpdateJob = null
    }

    /**
     * Reset DLS/Artist/Title beim DAB-Senderwechsel
     */
    private fun resetDabNowPlaying() {
        // currentDabDls is VM-driven now (Phase B). Reset our local change-tracker.
        lastDlsSeenByMainActivity = null
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
     *
     * Wenn [headerView] gesetzt ist, wird ein Tap (kein Drag) auf der
     * Headerfläche als „Collapse toggle" interpretiert und an
     * [DebugManager.toggleCollapse] weitergereicht. Tap-vs-Drag wird über
     * den System-`scaledTouchSlop` unterschieden.
     */
    private fun setupDebugOverlayDrag(
        overlay: View?,
        windowId: String,
        headerView: View? = null,
    ) {
        overlay ?: return
        var dX = 0f
        var dY = 0f
        var downX = 0f
        var downY = 0f
        var moved = false
        val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop

        overlay.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dX = view.x - event.rawX
                    dY = view.y - event.rawY
                    downX = event.rawX
                    downY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!moved &&
                        (kotlin.math.abs(event.rawX - downX) > touchSlop ||
                         kotlin.math.abs(event.rawY - downY) > touchSlop)) {
                        moved = true
                    }
                    if (moved) {
                        val newX = (event.rawX + dX).coerceIn(0f, (view.parent as View).width - view.width.toFloat())
                        val newY = (event.rawY + dY).coerceIn(0f, (view.parent as View).height - view.height.toFloat())
                        view.x = newX
                        view.y = newY
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        saveDebugWindowPosition(windowId, view)
                    } else if (headerView != null) {
                        // Tap (no drag) — toggle collapse if it landed on the header.
                        val loc = IntArray(2)
                        headerView.getLocationOnScreen(loc)
                        val withinHeader =
                            event.rawX >= loc[0] && event.rawX <= loc[0] + headerView.width &&
                            event.rawY >= loc[1] && event.rawY <= loc[1] + headerView.height
                        if (withinHeader) debugManager.toggleCollapse(view.id)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun setupParserLogOverlay() {
        // Drag is wired in initViews alongside the other debug overlays
        // (setupDebugOverlayDrag(binding.debugParserOverlay, "parser")).
        parserOverlayBinder.setup()
    }

    private fun updateParserTabButtons() = parserOverlayBinder.updateTabButtons()

    private fun updateParserLogDisplay() = parserOverlayBinder.updateLogDisplay()

    /** Holds the export-content while the system "Save As" dialog is open.
     *  Set by [parserOverlayBinder]'s onExportRequested callback, read by
     *  [parserLogExportLauncher] when the user picks a destination. */
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

    private fun updatePipDisplay() {
        if (!isPipMode) return
        pipController.update(
            ps = rdsManager.ps,
            frequency = binding.frequencyScale.getFrequency(),
            rawRt = rdsManager.rt,
            trackInfo = currentDeezerTrackInfo,
        )
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

        // Cover laden: bevorzugt lokaler Cache-Pfad, sonst Online-URL via Coil.
        // Bei Cache-Hits liefert DeezerCache.cover_url bereits den lokalen Pfad
        // (siehe DeezerCache.cacheTrack); fallback auf coverUrl/coverUrlMedium.
        val cachedLocal = deezerCache?.getLocalCoverPath(trackInfo.trackId)
        loadCoverImage(cachedLocal ?: trackInfo.coverUrl ?: trackInfo.coverUrlMedium)

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


    private fun updateDabCoverDisplay() {
        if (!isAnyDabMode) return
        coverDisplayController.updateDabDisplay()
    }

    /**
     * Empfangsverlust-Banner ein-/ausblenden. Fährt von rechts ein
     * (translationX 0 dp) und wieder raus (translationX = Banner-Breite +
     * Padding, damit auch der Schatten verschwindet). Idempotent — wenn
     * der Zielzustand schon erreicht ist, passiert nichts.
     *
     * Direkter Aufruf — kein Debounce. Für Reception-Stats-Events nutzt
     * der Caller `requestSignalLostBanner(visible)` mit Debounce.
     */
    private var signalLostBannerVisible = false

    private val signalLostBannerHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val signalLostBannerShowRunnable = Runnable { setSignalLostBannerVisible(true) }
    private val signalLostBannerHideRunnable = Runnable { setSignalLostBannerVisible(false) }

    /**
     * Verzögert das Einblenden des Banners um ~1,8 s, damit kurze
     * Sync-Aussetzer beim Sender-Wechsel (Tuner sucht den neuen Service)
     * nicht zu Banner-Flackern führen. Recovery-Pfad ist sofortig — der
     * Banner verschwindet ohne Wartezeit, sobald Empfang da ist.
     */
    private fun requestSignalLostBanner(visible: Boolean) {
        signalLostBannerHandler.removeCallbacks(signalLostBannerShowRunnable)
        signalLostBannerHandler.removeCallbacks(signalLostBannerHideRunnable)
        if (visible) {
            // Nur anstoßen, wenn nicht schon sichtbar — dann brauchen wir nicht
            // erneut zu warten.
            if (!signalLostBannerVisible) {
                signalLostBannerHandler.postDelayed(signalLostBannerShowRunnable, 1800)
            }
        } else {
            // Sofort ausblenden, sobald Empfang zurück.
            setSignalLostBannerVisible(false)
        }
    }

    private fun setSignalLostBannerVisible(visible: Boolean) {
        if (signalLostBannerVisible == visible) return
        signalLostBannerVisible = visible

        val banner = binding.dabSignalLostBanner
        // Off-screen-Distanz: Banner-Breite + 32 dp Reserve, damit der
        // Schatten/Eckenradius vollständig vom Rand verschwindet.
        val offDistance = (banner.width.takeIf { it > 0 } ?: 600) + 32

        if (visible) {
            banner.visibility = View.VISIBLE
            banner.animate()
                .translationX(0f)
                .setDuration(280)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .withEndAction(null)
                .start()
        } else {
            banner.animate()
                .translationX(offDistance.toFloat())
                .setDuration(220)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .withEndAction { banner.visibility = View.GONE }
                .start()
        }
    }

    /**
     * Aktualisiert die Sichtbarkeit der Deezer-Wasserzeichen.
     */
    private fun updateDeezerWatermarks(showDeezer: Boolean) =
        coverDisplayController.updateDeezerWatermarks(showDeezer)

    private fun updateSlideshowIndicators(canToggle: Boolean) {
        // Dots zeigen die Cover-Source-Auswahl (DAB_LOGO / STATION_LOGO /
        // SLIDESHOW / DEEZER) — das macht nur in DAB-Modus Sinn. In FM/AM
        // gibt es nichts umzuschalten, daher Container ausblenden.
        val effectiveToggle = canToggle && (isDabMode || isDabDevMode)
        coverDisplayController.updateIndicators(effectiveToggle)
    }

    private fun toggleDabCover() = coverDisplayController.toggleDabCover()

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
        return if (coverDisplayController.selectedCoverSourceIndex >= 0 && coverDisplayController.selectedCoverSourceIndex < coverDisplayController.availableCoverSources.size) {
            coverDisplayController.availableCoverSources[coverDisplayController.selectedCoverSourceIndex]
        } else {
            coverDisplayController.lockedCoverSource
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
        coverDisplayController.refreshAvailableSources()
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
                    runOnUiThread { toast(R.string.select_cover_source_first) }
                    return@Thread
                }

                val saved = saveCoverSourceToFile(currentSource, logoFile)

                if (saved) {
                    updateLogoInRepository(stationName, logoFile)
                    runOnUiThread {
                        toast(getString(R.string.logo_saved_for_format, stationName))
                        refreshUiAfterLogoSave()
                    }
                } else {
                    runOnUiThread { toast(R.string.no_cover_to_save) }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to save custom logo: ${e.message}", e)
                runOnUiThread { toast(R.string.logo_save_failed) }
            }
        }.start()
    }

    /**
     * Sperrt/entsperrt die aktuelle Cover-Quelle.
     */
    private fun toggleCoverSourceLock() = coverDisplayController.toggleCoverSourceLock()

    private fun loadCoverImage(coverPath: String?) {
        val coverImageView = binding.debugDeezerCoverImage

        when {
            coverPath.isNullOrBlank() -> {
                coverImageView.setImageResource(android.R.drawable.ic_menu_gallery)
            }
            coverPath.startsWith("/") -> {
                // Lokaler Pfad — synchron decodieren, da klein und cache-warm
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
            }
            else -> {
                // Remote-URL (Deezer-CDN, https) — Coil lädt async + cached selbst
                coverImageView.load(coverPath) {
                    placeholder(android.R.drawable.ic_menu_gallery)
                    error(android.R.drawable.ic_menu_gallery)
                }
            }
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

        // Station Name (PS)
        val ps = rdsManager.ps
        if (!ps.isNullOrBlank()) {
            binding.nowPlayingPs.text = ps
            binding.nowPlayingPs.visibility = View.VISIBLE
            autoNameStationIfNeeded(ps)
        } else {
            binding.nowPlayingPs.visibility = View.GONE
        }

        // Artist + Title
        binding.nowPlayingTitle.text = trackInfo.title
        if (trackInfo.artist.isBlank()) {
            binding.nowPlayingArtist.visibility = View.GONE
        } else {
            binding.nowPlayingArtist.visibility = View.VISIBLE
            binding.nowPlayingArtist.text = trackInfo.artist
        }

        // Cover decision: Deezer local → Station logo → FM/AM fallback drawable.
        // Only local paths (starting with "/"); never HTTP URLs.
        val currentFreq = binding.frequencyScale.getFrequency()
        val isAM = isAmMode
        val placeholderDrawable = if (isAM) R.drawable.placeholder_am else R.drawable.placeholder_fm
        val localCover = trackInfo.coverUrl?.takeIf { it.startsWith("/") }
        val deezerEnabled = presetRepository.isDeezerEnabledForFrequency(currentFreq)
        val stationLogo = radioLogoRepository.getLogoForStation(ps, rdsManager.pi, currentFreq)

        val accentTint = at.planqton.fytfm.ui.theme.AccentColors.current(this, presetRepository)
        when {
            deezerEnabled && !localCover.isNullOrBlank() -> {
                coverDisplayController.currentUiCoverSource = localCover
                binding.nowPlayingCover.loadCoverOrFallback(localCover, placeholderDrawable, accentTint)
            }
            stationLogo != null -> {
                coverDisplayController.currentUiCoverSource = stationLogo
                binding.nowPlayingCover.loadCoverOrFallback(stationLogo, placeholderDrawable, accentTint)
            }
            else -> {
                coverDisplayController.currentUiCoverSource =
                    if (isAM) "drawable:placeholder_am" else "drawable:placeholder_fm"
                binding.nowPlayingCover.loadCoverOrFallback(null, placeholderDrawable, accentTint)
            }
        }

        if (isCarouselMode) {
            val carouselCover = if (deezerEnabled) localCover else null
            stationCarouselAdapter?.updateCurrentCover(coverUrl = null, localCoverPath = carouselCover)
            updateCarouselNowPlayingBar(trackInfo)
        }

        if (binding.nowPlayingBar.visibility != View.VISIBLE) {
            showNowPlayingBar(binding.nowPlayingBar)
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

        // Phase B: serviceId/ensembleId are computed from DabController, which
        // already received the same dabStation via its onServiceStarted hook.
        // serviceLabel/ensembleLabel/dls similarly read from VM dabState.
        // VM clears dls on DabServiceStarted; reset MainActivity's local
        // change-tracker so the next dls counts as "new".
        lastDlsSeenByMainActivity = null
        lastDlsTimestamp = 0L
        lastDeezerSearchedDls = null
        lastParsedDls = null
        // Wipe Deezer state via VM (clears coverPath + coverSourceKey + currentTrack).
        radioViewModel.updateDeezerTrack(null, null, null)
        // currentDabSlideshow is VM-driven and already cleared by VM on DabServiceStarted.

        runOnUiThread {
            updateDeezerDebugInfo("Waiting...", null, null, null, null)
            coverDisplayController.refreshAvailableSources()
            updateSlideshowIndicators(true)

            // Cover basierend auf Lock-Status setzen
            val isLockedToDabLogo = coverDisplayController.coverSourceLocked && coverDisplayController.lockedCoverSource == DabCoverSource.DAB_LOGO
            val stationLogo = getLogoForDabStation(dabStation.serviceLabel, dabStation.serviceId)

            if (isLockedToDabLogo) {
                coverDisplayController.currentUiCoverSource = "drawable:ic_fytfm_dab_plus_light"
                coverTrio.setDabIdlePlaceholder()
            } else if (stationLogo != null && (!coverDisplayController.coverSourceLocked || coverDisplayController.lockedCoverSource == DabCoverSource.STATION_LOGO)) {
                coverDisplayController.currentUiCoverSource = stationLogo
                coverTrio.loadFromPath(stationLogo)
            } else {
                coverDisplayController.currentUiCoverSource = "drawable:ic_fytfm_dab_plus_light"
                coverTrio.setDabIdlePlaceholder()
            }
        }

        // DabController already persisted these via its own onServiceStarted
        // wiring (see DabController.setupCallbacks). No duplicate write needed.
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

        // Phase B: serviceId/ensembleId computed from DabController (set by
        // its own onServiceStarted hook). serviceLabel/ensembleLabel/dls/
        // slideshow now read from VM dabState.
        lastDlsSeenByMainActivity = null
        lastDlsTimestamp = 0L

        runOnUiThread {
            coverDisplayController.currentUiCoverSource = "drawable:ic_fytfm_dab_plus_light"
            coverTrio.setDabIdlePlaceholder()
            coverDisplayController.refreshAvailableSources()
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
     * Subscribes to RadioViewModel's event flows for DAB-related side effects.
     * Phase A complete: every former `radioController.on…` lambda callback is
     * now a `lifecycleScope.launch { vm.…Events.collect { … } }` block here.
     * lifecycleScope auto-cancels the collectors on Activity destroy.
     */
    private fun setupRadioControllerDabCallbacks() {
        // Phase A: dabTunerReadyEvents replaces the controller callback.
        lifecycleScope.launch {
            radioViewModel.dabTunerReadyEvents.collect {
                android.util.Log.i(TAG, "DAB Tuner ready")
                // Mock backend ships a fixed station list synthesised from
                // its own getServices(); the unified carousel UI reads from
                // the shared DAB-station prefs, so seed them on the way in.
                // Real DAB has its stations from a previous scan in prefs already.
                if (radioController.isMockDabBackendActive()) {
                    val mockStations = radioController.dabController.backend.getServices()
                    if (mockStations.isNotEmpty()) {
                        val asRadioStations = mockStations.map { dabStation ->
                            at.planqton.fytfm.data.RadioStation(
                                frequency = dabStation.ensembleFrequencyKHz.toFloat(),
                                name = dabStation.serviceLabel,
                                rssi = 0,
                                isAM = false,
                                isDab = true,
                                isFavorite = false,
                                serviceId = dabStation.serviceId,
                                ensembleId = dabStation.ensembleId,
                                ensembleLabel = dabStation.ensembleLabel,
                            )
                        }
                        // CRITICAL: write demo stations to the demo prefs file
                        // only. Writing them to saveDabStations() polluted the
                        // real-DAB list — that's where the "Demo Beat in DAB+"
                        // bug came from.
                        presetRepository.saveDabDevStations(asRadioStations)
                    }
                }
                // Read from whichever store matches the active mode.
                val dabStations = dabStationsForCurrentMode
                if (dabStations.isNotEmpty()) {
                    populateCarousel()
                    updateCarouselSelection()
                }
                // Phase B: isDabOn is now derived from radioController; no
                // local field to flip. The controller flipped its own flag
                // inside powerOn() before this event fired.
                updatePowerButton()
            }
        }

        // Phase A: dabServiceStartedEvents replaces the controller callback.
        // Last DAB callback to migrate — completes the controller-callback retirement.
        lifecycleScope.launch {
            radioViewModel.dabServiceStartedEvents.collect { dabStation ->
                handleDabServiceStartedFull(dabStation)
            }
        }

        // Phase A: dlsEvents replaces the controller callback.
        lifecycleScope.launch {
            radioViewModel.dlsEvents.collect { dls ->
                android.util.Log.d(TAG, "DLS received: '$dls' (Station: $currentDabServiceLabel)")
                handleDabDynamicLabelBase(dls)
                handleDabDlsLogging(dls)
                handleDabDlsParserLog(dls)
                processDabDeezerSearch(dls)
            }
        }

        // Phase A: dlPlusEvents replaces the controller callback.
        lifecycleScope.launch {
            radioViewModel.dlPlusEvents.collect { (artist, title) ->
                android.util.Log.d(TAG, "DL+ received: artist=$artist, title=$title")
                handleDabDlPlus(artist, title)
            }
        }

        // Phase A: slideshowEvents replaces the controller callback.
        lifecycleScope.launch {
            radioViewModel.slideshowEvents.collect { bitmap ->
                android.util.Log.d(TAG, "DAB Slideshow: ${bitmap.width}x${bitmap.height}")
                handleDabSlideshowBase(bitmap, "slideshow")
            }
        }

        // Phase A: dabServiceStoppedEvents replaces the controller callback.
        lifecycleScope.launch {
            radioViewModel.dabServiceStoppedEvents.collect {
                android.util.Log.i(TAG, "DAB Service stopped")
                stopDabVisualizer()
            }
        }

        // Phase A: dabAudioStartedEvents replaces the controller callback.
        lifecycleScope.launch {
            radioViewModel.dabAudioStartedEvents.collect { audioSessionId ->
                android.util.Log.i(TAG, "DAB Audio started: session=$audioSessionId")
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
        }

        // Phase A: receptionStatsEvents replaces the controller callback.
        // VM also projects onto dabState (receptionQuality/snr/signalSync).
        lifecycleScope.launch {
            radioViewModel.receptionStatsEvents.collect { stats ->
                debugManager.updateDabReceptionStats(stats.sync, stats.quality, stats.snr)
                val dabSignalEnabled = presetRepository.isSignalIconEnabledDab()
                dabListView.updateSignalIndicator(stats.sync, stats.quality, dabSignalEnabled)
                updateCarouselDabSignalIcon(stats.sync, stats.quality)
                // Empfangsverlust-Banner: nur in DAB/DAB Demo, und nur wenn
                // der Tuner kein Sync hat oder die Qualität als BAD/POOR
                // gemeldet wird. Bei Mode-Wechsel weg von DAB blendet ein
                // separater Hook (cleanupOldRadioMode) den Banner aus.
                if (isAnyDabMode) {
                    val signalLost = !stats.sync ||
                        stats.quality.equals("Bad", ignoreCase = true) ||
                        stats.quality.equals("Poor", ignoreCase = true)
                    requestSignalLostBanner(signalLost)
                }
            }
        }

        // Phase A migration: 3 recording callbacks (Started/Stopped/Error)
        // collapsed into one collector on radioViewModel.recordingEvents.
        // The VM also flips dabState.isRecording — UI button state will be
        // VM-driven once Phase B retires the local isRecording field.
        lifecycleScope.launch {
            radioViewModel.recordingEvents.collect { event ->
                when (event) {
                    is RadioViewModel.RecordingEvent.Started -> {
                        android.util.Log.i(TAG, "DAB Recording started")
                        updateRecordingButton(true)
                        toast(R.string.recording_started)
                    }
                    is RadioViewModel.RecordingEvent.Stopped -> {
                        android.util.Log.i(TAG, "DAB Recording stopped: ${event.file?.absolutePath}")
                        updateRecordingButton(false)
                        event.file?.let { toast(getString(R.string.recording_saved, it.name), long = true) }
                    }
                    is RadioViewModel.RecordingEvent.Failed -> {
                        android.util.Log.e(TAG, "DAB Recording error: ${event.message}")
                        updateRecordingButton(false)
                        toast(event.message, long = true)
                    }
                }
            }
        }

        // Phase A: epgEvents replaces the controller callback. The VM filters
        // out non-EpgData payloads, so no cast is needed here.
        lifecycleScope.launch {
            radioViewModel.epgEvents.collect { updateEpgDialog(it) }
        }

        // Phase A migration: route errors through radioViewModel.errorEvents
        // instead of the controller's onError lambda. The VM observes the
        // controller's events flow (set up in its init) and re-emits errors
        // here. lifecycleScope cancellation handles cleanup on Activity destroy.
        lifecycleScope.launch {
            radioViewModel.errorEvents.collect { error ->
                android.util.Log.e(TAG, "Controller error: $error")
                toast(error, long = true)
            }
        }
    }


    /**
     * Handler für DAB DLS (Dynamic Label) Callback - Basis-Variante.
     * Aktualisiert UI ohne Deezer-Integration.
     */
    private fun handleDabDynamicLabelBase(dls: String) {
        if (dls != lastDlsSeenByMainActivity) {
            lastDlsTimestamp = System.currentTimeMillis()
            lastDlsSeenByMainActivity = dls
        }
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
        // currentDabSlideshow is VM-driven now (set in handleDabSlideshow before this collector runs).
        coverDisplayController.refreshAvailableSources()
        updateSlideshowIndicators(true)

        val isLockedToOtherSource = coverDisplayController.coverSourceLocked &&
            coverDisplayController.lockedCoverSource != null &&
            coverDisplayController.lockedCoverSource != DabCoverSource.SLIDESHOW
        val hasDeezerCover = !currentDabDeezerCoverPath.isNullOrBlank()
        val slideshowSelected = coverDisplayController.selectedCoverSourceIndex >= 0 &&
            coverDisplayController.selectedCoverSourceIndex < coverDisplayController.availableCoverSources.size &&
            coverDisplayController.availableCoverSources[coverDisplayController.selectedCoverSourceIndex] == DabCoverSource.SLIDESHOW
        val showSlideshow = !isLockedToOtherSource &&
            (slideshowSelected || (coverDisplayController.selectedCoverSourceIndex < 0 && !hasDeezerCover))

        if (showSlideshow) {
            coverDisplayController.currentUiCoverSource = sourceLabel
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

            radioViewModel.updateDeezerTrack(trackInfo, localCover, dls)
            coverDisplayController.selectedCoverSourceIndex = -1
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
            radioViewModel.updateDeezerTrack(null, null, null)
            coverDisplayController.selectedCoverSourceIndex = -1
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
        val isLockedToDabLogo = coverDisplayController.coverSourceLocked && coverDisplayController.lockedCoverSource == DabCoverSource.DAB_LOGO
        val stationLogo = getLogoForDabStation(dabStation.serviceLabel, dabStation.serviceId)

        // Same cover decision applies to nowPlaying and carousel covers — do both
        // at once; the two blocks used to repeat the if/else a few lines apart.
        when {
            isLockedToDabLogo ->
                coverTrio.setPlaceholderForCovers(R.drawable.ic_cover_placeholder)
            stationLogo != null && (!coverDisplayController.coverSourceLocked || coverDisplayController.lockedCoverSource == DabCoverSource.STATION_LOGO) ->
                coverTrio.loadForCovers(stationLogo, R.drawable.placeholder_fm)
            else ->
                coverTrio.setPlaceholderForCovers(R.drawable.placeholder_fm)
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
        // Debug-Header auf DAB Debug setzen (über DebugManager, damit ▼/▶ erhalten bleibt)
        debugManager.setRdsHeaderTitle("DAB Debug")

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
                coverTrio.loadForCovers(stationLogo, R.drawable.placeholder_fm)

                // Carousel Now Playing Bar auch aktualisieren
                binding.carouselNowPlayingPs.text = station.name ?: "DAB+"
                binding.carouselNowPlayingPs.visibility = View.VISIBLE
                binding.carouselNowPlayingArtist.visibility = View.GONE
                binding.carouselNowPlayingTitle.visibility = View.GONE
            } else {
                // Kein gespeicherter Sender gefunden
                binding.tvFrequency.text = getString(R.string.band_dab_plus)
                binding.nowPlayingPs.text = getString(R.string.band_dab_plus)
                binding.nowPlayingPs.visibility = View.VISIBLE
            }
        } else {
            // Kein Sender ausgewählt
            binding.tvFrequency.text = getString(R.string.band_dab_plus)
        }
    }

    // ==================== DAB LIST MODE — delegators to DabListViewBinder ====================

    private fun setupDabListMode() = dabListView.bind()
    private fun populateDabListMode() = dabListView.populate()
    private fun refreshDabStationStrip() = dabListView.refreshStationStrip()
    private fun updateDabListModeSelection() = dabListView.updateModeSelection()
    private fun updateDabListRadiotext(text: String?) = dabListView.updateRadiotext(text)
    private fun updateDabListFavoriteIcon(isFavorite: Boolean) = dabListView.updateFavoriteIcon(isFavorite)
    private fun updateDabListFilterIcon() = dabListView.updateFilterIcon()
    private fun updateDabVisualizerSettings() = dabListView.applyVisualizerSettings()

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

    private fun stopDabVisualizer() {
        dabVisualizerView?.release()
        android.util.Log.i(TAG, "DAB Visualizer stopped")
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
            // Phase B: tuneDabService → DabController.tuneService writes
            // currentServiceId/EnsembleId predictively before the tuner
            // confirms; the local fields are computed properties on it.
            coverDisplayController.selectedCoverSourceIndex = -1  // Reset to auto mode on station change
            debugManager.resetDabDebugInfo()
            resetDabNowPlaying()

            val success = radioController.tuneDabService(serviceId, ensembleId)
            if (success) {
                android.util.Log.i(TAG, "Tuned to DAB service: $serviceId")
                // DabController persists on its own onServiceStarted callback.
            } else {
                android.util.Log.e(TAG, "Failed to tune to DAB service: $serviceId")
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error tuning DAB: ${e.message}", e)
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
        val accentTint = at.planqton.fytfm.ui.theme.AccentColors.current(this, presetRepository)
        binding.nowPlayingCover.loadCoverOrFallback(logoPath, placeholderDrawable, accentTint)

        // Clear raw RT display and hide ignored indicator
        binding.nowPlayingRawRt.text = ""
        binding.carouselNowPlayingRawRt.text = ""
        binding.nowPlayingIgnoredIndicator.visibility = View.GONE
        binding.carouselIgnoredIndicator.visibility = View.GONE

        // Update carousel bar
        binding.carouselNowPlayingTitle.text = displayName
        binding.carouselNowPlayingArtist.text = if (stationName != null) freqDisplay else ""
        binding.carouselNowPlayingArtist.visibility = if (stationName != null) View.VISIBLE else View.GONE

        binding.carouselNowPlayingCover.loadCoverOrFallback(logoPath, placeholderDrawable, accentTint)

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

        val accentTint = at.planqton.fytfm.ui.theme.AccentColors.current(this, presetRepository)
        if (deezerEnabled && !localCover.isNullOrBlank()) {
            // Lokaler Deezer Cache verfügbar
            binding.carouselNowPlayingCover.loadCoverOrFallback(localCover, placeholderDrawable, accentTint)
        } else if (stationLogo != null) {
            // Station logo available
            binding.carouselNowPlayingCover.loadCoverOrFallback(stationLogo, placeholderDrawable, accentTint)
        } else {
            // Fallback: Radio icon
            binding.carouselNowPlayingCover.loadCoverOrFallback(null, placeholderDrawable, accentTint)
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
                toast(R.string.rt_ignored)
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
                toast(R.string.searching_other_track)
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
        setupDabDeezerToggle()

        // Click handlers for both toggles (normal and carousel)
        val toggleAction = {
            // Master FM-Deezer-Toggle wird hier gewechselt — UI-Button und
            // Settings-Switch sollen 1:1 synchron sein. Per-Frequency-Override
            // bleibt separat über den Sender-Editor erreichbar.
            val newState = !presetRepository.isDeezerEnabledFm()
            presetRepository.setDeezerEnabledFm(newState)
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
        // Carousel-Button ist mode-aware: in DAB+ togglet er DAB-Deezer,
        // in FM/AM den FM-Master.
        binding.btnCarouselDeezerToggle.setOnClickListener {
            if (isDabMode || isDabDevMode) {
                val newState = !presetRepository.isDeezerEnabledDab()
                presetRepository.setDeezerEnabledDab(newState)
                updateDabDeezerToggleAppearance(newState)
                updateCarouselDeezerToggleAppearance()
                coverDisplayController.updateDabDisplay()
                updateSlideshowIndicators(true)
                toast(if (newState) R.string.deezer_enabled else R.string.deezer_disabled)
            } else {
                toggleAction()
            }
        }
    }

    /** Spiegelt den richtigen Pref-Wert (FM oder DAB je nach Mode) auf den
     *  Carousel-Deezer-Button. Aufruf nach Mode-Wechsel oder Pref-Änderung. */
    private fun updateCarouselDeezerToggleAppearance() {
        val enabled = if (isDabMode || isDabDevMode) {
            presetRepository.isDeezerEnabledDab()
        } else {
            presetRepository.isDeezerEnabledFm()
        }
        val bg = if (enabled) R.drawable.toggle_selected else android.R.color.transparent
        val drawableRes = if (enabled) R.drawable.ic_deezer else R.drawable.ic_deezer_off
        val tintColor = if (enabled) {
            at.planqton.fytfm.ui.theme.AccentColors.current(this, presetRepository)
        } else {
            androidx.core.content.ContextCompat.getColor(this, R.color.radio_text_secondary)
        }
        binding.btnCarouselDeezerToggle.setImageResource(drawableRes)
        binding.btnCarouselDeezerToggle.setBackgroundResource(bg)
        binding.btnCarouselDeezerToggle.alpha = 1.0f
        androidx.core.widget.ImageViewCompat.setImageTintList(
            binding.btnCarouselDeezerToggle,
            android.content.res.ColorStateList.valueOf(tintColor)
        )
    }

    /** DAB-Pendant zum FM-Deezer-Toggle. Schaltet das DAB-Master-Pref um
     *  und triggert den Cover-Display-Refresh, damit Deezer-Cover sofort
     *  geladen oder weggeschaltet werden. */
    private fun setupDabDeezerToggle() {
        updateDabDeezerToggleAppearance(presetRepository.isDeezerEnabledDab())
        binding.dabListDeezerToggleBtn.setOnClickListener {
            val newState = !presetRepository.isDeezerEnabledDab()
            presetRepository.setDeezerEnabledDab(newState)
            updateDabDeezerToggleAppearance(newState)
            if (isDabMode || isDabDevMode) {
                coverDisplayController.updateDabDisplay()
                updateSlideshowIndicators(true)
            }
            toast(if (newState) R.string.deezer_enabled else R.string.deezer_disabled)
        }
    }

    private fun updateDabDeezerToggleAppearance(enabled: Boolean) {
        val drawableRes = if (enabled) R.drawable.ic_deezer else R.drawable.ic_deezer_off
        val tintColor = if (enabled) {
            at.planqton.fytfm.ui.theme.AccentColors.current(this, presetRepository)
        } else {
            androidx.core.content.ContextCompat.getColor(this, R.color.radio_text_secondary)
        }
        binding.dabListDeezerToggleBtn.setImageResource(drawableRes)
        androidx.core.widget.ImageViewCompat.setImageTintList(
            binding.dabListDeezerToggleBtn,
            android.content.res.ColorStateList.valueOf(tintColor)
        )
    }

    /**
     * Update Spotify toggle button for current frequency
     * Call this when frequency changes
     */
    fun updateDeezerToggleForCurrentFrequency() {
        // UI-Button reflektiert den Master-FM-Toggle aus den Settings (nicht
        // den per-frequency-Override — der wird im Sender-Editor verwaltet).
        updateDeezerToggleAppearance(presetRepository.isDeezerEnabledFm())
    }

    /**
     * Update Deezer toggle buttons (FM main + carousel) for the current
     * enable-state. ON: regular logo, accent-color tint, selected background.
     * OFF: separate ic_deezer_off drawable (with strikethrough) tinted in
     * the secondary text color, transparent background.
     */
    private fun updateDeezerToggleAppearance(enabled: Boolean) {
        val bg = if (enabled) R.drawable.toggle_selected else android.R.color.transparent
        val drawableRes = if (enabled) R.drawable.ic_deezer else R.drawable.ic_deezer_off
        val tintColor = if (enabled) {
            at.planqton.fytfm.ui.theme.AccentColors.current(this, presetRepository)
        } else {
            androidx.core.content.ContextCompat.getColor(this, R.color.radio_text_secondary)
        }
        val tintList = android.content.res.ColorStateList.valueOf(tintColor)
        // FM-Hauptbutton folgt immer FM-Pref. Carousel-Button ist mode-aware
        // und wird unten separat gesetzt.
        binding.btnDeezerToggle.setImageResource(drawableRes)
        binding.btnDeezerToggle.setBackgroundResource(bg)
        binding.btnDeezerToggle.alpha = 1.0f
        androidx.core.widget.ImageViewCompat.setImageTintList(binding.btnDeezerToggle, tintList)
        updateCarouselDeezerToggleAppearance()
    }

    /**
     * Setup view mode toggle (Equalizer vs Carousel)
     */
    private fun setupViewModeToggle() {
        stationCarouselAdapter = at.planqton.fytfm.ui.StationCarouselAdapter(::onCarouselStationClicked)

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
     * Handler for a station click in the carousel view: tunes FM/AM/DAB
     * depending on the station's type, with toast-on-error reporting.
     */
    private fun onCarouselStationClicked(station: at.planqton.fytfm.ui.StationCarouselAdapter.StationItem) {
        android.util.Log.i(TAG, "=== CAROUSEL CLICK: station=${station.name}, isDab=${station.isDab}, currentMode=$currentMode, serviceId=${station.serviceId} ===")
        if (station.isDab || isAnyDabMode) {
            tuneCarouselDabStation(station)
        } else {
            tuneCarouselFmAmStation(station)
        }
    }

    private fun tuneCarouselDabStation(station: at.planqton.fytfm.ui.StationCarouselAdapter.StationItem) {
        android.util.Log.i(TAG, ">>> Using DAB logic (mode=$currentMode)")
        try {
            debugManager.resetDabDebugInfo()
            resetDabNowPlaying()
            // Real and mock DAB now share the controller path — the active
            // backend (set by useRealDabBackend / useMockDabBackend at mode
            // entry / power-on) decides which tuner actually receives the call.
            // tuneDabService writes currentServiceId/EnsembleId predictively
            // inside DabController; the MainActivity computed properties pick
            // it up immediately.
            val success = radioController.tuneDabService(station.serviceId, station.ensembleId)
            if (!success) {
                toast(R.string.tuner_error_dab_not_found, long = true)
            }
            updateCarouselSelection()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "DAB tune error: ${e.message}", e)
            toast(getString(R.string.tuner_error, e.message), long = true)
        }
    }

    private fun tuneCarouselFmAmStation(station: at.planqton.fytfm.ui.StationCarouselAdapter.StationItem) {
        android.util.Log.i(TAG, ">>> Using FM/AM logic")
        try {
            val mode = if (station.isAM) FrequencyScaleView.RadioMode.AM else FrequencyScaleView.RadioMode.FM
            binding.frequencyScale.setMode(mode)
            binding.frequencyScale.setFrequency(station.frequency)
            fmNative?.tune(station.frequency)
            updateCarouselSelection()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "FM tune error: ${e.message}", e)
            toast(getString(R.string.tuner_error, e.message), long = true)
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

            if (isAnyDabMode) {
                // DAB List Mode - special layout, hide control bar but show station bar.
                // isAnyDabMode covers DAB + DAB_DEV so the demo backend gets the same
                // dab-list UI as the real tuner — earlier code only checked isDabMode,
                // which fell through to the FM/AM branch in demo mode.
                mainContentArea?.visibility = View.GONE
                dabListContentArea?.visibility = View.VISIBLE
                dabListView.dabListStationStrip?.visibility = View.GONE  // Hide the DAB strip, use stationBar instead
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
        if (position >= 0) smoothScrollCarouselToCenter(position)
    }

    /**
     * Start 2-second timer to smoothly center the carousel on the selected station
     */
    private fun startCarouselCenterTimer(frequency: Float, isAM: Boolean) {
        // Cancel any existing center + debug-update jobs (keep returnJob alone —
        // the original Handler-based code did the same selective cancellation).
        carouselCenterJob?.cancel()
        carouselDebugUpdateJob?.cancel()

        val position = stationCarouselAdapter?.getPositionForFrequency(frequency, isAM) ?: -1
        if (position < 0) return

        carouselPendingCenterPosition = position
        carouselCenterTimerStart = System.currentTimeMillis()
        updateCarouselDebugInfo()

        carouselDebugUpdateJob = lifecycleScope.launch {
            while (isActive && carouselPendingCenterPosition >= 0) {
                delay(100)
                updateCarouselDebugInfo()
            }
        }

        carouselCenterJob = lifecycleScope.launch {
            delay(2000)
            smoothScrollCarouselToCenter(carouselPendingCenterPosition)
            carouselPendingCenterPosition = -1
            updateCarouselDebugInfo()
        }
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

        val currentFreq = binding.frequencyScale.getFrequency()
        val isAM = isAmMode
        carouselPendingCenterPosition = stationCarouselAdapter?.getPositionForFrequency(currentFreq, isAM) ?: -1
        carouselCenterTimerStart = System.currentTimeMillis()
        updateCarouselDebugInfo()

        carouselDebugUpdateJob = lifecycleScope.launch {
            while (isActive && carouselPendingCenterPosition >= 0) {
                delay(100)
                updateCarouselDebugInfo()
            }
        }

        carouselReturnJob = lifecycleScope.launch {
            delay(8000)
            val pos = carouselPendingCenterPosition
            android.util.Log.d(TAG, "Carousel return timer: scrolling to position $pos")
            if (pos >= 0) smoothScrollCarouselToCenter(pos)
            carouselPendingCenterPosition = -1
            updateCarouselDebugInfo()
        }
        android.util.Log.d(TAG, "Carousel return timer started (8s)")
    }

    /**
     * Cancel pending carousel return timer (and its debug-update sibling).
     * Mirrors the original Handler-based selective cancellation: leaves
     * any active centerJob untouched.
     */
    private fun cancelCarouselReturnTimer() {
        if (carouselReturnJob?.isActive == true) {
            android.util.Log.d(TAG, "Carousel return timer cancelled")
        }
        carouselReturnJob?.cancel()
        carouselReturnJob = null

        carouselDebugUpdateJob?.cancel()
        carouselDebugUpdateJob = null

        carouselPendingCenterPosition = -1
        updateCarouselDebugInfo()
    }

    /**
     * Update carousel favorite icon state
     */
    private fun updateCarouselFavoriteIcon() {
        val isFavorite = if (isAnyDabMode) {
            // Carousel UI was reading FM/AM stations only — DAB/DAB-Demo
            // services never matched, so the heart stayed outline even
            // when the station was favorited. Route to the right backend
            // (real vs demo store separately) like updateFavoriteButton.
            currentDabServiceId > 0 && if (isDabDevMode) {
                presetRepository.isDabDevFavorite(currentDabServiceId)
            } else {
                presetRepository.isDabFavorite(currentDabServiceId)
            }
        } else {
            val currentFreq = binding.frequencyScale.getFrequency()
            val stations = if (isAmMode) presetRepository.loadAmStations() else presetRepository.loadFmStations()
            stations.find { Math.abs(it.frequency - currentFreq) < 0.05f }?.isFavorite == true
        }

        // Filled heart = accent colour (dynamic); outline keeps grey #888888.
        val btn = btnCarouselFavorite ?: return
        btn.setImageResource(
            if (isFavorite) R.drawable.ic_favorite_filled else R.drawable.ic_favorite_border
        )
        if (isFavorite) {
            val accent = at.planqton.fytfm.ui.theme.AccentColors.current(this, presetRepository)
            androidx.core.widget.ImageViewCompat.setImageTintList(
                btn,
                android.content.res.ColorStateList.valueOf(accent)
            )
        } else {
            androidx.core.widget.ImageViewCompat.setImageTintList(btn, null)
        }
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
            android.util.Log.e(TAG, "Failed to load DLS log: ${e.message}", e)
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
            android.util.Log.e(TAG, "Failed to save DLS log: ${e.message}", e)
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

    override fun getLogoForDabStation(name: String?, serviceId: Int): String? {
        // User-saved custom logos win over the demo-default vector drawable.
        // Otherwise the swipe-down-to-save-as-logo gesture in DAB Demo mode
        // would silently be overwritten by the bundled demo logo on every
        // refresh, even though the user just saved a custom one.
        val custom = radioLogoRepository.getLogoForStation(name, null, 0f)
        if (custom != null) return custom

        // Fallback: bundled demo vector drawable (rendered to PNG once at
        // scan time). Demo service-ids ≥ 2000 keep this strictly separate
        // from any real-DAB station logos.
        if (isDabDevMode && serviceId >= at.planqton.fytfm.dab.MockDabTunerManager.SERVICE_ID_OFFSET) {
            val demoFile = filesDir.resolve("demo_logos/demo_${serviceId}.png")
            if (demoFile.exists()) return demoFile.absolutePath
        }
        return null
    }

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
        refreshUiAfterLogoSave()
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
                        // DAB: tune via DabController (predictive id update inside).
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
                    android.util.Log.e(TAG, "Station tune error: ${e.message}", e)
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
            updateSignalBars(rdsManager.rssi, rdsManager.rssiAgeMs)
        }

        // Explizit longClickable aktivieren
        binding.btnPrevStation.isLongClickable = true
        binding.btnNextStation.isLongClickable = true

        binding.btnPrevStation.setOnClickListener {
            val step = if (isFmMode) presetRepository.getFmFrequencyStep() else presetRepository.getAmFrequencyStep()
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
            val step = if (isFmMode) presetRepository.getFmFrequencyStep() else presetRepository.getAmFrequencyStep()
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
            // Phase B: VM-driven mute state. toggleMute() flips it synchronously
            // so the subsequent isPlaying read sees the new value.
            radioViewModel.toggleMute()
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
            archiveOverlayController.show()
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
        lifecycleScope.launch {
            delay(500)
            FytFMMediaService.instance?.let { service ->
                service.onPlayCallback = {
                    runOnUiThread {
                        if (!isPlaying) {
                            radioViewModel.setMuted(false)
                            if (isRadioOn) setRadioMuteState(false)
                            updatePlayPauseButton()
                        }
                    }
                }
                service.onPauseCallback = {
                    runOnUiThread {
                        if (isPlaying) {
                            radioViewModel.setMuted(true)
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
        }
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
        val options = resources.getStringArray(R.array.now_playing_animation_options)
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

    private var radioAreaCallback: ((Int) -> Unit)? = null

    private fun showRadioAreaDialog(onSelected: (Int) -> Unit) {
        val requestKey = "radio_area"
        radioAreaCallback = onSelected

        val areasWithTemplates = (0..4).filter {
            radioLogoRepository.getTemplatesForArea(it).isNotEmpty()
        }.toSet()

        supportFragmentManager.setFragmentResultListener(requestKey, this) { _, bundle ->
            if (bundle.getBoolean("cancelled", false)) {
                radioAreaCallback = null
                return@setFragmentResultListener
            }
            val areaId = bundle.getInt("areaId")
            val hasTemplates = bundle.getBoolean("hasTemplates", false)
            if (hasTemplates) {
                showAreaTemplateDialog(areaId) {
                    radioAreaCallback?.invoke(areaId)
                    radioAreaCallback = null
                }
            } else {
                radioLogoRepository.setActiveTemplate(null)
                radioAreaCallback?.invoke(areaId)
                radioAreaCallback = null
            }
        }

        RadioAreaDialogFragment.newInstance(
            currentArea = presetRepository.getRadioArea(),
            areasWithTemplates = areasWithTemplates,
            requestKey = requestKey,
        ).show(supportFragmentManager, RadioAreaDialogFragment.TAG)
    }

    /**
     * Zeigt Template-Auswahl für eine bestimmte Region
     */
    private fun showAreaTemplateDialog(areaId: Int, onComplete: (String?) -> Unit) {
        val templates = radioLogoRepository.getTemplatesForArea(areaId)
        val activeTemplate = radioLogoRepository.getActiveTemplateName()

        val options = buildList {
            add("Kein Template")
            addAll(templates.map { "${it.name} (${it.stations.size} Sender)" })
        }

        val currentIndex = activeTemplate
            ?.let { active -> templates.indexOfFirst { it.name == active } }
            ?.let { if (it >= 0) it + 1 else 0 }
            ?: 0

        val requestKey = "area_template"
        supportFragmentManager.setFragmentResultListener(requestKey, this) { _, bundle ->
            if (bundle.getBoolean("cancelled", false)) return@setFragmentResultListener
            val which = bundle.getInt("selectedIndex", -1)
            when {
                which == 0 -> {
                    radioLogoRepository.setActiveTemplate(null)
                    onComplete(null)
                }
                which in 1..templates.size -> {
                    val template = templates[which - 1]
                    if (template.stations.all { it.localPath != null }) {
                        radioLogoRepository.setActiveTemplate(template.name)
                        onComplete(template.name)
                    } else {
                        downloadAndActivateTemplate(template) {
                            onComplete(template.name)
                        }
                    }
                }
            }
        }

        AreaTemplateDialogFragment.newInstance(
            title = getString(R.string.area_template_dialog_title_format, getRadioAreaName(areaId)),
            options = options.toTypedArray(),
            selectedIndex = currentIndex,
            requestKey = requestKey,
        ).show(supportFragmentManager, AreaTemplateDialogFragment.TAG)
    }

    private val logoTemplateDownloader by lazy {
        LogoTemplateDownloader(this, radioLogoRepository)
    }

    private val stationLogoDownloader by lazy {
        StationLogoDownloader(
            activity = this,
            radioLogoRepository = radioLogoRepository,
            onLogoSaved = { stationName ->
                loadStationsForCurrentMode()
                coverDisplayController.refreshAvailableSources()
                updateSlideshowIndicators(true)
                if (stationName.equals(currentDabServiceLabel, ignoreCase = true)) {
                    updateDabCoverDisplay()
                }
            },
        )
    }

    private fun downloadAndActivateTemplate(
        template: at.planqton.fytfm.data.logo.RadioLogoTemplate,
        onComplete: () -> Unit,
    ) = logoTemplateDownloader.downloadAndActivate(template, onComplete)

    private fun showManualLogoSearchDialogInternal(
        station: at.planqton.fytfm.data.RadioStation,
        mode: FrequencyScaleView.RadioMode,
        onComplete: () -> Unit,
    ) {
        val requestKey = "manual_logo_search"
        supportFragmentManager.setFragmentResultListener(requestKey, this) { _, bundle ->
            if (bundle.getBoolean("cancelled", false)) return@setFragmentResultListener
            val imageUrl = bundle.getString("imageUrl") ?: return@setFragmentResultListener
            downloadAndSaveLogoForStation(imageUrl, station, mode, onComplete)
        }
        val stationName = station.name ?: station.ensembleLabel ?: ""
        ManualLogoSearchDialogFragment.newInstance(
            stationName = stationName,
            requestKey = requestKey,
        ).show(supportFragmentManager, ManualLogoSearchDialogFragment.TAG)
    }



    /**
     * Lädt ein Bild herunter und speichert es als Logo für den Sender.
     * Konvertiert webp/gif automatisch zu PNG.
     */
    private fun downloadAndSaveLogoForStation(
        imageUrl: String,
        station: at.planqton.fytfm.data.RadioStation,
        mode: FrequencyScaleView.RadioMode,
        onComplete: () -> Unit,
    ) = stationLogoDownloader.downloadAndSave(imageUrl, station, mode, onComplete)

    // EditStationDialogFragment posts back via FragmentResult. Since the
    // caller's lambda doesn't survive config-change anyway, the listener is
    // re-registered per show() call (same key overwrites the previous one).
    private var editStationCallback: ((String, Boolean) -> Unit)? = null
    private var editStationFrequency: Float = 0f
    private var editDabStationCallback: ((String) -> Unit)? = null

    private fun showEditStationDialogInternal(
        station: at.planqton.fytfm.data.RadioStation,
        onSave: (String, Boolean) -> Unit,
    ) {
        val requestKey = "edit_station"
        editStationCallback = onSave
        editStationFrequency = station.frequency
        supportFragmentManager.setFragmentResultListener(requestKey, this) { _, bundle ->
            if (!bundle.getBoolean("cancelled", false)) {
                val name = bundle.getString("name", "")
                val syncName = bundle.getBoolean("syncName", true)
                val deezerEnabled = bundle.getBoolean("deezerEnabled", true)
                editStationCallback?.invoke(name, syncName)
                presetRepository.setDeezerEnabledForFrequency(editStationFrequency, deezerEnabled)
                if (Math.abs(binding.frequencyScale.getFrequency() - editStationFrequency) < 0.05f) {
                    updateDeezerToggleForCurrentFrequency()
                }
            }
            editStationCallback = null
        }
        EditStationDialogFragment.newInstance(
            frequencyDisplay = station.getDisplayFrequency(),
            currentName = station.name ?: "",
            syncName = station.syncName,
            deezerEnabled = presetRepository.isDeezerEnabledForFrequency(station.frequency),
            requestKey = requestKey,
        ).show(supportFragmentManager, EditStationDialogFragment.TAG)
    }

    private fun showEditDabStationDialogInternal(
        station: at.planqton.fytfm.data.RadioStation,
        onSave: (String) -> Unit,
    ) {
        val requestKey = "edit_dab_station"
        editDabStationCallback = onSave
        supportFragmentManager.setFragmentResultListener(requestKey, this) { _, bundle ->
            if (!bundle.getBoolean("cancelled", false)) {
                editDabStationCallback?.invoke(bundle.getString("name", ""))
            }
            editDabStationCallback = null
        }
        EditDabStationDialogFragment.newInstance(
            ensembleLabel = station.ensembleLabel,
            currentName = station.name ?: "",
            requestKey = requestKey,
        ).show(supportFragmentManager, EditDabStationDialogFragment.TAG)
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
            setTitle(getString(R.string.dab_dev_scan_title))
            setMessage(getString(R.string.dab_dev_scanning_message))
            setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            setCancelable(false)
        }
        progressDialog.show()

        // Mock-Scan starten — uses controller's active backend, which the
        // dev-mode dialog enters with via useMockDabBackend() below.
        radioController.useMockDabBackend()
        radioController.dabController.startScan(object : at.planqton.fytfm.dab.DabScanListener {
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

                    // Render demo logos to filesDir/demo_logos/ once. The
                    // lookup later happens via getLogoForDabStation, which
                    // checks the demo folder when isDabDevMode is active.
                    renderDemoLogosOnce(services)

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
                    toast(getString(R.string.mock_stations_found_format, services.size))
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

    /**
     * Renders the per-station vector-drawable logos to PNG files in the
     * app's filesDir/demo_logos/ folder, one file per service-id, idempotent
     * (skips already-rendered files). Returns a map serviceId → absolute
     * file path so the caller can attach it to RadioStation.logoPath.
     *
     * Strict separation: demo logos live in their own directory and never
     * touch RadioLogoRepository's normal logo cache used for real stations.
     */
    private fun renderDemoLogosOnce(services: List<at.planqton.fytfm.dab.DabStation>): Map<Int, String> {
        val mock = radioController.dabController.backend as? at.planqton.fytfm.dab.MockDabTunerManager
            ?: return emptyMap()
        val outDir = filesDir.resolve("demo_logos").apply { mkdirs() }
        val result = mutableMapOf<Int, String>()
        for (service in services) {
            val resId = mock.getLogoResId(service.serviceId) ?: continue
            val outFile = java.io.File(outDir, "demo_${service.serviceId}.png")
            if (!outFile.exists()) {
                try {
                    val drawable = androidx.core.content.ContextCompat.getDrawable(this, resId)
                        ?: continue
                    val size = 256
                    val bmp = android.graphics.Bitmap.createBitmap(
                        size, size, android.graphics.Bitmap.Config.ARGB_8888
                    )
                    val canvas = android.graphics.Canvas(bmp)
                    drawable.setBounds(0, 0, size, size)
                    drawable.draw(canvas)
                    outFile.outputStream().use { os ->
                        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, os)
                    }
                    bmp.recycle()
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Failed to render demo logo for ${service.serviceLabel}: ${e.message}", e)
                    continue
                }
            }
            result[service.serviceId] = outFile.absolutePath
        }
        return result
    }

    private fun loadStationsForCurrentMode() {
        val allStations = when {
            isFmMode -> presetRepository.loadFmStations()
            isAmMode -> presetRepository.loadAmStations()
            isAnyDabMode -> dabStationsForCurrentMode
            else -> presetRepository.loadFmStations()
        }

        // Filter anwenden wenn aktiviert
        val stations = if (showFavoritesOnly) {
            allStations.filter { it.isFavorite }
        } else {
            allStations
        }

        stationAdapter.setStations(stations)
        if (isAnyDabMode) stationAdapter.setSelectedDabService(currentDabServiceId)
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
            debugManager.resetDabDebugInfo()
            resetDabNowPlaying()

            // Phase B: tuneDabService writes IDs predictively in DabController.
            val success = radioController.tuneDabService(newStation.serviceId, newStation.ensembleId)
            if (!success) {
                toast(R.string.tuner_error_dab_not_found, long = true)
            }
            updateCarouselSelection()
            updateFavoriteButton()
            // Show station change overlay
            showDabStationChangeOverlay(newStation.serviceId, oldServiceId)
            android.util.Log.i(TAG, "skipDabStation: -> ${newStation.name} (SID=${newStation.serviceId})")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "skipDabStation error: ${e.message}", e)
            toast(getString(R.string.tuner_error, e.message), long = true)
        }
    }

    private fun showStationChangeOverlay(frequency: Float, oldFrequency: Float = 0f) {
        stationOverlay.showFmAmChange(
            frequency = frequency,
            oldFrequency = oldFrequency,
            isAM = isAmMode,
            isAppInForeground = isAppInForeground,
            stations = stationAdapter.getStations(),
            logoFor = { name, freq -> radioLogoRepository.getLogoForStation(name, null, freq) },
        )
    }

    private fun showDabStationChangeOverlay(serviceId: Int, oldServiceId: Int = 0) {
        stationOverlay.showDabChange(
            serviceId = serviceId,
            oldServiceId = oldServiceId,
            isAppInForeground = isAppInForeground,
            stations = dabStationsForCurrentMode,
            logoFor = { name, id -> getLogoForDabStation(name, id) },
        )
    }

    private fun showPermanentStationOverlay() {
        stationOverlay.showPermanent(
            frequency = binding.frequencyScale.getFrequency(),
            isAM = isAmMode,
            stations = stationAdapter.getStations(),
            logoFor = { name, freq -> radioLogoRepository.getLogoForStation(name, null, freq) },
        )
    }

    private fun hidePermanentStationOverlay() {
        stationOverlay.hidePermanent()
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
        FmService.clearRds()
        binding.tvAfIndicator.visibility = View.GONE
        rdsManager.setUiFrequency(frequency)
        // Signal-Icon kurz auf "none" setzen — beim nächsten Poll-Tick
        // kommt der frische RSSI-Wert.
        updateSignalBars(0, -1)
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
        // FmAmController.tune persists internally (fytfm_fmam); local fytfm_prefs duplicate retired.
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
        val modes = resources.getStringArray(R.array.radio_mode_options).toMutableList()
        if (dabDevEnabled) {
            modes.add(getString(R.string.band_dab_dev))
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
        // Push the mode to RadioController too — that's the only path that
        // emits RadioEvent.ModeChanged, which the VM observes to transition
        // its uiState into RadioUiState.Dab/.FmAm. Without this the Phase B
        // computed properties (currentDabServiceLabel/Slideshow/DeezerCover)
        // stay null after a runtime mode switch, breaking the cover-source
        // dots and any UI that reads from VM dabState.
        radioController.setMode(mode)
        android.util.Log.i(TAG, "=== setRadioMode: mode is now ${binding.frequencyScale.getMode()} ===")

        // 3. Button-Sichtbarkeiten aktualisieren
        updateRecordButtonVisibility()
        updateEpgButtonVisibility()
        updateDemoSignalLossButtonVisibility()
        binding.viewModeToggle.visibility = View.VISIBLE

        // 4. Gespeicherte View-Einstellung wiederherstellen
        restoreViewModeForRadioMode(mode)

        // 5. Neuen Modus initialisieren
        initializeNewRadioMode(mode)

        // 6. UI aktualisieren
        updateUiAfterModeChange(mode)

        // 7. Carousel-Deezer-Toggle ist mode-abhängig — nach Mode-Wechsel re-rendern
        updateCarouselDeezerToggleAppearance()
    }

    private fun updateModeSpinner() {
        val position = when {
            isFmMode -> 0
            isAmMode -> 1
            isDabMode -> 2
            isDabDevMode -> 3
            else -> 0
        }
        // Top-Bar-Spinner
        if (binding.spinnerRadioMode.selectedItemPosition != position) {
            suppressSpinnerCallback = true
            binding.spinnerRadioMode.setSelection(position, false)
        }
        // DAB-List-Mode-Spinner (separater Adapter im DAB-Listenlayout). Ohne
        // diesen sync bleibt der zweite Spinner auf seinem Default (2 = DAB+),
        // obwohl die App im DAB Demo Mode ist.
        binding.dabListModeSpinner?.let { dabSpinner ->
            if (dabSpinner.selectedItemPosition != position) {
                dabSpinner.setSelection(position, false)
            }
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
            android.util.Log.e(TAG, "Failed to set sys.radio.mute: ${e.message}", e)
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
                // Radio ausschalten — controller flips its own isRadioOn flag.
                android.util.Log.i(TAG, "--- Powering OFF ---")
                stopRdsPolling()
                radioController.powerOffFmAmFull()
                // Reset mute state so next power-on starts audible (matches
                // historical "isPlaying = true on power-off" behaviour).
                radioViewModel.setMuted(false)
                updatePlayPauseButton()
                FytFMMediaService.instance?.updatePlaybackState(false)
            } else {
                // Radio einschalten via Controller (sets isRadioOn internally).
                val frequency = binding.frequencyScale.getFrequency()
                android.util.Log.i(TAG, "--- Powering ON at $frequency MHz ---")

                radioController.powerOnFmAmFull(frequency)
                android.util.Log.i(TAG, "isRadioOn = $isRadioOn")

                if (isRadioOn) {
                    // UI-Updates — VM creates fresh FmAm with isMuted=false on
                    // Off→FmAm transitions, but if we stayed in FmAm (e.g.
                    // power-cycle without cleanupOldRadioMode), reset explicitly.
                    radioViewModel.setMuted(false)
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
            // Controller's catch path sets fmAmController.isRadioOn = false
            // (see FmAmController.powerOnFull catch).
            toast(getString(R.string.tuner_error, e.message), long = true)
        }
        android.util.Log.i(TAG, "======= toggleRadioPower() done =======")
        updatePowerButton()
    }

    /**
     * Wendet die gespeicherten Tuner-Settings an (LOC, Mono, Area)
     */

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
            lifecycleScope.launch {
                delay(100)
                toneGenerator.release()
            }
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
            // Persistieren — sonst geht beim nächsten FM/AM-Mode-Reload die
            // gerade getunte Frequenz verloren und der Tuner springt zurück
            // auf den Default 98.4 / 1008.
            radioController.persistFrequency(frequency)
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
                        // FmAmController.seek persists internally; duplicate retired.
                    } else {
                        android.util.Log.w(TAG, "Seek: No station found")
                        // Zurück zur ursprünglichen Frequenz
                        binding.frequencyScale.setFrequency(currentFreq)
                    }
                }
            } catch (e: Throwable) {
                android.util.Log.e(TAG, "Seek failed: ${e.message}", e)
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
                // DAB ausschalten via Controller (resets currentServiceId/EnsembleId).
                android.util.Log.i(TAG, "--- DAB Powering OFF via controller ---")
                stopDabVisualizer()
                radioController.powerOffDab()
            } else {
                // Switch to real backend before powering on. Idempotent if
                // already on real (e.g. after a fresh app start).
                radioController.useRealDabBackend()
                android.util.Log.i(TAG, "--- DAB Powering ON via controller ---")
                val success = radioController.powerOnDab()
                if (!success) {
                    toast(R.string.dab_init_failed, long = true)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "DAB power toggle failed: ${e.message}", e)
            toast(getString(R.string.tuner_error, e.message), long = true)
        }

        android.util.Log.i(TAG, "======= toggleDabPower() done, isDabOn=$isDabOn =======")
        updatePowerButton()
    }

    /**
     * Mock DAB+ Tuner Ein/Aus schalten (für UI-Entwicklung ohne Hardware).
     * Identisch zu [toggleDabPower] bis auf das Backend — nach Routing durch
     * [DabController] ist der Tuner-Pfad identisch.
     */
    private fun toggleMockDabPower() {
        android.util.Log.i(TAG, "======= toggleMockDabPower() =======")

        try {
            if (isDabOn) {
                android.util.Log.i(TAG, "--- Mock DAB Powering OFF via controller ---")
                stopDabVisualizer()
                radioController.powerOffDab()
            } else {
                radioController.useMockDabBackend()
                android.util.Log.i(TAG, "--- Mock DAB Powering ON via controller ---")
                val success = radioController.powerOnDab()
                if (!success) {
                    toast(R.string.mock_dab_init_failed, long = true)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Mock DAB power toggle failed: ${e.message}", e)
            toast(getString(R.string.tuner_error, e.message), long = true)
        }

        android.util.Log.i(TAG, "======= toggleMockDabPower() done, isDabOn=$isDabOn =======")
        updatePowerButton()
    }

    /**
     * Lädt den Favoriten-Filter-Status für den aktuellen Modus (FM/AM)
     */
    private fun loadFavoritesFilterState() {
        radioViewModel.loadFavoritesFilterForMode(currentMode)
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
        recordingBlinkJob?.cancel()
        recordingBlinkJob = lifecycleScope.launch {
            var visible = true
            while (isActive) {
                val alpha = if (visible) 1.0f else 0.3f
                dabListRecordBtn?.alpha = alpha
                btnCarouselRecord?.alpha = alpha
                visible = !visible
                delay(500)
            }
        }
    }

    /**
     * Stoppt das Blinken des Record-Buttons
     */
    private fun stopRecordingBlink() {
        recordingBlinkJob?.cancel()
        recordingBlinkJob = null
        dabListRecordBtn?.alpha = 1.0f
        btnCarouselRecord?.alpha = 1.0f
    }

    /**
     * Zeigt/versteckt die Record-Buttons basierend auf der Konfiguration und dem Modus
     */
    private fun updateRecordButtonVisibility() {
        val isRecordingConfigured = presetRepository.isDabRecordingEnabled()
        dabListRecordBtn?.visibility = if (isRecordingConfigured) View.VISIBLE else View.GONE
        btnCarouselRecord?.visibility = if (isRecordingConfigured && isAnyDabMode) View.VISIBLE else View.GONE
    }

    /**
     * Zeigt/versteckt die EPG-Buttons basierend auf dem Modus
     */
    private fun updateEpgButtonVisibility() {
        // DAB List Mode - EPG immer sichtbar im DAB-Modus
        dabListEpgBtn?.visibility = View.VISIBLE

        // Carousel Mode - in beiden DAB-Modi sichtbar
        btnCarouselEpg?.visibility = if (isAnyDabMode) View.VISIBLE else View.GONE
    }

    /**
     * Demo-Signal-Loss-Test-Button: nur im DAB Demo Mode sichtbar.
     * Klick toggelt im MockDabTunerManager den Signal-Loss-State, was zu
     * sofortigen onReceptionStats-Emissions führt — das Empfangsverlust-
     * Banner fährt ein/aus über den normalen Reception-Stats-Pfad.
     */
    private fun updateDemoSignalLossButtonVisibility() {
        binding.dabListDemoSignalLossBtn.visibility =
            if (isDabDevMode) View.VISIBLE else View.GONE
    }

    private fun setupDemoSignalLossButton() {
        binding.dabListDemoSignalLossBtn.setOnClickListener {
            val mock = radioController.dabController.backend
                as? at.planqton.fytfm.dab.MockDabTunerManager ?: return@setOnClickListener
            val newState = !mock.isSignalLossSimulated()
            mock.setSignalLossSimulated(newState)
            // optional: visuelles Feedback durch Tönung des Buttons
            binding.dabListDemoSignalLossBtn.alpha = if (newState) 1.0f else 0.6f
        }
        binding.dabListDemoSignalLossBtn.alpha = 0.6f
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
            // Real DAB and DAB demo store stations in separate prefs, so the
            // favourite-lookup must route to the matching backend or the demo
            // serviceId never matches the real-DAB list (and the heart never
            // fills in demo).
            currentDabServiceId > 0 && if (isDabDevMode) {
                presetRepository.isDabDevFavorite(currentDabServiceId)
            } else {
                presetRepository.isDabFavorite(currentDabServiceId)
            }
        } else {
            presetRepository.isFavorite(binding.frequencyScale.getFrequency(), isAmMode)
        }
        // Filled heart = accent colour (dynamic); outline keeps grey #888888.
        binding.btnFavorite.setImageResource(
            if (isFavorite) R.drawable.ic_favorite_filled else R.drawable.ic_favorite_border
        )
        if (isFavorite) {
            val accent = at.planqton.fytfm.ui.theme.AccentColors.current(this, presetRepository)
            androidx.core.widget.ImageViewCompat.setImageTintList(
                binding.btnFavorite,
                android.content.res.ColorStateList.valueOf(accent)
            )
        } else {
            androidx.core.widget.ImageViewCompat.setImageTintList(binding.btnFavorite, null)
        }
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
            if (currentDabServiceId <= 0) return
            if (isDabDevMode) {
                presetRepository.toggleDabDevFavorite(currentDabServiceId)
            } else {
                presetRepository.toggleDabFavorite(currentDabServiceId)
            }
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
        val newValue = radioViewModel.toggleFavoritesOnlyForMode(currentMode)
        updateFolderButton()
        loadStationsForCurrentMode()
        toast(if (newValue) R.string.favorites_only else R.string.all_stations)
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
                toast(R.string.swc_next)
                seekToStation(seekUp = true)
                true
            }
            android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                android.util.Log.i(TAG, "MEDIA_PREVIOUS pressed")
                toast(R.string.swc_prev)
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
        // twUtil bleibt offen — gehört jetzt FytFmApp und überlebt Activity-
        // Recreate (Theme-Switch). Erst in closeApp() wird es geschlossen.
        updateRepository.destroy()
        rdsLogRepository.destroy()
        syuToolkitManager?.disconnect()
        // Cleanup ParserLogger listeners (binder owns the lambda + detach).
        parserOverlayBinder.release()
        // Radio NICHT ausschalten - läuft im MediaService weiter (auch im Sleep)
        // fmNative.powerOff() wird nur vom User manuell ausgelöst
    }

    // saveLastFrequency / loadLastFrequency / saveLastRadioMode / loadLastRadioMode
    // deleted — they wrote to "fytfm_prefs" with different key names than
    // FmAmController/RadioController's canonical "fytfm_fmam" prefs file.
    // Two parallel persistence layers were drifting on every tune/mode-switch.

    /**
     * Alten Radio-Modus aufräumen bevor gewechselt wird.
     */
    private fun cleanupOldRadioMode(oldMode: FrequencyScaleView.RadioMode) {
        if (oldMode == FrequencyScaleView.RadioMode.DAB ||
            oldMode == FrequencyScaleView.RadioMode.DAB_DEV) {
            // Both DAB modes go through the controller now; the active backend
            // (real OMRI tuner or MockDabTunerManager) is whichever was last
            // selected via useRealDabBackend / useMockDabBackend.
            stopDlsTimestampUpdates()
            // Empfangsverlust-Banner ausblenden — gilt nur im DAB-Modus.
            requestSignalLostBanner(false)
            dabListView.updateSignalIndicator(sync = false, quality = "", enabled = presetRepository.isSignalIconEnabledDab())
            updateCarouselDabSignalIcon(sync = false, quality = "")
            android.util.Log.i(TAG, "Stopping DAB tuner ($oldMode) via controller...")
            if (isDabOn) {
                // powerOffDab resets DabController.currentServiceId/EnsembleId.
                radioController.powerOffDab()
            }
            debugManager.resetDebugToRds()
        } else {
            android.util.Log.i(TAG, "Stopping FM/AM radio...")
            stopRdsPolling()
            if (isRadioOn) {
                // (powerOffFmAmFull-Versuch zurückgerollt — TWUtil ist auf
                // diesem Gerät eh nicht verfügbar, der Extra-Aufruf hat
                // nichts gebracht und die App-ID-Release vor Power-Off
                // hat den FM-Pfad nur noch sicherer kaputtgemacht.)
                radioController.powerOffFmAm()
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
                val lastFreq = radioController.getCurrentFrequency()
                // powerOnFmAmFull flips controller's isRadioOn on success.
                val success = radioController.powerOnFmAmFull(lastFreq)
                if (success) {
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
            mainContentArea?.visibility = View.GONE
            dabListContentArea?.visibility = View.GONE
            carouselContentArea?.visibility = View.VISIBLE
            populateCarousel()
            updateCarouselSelection()
            binding.controlBar.visibility = View.VISIBLE
            binding.stationBar.visibility = View.VISIBLE
        } else if (mode == FrequencyScaleView.RadioMode.DAB || mode == FrequencyScaleView.RadioMode.DAB_DEV) {
            carouselContentArea?.visibility = View.GONE
            mainContentArea?.visibility = View.GONE
            dabListContentArea?.visibility = View.VISIBLE
            dabListView.dabListStationStrip?.visibility = View.GONE
            binding.controlBar.visibility = View.GONE
            binding.stationBar.visibility = View.VISIBLE
            populateDabListMode()
            updateDabListModeSelection()
        } else {
            carouselContentArea?.visibility = View.GONE
            mainContentArea?.visibility = View.VISIBLE
            dabListContentArea?.visibility = View.GONE
            binding.controlBar.visibility = View.VISIBLE
            binding.stationBar.visibility = View.VISIBLE
        }

        updateFavoriteButton()
        updatePowerButton()
        // RadioController.setMode persists the mode internally (fytfm_fmam).
    }

    // saveLastDabService / loadLastDabService deleted — DabController already
    // owns this persistence (in its own "fytfm_dab" prefs file). MainActivity
    // was writing to a SECOND prefs file ("fytfm_prefs") with the same keys,
    // creating two sources of truth that drift apart on first install vs upgrade.

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

        // Cancel any existing timeout, then arm a fresh 3 s collection deadline.
        presetImportTimeoutJob?.cancel()
        presetImportTimeoutJob = lifecycleScope.launch {
            delay(3000)
            finishPresetCollection()
        }

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
        presetImportTimeoutJob = null

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

    override fun onAccentColorEditorRequested() {
        at.planqton.fytfm.ui.settings.AccentColorEditorDialogFragment
            .newInstance()
            .show(supportFragmentManager, at.planqton.fytfm.ui.settings.AccentColorEditorDialogFragment.TAG)
    }

    override fun onDeezerEnabledFmChanged(enabled: Boolean) {
        updateDeezerToggleAppearance(enabled)
    }

    override fun onDeezerEnabledDabChanged(enabled: Boolean) {
        updateDabDeezerToggleAppearance(enabled)
        updateCarouselDeezerToggleAppearance()
    }

    override fun onSignalIconToggleChanged(mode: FrequencyScaleView.RadioMode, enabled: Boolean) {
        // Toggle for a non-current mode → just persist; the icon UI for the
        // current mode must not be touched (carousel icon view is shared
        // between FM/AM and DAB).
        val toggledIsFmAm = mode == FrequencyScaleView.RadioMode.FM ||
            mode == FrequencyScaleView.RadioMode.AM
        val toggledIsDab = mode == FrequencyScaleView.RadioMode.DAB ||
            mode == FrequencyScaleView.RadioMode.DAB_DEV

        when {
            toggledIsFmAm && (isFmMode || isAmMode) -> {
                updateSignalBars(rdsManager.rssi, rdsManager.rssiAgeMs)
            }
            toggledIsDab && isAnyDabMode -> {
                if (!enabled) {
                    binding.ivCarouselSignalIcon.visibility = View.GONE
                    dabListView.dabListSignalIcon?.visibility = View.GONE
                } else {
                    val dabState = (radioViewModel.uiState.value as? at.planqton.fytfm.viewmodel.RadioUiState.Dab)?.dabState
                    val sync = dabState?.signalSync ?: false
                    val quality = dabState?.receptionQuality ?: ""
                    dabListView.updateSignalIndicator(sync, quality, true)
                    updateCarouselDabSignalIcon(sync, quality)
                }
            }
        }
    }

    override fun onAccentColorChanged() {
        applyCurrentAccentColor()
        // Settings-Dialog (falls offen) seine Preview-Swatches refreshen lassen.
        (supportFragmentManager.findFragmentByTag(at.planqton.fytfm.ui.settings.SettingsDialogFragment.TAG)
            as? at.planqton.fytfm.ui.settings.SettingsDialogFragment)?.refreshAccentPreviewSwatches()
    }

    /**
     * Wendet die aktuell gültige Akzentfarbe (User-Wahl oder Default) auf
     * alle UI-Elemente an, die `radio_accent` / `radio_indicator` benutzen
     * und nicht über Theme-Resources automatisch gerefresht werden:
     *
     * - Empfangsverlust-Banner (Background-Tint, Drawable-Singleton → mutate())
     * - PS-Label oben (`nowPlayingPs`) und im Carousel (`carouselNowPlayingPs`)
     * - Ensemble-Label im DAB-Listen-Modus (`dabListEnsemble` via DabListView)
     * - Roter Trenner unter dem Ensemble (`accentDivider`)
     * - Frequenzskala-Indikator (`frequencyScale.setAccentColor`)
     * - DAB-Strip-Adapter (selected-Card-Hintergrund) und Audio-Visualizer
     *   (Bars/Wave/Circle-Paints) — beides routed über DabListView.
     *
     * Wird bei App-Start, nach einer Color-Änderung im Editor, und bei
     * Day/Night-Configuration-Change aufgerufen.
     */
    private fun applyCurrentAccentColor() {
        val color = at.planqton.fytfm.ui.theme.AccentColors.current(this, presetRepository)
        // Banner-Background: Drawable ist Singleton, deshalb mutate() sodass
        // andere Verwendungen nicht mit-getintet werden.
        binding.dabSignalLostBanner.background?.mutate()?.setTint(color)
        binding.nowPlayingPs.setTextColor(color)
        binding.carouselNowPlayingPs.setTextColor(color)
        binding.accentDivider.setBackgroundColor(color)
        binding.frequencyScale.setAccentColor(color)
        dabListView.setAccentColor(color)
        // View-mode toggle icons (top-right): the drawables had a hardcoded
        // legacy-red fillColor. Now tinted dynamically so they match the
        // current accent color.
        val tint = android.content.res.ColorStateList.valueOf(color)
        (binding.btnViewModeEqualizer.getChildAt(0) as? ImageView)?.let {
            androidx.core.widget.ImageViewCompat.setImageTintList(it, tint)
        }
        (binding.btnViewModeImage.getChildAt(0) as? ImageView)?.let {
            androidx.core.widget.ImageViewCompat.setImageTintList(it, tint)
        }
        // Station list / strip selected-card stroke (item_station's
        // station_tile_background drawable) hardcodes the legacy red — the
        // adapter now rebuilds the drawable in code with this color.
        if (::stationAdapter.isInitialized) {
            stationAdapter.setAccentColor(color)
        }
        // Debug-Windows alpha SeekBar: progress + thumb default to the
        // theme's colorAccent (legacy red). Tint to follow the user's accent.
        binding.debugOverlaysAlphaSlider.progressTintList = tint
        binding.debugOverlaysAlphaSlider.thumbTintList = tint
        // Deezer-Toggles (FM-Hauptansicht + Carousel + DAB-UI1) sind jetzt
        // akzent-getintet — bei jedem Akzent-Wechsel re-rendern.
        updateDeezerToggleForCurrentFrequency()
        updateDabDeezerToggleAppearance(presetRepository.isDeezerEnabledDab())
        // Placeholder-Tint im Carousel-Adapter aktualisieren — die
        // FM/AM/DAB-Branding-Vektoren bekommen die Akzentfarbe.
        stationCarouselAdapter?.setAccentColor(color)
        // Now-Playing-Bar Cover (oben + Carousel) re-rendern, falls dort
        // gerade ein Placeholder steht — Tint ginge sonst beim Theme-Wechsel
        // verloren bis zum nächsten Sender-Update.
        if (currentMode == FrequencyScaleView.RadioMode.FM ||
            currentMode == FrequencyScaleView.RadioMode.AM) {
            val freq = binding.frequencyScale.getFrequency()
            resetNowPlayingBarForStation(
                stationName = rdsManager.ps,
                logoPath = radioLogoRepository.getLogoForStation(rdsManager.ps, null, freq),
                frequency = freq,
                isAM = isAmMode,
            )
        }
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
        if (isAnyDabMode) {
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
        return app.presetRepository
    }

    override fun getUpdateRepository(): at.planqton.fytfm.data.UpdateRepository {
        return updateRepository
    }

    override fun setUpdateStateListener(listener: ((UpdateState) -> Unit)?) {
        settingsUpdateListener = listener
    }

    override fun getCurrentUpdateState(): UpdateState {
        return updateRepository.updateState
    }

    override fun installUpdate(localPath: String) {
        try {
            val file = java.io.File(localPath)
            if (!file.exists()) {
                android.widget.Toast.makeText(this, R.string.update_apk_not_found, android.widget.Toast.LENGTH_LONG).show()
                return
            }
            val authority = "${packageName}.fileprovider"
            val uri = androidx.core.content.FileProvider.getUriForFile(this, authority, file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Install intent failed", e)
            android.widget.Toast.makeText(
                this,
                getString(R.string.update_install_failed_format, e.message ?: ""),
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }
}
