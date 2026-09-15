package ir.ghostshare

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.widget.Toast

/**
 * اکتیویتی شفاف جهت حفظ سازگاری قبلی.
 * بازیابی به صورت مستقیم توسط NotificationReceiver و HookEntry در پس‌زمینه بدون باز شدن اکتیویتی انجام می‌شود،
 * اما در صورت فراخوانی مستقیم، این اکتیویتی لینک را بازگردانده و فوراً بسته می‌شود.
 */
class RestoreActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // حذف انیمیشن باز شدن پنجره جهت تجربه کاربری سریع و بدون پرش
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }

        window.setBackgroundDrawableResource(android.R.color.transparent)

        val original = intent?.getStringExtra(Constants.EXTRA_ORIGINAL_LINK)
            ?: intent?.getStringExtra(EXTRA_ORIGINAL_LINK)

        if (!original.isNullOrEmpty()) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clipData = ClipData.newPlainText("restored_link", original)
            val bypassExtras = PersistableBundle().apply {
                putBoolean(Constants.EXTRA_BYPASS_CLEAN, true)
            }
            clipData.description.extras = bypassExtras

            cm?.setPrimaryClip(clipData)
            Toast.makeText(applicationContext, "لینک اصلی بازگردانده شد", Toast.LENGTH_SHORT).show()
        }

        finish()

        // حذف انیمیشن بستن پنجره
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    companion object {
        const val EXTRA_ORIGINAL_LINK = "original_link"
    }
}
