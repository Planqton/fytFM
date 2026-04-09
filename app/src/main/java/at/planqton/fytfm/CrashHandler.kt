package at.planqton.fytfm

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * Handles uncaught exceptions and saves crash logs
 */
class CrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {

    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    companion object {
        private const val TAG = "CrashHandler"
        private const val CRASH_FILE = "last_crash.txt"
        private const val PREFS_NAME = "crash_prefs"
        private const val KEY_CRASHED = "app_crashed"

        fun install(context: Context) {
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(context))
            Log.i(TAG, "Crash handler installed")
        }

        fun hasCrashLog(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_CRASHED, false)
        }

        fun getCrashLog(context: Context): String? {
            val file = File(context.filesDir, CRASH_FILE)
            return if (file.exists()) file.readText() else null
        }

        fun clearCrashLog(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_CRASHED, false).apply()
            // Keep the file for manual inspection if needed
        }

        fun deleteCrashLog(context: Context) {
            clearCrashLog(context)
            File(context.filesDir, CRASH_FILE).delete()
        }
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            Log.e(TAG, "Uncaught exception! Saving crash log...")
            val crashLog = buildCrashLog(thread, throwable)
            saveCrashLog(crashLog)
            markCrashed()
            Log.e(TAG, "Crash saved: ${throwable.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save crash log", e)
        }

        // Kurze Pause um sicherzustellen dass alles geschrieben wurde
        try {
            Thread.sleep(200)
        } catch (e: InterruptedException) {
            // ignore
        }

        // Call default handler to let the system handle the crash
        defaultHandler?.uncaughtException(thread, throwable)
    }

    private fun buildCrashLog(thread: Thread, throwable: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        val stackTrace = sw.toString()

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val timestamp = dateFormat.format(Date())

        return buildString {
            appendLine("=== fytFM Crash Report ===")
            appendLine("Zeit: $timestamp")
            appendLine("Thread: ${thread.name}")
            appendLine()
            appendLine("=== Gerät ===")
            appendLine("Modell: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("App Version: ${getAppVersion()}")
            appendLine()
            appendLine("=== Fehler ===")
            appendLine("${throwable.javaClass.simpleName}: ${throwable.message}")
            appendLine()
            appendLine("=== Stack Trace ===")
            appendLine(stackTrace)
        }
    }

    private fun getAppVersion(): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            "${pInfo.versionName} (${pInfo.longVersionCode})"
        } catch (e: Exception) {
            "Unknown"
        }
    }

    private fun saveCrashLog(log: String) {
        val file = File(context.filesDir, CRASH_FILE)
        file.writeText(log)
    }

    private fun markCrashed() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_CRASHED, true).commit() // commit() statt apply() für sync write
    }
}
