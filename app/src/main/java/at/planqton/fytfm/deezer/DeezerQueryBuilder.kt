package at.planqton.fytfm.deezer

/**
 * Pure query-string helpers backing [DeezerClient]'s 7-strategy fallback
 * search. Each strategy boils down to one small string transformation; if
 * any of these regexes drift the search silently degrades, so they're
 * extracted into a stateless object and unit-tested on representative
 * input from real RDS strings.
 */
internal object DeezerQueryBuilder {

    /**
     * Strips parenthesised + bracketed groups and any "feat./ft./&" tail
     * from a free-text query. Used as the second attempt when the literal
     * RDS string returns no hit.
     *
     * Example: `"Song (Remix) feat. X"` → `"Song"`.
     */
    fun cleanFreeQuery(query: String): String {
        return query
            .replace(Regex("\\(.*?\\)|\\[.*?\\]"), "")
            .replace(Regex("feat\\..*|ft\\..*|&.*", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    /**
     * Builds Deezer's field-filtered search syntax: `artist:"X" track:"Y"`.
     * Drops fields whose value is null/blank. Returns null if both inputs
     * are empty (caller should skip the network round-trip).
     */
    fun buildFieldQuery(artist: String?, title: String?): String? {
        val q = buildString {
            if (!artist.isNullOrBlank()) append("artist:\"$artist\" ")
            if (!title.isNullOrBlank()) append("track:\"$title\"")
        }.trim()
        return q.takeIf { it.isNotBlank() }
    }

    /**
     * Trims connectors-and-following-text from an artist string for the
     * "cleaned" strategy. Matches whitespace-bracketed `x`, `&`, `feat.`,
     * `ft.`, `vs.` and drops them plus everything after.
     *
     * Example: `"Artist X x Featured & Other feat. Guest"` → `"Artist X"`.
     * (First connector encountered wins — "X" inside the artist name is
     * preserved because it's not surrounded by whitespace + token.)
     */
    fun cleanArtistConnectors(artist: String): String {
        return artist
            .replace(
                Regex(
                    "\\s+x\\s+.*|\\s+&\\s+.*|\\s+feat\\..*|\\s+ft\\..*|\\s+vs\\..*",
                    RegexOption.IGNORE_CASE,
                ),
                "",
            )
            .trim()
    }

    /**
     * Strips parenthesised + bracketed groups from a track title (no
     * "feat." stripping — Deezer indexes that into the title sometimes).
     */
    fun cleanTitleParens(title: String): String {
        return title
            .replace(Regex("\\(.*?\\)|\\[.*?\\]"), "")
            .trim()
    }

    /**
     * Splits a multi-artist string on the same connectors as
     * [cleanArtistConnectors]. Used to recover the *second* artist for
     * the "second-artist" fallback strategy when the first artist's name
     * is too generic to find the track.
     */
    fun splitArtists(artist: String): List<String> {
        return artist
            .split(Regex("\\s+x\\s+|\\s+&\\s+|\\s+feat\\.\\s*|\\s+ft\\.\\s*", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }
}
