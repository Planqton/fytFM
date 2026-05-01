package at.planqton.fytfm.deezer

import at.planqton.fytfm.data.rdslog.RtCorrectionDao
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for RtCombiner
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RtCombinerTest {

    private lateinit var mockDeezerClient: DeezerClient
    private lateinit var mockDeezerCache: DeezerCache
    private lateinit var rtCombiner: RtCombiner

    @Before
    fun setup() {
        mockDeezerClient = mockk(relaxed = true)
        mockDeezerCache = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    /** Helper — build a RtCombiner with the standard mocks plus optional extras. */
    private fun build(
        correctionDao: RtCorrectionDao? = null,
        isNetworkAvailable: (() -> Boolean)? = null,
    ): RtCombiner = RtCombiner(
        deezerClient = mockDeezerClient,
        deezerCache = mockDeezerCache,
        correctionDao = correctionDao,
        isNetworkAvailable = isNetworkAvailable,
    )

    @Test
    fun `RtCombiner returns null for blank RT`() = runTest {
        rtCombiner = RtCombiner(mockDeezerClient, mockDeezerCache)

        val result = rtCombiner.processRt(1234, "   ")

        assertNull(result)
    }

    @Test
    fun `RtCombiner caches result for same RT`() = runTest {
        val trackInfo = TrackInfo(
            artist = "TestArtist",
            title = "TestTitle",
            trackId = "123",
            album = "TestAlbum",
            coverUrl = "http://test.com/cover.jpg",
            popularity = 80
        )

        coEvery { mockDeezerClient.searchTrack(any()) } returns trackInfo
        every { mockDeezerCache.searchLocal(any()) } returns null
        every { mockDeezerCache.searchLocalByParts(any(), any()) } returns null

        rtCombiner = RtCombiner(mockDeezerClient, mockDeezerCache)

        // First call
        val result1 = rtCombiner.processRt(1234, "TestArtist - TestTitle")

        // Second call with same RT should return cached
        val result2 = rtCombiner.processRt(1234, "TestArtist - TestTitle")

        assertEquals(result1, result2)
    }

    @Test
    fun `RtCombiner searches cache first`() = runTest {
        val cachedTrack = TrackInfo(
            artist = "CachedArtist",
            title = "CachedTitle",
            trackId = "456",
            album = "CachedAlbum",
            coverUrl = "http://test.com/cached.jpg",
            popularity = 90
        )

        every { mockDeezerCache.searchLocal(any()) } returns cachedTrack
        every { mockDeezerCache.searchLocalByParts(any(), any()) } returns cachedTrack

        rtCombiner = RtCombiner(mockDeezerClient, mockDeezerCache)

        val result = rtCombiner.processRt(1234, "CachedArtist - CachedTitle")

        assertEquals("CachedArtist - CachedTitle", result)
        // Verify Deezer API was NOT called since cache hit
        coVerify(exactly = 0) { mockDeezerClient.searchTrack(any()) }
    }

    @Test
    fun `RtCombiner clears all buffers on clearAll`() = runTest {
        rtCombiner = RtCombiner(mockDeezerClient, mockDeezerCache)

        rtCombiner.clearAll()

        assertNull(rtCombiner.getLastTrackInfo(1234))
        assertNull(rtCombiner.getCurrentRt())
        assertNull(rtCombiner.getCurrentTrackInfo())
    }

    @Test
    fun `RtCombiner handles offline mode with cache only`() = runTest {
        val cachedTrack = TrackInfo(
            artist = "OfflineArtist",
            title = "OfflineTitle",
            trackId = "111",
            album = "OfflineAlbum",
            coverUrl = null,
            popularity = 50
        )

        // searchInCache calls searchLocalByParts first for "Artist - Title" format
        every { mockDeezerCache.searchLocalByParts("OfflineArtist", "OfflineTitle") } returns cachedTrack
        every { mockDeezerCache.searchLocal(any()) } returns cachedTrack

        // No Deezer client = offline mode
        rtCombiner = RtCombiner(null, mockDeezerCache)

        val result = rtCombiner.processRt(1234, "OfflineArtist - OfflineTitle")

        assertEquals("OfflineArtist - OfflineTitle", result)
    }

    @Test
    fun `RtCombiner returns raw RT when offline with no cache`() = runTest {
        every { mockDeezerCache.searchLocal(any()) } returns null
        every { mockDeezerCache.searchLocalByParts(any(), any()) } returns null

        // No Deezer client = offline mode
        rtCombiner = RtCombiner(null, mockDeezerCache)

        val result = rtCombiner.processRt(1234, "Some Radio Text")

        assertEquals("Some Radio Text", result)
    }

    @Test
    fun `getLastTrackInfo returns null for unknown station`() {
        rtCombiner = RtCombiner(mockDeezerClient, mockDeezerCache)

        assertNull(rtCombiner.getLastTrackInfo(9999))
    }

    @Test
    fun `destroy cleans up all resources`() {
        rtCombiner = RtCombiner(mockDeezerClient, mockDeezerCache)

        rtCombiner.destroy()

        assertNull(rtCombiner.getCurrentRt())
        assertNull(rtCombiner.getCurrentTrackInfo())
    }

    // ============ Buffer-combination flow (Kronehit-style) ============
    // Stations like Kronehit send "Artist" then "Title" as TWO separate
    // short RT messages. RtCombiner buffers them and tries (i, j) pairings
    // through validateWithDeezer until something matches.

    @Test
    fun `buffer-combine - first short RT alone yields no result`() = runTest {
        every { mockDeezerCache.searchLocal(any()) } returns null
        every { mockDeezerCache.searchLocalByParts(any(), any()) } returns null
        coEvery { mockDeezerClient.searchTrackByParts(any(), any()) } returns null
        coEvery { mockDeezerClient.searchTrack(any()) } returns null

        rtCombiner = build()

        // shouldBufferFirst = true (length < 25 + no separator) → goes
        // straight to buffer; only ONE entry → tryBufferCombinations skipped.
        val result = rtCombiner.processRt(1234, "Coldplay")

        assertNull("single buffered RT must not return a result yet", result)
    }

    @Test
    fun `buffer-combine - second short RT triggers searchTrackByParts permutation`() = runTest {
        val matchedTrack = TrackInfo(
            artist = "Coldplay",
            title = "Yellow",
            trackId = "deezer-001",
            popularity = 90,
        )
        every { mockDeezerCache.searchLocal(any()) } returns null
        every { mockDeezerCache.searchLocalByParts(any(), any()) } returns null
        // Only the (Coldplay, Yellow) pairing has a hit; reverse pairing returns null.
        coEvery { mockDeezerClient.searchTrackByParts("Coldplay", "Yellow") } returns matchedTrack
        coEvery { mockDeezerClient.searchTrackByParts("Yellow", "Coldplay") } returns null
        coEvery { mockDeezerClient.searchTrack(any()) } returns null

        rtCombiner = build()

        rtCombiner.processRt(1234, "Coldplay")
        val result = rtCombiner.processRt(1234, "Yellow")

        assertEquals("Coldplay - Yellow", result)
        coVerify(atLeast = 1) { mockDeezerClient.searchTrackByParts("Coldplay", "Yellow") }
    }

    @Test
    fun `buffer-combine - best match by popularity wins when multiple permutations succeed`() = runTest {
        // Two permutations both return tracks whose artist+title contain
        // BOTH search terms ("Queen" and "Bohemian") so isTrackRelevant
        // accepts them. The popularity tie-break then picks the high one.
        val lowPopularity = TrackInfo("Queen Bohemian Tribute", "Live", trackId = "lo", popularity = 10)
        val highPopularity = TrackInfo("Queen", "Bohemian Rhapsody", trackId = "hi", popularity = 95)

        every { mockDeezerCache.searchLocal(any()) } returns null
        every { mockDeezerCache.searchLocalByParts(any(), any()) } returns null
        coEvery { mockDeezerClient.searchTrackByParts("Queen", "Bohemian") } returns highPopularity
        coEvery { mockDeezerClient.searchTrackByParts("Bohemian", "Queen") } returns lowPopularity
        coEvery { mockDeezerClient.searchTrack(any()) } returns null

        rtCombiner = build()

        rtCombiner.processRt(2222, "Queen")
        val result = rtCombiner.processRt(2222, "Bohemian")

        // Result is formatted from the chosen track's fields (not from
        // the buffered RT strings), so the high-popularity track wins.
        assertEquals("Queen - Bohemian Rhapsody", result)
    }

    @Test
    fun `buffer-combine - both buffered RTs are recognised as part of result on next call`() = runTest {
        val matchedTrack = TrackInfo("Foo", "Bar", trackId = "x", popularity = 50)
        every { mockDeezerCache.searchLocal(any()) } returns null
        every { mockDeezerCache.searchLocalByParts(any(), any()) } returns null
        coEvery { mockDeezerClient.searchTrackByParts("Foo", "Bar") } returns matchedTrack
        coEvery { mockDeezerClient.searchTrack(any()) } returns null

        rtCombiner = build()

        rtCombiner.processRt(3333, "Foo")
        rtCombiner.processRt(3333, "Bar")

        // Re-emitting "Foo" (already part of the buffered result) must NOT
        // re-trigger any Deezer search — that's the silent-early-out branch.
        clearMocks(mockDeezerClient, answers = false)
        val replay = rtCombiner.processRt(3333, "Foo")

        assertEquals("Foo - Bar", replay)
        coVerify(exactly = 0) { mockDeezerClient.searchTrackByParts(any(), any()) }
        coVerify(exactly = 0) { mockDeezerClient.searchTrack(any()) }
    }

    // ============ Ignore list ============

    @Test
    fun `RT marked as ignored returns null without querying Deezer`() = runTest {
        val mockDao = mockk<RtCorrectionDao>(relaxed = true)
        coEvery { mockDao.isRtIgnored(any()) } returns true

        rtCombiner = build(correctionDao = mockDao)

        val result = rtCombiner.processRt(4444, "Some sponsor message - 1234567890")

        assertNull(result)
        coVerify(exactly = 0) { mockDeezerClient.searchTrack(any()) }
        coVerify(exactly = 0) { mockDeezerClient.searchTrackByParts(any(), any()) }
    }

    // ============ Network-availability gate ============

    @Test
    fun `network-unavailable callback returning false skips Deezer API entirely`() = runTest {
        every { mockDeezerCache.searchLocal(any()) } returns null
        every { mockDeezerCache.searchLocalByParts(any(), any()) } returns null

        rtCombiner = build(isNetworkAvailable = { false })

        // Has the " - " separator → goes through searchTrack → validateWithDeezer.
        val result = rtCombiner.processRt(5555, "Artist - Title")

        // Cache miss + network-down → no API call, returns last (null) result.
        assertNull(result)
        coVerify(exactly = 0) { mockDeezerClient.searchTrackByParts(any(), any()) }
        coVerify(exactly = 0) { mockDeezerClient.searchTrack(any()) }
    }

    @Test
    fun `network-available callback returning true permits the API call`() = runTest {
        val matchedTrack = TrackInfo("Artist", "Title", trackId = "t1", popularity = 60)
        every { mockDeezerCache.searchLocal(any()) } returns null
        every { mockDeezerCache.searchLocalByParts(any(), any()) } returns null
        coEvery { mockDeezerClient.searchTrackByParts("Artist", "Title") } returns matchedTrack

        rtCombiner = build(isNetworkAvailable = { true })

        val result = rtCombiner.processRt(6666, "Artist - Title")

        assertEquals("Artist - Title", result)
        coVerify(exactly = 1) { mockDeezerClient.searchTrackByParts("Artist", "Title") }
    }
}
