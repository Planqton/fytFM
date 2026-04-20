package at.planqton.fytfm.deezer

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

        assertNull(rtCombiner.getLastResult(1234))
        assertNull(rtCombiner.getLastTrackInfo(1234))
        assertNull(rtCombiner.getCurrentRt())
        assertNull(rtCombiner.getCurrentTrackInfo())
    }

    @Test
    fun `RtCombiner clears station-specific data on clearStation`() = runTest {
        val trackInfo = TrackInfo(
            artist = "Artist",
            title = "Title",
            trackId = "789",
            album = "Album",
            coverUrl = "http://test.com/cover.jpg",
            popularity = 70
        )

        coEvery { mockDeezerClient.searchTrack(any()) } returns trackInfo
        every { mockDeezerCache.searchLocal(any()) } returns null
        every { mockDeezerCache.searchLocalByParts(any(), any()) } returns null

        rtCombiner = RtCombiner(mockDeezerClient, mockDeezerCache)

        // Process some RT
        rtCombiner.processRt(1234, "Artist - Title")

        // Clear only station 1234
        rtCombiner.clearStation(1234)

        assertNull(rtCombiner.getLastResult(1234))
        assertNull(rtCombiner.getLastTrackInfo(1234))
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
    fun `getLastResult returns null for unknown station`() {
        rtCombiner = RtCombiner(mockDeezerClient, mockDeezerCache)

        assertNull(rtCombiner.getLastResult(9999))
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
}
