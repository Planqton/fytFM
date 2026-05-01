package at.planqton.fytfm.deezer

import org.json.JSONObject
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
 * Tests for [DeezerResponseParser]. Inputs are real Deezer `/search?q=…`
 * response shapes (trimmed to the fields the parser reads).
 *
 * Robolectric is required because the production code uses Android's
 * `org.json.JSONObject` which is stubbed in plain unit tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DeezerResponseParserTest {

    // ===== pickBestCover =====

    @Test
    fun `pickBestCover prefers xl over big`() {
        assertEquals("xl", DeezerResponseParser.pickBestCover("s", "m", "big", "xl"))
    }

    @Test
    fun `pickBestCover prefers big over medium when xl missing`() {
        assertEquals("big", DeezerResponseParser.pickBestCover("s", "m", "big", null))
    }

    @Test
    fun `pickBestCover prefers medium over small when xl and big missing`() {
        assertEquals("m", DeezerResponseParser.pickBestCover("s", "m", null, null))
    }

    @Test
    fun `pickBestCover falls back to small when only small set`() {
        assertEquals("s", DeezerResponseParser.pickBestCover("s", null, null, null))
    }

    @Test
    fun `pickBestCover returns null when all sizes are null or blank`() {
        assertNull(DeezerResponseParser.pickBestCover(null, null, null, null))
        assertNull(DeezerResponseParser.pickBestCover("", "  ", null, ""))
    }

    @Test
    fun `pickBestCover skips blank xl and falls back to big`() {
        assertEquals("big", DeezerResponseParser.pickBestCover("s", "m", "big", ""))
    }

    // ===== normaliseRankToPopularity =====

    @Test
    fun `normaliseRankToPopularity divides rank by 10000`() {
        assertEquals(50, DeezerResponseParser.normaliseRankToPopularity(500_000))
        assertEquals(0, DeezerResponseParser.normaliseRankToPopularity(0))
        assertEquals(10, DeezerResponseParser.normaliseRankToPopularity(105_000))
    }

    @Test
    fun `normaliseRankToPopularity saturates at 100 for ranks above 1M`() {
        assertEquals(100, DeezerResponseParser.normaliseRankToPopularity(1_000_000))
        assertEquals(100, DeezerResponseParser.normaliseRankToPopularity(5_000_000))
        assertEquals(100, DeezerResponseParser.normaliseRankToPopularity(Int.MAX_VALUE))
    }

    @Test
    fun `normaliseRankToPopularity clamps negative ranks to 0`() {
        assertEquals(0, DeezerResponseParser.normaliseRankToPopularity(-1))
        assertEquals(0, DeezerResponseParser.normaliseRankToPopularity(Int.MIN_VALUE))
    }

    // ===== parseTrackItem =====

    private fun trackJson(
        title: String? = "Bohemian Rhapsody",
        id: Long? = 3135556L,
        duration: Int? = 354,
        rank: Int? = 850000,
        explicit: Boolean? = false,
        preview: String? = "https://cdn-preview.deezer.com/preview.mp3",
        link: String? = "https://www.deezer.com/track/3135556",
        artistName: String? = "Queen",
        artistId: Long? = 412L,
        albumTitle: String? = "A Night at the Opera",
        albumId: Long? = 7547,
        coverSmall: String? = "https://e-cdns-images.dzcdn.net/56x56.jpg",
        coverMedium: String? = "https://e-cdns-images.dzcdn.net/250x250.jpg",
        coverBig: String? = "https://e-cdns-images.dzcdn.net/500x500.jpg",
        coverXl: String? = "https://e-cdns-images.dzcdn.net/1000x1000.jpg",
    ): JSONObject {
        val artist = JSONObject().apply {
            artistName?.let { put("name", it) }
            artistId?.let { put("id", it) }
        }
        val album = JSONObject().apply {
            albumTitle?.let { put("title", it) }
            albumId?.let { put("id", it) }
            coverSmall?.let { put("cover_small", it) }
            coverMedium?.let { put("cover_medium", it) }
            coverBig?.let { put("cover_big", it) }
            coverXl?.let { put("cover_xl", it) }
        }
        return JSONObject().apply {
            title?.let { put("title", it) }
            id?.let { put("id", it) }
            duration?.let { put("duration", it) }
            rank?.let { put("rank", it) }
            explicit?.let { put("explicit_lyrics", it) }
            preview?.let { put("preview", it) }
            link?.let { put("link", it) }
            put("artist", artist)
            put("album", album)
        }
    }

    @Test
    fun `parseTrackItem extracts a fully-populated track`() {
        val info = DeezerResponseParser.parseTrackItem(trackJson())
        assertNotNull(info)
        info!!
        assertEquals("Queen", info.artist)
        assertEquals("Bohemian Rhapsody", info.title)
        assertEquals("3135556", info.trackId)
        assertEquals(354_000L, info.durationMs)
        assertEquals(85, info.popularity) // 850000 / 10000
        assertFalse(info.explicit)
        assertEquals("https://cdn-preview.deezer.com/preview.mp3", info.previewUrl)
        assertEquals("https://www.deezer.com/track/3135556", info.deezerUrl)
        assertEquals("A Night at the Opera", info.album)
        assertEquals("7547", info.albumId)
        // Cover priority: xl wins.
        assertEquals("https://e-cdns-images.dzcdn.net/1000x1000.jpg", info.coverUrl)
        assertEquals("https://e-cdns-images.dzcdn.net/250x250.jpg", info.coverUrlMedium)
        assertEquals(listOf("Queen"), info.allArtists)
        assertEquals(listOf("412"), info.allArtistIds)
    }

    @Test
    fun `parseTrackItem returns null when title field is missing`() {
        // title is REQUIRED via getString — anything else missing falls
        // through to defaults. This pins the contract.
        assertNull(DeezerResponseParser.parseTrackItem(trackJson(title = null)))
    }

    @Test
    fun `parseTrackItem defaults missing artist object to Unknown Artist`() {
        val obj = trackJson(artistName = null, artistId = null)
        // Strip the artist object entirely.
        obj.remove("artist")
        val info = DeezerResponseParser.parseTrackItem(obj)
        assertNotNull(info)
        assertEquals("Unknown Artist", info!!.artist)
        assertTrue("no artistId when artist object missing", info.allArtistIds.isEmpty())
    }

    @Test
    fun `parseTrackItem treats literal-string null preview as missing`() {
        // The Deezer API serialises null previews as the literal "null"
        // string in some endpoints — we must NOT pass that through.
        val info = DeezerResponseParser.parseTrackItem(trackJson(preview = "null"))
        assertNotNull(info)
        assertNull(info!!.previewUrl)
    }

    @Test
    fun `parseTrackItem treats blank preview as missing`() {
        val info = DeezerResponseParser.parseTrackItem(trackJson(preview = ""))
        assertNotNull(info)
        assertNull(info!!.previewUrl)
    }

    @Test
    fun `parseTrackItem keeps explicit=true flag intact`() {
        val info = DeezerResponseParser.parseTrackItem(trackJson(explicit = true))
        assertNotNull(info)
        assertTrue(info!!.explicit)
    }

    @Test
    fun `parseTrackItem converts duration seconds to millis`() {
        val info = DeezerResponseParser.parseTrackItem(trackJson(duration = 180))
        assertEquals(180_000L, info!!.durationMs)
    }

    @Test
    fun `parseTrackItem defaults rank to 0 popularity when missing`() {
        val info = DeezerResponseParser.parseTrackItem(trackJson(rank = 0))
        assertEquals(0, info!!.popularity)
    }

    @Test
    fun `parseTrackItem skips blank cover URLs in priority chain`() {
        // xl present but blank → fall back to big.
        val info = DeezerResponseParser.parseTrackItem(trackJson(coverXl = ""))
        assertEquals("https://e-cdns-images.dzcdn.net/500x500.jpg", info!!.coverUrl)
    }

    @Test
    fun `parseTrackItem handles minimal track without album covers`() {
        val obj = trackJson(
            coverSmall = null, coverMedium = null, coverBig = null, coverXl = null,
        )
        val info = DeezerResponseParser.parseTrackItem(obj)
        assertNotNull(info)
        assertNull(info!!.coverUrl)
        assertNull(info.coverUrlSmall)
        assertNull(info.coverUrlMedium)
    }

    @Test
    fun `parseTrackItem returns string trackId even when id is 0`() {
        val info = DeezerResponseParser.parseTrackItem(trackJson(id = 0))
        // Defaulted to 0 → "0" string. This is by design — see DeezerCache
        // which stores it as a string key.
        assertEquals("0", info!!.trackId)
    }
}
