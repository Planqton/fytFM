package at.planqton.fytfm.data.settings

/**
 * Centralised SharedPreferences key constants for [AppSettingsRepository].
 * Kept in one place so a key rename only happens once and `find usages`
 * reliably surfaces every reader/writer of a given setting.
 *
 * Conventions:
 *  - Prefix-by-feature where useful (e.g. `KEY_AUTO_BACKGROUND_*`).
 *  - Per-mode/per-frequency keys are constructed at the call site by
 *    appending a mode/frequency suffix to a base prefix below.
 */
internal object SettingsKeys {
    const val PREFS_FILE = "settings"

    // Autoplay
    const val AUTOPLAY_AT_STARTUP = "autoplay_at_startup"

    // Deezer
    const val DEEZER_ENABLED_FM = "deezer_enabled_fm"
    const val DEEZER_ENABLED_DAB = "deezer_enabled_dab"
    const val DEEZER_DISABLED_FREQUENCIES = "deezer_disabled_frequencies"
    const val DEEZER_CACHE_ENABLED = "deezer_cache_enabled"

    // Debug
    const val SHOW_DEBUG_INFOS = "show_debug_infos"
    const val DEBUG_WINDOW_OPEN_PREFIX = "debug_window_open_"
    const val DEBUG_WINDOW_X_PREFIX = "debug_window_x_"
    const val DEBUG_WINDOW_Y_PREFIX = "debug_window_y_"

    // Auto-start / auto-background
    const val AUTO_START_ENABLED = "auto_start_enabled"
    const val AUTO_BACKGROUND_ENABLED = "auto_background_enabled"
    const val AUTO_BACKGROUND_DELAY = "auto_background_delay"
    const val AUTO_BACKGROUND_ONLY_ON_BOOT = "auto_background_only_on_boot"

    // Theme
    const val DARK_MODE_PREFERENCE = "dark_mode_preference"

    // Tuner
    const val LOCAL_MODE = "local_mode"
    const val MONO_MODE = "mono_mode"
    const val RADIO_AREA = "radio_area"
    /** User-konfigurierbare Step-Größe für Prev/Next-Buttons. Default vom
     *  FrequencyScaleView (FM 0.1 MHz, AM 9 kHz). */
    const val FM_FREQUENCY_STEP = "fm_frequency_step"
    const val AM_FREQUENCY_STEP = "am_frequency_step"

    // Signal-bars icon (per mode)
    const val SIGNAL_ICON_ENABLED_FM = "signal_icon_enabled_fm"
    const val SIGNAL_ICON_ENABLED_AM = "signal_icon_enabled_am"

    // Favourites filter (per mode)
    const val SHOW_FAVORITES_ONLY_FM = "show_favorites_only_fm"
    const val SHOW_FAVORITES_ONLY_AM = "show_favorites_only_am"
    const val SHOW_FAVORITES_ONLY_DAB = "show_favorites_only_dab"

    // Scan
    const val OVERWRITE_FAVORITES = "overwrite_favorites"
    const val AUTO_SCAN_SENSITIVITY = "auto_scan_sensitivity"

    // UI
    const val NOW_PLAYING_ANIMATION = "now_playing_animation"
    const val CORRECTION_HELPERS_ENABLED = "correction_helpers_enabled"
    const val SHOW_LOGOS_IN_FAVORITES = "show_logos_in_favorites"
    const val CAROUSEL_MODE = "carousel_mode"
    const val CAROUSEL_MODE_FOR_RADIO_MODE_PREFIX = "carousel_mode_"

    // First-launch import dialog
    const val ASKED_ABOUT_IMPORT = "asked_about_import"

    // Steering wheel / overlays
    const val SHOW_STATION_CHANGE_TOAST = "show_station_change_toast"
    const val PERMANENT_STATION_OVERLAY = "permanent_station_overlay"
    const val REVERT_PREV_NEXT = "revert_prev_next"

    // DAB visualizer
    const val DAB_VISUALIZER_ENABLED = "dab_visualizer_enabled"
    const val DAB_VISUALIZER_STYLE = "dab_visualizer_style"

    // DAB recording
    const val DAB_RECORDING_PATH = "dab_recording_path"

    // Tick sound
    const val TICK_SOUND_ENABLED = "tick_sound_enabled"
    const val TICK_SOUND_VOLUME = "tick_sound_volume"

    // Cover source
    const val COVER_SOURCE_LOCKED = "cover_source_locked"
    const val LOCKED_COVER_SOURCE = "locked_cover_source"

    // Dev mode (currently feature-flagged off)
    const val DAB_DEV_MODE_ENABLED = "dab_dev_mode_enabled"

    // Root fallback for sqlfmservice (UIS7870/DUDU7 head units)
    const val ALLOW_ROOT_FALLBACK = "allow_root_fallback"

    // Accent colors (App Design). 0 = Default (use built-in radio_accent
    // resource from values/values-night). Anything else = user-picked
    // ARGB int.
    const val ACCENT_COLOR_DAY = "accent_color_day"
    const val ACCENT_COLOR_NIGHT = "accent_color_night"
}
