package at.planqton.fytfm.ui.cover

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import at.planqton.fytfm.R
import at.planqton.fytfm.data.PresetRepository

/**
 * Owns the DAB cover-source indicator rendering (the little dots below the
 * cover views that signal which of the 4 sources — DAB_LOGO, STATION_LOGO,
 * SLIDESHOW, DEEZER — are available and which is currently active) plus
 * related helpers. Extracted from MainActivity in stages; Round 1 moves the
 * indicator rendering (~115 lines) here as a pure function of state + views.
 *
 * Later rounds will move the tap-to-cycle logic, lock-state, and the main
 * cover-decision tree (updateDabCoverDisplay).
 */
class CoverDisplayController(
    private val context: Context,
    private val coverTrio: CoverViewTrio,
    private val dotContainersProvider: () -> List<DotContainerSpec>,
    private val deezerWatermarkProvider: () -> List<View?>,
    private val presetRepository: PresetRepository,
    private val radioLogoPathProvider: () -> String?,
    private val currentSlideshowProvider: () -> Bitmap?,
    private val currentDeezerCoverPathProvider: () -> String?,
    /** Invoked whenever a local cover-image path is actively rendered — the debug
     *  overlay hooks in to track which file is currently displayed. */
    private val onLocalCoverImageLoaded: (path: String) -> Unit,
    /** Called after each cover refresh to push the new source-snapshot to the
     *  MediaSession / notification. */
    private val mediaSessionSync: (MediaSessionSnapshot) -> Unit,
) {

    /**
     * Full snapshot of what the cover renderer ended up showing — the caller
     * pushes this to the MediaSession so the notification stays in sync.
     */
    data class MediaSessionSnapshot(
        val slideshowBitmap: Bitmap?,
        val radioLogoPath: String?,
        val deezerCoverPath: String?,
    )

    /**
     * Per-container size hint for the indicator dots. Compact = small
     * thumbnail covers (Now-Playing-Bar, Carousel-Now-Playing-Bar). Standard
     * = the full-size DAB-list cover where the dots can be normal size.
     */
    data class DotContainerSpec(val view: LinearLayout?, val compact: Boolean)

    companion object {
        private const val TAG = "CoverDisplayCtrl"
    }

    /**
     * Main cover-refresh entry point for DAB mode. Refreshes
     * [availableCoverSources], resolves the source to actually show (respecting
     * lock / manual selection / auto-pick fallback), renders it to the three
     * cover views via the injected [coverTrio], updates the Deezer watermark,
     * refreshes the indicator dots, and syncs the MediaSession snapshot.
     */
    fun updateDabDisplay() {
        refreshAvailableSources()
        val radioLogoPath = radioLogoPathProvider()

        val locked = lockedCoverSource
        val sourceToShow = when {
            coverSourceLocked && locked != null && availableCoverSources.contains(locked) -> locked
            selectedCoverSourceIndex in availableCoverSources.indices ->
                availableCoverSources[selectedCoverSourceIndex]
            else -> when {
                availableCoverSources.contains(DabCoverSource.DEEZER) -> DabCoverSource.DEEZER
                availableCoverSources.contains(DabCoverSource.SLIDESHOW) -> DabCoverSource.SLIDESHOW
                availableCoverSources.contains(DabCoverSource.STATION_LOGO) -> DabCoverSource.STATION_LOGO
                else -> DabCoverSource.DAB_LOGO
            }
        }

        when (sourceToShow) {
            DabCoverSource.DEEZER -> {
                val localCover = currentDeezerCoverPathProvider()!!
                currentUiCoverSource = localCover
                coverTrio.loadFromPath(localCover)
                onLocalCoverImageLoaded(localCover)
                mediaSessionSync(MediaSessionSnapshot(null, radioLogoPath, localCover))
                Log.d(TAG, "Cover: Deezer")
            }
            DabCoverSource.SLIDESHOW -> {
                val bitmap = currentSlideshowProvider()!!
                currentUiCoverSource = "slideshow"
                coverTrio.setBitmap(bitmap)
                mediaSessionSync(MediaSessionSnapshot(bitmap, radioLogoPath, null))
                Log.d(TAG, "Cover: Slideshow")
            }
            DabCoverSource.STATION_LOGO -> {
                currentUiCoverSource = radioLogoPath!!
                coverTrio.loadFromPath(radioLogoPath)
                mediaSessionSync(MediaSessionSnapshot(null, radioLogoPath, null))
                Log.d(TAG, "Cover: Station Logo")
            }
            DabCoverSource.DAB_LOGO -> {
                currentUiCoverSource = "drawable:ic_fytfm_dab_plus_light"
                coverTrio.setPlaceholder(R.drawable.ic_fytfm_dab_plus_light)
                mediaSessionSync(MediaSessionSnapshot(null, null, null))
                Log.d(TAG, "Cover: DAB Logo")
            }
        }

        updateDeezerWatermarks(sourceToShow == DabCoverSource.DEEZER)
        updateIndicators(canToggle = true)
    }

    /**
     * Tap-to-cycle. Releases any pinned source, then advances
     * [selectedCoverSourceIndex] to the next available source (wrapping at the
     * end). Re-renders the cover views. No-ops when only one source exists.
     */
    fun toggleDabCover() {
        if (coverSourceLocked) {
            coverSourceLocked = false
            lockedCoverSource = null
            presetRepository.setCoverSourceLocked(false)
            presetRepository.setLockedCoverSource(null)
        }

        refreshAvailableSources()
        if (availableCoverSources.size <= 1) return

        selectedCoverSourceIndex =
            if (selectedCoverSourceIndex < 0) 0
            else (selectedCoverSourceIndex + 1) % availableCoverSources.size

        updateDabDisplay()
    }

    /**
     * Long-press action: toggles the pinned-source lock.
     * Locked → unlock (no selection survives). Unlocked → pin whatever is
     * currently showing (either the explicitly picked source or the
     * auto-mode best-available one). Persists the lock state so it survives
     * process death. Re-renders the indicator dots so the lock icon
     * appears / disappears.
     */
    fun toggleCoverSourceLock() {
        if (coverSourceLocked) {
            coverSourceLocked = false
            lockedCoverSource = null
            presetRepository.setCoverSourceLocked(false)
            presetRepository.setLockedCoverSource(null)
        } else {
            lockedCoverSource = if (selectedCoverSourceIndex in availableCoverSources.indices) {
                availableCoverSources[selectedCoverSourceIndex]
            } else when {
                availableCoverSources.contains(DabCoverSource.DEEZER) -> DabCoverSource.DEEZER
                availableCoverSources.contains(DabCoverSource.SLIDESHOW) -> DabCoverSource.SLIDESHOW
                availableCoverSources.contains(DabCoverSource.STATION_LOGO) -> DabCoverSource.STATION_LOGO
                else -> DabCoverSource.DAB_LOGO
            }
            coverSourceLocked = true
            presetRepository.setCoverSourceLocked(true)
            presetRepository.setLockedCoverSource(lockedCoverSource?.name)
        }
        updateIndicators(canToggle = true)
    }

    /** Flips the "streaming via Deezer" watermark visibility on all cover views. */
    fun updateDeezerWatermarks(showDeezer: Boolean) {
        val visibility = if (showDeezer) View.VISIBLE else View.GONE
        deezerWatermarkProvider().forEach { it?.visibility = visibility }
    }

    /**
     * User-selected cover-source index. -1 = auto-mode (best available wins),
     * 0..N-1 = explicit pick into [availableCoverSources].
     */
    var selectedCoverSourceIndex: Int = -1

    /** Cached result of [computeAvailableCoverSources]; refresh via [refreshAvailableSources]. */
    var availableCoverSources: List<DabCoverSource> = emptyList()
        private set

    /** True when the user pinned a specific source via long-press. */
    var coverSourceLocked: Boolean = false

    /** The pinned source when [coverSourceLocked] is true. */
    var lockedCoverSource: DabCoverSource? = null

    /**
     * Human-readable tag of what is currently rendered — used by the debug
     * overlay to show the user which source path the cover came from. Values
     * are intentionally free-form strings, not an enum.
     */
    var currentUiCoverSource: String = "(none)"

    /**
     * Returns the currently available sources: DAB_LOGO is always there;
     * STATION_LOGO / SLIDESHOW / DEEZER appear only when their underlying
     * data is present (and the settings flag, for Deezer, is enabled).
     */
    fun computeAvailableCoverSources(): List<DabCoverSource> {
        val sources = mutableListOf(DabCoverSource.DAB_LOGO)
        if (radioLogoPathProvider() != null) sources += DabCoverSource.STATION_LOGO
        if (currentSlideshowProvider() != null) sources += DabCoverSource.SLIDESHOW
        val deezerEnabled = presetRepository.isDeezerEnabledDab()
        if (deezerEnabled && !currentDeezerCoverPathProvider().isNullOrBlank()) {
            sources += DabCoverSource.DEEZER
        }
        return sources
    }

    /** Refreshes [availableCoverSources] from the current providers. */
    fun refreshAvailableSources() {
        availableCoverSources = computeAvailableCoverSources()
    }

    /**
     * Re-renders the dot row below each cover view to show:
     * - coloured dot per source (accent when available, grey when not)
     * - ring around the currently-active source
     * - lock icon at the end when the user pinned a source
     *
     * When [canToggle] is false (e.g. not in DAB mode) the containers are
     * hidden — the dots only make sense while the user can switch sources.
     */
    fun updateIndicators(canToggle: Boolean) {
        val specs = dotContainersProvider()

        // Feste Reihenfolge: DAB_LOGO, STATION_LOGO, SLIDESHOW, DEEZER (nur wenn aktiviert)
        val deezerEnabled = presetRepository.isDeezerEnabledDab()
        val allSources = if (deezerEnabled) {
            listOf(DabCoverSource.DAB_LOGO, DabCoverSource.STATION_LOGO, DabCoverSource.SLIDESHOW, DabCoverSource.DEEZER)
        } else {
            listOf(DabCoverSource.DAB_LOGO, DabCoverSource.STATION_LOGO, DabCoverSource.SLIDESHOW)
        }

        if (!canToggle) {
            specs.forEach { it.view?.visibility = View.GONE }
            return
        }

        val accentColor = at.planqton.fytfm.ui.theme.AccentColors.current(context, presetRepository)
        val inactiveColor = ContextCompat.getColor(context, R.color.radio_text_secondary)

        val locked = lockedCoverSource
        val selectedSource: DabCoverSource = when {
            coverSourceLocked && locked != null && availableCoverSources.contains(locked) -> locked
            selectedCoverSourceIndex >= 0 && selectedCoverSourceIndex < availableCoverSources.size -> {
                availableCoverSources[selectedCoverSourceIndex]
            }
            else -> when {
                availableCoverSources.contains(DabCoverSource.DEEZER) -> DabCoverSource.DEEZER
                availableCoverSources.contains(DabCoverSource.SLIDESHOW) -> DabCoverSource.SLIDESHOW
                availableCoverSources.contains(DabCoverSource.STATION_LOGO) -> DabCoverSource.STATION_LOGO
                else -> DabCoverSource.DAB_LOGO
            }
        }

        specs.forEach { spec ->
            val container = spec.view ?: return@forEach
            container.removeAllViews()
            container.visibility = View.VISIBLE

            val density = context.resources.displayMetrics.density
            // Compact = small thumbnail covers (Now-Playing-Bar,
            // Carousel-Now-Playing-Bar). Standard = full-size DAB-list cover.
            val dotSize = ((if (spec.compact) 4 else 6) * density).toInt()
            val ringSize = ((if (spec.compact) 7 else 10) * density).toInt()
            val spacing = ((if (spec.compact) 3 else 4) * density).toInt()

            for ((i, source) in allSources.withIndex()) {
                val isAvailable = availableCoverSources.contains(source)
                val isSelected = source == selectedSource

                val frame = FrameLayout(context).apply {
                    layoutParams = LinearLayout.LayoutParams(ringSize, ringSize).apply {
                        marginStart = if (i > 0) spacing else 0
                    }
                }

                val dotDrawable = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (isAvailable) accentColor else inactiveColor)
                    setSize(dotSize, dotSize)
                }
                val dot = View(context).apply {
                    layoutParams = FrameLayout.LayoutParams(dotSize, dotSize).apply {
                        gravity = Gravity.CENTER
                    }
                    background = dotDrawable
                }
                frame.addView(dot)

                if (isSelected) {
                    val ringDrawable = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setStroke((1 * density).toInt(), accentColor)
                        setSize(ringSize, ringSize)
                    }
                    val ring = View(context).apply {
                        layoutParams = FrameLayout.LayoutParams(ringSize, ringSize).apply {
                            gravity = Gravity.CENTER
                        }
                        background = ringDrawable
                    }
                    frame.addView(ring)
                }

                container.addView(frame)
            }

            if (coverSourceLocked) {
                val lockIcon = TextView(context).apply {
                    text = "🔒"
                    textSize = 8f
                    includeFontPadding = false
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(ringSize, ringSize).apply {
                        marginStart = spacing
                        topMargin = (-2 * density).toInt()
                    }
                }
                container.addView(lockIcon)
            }
        }
    }
}
