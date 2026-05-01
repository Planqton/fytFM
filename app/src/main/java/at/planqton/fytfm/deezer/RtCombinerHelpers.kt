package at.planqton.fytfm.deezer

/**
 * Pure helpers backing [RtCombiner]'s heuristics. Extracted so the
 * separator/length thresholds and the track-relevance word match can be
 * unit-tested without spinning up the full coroutine pipeline + Deezer
 * client.
 *
 * All functions are stateless. The constants the caller passes in
 * (`separators`, `threshold`, `minWordLen`) come straight from
 * [RtCombiner]'s companion-object, so the production behaviour is
 * exercised by passing the same values.
 */
internal object RtCombinerHelpers {

    /**
     * Should this RT message be buffered (held back) before sending to
     * Deezer? Yes when **both**:
     *  1. it does NOT contain a typical artist/title separator
     *  2. it is shorter than [threshold] characters
     *
     * The use case: stations that emit `Artist` and `Title` on separate
     * RT updates (Kronehit) get buffered until a sibling arrives, then
     * the [RtCombiner] tries cross-pair combinations.
     */
    fun shouldBufferFirst(rt: String, separators: List<String>, threshold: Int): Boolean {
        if (separators.any { rt.contains(it) }) return false
        return rt.length < threshold
    }

    /**
     * Does the [track] plausibly match the buffered [searchTerms]? For
     * each term, at least one word with length ≥ [minWordLen] must
     * appear inside the track's `artist + title` text (case-insensitive).
     *
     * The minimum-word-length filter weeds out stop-word noise ("a",
     * "the", "in") so two unrelated tracks aren't false-matched.
     * **Note:** if a term has no qualifying words at all (everything
     * filtered out), the track is rejected — not skipped. That matches
     * the original RtCombiner behaviour and prevents an "all stop-words"
     * RT from passing as a match.
     */
    fun isTrackRelevant(track: TrackInfo, searchTerms: List<String>, minWordLen: Int = 3): Boolean {
        val trackText = "${track.artist} ${track.title}".lowercase()
        for (term in searchTerms) {
            val words = term.lowercase().split(" ").filter { it.length >= minWordLen }
            val hasMatch = words.any { word -> trackText.contains(word) }
            if (!hasMatch) return false
        }
        return true
    }
}
