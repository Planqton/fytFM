package at.planqton.fytfm.viewmodel

import android.graphics.Bitmap
import at.planqton.fytfm.FrequencyScaleView
import at.planqton.fytfm.data.RadioStation
import at.planqton.fytfm.deezer.TrackInfo

/**
 * Represents RDS (Radio Data System) information for FM/AM
 */
data class RdsData(
    val ps: String = "",      // Program Service name (station name)
    val rt: String = "",      // Radio Text
    val pi: Int = 0,          // Program Identification
    val pty: Int = 0,         // Program Type
    val rssi: Int = 0,        // Signal strength
    val tp: Int = 0,          // Traffic Program
    val ta: Int = 0,          // Traffic Announcement
    val afList: List<Int> = emptyList()  // Alternative Frequencies
)

/**
 * Represents DAB+ specific state
 */
data class DabState(
    val serviceId: Int = 0,
    val ensembleId: Int = 0,
    val serviceLabel: String? = null,
    val ensembleLabel: String? = null,
    val dls: String? = null,              // Dynamic Label Segment (DAB equivalent of RT)
    val slideshow: Bitmap? = null,        // MOT Slideshow image
    val dlPlusArtist: String? = null,
    val dlPlusTitle: String? = null,
    val isRecording: Boolean = false,
    /** Reception quality label as the OMRI tuner reports it (e.g. "good",
     *  "fair", "poor"). String, not Int — matches the controller payload. */
    val receptionQuality: String = "",
    val snr: Int = 0,
    val signalSync: Boolean = false
)

/**
 * Represents Deezer track information overlay
 */
data class DeezerState(
    val isEnabled: Boolean = false,
    val currentTrack: TrackInfo? = null,
    val coverPath: String? = null,
    val isSearching: Boolean = false,
    val lastQuery: String? = null,
    /** RT/DLS string the [coverPath] was matched for. Used as a freshness
     *  key — if the broadcaster's RT/DLS changes, the cached cover is stale. */
    val coverSourceKey: String? = null,
    /** Debug-overlay state — diagnostic fields surfaced in the bug-report
     *  builder + the developer overlay; not part of the user-visible flow. */
    val debugStatus: String? = null,
    val debugOriginalRt: String? = null,
    val debugStrippedRt: String? = null,
)

/**
 * Main UI State for the Radio application
 * This sealed class represents all possible states of the radio
 */
sealed class RadioUiState {

    /**
     * Radio is completely off
     */
    object Off : RadioUiState()

    /**
     * Radio is in FM or AM mode
     */
    data class FmAm(
        val frequency: Float = 87.5f,
        val mode: FrequencyScaleView.RadioMode = FrequencyScaleView.RadioMode.FM,
        val isPlaying: Boolean = false,
        val isMuted: Boolean = false,
        val currentStation: RadioStation? = null,
        val rdsData: RdsData = RdsData(),
        val deezerState: DeezerState = DeezerState(),
        val isStereo: Boolean = false,
        val isLocalMode: Boolean = false,
        val isMonoMode: Boolean = false
    ) : RadioUiState()

    /**
     * Radio is in DAB+ mode
     */
    data class Dab(
        val isPlaying: Boolean = false,
        val isMuted: Boolean = false,
        val currentStation: RadioStation? = null,
        val dabState: DabState = DabState(),
        val deezerState: DeezerState = DeezerState()
    ) : RadioUiState()

    /**
     * Radio is scanning for stations
     */
    data class Scanning(
        val mode: FrequencyScaleView.RadioMode,
        val progress: Int = 0,
        val currentFrequency: Float = 0f,
        val foundStations: List<RadioStation> = emptyList(),
        val statusMessage: String = ""
    ) : RadioUiState()
}

/**
 * Station list state (favorites, all stations)
 */
data class StationListState(
    val stations: List<RadioStation> = emptyList(),
    val showFavoritesOnly: Boolean = false,
    val isCarouselMode: Boolean = true,
    val selectedIndex: Int = -1
)
