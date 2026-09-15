package ir.ghostshare

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.widget.Toast

/**
 * گیرنده اعلان و بازیابی اختصاصی ماژول GhostShare در سطح پروسه برنامه.
 *
 * ویژگی‌ها و اصلاحات:
 * ۱. اعلان کاملاً سایلنت (Silent Notification):
 *    - قرارگیری در پنل اعلان‌ها با اهمیت IMPORTANCE_LOW بدون ویبره، بدون صدا و بدون پاپ‌آپ مزاحم (Heads-up).
 *    - استفاده از شناسه کانال اختصاصی ghostshare_silent_restore_v1 و پاک‌سازی کانال‌های قبلی.
 * ۲. رفع باگ بسته شدن قطعی و بدون بازگشت اعلان در هنگام کلیک روی «بازگرداندن»:
 *    - لغو درجا و دائمی نوتیفیکیشن با cancel(NOTIF_ID) و حذف کامل تایمرهای فعال.
 *    - علامت‌گذاری متن بازگردانی‌شده با LABEL_RESTORED_LINK و پرچم EXTRA_BYPASS_CLEAN جهت جلوگیری
 *      از تمیزکاری مجدد کلیپ بازگردانده‌شده و مسدودسازی کامل هرگونه نمایش دوباره اعلان.
 * ۳. تایم‌اوت خودکار ۵ ثانیه‌ای برای اعلان در صورت عدم تعامل کاربر.
 */
class NotificationReceiver : BroadcastReceiver() {

    companion object {
        const val CHANNEL_ID = "ghostshare_silent_restore_v1"
        const val NOTIF_ID = 7001

        @Volatile
        private var cancelHandler: Handler? = null

        private fun getCancelHandler(): Handler? {
            val looper = Looper.getMainLooper() ?: return null
            if (cancelHandler == null) {
                synchronized(this) {
                    if (cancelHandler == null) {
                        cancelHandler = Handler(looper)
                    }
                }
            }
            return cancelHandler
        }

        fun showNotification(ctx: Context, originalText: String, callingPackage: String) {
            try {
                val prefs = ctx.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                val isEnabled = prefs.getBoolean(Constants.PREF_SHOW_RESTORE_NOTIFICATION, true)
                if (!isEnabled) return

                val localizedCtx = try {
                    LocaleHelper.wrapContext(ctx)
                } catch (_: Throwable) {
                    ctx
                }

                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // حذف کانال‌های قدیمی جهت اعمال بدون تداخل تنظیمات سایلنت
                    try {
                        nm.deleteNotificationChannel("ghostshare_clipboard")
                        nm.deleteNotificationChannel("ghostshare_clipboard_v2")
                    } catch (_: Throwable) {}

                    val channel = NotificationChannel(
                        CHANNEL_ID,
                        localizedCtx.getString(R.string.notif_channel_name),
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = localizedCtx.getString(R.string.notif_channel_desc)
                        enableVibration(false)
                        setSound(null, null)
                        setShowBadge(false)
                    }
                    nm.createNotificationChannel(channel)
                }

                // اینتنت مستقیم به همین BroadcastReceiver در پس‌زمینه جهت بازیابی آنی
                val restoreBroadcastIntent = Intent(ctx, NotificationReceiver::class.java).apply {
                    action = Constants.ACTION_RESTORE_CLIPBOARD
                    putExtra(Constants.EXTRA_ORIGINAL_LINK, originalText)
                }

                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                val restorePendingIntent = PendingIntent.getBroadcast(
                    ctx,
                    NOTIF_ID + 1,
                    restoreBroadcastIntent,
                    flags
                )

                val contentText = if (callingPackage.isNotEmpty() && callingPackage != "unknown") {
                    localizedCtx.getString(R.string.notif_text_with_package, callingPackage)
                } else {
                    localizedCtx.getString(R.string.notif_text_generic)
                }

                val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Notification.Builder(ctx, CHANNEL_ID)
                } else {
                    @Suppress("DEPRECATION")
                    Notification.Builder(ctx)
                }

                // ساخت دکمه مستقیم «بازگرداندن» روی اعلان با ارجاع معتبر به پکیج android
                val actionTitle = localizedCtx.getString(R.string.action_restore)
                val actionIcon = android.R.drawable.ic_menu_revert
                val restoreAction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val icon = Icon.createWithResource("android", actionIcon)
                    Notification.Action.Builder(icon, actionTitle, restorePendingIntent).build()
                } else {
                    @Suppress("DEPRECATION")
                    Notification.Action.Builder(actionIcon, actionTitle, restorePendingIntent).build()
                }

                val notification = builder
                    .setContentTitle(localizedCtx.getString(R.string.notif_title))
                    .setContentText(contentText)
                    .setSmallIcon(R.drawable.ic_notification_ghost)
                    .setColor(0xFF00629E.toInt())
                    .setPriority(Notification.PRIORITY_LOW)
                    .setSound(null)
                    .setVibrate(null)
                    .setCategory(Notification.CATEGORY_STATUS)
                    .addAction(restoreAction)
                    .setContentIntent(restorePendingIntent)
                    .setAutoCancel(true)
                    .setTimeoutAfter(5000L)
                    .build()

                nm.notify(NOTIF_ID, notification)

                // لغو خودکار اعلان پس از ۵ ثانیه
                val handler = getCancelHandler()
                handler?.removeCallbacksAndMessages(null)
                val dismissTask = Runnable {
                    try {
                        nm.cancel(NOTIF_ID)
                    } catch (_: Throwable) {}
                }
                handler?.postDelayed(dismissTask, 5000L)
            } catch (_: Throwable) {}
        }
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        when (intent.action) {
            Constants.ACTION_SHOW_NOTIFICATION -> {
                val originalText = intent.getStringExtra(Constants.EXTRA_ORIGINAL_LINK) ?: return
                val callingPackage = intent.getStringExtra(Constants.EXTRA_CALLING_PACKAGE) ?: ""
                showNotification(context, originalText, callingPackage)
            }

            Constants.ACTION_RESTORE_CLIPBOARD -> {
                val originalText = intent.getStringExtra(Constants.EXTRA_ORIGINAL_LINK)
                if (!originalText.isNullOrEmpty()) {
                    // ۱. بستن فوری و لغو قطعی اعلان و ابطال کلیه تایمرهای بازپخش
                    try {
                        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                        nm?.cancel(NOTIF_ID)
                    } catch (_: Throwable) {}

                    getCancelHandler()?.removeCallbacksAndMessages(null)

                    // ۲. بازگردانی مستقیم متن اصلی به کلیپ‌بورد با برچسب اختصاصی و پرچم عدم پردازش
                    try {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val clipData = ClipData.newPlainText(Constants.LABEL_RESTORED_LINK, originalText)
                        val bypassExtras = PersistableBundle().apply {
                            putBoolean(Constants.EXTRA_BYPASS_CLEAN, true)
                        }
                        clipData.description.extras = bypassExtras
                        cm?.setPrimaryClip(clipData)
                    } catch (_: Throwable) {}

                    // ۳. اطلاع‌رسانی به هسته سیستم‌سرور جهت همگام‌سازی کش و لغو اعلان در سطح سیستم (در صورت عدم ارسال از سیستم)
                    if (!intent.getBooleanExtra("from_system", false)) {
                        try {
                            val syncIntent = Intent(Constants.ACTION_RESTORE_CLIPBOARD).apply {
                                setPackage("android")
                                putExtra(Constants.EXTRA_ORIGINAL_LINK, originalText)
                                putExtra("from_app", true)
                            }
                            context.sendBroadcast(syncIntent)
                        } catch (_: Throwable) {}
                    }

                    // ۴. نمایش پیام کوتاه تایید بازیابی
                    try {
                        val localizedCtx = LocaleHelper.wrapContext(context)
                        Toast.makeText(context.applicationContext, localizedCtx.getString(R.string.msg_link_restored), Toast.LENGTH_SHORT).show()
                    } catch (_: Throwable) {}
                }
            }
        }
    }
}
