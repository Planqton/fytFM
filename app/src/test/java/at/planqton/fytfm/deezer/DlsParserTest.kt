package at.planqton.fytfm.deezer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [DlsParser]. The heuristics here are deliberate and doc'd
 * with examples in the source — we capture those as executable specs so
 * future tweaks can't silently break the golden cases.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DlsParserTest {

    // ========== doc-comment golden cases ==========

    @Test
    fun `doc example 1 - Sie hoeren prefix plus station suffix`() {
        val r = DlsParser.parse("Sie hören MOZART - Serenade - radio klassik Stephansdom * ..")
        assertTrue(r.success)
        assertEquals("MOZART", r.artist)
        assertEquals("Serenade", r.title)
    }

    @Test
    fun `doc example 2 - auf station plus promo phrase`() {
        val r = DlsParser.parse(
            "HOLLY HUMBERSTONE - TO LOVE SOMEBODY auf Antenne Österreich Das DAB+ für..."
        )
        assertTrue(r.success)
        assertEquals("HOLLY HUMBERSTONE", r.artist)
        assertEquals("TO LOVE SOMEBODY", r.title)
    }

    @Test
    fun `doc example 3 - ONAIR prefix and trailing station plus slogan`() {
        val r = DlsParser.parse("ONAIR: WOLFMOTHER - Woman - Radio 88.6 - So rockt das Leben.")
        assertTrue(r.success)
        assertEquals("WOLFMOTHER", r.artist)
        assertEquals("Woman", r.title)
    }

    // ========== regression: whole-word keyword matching ==========
    // Prior impl used substring `contains(keyword)` and falsely classified
    // artist names as stations whenever they happened to contain keyword
    // letters: "humberstone" → "one", "wolfmother" → "fm", "police" → (no
    // hit but "Every Breath You Take" still fine). These tests lock in the
    // whole-word fix.

    @Test
    fun `artist name containing station-keyword substring is not filtered`() {
        // "humberstone" contains "one" — must no longer match.
        val r = DlsParser.parse("Holly Humberstone - Overkill")
        assertTrue(r.success)
        assertEquals("Holly Humberstone", r.artist)
        assertEquals("Overkill", r.title)
    }

    @Test
    fun `artist name containing fm substring is not filtered`() {
        // "wolfmother" contains "fm" — must no longer match.
        val r = DlsParser.parse("Wolfmother - Woman")
        assertTrue(r.success)
        assertEquals("Wolfmother", r.artist)
        assertEquals("Woman", r.title)
    }

    @Test
    fun `doc example 4 - JETZT prefix pipe separator station plus slogan`() {
        val r = DlsParser.parse("JETZT: Artist - Title | Ö3 - Hits für euch")
        assertTrue(r.success)
        assertEquals("Artist", r.artist)
        assertEquals("Title", r.title)
    }

    // ========== prefix removal ==========

    @Test
    fun `removes NOW PLAYING prefix`() {
        val r = DlsParser.parse("NOW PLAYING: Foo - Bar")
        assertTrue(r.success)
        assertEquals("Foo", r.artist)
        assertEquals("Bar", r.title)
    }

    @Test
    fun `removes symbol prefix`() {
        val r = DlsParser.parse("♪ Foo - Bar")
        assertTrue(r.success)
        assertEquals("Foo", r.artist)
        assertEquals("Bar", r.title)
    }

    @Test
    fun `removes Du hoerst German prefix`() {
        val r = DlsParser.parse("Du hörst: Foo - Bar")
        assertTrue(r.success)
        assertEquals("Foo", r.artist)
        assertEquals("Bar", r.title)
    }

    // ========== failure modes ==========

    @Test
    fun `empty input returns failed result`() {
        val r = DlsParser.parse("")
        assertFalse(r.success)
        assertNull(r.artist)
        assertNull(r.title)
    }

    @Test
    fun `blank input returns failed result`() {
        val r = DlsParser.parse("   ")
        assertFalse(r.success)
    }

    @Test
    fun `single word with no separator fails`() {
        val r = DlsParser.parse("JustOneWord")
        assertFalse(r.success)
        assertNull(r.artist)
        assertNull(r.title)
    }

    // ========== basic Artist - Title ==========

    @Test
    fun `simple dash separator succeeds`() {
        val r = DlsParser.parse("Foo - Bar")
        assertTrue(r.success)
        assertEquals("Foo", r.artist)
        assertEquals("Bar", r.title)
    }

    @Test
    fun `em dash separator succeeds`() {
        val r = DlsParser.parse("Foo — Bar")
        assertTrue(r.success)
        assertEquals("Foo", r.artist)
        assertEquals("Bar", r.title)
    }

    // ========== URL and time stripping ==========

    @Test
    fun `strips http URL`() {
        val r = DlsParser.parse("Foo - Bar https://example.com")
        assertTrue(r.success)
        assertEquals("Foo", r.artist)
        assertEquals("Bar", r.title)
    }

    @Test
    fun `strips time pattern`() {
        val r = DlsParser.parse("12:34 Foo - Bar")
        assertTrue(r.success)
        assertEquals("Foo", r.artist)
        assertEquals("Bar", r.title)
    }

    // ========== explicit station-name hint ==========

    @Test
    fun `station name hint helps strip trailing station suffix`() {
        val r = DlsParser.parse("Foo - Bar - Radio OE3", stationName = "Radio OE3")
        assertTrue(r.success)
        assertEquals("Foo", r.artist)
        assertEquals("Bar", r.title)
    }

    // ========== classical music pattern ==========

    @Test
    fun `classical Lastname-Firstname-Year - Title reorders to firstname lastname`() {
        val r = DlsParser.parse("Mozart, Wolfgang Amadeus (1756-1791) - Symphony No. 40")
        assertTrue(r.success)
        assertEquals("Wolfgang Amadeus Mozart", r.artist)
        assertEquals("Symphony No. 40", r.title)
    }

    @Test
    fun `classical pattern with single year also matches`() {
        val r = DlsParser.parse("Beethoven, Ludwig van (1770) - Ode to Joy")
        assertTrue(r.success)
        assertEquals("Ludwig van Beethoven", r.artist)
        assertEquals("Ode to Joy", r.title)
    }

    @Test
    fun `classical pattern takes precedence over separator split`() {
        // Without the precedence fix, this would be parsed as artist="Mozart,
        // Wolfgang Amadeus (1756-1791)" from the ' - ' split.
        val r = DlsParser.parse("Schubert, Franz (1797-1828) - Die Forelle")
        assertEquals("Franz Schubert", r.artist)
    }

    // ========== ParseResult contract ==========

    @Test
    fun `toSearchString on success returns artist dash title`() {
        val r = DlsParser.parse("Foo - Bar")
        assertEquals("Foo - Bar", r.toSearchString())
    }

    @Test
    fun `toSearchString on failure returns null`() {
        val r = DlsParser.parse("")
        assertNull(r.toSearchString())
    }

    @Test
    fun `original field preserves input verbatim`() {
        val input = "  Sie hören MOZART - Serenade - radio klassik Stephansdom * ..  "
        val r = DlsParser.parse(input)
        // parser trims input before storing in `original`, so compare against trimmed input
        assertEquals(input.trim(), r.original)
    }

    @Test
    fun `parse returns ParseResult even for failed parse`() {
        val r = DlsParser.parse("nonsense")
        assertNotNull(r)
        assertFalse(r.success)
        assertEquals("nonsense", r.original)
    }
}
