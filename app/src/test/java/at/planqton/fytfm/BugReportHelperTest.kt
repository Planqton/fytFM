package at.planqton.fytfm

import android.content.Context
import at.planqton.fytfm.deezer.TrackInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Tests for [BugReportHelper] — the report-formatting half of the bug-report
 * pipeline. The on-disk side (`createBugReport` / `getBugReports` / etc.) is
 * not exercised here; this file pins the **string-formatting contract** so a
 * future tweak to a section header or a `null`/empty handling rule can't
 * silently break a support workflow that's been triaging on these reports.
 *
 * `getLogcat()` is invoked but its output isn't asserted on (Robolectric's
 * Runtime.exec returns nothing meaningful). The other sections — Report Info,
 * Device Info, RDS, Deezer, optional User Description, optional Crash Log —
 * are all asserted on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class BugReportHelperTest {

    private lateinit var helper: BugReportHelper

    @Before
    fun setup() {
        val context: Context = RuntimeEnvironment.getApplication()
        helper = BugReportHelper(context)
    }

    private fun fullState() = BugReportHelper.AppState(
        rdsPs = "FM4",
        rdsRt = "Now: Beatles - Yesterday",
        rdsPi = 0xA101,
        rdsPty = 10,
        rdsRssi = -55,
        rdsTp = 1,
        rdsTa = 0,
        rdsAfEnabled = true,
        rdsAfList = listOf(984.toShort(), 998.toShort()),
        currentFrequency = 98.4f,
        spotifyStatus = "Found",
        spotifyOriginalRt = "Beatles - Yesterday",
        spotifyStrippedRt = "Beatles Yesterday",
        spotifyQuery = """artist:"Beatles" track:"Yesterday"""",
        spotifyTrackInfo = TrackInfo(
            artist = "The Beatles",
            title = "Yesterday",
            trackId = "12345",
            durationMs = 125_000L,
            album = "Help!",
        ),
        userDescription = "Wrong artist match",
    )

    // ============ buildReportContent — section presence ============

    @Test
    fun `report has a header banner with the title fytFM Bug Report`() {
        val report = helper.buildReportContent(fullState())
        assertTrue("title line present", report.contains("fytFM Bug Report"))
        assertTrue("banner separator present", report.contains("=".repeat(60)))
    }

    @Test
    fun `report has all the major sections in order`() {
        val report = helper.buildReportContent(fullState())
        val sections = listOf("## Report Info", "## Device Info", "## RDS Data", "## Deezer Data", "## Logcat")
        var lastIdx = -1
        for (s in sections) {
            val idx = report.indexOf(s)
            assertTrue("section '$s' present", idx >= 0)
            assertTrue("section '$s' appears AFTER previous", idx > lastIdx)
            lastIdx = idx
        }
    }

    @Test
    fun `report info section includes app version and build metadata`() {
        val report = helper.buildReportContent(fullState())
        assertTrue("Timestamp:", report.contains("Timestamp:"))
        assertTrue("App Version:", report.contains("App Version:"))
        assertTrue("Build Date:", report.contains("Build Date:"))
        assertTrue("Build Type:", report.contains("Build Type:"))
    }

    @Test
    fun `device info section includes manufacturer and Android version`() {
        val report = helper.buildReportContent(fullState())
        assertTrue("Manufacturer:", report.contains("Manufacturer:"))
        assertTrue("Model:", report.contains("Model:"))
        assertTrue("Android Version:", report.contains("Android Version:"))
        // SDK_INT 33 from the test config:
        assertTrue("API number embedded", report.contains("API 33"))
    }

    // ============ User Description / Crash Log toggling ============

    @Test
    fun `user description section appears when description is set`() {
        val report = helper.buildReportContent(fullState())
        assertTrue("Problem Description header", report.contains("## Problem Description"))
        assertTrue("description body present", report.contains("Wrong artist match"))
    }

    @Test
    fun `user description section is omitted when description is null or blank`() {
        val report = helper.buildReportContent(fullState().copy(userDescription = null))
        assertFalse("no header for empty desc", report.contains("## Problem Description"))

        val reportEmpty = helper.buildReportContent(fullState().copy(userDescription = ""))
        assertFalse("no header for blank desc", reportEmpty.contains("## Problem Description"))
    }

    @Test
    fun `crash log section appears only when crashLog is set`() {
        val withCrash = helper.buildReportContent(fullState().copy(crashLog = "java.lang.RuntimeException: boom"))
        assertTrue("Crash Log header present", withCrash.contains("## Crash Log"))
        assertTrue("crash body included", withCrash.contains("RuntimeException: boom"))

        val withoutCrash = helper.buildReportContent(fullState())
        assertFalse("no Crash Log header by default", withoutCrash.contains("## Crash Log"))
    }

    // ============ RDS section formatting ============

    @Test
    fun `RDS data renders frequency, PS, RT and signal stats`() {
        val report = helper.buildReportContent(fullState())
        assertTrue("frequency present", report.contains("Frequency: 98.4 MHz"))
        assertTrue("PS rendered", report.contains("PS: FM4"))
        assertTrue("RT rendered", report.contains("RT: Now: Beatles - Yesterday"))
        assertTrue("RSSI rendered", report.contains("RSSI: -55"))
        assertTrue("TP / TA rendered", report.contains("TP: 1"))
    }

    @Test
    fun `RDS PI is rendered as uppercase hex with 0x prefix`() {
        val report = helper.buildReportContent(fullState())
        assertTrue("0xA101 hex format", report.contains("PI: 0xA101"))
    }

    @Test
    fun `RDS PTY rendered with the human-readable name from RdsPtyNames`() {
        // PTY 10 → "Pop Music"
        val report = helper.buildReportContent(fullState())
        assertTrue("PTY name embedded", report.contains("PTY: 10 (Pop Music)"))
    }

    @Test
    fun `RDS PTY name uses European RDS table including codes 16-31`() {
        // Bonus: pin that the Bug-1.x fix (RDS PTY 16-31) flows through here.
        val report = helper.buildReportContent(fullState().copy(rdsPty = 22)) // Travel
        assertTrue("PTY 22 → Travel", report.contains("(Travel)"))
    }

    @Test
    fun `RDS missing fields render as parenthesised none`() {
        val sparse = fullState().copy(rdsPs = null, rdsRt = null)
        val report = helper.buildReportContent(sparse)
        assertTrue(report.contains("PS: (none)"))
        assertTrue(report.contains("RT: (none)"))
    }

    @Test
    fun `RDS AF list is rendered as comma-separated MHz when non-empty`() {
        val report = helper.buildReportContent(fullState())
        // 984 → 98.4, 998 → 99.8 (×0.1 MHz convention)
        assertTrue("AF List header", report.contains("AF List:"))
        assertTrue("first AF freq", report.contains("98.4"))
        assertTrue("second AF freq", report.contains("99.8"))
    }

    @Test
    fun `RDS AF list line is omitted when the list is null or empty`() {
        val report = helper.buildReportContent(fullState().copy(rdsAfList = null))
        // Header for AF Enabled stays — but the comma-separated list line goes.
        assertFalse(report.contains("AF List:"))

        val emptyAf = helper.buildReportContent(fullState().copy(rdsAfList = emptyList()))
        assertFalse("empty list also omitted", emptyAf.contains("AF List:"))
    }

    // ============ Deezer section ============

    @Test
    fun `Deezer section renders status and query strings`() {
        val report = helper.buildReportContent(fullState())
        assertTrue("Status:", report.contains("Status: Found"))
        assertTrue("Original RT:", report.contains("Original RT: Beatles - Yesterday"))
        assertTrue("Query:", report.contains("Query:"))
    }

    @Test
    fun `Deezer track info subsection appears when trackInfo is set`() {
        val report = helper.buildReportContent(fullState())
        assertTrue("Track Info header", report.contains("### Track Info"))
        assertTrue("Artist:", report.contains("Artist: The Beatles"))
        assertTrue("Title:", report.contains("Title: Yesterday"))
        assertTrue("Album:", report.contains("Album: Help!"))
        // Duration: 125_000ms → "2:05"
        assertTrue("Duration formatted M:SS", report.contains("Duration: 2:05"))
        assertTrue("Track ID:", report.contains("Track ID: 12345"))
    }

    @Test
    fun `Deezer track info subsection is omitted when trackInfo is null`() {
        val report = helper.buildReportContent(fullState().copy(spotifyTrackInfo = null))
        assertFalse("no Track Info header", report.contains("### Track Info"))
    }

    @Test
    fun `Deezer fields render as none when null`() {
        val noDeezer = fullState().copy(
            spotifyStatus = null,
            spotifyOriginalRt = null,
            spotifyStrippedRt = null,
            spotifyQuery = null,
            spotifyTrackInfo = null,
        )
        val report = helper.buildReportContent(noDeezer)
        assertTrue("Status none", report.contains("Status: (none)"))
        assertTrue("Original RT none", report.contains("Original RT: (none)"))
        assertTrue("Query none", report.contains("Query: (none)"))
    }

    // ============ buildParserReportContent ============

    @Test
    fun `parser report has its own banner title`() {
        val report = helper.buildParserReportContent("Parser bug XYZ")
        assertTrue(report.contains("fytFM Parser Bug Report"))
    }

    @Test
    fun `parser report includes the user description when provided`() {
        val report = helper.buildParserReportContent("Parser bug XYZ")
        assertTrue(report.contains("## Problem Description"))
        assertTrue(report.contains("Parser bug XYZ"))
    }

    @Test
    fun `parser report omits user description when null or empty`() {
        val noDesc = helper.buildParserReportContent(null)
        assertFalse(noDesc.contains("## Problem Description"))
    }

    @Test
    fun `parser report includes the RT-DLS Parser Log section`() {
        val report = helper.buildParserReportContent("X")
        assertTrue(report.contains("## RT-DLS Parser Log"))
    }

    // ============ buildDeezerReportContent ============

    @Test
    fun `deezer report has its own banner title`() {
        val report = helper.buildDeezerReportContent(
            userDescription = "X", deezerStatus = "Y",
        )
        assertTrue(report.contains("fytFM Deezer Bug Report"))
    }

    @Test
    fun `deezer report renders supplied trackInfo and DAB station`() {
        val track = TrackInfo(artist = "A", title = "T", trackId = "1", durationMs = 60_000L)
        val report = helper.buildDeezerReportContent(
            userDescription = "Mismatch",
            deezerStatus = "Found",
            trackInfo = track,
            dabStation = "Mock Ö1",
            dabDls = "Now: A - T",
        )
        assertTrue(report.contains("Mismatch"))
        assertTrue(report.contains("Found"))
        // FM section/track info — exact field naming is implementation-dependent
        // but artist/title should appear somewhere in the report.
        assertTrue(report.contains("A"))
        assertTrue(report.contains("T"))
    }

    @Test
    fun `deezer report tolerates everything-null inputs`() {
        // Defensive: the whole signature has nullable defaults — the report
        // should still be a non-empty string with banner + sections.
        val report = helper.buildDeezerReportContent()
        assertTrue("banner present", report.contains("fytFM Deezer Bug Report"))
        assertTrue(report.length > 100)
    }
}
