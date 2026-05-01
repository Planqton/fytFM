package at.planqton.fytfm.deezer

import android.content.Context
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Unit tests for [DeezerCache], running on Robolectric so the real SQLite
 * backend is exercised.
 *
 * Use null or "/"-prefixed [TrackInfo.coverUrl] values in tests to avoid the
 * network-backed cover-download path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DeezerCacheTest {

    private lateinit var context: Context
    private lateinit var cache: DeezerCache

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        cache = DeezerCache(context)
    }

    @After
    fun teardown() {
        cache.close()
        context.getDatabasePath("deezer_cache.db").delete()
        File("${context.getDatabasePath("deezer_cache.db").absolutePath}-journal").delete()
        File("${context.getDatabasePath("deezer_cache.db").absolutePath}-wal").delete()
        File("${context.getDatabasePath("deezer_cache.db").absolutePath}-shm").delete()
        File(context.filesDir, "deezer_covers").deleteRecursively()
    }

    private fun makeTrack(
        artist: String,
        title: String,
        trackId: String? = null,
        popularity: Int = 0,
    ) = TrackInfo(
        artist = artist,
        title = title,
        trackId = trackId,
        coverUrl = null, // null so cacheTrack never touches the network
        popularity = popularity,
    )

    // ========== basic persistence ==========

    @Test
    fun `fresh cache is empty`() {
        assertTrue(cache.getAllCachedTracks().isEmpty())
        assertEquals(0 to 0L, cache.getCacheStats())
    }

    @Test
    fun `cacheTrack persists track and round-trips via getAllCachedTracks`() = runTest {
        val track = makeTrack("Radiohead", "Karma Police", trackId = "rh-1")
        cache.cacheTrack(track)

        val all = cache.getAllCachedTracks()
        assertEquals(1, all.size)
        val restored = all[0]
        assertEquals("Radiohead", restored.artist)
        assertEquals("Karma Police", restored.title)
        assertEquals("rh-1", restored.trackId)
    }

    @Test
    fun `cacheTrack with same trackId replaces the existing row`() = runTest {
        cache.cacheTrack(makeTrack("Artist", "Title v1", trackId = "id-1"))
        cache.cacheTrack(makeTrack("Artist", "Title v2", trackId = "id-1"))

        val all = cache.getAllCachedTracks()
        assertEquals(1, all.size)
        assertEquals("Title v2", all[0].title)
    }

    @Test
    fun `clearCache empties the table`() = runTest {
        cache.cacheTrack(makeTrack("A", "B", trackId = "1"))
        cache.cacheTrack(makeTrack("C", "D", trackId = "2"))
        assertEquals(2, cache.getAllCachedTracks().size)

        cache.clearCache()

        assertTrue(cache.getAllCachedTracks().isEmpty())
    }

    // ========== search ==========

    @Test
    fun `searchLocal returns track when both artist and title appear in query`() = runTest {
        cache.cacheTrack(makeTrack("Radiohead", "Creep", trackId = "1"))

        val hit = cache.searchLocal("Radiohead - Creep (1992 Remaster)")

        assertNotNull(hit)
        assertEquals("Radiohead", hit!!.artist)
        assertEquals("Creep", hit.title)
    }

    @Test
    fun `searchLocal returns null when only artist matches`() = runTest {
        cache.cacheTrack(makeTrack("Radiohead", "Creep", trackId = "1"))

        assertNull(cache.searchLocal("Radiohead - Something Else"))
    }

    @Test
    fun `searchLocalByParts requires both artist and title`() = runTest {
        cache.cacheTrack(makeTrack("Radiohead", "Karma Police", trackId = "1"))

        assertNotNull(cache.searchLocalByParts("Radiohead", "Karma Police"))
        assertNull(cache.searchLocalByParts(null, "Karma Police"))
        assertNull(cache.searchLocalByParts("Radiohead", null))
    }

    // ========== export / import ==========

    @Test
    fun `exportToZip then importFromZip round-trips all tracks`() = runTest {
        cache.cacheTrack(makeTrack("Beatles", "Hey Jude", trackId = "b-1"))
        cache.cacheTrack(makeTrack("Queen", "Bohemian Rhapsody", trackId = "q-1"))

        val zipFile = File(context.cacheDir, "export-test.zip")
        assertTrue("export should succeed", cache.exportToZip(zipFile))
        assertTrue(zipFile.exists())
        assertTrue(zipFile.length() > 0)

        // Clear and verify
        cache.clearCache()
        assertTrue(cache.getAllCachedTracks().isEmpty())

        val imported = cache.importFromZip(zipFile)
        assertEquals(2, imported)

        val all = cache.getAllCachedTracks().map { it.trackId }.toSet()
        assertEquals(setOf("b-1", "q-1"), all)

        zipFile.delete()
    }

    /**
     * Regression for Bug 1.1: before the fix, a stale `-journal` / `-wal` /
     * `-shm` companion file left over from the previous DB would survive
     * `importFromZip()` and could corrupt the fresh DB once SQLite reopens.
     * The fix deletes them between close() and the file replacement; this
     * test asserts that behaviour and that post-import access still works.
     */
    @Test
    fun `importFromZip deletes stale WAL and journal companion files`() = runTest {
        // Populate and export a clean snapshot to restore from.
        cache.cacheTrack(makeTrack("Pink Floyd", "Time", trackId = "pf-1"))
        val zipFile = File(context.cacheDir, "companion-wipe.zip")
        assertTrue(cache.exportToZip(zipFile))

        // Fabricate stale companion files next to the active DB.
        val dbPath = context.getDatabasePath("deezer_cache.db").absolutePath
        val journal = File("$dbPath-journal").apply { writeText("stale journal content") }
        val wal = File("$dbPath-wal").apply { writeText("stale wal content") }
        val shm = File("$dbPath-shm").apply { writeText("stale shm content") }
        assertTrue(journal.exists() && wal.exists() && shm.exists())

        val imported = cache.importFromZip(zipFile)
        assertEquals(1, imported)

        assertFalse("journal file must be wiped", journal.exists())
        assertFalse("wal file must be wiped", wal.exists())
        assertFalse("shm file must be wiped", shm.exists())

        // Post-import access must not throw (the bug reported `database not open`).
        assertEquals(1, cache.getAllCachedTracks().size)

        zipFile.delete()
    }

}
