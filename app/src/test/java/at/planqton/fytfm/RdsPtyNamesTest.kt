package at.planqton.fytfm

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [RdsPtyNames]. Pin the European RDS PTY mapping (IEC 62106)
 * since pre-extraction the helper only handled 0..15 and silently
 * returned "Unknown" for codes 16..31 — which European DAB+ uses for
 * Weather, Travel, Folk Music etc.
 *
 * The full table is asserted because PTY codes are part of the spec
 * (broadcasters can't unilaterally change them) and a regression here
 * would silently corrupt every bug report from a station tagged ≥ 16.
 */
class RdsPtyNamesTest {

    @Test
    fun `0 maps to None`() = assertEquals("None", RdsPtyNames.nameFor(0))

    @Test
    fun `RBDS-shared codes 1 through 15 map per the standard`() {
        // 0..15 are identical in RDS (Europe) and RBDS (NA).
        assertEquals("News", RdsPtyNames.nameFor(1))
        assertEquals("Current Affairs", RdsPtyNames.nameFor(2))
        assertEquals("Information", RdsPtyNames.nameFor(3))
        assertEquals("Sport", RdsPtyNames.nameFor(4))
        assertEquals("Education", RdsPtyNames.nameFor(5))
        assertEquals("Drama", RdsPtyNames.nameFor(6))
        assertEquals("Culture", RdsPtyNames.nameFor(7))
        assertEquals("Science", RdsPtyNames.nameFor(8))
        assertEquals("Varied", RdsPtyNames.nameFor(9))
        assertEquals("Pop Music", RdsPtyNames.nameFor(10))
        assertEquals("Rock Music", RdsPtyNames.nameFor(11))
        assertEquals("Easy Listening", RdsPtyNames.nameFor(12))
        assertEquals("Light Classical", RdsPtyNames.nameFor(13))
        assertEquals("Serious Classical", RdsPtyNames.nameFor(14))
        assertEquals("Other Music", RdsPtyNames.nameFor(15))
    }

    @Test
    fun `European-only codes 16 through 31 map per IEC 62106`() {
        // These are the codes the pre-extraction helper missed — DAB+
        // broadcasters in EU routinely transmit them.
        assertEquals("Weather", RdsPtyNames.nameFor(16))
        assertEquals("Finance", RdsPtyNames.nameFor(17))
        assertEquals("Children's Programmes", RdsPtyNames.nameFor(18))
        assertEquals("Social Affairs", RdsPtyNames.nameFor(19))
        assertEquals("Religion", RdsPtyNames.nameFor(20))
        assertEquals("Phone-In", RdsPtyNames.nameFor(21))
        assertEquals("Travel", RdsPtyNames.nameFor(22))
        assertEquals("Leisure", RdsPtyNames.nameFor(23))
        assertEquals("Jazz Music", RdsPtyNames.nameFor(24))
        assertEquals("Country Music", RdsPtyNames.nameFor(25))
        assertEquals("National Music", RdsPtyNames.nameFor(26))
        assertEquals("Oldies Music", RdsPtyNames.nameFor(27))
        assertEquals("Folk Music", RdsPtyNames.nameFor(28))
        assertEquals("Documentary", RdsPtyNames.nameFor(29))
        assertEquals("Alarm Test", RdsPtyNames.nameFor(30))
        assertEquals("Alarm", RdsPtyNames.nameFor(31))
    }

    @Test
    fun `codes outside 0 to 31 fall back to Unknown`() {
        assertEquals("Unknown", RdsPtyNames.nameFor(32))
        assertEquals("Unknown", RdsPtyNames.nameFor(255))
        assertEquals("Unknown", RdsPtyNames.nameFor(Int.MAX_VALUE))
    }

    @Test
    fun `negative codes fall back to Unknown without throwing`() {
        // RDS PTY is unsigned in the spec but our Kotlin Int allows
        // negatives — defensive: don't throw on bad input.
        assertEquals("Unknown", RdsPtyNames.nameFor(-1))
        assertEquals("Unknown", RdsPtyNames.nameFor(Int.MIN_VALUE))
    }

    @Test
    fun `boundary codes 15 and 16 map correctly`() {
        // Regression-pin the RBDS/RDS boundary — easy off-by-one risk.
        assertEquals("Other Music", RdsPtyNames.nameFor(15))
        assertEquals("Weather", RdsPtyNames.nameFor(16))
    }

    @Test
    fun `boundary code 31 is the last valid name`() {
        assertEquals("Alarm", RdsPtyNames.nameFor(31))
        assertEquals("Unknown", RdsPtyNames.nameFor(32))
    }
}
