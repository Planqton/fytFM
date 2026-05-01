package at.planqton.fytfm.deezer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [RtCombinerHelpers] — the buffer-decision and
 * track-relevance heuristics that drive RT → Deezer matching.
 *
 * Roadmap §7.1 explicitly flagged these as untested. The Buffer-Combine
 * end-to-end coverage still lives in RtCombinerTest (which uses runTest
 * + a mocked HTTP layer); this file pins the *pure* slice that the
 * combiner relies on so a refactor of separators or thresholds can't
 * silently change matching semantics.
 */
class RtCombinerHelpersTest {

    private val productionSeparators = listOf(" - ", " – ", " — ", " / ", " | ")
    private val productionThreshold = 25

    private fun shouldBuffer(rt: String) =
        RtCombinerHelpers.shouldBufferFirst(rt, productionSeparators, productionThreshold)

    // ============ shouldBufferFirst ============

    @Test
    fun `RT with hyphen-space separator is NOT buffered (treated as complete)`() {
        assertFalse(shouldBuffer("Beatles - Yesterday"))
    }

    @Test
    fun `RT with en-dash separator is NOT buffered`() {
        assertFalse(shouldBuffer("Beatles – Yesterday"))
    }

    @Test
    fun `RT with em-dash separator is NOT buffered`() {
        assertFalse(shouldBuffer("Beatles — Yesterday"))
    }

    @Test
    fun `RT with slash separator is NOT buffered`() {
        assertFalse(shouldBuffer("Beatles / Yesterday"))
    }

    @Test
    fun `RT with pipe separator is NOT buffered`() {
        assertFalse(shouldBuffer("Beatles | Yesterday"))
    }

    @Test
    fun `short RT without separator IS buffered (e g Kronehit half-message)`() {
        // 7 chars, no separator → buffer it.
        assertTrue(shouldBuffer("Beatles"))
    }

    @Test
    fun `RT exactly at threshold (25 chars) is NOT buffered (boundary exclusive)`() {
        // length = 25, condition is `length < threshold`, so equals = false.
        val rt = "x".repeat(25)
        assertEquals(25, rt.length)
        assertFalse(shouldBuffer(rt))
    }

    @Test
    fun `RT of 24 chars without separator IS buffered (just under threshold)`() {
        val rt = "x".repeat(24)
        assertTrue(shouldBuffer(rt))
    }

    @Test
    fun `long RT without separator is NOT buffered (likely a full message)`() {
        // 30 chars, no separator → assume complete.
        assertFalse(shouldBuffer("This is a long radiotext line"))
    }

    @Test
    fun `hyphen WITHOUT spaces does NOT count as separator`() {
        // "Beatles-Yesterday" → no " - ", short → buffered.
        assertTrue(shouldBuffer("Beatles-Yesterday"))
    }

    @Test
    fun `empty RT is buffered (length 0 is below threshold and has no separator)`() {
        // shouldBufferFirst returns true for empty string. The caller
        // (RtCombiner.processRt) filters blanks earlier so this doesn't
        // surface — but the helper is honest about what it does.
        assertTrue(shouldBuffer(""))
    }

    // ============ isTrackRelevant ============

    private fun track(artist: String, title: String) = TrackInfo(artist = artist, title = title)

    @Test
    fun `track is relevant when artist contains a word from the search term`() {
        val t = track("Beatles", "Yesterday")
        assertTrue(RtCombinerHelpers.isTrackRelevant(t, listOf("Beatles song")))
    }

    @Test
    fun `track is relevant when title contains a word from the search term`() {
        val t = track("Beatles", "Yesterday")
        assertTrue(RtCombinerHelpers.isTrackRelevant(t, listOf("Yesterday")))
    }

    @Test
    fun `track is NOT relevant when no qualifying word matches`() {
        val t = track("Beatles", "Yesterday")
        // "Madonna" + "Like" — neither is in "Beatles Yesterday".
        assertFalse(RtCombinerHelpers.isTrackRelevant(t, listOf("Madonna Like")))
    }

    @Test
    fun `match is case-insensitive on both sides`() {
        val t = track("BEATLES", "YESTERDAY")
        assertTrue(RtCombinerHelpers.isTrackRelevant(t, listOf("beatles")))
    }

    @Test
    fun `each search term must independently match (logical AND across terms)`() {
        val t = track("Beatles", "Yesterday")
        // First term matches, second doesn't → reject.
        assertFalse(
            RtCombinerHelpers.isTrackRelevant(t, listOf("Beatles", "Madonna")),
        )
    }

    @Test
    fun `words shorter than 3 chars are filtered out (stop-word noise removal)`() {
        val t = track("Beatles", "Yesterday")
        // "in a Yesterday" → "in" and "a" filtered, "Yesterday" survives → match.
        assertTrue(RtCombinerHelpers.isTrackRelevant(t, listOf("in a Yesterday")))
    }

    @Test
    fun `term consisting only of stop-words rejects the track`() {
        // "in a" → both <3 chars, filtered out, words list empty → no match.
        // This is the documented invariant: empty word list = no signal = reject.
        val t = track("Beatles", "Yesterday")
        assertFalse(RtCombinerHelpers.isTrackRelevant(t, listOf("in a")))
    }

    @Test
    fun `empty search-term list trivially matches any track`() {
        val t = track("Beatles", "Yesterday")
        assertTrue(RtCombinerHelpers.isTrackRelevant(t, emptyList()))
    }

    @Test
    fun `partial substring match counts (no word-boundary requirement)`() {
        // "Beat" is a substring of "beatles" → match.
        val t = track("Beatles", "Yesterday")
        assertTrue(RtCombinerHelpers.isTrackRelevant(t, listOf("Beat")))
    }

    @Test
    fun `at least one qualifying word per term must match (not all of them)`() {
        // Term has TWO ≥3-char words, only one matches → that's enough.
        val t = track("Beatles", "Yesterday")
        assertTrue(
            RtCombinerHelpers.isTrackRelevant(t, listOf("Beatles MadonnaIsNotHere")),
        )
    }

    @Test
    fun `custom minWordLen lets caller tighten or loosen the stop-word filter`() {
        val t = track("Beatles", "Yesterday")
        // With minWordLen=10 → "Beatles" gets filtered (len 7 < 10),
        // word list empty → reject.
        assertFalse(RtCombinerHelpers.isTrackRelevant(t, listOf("Beatles"), minWordLen = 10))
    }
}

private fun assertEquals(expected: Int, actual: Int) =
    org.junit.Assert.assertEquals(expected.toLong(), actual.toLong())
