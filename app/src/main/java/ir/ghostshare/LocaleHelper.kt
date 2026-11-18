package ir.ghostshare

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

object LocaleHelper {

    const val LANG_FA = "fa"
    const val LANG_EN = "en"
    const val PREF_APP_LANGUAGE = "app_language"

    fun getLanguage(context: Context): String {
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(PREF_APP_LANGUAGE, null)
        if (!saved.isNullOrEmpty()) {
            return saved
        }

        val deviceLang: String = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val locales = Resources.getSystem().configuration.locales
                if (locales.size() > 0) {
                    locales.get(0)?.language ?: Locale.getDefault().language
                } else {
                    Locale.getDefault().language
                }
            } else {
                @Suppress("DEPRECATION")
                Resources.getSystem().configuration.locale?.language ?: Locale.getDefault().language
            }
        } catch (_: Throwable) {
            Locale.getDefault().language
        }

        val initialLang = if (deviceLang.equals(LANG_FA, ignoreCase = true) || deviceLang.startsWith("fa", ignoreCase = true)) {
            LANG_FA
        } else {
            LANG_EN
        }

        prefs.edit().putString(PREF_APP_LANGUAGE, initialLang).commit()
        return initialLang
    }

    fun setLanguage(context: Context, newLang: String) {
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_APP_LANGUAGE, newLang).commit()

        val appLocales = LocaleListCompat.forLanguageTags(newLang)
        AppCompatDelegate.setApplicationLocales(appLocales)
    }

    fun wrapContext(base: Context): Context {
        val lang = getLanguage(base)
        return getLocalizedContext(base, lang)
    }

    fun getLocalizedContext(context: Context, lang: String): Context {
        val locale = Locale(lang)
        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)

        return context.createConfigurationContext(config)
    }

    fun isPersian(context: Context): Boolean {
        return getLanguage(context) == LANG_FA
    }
}
