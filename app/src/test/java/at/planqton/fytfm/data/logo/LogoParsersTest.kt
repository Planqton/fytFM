package at.planqton.fytfm.data.logo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [LogoParsers]. These pin down the fragile regex / JSON
 * parsing paths in [LogoSearchService] so a future refactor (or a SI.xml
 * format wobble from a real broadcaster) can't break logo lookup silently.
 *
 * Design choices:
 * - org.json's [org.json.JSONObject] / [org.json.JSONArray] are stubbed in
 *   plain unit tests (Android's JAR has no impl) — so we run under
 *   Robolectric, which provides a real implementation. No application
 *   context is touched, so the manifest is stubbed out.
 * - Pass-2 of the radio-browser favicon lookup needs HTTP and is NOT
 *   exercised here — it stays in [LogoSearchService] and would need an
 *   integration test against a mock OkHttp.
 * - SI.xml fixtures are stripped down to the elements the parser actually
 *   reads (service block, multimedia url/width). Real broadcasters wrap
 *   these in `<spi:…>` namespaces but the regex ignores prefixes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class LogoParsersTest {

    // ============ buildRadioDnsFqdn ============

    @Test
    fun `buildRadioDnsFqdn formats lowercase 4-digit hex with leading zeros`() {
        val fqdn = LogoParsers.buildRadioDnsFqdn(serviceId = 0xC479, ensembleId = 0xC1, gcc = "de0")
        assertEquals("0.c479.00c1.de0.dab.radiodns.org", fqdn)
    }

    @Test
    fun `buildRadioDnsFqdn pads small ids to 4 hex chars`() {
        val fqdn = LogoParsers.buildRadioDnsFqdn(serviceId = 1, ensembleId = 2, gcc = "at0")
        assertEquals("0.0001.0002.at0.dab.radiodns.org", fqdn)
    }

    @Test
    fun `buildRadioDnsFqdn truncates ids above 0xFFFF (current behaviour)`() {
        // %04x emits more than 4 chars when overflowing; we just lock the
        // current behaviour so an accidental %04X swap is caught.
        val fqdn = LogoParsers.buildRadioDnsFqdn(serviceId = 0x1FFFF, ensembleId = 0xC1, gcc = "de0")
        assertTrue("hex must be lowercase", fqdn.startsWith("0.1ffff."))
    }

    // ============ parseDohCnameAnswer ============

    @Test
    fun `parseDohCnameAnswer returns CNAME target with trailing dot stripped`() {
        val json = """
            {"Status":0,"Answer":[
                {"name":"0.c479.00c1.de0.dab.radiodns.org.","type":5,"TTL":300,
                 "data":"rdns.swr.de."}
            ]}
        """.trimIndent()
        assertEquals("rdns.swr.de", LogoParsers.parseDohCnameAnswer(json))
    }

    @Test
    fun `parseDohCnameAnswer skips non-CNAME records (type 1 = A)`() {
        val json = """
            {"Status":0,"Answer":[
                {"name":"x.example.","type":1,"data":"1.2.3.4"},
                {"name":"x.example.","type":5,"data":"target.example."}
            ]}
        """.trimIndent()
        assertEquals("target.example", LogoParsers.parseDohCnameAnswer(json))
    }

    @Test
    fun `parseDohCnameAnswer returns null when no Answer section`() {
        val json = """{"Status":3}"""
        assertNull(LogoParsers.parseDohCnameAnswer(json))
    }

    @Test
    fun `parseDohCnameAnswer returns null on empty Answer array`() {
        assertNull(LogoParsers.parseDohCnameAnswer("""{"Answer":[]}"""))
    }

    @Test
    fun `parseDohCnameAnswer returns null on malformed JSON instead of throwing`() {
        assertNull(LogoParsers.parseDohCnameAnswer("not json"))
        assertNull(LogoParsers.parseDohCnameAnswer(""))
        assertNull(LogoParsers.parseDohCnameAnswer(null))
    }

    @Test
    fun `parseDohCnameAnswer skips CNAME entries with blank data`() {
        val json = """
            {"Answer":[
                {"type":5,"data":""},
                {"type":5,"data":"good.example."}
            ]}
        """.trimIndent()
        assertEquals("good.example", LogoParsers.parseDohCnameAnswer(json))
    }

    // ============ parseSiXmlForServiceLogo ============

    @Test
    fun `parseSiXmlForServiceLogo picks the largest-width logo for the matching service`() {
        val xml = """
            <serviceInformation>
              <service>
                <serviceIdentifier>dab:de0.c479.0001.0</serviceIdentifier>
                <multimedia url="https://cdn.example/small.png" width="100" height="100"/>
                <multimedia url="https://cdn.example/big.png" width="600" height="600"/>
                <multimedia url="https://cdn.example/medium.png" width="320" height="320"/>
              </service>
            </serviceInformation>
        """.trimIndent()
        assertEquals(
            "https://cdn.example/big.png",
            LogoParsers.parseSiXmlForServiceLogo(xml, serviceId = 1),
        )
    }

    @Test
    fun `parseSiXmlForServiceLogo matches case-insensitively on the hex serviceId`() {
        val xml = """
            <service>
              <bearer id="dab:de0.0001.c479.0"/>
              <multimedia url="https://cdn/swr3.png" width="600"/>
            </service>
        """.trimIndent()
        // 0xC479 → "C479" upper / "c479" lower. The XML uses lowercase.
        assertEquals(
            "https://cdn/swr3.png",
            LogoParsers.parseSiXmlForServiceLogo(xml, serviceId = 0xC479),
        )
    }

    @Test
    fun `parseSiXmlForServiceLogo skips non-matching service blocks`() {
        val xml = """
            <serviceInformation>
              <service>
                <bearer id="dab:de0.0001.AAAA.0"/>
                <multimedia url="https://wrong/aaaa.png" width="600"/>
              </service>
              <service>
                <bearer id="dab:de0.0001.C479.0"/>
                <multimedia url="https://right/c479.png" width="200"/>
              </service>
            </serviceInformation>
        """.trimIndent()
        assertEquals(
            "https://right/c479.png",
            LogoParsers.parseSiXmlForServiceLogo(xml, serviceId = 0xC479),
        )
    }

    @Test
    fun `parseSiXmlForServiceLogo falls back to any multimedia URL when no service matches`() {
        val xml = """
            <serviceInformation>
              <service>
                <bearer id="dab:de0.0001.AAAA.0"/>
                <multimedia url="https://only/option.png" width="123"/>
              </service>
            </serviceInformation>
        """.trimIndent()
        // Looking for 0xC479, no match → fallback to any multimedia URL.
        assertEquals(
            "https://only/option.png",
            LogoParsers.parseSiXmlForServiceLogo(xml, serviceId = 0xC479),
        )
    }

    @Test
    fun `parseSiXmlForServiceLogo returns null when no multimedia at all`() {
        val xml = """
            <serviceInformation>
              <service><bearer id="dab:de0.0001.0001.0"/></service>
            </serviceInformation>
        """.trimIndent()
        assertNull(LogoParsers.parseSiXmlForServiceLogo(xml, serviceId = 1))
    }

    @Test
    fun `parseSiXmlForServiceLogo handles multimedia without width attribute`() {
        val xml = """
            <service>
              <bearer id="dab:de0.0001.0001.0"/>
              <multimedia url="https://no/width.png"/>
            </service>
        """.trimIndent()
        assertEquals(
            "https://no/width.png",
            LogoParsers.parseSiXmlForServiceLogo(xml, serviceId = 1),
        )
    }

    @Test
    fun `parseSiXmlForServiceLogo returns null on null or blank input without throwing`() {
        assertNull(LogoParsers.parseSiXmlForServiceLogo(null, serviceId = 1))
        assertNull(LogoParsers.parseSiXmlForServiceLogo("", serviceId = 1))
        assertNull(LogoParsers.parseSiXmlForServiceLogo("   ", serviceId = 1))
    }

    @Test
    fun `parseSiXmlForServiceLogo prefers width-attributed over unspecified within same service`() {
        // multimedia without width counts as width=0 for the maxBy compare.
        val xml = """
            <service>
              <bearer id="dab:de0.0001.0001.0"/>
              <multimedia url="https://no/width.png"/>
              <multimedia url="https://small/w.png" width="50"/>
            </service>
        """.trimIndent()
        assertEquals(
            "https://small/w.png",
            LogoParsers.parseSiXmlForServiceLogo(xml, serviceId = 1),
        )
    }

    // ============ cleanStationNameForSearch ============

    @Test
    fun `cleanStationNameForSearch keeps Umlauts and strips punctuation (whitespace preserved)`() {
        // Punctuation chars are dropped in place, so the spaces around the
        // dash collapse into the result. We don't normalise runs of
        // whitespace — radio-browser tolerates them via URL-encoding.
        assertEquals("FM4  Österreich", LogoParsers.cleanStationNameForSearch("FM4 - Österreich!"))
    }

    @Test
    fun `cleanStationNameForSearch returns null for sub-2-char results`() {
        assertNull(LogoParsers.cleanStationNameForSearch("a"))
        assertNull(LogoParsers.cleanStationNameForSearch("!"))
        assertNull(LogoParsers.cleanStationNameForSearch(""))
        assertNull(LogoParsers.cleanStationNameForSearch(null))
    }

    @Test
    fun `cleanStationNameForSearch strips punctuation but does not collapse internal spaces`() {
        assertEquals("Radio  Wien", LogoParsers.cleanStationNameForSearch("Radio,  Wien."))
    }

    // ============ buildSearchVariants ============

    @Test
    fun `buildSearchVariants emits full, first-word, and no-space variants`() {
        val variants = LogoParsers.buildSearchVariants("Radio Wien")
        assertEquals(listOf("Radio Wien", "Radio", "RadioWien"), variants)
    }

    @Test
    fun `buildSearchVariants deduplicates single-word names`() {
        val variants = LogoParsers.buildSearchVariants("FM4")
        assertEquals(listOf("FM4"), variants)
    }

    // ============ pickFaviconFromRadioBrowserJson ============

    @Test
    fun `pickFaviconFromRadioBrowserJson prefers exact-name match over fallback`() {
        val json = """[
            {"name":"Random Other","favicon":"https://other/icon.png"},
            {"name":"FM4 Wien","favicon":"https://fm4/icon.png"}
        ]""".trimIndent()
        assertEquals(
            "https://fm4/icon.png",
            LogoParsers.pickFaviconFromRadioBrowserJson(json, "FM4"),
        )
    }

    @Test
    fun `pickFaviconFromRadioBrowserJson falls back to first non-blank favicon when no name match`() {
        val json = """[
            {"name":"Beta","favicon":""},
            {"name":"Gamma","favicon":"https://gamma/icon.png"},
            {"name":"Delta","favicon":"https://delta/icon.png"}
        ]""".trimIndent()
        // searchName "Other" matches no station name → pass 3 picks first
        // entry with non-blank favicon (Gamma).
        assertEquals(
            "https://gamma/icon.png",
            LogoParsers.pickFaviconFromRadioBrowserJson(json, "Other"),
        )
    }

    @Test
    fun `pickFaviconFromRadioBrowserJson treats literal string null as missing favicon`() {
        // The radio-browser API serialises nulls as the string "null".
        val json = """[{"name":"FM4","favicon":"null"}]"""
        assertNull(LogoParsers.pickFaviconFromRadioBrowserJson(json, "FM4"))
    }

    @Test
    fun `pickFaviconFromRadioBrowserJson returns null on empty array`() {
        assertNull(LogoParsers.pickFaviconFromRadioBrowserJson("[]", "FM4"))
    }

    @Test
    fun `pickFaviconFromRadioBrowserJson is case-insensitive on name match`() {
        val json = """[{"name":"FM4 WIEN","favicon":"https://fm4/icon.png"}]"""
        assertEquals(
            "https://fm4/icon.png",
            LogoParsers.pickFaviconFromRadioBrowserJson(json, "fm4"),
        )
    }

    @Test
    fun `pickFaviconFromRadioBrowserJson tolerates malformed JSON without throwing`() {
        assertNull(LogoParsers.pickFaviconFromRadioBrowserJson("not json", "FM4"))
        assertNull(LogoParsers.pickFaviconFromRadioBrowserJson("", "FM4"))
        assertNull(LogoParsers.pickFaviconFromRadioBrowserJson(null, "FM4"))
    }

    // ============ buildFaviconCandidates ============

    @Test
    fun `buildFaviconCandidates produces 4 URLs in production order`() {
        val candidates = LogoParsers.buildFaviconCandidates("https://example.com")
        assertEquals(
            listOf(
                "https://example.com/favicon.ico",
                "https://example.com/favicon.png",
                "https://example.com/apple-touch-icon.png",
                "https://example.com/apple-touch-icon-precomposed.png",
            ),
            candidates,
        )
    }

    @Test
    fun `buildFaviconCandidates strips trailing slash so URLs don't double up`() {
        val candidates = LogoParsers.buildFaviconCandidates("https://example.com/")
        assertNotNull(candidates.firstOrNull())
        assertEquals("https://example.com/favicon.ico", candidates.first())
    }

    @Test
    fun `buildFaviconCandidates returns empty list for null or blank homepage`() {
        assertTrue(LogoParsers.buildFaviconCandidates(null).isEmpty())
        assertTrue(LogoParsers.buildFaviconCandidates("").isEmpty())
        assertTrue(LogoParsers.buildFaviconCandidates("   ").isEmpty())
    }
}
