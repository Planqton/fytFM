package at.planqton.fytfm.debug

import android.content.Context
import android.graphics.Color
import android.view.View
import at.planqton.fytfm.BuildConfig
import at.planqton.fytfm.FrequencyScaleView
import at.planqton.fytfm.R
import at.planqton.fytfm.databinding.ActivityMainBinding
import com.android.fmradio.FmNative

/**
 * Manager für Debug-Overlay Funktionalität.
 * Extrahiert Debug-bezogene Logik aus MainActivity.
 */
class DebugManager(
    private val context: Context,
    private val binding: ActivityMainBinding
) {
    companion object {

        // Colors for debug UI
        private val COLOR_CYAN = Color.parseColor("#00FFFF")
        private val COLOR_GREEN = Color.parseColor("#00FF00")
        private val COLOR_RED = Color.parseColor("#FF4444")
    }

    // State für DLS Timestamp
    var lastDlsTimestamp: Long = 0L

    /**
     * Per-overlay collapse state, keyed by the overlay container view-id.
     * The state survives toggling the overlay itself off/on, so a window
     * the user collapsed once stays collapsed when reopened.
     */
    private val collapseStates = mutableMapOf<Int, Boolean>()

    /**
     * Stores the plain (un-prefixed) header title for each collapsible
     * overlay so we can re-render the ▼/▶ affordance on every state change
     * without losing the title — and so dynamic-title overlays (RDS ↔ DAB+)
     * can update via [setRdsHeaderTitle].
     */
    private val headerTitles = mutableMapOf<Int, String>()

    /**
     * Per-overlay registration so [toggleCollapse] can re-render after a
     * tap-on-header detected by MainActivity's drag handler. Three fields:
     * the header *container* (skipped when hiding), the title-text holder
     * (gets the ▼/▶ prefix), and the title string itself.
     */
    private data class CollapseConfig(
        val overlay: android.view.ViewGroup,
        val headerContainer: android.view.View,
        val titleHolder: android.widget.TextView,
    )

    private val collapseConfigs = mutableMapOf<Int, CollapseConfig>()

    /** Persistierter Klappzustand pro Overlay (Pref-Key → Boolean).
     *  Eigene Prefs-Datei, damit Debug-State nicht andere App-Settings stört. */
    private val collapsePrefs = context.getSharedPreferences("fytfm_debug_collapse", Context.MODE_PRIVATE)

    /** overlay-view-id → Pref-Key. Pref-Key ist ein stabiler String, weil
     *  die View-IDs zwischen Builds neu vergeben werden können. */
    private val collapseKeys = mutableMapOf<Int, String>()

    init {
        // Register collapse config for every debug sub-window. The actual
        // tap detection is done in MainActivity's drag handler — adding an
        // OnClickListener here would swallow ACTION_DOWN before the drag
        // listener on the overlay container could react, breaking dragging.
        // For Parser, the title text view sits inside a row of tab+clear+
        // export buttons, so headerContainer (the row) and titleHolder
        // (the "Parser" TextView) differ.
        register(binding.debugChecklistOverlay, binding.debugChecklistHeader, binding.debugChecklistHeader, "Debug Windows", "checklist")
        register(binding.debugLayoutOverlay, binding.debugLayoutHeader, binding.debugLayoutHeader, "UI Info", "layout")
        register(binding.debugBuildOverlay, binding.debugBuildHeader, binding.debugBuildHeader, "Build Info", "build")
        register(binding.debugDeezerOverlay, binding.debugDeezerHeader, binding.debugDeezerHeader, "Deezer Debug", "deezer")
        register(binding.debugOverlay, binding.debugHeader, binding.debugHeader, "RDS Debug", "rds")
        register(binding.debugButtonsOverlay, binding.debugButtonsHeader, binding.debugButtonsHeader, "Debug Buttons", "buttons")
        register(binding.debugSwcOverlay, binding.debugSwcHeader, binding.debugSwcHeader, "SWC Debug", "swc")
        register(binding.debugCarouselOverlay, binding.debugCarouselHeader, binding.debugCarouselHeader, "Carousel Debug", "carousel")
        register(binding.debugStationOverlay, binding.debugStationHeader, binding.debugStationHeader, "Station Overlay Debug", "station")
        register(binding.debugTunerOverlay, binding.debugTunerHeader, binding.debugTunerHeader, "Tuner Info", "tuner")
        register(binding.debugParserOverlay, binding.debugParserHeader, binding.debugParserHeaderText, "Parser", "parser")
    }

    private fun register(
        overlay: android.view.ViewGroup,
        headerContainer: android.view.View,
        titleHolder: android.widget.TextView,
        title: String,
        prefKey: String,
    ) {
        collapseConfigs[overlay.id] = CollapseConfig(overlay, headerContainer, titleHolder)
        headerTitles[overlay.id] = title
        collapseKeys[overlay.id] = prefKey
        // Persistierten Zustand laden (default false = ausgeklappt).
        collapseStates[overlay.id] = collapsePrefs.getBoolean(prefKey, false)
        applyCollapseState(overlay.id)
    }

    /**
     * Public entry point for MainActivity's drag handler — flips the collapse
     * state for the overlay with the given id and re-renders the header.
     */
    fun toggleCollapse(overlayId: Int) {
        if (overlayId !in collapseConfigs) return
        val newState = !(collapseStates[overlayId] ?: false)
        collapseStates[overlayId] = newState
        // Sofort persistieren — sonst geht der State beim Activity-Recreate
        // (Theme-Wechsel etc.) verloren bevor onPause/onStop schreibt.
        collapseKeys[overlayId]?.let {
            collapsePrefs.edit().putBoolean(it, newState).apply()
        }
        applyCollapseState(overlayId)
    }

    private fun applyCollapseState(overlayId: Int) {
        val cfg = collapseConfigs[overlayId] ?: return
        val collapsed = collapseStates[overlayId] ?: false
        val visibility = if (collapsed) View.GONE else View.VISIBLE
        for (i in 0 until cfg.overlay.childCount) {
            val child = cfg.overlay.getChildAt(i)
            if (child !== cfg.headerContainer) child.visibility = visibility
        }
        val title = headerTitles[overlayId] ?: return
        cfg.titleHolder.text = if (collapsed) "▶ $title" else "▼ $title"
    }

    /**
     * Updates the RDS/DAB debug overlay title (it switches between
     * "RDS Debug" and "DAB+ Debug" depending on the active mode) while
     * preserving the current collapse affordance.
     */
    fun setRdsHeaderTitle(title: String) {
        headerTitles[binding.debugOverlay.id] = title
        applyCollapseState(binding.debugOverlay.id)
    }

    /**
     * Aktualisiert Build-Informationen im Debug-Overlay.
     */
    fun updateBuildDebugInfo() {
        binding.debugBuildVersion.text = BuildConfig.VERSION_NAME
        binding.debugBuildVersionCode.text = BuildConfig.VERSION_CODE.toString()
        binding.debugBuildDate.text = BuildConfig.BUILD_DATE
        binding.debugBuildTime.text = BuildConfig.BUILD_TIME
        binding.debugBuildType.text = BuildConfig.BUILD_TYPE
        binding.debugBuildPackage.text = BuildConfig.APPLICATION_ID
        binding.debugBuildMinSdk.text = "API ${context.applicationInfo.minSdkVersion}"
        binding.debugBuildTargetSdk.text = "API ${context.applicationInfo.targetSdkVersion}"
        binding.debugBuildDeviceSdk.text = "API ${android.os.Build.VERSION.SDK_INT} (${android.os.Build.VERSION.RELEASE})"
    }

    /**
     * Aktualisiert Tuner-Verfügbarkeit im Debug-Overlay.
     */
    fun updateTunerDebugInfo(currentMode: FrequencyScaleView.RadioMode, isDabAvailable: Boolean) {
        // Current active tuner
        binding.debugTunerActive.text = currentMode.name
        binding.debugTunerActive.setTextColor(COLOR_CYAN)

        // FM availability
        val fmAvailable = FmNative.isLibraryLoaded()
        binding.debugTunerFm.text = context.getString(if (fmAvailable) R.string.available else R.string.not_available)
        binding.debugTunerFm.setTextColor(if (fmAvailable) COLOR_GREEN else COLOR_RED)

        // AM availability (same as FM - shares tuner)
        binding.debugTunerAm.text = context.getString(if (fmAvailable) R.string.available else R.string.not_available)
        binding.debugTunerAm.setTextColor(if (fmAvailable) COLOR_GREEN else COLOR_RED)

        // DAB availability
        binding.debugTunerDab.text = context.getString(if (isDabAvailable) R.string.available else R.string.not_available)
        binding.debugTunerDab.setTextColor(if (isDabAvailable) COLOR_GREEN else COLOR_RED)
    }

    /**
     * Setzt DAB Debug-Felder auf Ladezustand zurück.
     */
    fun resetDabDebugInfo() {
        binding.debugPs.text = "..."
        binding.debugPi.text = "..."
        binding.debugPty.text = "..."
        binding.debugRt.text = "..."
        binding.debugFreq.text = "..."
        binding.debugAf.text = "..."
        binding.debugRssi.text = "..."
        binding.debugTpTa.text = ""
        binding.debugAfUsing.text = ""
        binding.labelRt.text = "DLS:"
    }

    /**
     * Aktualisiert RDS Debug-Informationen.
     */
    fun updateDebugInfo(
        ps: String? = null,
        pi: String? = null,
        pty: String? = null,
        rt: String? = null,
        rssiStr: String? = null,
        freq: Float? = null,
        af: String? = null,
        tpTa: String? = null,
        afUsing: String? = null
    ) {
        if (binding.debugOverlay.visibility != View.VISIBLE) return

        ps?.let { binding.debugPs.text = it.ifEmpty { "--------" } }
        pi?.let { binding.debugPi.text = if (it.isNotEmpty()) it else "----" }
        pty?.let { binding.debugPty.text = if (it.isNotEmpty()) it else "--" }
        rt?.let { binding.debugRt.text = it.ifEmpty { "--------------------------------" } }
        rssiStr?.let { binding.debugRssi.text = it }
        freq?.let { binding.debugFreq.text = String.format("%.1f MHz", it) }
        af?.let { binding.debugAf.text = it.ifEmpty { "----" } }
        afUsing?.let { binding.debugAfUsing.text = it }
        tpTa?.let { binding.debugTpTa.text = it }
    }

    /**
     * Aktualisiert das Debug-Overlay für DAB-Modus.
     */
    fun updateDabDebugInfo(dabStation: at.planqton.fytfm.dab.DabStation? = null, dls: String? = null) {
        if (binding.debugOverlay.visibility != View.VISIBLE) return

        // Header auf DAB Debug ändern
        setRdsHeaderTitle("DAB+ Debug")
        binding.btnDlsLog.visibility = View.VISIBLE

        // Labels für DAB ändern
        binding.labelPs.text = "Label:"
        binding.labelPi.text = "SID:"
        binding.labelPty.text = "Block:"

        // DLS Label mit Zeitangabe
        val dlsAgeText = if (lastDlsTimestamp > 0) {
            val ageSeconds = (System.currentTimeMillis() - lastDlsTimestamp) / 1000
            "DLS (${ageSeconds}s):"
        } else {
            "DLS:"
        }
        binding.labelRt.text = dlsAgeText
        binding.labelRssi.text = "SNR:"
        binding.labelAf.text = "EID:"

        dabStation?.let { station ->
            binding.debugPs.text = station.serviceLabel.ifEmpty { "--------" }
            binding.debugPi.text = String.format("0x%04X", station.serviceId)
            val block = frequencyToChannelBlock(station.ensembleFrequencyKHz)
            binding.debugPty.text = block
            binding.debugFreq.text = "$block (${String.format("%.3f", station.ensembleFrequencyKHz / 1000000.0f)} MHz)"
            binding.debugAf.text = String.format("0x%04X %s", station.ensembleId, station.ensembleLabel)
            binding.debugTpTa.text = ""
            binding.debugAfUsing.text = ""
        }

        dls?.let { binding.debugRt.text = it.ifEmpty { "--------------------------------" } }
    }

    /**
     * Aktualisiert die DAB Empfangsstatistiken.
     */
    fun updateDabReceptionStats(sync: Boolean, quality: String, snr: Int) {
        if (binding.debugOverlay.visibility != View.VISIBLE) return
        val syncStatus = if (sync) "✓" else "✗"
        binding.debugRssi.text = "$snr dB $syncStatus"
        binding.debugTpTa.text = quality
    }

    /**
     * Setzt das Debug-Overlay zurück auf FM/AM RDS-Modus.
     */
    fun resetDebugToRds() {
        setRdsHeaderTitle("RDS Debug")
        binding.btnDlsLog.visibility = View.GONE
        binding.labelPs.text = "PS:"
        binding.labelPi.text = "PI:"
        binding.labelPty.text = "PTY:"
        binding.labelRt.text = "RT:"
        binding.labelRssi.text = "RSSI:"
        binding.labelAf.text = "AF:"
    }

    /**
     * Aktualisiert das DLS Timestamp Label.
     */
    fun updateDlsTimestampLabel() {
        if (binding.debugOverlay.visibility != View.VISIBLE) return
        if (lastDlsTimestamp > 0) {
            val ageSeconds = (System.currentTimeMillis() - lastDlsTimestamp) / 1000
            binding.labelRt.text = "DLS (${ageSeconds}s):"
        }
    }

    /**
     * Aktualisiert Layout Debug-Informationen.
     */
    fun updateLayoutDebugInfo(currentUiCoverSource: String) {
        binding.debugUiCoverArtwork.text = currentUiCoverSource
    }

    /**
     * Convert DAB frequency (Hz) to channel block name (e.g., 5A, 5B, 5C, 6A, etc.)
     */
    private fun frequencyToChannelBlock(frequencyHz: Int): String {
        val freqMhz = frequencyHz / 1000000.0
        val index = kotlin.math.round((freqMhz - 174.928) / 1.712).toInt()

        if (index < 0 || index > 40) return "?"

        val channelNumber = 5 + (index / 4)
        val subChannel = "ABCD"[index % 4]

        return "$channelNumber$subChannel"
    }

    /**
     * Setzt Deezer Debug-Felder zurück.
     */
    fun clearDeezerDebugFields() {
        binding.debugDeezerSource.text = "--"
        binding.debugDeezerSource.setTextColor(Color.parseColor("#AAAAAA"))
        binding.debugDeezerCoverImage.setImageResource(android.R.drawable.ic_menu_gallery)

        // Track fields
        binding.debugDeezerArtist.text = "--"
        binding.debugDeezerTitle.text = "--"
        binding.debugDeezerAllArtists.text = "--"
        binding.debugDeezerDuration.text = "--"
        binding.debugDeezerPopularity.text = "--"
        binding.debugDeezerExplicit.text = "--"
        binding.debugDeezerTrackDisc.text = "--"
        binding.debugDeezerISRC.text = "--"

        // Album fields
        binding.debugDeezerAlbum.text = "--"
        binding.debugDeezerAlbumType.text = "--"
        binding.debugDeezerTotalTracks.text = "--"
        binding.debugDeezerReleaseDate.text = "--"

        // IDs & URLs
        binding.debugDeezerTrackId.text = "--"
        binding.debugDeezerAlbumId.text = "--"
        binding.debugDeezerUrl.text = "--"
        binding.debugDeezerAlbumUrl.text = "--"
        binding.debugDeezerPreviewUrl.text = "--"
        binding.debugDeezerCoverUrl.text = "--"
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0) return "--"
        val seconds = (ms / 1000) % 60
        val minutes = (ms / 1000) / 60
        return String.format("%d:%02d", minutes, seconds)
    }

    /**
     * Aktualisiert Carousel Debug-Informationen.
     */
    fun updateCarouselDebugInfo(
        pendingPosition: Int,
        timerStart: Long,
        currentFreq: Float,
        isAM: Boolean,
        getPositionForFrequency: (Float, Boolean) -> Int,
        recyclerView: androidx.recyclerview.widget.RecyclerView?
    ) {
        // Timer countdown
        if (pendingPosition >= 0) {
            val elapsed = System.currentTimeMillis() - timerStart
            val remaining = ((2000 - elapsed) / 100) / 10f
            binding.debugCarouselTimer.text = "%.1fs".format(remaining.coerceAtLeast(0f))
        } else {
            binding.debugCarouselTimer.text = "idle"
        }

        // Active tile
        val currentPos = getPositionForFrequency(currentFreq, isAM)
        binding.debugCarouselActiveTile.text = if (currentPos >= 0) {
            "Pos $currentPos (${if (pendingPosition >= 0) "scrolling" else "idle"})"
        } else {
            "no match"
        }

        // Current scroll position
        recyclerView?.let { rv ->
            val layoutManager = rv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
            val firstVisible = layoutManager?.findFirstVisibleItemPosition() ?: -1
            val lastVisible = layoutManager?.findLastVisibleItemPosition() ?: -1
            binding.debugCarouselPosition.text = "$firstVisible - $lastVisible"
            binding.debugCarouselPadding.text = "${rv.paddingStart}px"
        }
    }

    /**
     * Aktualisiert Deezer Status und RT Input im Debug-Overlay.
     */
    fun updateDeezerStatusAndInput(status: String, originalRt: String?, strippedRt: String?) {
        if (binding.debugDeezerOverlay.visibility != View.VISIBLE) return
        binding.debugDeezerStatus.text = status
        val orig = originalRt ?: "--"
        val search = strippedRt ?: "--"
        binding.debugDeezerRtInput.text = "Orig: $orig\nSuche: $search"
    }

    /**
     * Aktualisiert Deezer Source Anzeige (LOKAL/DEEZER).
     */
    fun updateDeezerSource(status: String) {
        val isFromLocalCache = status.contains("Cached", ignoreCase = true) ||
                               status.contains("offline", ignoreCase = true)
        val isFromDeezerOnline = status == "Found!"

        val sourceText = when {
            isFromLocalCache -> "LOKAL"
            isFromDeezerOnline -> "DEEZER"
            else -> "..."
        }
        val sourceColor = when {
            isFromLocalCache -> Color.parseColor("#FFAA00")  // Orange
            isFromDeezerOnline -> Color.parseColor("#FEAA2D")  // Deezer Orange
            else -> Color.parseColor("#AAAAAA")
        }
        binding.debugDeezerSource.text = sourceText
        binding.debugDeezerSource.setTextColor(sourceColor)
    }

    /**
     * Aktualisiert "Not Found" Status im Deezer Debug.
     */
    fun updateDeezerNotFound() {
        binding.debugDeezerStatus.text = "Not found - waiting"
        binding.debugDeezerSource.text = "---"
        binding.debugDeezerSource.setTextColor(Color.parseColor("#FF5555"))
    }

    /**
     * Aktualisiert Deezer Track-Informationen im Debug-Overlay.
     */
    fun updateDeezerTrackInfo(
        artist: String,
        title: String,
        allArtists: List<String>,
        durationMs: Long,
        popularity: Int,
        explicit: Boolean,
        trackNumber: Int,
        discNumber: Int,
        isrc: String?,
        album: String?,
        albumType: String?,
        totalTracks: Int,
        releaseDate: String?,
        trackId: String?,
        albumId: String?,
        deezerUrl: String?,
        albumUrl: String?,
        previewUrl: String?,
        coverUrl: String?,
        coverUrlMedium: String?
    ) {
        // TRACK Section
        binding.debugDeezerArtist.text = artist
        binding.debugDeezerTitle.text = title
        binding.debugDeezerAllArtists.text =
            if (allArtists.isNotEmpty()) allArtists.joinToString(", ") else "--"
        binding.debugDeezerDuration.text = formatDuration(durationMs)
        binding.debugDeezerPopularity.text = "$popularity/100"
        binding.debugDeezerExplicit.text = if (explicit) "Yes" else "No"
        binding.debugDeezerTrackDisc.text = "$trackNumber/$discNumber"
        binding.debugDeezerISRC.text = isrc ?: "--"

        // ALBUM Section
        binding.debugDeezerAlbum.text = album ?: "--"
        binding.debugDeezerAlbumType.text = albumType ?: "--"
        binding.debugDeezerTotalTracks.text = if (totalTracks > 0) totalTracks.toString() else "--"
        binding.debugDeezerReleaseDate.text = releaseDate ?: "--"

        // IDs & URLs Section
        binding.debugDeezerTrackId.text = trackId ?: "--"
        binding.debugDeezerAlbumId.text = albumId ?: "--"
        binding.debugDeezerUrl.text = deezerUrl ?: "--"
        binding.debugDeezerAlbumUrl.text = albumUrl ?: "--"
        binding.debugDeezerPreviewUrl.text = previewUrl ?: "--"
        binding.debugDeezerCoverUrl.text = coverUrl ?: coverUrlMedium ?: "--"
    }
}
