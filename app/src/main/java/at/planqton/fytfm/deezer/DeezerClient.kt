package at.planqton.fytfm.deezer

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

data class TrackInfo(
    // Track basics
    val artist: String,
    val title: String,
    val trackId: String? = null,
    val deezerUrl: String? = null,
    val durationMs: Long = 0,
    val popularity: Int = 0,
    val explicit: Boolean = false,
    val previewUrl: String? = null,
    val trackNumber: Int = 0,
    val discNumber: Int = 0,
    val isrc: String? = null,

    // All artists (not just first)
    val allArtists: List<String> = emptyList(),
    val allArtistIds: List<String> = emptyList(),

    // Album info
    val album: String? = null,
    val albumId: String? = null,
    val albumUrl: String? = null,
    val albumType: String? = null,
    val totalTracks: Int = 0,
    val releaseDate: String? = null,
    val coverUrl: String? = null,
    val coverUrlSmall: String? = null,
    val coverUrlMedium: String? = null
)

/**
 * Sealed class for network errors
 */
sealed class DeezerError : Exception() {
    object NoNetwork : DeezerError()
    object Timeout : DeezerError()
    object ServerError : DeezerError()
    data class HttpError(val code: Int) : DeezerError()
    data class ParseError(override val cause: Throwable) : DeezerError()
}

class DeezerClient {
    companion object {
        private const val TAG = "DeezerClient"
        private const val DEEZER_API = "https://api.deezer.com"
        private const val MAX_RETRIES = 2
        private const val RETRY_DELAY_MS = 500L

        // Debug: Simuliert fehlende Internetverbindung
        @Volatile
        var debugInternetDisabled = false
    }

    // Callback for network errors (optional)
    var onNetworkError: ((DeezerError) -> Unit)? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Search for a track on Deezer with multiple fallback strategies
     */
    suspend fun searchTrack(query: String): TrackInfo? = withContext(Dispatchers.IO) {
        if (debugInternetDisabled) {
            Log.d(TAG, "searchTrack: Internet disabled (debug)")
            return@withContext null
        }

        // Try simple search first
        var result = doSearch(query, "simple")

        // Try cleaned query
        if (result == null) {
            val cleanedQuery = query
                .replace(Regex("\\(.*?\\)|\\[.*?\\]"), "")
                .replace(Regex("feat\\..*|ft\\..*|&.*", RegexOption.IGNORE_CASE), "")
                .trim()
            if (cleanedQuery != query) {
                result = doSearch(cleanedQuery, "cleaned")
            }
        }

        result
    }

    /**
     * Search with specific artist and title parameters
     */
    suspend fun searchTrackByParts(artist: String?, title: String?): TrackInfo? = withContext(Dispatchers.IO) {
        if (debugInternetDisabled) {
            Log.d(TAG, "searchTrackByParts: Internet disabled (debug)")
            return@withContext null
        }

        if (artist.isNullOrBlank() && title.isNullOrBlank()) return@withContext null

        // Build query with Deezer's search syntax
        val query = buildString {
            if (!artist.isNullOrBlank()) append("artist:\"$artist\" ")
            if (!title.isNullOrBlank()) append("track:\"$title\"")
        }.trim()

        if (query.isBlank()) return@withContext null

        // Try original
        var result = doSearch(query, "original")

        // Try cleaned (handle "x", "&", "feat.", "vs." in artist names)
        if (result == null && !artist.isNullOrBlank() && !title.isNullOrBlank()) {
            val cleanArtist = artist
                .replace(Regex("\\s+x\\s+.*|\\s+&\\s+.*|\\s+feat\\..*|\\s+ft\\..*|\\s+vs\\..*", RegexOption.IGNORE_CASE), "")
                .trim()
            val cleanTitle = title.replace(Regex("\\(.*?\\)|\\[.*?\\]"), "").trim()
            val cleanQuery = "artist:\"$cleanArtist\" track:\"$cleanTitle\""
            result = doSearch(cleanQuery, "cleaned")
        }

        // Try with both artists if "x" or "&" was in the name
        if (result == null && !artist.isNullOrBlank() && !title.isNullOrBlank()) {
            val artistParts = artist.split(Regex("\\s+x\\s+|\\s+&\\s+|\\s+feat\\.\\s*|\\s+ft\\.\\s*", RegexOption.IGNORE_CASE))
            if (artistParts.size >= 2) {
                val secondArtist = artistParts[1].trim()
                val query2 = "artist:\"$secondArtist\" track:\"$title\""
                result = doSearch(query2, "second_artist")
            }
        }

        // Try swapped (maybe artist and title were mixed up)
        if (result == null && !artist.isNullOrBlank() && !title.isNullOrBlank()) {
            val swappedQuery = "artist:\"$title\" track:\"$artist\""
            result = doSearch(swappedQuery, "swapped")
        }

        // Try combined search without field specifiers (last resort)
        if (result == null && !title.isNullOrBlank() && title.length >= 5) {
            val combinedQuery = "${artist ?: ""} $title".trim()
            result = doSearch(combinedQuery, "combined_free")
        }

        result
    }

    /**
     * Search for tracks excluding specific trackIds
     */
    suspend fun searchTrackWithSkip(query: String, skipTrackIds: List<String>): TrackInfo? = withContext(Dispatchers.IO) {
        if (debugInternetDisabled) {
            Log.d(TAG, "searchTrackWithSkip: Internet disabled (debug)")
            return@withContext null
        }
        if (skipTrackIds.isEmpty()) {
            return@withContext searchTrack(query)
        }

        // Get multiple results and filter
        val tracks = doSearchMultiple(query, "with_skip", limit = skipTrackIds.size + 3)
        tracks.firstOrNull { it.trackId !in skipTrackIds }
    }

    private suspend fun doSearchMultiple(query: String, step: String, limit: Int = 5): List<TrackInfo> {
        Log.d(TAG, "Searching multiple [$step]: $query (limit=$limit)")

        val searchUrl = HttpUrl.Builder()
            .scheme("https")
            .host("api.deezer.com")
            .addPathSegment("search")
            .addQueryParameter("q", query)
            .addQueryParameter("limit", limit.toString())
            .build()

        val request = Request.Builder()
            .url(searchUrl)
            .build()

        for (attempt in 1..MAX_RETRIES) {
            try {
                return withContext(Dispatchers.IO) {
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            if (response.code in 500..599) {
                                throw IOException("Server error: ${response.code}")
                            }
                            Log.e(TAG, "Search error ($step): ${response.code}")
                            return@withContext emptyList()
                        }

                        val responseBody = response.body?.string()
                        if (responseBody.isNullOrBlank()) {
                            Log.e(TAG, "Empty response ($step)")
                            return@withContext emptyList()
                        }

                        try {
                            val json = JSONObject(responseBody)
                            val items = json.optJSONArray("data") ?: return@withContext emptyList()
                            val results = mutableListOf<TrackInfo>()

                            for (i in 0 until items.length()) {
                                val trackItem = items.optJSONObject(i) ?: continue
                                parseTrackItem(trackItem, step)?.let { results.add(it) }
                            }

                            Log.d(TAG, "Found ${results.size} tracks for [$step]")
                            results
                        } catch (e: JSONException) {
                            Log.e(TAG, "JSON parse error ($step)", e)
                            emptyList()
                        }
                    }
                }
            } catch (e: SocketTimeoutException) {
                Log.w(TAG, "Timeout ($step), attempt $attempt/$MAX_RETRIES")
                if (attempt < MAX_RETRIES) delay(RETRY_DELAY_MS)
            } catch (e: UnknownHostException) {
                Log.e(TAG, "No network ($step)")
                return emptyList()
            } catch (e: IOException) {
                Log.w(TAG, "IO error ($step), attempt $attempt/$MAX_RETRIES: ${e.message}")
                if (attempt < MAX_RETRIES) delay(RETRY_DELAY_MS)
            }
        }

        Log.e(TAG, "Search multiple failed after $MAX_RETRIES attempts ($step)")
        return emptyList()
    }

    private fun parseTrackItem(trackItem: JSONObject, step: String): TrackInfo? {
        try {
            val trackName = trackItem.getString("title")
            val trackId = trackItem.optLong("id", 0).toString()
            val durationSec = trackItem.optInt("duration", 0)
            val durationMs = durationSec * 1000L
            val rank = trackItem.optInt("rank", 0)
            val popularity = (rank / 10000).coerceIn(0, 100) // Normalize rank to 0-100
            val explicit = trackItem.optBoolean("explicit_lyrics", false)
            val previewUrl = trackItem.optString("preview", null)?.takeIf { it.isNotBlank() && it != "null" }
            val deezerUrl = trackItem.optString("link", null)?.takeIf { it.isNotBlank() }

            val artistObj = trackItem.optJSONObject("artist")
            val artistName = artistObj?.optString("name") ?: "Unknown Artist"
            val artistId = artistObj?.optLong("id", 0)?.toString()

            val albumObj = trackItem.optJSONObject("album")
            val albumName = albumObj?.optString("title")
            val albumId = albumObj?.optLong("id", 0)?.toString()

            // Cover images from album
            val coverSmall = albumObj?.optString("cover_small")
            val coverMedium = albumObj?.optString("cover_medium")
            val coverBig = albumObj?.optString("cover_big")
            val coverXl = albumObj?.optString("cover_xl")

            // Use largest available as primary
            val coverUrl = coverXl ?: coverBig ?: coverMedium ?: coverSmall

            return TrackInfo(
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
                coverUrlMedium = coverMedium
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse track item ($step)", e)
            return null
        }
    }

    private suspend fun doSearch(query: String, step: String): TrackInfo? {
        Log.d(TAG, "Searching [$step]: $query")

        val searchUrl = HttpUrl.Builder()
            .scheme("https")
            .host("api.deezer.com")
            .addPathSegment("search")
            .addQueryParameter("q", query)
            .addQueryParameter("limit", "1")
            .build()

        val request = Request.Builder()
            .url(searchUrl)
            .build()

        var lastError: Exception? = null

        for (attempt in 1..MAX_RETRIES) {
            try {
                return withContext(Dispatchers.IO) {
                    client.newCall(request).execute().use { response ->
                        when {
                            response.isSuccessful -> {
                                val responseBody = response.body?.string()
                                if (responseBody.isNullOrBlank()) {
                                    Log.e(TAG, "Empty response ($step)")
                                    return@withContext null
                                }

                                try {
                                    val json = JSONObject(responseBody)
                                    val trackItem = json.optJSONArray("data")?.optJSONObject(0)
                                        ?: return@withContext null

                                    val result = parseTrackItem(trackItem, step)
                                    if (result != null) {
                                        Log.d(TAG, "Found [$step]: ${result.artist} - ${result.title} (popularity=${result.popularity}, duration=${result.durationMs}ms)")
                                    }
                                    result
                                } catch (e: JSONException) {
                                    Log.e(TAG, "JSON parse error ($step)", e)
                                    onNetworkError?.invoke(DeezerError.ParseError(e))
                                    null
                                }
                            }
                            response.code in 500..599 -> {
                                Log.e(TAG, "Server error ($step): ${response.code}, attempt $attempt/$MAX_RETRIES")
                                onNetworkError?.invoke(DeezerError.ServerError)
                                throw IOException("Server error: ${response.code}")
                            }
                            response.code == 429 -> {
                                Log.w(TAG, "Rate limited ($step), waiting before retry")
                                throw IOException("Rate limited")
                            }
                            else -> {
                                Log.e(TAG, "HTTP error ($step): ${response.code}")
                                onNetworkError?.invoke(DeezerError.HttpError(response.code))
                                null
                            }
                        }
                    }
                }
            } catch (e: SocketTimeoutException) {
                Log.w(TAG, "Timeout ($step), attempt $attempt/$MAX_RETRIES")
                lastError = e
                onNetworkError?.invoke(DeezerError.Timeout)
                if (attempt < MAX_RETRIES) delay(RETRY_DELAY_MS)
            } catch (e: UnknownHostException) {
                Log.e(TAG, "No network ($step)")
                onNetworkError?.invoke(DeezerError.NoNetwork)
                return null // Don't retry on no network
            } catch (e: IOException) {
                Log.w(TAG, "IO error ($step), attempt $attempt/$MAX_RETRIES: ${e.message}")
                lastError = e
                if (attempt < MAX_RETRIES) delay(RETRY_DELAY_MS)
            }
        }

        if (lastError != null) {
            Log.e(TAG, "Search failed after $MAX_RETRIES attempts ($step)", lastError)
        }
        return null
    }
}
