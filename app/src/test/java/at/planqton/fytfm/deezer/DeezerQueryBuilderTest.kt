package at.planqton.fytfm.deezer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [DeezerQueryBuilder]. No JSON / no Robolectric — these are
 * pure-string transforms.
 */
class DeezerQueryBuilderTest {

    // ===== cleanFreeQuery =====

    @Test
    fun `cleanFreeQuery strips parenthesised remix tag`() {
        assertEquals("Song", DeezerQueryBuilder.cleanFreeQuery("Song (Remix)"))
    }

    @Test
    fun `cleanFreeQuery strips bracket tag`() {
        assertEquals("Song", DeezerQueryBuilder.cleanFreeQuery("Song [Live]"))
    }

    @Test
    fun `cleanFreeQuery strips feat tail`() {
        assertEquals("Song", DeezerQueryBuilder.cleanFreeQuery("Song feat. Other Artist"))
    }

    @Test
    fun `cleanFreeQuery strips ft tail (lowercase)`() {
        assertEquals("Song", DeezerQueryBuilder.cleanFreeQuery("Song ft. Someone"))
    }

    @Test
    fun `cleanFreeQuery is case-insensitive on FEAT`() {
        assertEquals("Song", DeezerQueryBuilder.cleanFreeQuery("Song FEAT. Other"))
    }

    @Test
    fun `cleanFreeQuery strips ampersand collaborator tail`() {
        assertEquals("Song", DeezerQueryBuilder.cleanFreeQuery("Song & Other"))
    }

    @Test
    fun `cleanFreeQuery handles bracket then feat`() {
        assertEquals("Song", DeezerQueryBuilder.cleanFreeQuery("Song [Radio Edit] feat. X"))
    }

    @Test
    fun `cleanFreeQuery returns input unchanged when nothing matches`() {
        assertEquals("Plain Title", DeezerQueryBuilder.cleanFreeQuery("Plain Title"))
    }

    @Test
    fun `cleanFreeQuery trims leftover whitespace`() {
        // Bracket + trailing space removed → trim collapses the gap.
        assertEquals("Song", DeezerQueryBuilder.cleanFreeQuery("  Song [Mix]  "))
    }

    // ===== buildFieldQuery =====

    @Test
    fun `buildFieldQuery emits artist plus track when both present`() {
        assertEquals(
            """artist:"Queen" track:"Bohemian Rhapsody"""",
            DeezerQueryBuilder.buildFieldQuery("Queen", "Bohemian Rhapsody"),
        )
    }

    @Test
    fun `buildFieldQuery emits track only when artist blank`() {
        assertEquals(
            """track:"Some Song"""",
            DeezerQueryBuilder.buildFieldQuery("", "Some Song"),
        )
    }

    @Test
    fun `buildFieldQuery emits artist only when title blank`() {
        // trailing space is trimmed.
        assertEquals(
            """artist:"Queen"""",
            DeezerQueryBuilder.buildFieldQuery("Queen", null),
        )
    }

    @Test
    fun `buildFieldQuery returns null when both inputs are blank`() {
        assertNull(DeezerQueryBuilder.buildFieldQuery(null, null))
        assertNull(DeezerQueryBuilder.buildFieldQuery("", "  "))
    }

    // ===== cleanArtistConnectors =====

    @Test
    fun `cleanArtistConnectors drops x and following text`() {
        // Note: "x" must be surrounded by whitespace to count as a
        // connector — names like "Calvin Harris" stay intact.
        assertEquals("DJ Snake", DeezerQueryBuilder.cleanArtistConnectors("DJ Snake x Lil Jon"))
    }

    @Test
    fun `cleanArtistConnectors drops ampersand collaborator`() {
        assertEquals("Sonny", DeezerQueryBuilder.cleanArtistConnectors("Sonny & Cher"))
    }

    @Test
    fun `cleanArtistConnectors drops vs section`() {
        assertEquals("Tiesto", DeezerQueryBuilder.cleanArtistConnectors("Tiesto vs. Diplo"))
    }

    @Test
    fun `cleanArtistConnectors leaves single-name artists unchanged`() {
        assertEquals("Queen", DeezerQueryBuilder.cleanArtistConnectors("Queen"))
    }

    @Test
    fun `cleanArtistConnectors does not strip x inside a word`() {
        // "Mixie Lin" has "x" without surrounding whitespace — must stay.
        assertEquals("Mixie Lin", DeezerQueryBuilder.cleanArtistConnectors("Mixie Lin"))
    }

    @Test
    fun `cleanArtistConnectors trims trailing whitespace after strip`() {
        assertEquals("Foo", DeezerQueryBuilder.cleanArtistConnectors("Foo  feat. Bar"))
    }

    // ===== cleanTitleParens =====

    @Test
    fun `cleanTitleParens strips parens but keeps feat words (whitespace not collapsed)`() {
        // Unlike cleanFreeQuery, this DOES NOT strip 'feat.' — Deezer
        // sometimes indexes that into the title. The parens removal leaves
        // a double space — we don't normalise it (the search API tolerates).
        assertEquals("Song  feat. X", DeezerQueryBuilder.cleanTitleParens("Song (Remix) feat. X"))
    }

    @Test
    fun `cleanTitleParens strips brackets`() {
        assertEquals("Song", DeezerQueryBuilder.cleanTitleParens("Song [Live Edit]"))
    }

    @Test
    fun `cleanTitleParens trims surrounding spaces`() {
        assertEquals("Song", DeezerQueryBuilder.cleanTitleParens("  Song  "))
    }

    // ===== splitArtists =====

    @Test
    fun `splitArtists splits on x with whitespace`() {
        assertEquals(listOf("DJ Snake", "Lil Jon"), DeezerQueryBuilder.splitArtists("DJ Snake x Lil Jon"))
    }

    @Test
    fun `splitArtists splits on ampersand`() {
        assertEquals(listOf("Sonny", "Cher"), DeezerQueryBuilder.splitArtists("Sonny & Cher"))
    }

    @Test
    fun `splitArtists splits on feat dot`() {
        assertEquals(
            listOf("Eminem", "Rihanna"),
            DeezerQueryBuilder.splitArtists("Eminem feat. Rihanna"),
        )
    }

    @Test
    fun `splitArtists splits on ft dot`() {
        assertEquals(
            listOf("Drake", "Future"),
            DeezerQueryBuilder.splitArtists("Drake ft. Future"),
        )
    }

    @Test
    fun `splitArtists returns single-element list for solo artists`() {
        assertEquals(listOf("Queen"), DeezerQueryBuilder.splitArtists("Queen"))
    }

    @Test
    fun `splitArtists drops blank parts from trailing connectors`() {
        // "Foo feat. " → ["Foo", ""] → blank dropped.
        val result = DeezerQueryBuilder.splitArtists("Foo feat. ")
        assertEquals(listOf("Foo"), result)
    }

    @Test
    fun `splitArtists handles three-way collaboration`() {
        assertEquals(
            listOf("A", "B", "C"),
            DeezerQueryBuilder.splitArtists("A & B feat. C"),
        )
    }

    @Test
    fun `splitArtists trims whitespace around each part`() {
        val result = DeezerQueryBuilder.splitArtists("  Foo  &  Bar  ")
        assertTrue("expected exactly two trimmed parts, got $result", result == listOf("Foo", "Bar"))
    }
}
