package at.planqton.fytfm.data.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * App-wide user/UI settings (Deezer toggles, dark mode, debug-overlay
 * positions, tick-sound volume, …). Owns the `settings` SharedPreferences
 * file and exposes ~50 read/write pairs.
 *
 * Pulled out of the old [at.planqton.fytfm.data.PresetRepository] god-class
 * (§4 of the umstrukturierung roadmap). PresetRepository remains as a thin
 * facade that forwards every settings call here, so existing call sites
 * don't need to change in this round; new code should depend on this
 * directly.
 *
 * Keys live in [SettingsKeys] so renames happen in one place.
 */
class AppSettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(SettingsKeys.PREFS_FILE, Context.MODE_PRIVATE)

    // ===== Autoplay =====

    fun isAutoplayAtStartup(): Boolean = prefs.getBoolean(SettingsKeys.AUTOPLAY_AT_STARTUP, false)

    fun setAutoplayAtStartup(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsKeys.AUTOPLAY_AT_STARTUP, enabled).apply()
    }

    // ===== Deezer =====

    fun isDeezerEnabledFm(): Boolean = prefs.getBoolean(SettingsKeys.DEEZER_ENABLED_FM, true)

    fun setDeezerEnabledFm(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsKeys.DEEZER_ENABLED_FM, enabled).apply()
    }

    fun isDeezerEnabledDab(): Boolean = prefs.getBoolean(SettingsKeys.DEEZER_ENABLED_DAB, false)

    fun setDeezerEnabledDab(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsKeys.DEEZER_ENABLED_DAB, enabled).apply()
    }

    /** Per-frequency Deezer disable list. We store frequencies where Deezer
     *  is OFF (default = enabled), so an empty/missing set means "enabled
     *  everywhere". Frequency keys use one decimal: "98.4". */
    fun isDeezerEnabledForFrequency(frequency: Float): Boolean {
        val disabled = prefs.getStringSet(SettingsKeys.DEEZER_DISABLED_FREQUENCIES, emptySet()) ?: emptySet()
        return !disabled.contains("%.1f".format(frequency))
    }

    fun setDeezerEnabledForFrequency(frequency: Float, enabled: Boolean) {
        val disabled = (prefs.getStringSet(SettingsKeys.DEEZER_DISABLED_FREQUENCIES, emptySet()) ?: emptySet())
            .toMutableSet()
        val key = "%.1f".format(frequency)
        if (enabled) disabled.remove(key) else disabled.add(key)
        prefs.edit().putStringSet(SettingsKeys.DEEZER_DISABLED_FREQUENCIES, disabled).apply()
    }

    fun isDeezerCacheEnabled(): Boolean = prefs.getBoolean(SettingsKeys.DEEZER_CACHE_ENABLED, true)

    fun setDeezerCacheEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsKeys.DEEZER_CACHE_ENABLED, enabled).apply()
    }

    // ===== Debug overlay =====

    fun isShowDebugInfos(): Boolean = prefs.getBoolean(SettingsKeys.SHOW_DEBUG_INFOS, false)

    fun setShowDebugInfos(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsKeys.SHOW_DEBUG_INFOS, enabled).apply()
    }

    fun setDebugWindowOpen(windowId: String, isOpen: Boolean) {
        prefs.edit().putBoolean(SettingsKeys.DEBUG_WINDOW_OPEN_PREFIX + windowId, isOpen).apply()
    }

    fun isDebugWindowOpen(windowId: String, default: Boolean = false): Boolean =
        prefs.getBoolean(SettingsKeys.DEBUG_WINDOW_OPEN_PREFIX + windowId, default)

    fun setDebugWindowPosition(windowId: String, x: Float, y: Float) {
        prefs.edit()
            .putFloat(SettingsKeys.DEBUG_WINDOW_X_PREFIX + windowId, x)
            .putFloat(SettingsKeys.DEBUG_WINDOW_Y_PREFIX + windowId, y)
            .apply()
    }

    fun getDebugWindowPositionX(windowId: String): Float =
        prefs.getFloat(SettingsKeys.DEBUG_WINDOW_X_PREFIX + windowId, -1f)

    fun getDebugWindowPositionY(windowId: String): Float =
        prefs.getFloat(SettingsKeys.DEBUG_WINDOW_Y_PREFIX + windowId, -1f)

    fun clearAllDebugWindowPositions() {
        val editor = prefs.edit()
        prefs.all.keys
            .filter {
                it.startsWith(SettingsKeys.DEBUG_WINDOW_X_PREFIX) ||
                    it.startsWith(SettingsKeys.DEBUG_WINDOW_Y_PREFIX)
            }
            .forEach { editor.remove(it) }
        editor.apply()
    }

    // ===== Auto-start / auto-background =====

    fun isAutoStartEnabled(): Boolean = prefs.getBoolean(SettingsKeys.AUTO_START_ENABLED, false)

    fun setAutoStartEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsKeys.AUTO_START_ENABLED, enabled).apply()
    }

    fun isAutoBackgroundEnabled(): Boolean = prefs.getBoolean(SettingsKeys.AUTO_BACKGROUND_ENABLED, false)

    fun setAutoBackgroundEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsKeys.AUTO_BACKGROUND_ENABLED, enabled).apply()
    }

    fun getAutoBackgroundDelay(): Int = prefs.getInt(SettingsKeys.AUTO_BACKGROUND_DELAY, 5)

    fun setAutoBackgroundDelay(seconds: Int) {
        prefs.edit().putInt(SettingsKeys.AUTO_BACKGROUND_DELAY, seconds).apply()
    }

    fun isAutoBackgroundOnlyOnBoot(): Boolean =
        prefs.getBoolean(SettingsKeys.AUTO_BACKGROUND_ONLY_ON_BOOT, true)

    fun setAutoBackgroundOnlyOnBoot(onlyOnBoot: Boolean) {
        prefs.edit().putBoolean(SettingsKeys.AUTO_BACKGROUND_ONLY_ON_BOOT, onlyOnBoot).apply()
    }

    // ===== Theme =====

    /** 0 = System, 1 = Light, 2 = Dark. */
    fun getDarkModePreference(): Int = prefs.getInt(SettingsKeys.DARK_MODE_PREFERENCE, 0)

    fun setDarkModePreference(mode: Int) {
        prefs.edit().putInt(SettingsKeys.DARK_MODE_PREFERENCE, mode).apply()
    }

    // ===== Tuner =====

    fun isLocalMode(): Boolean = prefs.getBoolean(SettingsKeys.LOCAL_MODE, false)

    fun setLocalMode(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsKeys.LOCAL_MODE, enabled).apply()
    }

    fun isMonoMode(): Boolean = prefs.getBoolean(SettingsKeys.MONO_MODE, false)

    fun setMonoMode(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsKeys.MONO_MODE, enabled).apply()
    }

    /** 2 = Europe (default). */
    fun getRadioArea(): Int = prefs.getInt(SettingsKeys.RADIO_AREA, 2)

    fun setRadioArea(area: Int) {
        prefs.edit().putInt(SettingsKeys.RADIO_AREA, area).apply()
    }

    /** Schrittweite der Prev/Next-Buttons im FM-Modus (in MHz).
     *  Min sinnvoll 0.05, Max sinnvoll 0.5. Default 0.1 MHz. */
    fun getFmFrequencyStep(): Float = prefs.getFloat(SettingsKeys.FM_FREQUENCY_STEP, 0.1f)

    fun setFmFrequencyStep(step: Float) {
        prefs.edit().putFloat(SettingsKeys.FM_FREQUENCY_STEP, step).apply()
    }

    /** Schrittweite der Prev/Next-Buttons im AM-Modus (in kHz).
     *  Min 1, Max sinnvoll 100. Default 9 kHz (Europe/IARU R1 Raster). */
    fun getAmFrequencyStep(): Float = prefs.getFloat(SettingsKeys.AM_FREQUENCY_STEP, 9f)

    fun setAmFrequencyStep(step: Float) {
        prefs.edit().putFloat(SettingsKeys.AM_FREQUENCY_STEP, step).apply()
    }

    // ===== Signal-bars icon (per mode) =====

    fun isSignalIconEnabledFm(): Boolean = prefs.getBoolean(SettingsKeys.SIGNAL_ICON_ENABLED_FM, true)

    fun setSignalIconEnabledFm(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsKeys.SIGNAL_ICON_ENABLED_FM, enabled).apply()
    }

    fun isSignalIconEnabledAm(): Boolean = prefs.getBoolean(SettingsKeys.SIGNAL_ICON_ENABLED_AM, true)

    fun setSignalIconEnabledAm(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsKeys.SIGNAL_ICON_ENABLED_AM, enabled).apply()
    }

    fun isSignalIconEnabledDab(): Boolean = prefs.getBoolean(SettingsKeys.SIGNAL_ICON_ENABLED_DAB, true)

    fun setSignalIconEnabledDab(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsKeys.SIGNAL_ICON_ENABLED_DAB, enabled).apply()
    }

    // ===== Favourites filter (per mode) =====

    fun isShowFavoritesOnlyFm(): Boolean = prefs.getBoolean(SettingsKeys.SHOW_FAVORITES_ONLY_FM, false)

    fun setShowFavoritesOnlyFm(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsKeys.SHOW_FAVORITES_ONLY_FM, enabled).apply()
    }

    fun isShowFavoritesOnlyAm(): Boolean = prefs.getBoolean(SettingsKeys.SHOW_FAVORITES_ONLY_AM, false)

    fun setShowFavoritesOnlyAm(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsKeys.SHOW_FAVORITES_ONLY_AM, enabled).apply()
    }

    fun isShowFavoritesOnlyDab(): Boolean = prefs.getBoolean(SettingsKeys.SHOW_FAVORITES_ONLY_DAB, false)

    fun setShowFavoritesOnlyDab(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsKeys.SHOW_FAVORITES_ONLY_DAB, enabled).apply()
    }

    // ===== Scan =====

    fun isOverwriteFavorites(): Boolean = prefs.getBoolean(SettingsKeys.OVERWRITE_FAVORITES, false)

    fun setOverwriteFavorites(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsKeys.OVERWRITE_FAVORITES, enabled).apply()
    }

    fun isAutoScanSensitivity(): Boolean = prefs.getBoolean(SettingsKeys.AUTO_SCAN_SENSITIVITY, false)

    fun setAutoScanSensitivity(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsKeys.AUTO_SCAN_SENSITIVITY, enabled).apply()
    }

    // ===== UI =====

    /** 0 = None, 1 = Slide, 2 = Fade. */
    fun getNowPlayingAnimation(): Int = prefs.getInt(SettingsKeys.NOW_PLAYING_ANIMATION, 1)

    fun setNowPlayingAnimation(type: Int) {
        prefs.edit().putInt(SettingsKeys.NOW_PLAYING_ANIMATION, type).apply()
    }

    fun isCorrectionHelpersEnabled(): Boolean =
        prefs.getBoolean(SettingsKeys.CORRECTION_HELPERS_ENABLED, false)

    fun setCorrectionHelpersEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsKeys.CORRECTION_HELPERS_ENABLED, enabled).apply()
    }

    fun isShowLogosInFavorites(): Boolean = prefs.getBoolean(SettingsKeys.SHOW_LOGOS_IN_FAVORITES, true)

    fun setShowLogosInFavorites(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsKeys.SHOW_LOGOS_IN_FAVORITES, enabled).apply()
    }

    fun isCarouselMode(): Boolean = prefs.getBoolean(SettingsKeys.CAROUSEL_MODE, false)

    fun setCarouselMode(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsKeys.CAROUSEL_MODE, enabled).apply()
    }

    /** Per-radio-mode carousel preference (mode is "FM" / "AM" / "DAB"). */
    fun isCarouselModeForRadioMode(radioMode: String): Boolean =
        prefs.getBoolean(SettingsKeys.CAROUSEL_MODE_FOR_RADIO_MODE_PREFIX + radioMode, false)

    fun setCarouselModeForRadioMode(radioMode: String, enabled: Boolean) {
        prefs.edit().putBoolean(SettingsKeys.CAROUSEL_MODE_FOR_RADIO_MODE_PREFIX + radioMode, enabled).apply()
    }

    // ===== First-launch =====

    fun hasAskedAboutImport(): Boolean = prefs.getBoolean(SettingsKeys.ASKED_ABOUT_IMPORT, false)

    fun setAskedAboutImport(asked: Boolean) {
        prefs.edit().putBoolean(SettingsKeys.ASKED_ABOUT_IMPORT, asked).apply()
    }

    // ===== Steering wheel / overlays =====

    fun isShowStationChangeToast(): Boolean = prefs.getBoolean(SettingsKeys.SHOW_STATION_CHANGE_TOAST, true)

    fun setShowStationChangeToast(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsKeys.SHOW_STATION_CHANGE_TOAST, enabled).apply()
    }

    fun isPermanentStationOverlay(): Boolean =
        prefs.getBoolean(SettingsKeys.PERMANENT_STATION_OVERLAY, false)

    fun setPermanentStationOverlay(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsKeys.PERMANENT_STATION_OVERLAY, enabled).apply()
    }

    fun isRevertPrevNext(): Boolean = prefs.getBoolean(SettingsKeys.REVERT_PREV_NEXT, false)

    fun setRevertPrevNext(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsKeys.REVERT_PREV_NEXT, enabled).apply()
    }

    // ===== DAB visualizer =====

    fun isDabVisualizerEnabled(): Boolean = prefs.getBoolean(SettingsKeys.DAB_VISUALIZER_ENABLED, true)

    fun setDabVisualizerEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsKeys.DAB_VISUALIZER_ENABLED, enabled).apply()
    }

    /** 0 = Bars, 1 = Waveform, 2 = Circular. */
    fun getDabVisualizerStyle(): Int = prefs.getInt(SettingsKeys.DAB_VISUALIZER_STYLE, 0)

    fun setDabVisualizerStyle(style: Int) {
        prefs.edit().putInt(SettingsKeys.DAB_VISUALIZER_STYLE, style).apply()
    }

    // ===== DAB recording =====

    fun getDabRecordingPath(): String? = prefs.getString(SettingsKeys.DAB_RECORDING_PATH, null)

    fun setDabRecordingPath(path: String?) {
        prefs.edit().putString(SettingsKeys.DAB_RECORDING_PATH, path).apply()
    }

    fun isDabRecordingEnabled(): Boolean = getDabRecordingPath() != null

    // ===== Tick sound =====

    fun isTickSoundEnabled(): Boolean = prefs.getBoolean(SettingsKeys.TICK_SOUND_ENABLED, false)

    fun setTickSoundEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsKeys.TICK_SOUND_ENABLED, enabled).apply()
    }

    /** 0–100, clamped. */
    fun getTickSoundVolume(): Int = prefs.getInt(SettingsKeys.TICK_SOUND_VOLUME, 50)

    fun setTickSoundVolume(volume: Int) {
        prefs.edit().putInt(SettingsKeys.TICK_SOUND_VOLUME, volume.coerceIn(0, 100)).apply()
    }

    // ===== Cover source lock (DAB) =====

    fun isCoverSourceLocked(): Boolean = prefs.getBoolean(SettingsKeys.COVER_SOURCE_LOCKED, false)

    fun setCoverSourceLocked(locked: Boolean) {
        prefs.edit().putBoolean(SettingsKeys.COVER_SOURCE_LOCKED, locked).apply()
    }

    fun getLockedCoverSource(): String? = prefs.getString(SettingsKeys.LOCKED_COVER_SOURCE, null)

    fun setLockedCoverSource(source: String?) {
        prefs.edit().putString(SettingsKeys.LOCKED_COVER_SOURCE, source).apply()
    }

    // ===== Demo mode (DAB) =====
    // Activates the MockDabTunerManager backend with real audio loop, demo
    // stations, demo logos and synced DLS. Default off; user opts in via the
    // "DAB Demo Mode" toggle in Settings.
    // SharedPreferences key still uses the historical "dab_dev_mode" name to
    // preserve any persisted preference across this rename.

    fun isDabDevModeEnabled(): Boolean =
        prefs.getBoolean(SettingsKeys.DAB_DEV_MODE_ENABLED, false)

    fun setDabDevModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsKeys.DAB_DEV_MODE_ENABLED, enabled).apply()
    }

    // ===== Root fallback (sqlfmservice via su) =====

    fun isAllowRootFallback(): Boolean = prefs.getBoolean(SettingsKeys.ALLOW_ROOT_FALLBACK, false)

    fun setAllowRootFallback(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsKeys.ALLOW_ROOT_FALLBACK, enabled).apply()
    }

    // ===== Accent colors (App Design) =====
    // 0 = Default (use built-in radio_accent resource).
    // Anything non-zero is a packed ARGB int chosen by the user.

    fun getAccentColorDay(): Int = prefs.getInt(SettingsKeys.ACCENT_COLOR_DAY, 0)

    fun setAccentColorDay(color: Int) {
        prefs.edit().putInt(SettingsKeys.ACCENT_COLOR_DAY, color).apply()
    }

    fun getAccentColorNight(): Int = prefs.getInt(SettingsKeys.ACCENT_COLOR_NIGHT, 0)

    fun setAccentColorNight(color: Int) {
        prefs.edit().putInt(SettingsKeys.ACCENT_COLOR_NIGHT, color).apply()
    }

    fun resetAccentColors() {
        prefs.edit()
            .remove(SettingsKeys.ACCENT_COLOR_DAY)
            .remove(SettingsKeys.ACCENT_COLOR_NIGHT)
            .apply()
    }
}
