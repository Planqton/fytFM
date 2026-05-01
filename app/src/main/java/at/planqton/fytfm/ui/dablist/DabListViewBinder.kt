package at.planqton.fytfm.ui.dablist

import android.graphics.Bitmap
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.content.res.ColorStateList
import at.planqton.fytfm.FrequencyScaleView
import at.planqton.fytfm.R
import at.planqton.fytfm.dab.DabStation
import at.planqton.fytfm.data.PresetRepository
import at.planqton.fytfm.data.RadioStation
import at.planqton.fytfm.databinding.ActivityMainBinding
import at.planqton.fytfm.ui.AudioVisualizerView
import at.planqton.fytfm.ui.DabStripAdapter
import at.planqton.fytfm.ui.cover.CoverDisplayController
import at.planqton.fytfm.ui.cover.DabCoverSource
import at.planqton.fytfm.ui.helper.loadCover

/**
 * Owns the DAB-list-mode view binding + button wiring that previously lived
 * inline in MainActivity (`setupDabListMode` and friends, ~350 lines).
 *
 * MainActivity passes its dependencies as a [Callbacks] bag — the binder
 * never reaches into the Activity directly. Update methods (selection,
 * radiotext, favourite icon, filter icon, strip refresh) are called from
 * MainActivity's event collectors / state-update paths.
 *
 * The binder owns:
 * - All `dabList*` view references (captured from binding in [bind])
 * - The [DabStripAdapter] for the horizontal station strip
 * - The swipe-gesture detector + slide-animation state on the main area
 * - The `dabSwipeFlingDetected` re-entrancy guard
 */
class DabListViewBinder(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val presetRepository: PresetRepository,
    private val callbacks: Callbacks,
) {

    /**
     * The set of host callbacks the binder needs. MainActivity supplies
     * concrete implementations; the binder calls them for navigation, state
     * lookup, and side effects (toasts, dialogs, recording, etc).
     */
    data class Callbacks(
        // ---- state lookups ----
        val getCurrentDabServiceId: () -> Int,
        val getDabStationsForCurrentMode: () -> List<RadioStation>,
        val getCurrentDabSlideshow: () -> Bitmap?,
        val getCoverDisplayController: () -> CoverDisplayController,
        val getShowFavoritesOnly: () -> Boolean,
        val getLogoForDabStation: (name: String?, serviceId: Int) -> String?,
        /** Routes to toggleDabFavorite or toggleDabDevFavorite based on the
         *  active DAB backend (real vs demo) — they live in separate prefs. */
        val toggleCurrentDabFavorite: (serviceId: Int) -> Boolean,
        // ---- navigation / actions ----
        val tuneToDabStation: (serviceId: Int, ensembleId: Int) -> Unit,
        val toggleDabCover: () -> Unit,
        val setupCoverLongPressListener: (View?) -> Unit,
        val loadStationsForCurrentMode: () -> Unit,
        val toggleFavoritesFilter: () -> Unit,
        val showStationScanDialog: () -> Unit,
        val showSettingsDialogFragment: () -> Unit,
        val toggleDabRecording: () -> Unit,
        val updateRecordButtonVisibility: () -> Unit,
        val showEpgDialog: () -> Unit,
        val updateEpgButtonVisibility: () -> Unit,
        val setRadioMode: (FrequencyScaleView.RadioMode) -> Unit,
    )

    // ---- view references (captured in bind()) ----
    var dabListCover: ImageView? = null
        private set
    var dabListCoverDots: LinearLayout? = null
        private set
    var dabListDeezerWatermark: TextView? = null
        private set
    var dabListStationName: TextView? = null
        private set
    var dabListEnsemble: TextView? = null
        private set
    var dabListSignalIcon: ImageView? = null
        private set
    var dabListRadiotext: TextView? = null
        private set
    var dabListFavoriteBtn: ImageButton? = null
        private set
    var dabListFilterBtn: ImageButton? = null
        private set
    var dabListSearchBtn: ImageButton? = null
        private set
    var dabListSettingsBtn: ImageButton? = null
        private set
    var dabListRecordBtn: ImageButton? = null
        private set
    var dabListEpgBtn: ImageButton? = null
        private set
    var dabListStationStrip: RecyclerView? = null
        private set
    var dabListMainArea: View? = null
        private set
    var dabVisualizerView: AudioVisualizerView? = null
        private set

    private var dabStripAdapter: DabStripAdapter? = null
    private var dabSwipeFlingDetected = false
    /** Last accent color pushed by MainActivity. 0 = nicht gesetzt → Defaults
     *  aus den Resources greifen. Wird in refreshStationStrip() auf den neu
     *  erstellten Adapter angewandt, damit nach einem Recreate die Farbe nicht
     *  verloren geht. */
    private var lastAccentColor: Int = 0

    /**
     * Push the current accent color into all DAB-list children that don't
     * pull it from theme resources (strip adapter, visualizer paints,
     * ensemble label). Wird von MainActivity nach jeder Color-Änderung und
     * im onConfigurationChanged-Pfad aufgerufen.
     */
    fun setAccentColor(color: Int) {
        lastAccentColor = color
        dabStripAdapter?.setAccentColor(color)
        dabVisualizerView?.setAccentColor(color)
        dabListEnsemble?.setTextColor(color)
    }

    /**
     * Captures view refs from the binding and wires every button + spinner
     * + swipe gesture. Call once during MainActivity#initViews.
     */
    fun bind() {
        dabListCover = binding.dabListCover
        dabListCoverDots = binding.dabListCoverDots
        dabListDeezerWatermark = binding.dabListDeezerWatermark
        dabListCover?.setOnClickListener { callbacks.toggleDabCover() }
        callbacks.setupCoverLongPressListener(dabListCover)
        dabListStationName = binding.dabListStationName
        dabListEnsemble = binding.dabListEnsemble
        dabListSignalIcon = binding.dabListSignalIcon
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

        applyVisualizerSettings()

        dabVisualizerView?.setOnClickListener {
            val current = presetRepository.getDabVisualizerStyle()
            val next = (current + 1) % VISUALIZER_STYLE_COUNT
            presetRepository.setDabVisualizerStyle(next)
            dabVisualizerView?.setStyle(next)
        }

        dabStripAdapter = DabStripAdapter(
            onStationClick = { station -> callbacks.tuneToDabStation(station.serviceId, station.ensembleId) },
            getLogoPath = { name, serviceId -> callbacks.getLogoForDabStation(name, serviceId) },
        )

        dabListStationStrip?.apply {
            layoutManager = LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false)
            adapter = dabStripAdapter
        }

        dabListFavoriteBtn?.setOnClickListener {
            val sid = callbacks.getCurrentDabServiceId()
            if (sid > 0) {
                // Routed through MainActivity so demo/real DAB use their
                // own preference store — toggleDabFavorite alone targets
                // only the real DAB list and silently no-ops in demo mode.
                val isFav = callbacks.toggleCurrentDabFavorite(sid)
                updateFavoriteIcon(isFav)
                refreshStationStrip()
                callbacks.loadStationsForCurrentMode()
            }
        }

        dabListFilterBtn?.setOnClickListener {
            callbacks.toggleFavoritesFilter()
            updateFilterIcon()
        }

        dabListSearchBtn?.setOnClickListener { callbacks.showStationScanDialog() }
        dabListSettingsBtn?.setOnClickListener { callbacks.showSettingsDialogFragment() }
        dabListRecordBtn?.setOnClickListener { callbacks.toggleDabRecording() }
        callbacks.updateRecordButtonVisibility()
        dabListEpgBtn?.setOnClickListener { callbacks.showEpgDialog() }
        callbacks.updateEpgButtonVisibility()

        setupModeSpinner()
        setupSwipeGestures()
    }

    private fun setupModeSpinner() {
        val spinner = binding.dabListModeSpinner ?: return
        val dabDevEnabled = presetRepository.isDabDevModeEnabled()
        val modes = activity.resources.getStringArray(R.array.radio_mode_options).toMutableList().apply {
            if (dabDevEnabled) add(activity.getString(R.string.band_dab_dev))
        }
        val adapter = ArrayAdapter(activity, R.layout.item_radio_mode_spinner, modes)
        adapter.setDropDownViewResource(R.layout.item_radio_mode_dropdown)
        spinner.adapter = adapter

        val currentSelection = when (binding.frequencyScale.getMode()) {
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
                if (newMode != binding.frequencyScale.getMode()) {
                    callbacks.setRadioMode(newMode)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupSwipeGestures() {
        var startX = 0f
        var isSwiping = false

        val gestureDetector = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y
                if (Math.abs(diffX) > Math.abs(diffY) &&
                    Math.abs(diffX) > SWIPE_THRESHOLD_PX &&
                    Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD
                ) {
                    dabSwipeFlingDetected = true
                    val direction = if (diffX > 0) -1 else 1
                    animateStationChange(direction)
                    return true
                }
                return false
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean = true
        })

        dabListMainArea?.setOnTouchListener { view, event ->
            gestureDetector.onTouchEvent(event)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    isSwiping = true
                    dabSwipeFlingDetected = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isSwiping && !dabSwipeFlingDetected) {
                        val deltaX = event.x - startX
                        val translation = (deltaX * SWIPE_DAMPING).coerceIn(-MAX_DRAG_PX, MAX_DRAG_PX)
                        view.translationX = translation
                        view.alpha = 1f - (Math.abs(translation) / MAX_DRAG_PX) * 0.15f
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isSwiping && !dabSwipeFlingDetected) {
                        view.animate()
                            .translationX(0f)
                            .alpha(1f)
                            .setDuration(SWIPE_RELEASE_DURATION_MS)
                            .setInterpolator(DecelerateInterpolator())
                            .start()
                    }
                    isSwiping = false
                }
            }
            true
        }
    }

    private fun animateStationChange(direction: Int) {
        val view = dabListMainArea ?: return
        val slideDistance = view.width.toFloat() * 0.3f

        view.animate()
            .translationX(if (direction > 0) -slideDistance else slideDistance)
            .alpha(0.3f)
            .setDuration(STATION_SLIDE_OUT_DURATION_MS)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                navigateStation(direction)
                view.translationX = if (direction > 0) slideDistance else -slideDistance
                view.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(STATION_SLIDE_IN_DURATION_MS)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            .start()
    }

    private fun navigateStation(direction: Int) {
        val stations = callbacks.getDabStationsForCurrentMode()
        if (stations.isEmpty()) return
        val currentIndex = stations.indexOfFirst { it.serviceId == callbacks.getCurrentDabServiceId() }
        val newIndex = when {
            currentIndex < 0 -> 0
            else -> (currentIndex + direction).coerceIn(0, stations.size - 1)
        }
        if (newIndex != currentIndex && newIndex in stations.indices) {
            val s = stations[newIndex]
            callbacks.tuneToDabStation(s.serviceId, s.ensembleId)
        }
    }

    /** Refresh the visualizer style + visibility from PresetRepository. */
    fun applyVisualizerSettings() {
        val isEnabled = presetRepository.isDabVisualizerEnabled()
        dabVisualizerView?.visibility = if (isEnabled) View.VISIBLE else View.GONE
        dabVisualizerView?.setStyle(presetRepository.getDabVisualizerStyle())
    }

    /** Push the current station list into the strip adapter. */
    fun populate() {
        dabStripAdapter?.setStations(callbacks.getDabStationsForCurrentMode())
    }

    /**
     * Re-create the strip adapter to pick up favourite-status changes.
     * (DabStripAdapter doesn't have a per-row update API.)
     */
    fun refreshStationStrip() {
        val strip = dabListStationStrip ?: return
        val serviceId = callbacks.getCurrentDabServiceId()
        val stations = callbacks.getDabStationsForCurrentMode()

        val newAdapter = DabStripAdapter(
            onStationClick = { station -> callbacks.tuneToDabStation(station.serviceId, station.ensembleId) },
            getLogoPath = { name, sid -> callbacks.getLogoForDabStation(name, sid) },
        )
        newAdapter.setStations(stations)
        newAdapter.setSelectedStation(serviceId)
        if (lastAccentColor != 0) newAdapter.setAccentColor(lastAccentColor)

        dabStripAdapter = newAdapter
        strip.adapter = newAdapter

        val position = newAdapter.getPositionForServiceId(serviceId)
        if (position >= 0) strip.scrollToPosition(position)
    }

    /**
     * Project the currently-selected station onto the DAB-list panel:
     * station name / ensemble label / cover (with cover-source-lock
     * awareness) / favourite icon. Called from MainActivity's
     * service-started + state-change paths.
     */
    fun updateModeSelection() {
        val contentArea = binding.dabListContentArea ?: return
        if (contentArea.visibility != View.VISIBLE) return

        val sid = callbacks.getCurrentDabServiceId()
        val station = callbacks.getDabStationsForCurrentMode().find { it.serviceId == sid }

        dabStripAdapter?.setSelectedStation(sid)
        val position = dabStripAdapter?.getPositionForServiceId(sid) ?: -1
        if (position >= 0) dabListStationStrip?.smoothScrollToPosition(position)

        if (station != null) {
            dabListStationName?.text = station.name ?: "Unknown"
            dabListEnsemble?.text = station.ensembleLabel ?: ""
            updateFavoriteIcon(station.isFavorite)

            val ctrl = callbacks.getCoverDisplayController()
            val isLockedToDabLogo = ctrl.coverSourceLocked && ctrl.lockedCoverSource == DabCoverSource.DAB_LOGO
            val isLockedToSlideshow = ctrl.coverSourceLocked && ctrl.lockedCoverSource == DabCoverSource.SLIDESHOW
            val isLockedToStationLogo = ctrl.coverSourceLocked && ctrl.lockedCoverSource == DabCoverSource.STATION_LOGO
            val stationLogo = callbacks.getLogoForDabStation(station.name, station.serviceId)
            val slideshow = callbacks.getCurrentDabSlideshow()

            when {
                isLockedToDabLogo -> dabListCover?.setImageResource(R.drawable.ic_fytfm_dab_plus_light)
                isLockedToStationLogo && stationLogo != null ->
                    dabListCover?.loadCover(stationLogo, R.drawable.ic_fytfm_dab_plus_light)
                isLockedToSlideshow && slideshow != null ->
                    dabListCover?.setImageBitmap(slideshow)
                !ctrl.coverSourceLocked && stationLogo != null ->
                    dabListCover?.loadCover(stationLogo, R.drawable.ic_fytfm_dab_plus_light)
                !ctrl.coverSourceLocked && slideshow != null ->
                    dabListCover?.setImageBitmap(slideshow)
                else -> dabListCover?.setImageResource(R.drawable.ic_fytfm_dab_plus_light)
            }
        } else {
            dabListStationName?.text = activity.getString(R.string.no_station)
            dabListEnsemble?.text = ""
            dabListRadiotext?.text = ""
            dabListCover?.setImageResource(R.drawable.ic_fytfm_dab_plus_light)
        }
    }

    /** No-op when DAB-list panel isn't visible — saves needless View work. */
    fun updateRadiotext(text: String?) {
        val contentArea = binding.dabListContentArea ?: return
        if (contentArea.visibility != View.VISIBLE) return
        dabListRadiotext?.text = text ?: ""
    }

    fun updateFavoriteIcon(isFavorite: Boolean) {
        val btn = dabListFavoriteBtn ?: return
        btn.setImageResource(
            if (isFavorite) R.drawable.ic_favorite_filled else R.drawable.ic_favorite_border
        )
        // Filled heart uses the dynamic accent colour (default red or
        // user-picked); outline keeps its built-in #888888 grey.
        if (isFavorite) {
            val accent = at.planqton.fytfm.ui.theme.AccentColors.current(activity, presetRepository)
            ImageViewCompat.setImageTintList(btn, ColorStateList.valueOf(accent))
        } else {
            ImageViewCompat.setImageTintList(btn, null)
        }
    }

    fun updateFilterIcon() {
        dabListFilterBtn?.setImageResource(
            if (callbacks.getShowFavoritesOnly()) R.drawable.ic_folder else R.drawable.ic_folder_all
        )
    }

    /**
     * Updates the DAB+ reception-quality icon next to the ensemble label.
     * Drawable swap on a 3-bar pattern, matching the real OMRI
     * ReceptionQuality enum (BEST/GOOD/OKAY/POOR):
     *   - Best        → all 3 bars (full)
     *   - Good        → 2 bars (half)
     *   - Okay        → 1 bar (weak)
     *   - Poor / Bad  → faded bars + diagonal slash (durchgestrichen)
     *   - !sync       → faded bars + diagonal slash (durchgestrichen)
     * Tint follows the secondary text color so the icon reads on both
     * day and night themes.
     */
    fun updateSignalIndicator(sync: Boolean, quality: String) {
        val icon = dabListSignalIcon ?: return
        val drawableRes = if (!sync) {
            R.drawable.ic_signal_bars_none
        } else when (quality.lowercase()) {
            "best" -> R.drawable.ic_signal_bars_full
            "good" -> R.drawable.ic_signal_bars_half
            "okay" -> R.drawable.ic_signal_bars_weak
            "poor", "bad" -> R.drawable.ic_signal_bars_none
            else -> R.drawable.ic_signal_bars_none
        }
        icon.setImageResource(drawableRes)
        ImageViewCompat.setImageTintList(
            icon,
            ColorStateList.valueOf(
                ContextCompat.getColor(activity, R.color.radio_text_secondary)
            )
        )
        icon.visibility = View.VISIBLE
    }

    companion object {
        private const val VISUALIZER_STYLE_COUNT = 12
        private const val MAX_DRAG_PX = 150f
        private const val SWIPE_THRESHOLD_PX = 100
        private const val SWIPE_VELOCITY_THRESHOLD = 100
        private const val SWIPE_DAMPING = 0.4f
        private const val SWIPE_RELEASE_DURATION_MS = 200L
        private const val STATION_SLIDE_OUT_DURATION_MS = 150L
        private const val STATION_SLIDE_IN_DURATION_MS = 200L
    }
}
