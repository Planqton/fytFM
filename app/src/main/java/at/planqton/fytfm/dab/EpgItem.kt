package at.planqton.fytfm.dab

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Repräsentiert einen einzelnen EPG-Eintrag (Sendung).
 */
data class EpgItem(
    val title: String,
    val description: String? = null,
    val startTime: Long,           // POSIX Timestamp (Sekunden)
    val endTime: Long? = null,     // POSIX Timestamp (Sekunden)
    val duration: Long? = null,    // Dauer in Sekunden
    val genre: String? = null,
    val isLive: Boolean = false
) {
    /**
     * Formatierte Startzeit (z.B. "14:30")
     */
    fun getFormattedStartTime(): String {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        return sdf.format(Date(startTime * 1000))
    }

    /**
     * Formatierte Endzeit (z.B. "15:00")
     */
    fun getFormattedEndTime(): String? {
        return endTime?.let {
            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            sdf.format(Date(it * 1000))
        }
    }

    /**
     * Formatierte Dauer (z.B. "30 Min")
     */
    fun getFormattedDuration(): String? {
        val dur = duration ?: run {
            // Berechne aus Start/End falls vorhanden
            if (endTime != null && endTime > startTime) {
                endTime - startTime
            } else null
        }
        return dur?.let { "${it / 60} Min" }
    }

    /**
     * Fortschritt in Prozent (0-100) falls Sendung läuft
     */
    fun getProgress(): Int? {
        if (!isLive) return null
        val end = endTime ?: return null
        val now = System.currentTimeMillis() / 1000
        val total = end - startTime
        val elapsed = now - startTime
        return if (total > 0) ((elapsed * 100) / total).toInt().coerceIn(0, 100) else null
    }

    /**
     * Verbleibende Zeit in Minuten
     */
    fun getRemainingMinutes(): Int? {
        if (!isLive) return null
        val end = endTime ?: return null
        val now = System.currentTimeMillis() / 1000
        val remaining = end - now
        return if (remaining > 0) (remaining / 60).toInt() else 0
    }
}
