package at.planqton.fytfm.spotify

import android.util.Log
import kotlinx.coroutines.*

/**
 * Combines fragmented Radio Text (RT) into "Artist - Title" format using Spotify API
 *
 * Problem:
 * - Some stations send: "Artist - Title" (complete)
 * - Others send separately: "Artist" then "Title" (or vice versa)
 * - Sometimes random messages are mixed in
 *
 * Solution:
 * - Use Spotify Search API to identify and format track info correctly
 */
class RtCombiner(
    private val spotifyClient: SpotifyClient?,
    private val spotifyCache: SpotifyCache? = null,
    private val isCacheEnabled: (() -> Boolean)? = null,
    private val isNetworkAvailable: (() -> Boolean)? = null,
    private val onDebugUpdate: ((status: String, rtInput: String?, query: String?, trackInfo: TrackInfo?) -> Unit)? = null
) {
    companion object {
        private const val TAG = "RtCombiner"
        private const val BUFFER_TIMEOUT_MS = 15000L // 15 seconds timeout for buffer
        private const val MAX_BUFFER_SIZE = 3 // Max RT messages to buffer
    }

    // RT-Buffer per station (PI-Code)
    private val rtBuffer = mutableMapOf<Int, MutableList<RtEntry>>()
    private val lastResult = mutableMapOf<Int, String>()
    private val lastTrackInfo = mutableMapOf<Int, TrackInfo>() // Cache TrackInfo for debug display
    private val lastProcessedRt = mutableMapOf<Int, String>() // Track last processed RT to avoid duplicates
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    data class RtEntry(
        val text: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * Process incoming RT
     * @param pi PI-Code of the station
     * @param rt Radio Text
     * @return Formatted "Artist - Title" or null if not yet determined
     */
    suspend fun processRt(pi: Int, rt: String): String? {
        val trimmedRt = rt.trim()
        if (trimmedRt.isBlank()) return null

        // Skip if RT hasn't changed - return cached result but update debug display
        if (lastProcessedRt[pi] == trimmedRt) {
            val cachedTrack = lastTrackInfo[pi]
            if (cachedTrack != null) {
                onDebugUpdate?.invoke("Cached", trimmedRt, null, cachedTrack)
            }
            return lastResult[pi]
        }
        lastProcessedRt[pi] = trimmedRt

        Log.d(TAG, "Processing RT for PI=$pi: $trimmedRt")
        // Clear other fields when processing starts
        onDebugUpdate?.invoke("Processing...", trimmedRt, null, null)

        // Check if Spotify is available - if not, try local cache only
        if (spotifyClient == null) {
            Log.d(TAG, "No Spotify client, trying local cache only")

            // Try local cache
            val cachedTrack = if (trimmedRt.contains(" - ")) {
                val parts = trimmedRt.split(" - ", limit = 2)
                spotifyCache?.searchLocalByParts(parts[0].trim(), parts[1].trim())
                    ?: spotifyCache?.searchLocal(trimmedRt)
            } else {
                spotifyCache?.searchLocal(trimmedRt)
            }

            if (cachedTrack != null) {
                val result = "${cachedTrack.artist} - ${cachedTrack.title}"
                Log.d(TAG, "Found in local cache (offline): $result")
                onDebugUpdate?.invoke("Cached (offline)", trimmedRt, "local cache", cachedTrack)
                lastResult[pi] = result
                lastTrackInfo[pi] = cachedTrack
                return result
            }

            onDebugUpdate?.invoke("No Spotify", trimmedRt, null, null)
            return trimmedRt
        }

        // Already contains " - "? Likely complete "Artist - Title"
        if (trimmedRt.contains(" - ")) {
            val parts = trimmedRt.split(" - ", limit = 2)
            if (parts.size == 2) {
                val (result, track) = validateWithSpotify(parts[0].trim(), parts[1].trim(), trimmedRt)
                if (result != null) {
                    lastResult[pi] = result
                    if (track != null) lastTrackInfo[pi] = track
                    clearBuffer(pi)
                    return result
                }
            }
        }

        // Add to buffer
        addToBuffer(pi, trimmedRt)

        // Try to combine buffer entries
        val buffer = rtBuffer[pi] ?: return null
        if (buffer.size >= 2) {
            val (result, track) = tryBufferCombinations(pi, buffer)
            if (result != null) {
                lastResult[pi] = result
                if (track != null) lastTrackInfo[pi] = track
                clearBuffer(pi)
                return result
            }
        }

        // Return last known result if available
        return lastResult[pi]
    }

    private fun addToBuffer(pi: Int, rt: String) {
        val buffer = rtBuffer.getOrPut(pi) { mutableListOf() }

        // Remove old entries
        val now = System.currentTimeMillis()
        buffer.removeAll { now - it.timestamp > BUFFER_TIMEOUT_MS }

        // Don't add duplicates
        if (buffer.none { it.text.equals(rt, ignoreCase = true) }) {
            buffer.add(RtEntry(rt))
            Log.d(TAG, "Added to buffer[PI=$pi]: $rt (size=${buffer.size})")
        }

        // Limit buffer size
        while (buffer.size > MAX_BUFFER_SIZE) {
            buffer.removeAt(0)
        }
    }

    private suspend fun tryBufferCombinations(pi: Int, buffer: List<RtEntry>): Pair<String?, TrackInfo?> {
        if (buffer.size < 2) return Pair(null, null)

        val texts = buffer.map { it.text }
        Log.d(TAG, "Trying combinations from buffer: $texts")

        // Try different combinations
        for (i in texts.indices) {
            for (j in texts.indices) {
                if (i == j) continue

                val artist = texts[i]
                val title = texts[j]
                val query = "$artist $title"

                onDebugUpdate?.invoke("Searching...", texts.joinToString(" | "), query, null)

                val (result, track) = validateWithSpotify(artist, title, query)
                if (result != null) {
                    return Pair(result, track)
                }
            }
        }

        // Try simple concatenation search
        val combined = texts.joinToString(" ")
        onDebugUpdate?.invoke("Searching combined...", texts.joinToString(" | "), combined, null)

        // Try local cache first
        val cachedTrack = spotifyCache?.searchLocal(combined)
        if (cachedTrack != null) {
            val result = "${cachedTrack.artist} - ${cachedTrack.title}"
            Log.d(TAG, "Found in local cache (combined): $result")
            onDebugUpdate?.invoke("Cached (local)", combined, "local cache", cachedTrack)
            return Pair(result, cachedTrack)
        }

        // Try Spotify API
        val track = spotifyClient?.searchTrack(combined)
        if (track != null) {
            val result = "${track.artist} - ${track.title}"
            Log.d(TAG, "Found by combined search: $result")
            onDebugUpdate?.invoke("Found!", combined, combined, track)
            // Cache the result
            cacheTrack(track)
            return Pair(result, track)
        }

        return Pair(null, null)
    }

    private suspend fun validateWithSpotify(possibleArtist: String, possibleTitle: String, rawQuery: String): Pair<String?, TrackInfo?> {
        // 1. First try local cache
        val cachedTrack = spotifyCache?.searchLocalByParts(possibleArtist, possibleTitle)
            ?: spotifyCache?.searchLocal(rawQuery)
        if (cachedTrack != null) {
            val result = "${cachedTrack.artist} - ${cachedTrack.title}"
            Log.d(TAG, "Found in local cache: $result")
            onDebugUpdate?.invoke("Cached (local)", rawQuery, "local cache", cachedTrack)
            return Pair(result, cachedTrack)
        }

        // 2. Check if network is available - if not, return null (no API call)
        if (isNetworkAvailable?.invoke() == false) {
            Log.d(TAG, "No network available, skipping Spotify API")
            onDebugUpdate?.invoke("Offline", rawQuery, "no network", null)
            return Pair(null, null)
        }

        // 3. Try Spotify API
        val track = spotifyClient?.searchTrackByParts(possibleArtist, possibleTitle)
        if (track != null) {
            val result = "${track.artist} - ${track.title}"
            Log.d(TAG, "Validated: $result")
            onDebugUpdate?.invoke("Found!", rawQuery, "artist:$possibleArtist track:$possibleTitle", track)
            // Cache the result
            cacheTrack(track)
            return Pair(result, track)
        }

        // Try simple search
        val simpleTrack = spotifyClient?.searchTrack(rawQuery)
        if (simpleTrack != null) {
            val result = "${simpleTrack.artist} - ${simpleTrack.title}"
            Log.d(TAG, "Found by simple search: $result")
            onDebugUpdate?.invoke("Found!", rawQuery, rawQuery, simpleTrack)
            // Cache the result
            cacheTrack(simpleTrack)
            return Pair(result, simpleTrack)
        }

        onDebugUpdate?.invoke("Not found", rawQuery, rawQuery, null)
        return Pair(null, null)
    }

    private fun cacheTrack(track: TrackInfo) {
        // Only cache if enabled
        if (isCacheEnabled?.invoke() != false) {
            spotifyCache?.let { cache ->
                scope.launch {
                    cache.cacheTrack(track)
                }
            }
        }
    }

    private fun clearBuffer(pi: Int) {
        rtBuffer.remove(pi)
    }

    /**
     * Clear all buffers (e.g., when changing station)
     */
    fun clearAll() {
        rtBuffer.clear()
        lastResult.clear()
        lastTrackInfo.clear()
        lastProcessedRt.clear()
    }

    /**
     * Clear buffer for specific station
     */
    fun clearStation(pi: Int) {
        rtBuffer.remove(pi)
        lastResult.remove(pi)
        lastTrackInfo.remove(pi)
        lastProcessedRt.remove(pi)
    }

    /**
     * Get the last known result for a station
     */
    fun getLastResult(pi: Int): String? = lastResult[pi]

    /**
     * Get the last known TrackInfo for a station (for cover art, etc.)
     */
    fun getLastTrackInfo(pi: Int): TrackInfo? = lastTrackInfo[pi]

    /**
     * Cleanup resources
     */
    fun destroy() {
        scope.cancel()
        rtBuffer.clear()
        lastResult.clear()
        lastTrackInfo.clear()
        lastProcessedRt.clear()
    }
}
