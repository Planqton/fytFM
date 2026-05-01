package at.planqton.fytfm

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object LocaleHelper {

    private const val PREFS_NAME = "locale_prefs"
    private const val KEY_LANGUAGE = "language"

    const val LANGUAGE_SYSTEM = "system"
    const val LANGUAGE_ENGLISH = "en"
    const val LANGUAGE_GERMAN = "de"

    fun setLocale(context: Context, language: String): Context {
        saveLanguage(context, language)
        return updateResources(context, language)
    }

    fun onAttach(context: Context): Context {
        val language = getLanguage(context)
        return updateResources(context, language)
    }

    fun getLanguage(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LANGUAGE, LANGUAGE_SYSTEM) ?: LANGUAGE_SYSTEM
    }

    private fun saveLanguage(context: Context, language: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LANGUAGE, language).apply()
    }

    private fun updateResources(context: Context, language: String): Context {
        val locale = when (language) {
            LANGUAGE_ENGLISH -> Locale.ENGLISH
            LANGUAGE_GERMAN -> Locale.GERMAN
            else -> Locale.getDefault() // System default
        }

        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)

        return context.createConfigurationContext(config)
    }

    fun getLanguageDisplayName(context: Context, language: String): String {
        return when (language) {
            LANGUAGE_ENGLISH -> context.getString(R.string.language_english)
            LANGUAGE_GERMAN -> context.getString(R.string.language_german)
            else -> context.getString(R.string.language_system)
        }
    }
}
