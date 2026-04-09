package at.planqton.fytfm.deezer

import android.util.Log

/**
 * Normalizes classical music metadata for better Deezer search results.
 *
 * Handles patterns like:
 * - "TSCHAIKOWSKY, PETER ILJITSCH (1840-1893)" → "Tchaikovsky"
 * - "Klavierkonzert Nr. 2 G-Dur, op. 44" → "Piano Concerto No. 2"
 * - "BACH, JOHANN SEBASTIAN" → "Johann Sebastian Bach"
 */
object ClassicalMusicNormalizer {
    private const val TAG = "ClassicalNormalizer"

    // Known composer name variations (German/local → International)
    private val composerVariations = mapOf(
        "tschaikowsky" to "Tchaikovsky",
        "tschaikovsky" to "Tchaikovsky",
        "tschaikowski" to "Tchaikovsky",
        "cajkovskij" to "Tchaikovsky",
        "beethoven" to "Beethoven",
        "mozart" to "Mozart",
        "bach" to "Bach",
        "haydn" to "Haydn",
        "händel" to "Handel",
        "handel" to "Handel",
        "haendel" to "Handel",
        "brahms" to "Brahms",
        "schubert" to "Schubert",
        "schumann" to "Schumann",
        "chopin" to "Chopin",
        "liszt" to "Liszt",
        "wagner" to "Wagner",
        "verdi" to "Verdi",
        "puccini" to "Puccini",
        "dvořák" to "Dvorak",
        "dvorak" to "Dvorak",
        "smetana" to "Smetana",
        "grieg" to "Grieg",
        "sibelius" to "Sibelius",
        "rachmaninow" to "Rachmaninoff",
        "rachmaninoff" to "Rachmaninoff",
        "rachmaninov" to "Rachmaninoff",
        "strawinsky" to "Stravinsky",
        "stravinsky" to "Stravinsky",
        "prokofjew" to "Prokofiev",
        "prokofiev" to "Prokofiev",
        "schostakowitsch" to "Shostakovich",
        "shostakovich" to "Shostakovich",
        "mahler" to "Mahler",
        "bruckner" to "Bruckner",
        "strauss" to "Strauss",
        "strauß" to "Strauss",
        "vivaldi" to "Vivaldi",
        "debussy" to "Debussy",
        "ravel" to "Ravel",
        "berlioz" to "Berlioz",
        "mendelssohn" to "Mendelssohn",
        "schönberg" to "Schoenberg",
        "schoenberg" to "Schoenberg",
        "webern" to "Webern",
        "berg" to "Berg",
        "bartók" to "Bartok",
        "bartok" to "Bartok",
        "kodály" to "Kodaly",
        "kodaly" to "Kodaly",
        "janáček" to "Janacek",
        "janacek" to "Janacek",
        "biber" to "Biber",
        "telemann" to "Telemann",
        "purcell" to "Purcell",
        "monteverdi" to "Monteverdi",
        "palestrina" to "Palestrina",
        "pergolesi" to "Pergolesi",
        "corelli" to "Corelli",
        "albinoni" to "Albinoni",
        "pachelbel" to "Pachelbel"
    )

    // Compound German music terms → English (must be checked BEFORE individual words)
    private val compoundTerms = mapOf(
        "klavierkonzert" to "Piano Concerto",
        "violinkonzert" to "Violin Concerto",
        "cellokonzert" to "Cello Concerto",
        "flötenkonzert" to "Flute Concerto",
        "klarinettenkonzert" to "Clarinet Concerto",
        "hornkonzert" to "Horn Concerto",
        "trompetenkonzert" to "Trumpet Concerto",
        "orgelkonzert" to "Organ Concerto",
        "klaviersonate" to "Piano Sonata",
        "violinsonate" to "Violin Sonata",
        "cellosonate" to "Cello Sonata",
        "streichquartett" to "String Quartet",
        "klaviertrio" to "Piano Trio",
        "klavierquartett" to "Piano Quartet",
        "klavierquintett" to "Piano Quintet"
    )

    // German music terms → English
    private val musicTerms = mapOf(
        // Instruments
        "klavier" to "Piano",
        "violine" to "Violin",
        "viola" to "Viola",
        "violoncello" to "Cello",
        "cello" to "Cello",
        "kontrabass" to "Double Bass",
        "flöte" to "Flute",
        "floete" to "Flute",
        "oboe" to "Oboe",
        "klarinette" to "Clarinet",
        "fagott" to "Bassoon",
        "horn" to "Horn",
        "trompete" to "Trumpet",
        "posaune" to "Trombone",
        "harfe" to "Harp",
        "orgel" to "Organ",
        "cembalo" to "Harpsichord",

        // Forms
        "konzert" to "Concerto",
        "sinfonie" to "Symphony",
        "symphonie" to "Symphony",
        "sonate" to "Sonata",
        "ouvertüre" to "Overture",
        "ouverture" to "Overture",
        "suite" to "Suite",
        "variationen" to "Variations",
        "fantasie" to "Fantasy",
        "rhapsodie" to "Rhapsody",
        "präludium" to "Prelude",
        "praeludium" to "Prelude",
        "fuge" to "Fugue",
        "toccata" to "Toccata",
        "etüde" to "Etude",
        "etuede" to "Etude",
        "nocturne" to "Nocturne",
        "walzer" to "Waltz",
        "polka" to "Polka",
        "marsch" to "March",
        "messe" to "Mass",
        "requiem" to "Requiem",
        "oratorium" to "Oratorio",
        "oper" to "Opera",
        "arie" to "Aria",
        "lied" to "Song",
        "lieder" to "Songs",

        // Tempo/Character
        "allegro" to "Allegro",
        "adagio" to "Adagio",
        "andante" to "Andante",
        "presto" to "Presto",
        "largo" to "Largo",
        "vivace" to "Vivace",
        "moderato" to "Moderato",
        "scherzo" to "Scherzo",

        // Keys (German)
        "dur" to "Major",
        "moll" to "Minor",

        // Numbers
        "nr." to "No.",
        "nr" to "No.",
        "nummer" to "No.",
        "op." to "Op.",
        "opus" to "Op."
    )

    // Key translations
    private val keyNames = mapOf(
        "c-dur" to "C Major", "c-moll" to "C Minor",
        "cis-dur" to "C-sharp Major", "cis-moll" to "C-sharp Minor",
        "d-dur" to "D Major", "d-moll" to "D Minor",
        "dis-dur" to "D-sharp Major", "dis-moll" to "D-sharp Minor",
        "es-dur" to "E-flat Major", "es-moll" to "E-flat Minor",
        "e-dur" to "E Major", "e-moll" to "E Minor",
        "f-dur" to "F Major", "f-moll" to "F Minor",
        "fis-dur" to "F-sharp Major", "fis-moll" to "F-sharp Minor",
        "ges-dur" to "G-flat Major", "ges-moll" to "G-flat Minor",
        "g-dur" to "G Major", "g-moll" to "G Minor",
        "gis-dur" to "G-sharp Major", "gis-moll" to "G-sharp Minor",
        "as-dur" to "A-flat Major", "as-moll" to "A-flat Minor",
        "a-dur" to "A Major", "a-moll" to "A Minor",
        "ais-dur" to "A-sharp Major", "ais-moll" to "A-sharp Minor",
        "b-dur" to "B-flat Major", "b-moll" to "B-flat Minor",
        "h-dur" to "B Major", "h-moll" to "B Minor"
    )

    /**
     * Check if this looks like classical music metadata
     */
    fun isClassicalFormat(text: String): Boolean {
        val lower = text.lowercase()

        // Has year range like (1840-1893) or (1756)
        if (Regex("""\(\d{4}(-\d{4})?\)""").containsMatchIn(text)) return true

        // Has "LASTNAME, FIRSTNAME" pattern (all caps with comma)
        if (Regex("""^[A-ZÄÖÜ][A-ZÄÖÜa-zäöüß]+,\s+[A-ZÄÖÜ]""").containsMatchIn(text)) return true

        // Contains known classical music terms
        val classicalTerms = listOf(
            "konzert", "sinfonie", "symphonie", "sonate", "op.", "opus",
            "dur", "moll", "nr.", "bwv", "kv", "hob", "woo"
        )
        if (classicalTerms.any { lower.contains(it) }) return true

        // Contains known composer names
        if (composerVariations.keys.any { lower.contains(it) }) return true

        return false
    }

    /**
     * Normalize artist name for classical music
     * "TSCHAIKOWSKY, PETER ILJITSCH (1840-1893)" → "Tchaikovsky"
     */
    fun normalizeArtist(artist: String): String {
        var result = artist.trim()

        // Remove year ranges: (1840-1893), (1756), etc.
        result = result.replace(Regex("""\s*\(\d{4}(-\d{4})?\)\s*"""), " ").trim()

        // Handle "LASTNAME, FIRSTNAME MIDDLENAME" format
        if (result.contains(",")) {
            val parts = result.split(",", limit = 2)
            if (parts.size == 2) {
                val lastName = parts[0].trim()
                val firstNames = parts[1].trim()
                // Only flip if lastName looks like a single word (not "Bach, J.S.")
                if (!lastName.contains(" ") && firstNames.isNotBlank()) {
                    result = "$firstNames $lastName"
                }
            }
        }

        // Normalize capitalization: "JOHANN SEBASTIAN BACH" → "Johann Sebastian Bach"
        if (result.uppercase() == result && result.length > 3) {
            result = result.split(" ").joinToString(" ") { word ->
                word.lowercase().replaceFirstChar { it.uppercase() }
            }
        }

        // Try to find known composer variation
        val lowerResult = result.lowercase()
        for ((variant, standard) in composerVariations) {
            if (lowerResult.contains(variant)) {
                // Extract just the standard name
                Log.d(TAG, "Normalized artist: '$artist' → '$standard'")
                return standard
            }
        }

        // Return cleaned name even if not in lookup
        Log.d(TAG, "Normalized artist: '$artist' → '$result'")
        return result
    }

    /**
     * Normalize title for classical music
     * "Klavierkonzert Nr. 2 G-Dur, op. 44" → "Piano Concerto No. 2"
     */
    fun normalizeTitle(title: String): String {
        var result = title.trim()

        // Remove opus numbers for cleaner search (op. 44, Op.23, etc.)
        result = result.replace(Regex(""",?\s*op\.?\s*\d+""", RegexOption.IGNORE_CASE), "").trim()

        // Remove catalog numbers (BWV 123, KV 456, Hob. VII:3, WoO 12)
        result = result.replace(Regex(""",?\s*(bwv|kv|k\.|hob\.?|woo)\s*[\d:]+""", RegexOption.IGNORE_CASE), "").trim()

        // Translate compound terms FIRST (before splitting words)
        for ((german, english) in compoundTerms) {
            val pattern = Regex(german, RegexOption.IGNORE_CASE)
            if (pattern.containsMatchIn(result)) {
                result = pattern.replace(result, english)
            }
        }

        // Translate key names (before individual words)
        for ((german, english) in keyNames) {
            val pattern = Regex(german, RegexOption.IGNORE_CASE)
            if (pattern.containsMatchIn(result)) {
                result = pattern.replace(result, english)
            }
        }

        // Handle "Nr." → "No." properly (avoid double dots)
        result = result.replace(Regex("""Nr\.\s*""", RegexOption.IGNORE_CASE), "No. ")

        // Translate remaining music terms (individual words)
        for ((german, english) in musicTerms) {
            // Skip if already translated as compound
            if (german in listOf("klavier", "violine", "cello", "konzert", "sonate")) continue
            val pattern = Regex("""\b$german\b""", RegexOption.IGNORE_CASE)
            if (pattern.containsMatchIn(result)) {
                result = pattern.replace(result, english)
            }
        }

        // Clean up multiple spaces and punctuation
        result = result.replace(Regex("""\s{2,}"""), " ")
        result = result.replace(Regex(""",\s*,"""), ",")
        result = result.replace(Regex("""\.{2,}"""), ".") // Fix double dots
        result = result.trim(',', ' ')

        Log.d(TAG, "Normalized title: '$title' → '$result'")
        return result
    }

    /**
     * Get simplified search query for classical music
     * Returns multiple search variations to try
     */
    fun getSearchVariations(artist: String, title: String): List<Pair<String, String>> {
        val variations = mutableListOf<Pair<String, String>>()

        val normArtist = normalizeArtist(artist)
        val normTitle = normalizeTitle(title)

        // 1. Normalized version
        variations.add(normArtist to normTitle)

        // 2. Just composer + main work type (e.g., "Tchaikovsky Piano Concerto")
        val mainTerms = listOf("Concerto", "Symphony", "Sonata", "Suite", "Variations", "Overture", "Mass", "Requiem")
        for (term in mainTerms) {
            if (normTitle.contains(term, ignoreCase = true)) {
                // Extract instrument if present
                val instruments = listOf("Piano", "Violin", "Cello", "Flute", "Clarinet", "Horn", "Trumpet")
                val instrument = instruments.find { normTitle.contains(it, ignoreCase = true) }
                val simplified = if (instrument != null) "$instrument $term" else term
                variations.add(normArtist to simplified)
                break
            }
        }

        // 3. Very simplified - just composer name (for finding any track by them)
        if (normArtist.isNotBlank()) {
            variations.add(normArtist to "")
        }

        return variations.distinctBy { "${it.first}|${it.second}".lowercase() }
    }

    /**
     * Normalize a full "Artist - Title" string
     */
    fun normalize(artistTitle: String): String {
        val parts = artistTitle.split(" - ", limit = 2)
        return if (parts.size == 2) {
            val normArtist = normalizeArtist(parts[0])
            val normTitle = normalizeTitle(parts[1])
            "$normArtist - $normTitle"
        } else {
            artistTitle
        }
    }
}
