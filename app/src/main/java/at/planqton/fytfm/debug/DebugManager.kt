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
        private const val TAG = "DebugManager"

        // Colors for debug UI
        private val COLOR_CYAN = Color.parseColor("#00FFFF")
        private val COLOR_GREEN = Color.parseColor("#00FF00")
        private val COLOR_RED = Color.parseColor("#FF4444")
    }

    // State für DLS Timestamp
    var lastDlsTimestamp: Long = 0L

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
        binding.debugHeader.text = "DAB+ Debug"
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
        binding.debugHeader.text = "RDS Debug"
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
    fun frequencyToChannelBlock(frequencyHz: Int): String {
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

    /**
     * Formatiert Millisekunden als mm:ss.
     */
    fun formatDuration(ms: Long): String {
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
