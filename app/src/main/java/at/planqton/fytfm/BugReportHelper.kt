package at.planqton.fytfm

import android.content.Context
import android.os.Build
import android.util.Log
import at.planqton.fytfm.spotify.TrackInfo
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.*

/**
 * Helper class for creating bug reports with logcat, RDS data, Spotify info, etc.
 */
class BugReportHelper(private val context: Context) {

    companion object {
        private const val TAG = "BugReportHelper"
        private const val REPORTS_DIR = "bug_reports"
        private const val MAX_LOGCAT_LINES = 2000
    }

    data class AppState(
        // RDS Data
        val rdsPs: String? = null,
        val rdsRt: String? = null,
        val rdsPi: Int = 0,
        val rdsPty: Int = 0,
        val rdsRssi: Int = 0,
        val rdsTp: Int = 0,
        val rdsTa: Int = 0,
        val rdsAfEnabled: Boolean = false,
        val rdsAfList: List<Short>? = null,
        val currentFrequency: Float = 0f,

        // Spotify Data
        val spotifyStatus: String? = null,
        val spotifyOriginalRt: String? = null,
        val spotifyStrippedRt: String? = null,
        val spotifyQuery: String? = null,
        val spotifyTrackInfo: TrackInfo? = null,

        // Additional info
        val userDescription: String? = null
    )

    /**
     * Creates a bug report and saves it to the app's files directory.
     * @return The file path of the created report, or null on error.
     */
    fun createBugReport(appState: AppState): String? {
        return try {
            val reportsDir = File(context.filesDir, REPORTS_DIR)
            if (!reportsDir.exists()) {
                reportsDir.mkdirs()
            }

            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
            val filename = "bugreport_$timestamp.txt"
            val reportFile = File(reportsDir, filename)

            val report = buildString {
                appendLine("=" .repeat(60))
                appendLine("fytFM Bug Report")
                appendLine("=" .repeat(60))
                appendLine()

                // Timestamp and version
                appendLine("## Report Info")
                appendLine("Timestamp: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
                appendLine("App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("Build Date: ${BuildConfig.BUILD_DATE} ${BuildConfig.BUILD_TIME}")
                appendLine("Build Type: ${BuildConfig.BUILD_TYPE}")
                appendLine()

                // Device Info
                appendLine("## Device Info")
                appendLine("Manufacturer: ${Build.MANUFACTURER}")
                appendLine("Model: ${Build.MODEL}")
                appendLine("Device: ${Build.DEVICE}")
                appendLine("Product: ${Build.PRODUCT}")
                appendLine("Board: ${Build.BOARD}")
                appendLine("Hardware: ${Build.HARDWARE}")
                appendLine("Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("Display: ${Build.DISPLAY}")
                appendLine()

                // User Description
                if (!appState.userDescription.isNullOrEmpty()) {
                    appendLine("## User Description")
                    appendLine(appState.userDescription)
                    appendLine()
                }

                // RDS Data
                appendLine("## RDS Data")
                appendLine("Frequency: ${appState.currentFrequency} MHz")
                appendLine("PS: ${appState.rdsPs ?: "(none)"}")
                appendLine("RT: ${appState.rdsRt ?: "(none)"}")
                appendLine("PI: 0x${Integer.toHexString(appState.rdsPi).uppercase()}")
                appendLine("PTY: ${appState.rdsPty} (${getPtyName(appState.rdsPty)})")
                appendLine("RSSI: ${appState.rdsRssi}")
                appendLine("TP: ${appState.rdsTp}")
                appendLine("TA: ${appState.rdsTa}")
                appendLine("AF Enabled: ${appState.rdsAfEnabled}")
                if (appState.rdsAfList != null && appState.rdsAfList.isNotEmpty()) {
                    appendLine("AF List: ${appState.rdsAfList.joinToString(", ") { "%.1f".format(it / 10.0) }}")
                }
                appendLine()

                // Spotify Data
                appendLine("## Spotify Data")
                appendLine("Status: ${appState.spotifyStatus ?: "(none)"}")
                appendLine("Original RT: ${appState.spotifyOriginalRt ?: "(none)"}")
                appendLine("Stripped RT: ${appState.spotifyStrippedRt ?: "(none)"}")
                appendLine("Query: ${appState.spotifyQuery ?: "(none)"}")
                if (appState.spotifyTrackInfo != null) {
                    val track = appState.spotifyTrackInfo
                    appendLine()
                    appendLine("### Track Info")
                    appendLine("Artist: ${track.artist}")
                    appendLine("Title: ${track.title}")
                    appendLine("Album: ${track.album ?: "(none)"}")
                    appendLine("All Artists: ${track.allArtists.joinToString(", ")}")
                    appendLine("Duration: ${formatDuration(track.durationMs)}")
                    appendLine("Popularity: ${track.popularity}/100")
                    appendLine("Explicit: ${track.explicit}")
                    appendLine("Track/Disc: ${track.trackNumber}/${track.discNumber}")
                    appendLine("ISRC: ${track.isrc ?: "(none)"}")
                    appendLine("Album Type: ${track.albumType ?: "(none)"}")
                    appendLine("Release Date: ${track.releaseDate ?: "(none)"}")
                    appendLine("Track ID: ${track.trackId ?: "(none)"}")
                    appendLine("Album ID: ${track.albumId ?: "(none)"}")
                    appendLine("Spotify URL: ${track.spotifyUrl ?: "(none)"}")
                    appendLine("Cover URL: ${track.coverUrl ?: track.coverUrlMedium ?: "(none)"}")
                }
                appendLine()

                // Logcat
                appendLine("## Logcat (last $MAX_LOGCAT_LINES lines)")
                appendLine("-".repeat(60))
                append(getLogcat())
            }

            reportFile.writeText(report)
            Log.i(TAG, "Bug report created: ${reportFile.absolutePath}")
            reportFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create bug report", e)
            null
        }
    }

    /**
     * Gets the list of all bug reports.
     */
    fun getBugReports(): List<File> {
        val reportsDir = File(context.filesDir, REPORTS_DIR)
        return if (reportsDir.exists()) {
            reportsDir.listFiles()
                ?.filter { it.name.startsWith("bugreport_") && it.name.endsWith(".txt") }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
        } else {
            emptyList()
        }
    }

    /**
     * Deletes a bug report.
     */
    fun deleteBugReport(file: File): Boolean {
        return try {
            file.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete bug report", e)
            false
        }
    }

    /**
     * Reads the content of a bug report.
     */
    fun readBugReport(file: File): String? {
        return try {
            file.readText()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read bug report", e)
            null
        }
    }

    private fun getLogcat(): String {
        return try {
            val process = Runtime.getRuntime().exec("logcat -d -v threadtime *:V")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val lines = mutableListOf<String>()

            reader.useLines { lineSequence ->
                lineSequence.forEach { line ->
                    // Filter for relevant tags
                    if (line.contains("fytFM") ||
                        line.contains("FmRadio") ||
                        line.contains("FmNative") ||
                        line.contains("RdsManager") ||
                        line.contains("FmService") ||
                        line.contains("Spotify") ||
                        line.contains("AndroidRuntime") ||
                        line.contains("System.err")) {
                        lines.add(line)
                    }
                }
            }

            // Take last MAX_LOGCAT_LINES lines
            val result = if (lines.size > MAX_LOGCAT_LINES) {
                lines.takeLast(MAX_LOGCAT_LINES)
            } else {
                lines
            }

            result.joinToString("\n")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get logcat", e)
            "Error getting logcat: ${e.message}"
        }
    }

    private fun getPtyName(pty: Int): String {
        return when (pty) {
            0 -> "None"
            1 -> "News"
            2 -> "Current Affairs"
            3 -> "Information"
            4 -> "Sport"
            5 -> "Education"
            6 -> "Drama"
            7 -> "Culture"
            8 -> "Science"
            9 -> "Varied"
            10 -> "Pop Music"
            11 -> "Rock Music"
            12 -> "Easy Listening"
            13 -> "Light Classical"
            14 -> "Serious Classical"
            15 -> "Other Music"
            else -> "Unknown"
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "${minutes}:${seconds.toString().padStart(2, '0')}"
    }
}
