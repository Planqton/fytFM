package at.planqton.fytfm.data

import android.content.Context
import android.content.SharedPreferences
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for PresetRepository
 */
class PresetRepositoryTest {

    private lateinit var mockContext: Context
    private lateinit var mockFmPrefs: SharedPreferences
    private lateinit var mockAmPrefs: SharedPreferences
    private lateinit var mockDabPrefs: SharedPreferences
    private lateinit var mockSettingsPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private lateinit var repository: PresetRepository

    @Before
    fun setup() {
        mockContext = mockk(relaxed = true)
        mockFmPrefs = mockk(relaxed = true)
        mockAmPrefs = mockk(relaxed = true)
        mockDabPrefs = mockk(relaxed = true)
        mockSettingsPrefs = mockk(relaxed = true)
        mockEditor = mockk(relaxed = true)

        every { mockFmPrefs.edit() } returns mockEditor
        every { mockAmPrefs.edit() } returns mockEditor
        every { mockDabPrefs.edit() } returns mockEditor
        every { mockSettingsPrefs.edit() } returns mockEditor
        every { mockEditor.putString(any(), any()) } returns mockEditor
        every { mockEditor.putBoolean(any(), any()) } returns mockEditor
        every { mockEditor.putInt(any(), any()) } returns mockEditor
        every { mockEditor.remove(any()) } returns mockEditor
        every { mockEditor.apply() } just Runs

        every { mockContext.getSharedPreferences("fm_presets", Context.MODE_PRIVATE) } returns mockFmPrefs
        every { mockContext.getSharedPreferences("am_presets", Context.MODE_PRIVATE) } returns mockAmPrefs
        every { mockContext.getSharedPreferences("dab_presets", Context.MODE_PRIVATE) } returns mockDabPrefs
        every { mockContext.getSharedPreferences("settings", Context.MODE_PRIVATE) } returns mockSettingsPrefs

        repository = PresetRepository(mockContext)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `loadFmStations returns empty list when no stations saved`() {
        every { mockFmPrefs.getString("stations", null) } returns null

        val stations = repository.loadFmStations()

        assertTrue(stations.isEmpty())
    }

    @Test
    fun `loadFmStations parses JSON correctly`() {
        val json = """[
            {"frequency": 101.5, "name": "Test FM", "rssi": 50, "isFavorite": true, "syncName": false, "isDab": false, "serviceId": 0, "ensembleId": 0, "ensembleLabel": ""}
        ]"""
        every { mockFmPrefs.getString("stations", null) } returns json

        val stations = repository.loadFmStations()

        assertEquals(1, stations.size)
        assertEquals(101.5f, stations[0].frequency, 0.01f)
        assertEquals("Test FM", stations[0].name)
        assertTrue(stations[0].isFavorite)
    }

    @Test
    fun `loadAmStations returns empty list when no stations saved`() {
        every { mockAmPrefs.getString("stations", null) } returns null

        val stations = repository.loadAmStations()

        assertTrue(stations.isEmpty())
    }

    @Test
    fun `loadDabStations returns empty list when no stations saved`() {
        every { mockDabPrefs.getString("stations", null) } returns null

        val stations = repository.loadDabStations()

        assertTrue(stations.isEmpty())
    }

    @Test
    fun `loadDabStations parses DAB station correctly`() {
        val json = """[
            {"frequency": 178.352, "name": "Radio 1", "rssi": 0, "isFavorite": false, "syncName": false, "isDab": true, "serviceId": 12345, "ensembleId": 6789, "ensembleLabel": "DAB Wien"}
        ]"""
        every { mockDabPrefs.getString("stations", null) } returns json

        val stations = repository.loadDabStations()

        assertEquals(1, stations.size)
        assertTrue(stations[0].isDab)
        assertEquals(12345, stations[0].serviceId)
        assertEquals(6789, stations[0].ensembleId)
        assertEquals("DAB Wien", stations[0].ensembleLabel)
    }

    @Test
    fun `saveFmStations calls editor with correct JSON`() {
        val stations = listOf(
            RadioStation(
                frequency = 99.9f,
                name = "Test Station",
                rssi = 45,
                isFavorite = true
            )
        )

        repository.saveFmStations(stations)

        verify { mockEditor.putString("stations", any()) }
        verify { mockEditor.apply() }
    }

    @Test
    fun `clearDabStations removes stations key`() {
        repository.clearDabStations()

        verify { mockEditor.remove("stations") }
        verify { mockEditor.apply() }
    }

    @Test
    fun `isPowerOnStartup returns false by default`() {
        every { mockSettingsPrefs.getBoolean("power_on_startup", false) } returns false

        val result = repository.isPowerOnStartup()

        assertFalse(result)
    }

    @Test
    fun `isPowerOnStartup returns true when enabled`() {
        every { mockSettingsPrefs.getBoolean("power_on_startup", false) } returns true

        val result = repository.isPowerOnStartup()

        assertTrue(result)
    }

    @Test
    fun `isDeezerEnabledFm returns true by default`() {
        every { mockSettingsPrefs.getBoolean("deezer_enabled_fm", true) } returns true

        val result = repository.isDeezerEnabledFm()

        assertTrue(result)
    }

    @Test
    fun `isShowFavoritesOnlyFm returns false by default`() {
        every { mockSettingsPrefs.getBoolean("show_favorites_only_fm", false) } returns false

        val result = repository.isShowFavoritesOnlyFm()

        assertFalse(result)
    }

    @Test
    fun `toggleFavorite adds new favorite station`() {
        val mockTunerPrefs: SharedPreferences = mockk(relaxed = true)
        every { mockContext.getSharedPreferences("tuner_presets_test_tuner", Context.MODE_PRIVATE) } returns mockTunerPrefs
        every { mockTunerPrefs.getString("stations", null) } returns null
        every { mockTunerPrefs.edit() } returns mockEditor

        val isFavorite = repository.toggleFavoriteForTuner("test_tuner", 100.0f, false)

        assertTrue(isFavorite)
        verify { mockEditor.putString("stations", any()) }
    }

    @Test
    fun `toggleFavorite removes existing favorite`() {
        val mockTunerPrefs: SharedPreferences = mockk(relaxed = true)
        val json = """[{"frequency": 100.0, "name": "Test", "rssi": 50, "isFavorite": true, "syncName": false, "isDab": false, "serviceId": 0, "ensembleId": 0, "ensembleLabel": ""}]"""
        every { mockContext.getSharedPreferences("tuner_presets_test_tuner", Context.MODE_PRIVATE) } returns mockTunerPrefs
        every { mockTunerPrefs.getString("stations", null) } returns json
        every { mockTunerPrefs.edit() } returns mockEditor

        val isFavorite = repository.toggleFavoriteForTuner("test_tuner", 100.0f, false)

        assertFalse(isFavorite)
    }

    @Test
    fun `isFavoriteForTuner returns false for unknown frequency`() {
        val mockTunerPrefs: SharedPreferences = mockk(relaxed = true)
        every { mockContext.getSharedPreferences("tuner_presets_test_tuner", Context.MODE_PRIVATE) } returns mockTunerPrefs
        every { mockTunerPrefs.getString("stations", null) } returns null

        val result = repository.isFavoriteForTuner("test_tuner", 105.5f, false)

        assertFalse(result)
    }

    @Test
    fun `isShowDebugInfos returns false by default`() {
        every { mockSettingsPrefs.getBoolean("show_debug_infos", false) } returns false

        val result = repository.isShowDebugInfos()

        assertFalse(result)
    }

    @Test
    fun `isAutoBackgroundEnabled returns false by default`() {
        every { mockSettingsPrefs.getBoolean("auto_background_enabled", false) } returns false

        val result = repository.isAutoBackgroundEnabled()

        assertFalse(result)
    }

    @Test
    fun `getAutoBackgroundDelay returns default value of 5`() {
        every { mockSettingsPrefs.getInt("auto_background_delay", 5) } returns 5

        val result = repository.getAutoBackgroundDelay()

        assertEquals(5, result)
    }

    @Test
    fun `setAutoBackgroundDelay saves value`() {
        repository.setAutoBackgroundDelay(30)

        verify { mockEditor.putInt("auto_background_delay", 30) }
        verify { mockEditor.apply() }
    }
}
