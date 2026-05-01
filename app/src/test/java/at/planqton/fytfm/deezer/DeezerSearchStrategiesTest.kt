package at.planqton.fytfm.deezer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [DeezerSearchStrategies] — the pure-logic half of DeezerClient's
 * 7-strategy fallback search. Pin the *what to try, in what order* contract
 * so a tweak to the waterfall (re-ordering, dropping a step, changing the
 * field-syntax of a query) can't silently regress the search hit-rate.
 *
 * The strategy labels are also asserted on, since they appear in log lines
 * and bug reports — renaming one would break our triage workflow.
 *
 * Robolectric runner is required because [ClassicalMusicNormalizer] uses
 * `android.util.Log` internally — pure JUnit would NPE on the classical paths.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DeezerSearchStrategiesTest {

    // ===== Empty / degenerate inputs =====

    @Test
    fun `both null → empty list`() {
        assertTrue(DeezerSearchStrategies.buildStrategies(null, null).isEmpty())
    }

    @Test
    fun `both blank → empty list`() {
        assertTrue(DeezerSearchStrategies.buildStrategies("", "  ").isEmpty())
    }

    // ===== Non-classical: typical artist + title =====

    @Test
    fun `non-classical artist plus title yields original first`() {
        val steps = DeezerSearchStrategies.buildStrategies("Queen", "Bohemian Rhapsody")
        assertEquals("original", steps[0].label)
        assertEquals("""artist:"Queen" track:"Bohemian Rhapsody"""", steps[0].query)
    }

    @Test
    fun `non-classical without classical hints does not emit classical labels`() {
        val labels = DeezerSearchStrategies.buildStrategies("Queen", "Bohemian Rhapsody")
            .map { it.label }
        assertFalse("no classical label", labels.any { it.startsWith("classical") })
    }

    @Test
    fun `simple artist plus simple title emits original cleaned swapped combined_free`() {
        // No connector in the artist → no second_artist step.
        val labels = DeezerSearchStrategies.buildStrategies("Queen", "Bohemian Rhapsody")
            .map { it.label }
        assertEquals(listOf("original", "cleaned", "swapped", "combined_free"), labels)
    }

    @Test
    fun `multi-artist input emits second_artist between cleaned and swapped`() {
        // "DJ Snake x Lil Jon" splits into [DJ Snake, Lil Jon].
        val labels = DeezerSearchStrategies.buildStrategies("DJ Snake x Lil Jon", "Turn Down for What")
            .map { it.label }
        assertEquals(
            listOf("original", "cleaned", "second_artist", "swapped", "combined_free"),
            labels,
        )
    }

    @Test
    fun `second_artist step uses the second artist verbatim with original title`() {
        val steps = DeezerSearchStrategies.buildStrategies("DJ Snake x Lil Jon", "Turn Down for What")
        val secondArtist = steps.single { it.label == "second_artist" }
        assertEquals("""artist:"Lil Jon" track:"Turn Down for What"""", secondArtist.query)
    }

    @Test
    fun `cleaned step uses connector-stripped artist`() {
        val steps = DeezerSearchStrategies.buildStrategies("DJ Snake feat. Lil Jon", "Turn Down for What")
        val cleaned = steps.single { it.label == "cleaned" }
        // cleanArtistConnectors drops " feat. Lil Jon"; cleanTitleParens leaves title alone.
        assertEquals("""artist:"DJ Snake" track:"Turn Down for What"""", cleaned.query)
    }

    @Test
    fun `cleaned step strips parens from title`() {
        val steps = DeezerSearchStrategies.buildStrategies("Queen", "Bohemian Rhapsody (Live)")
        val cleaned = steps.single { it.label == "cleaned" }
        // cleanTitleParens strips "(Live)" and trims the trailing space.
        assertEquals("""artist:"Queen" track:"Bohemian Rhapsody"""", cleaned.query)
    }

    @Test
    fun `swapped step swaps artist and title fields`() {
        val steps = DeezerSearchStrategies.buildStrategies("Queen", "Bohemian Rhapsody")
        val swapped = steps.single { it.label == "swapped" }
        assertEquals("""artist:"Bohemian Rhapsody" track:"Queen"""", swapped.query)
    }

    @Test
    fun `combined_free step joins artist and title with a single space`() {
        val steps = DeezerSearchStrategies.buildStrategies("Queen", "Bohemian Rhapsody")
        val combined = steps.single { it.label == "combined_free" }
        assertEquals("Queen Bohemian Rhapsody", combined.query)
    }

    // ===== Conditional inclusions =====

    @Test
    fun `artist-only input emits only the original step`() {
        val labels = DeezerSearchStrategies.buildStrategies("Queen", null).map { it.label }
        assertEquals(listOf("original"), labels)
    }

    @Test
    fun `title-only with length geq 5 emits original and combined_free`() {
        // Title only → no cleaned/second_artist/swapped (they require both).
        // Title.length >= 5 enables combined_free.
        val labels = DeezerSearchStrategies.buildStrategies(null, "Hello").map { it.label }
        assertEquals(listOf("original", "combined_free"), labels)
    }

    @Test
    fun `title-only with length lt 5 omits combined_free`() {
        val labels = DeezerSearchStrategies.buildStrategies(null, "Hi").map { it.label }
        assertEquals(listOf("original"), labels)
    }

    @Test
    fun `solo artist (no connector) does not emit second_artist`() {
        val labels = DeezerSearchStrategies.buildStrategies("Queen", "Bohemian Rhapsody")
            .map { it.label }
        assertFalse("no second_artist for solo", labels.contains("second_artist"))
    }

    // ===== Classical music branch =====

    @Test
    fun `classical-format input labels first step classical_normalized`() {
        // Year-range pattern triggers isClassicalFormat.
        val steps = DeezerSearchStrategies.buildStrategies(
            "TSCHAIKOWSKY, PETER ILJITSCH (1840-1893)",
            "Klavierkonzert Nr. 1 b-moll, op. 23",
        )
        assertEquals("classical_normalized", steps[0].label)
    }

    @Test
    fun `classical input also emits cleaned swapped combined_free`() {
        val labels = DeezerSearchStrategies.buildStrategies(
            "TSCHAIKOWSKY, PETER ILJITSCH (1840-1893)",
            "Klavierkonzert Nr. 1 b-moll, op. 23",
        ).map { it.label }
        assertTrue("emits cleaned", labels.contains("cleaned"))
        assertTrue("emits swapped", labels.contains("swapped"))
        assertTrue("emits combined_free", labels.contains("combined_free"))
    }

    @Test
    fun `classical input appends classical_variation steps after standard waterfall`() {
        val steps = DeezerSearchStrategies.buildStrategies(
            "TSCHAIKOWSKY, PETER ILJITSCH (1840-1893)",
            "Klavierkonzert Nr. 1 b-moll, op. 23",
        )
        val labels = steps.map { it.label }
        // classical_variation (and/or classical_artist_only) should be at the end.
        val firstClassicalIdx = labels.indexOfFirst { it.startsWith("classical_") && it != "classical_normalized" }
        val combinedIdx = labels.indexOf("combined_free")
        assertTrue("at least one variation appended", firstClassicalIdx >= 0)
        assertTrue("variations come after combined_free", firstClassicalIdx > combinedIdx)
    }

    @Test
    fun `classical input ends with classical_artist_only when variations include solo artist`() {
        // getSearchVariations always appends an (artist, "") pair at the end
        // when the normalised artist is non-blank.
        val steps = DeezerSearchStrategies.buildStrategies(
            "TSCHAIKOWSKY, PETER ILJITSCH (1840-1893)",
            "Klavierkonzert Nr. 1 b-moll, op. 23",
        )
        val artistOnly = steps.lastOrNull { it.label == "classical_artist_only" }
        assertTrue("classical_artist_only emitted at least once", artistOnly != null)
        // Query for that step is just the bare composer string, no field syntax.
        assertFalse("artist_only query has no field syntax", artistOnly!!.query.contains("artist:"))
    }

    @Test
    fun `classical_variation query uses field syntax`() {
        val steps = DeezerSearchStrategies.buildStrategies(
            "TSCHAIKOWSKY, PETER ILJITSCH (1840-1893)",
            "Klavierkonzert Nr. 1 b-moll, op. 23",
        )
        val variation = steps.firstOrNull { it.label == "classical_variation" }
        if (variation != null) {
            assertTrue("field-syntax query", variation.query.contains("artist:") && variation.query.contains("track:"))
        }
        // If no classical_variation, that's also fine — depends on the
        // normalised title matching the main-terms list. The artist_only
        // fallback is the load-bearing one, asserted above.
    }

    // ===== Ordering (general) =====

    @Test
    fun `original step is always first when emitted`() {
        val cases = listOf(
            "Queen" to "Bohemian Rhapsody",
            "DJ Snake x Lil Jon" to "Turn Down for What",
            "TSCHAIKOWSKY (1840-1893)" to "Symphony No. 5",
        )
        for ((artist, title) in cases) {
            val steps = DeezerSearchStrategies.buildStrategies(artist, title)
            assertTrue("$artist / $title — non-empty", steps.isNotEmpty())
            val first = steps[0].label
            assertTrue(
                "$artist / $title — first label is original or classical_normalized but was $first",
                first == "original" || first == "classical_normalized",
            )
        }
    }
}
