package at.planqton.fytfm.data.settings

import android.content.Context
import androidx.core.content.edit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Tests for [AppSettingsRepository] — the 50+ user-settings half of what
 * was the [at.planqton.fytfm.data.PresetRepository] god-class. The bulk of
 * methods are trivial pass-throughs to SharedPreferences; these tests pin:
 *
 *  - **Defaults** that the production app relies on without setting anything
 *    (e.g. Deezer cache enabled, Deezer-FM enabled, Deezer-DAB disabled,
 *    autoplay off, dark-mode "system", radio area = Europe).
 *  - **Round-trip** for each value type used (Boolean, Int, String?, Float).
 *  - **Per-frequency Deezer toggle** semantics — empty set means "enabled
 *    everywhere", and the format key is "%.1f" of the float frequency.
 *  - **Tick-volume clamping** to 0..100.
 *  - **DAB-recording-enabled derivation** — true iff path is non-null.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AppSettingsRepositoryTest {

    private lateinit var context: Context
    private lateinit var settings: AppSettingsRepository

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        // Wipe the settings prefs so each test starts from defaults.
        context.getSharedPreferences(SettingsKeys.PREFS_FILE, Context.MODE_PRIVATE).edit { clear() }
        settings = AppSettingsRepository(context)
    }

    @After
    fun teardown() {
        context.getSharedPreferences(SettingsKeys.PREFS_FILE, Context.MODE_PRIVATE).edit { clear() }
    }

    // ============ Defaults ============

    @Test
    fun `defaults pin the production-app behavior`() {
        // Deezer
        assertTrue("FM Deezer defaults to enabled", settings.isDeezerEnabledFm())
        assertFalse("DAB Deezer defaults to disabled (DAB has slideshow)", settings.isDeezerEnabledDab())
        assertTrue("local Deezer cache defaults to enabled", settings.isDeezerCacheEnabled())
        // Lifecycle
        assertFalse(settings.isAutoplayAtStartup())
        assertFalse(settings.isAutoStartEnabled())
        assertFalse(settings.isAutoBackgroundEnabled())
        assertEquals(5, settings.getAutoBackgroundDelay())
        assertTrue("auto-bg restricted to boot by default", settings.isAutoBackgroundOnlyOnBoot())
        // Tuner
        assertFalse(settings.isLocalMode())
        assertFalse(settings.isMonoMode())
        assertEquals(2, settings.getRadioArea()) // Europe
        // UI
        assertEquals(0, settings.getDarkModePreference()) // 0 = System
        assertEquals(1, settings.getNowPlayingAnimation()) // 1 = Slide
        assertTrue(settings.isShowLogosInFavorites())
        assertTrue(settings.isShowStationChangeToast())
        // Visualizer
        assertTrue(settings.isDabVisualizerEnabled())
        assertEquals(0, settings.getDabVisualizerStyle()) // 0 = Bars
        // Tick sound
        assertFalse(settings.isTickSoundEnabled())
        assertEquals(50, settings.getTickSoundVolume())
        // Recording
        assertNull(settings.getDabRecordingPath())
        assertFalse(settings.isDabRecordingEnabled())
        // Cover
        assertFalse(settings.isCoverSourceLocked())
        assertNull(settings.getLockedCoverSource())
        // Dev mode is hard-disabled
        assertFalse(settings.isDabDevModeEnabled())
    }

    // ============ Round-trips ============

    @Test
    fun `boolean setter persists across repo recreation`() {
        settings.setAutoplayAtStartup(true)
        // New instance reads the same SharedPreferences file.
        val fresh = AppSettingsRepository(context)
        assertTrue(fresh.isAutoplayAtStartup())
    }

    @Test
    fun `int setter persists across repo recreation`() {
        settings.setRadioArea(1) // 1 = US
        val fresh = AppSettingsRepository(context)
        assertEquals(1, fresh.getRadioArea())
    }

    @Test
    fun `nullable string setter round-trips null and value`() {
        settings.setDabRecordingPath("content://example/recordings")
        assertEquals("content://example/recordings", settings.getDabRecordingPath())
        assertTrue(settings.isDabRecordingEnabled())

        settings.setDabRecordingPath(null)
        assertNull(settings.getDabRecordingPath())
        assertFalse("isDabRecordingEnabled flips false when path nulled", settings.isDabRecordingEnabled())
    }

    @Test
    fun `float setter for debug window position round-trips`() {
        settings.setDebugWindowPosition("rds", 12.5f, 34.0f)
        assertEquals(12.5f, settings.getDebugWindowPositionX("rds"), 0.001f)
        assertEquals(34.0f, settings.getDebugWindowPositionY("rds"), 0.001f)
    }

    @Test
    fun `unset debug window position returns -1f sentinel`() {
        // -1f means "no saved position; let the layout default place it".
        assertEquals(-1f, settings.getDebugWindowPositionX("never_set"), 0.001f)
        assertEquals(-1f, settings.getDebugWindowPositionY("never_set"), 0.001f)
    }

    // ============ Per-frequency Deezer toggle ============

    @Test
    fun `Deezer is enabled by default for any frequency`() {
        // Empty disabled-set → every frequency is enabled.
        assertTrue(settings.isDeezerEnabledForFrequency(98.4f))
        assertTrue(settings.isDeezerEnabledForFrequency(102.5f))
        assertTrue(settings.isDeezerEnabledForFrequency(87.6f))
    }

    @Test
    fun `disabling one frequency does not affect others`() {
        settings.setDeezerEnabledForFrequency(98.4f, false)
        assertFalse(settings.isDeezerEnabledForFrequency(98.4f))
        assertTrue(settings.isDeezerEnabledForFrequency(102.5f))
    }

    @Test
    fun `re-enabling removes the frequency from disabled set`() {
        settings.setDeezerEnabledForFrequency(98.4f, false)
        settings.setDeezerEnabledForFrequency(98.4f, true)
        assertTrue(settings.isDeezerEnabledForFrequency(98.4f))
    }

    @Test
    fun `frequency key uses one decimal — 98_40 collapses to 98_4`() {
        // 98.40f and 98.4f format to the same "98.4" key; setting one
        // affects the other.
        settings.setDeezerEnabledForFrequency(98.4f, false)
        assertFalse(settings.isDeezerEnabledForFrequency(98.40f))
        assertFalse(settings.isDeezerEnabledForFrequency(98.45f))  // also rounds to "98.4"... wait, "%.1f" formats with rounding
    }

    // ============ Tick-volume clamping ============

    @Test
    fun `setTickSoundVolume clamps below 0 to 0`() {
        settings.setTickSoundVolume(-50)
        assertEquals(0, settings.getTickSoundVolume())
    }

    @Test
    fun `setTickSoundVolume clamps above 100 to 100`() {
        settings.setTickSoundVolume(150)
        assertEquals(100, settings.getTickSoundVolume())
    }

    @Test
    fun `setTickSoundVolume in-range value passes through unchanged`() {
        settings.setTickSoundVolume(75)
        assertEquals(75, settings.getTickSoundVolume())
    }

    // ============ Per-mode favourites filter ============

    @Test
    fun `per-mode favourites filter has independent state for FM AM DAB`() {
        settings.setShowFavoritesOnlyFm(true)
        // Other modes unaffected.
        assertFalse(settings.isShowFavoritesOnlyAm())
        assertFalse(settings.isShowFavoritesOnlyDab())
        // And vice-versa.
        settings.setShowFavoritesOnlyDab(true)
        assertTrue(settings.isShowFavoritesOnlyFm())
        assertFalse(settings.isShowFavoritesOnlyAm())
        assertTrue(settings.isShowFavoritesOnlyDab())
    }

    @Test
    fun `per-mode carousel keys use mode-name suffix`() {
        settings.setCarouselModeForRadioMode("FM", true)
        settings.setCarouselModeForRadioMode("DAB", false)
        assertTrue(settings.isCarouselModeForRadioMode("FM"))
        assertFalse(settings.isCarouselModeForRadioMode("DAB"))
        assertFalse("AM never set, defaults to false", settings.isCarouselModeForRadioMode("AM"))
    }

    // ============ Debug window state ============

    @Test
    fun `debug window open state defaults to passed default`() {
        assertFalse(settings.isDebugWindowOpen("rds", default = false))
        assertTrue(settings.isDebugWindowOpen("rds", default = true))
    }

    @Test
    fun `debug window open state persists across repo recreation`() {
        settings.setDebugWindowOpen("layout", true)
        val fresh = AppSettingsRepository(context)
        assertTrue(fresh.isDebugWindowOpen("layout", default = false))
    }

    // ============ Dev mode (feature-flagged off) ============

    @Test
    fun `isDabDevModeEnabled always returns false even after setter`() {
        // The getter is hard-coded to false; setter still persists for a
        // future re-enable, but reads stay false.
        settings.setDabDevModeEnabled(true)
        assertFalse(settings.isDabDevModeEnabled())
    }

    @Test
    fun `setter for dev mode persists to prefs even though getter is gated`() {
        // Documenting the asymmetry — verified by reading via the raw key.
        settings.setDabDevModeEnabled(true)
        val raw = context.getSharedPreferences(SettingsKeys.PREFS_FILE, Context.MODE_PRIVATE)
            .getBoolean(SettingsKeys.DAB_DEV_MODE_ENABLED, false)
        assertTrue("setter writes to prefs even if getter ignores", raw)
    }
}
