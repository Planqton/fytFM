package at.planqton.fytfm.data.logo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for the pure-parsing helpers in [DuckDuckGoImageSearch]. DDG has
 * changed the vqd-token placement and i.js payload shape multiple times, so
 * the parsers are expected to be brittle — these tests pin every known input
 * shape so a future DDG-side change surfaces here instead of at runtime.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DuckDuckGoImageSearchTest {

    private lateinit var search: DuckDuckGoImageSearch

    @Before
    fun setup() {
        search = DuckDuckGoImageSearch()
    }

    // ========== findVqdToken ==========

    @Test
    fun `findVqdToken matches double-quoted vqd assignment`() {
        val html = """<script>window.foo = {vqd="1-1234567890"};</script>"""
        assertEquals("1-1234567890", search.findVqdToken(html))
    }

    @Test
    fun `findVqdToken matches single-quoted vqd assignment`() {
        val html = """<script>var a = vqd='3-abc-987';</script>"""
        assertEquals("3-abc-987", search.findVqdToken(html))
    }

    @Test
    fun `findVqdToken matches bare numeric vqd pattern`() {
        // Regex is ([0-9]+-[0-9]+) — captures two hyphen-separated numeric segments.
        val html = """href="/i.js?q=x&vqd=42-12345&o=json""""
        assertEquals("42-12345", search.findVqdToken(html))
    }

    @Test
    fun `findVqdToken matches JSON-embedded vqd field`() {
        val html = """{"tag":"foo","vqd":"5-xyz-777","other":"bar"}"""
        assertEquals("5-xyz-777", search.findVqdToken(html))
    }

    @Test
    fun `findVqdToken matches URL-encoded vqd in href`() {
        val html = """<a href="/l/?q=test&vqd%3D6-enc-555&kl=de">"""
        assertEquals("6-enc-555", search.findVqdToken(html))
    }

    @Test
    fun `findVqdToken returns null when no pattern matches`() {
        assertNull(search.findVqdToken("<html><body>no token here</body></html>"))
        assertNull(search.findVqdToken(""))
    }

    // ========== parseJsonResults ==========

    @Test
    fun `parseJsonResults extracts url and title with thumbnail preference`() {
        val json = """
            {
                "results": [
                    {
                        "image": "https://example.com/big.jpg",
                        "thumbnail": "https://example.com/thumb.jpg",
                        "title": "Example Logo"
                    }
                ]
            }
        """.trimIndent()

        val results = search.parseJsonResults(json, fallbackName = "fallback")

        assertEquals(1, results.size)
        assertEquals("https://example.com/thumb.jpg", results[0].url) // thumbnail preferred
        assertEquals("Example Logo", results[0].name)
    }

    @Test
    fun `parseJsonResults falls back to image when thumbnail missing`() {
        val json = """{"results":[{"image":"https://example.com/img.png","title":"t"}]}"""
        val results = search.parseJsonResults(json, fallbackName = "fallback")
        assertEquals("https://example.com/img.png", results[0].url)
    }

    @Test
    fun `parseJsonResults uses fallbackName when title missing`() {
        val json = """{"results":[{"image":"https://example.com/img.png"}]}"""
        val results = search.parseJsonResults(json, fallbackName = "my-query")
        assertEquals("my-query", results[0].name)
    }

    @Test
    fun `parseJsonResults skips entries with non-http urls`() {
        val json = """
            {
                "results": [
                    {"image":"https://ok.com/a.jpg","title":"ok"},
                    {"image":"data:image/png;base64,abc","title":"skip-data"},
                    {"image":"","title":"skip-empty"}
                ]
            }
        """.trimIndent()

        val results = search.parseJsonResults(json, fallbackName = "x")

        assertEquals(1, results.size)
        assertEquals("https://ok.com/a.jpg", results[0].url)
    }

    @Test
    fun `parseJsonResults returns empty on missing results key`() {
        val json = """{"other":"structure"}"""
        assertTrue(search.parseJsonResults(json, fallbackName = "x").isEmpty())
    }

    @Test
    fun `parseJsonResults returns empty on malformed JSON`() {
        assertTrue(search.parseJsonResults("{not valid json", fallbackName = "x").isEmpty())
        assertTrue(search.parseJsonResults("", fallbackName = "x").isEmpty())
    }

    @Test
    fun `parseJsonResults caps results at MAX_RESULTS (30)`() {
        val builder = StringBuilder("""{"results":[""")
        for (i in 0 until 50) {
            if (i > 0) builder.append(",")
            builder.append("""{"image":"https://x.com/$i.jpg","title":"n$i"}""")
        }
        builder.append("]}")

        val results = search.parseJsonResults(builder.toString(), fallbackName = "x")

        assertEquals(30, results.size)
    }

    // ========== extractImagesFromHtml ==========

    @Test
    fun `extractImagesFromHtml finds ou-pattern image urls`() {
        val html = """{"results":[{"ou":"https://example.com/logo.jpg","other":"ignored"}]}"""
        val results = search.extractImagesFromHtml(html, query = "logo")

        assertEquals(1, results.size)
        assertEquals("https://example.com/logo.jpg", results[0].url)
        assertEquals("logo", results[0].name) // uses query as name
    }

    @Test
    fun `extractImagesFromHtml unescapes forward slashes in path`() {
        // DDG's JSON-in-HTML escapes path-separators as '\/' but leaves '://'
        // intact (so the `https?://` regex anchor still matches).
        val html = """"ou":"https://cdn.example.com\/path\/img.png""""
        val results = search.extractImagesFromHtml(html, query = "x")
        assertEquals(1, results.size)
        assertEquals("https://cdn.example.com/path/img.png", results[0].url)
    }

    @Test
    fun `extractImagesFromHtml skips URLs without recognised image extension`() {
        val html = """
            "ou":"https://example.com/not-an-image.html"
            "ou":"https://example.com/data.json"
        """.trimIndent()
        assertTrue(search.extractImagesFromHtml(html, query = "x").isEmpty())
    }

    @Test
    fun `extractImagesFromHtml accepts all supported extensions`() {
        val html = """
            "ou":"https://a.com/1.jpg"
            "ou":"https://a.com/2.png"
            "ou":"https://a.com/3.jpeg"
            "ou":"https://a.com/4.webp"
        """.trimIndent()

        val urls = search.extractImagesFromHtml(html, query = "x").map { it.url }.toSet()
        assertEquals(
            setOf(
                "https://a.com/1.jpg",
                "https://a.com/2.png",
                "https://a.com/3.jpeg",
                "https://a.com/4.webp",
            ),
            urls,
        )
    }

    @Test
    fun `extractImagesFromHtml returns empty for HTML with no matches`() {
        assertTrue(search.extractImagesFromHtml("<html><body>nothing</body></html>", "x").isEmpty())
    }

    @Test
    fun `extractImagesFromHtml caps at MAX_RESULTS (30)`() {
        val html = buildString {
            for (i in 0 until 50) append(""""ou":"https://x.com/$i.jpg" """)
        }
        val results = search.extractImagesFromHtml(html, query = "x")
        assertEquals(30, results.size)
    }
}
