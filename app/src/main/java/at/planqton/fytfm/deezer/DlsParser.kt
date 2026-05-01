package at.planqton.fytfm.deezer

import android.util.Log

/**
 * Intelligent DAB DLS (Dynamic Label Segment) Parser
 *
 * Extracts "Artist - Title" from complex DLS strings like:
 * - "Sie hören MOZART - Serenade - radio klassik Stephansdom * .."
 * - "HOLLY HUMBERSTONE - TO LOVE SOMEBODY auf Antenne Österreich Das DAB+ für..."
 * - "ONAIR: WOLFMOTHER - Woman - Radio 88.6 - So rockt das Leben."
 * - "JETZT: Artist - Title | Ö3 - Hits für euch"
 */
object DlsParser {
    private const val TAG = "DlsParser"

    // Dynamic prefix patterns (with or without colon)
    private val PREFIX_PATTERNS = listOf(
        Regex("""^(ONAIR|ON AIR|NOW PLAYING|NOW|JETZT|AKTUELL|PLAYING|CURRENT)\s*:?\s*""", RegexOption.IGNORE_CASE),
        Regex("""^(Sie hören|Du hörst|You're listening to|Listening to|Gerade läuft|Jetzt läuft|Now playing)\s*:?\s*""", RegexOption.IGNORE_CASE),
        Regex("""^(Musik|Music|Song|Track)\s*:\s*""", RegexOption.IGNORE_CASE),
        Regex("""^[♪♫▶►●★☆→»]\s*""")
    )

    // URL patterns to remove
    private val URL_PATTERNS = listOf(
        Regex("""https?://\S+""", RegexOption.IGNORE_CASE),
        Regex("""www\.\S+""", RegexOption.IGNORE_CASE),
        Regex("""\S+\.(at|de|com|net|org|fm|radio)(/\S*)?(?=\s|$)""", RegexOption.IGNORE_CASE)
    )

    // Time patterns (HH:MM or H:MM)
    private val TIME_PATTERN = Regex("""\b\d{1,2}:\d{2}\b""")

    // Station name indicators - words that indicate a station name follows or precedes
    private val STATION_PREPOSITIONS = listOf("bei", "auf", "on", "@", "von", "from", "via")

    // Promotional/slogan phrases that often follow station names
    private val PROMO_PHRASES = listOf(
        "das dab+", "dab+ für", "mit den besten", "mit bester", "mit österreichs",
        "so rockt", "hits für", "best of", "non stop", "nonstop", "non-stop",
        "mehr musik", "more music", "best music", "beste musik", "nur hits",
        "only hits", "the best", "das beste", "dein radio", "your radio",
        "wir spielen", "we play", "24 stunden", "24/7", "rund um die uhr",
        "für österreich", "für deutschland", "for you", "für dich", "für euch"
    )

    // Words that indicate station names
    private val STATION_KEYWORDS = listOf(
        "radio", "fm", "antenne", "welle", "hitradio", "energy", "nrj",
        "orf", "ö3", "ö1", "fm4", "kronehit", "life", "station", "sender",
        "klassik", "rock", "pop", "news", "info", "kultur", "one"
    )

    // Separators used in DLS
    private val SEPARATORS = listOf(" - ", " – ", " — ", " | ", " / ", " >>> ", " << ", " >> ", " * ")

    // Whole-word matchers for STATION_KEYWORDS. Using substring-contains here
    // caused false positives like "HOLLY HUMBERSTONE" → "one" hit, or
    // "WOLFMOTHER" → "fm" hit, so a real artist name was filtered as a station.
    // Lookarounds include digits so "fm4" / "88.6"-style matches still bind.
    private val STATION_KEYWORD_PATTERNS: List<Regex> = STATION_KEYWORDS.map { keyword ->
        Regex(
            """(?<![\p{L}\p{N}])${Regex.escape(keyword)}(?![\p{L}\p{N}])""",
            RegexOption.IGNORE_CASE,
        )
    }

    /**
     * Parse DLS string and extract artist/title
     */
    fun parse(dls: String, stationName: String? = null): ParseResult {
        var text = dls.trim()
        val original = text

        if (text.isBlank()) {
            return ParseResult(original, null, null, false)
        }

        Log.d(TAG, "=== Parsing DLS ===")
        Log.d(TAG, "Input: '$text'")
        Log.d(TAG, "Station: '$stationName'")

        // Step 1: Remove prefixes
        text = removePrefix(text)

        // Step 2: Remove URLs
        for (pattern in URL_PATTERNS) {
            text = pattern.replace(text, " ").trim()
        }

        // Step 3: Remove timestamps
        text = TIME_PATTERN.replace(text, " ").trim()

        // Step 4: Remove trailing dots, asterisks, etc.
        text = text.replace(Regex("""[\s.*…]+$"""), "").trim()
        text = text.replace(Regex("""^[\s.*…]+"""), "").trim()

        // Step 5: Remove station suffix and promotional text
        text = removeStationAndPromo(text, stationName)

        // Step 6: Clean up
        text = text.replace(Regex("""\s{2,}"""), " ")
        text = text.replace(Regex("""(\s*[-–—|/]\s*){2,}"""), " - ")
        text = text.trim(' ', '-', '–', '—', '|', '/', '*', '.')

        Log.d(TAG, "After cleanup: '$text'")

        // Step 7: Extract artist and title
        return extractArtistTitle(text, original, stationName)
    }

    /**
     * Remove known prefixes
     */
    private fun removePrefix(text: String): String {
        var result = text
        for (pattern in PREFIX_PATTERNS) {
            val match = pattern.find(result)
            if (match != null) {
                result = result.substring(match.range.last + 1).trim()
                Log.d(TAG, "Removed prefix '${match.value.trim()}': '$result'")
                break
            }
        }
        return result
    }

    /**
     * Intelligently remove station names and promotional text
     */
    private fun removeStationAndPromo(text: String, stationName: String?): String {
        var result = text

        // Clean station name
        val cleanStation = stationName?.replace("*", "")?.replace("'", "")?.replace("\"", "")?.trim()
        val lowerStation = cleanStation?.lowercase()

        // Strategy 1: Find "preposition + station" pattern anywhere
        for (prep in STATION_PREPOSITIONS) {
            val prepPattern = Regex("""\s+$prep\s+""", RegexOption.IGNORE_CASE)
            val prepMatch = prepPattern.find(result)
            if (prepMatch != null) {
                val afterPrep = result.substring(prepMatch.range.last + 1)

                // Check if what follows looks like a station
                if (looksLikeStation(afterPrep, lowerStation)) {
                    result = result.substring(0, prepMatch.range.first).trim()
                    Log.d(TAG, "Removed '$prep + station': kept '$result'")
                    break
                }
            }
        }

        // Strategy 2: Find station name directly in the text (use current result, not original)
        if (lowerStation != null && lowerStation.length >= 3) {
            val currentLower = result.lowercase()
            val stationIndex = currentLower.indexOf(lowerStation)
            if (stationIndex > 0 && stationIndex < result.length) {
                // Station name found - check if it's preceded by a separator
                val beforeStation = result.substring(0, stationIndex)
                val lastSepIndex = findLastSeparatorIndex(beforeStation)
                if (lastSepIndex > 0) {
                    result = beforeStation.substring(0, lastSepIndex).trim()
                    Log.d(TAG, "Found station '$cleanStation' at index $stationIndex, cut to: '$result'")
                }
            }
        }

        // Strategy 3: Look for promotional phrases
        for (promo in PROMO_PHRASES) {
            val currentLower = result.lowercase()
            val promoIndex = currentLower.indexOf(promo)
            if (promoIndex > 0 && promoIndex < result.length) {
                // Find the separator before the promo
                val beforePromo = result.substring(0, promoIndex)
                val lastSepIndex = findLastSeparatorIndex(beforePromo)
                if (lastSepIndex > 0) {
                    result = beforePromo.substring(0, lastSepIndex).trim()
                    Log.d(TAG, "Found promo phrase '$promo', cut to: '$result'")
                    break
                }
            }
        }

        // Strategy 4: Pattern "- STATION" at the end (with station keywords)
        val endPattern = Regex("""\s*[-–—|]\s*([^-–—|]{2,40})\s*$""")
        val endMatch = endPattern.find(result)
        if (endMatch != null) {
            val potentialStation = endMatch.groupValues[1].trim()
            if (looksLikeStation(potentialStation, lowerStation)) {
                result = result.substring(0, endMatch.range.first).trim()
                Log.d(TAG, "Removed trailing station '$potentialStation': '$result'")
            }
        }

        return result
    }

    /**
     * Check if text looks like a station name
     */
    private fun looksLikeStation(text: String, knownStation: String?): Boolean {
        val lowerText = text.lowercase().trim()

        // Check against known station name
        if (knownStation != null && knownStation.length >= 3) {
            if (lowerText.contains(knownStation) || knownStation.contains(lowerText.take(knownStation.length))) {
                return true
            }
        }

        // Check for station keywords (whole-word; see STATION_KEYWORD_PATTERNS).
        for (pattern in STATION_KEYWORD_PATTERNS) {
            if (pattern.containsMatchIn(lowerText)) {
                return true
            }
        }

        // Check for promotional phrases (indicates station context)
        for (promo in PROMO_PHRASES) {
            if (lowerText.contains(promo)) {
                return true
            }
        }

        // Contains frequency pattern (like "88.6", "104.9")
        if (Regex("""\d{2,3}[.,]\d""").containsMatchIn(text)) {
            return true
        }

        // All caps short text (like "ORF", "NDR", "SWR", "NRJ")
        if (text.length <= 5 && text.uppercase() == text && text.all { it.isLetter() }) {
            return true
        }

        return false
    }

    /**
     * Find the last separator position in text
     */
    private fun findLastSeparatorIndex(text: String): Int {
        var lastIndex = -1
        for (sep in SEPARATORS) {
            val idx = text.lastIndexOf(sep)
            if (idx > lastIndex) {
                lastIndex = idx
            }
        }
        return lastIndex
    }

    /**
     * Extract artist and title from cleaned text
     */
    private fun extractArtistTitle(text: String, original: String, stationName: String?): ParseResult {
        // Try the classical "Lastname, Firstname (Year[-Year]) - Title" form
        // FIRST — the generic separator split always succeeded and made the
        // pattern below unreachable before.
        CLASSICAL_PATTERN.find(text)?.let { match ->
            val lastName = match.groupValues[1].trim()
            val firstName = match.groupValues[2].trim()
            val title = match.groupValues[4].trim()
            val artist = "$firstName $lastName"
            Log.d(TAG, "CLASSICAL: Artist='$artist', Title='$title'")
            return ParseResult(original, artist, title, true)
        }

        // Try to split by separators
        for (sep in SEPARATORS) {
            if (text.contains(sep)) {
                val parts = text.split(sep).map { it.trim() }.filter { it.isNotBlank() }

                if (parts.size >= 2) {
                    // Filter out any remaining station/slogan parts
                    val cleanParts = parts.filter { !isStationOrSlogan(it, stationName) }

                    if (cleanParts.size >= 2) {
                        val artist = cleanParts[0]
                        val title = cleanParts[1]
                        Log.d(TAG, "SUCCESS: Artist='$artist', Title='$title'")
                        return ParseResult(original, artist, title, true)
                    } else if (cleanParts.size == 1 && parts.size >= 2) {
                        // Use first two parts
                        val artist = parts[0]
                        val title = parts[1]
                        if (!isStationOrSlogan(artist, stationName)) {
                            Log.d(TAG, "FALLBACK: Artist='$artist', Title='$title'")
                            return ParseResult(original, artist, title, true)
                        }
                    }
                }
                break
            }
        }

        Log.d(TAG, "FAILED: Could not extract artist/title from '$text'")
        return ParseResult(original, null, null, false)
    }

    private val CLASSICAL_PATTERN =
        Regex("""^([^,]+),\s*([^(]+)\s*\((\d{4}(?:-\d{4})?)\)\s*[-–—]\s*(.+)$""")

    /**
     * Check if text looks like a station name or slogan
     */
    private fun isStationOrSlogan(text: String, stationName: String?): Boolean {
        val lowerText = text.lowercase()

        // Match against known station
        if (stationName != null) {
            val cleanStation = stationName.replace("*", "").replace("'", "").replace("\"", "").trim().lowercase()
            if (cleanStation.isNotBlank() && (lowerText.contains(cleanStation) || cleanStation.contains(lowerText))) {
                return true
            }
        }

        // Station/slogan keywords (whole-word; see STATION_KEYWORD_PATTERNS).
        var keywordCount = 0
        for (pattern in STATION_KEYWORD_PATTERNS) {
            if (pattern.containsMatchIn(lowerText)) keywordCount++
        }
        if (keywordCount >= 1 && text.length < 25) return true

        // Promotional phrases
        for (promo in PROMO_PHRASES) {
            if (lowerText.contains(promo)) return true
        }

        // Short all-caps (likely abbreviation)
        if (text.length <= 4 && text.uppercase() == text) return true

        return false
    }

    /**
     * Result of DLS parsing
     */
    data class ParseResult(
        val original: String,
        val artist: String?,
        val title: String?,
        val success: Boolean
    ) {
        fun toSearchString(): String? {
            return if (success && artist != null && title != null) {
                "$artist - $title"
            } else {
                null
            }
        }

        override fun toString(): String {
            return if (success) {
                "ParseResult(artist='$artist', title='$title')"
            } else {
                "ParseResult(failed, original='$original')"
            }
        }
    }
}
