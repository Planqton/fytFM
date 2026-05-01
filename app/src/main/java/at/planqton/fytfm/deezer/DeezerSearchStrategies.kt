package at.planqton.fytfm.deezer

/**
 * Pure logic for the 7-strategy fallback search [DeezerClient.searchTrackByParts]
 * runs against api.deezer.com. Each [SearchStep] corresponds to one HTTP
 * round-trip; the client iterates the list and returns the first track that
 * the Deezer API matches against `query`.
 *
 * Extracted out of `DeezerClient` so the *what to try and in what order*
 * decision is unit-testable without spinning up an HTTP mock — the network
 * code in `DeezerClient` becomes a thin loop on top.
 */
internal object DeezerSearchStrategies {

    /**
     * One HTTP round-trip against the Deezer search endpoint.
     *
     * @property query the value passed as Deezer's `q` query-parameter
     * @property label short identifier used in log lines and bug reports —
     *  matches the `step` strings the previous inline implementation used,
     *  so log-grepping continues to work.
     */
    data class SearchStep(val query: String, val label: String)

    /**
     * Builds the ordered list of search attempts for the given RDS-derived
     * artist/title. Returns an empty list when no usable query could be built
     * (caller should skip the network entirely).
     *
     * Order matters — each step is tried only if all earlier ones missed.
     * The classical-music branch of the waterfall is appended at the end as
     * additional "give it one more try" variations.
     */
    fun buildStrategies(artist: String?, title: String?): List<SearchStep> {
        val fullText = "${artist ?: ""} ${title ?: ""}"
        val isClassical = ClassicalMusicNormalizer.isClassicalFormat(fullText)

        val searchArtist: String?
        val searchTitle: String?
        if (isClassical && !artist.isNullOrBlank()) {
            searchArtist = ClassicalMusicNormalizer.normalizeArtist(artist)
            searchTitle = if (!title.isNullOrBlank()) ClassicalMusicNormalizer.normalizeTitle(title) else title
        } else {
            searchArtist = artist
            searchTitle = title
        }

        val originalQuery = DeezerQueryBuilder.buildFieldQuery(searchArtist, searchTitle)
            ?: return emptyList()

        val steps = mutableListOf<SearchStep>()
        steps += SearchStep(originalQuery, if (isClassical) "classical_normalized" else "original")

        val haveBoth = !artist.isNullOrBlank() && !title.isNullOrBlank()

        if (haveBoth) {
            val cleanArtist = DeezerQueryBuilder.cleanArtistConnectors(artist!!)
            val cleanTitle = DeezerQueryBuilder.cleanTitleParens(title!!)
            steps += SearchStep("artist:\"$cleanArtist\" track:\"$cleanTitle\"", "cleaned")

            val artistParts = DeezerQueryBuilder.splitArtists(artist)
            if (artistParts.size >= 2) {
                val secondArtist = artistParts[1]
                steps += SearchStep("artist:\"$secondArtist\" track:\"$title\"", "second_artist")
            }

            steps += SearchStep("artist:\"$title\" track:\"$artist\"", "swapped")
        }

        if (!title.isNullOrBlank() && title.length >= 5) {
            val combined = "${searchArtist ?: ""} $searchTitle".trim()
            steps += SearchStep(combined, "combined_free")
        }

        if (isClassical && !artist.isNullOrBlank()) {
            // .drop(1) — getSearchVariations returns the original (artist, title)
            // pair as element 0; we already enqueued that as "classical_normalized".
            for ((varArtist, varTitle) in ClassicalMusicNormalizer.getSearchVariations(artist, title ?: "").drop(1)) {
                if (varTitle.isNotBlank()) {
                    steps += SearchStep("artist:\"$varArtist\" track:\"$varTitle\"", "classical_variation")
                } else {
                    steps += SearchStep(varArtist, "classical_artist_only")
                }
            }
        }

        return steps
    }
}
