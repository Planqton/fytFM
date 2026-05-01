package at.planqton.fytfm

import android.content.Context
import androidx.core.content.edit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Tests for [LocaleHelper] — the EN/DE/system language switcher.
 *
 * Pins the prefs key + default behaviour so a future migration to
 * DataStore (roadmap §P4) can verify byte-for-byte equivalence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class LocaleHelperTest {

    private lateinit var context: Context
    private lateinit var originalDefault: Locale

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("locale_prefs", Context.MODE_PRIVATE).edit { clear() }
        // Locale.setDefault is a JVM-wide side effect — snapshot and restore.
        originalDefault = Locale.getDefault()
    }

    @Test
    fun `getLanguage returns LANGUAGE_SYSTEM on fresh prefs`() {
        try {
            assertEquals(LocaleHelper.LANGUAGE_SYSTEM, LocaleHelper.getLanguage(context))
        } finally {
            Locale.setDefault(originalDefault)
        }
    }

    @Test
    fun `setLocale persists the chosen language`() {
        try {
            LocaleHelper.setLocale(context, LocaleHelper.LANGUAGE_GERMAN)
            assertEquals(LocaleHelper.LANGUAGE_GERMAN, LocaleHelper.getLanguage(context))
            LocaleHelper.setLocale(context, LocaleHelper.LANGUAGE_ENGLISH)
            assertEquals(LocaleHelper.LANGUAGE_ENGLISH, LocaleHelper.getLanguage(context))
        } finally {
            Locale.setDefault(originalDefault)
        }
    }

    @Test
    fun `setLocale GERMAN switches Locale_getDefault to German`() {
        try {
            LocaleHelper.setLocale(context, LocaleHelper.LANGUAGE_GERMAN)
            assertEquals("de", Locale.getDefault().language)
        } finally {
            Locale.setDefault(originalDefault)
        }
    }

    @Test
    fun `setLocale ENGLISH switches Locale_getDefault to English`() {
        try {
            LocaleHelper.setLocale(context, LocaleHelper.LANGUAGE_ENGLISH)
            assertEquals("en", Locale.getDefault().language)
        } finally {
            Locale.setDefault(originalDefault)
        }
    }

    @Test
    fun `setLocale SYSTEM keeps the system default locale`() {
        try {
            // Pre-set to a known German locale.
            Locale.setDefault(Locale.GERMAN)
            LocaleHelper.setLocale(context, LocaleHelper.LANGUAGE_SYSTEM)
            // SYSTEM passes Locale.getDefault() back through setDefault — no
            // external change. The persisted choice is "system".
            assertEquals(LocaleHelper.LANGUAGE_SYSTEM, LocaleHelper.getLanguage(context))
            assertEquals("de", Locale.getDefault().language)
        } finally {
            Locale.setDefault(originalDefault)
        }
    }

    @Test
    fun `setLocale returns a Context whose configuration uses the chosen locale`() {
        try {
            val configured = LocaleHelper.setLocale(context, LocaleHelper.LANGUAGE_GERMAN)
            val locales = configured.resources.configuration.locales
            assertTrue("LocaleList must contain a German entry", locales.size() > 0)
            assertEquals("de", locales.get(0).language)
        } finally {
            Locale.setDefault(originalDefault)
        }
    }

    @Test
    fun `onAttach respects the persisted language across process restarts`() {
        try {
            // Simulate prior session that chose German.
            LocaleHelper.setLocale(context, LocaleHelper.LANGUAGE_GERMAN)
            // Fresh attach (e.g. Activity recreate) re-reads prefs.
            val attached = LocaleHelper.onAttach(context)
            assertEquals("de", attached.resources.configuration.locales.get(0).language)
        } finally {
            Locale.setDefault(originalDefault)
        }
    }

    @Test
    fun `getLanguage tolerates an unknown stored value (treats as that string)`() {
        try {
            // Older builds might write something non-canonical — getLanguage
            // returns the raw string and the consumer (updateResources) falls
            // back to system default for anything not en/de.
            context.getSharedPreferences("locale_prefs", Context.MODE_PRIVATE)
                .edit { putString("language", "xx") }
            assertEquals("xx", LocaleHelper.getLanguage(context))
            // setLocale("xx") falls through to system default.
            val configured = LocaleHelper.setLocale(context, "xx")
            // No throw, no English/German imposed — system default kept.
            assertTrue(configured.resources.configuration.locales.size() > 0)
        } finally {
            Locale.setDefault(originalDefault)
        }
    }

    @Test
    fun `getLanguage returns LANGUAGE_SYSTEM when prefs explicitly contain null entry`() {
        // Edge: getString(key, default) — if the key was written as null
        // somehow (Android sometimes treats this as missing), default kicks in.
        try {
            context.getSharedPreferences("locale_prefs", Context.MODE_PRIVATE)
                .edit { remove("language") }
            assertEquals(LocaleHelper.LANGUAGE_SYSTEM, LocaleHelper.getLanguage(context))
        } finally {
            Locale.setDefault(originalDefault)
        }
    }
}
