package at.planqton.fytfm.deezer

import org.json.JSONObject

/**
 * Pure parsing helpers for Deezer's `/search` JSON responses. Extracted
 * from [DeezerClient] so the fragile field-by-field extraction logic
 * (cover-size priority, popularity normalisation, missing-id defaults)
 * can be unit-tested without spinning up HTTP infrastructure.
 *
 * Stateless and side-effect free: all functions return a value or null;
 * caller logs context.
 */
internal object DeezerResponseParser {

    /**
     * Decodes a single `data[i]` track entry into a [TrackInfo]. Returns
     * null if the JSON is missing required fields (`title`) or any decode
     * step throws.
     *
     * Behaviour pinned by tests:
     * - Cover priority: xl → big → medium → small (largest available wins).
     * - `popularity` = rank / 10000, clamped to 0..100.
     * - `previewUrl` and `deezerUrl` get nulled out for blank / literal-"null" values.
     * - Missing artist object → `"Unknown Artist"`, no artistId.
     */
    fun parseTrackItem(trackItem: JSONObject): TrackInfo? {
        return try {
            val trackName = trackItem.getString("title")
            val trackId = trackItem.optLong("id", 0).toString()
            val durationSec = trackItem.optInt("duration", 0)
            val durationMs = durationSec * 1000L
            val rank = trackItem.optInt("rank", 0)
            val popularity = normaliseRankToPopularity(rank)
            val explicit = trackItem.optBoolean("explicit_lyrics", false)
            val previewUrl = trackItem.optString("preview")
                .takeIf { it.isNotBlank() && it != "null" }
            val deezerUrl = trackItem.optString("link").takeIf { it.isNotBlank() }

            val artistObj = trackItem.optJSONObject("artist")
            val artistName = artistObj?.optString("name")?.takeIf { it.isNotBlank() } ?: "Unknown Artist"
            val artistId = artistObj?.optLong("id", 0)?.toString()

            val albumObj = trackItem.optJSONObject("album")
            val albumName = albumObj?.optString("title")?.takeIf { it.isNotBlank() }
            val albumId = albumObj?.optLong("id", 0)?.toString()

            val coverSmall = albumObj?.optString("cover_small")?.takeIf { it.isNotBlank() }
            val coverMedium = albumObj?.optString("cover_medium")?.takeIf { it.isNotBlank() }
            val coverBig = albumObj?.optString("cover_big")?.takeIf { it.isNotBlank() }
            val coverXl = albumObj?.optString("cover_xl")?.takeIf { it.isNotBlank() }
            val coverUrl = pickBestCover(small = coverSmall, medium = coverMedium, big = coverBig, xl = coverXl)

            TrackInfo(
                artist = artistName,
                title = trackName,
                trackId = trackId,
                deezerUrl = deezerUrl,
                durationMs = durationMs,
                popularity = popularity,
                explicit = explicit,
                previewUrl = previewUrl,
                trackNumber = 0,
                discNumber = 0,
                isrc = null,
                allArtists = listOf(artistName),
                allArtistIds = listOfNotNull(artistId),
                album = albumName,
                albumId = albumId,
                albumUrl = null,
                albumType = null,
                totalTracks = 0,
                releaseDate = null,
                coverUrl = coverUrl,
                coverUrlSmall = coverSmall,
                coverUrlMedium = coverMedium,
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Picks the largest available cover URL from Deezer's tiered options.
     * Treats blank/null as missing — callers should not pre-filter.
     */
    fun pickBestCover(small: String?, medium: String?, big: String?, xl: String?): String? {
        return xl?.takeIf { it.isNotBlank() }
            ?: big?.takeIf { it.isNotBlank() }
            ?: medium?.takeIf { it.isNotBlank() }
            ?: small?.takeIf { it.isNotBlank() }
    }

    /**
     * Maps Deezer's open-ended `rank` (typical range 0..1_000_000+) onto
     * the 0..100 popularity scale we surface in the UI. Anything ≥ 1M
     * saturates to 100; negatives clamp to 0.
     */
    fun normaliseRankToPopularity(rank: Int): Int = (rank / 10_000).coerceIn(0, 100)
}
