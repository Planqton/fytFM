package at.planqton.fytfm

import android.content.Context
import androidx.core.content.edit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Tests for [CrashHandler] — the uncaught-exception trap and crash-log
 * persistence. Critical class: if it breaks silently, every subsequent
 * crash goes unreported.
 *
 * Pins the **deliberate asymmetry** between `clearCrashLog` (clears the
 * "had a crash" flag but **leaves the file** for manual inspection) and
 * `deleteCrashLog` (clears flag + deletes file).
 *
 * Robolectric provides real SharedPreferences and filesDir; we restore
 * the original UncaughtExceptionHandler in @After so a test failure
 * doesn't poison the runner.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class CrashHandlerTest {

    private lateinit var context: Context
    private var originalHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        // Wipe both persistence sites so tests don't bleed.
        context.getSharedPreferences("crash_prefs", Context.MODE_PRIVATE).edit { clear() }
        File(context.filesDir, "last_crash.txt").delete()
        // Snapshot the JVM-wide handler so install() doesn't escape this test.
        originalHandler = Thread.getDefaultUncaughtExceptionHandler()
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(originalHandler)
    }

    // ============ Companion methods ============

    @Test
    fun `hasCrashLog is false on fresh prefs`() {
        assertFalse(CrashHandler.hasCrashLog(context))
    }

    @Test
    fun `getCrashLog returns null when the crash file does not exist`() {
        assertNull(CrashHandler.getCrashLog(context))
    }

    @Test
    fun `getCrashLog returns the file contents when present`() {
        val file = File(context.filesDir, "last_crash.txt")
        file.writeText("=== test crash ===\nstack trace here")
        val log = CrashHandler.getCrashLog(context)
        assertNotNull(log)
        assertTrue(log!!.contains("test crash"))
    }

    @Test
    fun `clearCrashLog clears the flag but PRESERVES the file (intentional asymmetry)`() {
        // Pre-seed both halves.
        context.getSharedPreferences("crash_prefs", Context.MODE_PRIVATE)
            .edit { putBoolean("app_crashed", true) }
        val file = File(context.filesDir, "last_crash.txt")
        file.writeText("crash content")

        CrashHandler.clearCrashLog(context)

        assertFalse("flag must be cleared", CrashHandler.hasCrashLog(context))
        assertTrue("file is preserved for manual inspection", file.exists())
        assertEquals(
            "file content untouched after clear",
            "crash content",
            CrashHandler.getCrashLog(context),
        )
    }

    @Test
    fun `deleteCrashLog clears flag AND removes the file`() {
        context.getSharedPreferences("crash_prefs", Context.MODE_PRIVATE)
            .edit { putBoolean("app_crashed", true) }
        val file = File(context.filesDir, "last_crash.txt")
        file.writeText("doomed content")

        CrashHandler.deleteCrashLog(context)

        assertFalse(CrashHandler.hasCrashLog(context))
        assertFalse("file gone after delete", file.exists())
        assertNull(CrashHandler.getCrashLog(context))
    }

    @Test
    fun `deleteCrashLog is safe to call when there is no crash log`() {
        // Must not throw on the missing-file path.
        CrashHandler.deleteCrashLog(context)
        assertFalse(CrashHandler.hasCrashLog(context))
    }

    // ============ install + uncaughtException ============

    @Test
    fun `install registers as the default uncaught exception handler`() {
        CrashHandler.install(context)
        val current = Thread.getDefaultUncaughtExceptionHandler()
        assertNotNull(current)
        assertTrue(
            "installed handler must be a CrashHandler instance, got ${current!!::class.java}",
            current is CrashHandler,
        )
    }

    @Test
    fun `uncaughtException writes the crash file and sets the flag`() {
        val handler = CrashHandler(context)
        val previousDefault = Thread.getDefaultUncaughtExceptionHandler()
        // The uncaughtException impl forwards to the default handler. To
        // keep the test runner alive, swap in a no-op while we invoke.
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> /* swallow */ }
        try {
            // Re-construct the CrashHandler so it captures our no-op default.
            val isolated = CrashHandler(context)
            isolated.uncaughtException(
                Thread.currentThread(),
                RuntimeException("synthetic crash for test"),
            )
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousDefault)
        }

        assertTrue("flag is set after uncaught exception", CrashHandler.hasCrashLog(context))
        val log = CrashHandler.getCrashLog(context)
        assertNotNull("crash file written", log)
        assertTrue(
            "crash log contains the throwable's message",
            log!!.contains("synthetic crash for test"),
        )
        assertTrue("crash log contains stack trace section header", log.contains("Stack Trace"))
        assertTrue("crash log contains device section", log.contains("Gerät"))
        assertTrue("crash log contains app version section", log.contains("App Version"))
    }

    @Test
    fun `uncaughtException records the throwing thread name`() {
        val previousDefault = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> /* swallow */ }
        try {
            val isolated = CrashHandler(context)
            // Synthesize a "Thread named X" without spawning one — Thread
            // constructor lets us name without start.
            val namedThread = Thread(null, null, "fytFM-test-thread")
            isolated.uncaughtException(namedThread, IllegalStateException("boom"))
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousDefault)
        }
        val log = CrashHandler.getCrashLog(context)!!
        assertTrue("thread name surfaces in the log", log.contains("fytFM-test-thread"))
    }

    @Test
    fun `consecutive uncaughtException calls overwrite the previous crash log`() {
        val previousDefault = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> /* swallow */ }
        try {
            val handler = CrashHandler(context)
            handler.uncaughtException(Thread.currentThread(), RuntimeException("first crash"))
            handler.uncaughtException(Thread.currentThread(), RuntimeException("second crash"))
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousDefault)
        }
        val log = CrashHandler.getCrashLog(context)!!
        // We don't keep history — only the latest crash is persisted.
        assertFalse("first crash is overwritten", log.contains("first crash"))
        assertTrue("latest crash is what's read back", log.contains("second crash"))
    }
}
