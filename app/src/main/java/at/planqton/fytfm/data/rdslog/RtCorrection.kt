package at.planqton.fytfm.data.rdslog

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Correction entry for RT (Radio Text) to track:
 * - IGNORED: This RT should be completely ignored (no Spotify search)
 * - SKIP_TRACK: This trackId doesn't match this RT (try next result)
 */
@Entity(
    tableName = "rt_corrections",
    indices = [
        Index(value = ["rtNormalized"]),
        Index(value = ["type"])
    ]
)
data class RtCorrection(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // The normalized RT text (lowercase, trimmed)
    val rtNormalized: String,

    // Original RT text (for display)
    val rtOriginal: String,

    // Type: "IGNORED" or "SKIP_TRACK"
    val type: String,

    // For SKIP_TRACK: the trackId that doesn't match
    // For IGNORED: null
    val skipTrackId: String? = null,

    // For SKIP_TRACK: track info for display
    val skipTrackArtist: String? = null,
    val skipTrackTitle: String? = null,

    // Timestamp when correction was added
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        const val TYPE_IGNORED = "IGNORED"
        const val TYPE_SKIP_TRACK = "SKIP_TRACK"
        const val TYPE_STRIP_STRING = "STRIP_STRING"

        fun normalizeRt(rt: String): String {
            return rt.lowercase().trim()
        }
    }
}
