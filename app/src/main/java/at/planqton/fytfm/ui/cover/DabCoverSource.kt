package at.planqton.fytfm.ui.cover

/**
 * The four image sources the DAB cover display can show. The enum is used
 * both by the user-facing cycling logic (tap to switch) and by the auto-mode
 * that picks the "best available" source when nothing is pinned.
 *
 * Enum value names are persisted in settings (`locked_cover_source`), so
 * renaming a value requires a migration step.
 */
enum class DabCoverSource {
    DAB_LOGO,
    STATION_LOGO,
    SLIDESHOW,
    DEEZER,
}
