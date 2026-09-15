package ir.ghostshare

/**
 * کلیدهای مشترک بین تنظیمات رابط کاربری (MainActivity)،
 * هوک سیستمی (HookEntry)، گیرنده اعلان (NotificationReceiver) و بازیابی لینک.
 */
object Constants {
    const val PREFS_NAME = "ghostshare_prefs"
    const val PREF_SHOW_RESTORE_NOTIFICATION = "show_restore_notification"

    // اکشن و اکسترا جهت همگام‌سازی لحظه‌ای تنظیمات با پروسه system_server
    const val ACTION_UPDATE_SETTINGS = "ir.ghostshare.ACTION_UPDATE_SETTINGS"
    const val EXTRA_SHOW_RESTORE_NOTIFICATION = "show_restore_notification"

    // اکشن بررسی وضعیت زنده ماژول در چارچوب سیستم (LSPosed / system_server)
    const val ACTION_CHECK_STATUS = "ir.ghostshare.ACTION_CHECK_STATUS"

    // اکشن نمایش اعلان بازگردانی از طریق پروسه برنامه GhostShare
    const val ACTION_SHOW_NOTIFICATION = "ir.ghostshare.ACTION_SHOW_NOTIFICATION"
    const val EXTRA_CALLING_PACKAGE = "calling_package"

    // اکشن بازیابی مستقیم لینک اصلی در پس‌زمینه بدون باز شدن اکتیویتی
    const val ACTION_RESTORE_CLIPBOARD = "ir.ghostshare.ACTION_RESTORE_CLIPBOARD"
    const val EXTRA_ORIGINAL_LINK = "original_link"

    // پرچم دور زدن تمیزکاری در هنگام بازگردانی لینک اصلی توسط کاربر
    const val EXTRA_BYPASS_CLEAN = "ir.ghostshare.BYPASS_CLEAN"
    const val LABEL_RESTORED_LINK = "ghostshare_restored_link"

    // آدرس مخزن رسمی گیت‌هاب پروژه
    const val GITHUB_REPO_URL = "https://github.com/BS1388/GhostShare"
}
