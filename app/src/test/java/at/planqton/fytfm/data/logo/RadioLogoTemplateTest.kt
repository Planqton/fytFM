package at.planqton.fytfm.data.logo

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [RadioLogoTemplate] and [StationLogo] — JSON round-trips and
 * the [StationLogo.matchPriority] rules that drive logo lookup at runtime.
 *
 * Robolectric is needed because both data classes use Android's
 * `org.json.JSONObject`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RadioLogoTemplateTest {

    // ============ StationLogo.matchPriority ============

    @Test
    fun `matchPriority returns 3 for PI hex match (highest priority)`() {
        val logo = StationLogo(pi = "A3E0", logoUrl = "x.png")
        assertEquals(3, logo.matchPriority(stationPs = null, stationPi = 0xA3E0, stationFrequency = null))
    }

    @Test
    fun `matchPriority normalises PI hex case-insensitively and strips 0x prefix`() {
        val logo1 = StationLogo(pi = "0xa3e0", logoUrl = "x.png")
        val logo2 = StationLogo(pi = "0XA3E0", logoUrl = "x.png")
        val logo3 = StationLogo(pi = "a3e0", logoUrl = "x.png")
        assertEquals(3, logo1.matchPriority(null, 0xA3E0, null))
        assertEquals(3, logo2.matchPriority(null, 0xA3E0, null))
        assertEquals(3, logo3.matchPriority(null, 0xA3E0, null))
    }

    @Test
    fun `matchPriority returns 2 for PS match when PI does not match`() {
        val logo = StationLogo(ps = "FM4", pi = "FFFF", logoUrl = "x.png")
        // Station PI doesn't match the template, but PS does.
        assertEquals(2, logo.matchPriority(stationPs = "FM4", stationPi = 0x1234, stationFrequency = null))
    }

    @Test
    fun `matchPriority is case-insensitive on PS and trims whitespace`() {
        val logo = StationLogo(ps = "FM4", logoUrl = "x.png")
        assertEquals(2, logo.matchPriority(stationPs = "  fm4  ", stationPi = null, stationFrequency = null))
    }

    @Test
    fun `matchPriority returns 1 for any matching frequency in list`() {
        val logo = StationLogo(frequencies = listOf(88.8f, 90.4f, 99.5f), logoUrl = "x.png")
        assertEquals(1, logo.matchPriority(null, null, 90.4f))
        assertEquals(1, logo.matchPriority(null, null, 99.5f))
    }

    @Test
    fun `matchPriority frequency match tolerates 005 MHz drift`() {
        val logo = StationLogo(frequencies = listOf(99.5f), logoUrl = "x.png")
        assertEquals(1, logo.matchPriority(null, null, 99.51f))
        assertEquals(1, logo.matchPriority(null, null, 99.49f))
        assertEquals(0, logo.matchPriority(null, null, 99.6f)) // 0.1 off → no match
    }

    @Test
    fun `matchPriority returns 0 when no criteria match`() {
        val logo = StationLogo(ps = "FM4", logoUrl = "x.png")
        assertEquals(0, logo.matchPriority(stationPs = "OE3", stationPi = null, stationFrequency = null))
    }

    @Test
    fun `matchPriority returns 0 when template specifies criteria but station is null`() {
        val logo = StationLogo(ps = "FM4", pi = "A3E0", logoUrl = "x.png")
        assertEquals(0, logo.matchPriority(stationPs = null, stationPi = null, stationFrequency = null))
    }

    @Test
    fun `matchPriority PI wins over PS when both could match`() {
        // Station has both — template offers both — PI must win.
        val logo = StationLogo(ps = "FM4", pi = "A3E0", logoUrl = "x.png")
        assertEquals(3, logo.matchPriority(stationPs = "FM4", stationPi = 0xA3E0, stationFrequency = null))
    }

    @Test
    fun `matchPriority PS wins over Frequency when both could match`() {
        val logo = StationLogo(ps = "FM4", frequencies = listOf(99.5f), logoUrl = "x.png")
        assertEquals(2, logo.matchPriority("FM4", null, 99.5f))
    }

    // ============ StationLogo JSON round-trip ============

    @Test
    fun `StationLogo round-trip preserves all fields`() {
        val original = StationLogo(
            ps = "FM4",
            pi = "A3E0",
            frequencies = listOf(88.8f, 90.4f),
            logoUrl = "https://cdn/logo.png",
            localPath = "/data/files/logos/x.png",
        )
        val restored = StationLogo.fromJson(original.toJson())
        assertEquals("FM4", restored.ps)
        assertEquals("A3E0", restored.pi)
        assertEquals(listOf(88.8f, 90.4f), restored.frequencies)
        assertEquals("https://cdn/logo.png", restored.logoUrl)
        assertEquals("/data/files/logos/x.png", restored.localPath)
    }

    @Test
    fun `StationLogo round-trip drops null optional fields cleanly`() {
        val original = StationLogo(logoUrl = "https://cdn/logo.png")
        val restored = StationLogo.fromJson(original.toJson())
        assertNull(restored.ps)
        assertNull(restored.pi)
        assertNull(restored.frequencies)
        assertNull(restored.localPath)
        assertEquals("https://cdn/logo.png", restored.logoUrl)
    }

    @Test
    fun `StationLogo fromJson treats blank ps as missing`() {
        // Pre-fix the optString chain emitted "" for missing fields, which
        // must be normalised back to null so PS-matching doesn't false-hit.
        val json = JSONObject("""
            {"ps":"","pi":"","logoUrl":"https://cdn/logo.png","localPath":""}
        """.trimIndent())
        val logo = StationLogo.fromJson(json)
        assertNull(logo.ps)
        assertNull(logo.pi)
        assertNull(logo.localPath)
    }

    @Test
    fun `StationLogo fromJson reads legacy single-frequency field for backward compat`() {
        // Older templates stored `frequency` (singular). New writes use
        // `frequencies` (array). The reader must accept either.
        val json = JSONObject("""
            {"frequency":88.8,"logoUrl":"https://cdn/logo.png"}
        """.trimIndent())
        val logo = StationLogo.fromJson(json)
        assertEquals(listOf(88.8f), logo.frequencies)
    }

    @Test
    fun `StationLogo fromJson new format frequencies array wins over legacy frequency`() {
        // Spec is "if `frequencies` exists, use it" — legacy field is then ignored.
        val json = JSONObject("""
            {"frequencies":[100.0, 101.0],"frequency":88.8,"logoUrl":"https://cdn/logo.png"}
        """.trimIndent())
        val logo = StationLogo.fromJson(json)
        assertEquals(listOf(100.0f, 101.0f), logo.frequencies)
    }

    // ============ RadioLogoTemplate JSON round-trip ============

    @Test
    fun `RadioLogoTemplate round-trip preserves nested stations`() {
        val original = RadioLogoTemplate(
            name = "Austria-FM",
            area = 2,
            stations = listOf(
                StationLogo(ps = "FM4", logoUrl = "https://cdn/fm4.png"),
                StationLogo(pi = "A3E0", frequencies = listOf(99.5f), logoUrl = "https://cdn/oe3.png"),
            ),
        )
        val restored = RadioLogoTemplate.fromJson(original.toJson())
        assertEquals("Austria-FM", restored.name)
        assertEquals(2, restored.area)
        assertEquals(1, restored.version)
        assertEquals(2, restored.stations.size)
        assertEquals("FM4", restored.stations[0].ps)
        assertEquals("A3E0", restored.stations[1].pi)
        assertEquals(listOf(99.5f), restored.stations[1].frequencies)
    }

    @Test
    fun `RadioLogoTemplate fromJson defaults area to 2 (Europe) when missing`() {
        val json = JSONObject("""{"name":"X","stations":[]}""")
        val template = RadioLogoTemplate.fromJson(json)
        assertEquals(2, template.area)
    }

    @Test
    fun `RadioLogoTemplate fromJson defaults version to 1 when missing`() {
        val json = JSONObject("""{"name":"X","area":0,"stations":[]}""")
        val template = RadioLogoTemplate.fromJson(json)
        assertEquals(1, template.version)
    }

    @Test
    fun `RadioLogoTemplate fromJsonString parses string input`() {
        val template = RadioLogoTemplate.fromJsonString(
            """{"name":"Test","area":3,"stations":[]}"""
        )
        assertEquals("Test", template.name)
        assertEquals(3, template.area)
        assertTrue(template.stations.isEmpty())
    }

    @Test
    fun `RadioLogoTemplate empty station list round-trips cleanly`() {
        val original = RadioLogoTemplate(name = "Empty", area = 0, stations = emptyList())
        val restored = RadioLogoTemplate.fromJson(original.toJson())
        assertNotNull(restored)
        assertTrue(restored.stations.isEmpty())
    }
}
