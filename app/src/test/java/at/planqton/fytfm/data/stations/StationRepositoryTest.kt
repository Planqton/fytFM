package at.planqton.fytfm.data.stations

import android.content.Context
import at.planqton.fytfm.data.RadioStation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Merge-focused unit tests for [StationRepository]. Uses Robolectric so
 * SharedPreferences is real — makes the end-to-end save→merge→load round-trip
 * testable without mocking every storage call.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class StationRepositoryTest {

    private lateinit var context: Context
    private var overwriteFavorites = false
    private lateinit var repo: StationRepository

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        // Wipe the four prefs files + any per-tuner file so runs don't leak state.
        listOf(
            "fm_presets", "am_presets", "dab_presets", "dab_dev_presets",
            "tuner_presets_test_tuner",
        ).forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        overwriteFavorites = false
        repo = StationRepository(context) { overwriteFavorites }
    }

    @After
    fun tearDown() {
        listOf(
            "fm_presets", "am_presets", "dab_presets", "dab_dev_presets",
            "tuner_presets_test_tuner",
        ).forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    private fun fm(freq: Float, name: String? = null, isFavorite: Boolean = false) =
        RadioStation(frequency = freq, name = name, rssi = 50, isFavorite = isFavorite)

    private fun dab(serviceId: Int, name: String? = null, isFavorite: Boolean = false) =
        RadioStation(
            frequency = 0f,
            name = name,
            rssi = 0,
            isDab = true,
            serviceId = serviceId,
            isFavorite = isFavorite,
        )

    // ========== basic CRUD sanity ==========

    @Test
    fun `saveFmStations round-trips through loadFmStations`() {
        repo.saveFmStations(listOf(fm(88.8f, "FM A"), fm(99.5f, "FM B")))

        val loaded = repo.loadFmStations()
        assertEquals(2, loaded.size)
        assertEquals("FM A", loaded[0].name)
        assertEquals("FM B", loaded[1].name)
    }

    @Test
    fun `saveFmStations updates fmStations StateFlow`() {
        assertTrue(repo.fmStations.value.isEmpty())
        repo.saveFmStations(listOf(fm(88.8f, "FM A")))
        assertEquals(1, repo.fmStations.value.size)
    }

    @Test
    fun `clearFmStations emits empty list`() {
        repo.saveFmStations(listOf(fm(88.8f, "FM A")))
        repo.clearFmStations()
        assertTrue(repo.fmStations.value.isEmpty())
        assertTrue(repo.loadFmStations().isEmpty())
    }

    // ========== FM/AM merge: happy path ==========

    @Test
    fun `merge adds new scanned stations when nothing exists`() {
        val scanned = listOf(fm(88.8f, "FM A"), fm(99.5f, "FM B"))

        val merged = repo.mergeScannedStations(scanned, isAM = false)

        assertEquals(2, merged.size)
        assertEquals(88.8f, merged[0].frequency, 0.01f)
        assertEquals(99.5f, merged[1].frequency, 0.01f)
    }

    @Test
    fun `merge replaces non-favorite existing stations with scanned data`() {
        repo.saveFmStations(listOf(fm(88.8f, "Old Name", isFavorite = false)))

        val merged = repo.mergeScannedStations(listOf(fm(88.8f, "New Name")), isAM = false)

        assertEquals(1, merged.size)
        assertEquals("New Name", merged[0].name)
        assertFalse(merged[0].isFavorite)
    }

    @Test
    fun `merge keeps favorites that were not in the scan`() {
        repo.saveFmStations(listOf(
            fm(88.8f, "Keep", isFavorite = true),
            fm(99.5f, "Drop", isFavorite = false),
        ))

        val merged = repo.mergeScannedStations(listOf(fm(101.0f, "New")), isAM = false)

        val freqs = merged.map { it.frequency }
        assertTrue(88.8f in freqs)  // favorite preserved
        assertTrue(101.0f in freqs) // new one added
        assertFalse(99.5f in freqs) // non-favorite dropped
    }

    // ========== FM/AM merge: overwrite-favorites flag ==========

    @Test
    fun `merge with overwriteFavorites=false keeps favorite data over scanned`() {
        overwriteFavorites = false
        repo.saveFmStations(listOf(fm(88.8f, "Favorite Name", isFavorite = true)))

        val merged = repo.mergeScannedStations(
            listOf(fm(88.8f, "Scanned Name")),
            isAM = false,
        )

        val result = merged.first { it.frequency == 88.8f }
        assertEquals("Favorite Name", result.name)
        assertTrue(result.isFavorite)
    }

    @Test
    fun `merge with overwriteFavorites=true replaces favorite with scanned data but keeps isFavorite`() {
        overwriteFavorites = true
        repo.saveFmStations(listOf(fm(88.8f, "Favorite Name", isFavorite = true)))

        val merged = repo.mergeScannedStations(
            listOf(fm(88.8f, "Scanned Name")),
            isAM = false,
        )

        val result = merged.first { it.frequency == 88.8f }
        assertEquals("Scanned Name", result.name)
        assertTrue("isFavorite must survive overwrite", result.isFavorite)
    }

    // ========== FM/AM merge: updateNameIfBlank rule ==========

    @Test
    fun `merge updates blank favorite name when scan has a name (FM-AM rule)`() {
        overwriteFavorites = false
        repo.saveFmStations(listOf(fm(88.8f, name = null, isFavorite = true)))

        val merged = repo.mergeScannedStations(
            listOf(fm(88.8f, "Discovered")),
            isAM = false,
        )

        val result = merged.first { it.frequency == 88.8f }
        assertEquals("Discovered", result.name)
        assertTrue(result.isFavorite)
    }

    @Test
    fun `merge keeps existing favorite name when scan name is blank`() {
        overwriteFavorites = false
        repo.saveFmStations(listOf(fm(88.8f, "Kept", isFavorite = true)))

        val merged = repo.mergeScannedStations(
            listOf(fm(88.8f, name = null)),
            isAM = false,
        )

        assertEquals("Kept", merged.first { it.frequency == 88.8f }.name)
    }

    // ========== FM/AM merge: result ordering ==========

    @Test
    fun `merge result is sorted by frequency`() {
        val merged = repo.mergeScannedStations(
            listOf(fm(108.0f, "High"), fm(88.8f, "Low"), fm(95.5f, "Mid")),
            isAM = false,
        )

        assertEquals(listOf(88.8f, 95.5f, 108.0f), merged.map { it.frequency })
    }

    // ========== DAB merge ==========

    @Test
    fun `mergeDab preserves favorite by serviceId`() {
        overwriteFavorites = false
        repo.saveDabStations(listOf(dab(serviceId = 1001, name = "Fav DAB", isFavorite = true)))

        val merged = repo.mergeDabScannedStations(
            listOf(dab(serviceId = 1001, name = "Scanned DAB")),
        )

        val result = merged.first { it.serviceId == 1001 }
        assertEquals("Fav DAB", result.name)
        assertTrue(result.isFavorite)
    }

    @Test
    fun `mergeDab does not apply updateNameIfBlank rule (FM-AM-only)`() {
        // DAB stations should keep their existing (null) name even if scanned
        // provides one — the name-propagation rule is FM/AM-specific.
        overwriteFavorites = false
        repo.saveDabStations(listOf(dab(serviceId = 1001, name = null, isFavorite = true)))

        val merged = repo.mergeDabScannedStations(
            listOf(dab(serviceId = 1001, name = "Scanned")),
        )

        assertEquals(null, merged.first { it.serviceId == 1001 }.name)
    }

    @Test
    fun `mergeDab overwriteFavorites=true replaces with scanned and keeps isFavorite`() {
        overwriteFavorites = true
        repo.saveDabStations(listOf(dab(serviceId = 1001, name = "Fav", isFavorite = true)))

        val merged = repo.mergeDabScannedStations(
            listOf(dab(serviceId = 1001, name = "Scanned")),
        )

        val result = merged.first { it.serviceId == 1001 }
        assertEquals("Scanned", result.name)
        assertTrue(result.isFavorite)
    }

    // Per-tuner merge tests deleted — the methods were removed from
    // StationRepository (no production callers).

    // ========== favourites ==========

    @Test
    fun `toggleFavorite on non-existent frequency adds it as favorite`() {
        val isFav = repo.toggleFavorite(88.8f, isAM = false)

        assertTrue(isFav)
        val stations = repo.loadFmStations()
        assertEquals(1, stations.size)
        assertTrue(stations[0].isFavorite)
        assertEquals(88.8f, stations[0].frequency, 0.01f)
    }

    @Test
    fun `toggleFavorite twice removes the favourite flag`() {
        repo.toggleFavorite(88.8f, isAM = false)
        val second = repo.toggleFavorite(88.8f, isAM = false)

        assertFalse(second)
        assertFalse(repo.loadFmStations().first().isFavorite)
    }

    @Test
    fun `toggleDabFavorite returns false for unknown serviceId`() {
        // DAB semantics: can only toggle existing DAB stations (scan discovers them).
        val result = repo.toggleDabFavorite(serviceId = 9999)
        assertFalse(result)
    }
}
