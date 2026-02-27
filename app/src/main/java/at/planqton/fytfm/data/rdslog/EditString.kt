package at.planqton.fytfm.data.rdslog

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Strings that should be edited/replaced in RT before Spotify search.
 * Example: "Jetzt On Air:" -> "" (delete), " mit " -> " - " (replace)
 */
@Entity(
    tableName = "edit_strings",
    indices = [
        Index(value = ["textNormalized"], unique = true)
    ]
)
data class EditString(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // The original text to find (for display)
    val textOriginal: String,

    // Normalized version for matching (lowercase, trimmed)
    val textNormalized: String,

    // What to replace with (empty string = delete)
    val replaceWith: String = "",

    // Where to match: PREFIX, SUFFIX, EITHER, ANYWHERE
    val position: String = POSITION_PREFIX,

    // Only apply if first search found nothing (fallback mode)
    val onlyIfNotFound: Boolean = false,

    // Only apply if RT contains this string (empty = always apply)
    val conditionContains: String? = null,

    // Case sensitive matching for find text
    val caseSensitiveFind: Boolean = false,

    // Case sensitive matching for condition text
    val caseSensitiveCondition: Boolean = false,

    // Only apply for specific frequency in MHz (null = all frequencies)
    val forFrequency: Float? = null,

    // Is this rule enabled?
    val enabled: Boolean = true,

    // Timestamp when added
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        const val POSITION_PREFIX = "PREFIX"       // Am Anfang
        const val POSITION_SUFFIX = "SUFFIX"       // Am Ende
        const val POSITION_EITHER = "EITHER"       // Anfang oder Ende
        const val POSITION_ANYWHERE = "ANYWHERE"   // Überall

        fun normalize(text: String): String {
            return text.lowercase()
        }

        fun create(
            text: String,
            replaceWith: String = "",
            position: String = POSITION_PREFIX,
            onlyIfNotFound: Boolean = false,
            conditionContains: String? = null,
            caseSensitiveFind: Boolean = false,
            caseSensitiveCondition: Boolean = false,
            forFrequency: Float? = null,
            enabled: Boolean = true
        ): EditString {
            return EditString(
                textOriginal = text,
                textNormalized = normalize(text),
                replaceWith = replaceWith,
                position = position,
                onlyIfNotFound = onlyIfNotFound,
                conditionContains = conditionContains?.trim()?.takeIf { it.isNotEmpty() },
                caseSensitiveFind = caseSensitiveFind,
                caseSensitiveCondition = caseSensitiveCondition,
                forFrequency = forFrequency,
                enabled = enabled
            )
        }

        fun getPositionLabel(position: String): String {
            return when (position) {
                POSITION_PREFIX -> "Anfang"
                POSITION_SUFFIX -> "Ende"
                POSITION_EITHER -> "Anfang/Ende"
                POSITION_ANYWHERE -> "Überall"
                else -> position
            }
        }
    }
}
