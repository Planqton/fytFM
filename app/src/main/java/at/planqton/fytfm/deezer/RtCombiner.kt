package at.planqton.fytfm.deezer

import android.util.Log
import at.planqton.fytfm.data.rdslog.EditString
import at.planqton.fytfm.data.rdslog.EditStringDao
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
 * - Apply EditStrings to transform RT before search (e.g., remove "Jetzt On Air:", replace " mit " with " - ")
 * - Two-phase search: immediate rules first, then fallback rules if nothing found
 */
class RtCombiner(
    private val deezerClient: DeezerClient?,
    private val deezerCache: DeezerCache? = null,
    private val isCacheEnabled: (() -> Boolean)? = null,
    private val isNetworkAvailable: (() -> Boolean)? = null,
    private val correctionDao: RtCorrectionDao? = null,
    private val editStringDao: EditStringDao? = null,
    private val onDebugUpdate: ((status: String, originalRt: String?, strippedRt: String?, query: String?, trackInfo: TrackInfo?) -> Unit)? = null
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
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    data class RtEntry(
        val text: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    // Current RT for correction buttons
    private var currentRt: String? = null
    private var currentTrackInfo: TrackInfo? = null

    /**
     * Get current RT (for correction buttons)
     */
    fun getCurrentRt(): String? = currentRt

    /**
     * Get current TrackInfo (for correction buttons)
     */
    fun getCurrentTrackInfo(): TrackInfo? = currentTrackInfo

    /**
     * Apply EditStrings to RT
     * @param rt The RT to transform
     * @param originalRt The original RT (for condition checking)
     * @param frequency Current frequency in MHz (for frequency-specific rules)
     * @param editStrings List of edit rules to apply
     * @return Transformed RT
     */
    private fun applyEditStrings(rt: String, originalRt: String, frequency: Float?, editStrings: List<EditString>): String {
        if (editStrings.isEmpty()) return rt

        var result = rt

        for (edit in editStrings) {
            // Check frequency condition
            if (edit.forFrequency != null && frequency != null) {
                // Allow small tolerance for float comparison (0.05 MHz)
                if (kotlin.math.abs(edit.forFrequency - frequency) > 0.05f) {
                    continue // Skip this rule - frequency doesn't match
                }
            }

            // Check conditionContains (respects caseSensitiveCondition flag)
            if (!edit.conditionContains.isNullOrEmpty()) {
                val conditionMatches = if (edit.caseSensitiveCondition) {
                    originalRt.contains(edit.conditionContains)
                } else {
                    originalRt.lowercase().contains(edit.conditionContains.lowercase())
                }
                if (!conditionMatches) {
                    continue // Skip this rule - condition not met
                }
            }

            // Find text comparison (respects caseSensitiveFind flag)
            val findText = if (edit.caseSensitiveFind) edit.textOriginal else edit.textNormalized
            val compareResult = if (edit.caseSensitiveFind) result else result.lowercase()

            val matched = when (edit.position) {
                EditString.POSITION_PREFIX -> compareResult.startsWith(findText)
                EditString.POSITION_SUFFIX -> compareResult.endsWith(findText)
                EditString.POSITION_EITHER -> compareResult.startsWith(findText) || compareResult.endsWith(findText)
                EditString.POSITION_ANYWHERE -> compareResult.contains(findText)
                else -> false
            }

            if (matched) {
                val oldResult = result
                when (edit.position) {
                    EditString.POSITION_PREFIX -> {
                        result = edit.replaceWith + result.substring(findText.length)
                        result = result.trim()
                    }
                    EditString.POSITION_SUFFIX -> {
                        val idx = compareResult.lastIndexOf(findText)
                        if (idx >= 0) {
                            result = result.substring(0, idx) + edit.replaceWith
                            result = result.trim()
                        }
                    }
                    EditString.POSITION_EITHER -> {
                        if (compareResult.startsWith(findText)) {
                            result = edit.replaceWith + result.substring(findText.length)
                            result = result.trim()
                        } else if (compareResult.endsWith(findText)) {
                            val idx = compareResult.lastIndexOf(findText)
                            if (idx >= 0) {
                                result = result.substring(0, idx) + edit.replaceWith
                                result = result.trim()
                            }
                        }
                    }
                    EditString.POSITION_ANYWHERE -> {
                        val idx = compareResult.indexOf(findText)
                        if (idx >= 0) {
                            result = result.substring(0, idx) + edit.replaceWith + result.substring(idx + findText.length)
                            result = result.trim()
                        }
                    }
                }
                Log.d(TAG, "Applied edit '${edit.textOriginal}' -> '${edit.replaceWith}' (${edit.position}): '$oldResult' -> '$result'")
            }
        }

        return result
    }

    /**
     * Process incoming RT
     * @param pi PI-Code of the station
     * @param rt Radio Text
     * @param frequency Current frequency in MHz (for frequency-specific edit rules)
     * @return Formatted "Artist - Title" or null if not yet determined
     */
    suspend fun processRt(pi: Int, rt: String, frequency: Float? = null): String? {
        val trimmedRt = rt.trim()
        if (trimmedRt.isBlank()) return null

        // Check if RT is ignored
        val normalizedRt = RtCorrection.normalizeRt(trimmedRt)
        if (correctionDao?.isRtIgnored(normalizedRt) == true) {
            Log.d(TAG, "RT is ignored: $trimmedRt")
            onDebugUpdate?.invoke("Ignored", trimmedRt, trimmedRt, null, null)
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
        lastOriginalRt[pi] = trimmedRt
        currentRt = trimmedRt

        // Phase 1: Apply immediate edit strings (not fallback)
        val immediateEdits = editStringDao?.getAllImmediate() ?: emptyList()
        var searchRt = applyEditStrings(trimmedRt, trimmedRt, frequency, immediateEdits)

        // Cache the stripped RT for debug display
        lastSearchRt[pi] = searchRt

        Log.d(TAG, "Processing RT for PI=$pi: $trimmedRt" + (if (searchRt != trimmedRt) " -> '$searchRt'" else ""))
        onDebugUpdate?.invoke("Processing...", trimmedRt, searchRt, null, null)

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
        val bufferFirst = shouldBufferFirst(searchRt)
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

        // Try to find track with immediate edits applied
        var (result, track) = searchTrack(pi, searchRt)

        // Phase 2: If nothing found, apply fallback edit strings and try again
        if (result == null) {
            val fallbackEdits = editStringDao?.getAllFallback() ?: emptyList()
            if (fallbackEdits.isNotEmpty()) {
                val fallbackRt = applyEditStrings(searchRt, trimmedRt, frequency, fallbackEdits)
                if (fallbackRt != searchRt) {
                    Log.d(TAG, "Trying fallback: '$searchRt' -> '$fallbackRt'")
                    onDebugUpdate?.invoke("Fallback...", trimmedRt, fallbackRt, null, null)

                    val (fallbackResult, fallbackTrack) = searchTrack(pi, fallbackRt)
                    if (fallbackResult != null) {
                        result = fallbackResult
                        track = fallbackTrack
                    }
                }
            }
        }

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
        return if (searchRt.contains(" - ")) {
            val parts = searchRt.split(" - ", limit = 2)
            deezerCache?.searchLocalByParts(parts[0].trim(), parts[1].trim())
                ?: deezerCache?.searchLocal(searchRt)
        } else {
            deezerCache?.searchLocal(searchRt)
        }
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
            val result = "${track.artist} - ${track.title}"
            Log.d(TAG, "Found by combined search: $result")
            onDebugUpdate?.invoke("Found!", currentRt, combined, combined, track)
            // Cache the result
            cacheTrack(track)
            return Pair(result, track)
        }

        return Pair(null, null)
    }

    private suspend fun validateWithDeezer(possibleArtist: String, possibleTitle: String, rawQuery: String): Pair<String?, TrackInfo?> {
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
                val result = "${track.artist} - ${track.title}"
                Log.d(TAG, "Validated: $result")
                onDebugUpdate?.invoke("Found!", currentRt, rawQuery, "artist:\"$possibleArtist\" track:\"$possibleTitle\"", track)
                // Cache the result
                cacheTrack(track)
                currentTrackInfo = track
                return Pair(result, track)
            }
        }

        // Try simple search (with skip if needed)
        val simpleTrack = if (skippedTrackIds.isNotEmpty()) {
            deezerClient?.searchTrackWithSkip(rawQuery, skippedTrackIds)
        } else {
            deezerClient?.searchTrack(rawQuery)
        }
        if (simpleTrack != null && simpleTrack.trackId !in skippedTrackIds) {
            val result = "${simpleTrack.artist} - ${simpleTrack.title}"
            Log.d(TAG, "Found by simple search: $result")
            onDebugUpdate?.invoke("Found!", currentRt, rawQuery, rawQuery, simpleTrack)
            // Cache the result
            cacheTrack(simpleTrack)
            currentTrackInfo = simpleTrack
            return Pair(result, simpleTrack)
        }

        onDebugUpdate?.invoke("Not found", currentRt, rawQuery, rawQuery, null)
        return Pair(null, null)
    }

    private fun cacheTrack(track: TrackInfo) {
        // Only cache if enabled
        if (isCacheEnabled?.invoke() != false) {
            deezerCache?.let { cache ->
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
        lastSearchRt.clear()
        lastOriginalRt.clear()
        bufferResultRts.clear()
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
    }
}
