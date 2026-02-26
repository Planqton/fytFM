package at.planqton.fytfm.spotify

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class TrackInfo(
    // Track basics
    val artist: String,
    val title: String,
    val trackId: String? = null,
    val spotifyUrl: String? = null,
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
    val albumType: String? = null,  // album, single, compilation
    val totalTracks: Int = 0,
    val releaseDate: String? = null,
    val coverUrl: String? = null,
    val coverUrlSmall: String? = null,  // 64px version
    val coverUrlMedium: String? = null  // 300px version
)

class SpotifyClient(
    private val clientId: String,
    private val clientSecret: String
) {
    companion object {
        private const val TAG = "SpotifyClient"

        // Debug: Simuliert fehlende Internetverbindung
        @Volatile
        var debugInternetDisabled = false
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val tokenLock = Any()
    @Volatile
    private var accessToken: String? = null
    @Volatile
    private var tokenExpirationTime: Long = 0

    /**
     * Get access token using Client Credentials flow
     */
    suspend fun getAccessToken(): String? = withContext(Dispatchers.IO) {
        if (clientId.isBlank() || clientSecret.isBlank()) {
            Log.w(TAG, "Spotify credentials missing")
            return@withContext null
        }

        // Thread-safe Token-Check
        synchronized(tokenLock) {
            val currentToken = accessToken
            if (System.currentTimeMillis() < tokenExpirationTime && currentToken != null) {
                return@withContext currentToken
            }
        }

        val credentials = "$clientId:$clientSecret"
        val encodedCredentials = Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)

        val requestBody = FormBody.Builder()
            .add("grant_type", "client_credentials")
            .build()

        val request = Request.Builder()
            .url("https://accounts.spotify.com/api/token")
            .header("Authorization", "Basic $encodedCredentials")
            .post(requestBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Spotify Auth failed: ${response.code}")
                    return@withContext null
                }

                val responseBody = response.body?.string()
                if (responseBody.isNullOrBlank()) {
                    Log.e(TAG, "Empty response body from Spotify Auth")
                    return@withContext null
                }

                val json = JSONObject(responseBody)
                val newToken = json.getString("access_token")
                val expiresIn = json.getLong("expires_in")

                synchronized(tokenLock) {
                    accessToken = newToken
                    tokenExpirationTime = System.currentTimeMillis() + expiresIn * 1000
                }

                Log.d(TAG, "Got access token, expires in ${expiresIn}s")
                newToken
            }
        } catch (e: Exception) {
            Log.e(TAG, "Token request failed", e)
            null
        }
    }

    /**
     * Search for a track on Spotify with multiple fallback strategies
     * @param query The search query (e.g., "Artist Title" or separate parts)
     * @return TrackInfo if found, null otherwise
     */
    suspend fun searchTrack(query: String): TrackInfo? = withContext(Dispatchers.IO) {
        // Debug: Simulierte Netzwerkstörung
        if (debugInternetDisabled) {
            Log.d(TAG, "searchTrack: Internet disabled (debug)")
            return@withContext null
        }

        val token = getAccessToken() ?: return@withContext null

        // Try simple search first
        var result = doSearch(token, query, "simple")

        // Try cleaned query
        if (result == null) {
            val cleanedQuery = query
                .replace(Regex("\\(.*?\\)|\\[.*?\\]"), "")
                .replace(Regex("feat\\..*|ft\\..*|&.*", RegexOption.IGNORE_CASE), "")
                .trim()
            if (cleanedQuery != query) {
                result = doSearch(token, cleanedQuery, "cleaned")
            }
        }

        result
    }

    /**
     * Search with specific artist and title parameters
     */
    suspend fun searchTrackByParts(artist: String?, title: String?): TrackInfo? = withContext(Dispatchers.IO) {
        // Debug: Simulierte Netzwerkstörung
        if (debugInternetDisabled) {
            Log.d(TAG, "searchTrackByParts: Internet disabled (debug)")
            return@withContext null
        }

        val token = getAccessToken() ?: return@withContext null

        // Build query with Spotify's search syntax
        val query = buildString {
            if (!title.isNullOrBlank()) append("track:$title ")
            if (!artist.isNullOrBlank()) append("artist:$artist")
        }.trim()

        if (query.isBlank()) return@withContext null

        // Try original
        var result = doSearch(token, query, "original")

        // Try cleaned (handle "x", "&", "feat.", "vs." in artist names)
        if (result == null && !artist.isNullOrBlank() && !title.isNullOrBlank()) {
            val cleanArtist = artist
                .replace(Regex("\\s+x\\s+.*|\\s+&\\s+.*|\\s+feat\\..*|\\s+ft\\..*|\\s+vs\\..*", RegexOption.IGNORE_CASE), "")
                .trim()
            val cleanTitle = title.replace(Regex("\\(.*?\\)|\\[.*?\\]"), "").trim()
            val cleanQuery = "track:$cleanTitle artist:$cleanArtist"
            result = doSearch(token, cleanQuery, "cleaned")
        }

        // Try with both artists if "x" or "&" was in the name
        if (result == null && !artist.isNullOrBlank() && !title.isNullOrBlank()) {
            val artistParts = artist.split(Regex("\\s+x\\s+|\\s+&\\s+|\\s+feat\\.\\s*|\\s+ft\\.\\s*", RegexOption.IGNORE_CASE))
            if (artistParts.size >= 2) {
                // Try with second artist
                val secondArtist = artistParts[1].trim()
                val query2 = "track:$title artist:$secondArtist"
                result = doSearch(token, query2, "second_artist")
            }
        }

        // Try swapped (maybe artist and title were mixed up)
        if (result == null && !artist.isNullOrBlank() && !title.isNullOrBlank()) {
            val swappedQuery = "track:$artist artist:$title"
            result = doSearch(token, swappedQuery, "swapped")
        }

        // Try combined search without field specifiers (last resort, but only for longer titles)
        if (result == null && !title.isNullOrBlank() && title.length >= 5) {
            val combinedQuery = "${artist ?: ""} $title".trim()
            result = doSearch(token, combinedQuery, "combined_free")
        }

        result
    }

    /**
     * Search for tracks excluding specific trackIds
     * @param query Search query
     * @param skipTrackIds List of track IDs to skip
     * @return First non-skipped track or null
     */
    suspend fun searchTrackWithSkip(query: String, skipTrackIds: List<String>): TrackInfo? = withContext(Dispatchers.IO) {
        if (debugInternetDisabled) {
            Log.d(TAG, "searchTrackWithSkip: Internet disabled (debug)")
            return@withContext null
        }
        if (skipTrackIds.isEmpty()) {
            return@withContext searchTrack(query)
        }

        val token = getAccessToken() ?: return@withContext null

        // Get multiple results and filter
        val tracks = doSearchMultiple(token, query, "with_skip", limit = skipTrackIds.size + 3)
        tracks.firstOrNull { it.trackId !in skipTrackIds }
    }

    private fun doSearchMultiple(token: String, query: String, step: String, limit: Int = 5): List<TrackInfo> {
        Log.d(TAG, "Searching multiple [$step]: $query (limit=$limit)")

        val searchUrl = HttpUrl.Builder()
            .scheme("https")
            .host("api.spotify.com")
            .addPathSegments("v1/search")
            .addQueryParameter("q", query)
            .addQueryParameter("type", "track")
            .addQueryParameter("limit", limit.toString())
            .build()

        val request = Request.Builder()
            .url(searchUrl)
            .header("Authorization", "Bearer $token")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Search error ($step): ${response.code}")
                    return emptyList()
                }

                val responseBody = response.body?.string()
                if (responseBody.isNullOrBlank()) {
                    Log.e(TAG, "Empty response ($step)")
                    return emptyList()
                }

                val json = JSONObject(responseBody)
                val items = json.getJSONObject("tracks").getJSONArray("items")
                val results = mutableListOf<TrackInfo>()

                for (i in 0 until items.length()) {
                    val trackItem = items.optJSONObject(i) ?: continue
                    parseTrackItem(trackItem, step)?.let { results.add(it) }
                }

                Log.d(TAG, "Found ${results.size} tracks for [$step]")
                return results
            }
        } catch (e: Exception) {
            Log.e(TAG, "Search multiple failed ($step)", e)
            return emptyList()
        }
    }

    private fun parseTrackItem(trackItem: JSONObject, step: String): TrackInfo? {
        try {
            val trackName = trackItem.getString("name")
            val trackId = trackItem.optString("id")
            val durationMs = trackItem.optLong("duration_ms", 0)
            val popularity = trackItem.optInt("popularity", 0)
            val explicit = trackItem.optBoolean("explicit", false)
            val previewUrl = trackItem.optString("preview_url", null)?.takeIf { it.isNotBlank() && it != "null" }
            val trackNumber = trackItem.optInt("track_number", 0)
            val discNumber = trackItem.optInt("disc_number", 0)

            val externalIds = trackItem.optJSONObject("external_ids")
            val isrc = externalIds?.optString("isrc", null)?.takeIf { it.isNotBlank() && it != "null" }

            val externalUrls = trackItem.optJSONObject("external_urls")
            val spotifyUrl = externalUrls?.optString("spotify")

            val artistArray = trackItem.getJSONArray("artists")
            val allArtists = mutableListOf<String>()
            val allArtistIds = mutableListOf<String>()
            for (j in 0 until artistArray.length()) {
                val artistObj = artistArray.getJSONObject(j)
                allArtists.add(artistObj.getString("name"))
                artistObj.optString("id")?.takeIf { it.isNotBlank() }?.let { allArtistIds.add(it) }
            }
            val artistName = allArtists.firstOrNull() ?: "Unknown Artist"

            val album = trackItem.optJSONObject("album")
            val albumName = album?.optString("name")
            val albumId = album?.optString("id")
            val albumExternalUrls = album?.optJSONObject("external_urls")
            val albumUrl = albumExternalUrls?.optString("spotify")
            val albumType = album?.optString("album_type")
            val totalTracks = album?.optInt("total_tracks", 0) ?: 0
            val releaseDate = album?.optString("release_date")

            val images = album?.optJSONArray("images")
            var coverUrl: String? = null
            var coverUrlMedium: String? = null
            var coverUrlSmall: String? = null
            if (images != null) {
                for (i in 0 until images.length()) {
                    val img = images.getJSONObject(i)
                    val url = img.optString("url")
                    val width = img.optInt("width", 0)
                    when {
                        width >= 600 && coverUrl == null -> coverUrl = url
                        width in 200..400 && coverUrlMedium == null -> coverUrlMedium = url
                        width in 50..100 && coverUrlSmall == null -> coverUrlSmall = url
                    }
                }
                if (coverUrl == null && images.length() > 0) {
                    coverUrl = images.getJSONObject(0).optString("url")
                }
            }

            return TrackInfo(
                artist = artistName,
                title = trackName,
                trackId = trackId,
                spotifyUrl = spotifyUrl,
                durationMs = durationMs,
                popularity = popularity,
                explicit = explicit,
                previewUrl = previewUrl,
                trackNumber = trackNumber,
                discNumber = discNumber,
                isrc = isrc,
                allArtists = allArtists,
                allArtistIds = allArtistIds,
                album = albumName,
                albumId = albumId,
                albumUrl = albumUrl,
                albumType = albumType,
                totalTracks = totalTracks,
                releaseDate = releaseDate,
                coverUrl = coverUrl,
                coverUrlMedium = coverUrlMedium,
                coverUrlSmall = coverUrlSmall
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse track item ($step)", e)
            return null
        }
    }

    private fun doSearch(token: String, query: String, step: String): TrackInfo? {
        Log.d(TAG, "Searching [$step]: $query")

        val searchUrl = HttpUrl.Builder()
            .scheme("https")
            .host("api.spotify.com")
            .addPathSegments("v1/search")
            .addQueryParameter("q", query)
            .addQueryParameter("type", "track")
            .addQueryParameter("limit", "1")
            .build()

        val request = Request.Builder()
            .url(searchUrl)
            .header("Authorization", "Bearer $token")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Search error ($step): ${response.code}")
                    return null
                }

                val responseBody = response.body?.string()
                if (responseBody.isNullOrBlank()) {
                    Log.e(TAG, "Empty response ($step)")
                    return null
                }

                val json = JSONObject(responseBody)
                val trackItem = json.getJSONObject("tracks").getJSONArray("items").optJSONObject(0)
                    ?: return null

                // Track basics
                val trackName = trackItem.getString("name")
                val trackId = trackItem.optString("id")
                val durationMs = trackItem.optLong("duration_ms", 0)
                val popularity = trackItem.optInt("popularity", 0)
                val explicit = trackItem.optBoolean("explicit", false)
                val previewUrl = trackItem.optString("preview_url", null)?.takeIf { it.isNotBlank() && it != "null" }
                val trackNumber = trackItem.optInt("track_number", 0)
                val discNumber = trackItem.optInt("disc_number", 0)

                // ISRC from external_ids
                val externalIds = trackItem.optJSONObject("external_ids")
                val isrc = externalIds?.optString("isrc", null)?.takeIf { it.isNotBlank() && it != "null" }

                // Spotify URL
                val externalUrls = trackItem.optJSONObject("external_urls")
                val spotifyUrl = externalUrls?.optString("spotify")

                // All artists
                val artistArray = trackItem.getJSONArray("artists")
                val allArtists = mutableListOf<String>()
                val allArtistIds = mutableListOf<String>()
                for (i in 0 until artistArray.length()) {
                    val artistObj = artistArray.getJSONObject(i)
                    allArtists.add(artistObj.getString("name"))
                    artistObj.optString("id")?.takeIf { it.isNotBlank() }?.let { allArtistIds.add(it) }
                }
                val artistName = allArtists.firstOrNull() ?: "Unknown Artist"

                // Album info
                val album = trackItem.optJSONObject("album")
                val albumName = album?.optString("name")
                val albumId = album?.optString("id")
                val albumExternalUrls = album?.optJSONObject("external_urls")
                val albumUrl = albumExternalUrls?.optString("spotify")
                val albumType = album?.optString("album_type")
                val totalTracks = album?.optInt("total_tracks", 0) ?: 0
                val releaseDate = album?.optString("release_date")

                // Cover images (different sizes)
                val images = album?.optJSONArray("images")
                var coverUrl: String? = null
                var coverUrlMedium: String? = null
                var coverUrlSmall: String? = null
                if (images != null) {
                    for (i in 0 until images.length()) {
                        val img = images.getJSONObject(i)
                        val url = img.optString("url")
                        val width = img.optInt("width", 0)
                        when {
                            width >= 600 && coverUrl == null -> coverUrl = url
                            width in 200..400 && coverUrlMedium == null -> coverUrlMedium = url
                            width in 50..100 && coverUrlSmall == null -> coverUrlSmall = url
                        }
                    }
                    // Fallback: use first image if no large found
                    if (coverUrl == null && images.length() > 0) {
                        coverUrl = images.getJSONObject(0).optString("url")
                    }
                }

                Log.d(TAG, "Found [$step]: $artistName - $trackName (popularity=$popularity, duration=${durationMs}ms, explicit=$explicit)")
                return TrackInfo(
                    artist = artistName,
                    title = trackName,
                    trackId = trackId,
                    spotifyUrl = spotifyUrl,
                    durationMs = durationMs,
                    popularity = popularity,
                    explicit = explicit,
                    previewUrl = previewUrl,
                    trackNumber = trackNumber,
                    discNumber = discNumber,
                    isrc = isrc,
                    allArtists = allArtists,
                    allArtistIds = allArtistIds,
                    album = albumName,
                    albumId = albumId,
                    albumUrl = albumUrl,
                    albumType = albumType,
                    totalTracks = totalTracks,
                    releaseDate = releaseDate,
                    coverUrl = coverUrl,
                    coverUrlMedium = coverUrlMedium,
                    coverUrlSmall = coverUrlSmall
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Search failed ($step)", e)
            return null
        }
    }

    /**
     * Check if credentials are valid by trying to get a token
     */
    suspend fun validateCredentials(): Boolean {
        return getAccessToken() != null
    }
}
