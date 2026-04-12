package at.planqton.fytfm.deezer

import android.util.Log
import at.planqton.fytfm.data.rdslog.RtCorrection
import at.planqton.fytfm.data.rdslog.RtCorrectionDao
import kotlinx.coroutines.*

/**
 * Combines fragmented Radio Text (RT) into "Artist - Title" format using Deezer API
 *
 * Problem:
 * - Some stations send: "Artist - Title" (complete)
 * - Others send separately: "Artist" then "Title" (or vice versa)
 * - Sometimes random messages are mixed in
 *
 * Solution:
 * - Use Deezer Search API to identify and format track info correctly
 * - DlsParser handles common prefixes, station names and promotional text
 */
class RtCombiner(
    private val deezerClient: DeezerClient?,
    private val deezerCache: DeezerCache? = null,
    private val isCacheEnabled: (() -> Boolean)? = null,
    private val isNetworkAvailable: (() -> Boolean)? = null,
    private val correctionDao: RtCorrectionDao? = null,
    private val onDebugUpdate: ((status: String, originalRt: String?, strippedRt: String?, query: String?, trackInfo: TrackInfo?) -> Unit)? = null,
    private val onCoverDownloaded: ((trackInfo: TrackInfo) -> Unit)? = null
) {
    companion object {
        private const val TAG = "RtCombiner"
        private const val BUFFER_TIMEOUT_MS = 15000L // 15 seconds timeout for buffer
        private const val MAX_BUFFER_SIZE = 3 // Max RT messages to buffer
        private const val SHORT_RT_THRESHOLD = 25 // RT shorter than this without separator should be buffered
        private val SEPARATORS = listOf(" - ", " – ", " — ", " / ", " | ")
    }

    /**
     * Check if RT should be buffered first before searching.
     * Returns true if RT is short and doesn't contain a typical separator.
     * This helps with stations like Kronehit that send Artist and Title as separate RTs.
     */
    private fun shouldBufferFirst(rt: String): Boolean {
        // If RT contains a separator, it's likely complete
        if (SEPARATORS.any { rt.contains(it) }) {
            return false
        }
        // If RT is short, buffer it first
        return rt.length < SHORT_RT_THRESHOLD
    }

    // RT-Buffer per station (PI-Code)
    private val rtBuffer = mutableMapOf<Int, MutableList<RtEntry>>()
    private val lastResult = mutableMapOf<Int, String>()
    private val lastTrackInfo = mutableMapOf<Int, TrackInfo>() // Cache TrackInfo for debug display
    private val lastProcessedRt = mutableMapOf<Int, String>() // Track last processed RT to avoid duplicates
    private val lastSearchRt = mutableMapOf<Int, String>() // Track last stripped RT for debug display
    private val lastOriginalRt = mutableMapOf<Int, String>() // Track last original RT for debug display
    private val bufferResultRts = mutableMapOf<Int, Set<String>>() // RTs that were combined to find last result
    private val currentResultRts = mutableMapOf<Int, Set<String>>() // All RTs belonging to current result (for silent early-out)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    data class RtEntry(
        val text: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    // Current RT for correction buttons
    private var currentRt: String? = null
    private var currentTrackInfo: TrackInfo? = null
    private var currentPi: Int = 0  // Current PI for clearing lastTrackInfo on "Not found"

    /**
     * Get current RT (for correction buttons)
     */
    fun getCurrentRt(): String? = currentRt

    /**
     * Get current TrackInfo (for correction buttons)
     */
    fun getCurrentTrackInfo(): TrackInfo? = currentTrackInfo

    /**
     * Process incoming RT
     * @param pi PI-Code of the station
     * @param rt Radio Text
     * @param frequency Current frequency in MHz (for frequency-specific edit rules)
     * @param rawOriginal Optional raw original text (e.g., unprocessed DLS) for debug display
     * @return Formatted "Artist - Title" or null if not yet determined
     */
    suspend fun processRt(pi: Int, rt: String, frequency: Float? = null, rawOriginal: String? = null, skipBuffer: Boolean = false): String? {
        currentPi = pi  // Store for clearing lastTrackInfo on "Not found"
        val trimmedRt = rt.trim()
        if (trimmedRt.isBlank()) return null

        // Use rawOriginal for debug display if provided (e.g., original DLS before parsing)
        val displayOriginal = rawOriginal?.trim() ?: trimmedRt

        // Silent early-out: If this RT is part of the current result and nothing changed,
        // return cached immediately WITHOUT any debug updates or UI triggers
        val resultRts = currentResultRts[pi]
        val rtLower = trimmedRt.lowercase()
        val cachedResult = lastResult[pi]

        if (resultRts != null && rtLower in resultRts && cachedResult != null) {
            // RT is part of current result - return cached silently
            return cachedResult
        }

        // If we have a cached result but this RT is NOT part of it,
        // it means a NEW song is starting - clear old caches
        if (resultRts != null && rtLower !in resultRts) {
            Log.d(TAG, "New RT '$rtLower' detected (not in $resultRts) - clearing old caches")
            currentResultRts.remove(pi)
            bufferResultRts.remove(pi)
            lastResult.remove(pi)
            lastTrackInfo.remove(pi)
            lastProcessedRt.remove(pi)
        }

        // Check if RT is ignored
        Log.d(TAG, "Checking if RT is ignored...")
        val normalizedRt = RtCorrection.normalizeRt(trimmedRt)
        Log.d(TAG, "Normalized RT: $normalizedRt")
        if (correctionDao?.isRtIgnored(normalizedRt) == true) {
            Log.d(TAG, "RT is ignored: $trimmedRt")
            onDebugUpdate?.invoke("Ignored", displayOriginal, trimmedRt, null, null)
            return null
        }

        // Skip if RT hasn't changed - return cached result but update debug display
        if (lastProcessedRt[pi] == trimmedRt) {
            val cachedTrack = lastTrackInfo[pi]
            if (cachedTrack != null) {
                val originalRt = lastOriginalRt[pi] ?: trimmedRt
                val strippedRt = lastSearchRt[pi] ?: trimmedRt
                onDebugUpdate?.invoke("Cached", originalRt, strippedRt, null, cachedTrack)
            }
            return lastResult[pi]
        }

        // Check if this RT was part of a buffer combination that already found a result
        val bufferRts = bufferResultRts[pi]
        if (bufferRts != null && trimmedRt.lowercase() in bufferRts && lastResult[pi] != null) {
            val cachedTrack = lastTrackInfo[pi]
            if (cachedTrack != null) {
                val originalRt = lastOriginalRt[pi] ?: trimmedRt
                val strippedRt = lastSearchRt[pi] ?: trimmedRt
                Log.d(TAG, "RT '$trimmedRt' is part of buffered result, returning cached: ${lastResult[pi]}")
                onDebugUpdate?.invoke("Cached", originalRt, strippedRt, null, cachedTrack)
            }
            return lastResult[pi]
        }

        lastProcessedRt[pi] = trimmedRt
        lastOriginalRt[pi] = displayOriginal
        currentRt = displayOriginal

        // Use trimmedRt directly for search (DlsParser already handles cleanup)
        val searchRt = trimmedRt

        // Cache the stripped RT for debug display
        lastSearchRt[pi] = searchRt

        Log.d(TAG, "Processing RT for PI=$pi: $displayOriginal")
        onDebugUpdate?.invoke("Processing...", displayOriginal, searchRt, null, null)

        // Check if Deezer is available - if not, try local cache only
        if (deezerClient == null) {
            Log.d(TAG, "No Deezer client, trying local cache only")

            // Try local cache
            val cachedTrack = searchInCache(searchRt)
            if (cachedTrack != null) {
                val result = "${cachedTrack.artist} - ${cachedTrack.title}"
                Log.d(TAG, "Found in local cache (offline): $result")
                onDebugUpdate?.invoke("Cached (offline)", trimmedRt, searchRt, "local cache", cachedTrack)
                lastResult[pi] = result
                lastTrackInfo[pi] = cachedTrack
                return result
            }

            onDebugUpdate?.invoke("No Deezer", trimmedRt, searchRt, null, null)
            return searchRt
        }

        // Check if this RT should be buffered first (short RT without separator)
        // This helps with stations like Kronehit that send Artist and Title separately
        // For DAB+ (skipBuffer=true), always search directly - DLS is usually complete
        val bufferFirst = !skipBuffer && shouldBufferFirst(searchRt)
        Log.d(TAG, "shouldBufferFirst('$searchRt') = $bufferFirst, len=${searchRt.length}, has ' - '=${searchRt.contains(" - ")}, skipBuffer=$skipBuffer")
        if (bufferFirst) {
            Log.d(TAG, "Short RT without separator, buffering first: '$searchRt'")
            onDebugUpdate?.invoke("Buffering...", trimmedRt, searchRt, null, null)
            addToBuffer(pi, searchRt)
            val buffer = rtBuffer[pi] ?: return lastResult[pi]

            // Only search when we have 2+ entries in buffer
            if (buffer.size >= 2) {
                val (bufferResult, bufferTrack) = tryBufferCombinations(pi, buffer)
                if (bufferResult != null) {
                    // Save all buffer RTs so they're recognized as "already processed"
                    val bufferTexts = buffer.map { it.text.lowercase() }.toSet()
                    bufferResultRts[pi] = bufferTexts
                    currentResultRts[pi] = bufferTexts  // For silent early-out on next cycle
                    Log.d(TAG, "SET currentResultRts[$pi] = $bufferTexts")

                    // Update display to show combined RT
                    val combinedRt = buffer.joinToString(" | ") { it.text }
                    lastOriginalRt[pi] = combinedRt
                    lastSearchRt[pi] = combinedRt
                    currentRt = combinedRt

                    lastResult[pi] = bufferResult
                    if (bufferTrack != null) {
                        lastTrackInfo[pi] = bufferTrack
                        currentTrackInfo = bufferTrack
                    }
                    clearBuffer(pi)
                    onDebugUpdate?.invoke("Found!", combinedRt, combinedRt, null, bufferTrack)
                    return bufferResult
                }
            }

            // Not enough buffer entries yet, return last result or null
            return lastResult[pi]
        }

        // Try to find track
        Log.d(TAG, "Calling searchTrack...")
        val (result, track) = searchTrack(pi, searchRt)
        Log.d(TAG, "searchTrack returned: result=$result")

        if (result != null) {
            lastResult[pi] = result
            if (track != null) lastTrackInfo[pi] = track
            clearBuffer(pi)
            return result
        }

        // Add to buffer and try combinations
        addToBuffer(pi, searchRt)
        val buffer = rtBuffer[pi] ?: return lastResult[pi]
        if (buffer.size >= 2) {
            val (bufferResult, bufferTrack) = tryBufferCombinations(pi, buffer)
            if (bufferResult != null) {
                // Save buffer RTs for silent early-out on next cycle
                val bufferTexts = buffer.map { it.text.lowercase() }.toSet()
                bufferResultRts[pi] = bufferTexts
                currentResultRts[pi] = bufferTexts

                lastResult[pi] = bufferResult
                if (bufferTrack != null) lastTrackInfo[pi] = bufferTrack
                clearBuffer(pi)
                return bufferResult
            }
        }

        return lastResult[pi]
    }

    /**
     * Search for track in cache
     */
    private fun searchInCache(searchRt: String): TrackInfo? {
        val result = if (searchRt.contains(" - ")) {
            val parts = searchRt.split(" - ", limit = 2)
            deezerCache?.searchLocalByParts(parts[0].trim(), parts[1].trim())
                ?: deezerCache?.searchLocal(searchRt)
        } else {
            deezerCache?.searchLocal(searchRt)
        }
        return result
    }

    /**
     * Search for track using the given RT
     */
    private suspend fun searchTrack(pi: Int, searchRt: String): Pair<String?, TrackInfo?> {
        // Already contains " - "? Likely complete "Artist - Title"
        if (searchRt.contains(" - ")) {
            val parts = searchRt.split(" - ", limit = 2)
            if (parts.size == 2) {
                return validateWithDeezer(parts[0].trim(), parts[1].trim(), searchRt)
            }
        }

        // Try simple search
        return validateWithDeezer(searchRt, "", searchRt)
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

    /**
     * Validate that a track result actually matches the search terms.
     * Returns true if the track's artist or title contains at least one word from each search term.
     */
    private fun isTrackRelevant(track: TrackInfo, searchTerms: List<String>): Boolean {
        val trackText = "${track.artist} ${track.title}".lowercase()

        // Each search term should have at least one word (>= 3 chars) present in the track
        for (term in searchTerms) {
            val words = term.lowercase().split(" ").filter { it.length >= 3 }
            val hasMatch = words.any { word -> trackText.contains(word) }
            if (!hasMatch) {
                Log.d(TAG, "Track '$trackText' doesn't match term '$term'")
                return false
            }
        }
        return true
    }

    private suspend fun tryBufferCombinations(pi: Int, buffer: List<RtEntry>): Pair<String?, TrackInfo?> {
        if (buffer.size < 2) return Pair(null, null)

        val texts = buffer.map { it.text }
        Log.d(TAG, "Trying combinations from buffer: $texts")

        // Collect all valid results and pick the best one
        val validResults = mutableListOf<Pair<String, TrackInfo>>()

        // Try different combinations
        for (i in texts.indices) {
            for (j in texts.indices) {
                if (i == j) continue

                val artist = texts[i]
                val title = texts[j]
                val query = "$artist $title"

                onDebugUpdate?.invoke("Searching...", currentRt, texts.joinToString(" | "), query, null)

                val (result, track) = validateWithDeezer(artist, title, query)
                if (result != null && track != null) {
                    // Validate that the result actually matches the search terms
                    if (isTrackRelevant(track, texts)) {
                        Log.d(TAG, "Valid match found: $result")
                        validResults.add(Pair(result, track))
                    } else {
                        Log.d(TAG, "Ignoring irrelevant result: $result for search: $texts")
                    }
                }
            }
        }

        // Return best match (highest popularity)
        if (validResults.isNotEmpty()) {
            val best = validResults.maxByOrNull { it.second.popularity }!!
            return best
        }

        // Try simple concatenation search
        val combined = texts.joinToString(" ")
        onDebugUpdate?.invoke("Searching combined...", currentRt, texts.joinToString(" | "), combined, null)

        // Try local cache first
        val cachedTrack = deezerCache?.searchLocal(combined)
        if (cachedTrack != null) {
            val result = "${cachedTrack.artist} - ${cachedTrack.title}"
            Log.d(TAG, "Found in local cache (combined): $result")
            onDebugUpdate?.invoke("Cached (local)", currentRt, combined, "local cache", cachedTrack)
            return Pair(result, cachedTrack)
        }

        // Try Deezer API
        val track = deezerClient?.searchTrack(combined)
        if (track != null) {
            // Cache the result and get updated track with local cover
            val cachedTrack = cacheTrackAndGetUpdated(track)
            val result = "${cachedTrack.artist} - ${cachedTrack.title}"
            Log.d(TAG, "Found by combined search: $result")
            onDebugUpdate?.invoke("Found!", currentRt, combined, combined, cachedTrack)
            return Pair(result, cachedTrack)
        }

        return Pair(null, null)
    }

    private suspend fun validateWithDeezer(possibleArtist: String, possibleTitle: String, rawQuery: String): Pair<String?, TrackInfo?> {
        Log.d(TAG, "validateWithDeezer: artist='$possibleArtist', title='$possibleTitle', query='$rawQuery'")
        val normalizedRt = currentRt?.let { RtCorrection.normalizeRt(it) }

        // Get list of skipped trackIds for this RT
        val skippedTrackIds = if (normalizedRt != null) {
            correctionDao?.getSkippedTrackIds(normalizedRt) ?: emptyList()
        } else {
            emptyList()
        }

        // 1. First try local cache (but check if skipped)
        val cachedTrack = if (possibleTitle.isNotEmpty()) {
            deezerCache?.searchLocalByParts(possibleArtist, possibleTitle)
                ?: deezerCache?.searchLocal(rawQuery)
        } else {
            deezerCache?.searchLocal(rawQuery)
        }
        if (cachedTrack != null && cachedTrack.trackId !in skippedTrackIds) {
            val result = "${cachedTrack.artist} - ${cachedTrack.title}"
            Log.d(TAG, "Found in local cache: $result")
            onDebugUpdate?.invoke("Cached (local)", currentRt, rawQuery, "local cache", cachedTrack)
            currentTrackInfo = cachedTrack
            return Pair(result, cachedTrack)
        }

        // 2. Check if network is available - if not, return null (no API call)
        if (isNetworkAvailable?.invoke() == false) {
            Log.d(TAG, "No network available, skipping Deezer API")
            onDebugUpdate?.invoke("Offline", currentRt, rawQuery, "no network", null)
            return Pair(null, null)
        }

        // 3. Try Deezer API (with skip if needed)
        if (possibleTitle.isNotEmpty()) {
            val track = if (skippedTrackIds.isNotEmpty()) {
                deezerClient?.searchTrackWithSkip("artist:\"$possibleArtist\" track:\"$possibleTitle\"", skippedTrackIds)
            } else {
                deezerClient?.searchTrackByParts(possibleArtist, possibleTitle)
            }
            if (track != null && track.trackId !in skippedTrackIds) {
                // Cache the result and get updated track with local cover
                val cachedTrack = cacheTrackAndGetUpdated(track)
                val result = "${cachedTrack.artist} - ${cachedTrack.title}"
                Log.d(TAG, "Validated: $result")
                onDebugUpdate?.invoke("Found!", currentRt, rawQuery, "artist:\"$possibleArtist\" track:\"$possibleTitle\"", cachedTrack)
                currentTrackInfo = cachedTrack
                return Pair(result, cachedTrack)
            }
        }

        // Try simple search (with skip if needed)
        val simpleTrack = if (skippedTrackIds.isNotEmpty()) {
            deezerClient?.searchTrackWithSkip(rawQuery, skippedTrackIds)
        } else {
            deezerClient?.searchTrack(rawQuery)
        }
        if (simpleTrack != null && simpleTrack.trackId !in skippedTrackIds) {
            // Cache the result and get updated track with local cover
            val cachedTrack = cacheTrackAndGetUpdated(simpleTrack)
            val result = "${cachedTrack.artist} - ${cachedTrack.title}"
            Log.d(TAG, "Found by simple search: $result")
            onDebugUpdate?.invoke("Found!", currentRt, rawQuery, rawQuery, cachedTrack)
            currentTrackInfo = cachedTrack
            return Pair(result, cachedTrack)
        }

        // Clear lastTrackInfo for this PI so getLastTrackInfo returns null
        lastTrackInfo.remove(currentPi)
        onDebugUpdate?.invoke("Not found", currentRt, rawQuery, rawQuery, null)
        return Pair(null, null)
    }

    /**
     * Cache track and return updated track with local cover path.
     * Checks if cover is already cached (fast), starts background download if not.
     */
    private fun cacheTrackAndGetUpdated(track: TrackInfo): TrackInfo {
        // Only cache if enabled
        if (isCacheEnabled?.invoke() == false) {
            return track
        }

        val cache = deezerCache ?: return track

        // First check if cover is ALREADY cached (fast, no network)
        val existingLocalPath = cache.getLocalCoverPath(track.trackId)
        if (existingLocalPath != null) {
            // Cover already cached - return immediately with local path
            return track.copy(coverUrl = existingLocalPath)
        }

        // Cover not yet cached - start background download (non-blocking)
        scope.launch {
            cache.cacheTrack(track)
            // After download, notify caller so MediaSession can be updated
            val localPath = cache.getLocalCoverPath(track.trackId)
            if (localPath != null) {
                val updatedTrack = track.copy(coverUrl = localPath)
                onCoverDownloaded?.invoke(updatedTrack)
                Log.d(TAG, "Cover downloaded, notifying: ${track.artist} - ${track.title}")
            }
        }

        // Return track without local cover for now (UI will show placeholder)
        // Next time this track is found, the cover will be cached
        return track
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
        lastSearchRt.clear()
        lastOriginalRt.clear()
        bufferResultRts.clear()
        currentResultRts.clear()
        currentRt = null
        currentTrackInfo = null
    }

    /**
     * Force re-process of current RT (e.g., after adding a skip correction)
     * Clears the cache for the current RT so next update will search again
     */
    fun forceReprocess() {
        val rt = currentRt ?: return
        // Clear cached result for this RT so next RDS update triggers new search
        lastProcessedRt.entries.removeIf { it.value == rt }
        lastSearchRt.entries.removeIf { true } // Clear all search RT
        lastTrackInfo.entries.removeIf { true } // Clear all track info
        lastResult.entries.removeIf { true } // Clear all results
        currentResultRts.clear() // Clear silent early-out cache
        currentTrackInfo = null
        Log.d(TAG, "Force re-process triggered for RT: $rt")
    }

    /**
     * Clear buffer for specific station
     */
    fun clearStation(pi: Int) {
        rtBuffer.remove(pi)
        lastResult.remove(pi)
        lastTrackInfo.remove(pi)
        lastProcessedRt.remove(pi)
        lastSearchRt.remove(pi)
        lastOriginalRt.remove(pi)
        bufferResultRts.remove(pi)
        currentResultRts.remove(pi)
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
        lastSearchRt.clear()
        lastOriginalRt.clear()
        bufferResultRts.clear()
        currentResultRts.clear()
    }
}
