package ir.ghostshare

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * مدیریت چندزبانه برنامه (فارسی و انگلیسی) بر اساس الزامات پروژه:
 *
 * ۱. در اولین اجرا:
 *    - بررسی زبان اصلی دستگاه (System Locale).
 *    - اگر زبان گوشی روی فارسی ('fa') باشد، زبان برنامه فارسی می‌شود.
 *    - اگر هر زبان دیگری باشد (انگلیسی، فرانسوی، آلمانی، عربی و...)، زبان برنامه اجباراً روی انگلیسی تنظیم می‌گردد.
 *
 * ۲. ذخیره وضعیت (State Persistence) در SharedPreferences با هماهنگی کامل حافظه و دیسک (commit).
 *
 * ۳. هماهنگی با سازوکار مدرن AndroidX و AppCompatDelegate برای اعمال آنی، نرم و بدون کرش
 *    همراه با تطبیق خودکار جهت لی‌اوت (RTL برای فارسی و LTR برای انگلیسی).
 */
object LocaleHelper {

    const val LANG_FA = "fa"
    const val LANG_EN = "en"
    const val PREF_APP_LANGUAGE = "app_language"

    /**
     * استخراج زبان ذخیره‌شده یا مقداردهی اولیه بر اساس زبان دستگاه
     */
    fun getLanguage(context: Context): String {
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(PREF_APP_LANGUAGE, null)
        if (!saved.isNullOrEmpty()) {
            return saved
        }

        // استخراج ایمن زبان سیستم دستگاه در اولین راه‌اندازی
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

        // فقط در صورتی که زبان گوشی فارسی باشد برنامه فارسی می‌شود؛ در غیر این صورت اجباراً انگلیسی
        val initialLang = if (deviceLang.equals(LANG_FA, ignoreCase = true) || deviceLang.startsWith("fa", ignoreCase = true)) {
            LANG_FA
        } else {
            LANG_EN
        }

        prefs.edit().putString(PREF_APP_LANGUAGE, initialLang).commit()
        return initialLang
    }

    /**
     * تغییر زبان جاری، ذخیره پایدار همگام و فعال‌سازی از طریق AppCompatDelegate
     */
    fun setLanguage(context: Context, newLang: String) {
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_APP_LANGUAGE, newLang).commit()

        val appLocales = LocaleListCompat.forLanguageTags(newLang)
        AppCompatDelegate.setApplicationLocales(appLocales)
    }

    /**
     * ایجاد Context سفارشی‌شده با زبان و جهت چیدمان (LayoutDirection) برای attachBaseContext
     */
    fun wrapContext(base: Context): Context {
        val lang = getLanguage(base)
        return getLocalizedContext(base, lang)
    }

    /**
     * دریافت Context بر اساس زبان مشخص جهت دسترسی به رشته‌های محلی‌شده زبان هدف
     */
    fun getLocalizedContext(context: Context, lang: String): Context {
        val locale = Locale(lang)
        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)

        return context.createConfigurationContext(config)
    }

    /**
     * آیا زبان جاری فارسی است؟
     */
    fun isPersian(context: Context): Boolean {
        return getLanguage(context) == LANG_FA
    }
}
