package at.planqton.fytfm

/**
 * RDS Programme Type (PTY) → display-name lookup.
 *
 * Codes 0–15 are identical between RDS (Europe / IEC 62106) and RBDS
 * (North America). Codes 16–31 differ across the two standards; this
 * implementation uses **European RDS** mappings because the FYT
 * head-units this app targets are sold in DACH / EU markets and DAB+
 * broadcasters routinely transmit PTY ≥ 16 (Weather, Travel, Folk).
 *
 * Extracted from `BugReportHelper.getPtyName` so the table can be
 * unit-tested and reused. Returns `"Unknown"` for codes outside 0..31.
 */
internal object RdsPtyNames {

    private val EUROPEAN_PTY = arrayOf(
        // 0..15 — also valid under RBDS
        "None",
        "News",
        "Current Affairs",
        "Information",
        "Sport",
        "Education",
        "Drama",
        "Culture",
        "Science",
        "Varied",
        "Pop Music",
        "Rock Music",
        "Easy Listening",
        "Light Classical",
        "Serious Classical",
        "Other Music",
        // 16..31 — European RDS only
        "Weather",
        "Finance",
        "Children's Programmes",
        "Social Affairs",
        "Religion",
        "Phone-In",
        "Travel",
        "Leisure",
        "Jazz Music",
        "Country Music",
        "National Music",
        "Oldies Music",
        "Folk Music",
        "Documentary",
        "Alarm Test",
        "Alarm",
    )

    /** Returns the European PTY display name for [pty], or "Unknown" if out of range. */
    fun nameFor(pty: Int): String {
        return EUROPEAN_PTY.getOrNull(pty) ?: "Unknown"
    }
}
