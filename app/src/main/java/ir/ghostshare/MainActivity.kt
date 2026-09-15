package ir.ghostshare

import android.Manifest
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.Window
import android.view.WindowInsetsController
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * صفحه اصلی تنظیمات، نمایش وضعیت، کنترل مجوز اعلان‌ها و پیوند گیت‌هاب ماژول GhostShare.
 * بازطراحی شده بر اساس سیستم طراحی Material 3 Design Kit (Google M3) با پشتیبانی کامل از:
 *
 * ۱. اعتبارسنجی پویا و کنترل هوشمند مجوز اعلان:
 *    - بررسی وضعیت دسترسی با NotificationManagerCompat.areNotificationsEnabled() در تمام نسخه‌ها.
 *    - بررسی مجوز Runtime در اندروید ۱۳ به بعد (API 33+) با POST_NOTIFICATIONS.
 *    - بررسی مسدود نبودن کانال اعلان سایلنت اختصاصی ماژول.
 *    - دیالوگ راهنما و درخواست مجوز رسمی متریال ۳ با MaterialAlertDialogBuilder و گوشه‌های ۲۸dp.
 *    - هدایت مستقیم به صفحه تنظیمات اعلان برنامه در صورت رد دائمی یا مسدود بودن دسترسی.
 *
 * ۲. رنگ‌های پویا (Material You / Monet):
 *    استخراج خودکار پالت رنگی از والپیپر و تم گوشی کاربر در اندروید ۱۲ به بعد
 *    و سوئیچ خودکار بین حالت تیره و روشن همگام با سیستم‌عامل.
 *
 * ۳. تعامل بدون پرش و فیلیکر در نشانگر وضعیت (Status Badge):
 *    - انیمیشن ملایم چرخش نشانگر نوری و میکرواِشکیل کادر بدون تغییر ناگهانی ابعاد و متن.
 *    - بازخورد لمسی (Haptic Feedback) آنی برای تجربه کاربری نرم و حرفه‌ای.
 *    - مکانیزم دِبانس (Debounce Cooldown) ۱.۲ ثانیه‌ای جهت جلوگیری از ارسال اسپم برودکست.
 *    - ایمنی کامل در برابر نشت کانتکست در زمان بستن یا چرخش صفحه.
 */
open class MainActivity : AppCompatActivity() {

    private lateinit var layoutStatusBadge: LinearLayout
    private lateinit var tvStatusBadge: TextView
    private lateinit var ivStatusDot: ImageView
    private lateinit var switchNotification: Switch
    private lateinit var cardGithub: LinearLayout

    // لانچر رسمی درخواست مجوز Runtime در اندروید ۱۳ به بعد
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>

    // پرچم‌های کنترل وضعیت داخلی جهت جلوگیری از لوپ رویدادها و همگام‌سازی بعد از بازگشت از تنظیمات
    private var isUpdatingSwitchInternally = false
    private var waitingForNotificationSettings = false

    // وضعیت قبلی نشانگر جهت پیشگیری از تغییرات ناگهانی و ریدراهای مکرر
    private var lastStatusState: StatusState? = null
    private var lastCheckTimestamp = 0L
    private var isCheckingInProgress = false

    private enum class StatusState {
        ACTIVE,
        NEEDS_REBOOT,
        INACTIVE
    }

    /**
     * این متد در زمان اجرا توسط هوک اختصاصی در HookEntry به مقدار true تغییر می‌یابد.
     * به صورت open تعریف شده تا توسط بهینه‌سازهای کامپایلر اینلاین نشود.
     */
    open fun isModuleActive(): Boolean {
        return false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // حذف اکشن‌بار سنتی جهت نمایش استاندارد Material 3 Edge-to-Edge
        supportActionBar?.hide()
        actionBar?.hide()

        // ثبت لانچر درخواست مجوز Runtime برای اندروید ۱۳ به بعد
        notificationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                onNotificationPermissionGranted()
            } else {
                onNotificationPermissionDenied()
            }
        }

        setContentView(R.layout.activity_main)

        // تنظیم هماهنگ نوار وضعیت و کلیدهای ناوبری با سیستم رنگ و تم گوشی
        setupSystemBars()

        layoutStatusBadge = findViewById(R.id.layout_status_badge)
        tvStatusBadge = findViewById(R.id.tv_status_badge)
        ivStatusDot = findViewById(R.id.iv_status_dot)
        switchNotification = findViewById(R.id.switch_restore_notification)
        cardGithub = findViewById(R.id.card_github)

        // اطمینان از اعمال برش کادر گرد روی ریپل لمسی در سطح کانتکست
        cardGithub.clipToOutline = true

        // لمس نشانگر وضعیت همراه با بازخورد لمسی، انیمیشن نرم و دِبانس بدون پرش متن
        layoutStatusBadge.setOnClickListener {
            onStatusBadgeClicked()
        }

        // کلیک روی کارت گیت‌هاب برای باز کردن مستقیم مخزن در مرورگر
        cardGithub.setOnClickListener {
            cardGithub.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            openGitHubRepository()
        }

        // مقداردهی اولیه وضعیت سوئیچ با اعتبارسنجی مجوز
        initSwitchState()

        // کنترل مجوز هنگام تغییر وضعیت سوئیچ
        switchNotification.setOnCheckedChangeListener { view, isChecked ->
            if (isUpdatingSwitchInternally) return@setOnCheckedChangeListener

            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

            if (isChecked) {
                if (isNotificationPermissionGranted()) {
                    setNotificationEnabled(enabled = true, showToast = true)
                } else {
                    // دسترسی اعلان مسدود است؛ بازگرداندن سوئیچ به حالت خاموش و نمایش دیالوگ Material 3
                    setSwitchCheckedSilently(false)
                    showNotificationPermissionDialog()
                }
            } else {
                setNotificationEnabled(enabled = false, showToast = true)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setupSystemBars()

        // در صورت بازگشت کاربر از صفحه تنظیمات اعلان، بررسی مجدد دسترسی
        if (waitingForNotificationSettings) {
            waitingForNotificationSettings = false
            if (isNotificationPermissionGranted()) {
                setNotificationEnabled(enabled = true, showToast = true)
            } else {
                setSwitchCheckedSilently(false)
                saveNotificationPreference(false)
                notifySystemServer(false)
            }
        } else {
            syncNotificationSwitchState()
        }

        checkModuleStatus(silent = true)
    }

    /**
     * همگام‌سازی لحظه‌ای با تغییر حالت Dark/Light تم سیستم‌عامل
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        setupSystemBars()
        // بازنشانی وضعیت ظاهری با رنگ‌های جدید حالت شب/روز
        lastStatusState?.let { renderStatus(it, animateTransition = false) }
    }

    /**
     * هماهنگ‌سازی رنگ و کنتراست نوار وضعیت و ناوبری با سیستم Material 3 و Material You
     */
    private fun setupSystemBars() {
        val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        window.statusBarColor = getColor(R.color.m3_sys_color_background)
        window.navigationBarColor = getColor(R.color.m3_sys_color_background)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.insetsController
            if (controller != null) {
                val appearance = if (isNight) {
                    0
                } else {
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                }
                controller.setSystemBarsAppearance(
                    appearance,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                )
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            var flags = window.decorView.systemUiVisibility
            flags = if (!isNight) {
                flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            } else {
                flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                flags = if (!isNight) {
                    flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                } else {
                    flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
                }
            }
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = flags
        }
    }

    /**
     * بررسی جامع دسترسی اعلان:
     * ۱. بررسی مجوز Runtime در اندروید ۱۳ به بعد (API 33+) با POST_NOTIFICATIONS
     * ۲. بررسی فعال بودن اعلان برنامه در سطح سیستم با NotificationManagerCompat.areNotificationsEnabled()
     * ۳. بررسی مسدود نبودن کانال اعلان اختصاصی ماژول در اندروید ۸ به بعد (API 26+)
     */
    private fun isNotificationPermissionGranted(): Boolean {
        // ۱. بررسی مجوز Runtime در اندروید ۱۳ به بعد (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissionCheck = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            )
            if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }

        // ۲. بررسی وضعیت سراسری فعال بودن اعلان برنامه از طریق NotificationManagerCompat
        val nmCompat = NotificationManagerCompat.from(this)
        if (!nmCompat.areNotificationsEnabled()) {
            return false
        }

        // ۳. بررسی وضعیت کانال اعلان در اندروید ۸ به بعد (API 26+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val channel = nm?.getNotificationChannel(NotificationReceiver.CHANNEL_ID)
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
                return false
            }
        }

        return true
    }

    /**
     * مقداردهی سوئیچ در راه‌اندازی اولیه صفحه
     */
    private fun initSwitchState() {
        val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val savedState = prefs.getBoolean(Constants.PREF_SHOW_RESTORE_NOTIFICATION, true)

        if (savedState && isNotificationPermissionGranted()) {
            setSwitchCheckedSilently(true)
        } else {
            setSwitchCheckedSilently(false)
            if (savedState) {
                // اگر قبلاً روشن بوده ولی در سطح سیستم دسترسی قطع شده، ذخیره وضعیت جدید
                saveNotificationPreference(false)
            }
        }
    }

    /**
     * همگام‌سازی وضعیت سوئیچ با تنظیمات سیستم هنگام بازگشت به برنامه
     */
    private fun syncNotificationSwitchState() {
        val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val savedState = prefs.getBoolean(Constants.PREF_SHOW_RESTORE_NOTIFICATION, true)

        if (savedState) {
            if (isNotificationPermissionGranted()) {
                setSwitchCheckedSilently(true)
                notifySystemServer(true)
            } else {
                setSwitchCheckedSilently(false)
                saveNotificationPreference(false)
                notifySystemServer(false)
            }
        } else {
            setSwitchCheckedSilently(false)
            notifySystemServer(false)
        }
    }

    /**
     * نمایش دیالوگ راهنمایی و درخواست مجوز اعلان منطبق بر استانداردهای Material 3 Design Kit:
     * - گوشه‌های گرد ۲۸dp استاندارد Material 3
     * - استفاده از تم و پالت رنگی دینامیک (Monet)
     * - توضیح شفاف و محترمانه دلیل نیاز به اعلان
     * - دکمه مثبت برای اعطای مجوز یا باز کردن تنظیمات، و دکمه منفی برای انصراف
     */
    private fun showNotificationPermissionDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_notif_permission_title)
            .setMessage(R.string.dialog_notif_permission_message)
            .setIcon(R.drawable.ic_notification_ghost)
            .setPositiveButton(R.string.dialog_notif_permission_positive) { dialog, _ ->
                dialog.dismiss()
                requestNotificationAccess()
            }
            .setNegativeButton(R.string.dialog_notif_permission_negative) { dialog, _ ->
                dialog.dismiss()
                setSwitchCheckedSilently(false)
                saveNotificationPreference(false)
            }
            .setOnCancelListener {
                setSwitchCheckedSilently(false)
                saveNotificationPreference(false)
            }
            .show()
    }

    /**
     * پردازش درخواست دسترسی به اعلان:
     * در اندروید ۱۳ به بعد، در صورت امکان از لانچر سیستمی استفاده می‌شود؛
     * در غیر این صورت یا در نسخه‌های پایین‌تر، کاربر مستقیماً به صفحه تنظیمات اعلان برنامه هدایت می‌شود.
     */
    private fun requestNotificationAccess() {
        val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val hasRequestedBefore = prefs.getBoolean("has_requested_notif_permission", false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            if (!hasRequestedBefore || ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS)) {
                prefs.edit().putBoolean("has_requested_notif_permission", true).apply()
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        openAppNotificationSettings()
    }

    /**
     * باز کردن مستقیم صفحه تنظیمات اعلان ماژول GhostShare در تنظیمات سیستم‌عامل
     */
    private fun openAppNotificationSettings() {
        waitingForNotificationSettings = true
        try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                }
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
            }
            startActivity(intent)
        } catch (_: Throwable) {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            } catch (_: Throwable) {
                Toast.makeText(this, R.string.msg_notif_permission_settings_guide, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * کال‌بک تأیید مجوز از طرف کاربر
     */
    private fun onNotificationPermissionGranted() {
        if (isNotificationPermissionGranted()) {
            setNotificationEnabled(enabled = true, showToast = true)
        } else {
            // ممکن است مجوز Runtime تأیید شده باشد اما اعلان در سطح سیستم غیرفعال باشد
            openAppNotificationSettings()
        }
    }

    /**
     * کال‌بک رد مجوز از طرف کاربر
     */
    private fun onNotificationPermissionDenied() {
        setSwitchCheckedSilently(false)
        saveNotificationPreference(false)
        notifySystemServer(false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS)
        ) {
            Toast.makeText(this, R.string.msg_notif_permission_settings_guide, Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, R.string.msg_notif_permission_denied, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * اعمال وضعیت اعلان، ذخیره در SharedPreferences و ارسال برودکست به سیستم‌سرور
     */
    private fun setNotificationEnabled(enabled: Boolean, showToast: Boolean) {
        setSwitchCheckedSilently(enabled)
        saveNotificationPreference(enabled)
        notifySystemServer(enabled)

        if (showToast) {
            val msgRes = if (enabled) R.string.msg_notif_enabled else R.string.msg_notif_disabled
            Toast.makeText(this, msgRes, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setSwitchCheckedSilently(checked: Boolean) {
        isUpdatingSwitchInternally = true
        switchNotification.isChecked = checked
        isUpdatingSwitchInternally = false
    }

    private fun saveNotificationPreference(enabled: Boolean) {
        val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(Constants.PREF_SHOW_RESTORE_NOTIFICATION, enabled).apply()
    }

    /**
     * پردازش لمس بج وضعیت:
     * - بازخورد لرزشی (Haptic Feedback)
     * - جلوگیری از اسپم کلیک (Debounce)
     * - انیمیشن نرم نور وضعیت بدون هیچ‌گونه پرش سایز یا تغییر ناگهانی متن کادر
     */
    private fun onStatusBadgeClicked() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastCheckTimestamp < 1200L || isCheckingInProgress) {
            // جلوگیری از اسپم با مکانیزم Debounce
            return
        }
        lastCheckTimestamp = now
        isCheckingInProgress = true

        // بازخورد لمسی آنی
        try {
            layoutStatusBadge.performHapticFeedback(
                HapticFeedbackConstants.KEYBOARD_TAP,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )
        } catch (_: Throwable) {
            layoutStatusBadge.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }

        // افکت نرم فید و میکرواِشکیل کادر بدون تغییر سایز لی‌اوت
        layoutStatusBadge.animate()
            .scaleX(0.96f)
            .scaleY(0.96f)
            .setDuration(90L)
            .withEndAction {
                layoutStatusBadge.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(140L)
                    .start()
            }.start()

        // انیمیشن چرخش و پالس ملایم چراغ وضعیت (Status Dot)
        ivStatusDot.animate()
            .rotationBy(360f)
            .alpha(0.3f)
            .setDuration(260L)
            .withEndAction {
                ivStatusDot.animate()
                    .alpha(1.0f)
                    .setDuration(160L)
                    .start()
            }.start()

        checkModuleStatus(silent = false)
    }

    private fun openGitHubRepository() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(Constants.GITHUB_REPO_URL)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: Throwable) {
            Toast.makeText(this, "امکان باز کردن مرورگر یافت نشد", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * استعلام وضعیت فعال‌سازی ماژول:
     * ۱. هوک مستقیم isModuleActive()
     * ۲. پاسخ Ordered Broadcast سیستم‌سرور
     */
    private fun checkModuleStatus(silent: Boolean) {
        val directHookActive = isModuleActive()
        val intent = Intent(Constants.ACTION_CHECK_STATUS)

        try {
            sendOrderedBroadcast(
                intent,
                null,
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        if (isFinishing || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed)) {
                            return
                        }
                        val systemServerActive = resultCode == RESULT_OK
                        applyStatus(directHookActive, systemServerActive, animateTransition = !silent)
                        isCheckingInProgress = false
                    }
                },
                null,
                RESULT_CANCELED,
                null,
                null
            )
        } catch (_: Throwable) {
            if (!isFinishing && (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1 || !isDestroyed)) {
                applyStatus(directHookActive, false, animateTransition = !silent)
            }
            isCheckingInProgress = false
        }
    }

    private fun applyStatus(directHookActive: Boolean, systemServerActive: Boolean, animateTransition: Boolean) {
        val newState = when {
            systemServerActive -> StatusState.ACTIVE
            directHookActive -> StatusState.NEEDS_REBOOT
            else -> StatusState.INACTIVE
        }

        renderStatus(newState, animateTransition)
    }

    /**
     * اعمال وضعیت بصری با تغییرات پیوسته و جلوگیری از ریدراهای ناگهانی
     */
    private fun renderStatus(state: StatusState, animateTransition: Boolean) {
        val stateChanged = lastStatusState != state
        lastStatusState = state

        val bgRes: Int
        val textRes: Int
        val textColorRes: Int
        val dotColorRes: Int

        when (state) {
            StatusState.ACTIVE -> {
                bgRes = R.drawable.bg_m3_badge_active
                textRes = R.string.status_active
                textColorRes = R.color.m3_sys_color_on_success_container
                dotColorRes = R.color.m3_sys_color_success
            }
            StatusState.NEEDS_REBOOT -> {
                bgRes = R.drawable.bg_m3_badge_warning
                textRes = R.string.status_needs_reboot
                textColorRes = R.color.m3_sys_color_on_warning_container
                dotColorRes = R.color.m3_sys_color_warning
            }
            StatusState.INACTIVE -> {
                bgRes = R.drawable.bg_m3_badge_inactive
                textRes = R.string.status_inactive
                textColorRes = R.color.m3_sys_color_on_error_container
                dotColorRes = R.color.m3_sys_color_error
            }
        }

        // اگر وضعیت تغییری نکرده، بدون بازنویسی ناگهانی فقط رنگ و شکل حفظ شود
        if (!stateChanged && !animateTransition) {
            layoutStatusBadge.setBackgroundResource(bgRes)
            tvStatusBadge.setText(textRes)
            tvStatusBadge.setTextColor(getColor(textColorRes))
            ivStatusDot.setColorFilter(getColor(dotColorRes))
            return
        }

        if (stateChanged && animateTransition) {
            // تغییر ملایم با فید نرم در صورت تفاوت در وضعیت
            layoutStatusBadge.animate()
                .alpha(0.6f)
                .setDuration(100L)
                .withEndAction {
                    layoutStatusBadge.setBackgroundResource(bgRes)
                    tvStatusBadge.setText(textRes)
                    tvStatusBadge.setTextColor(getColor(textColorRes))
                    ivStatusDot.setColorFilter(getColor(dotColorRes))
                    layoutStatusBadge.animate()
                        .alpha(1.0f)
                        .setDuration(150L)
                        .start()
                }.start()
        } else {
            layoutStatusBadge.setBackgroundResource(bgRes)
            tvStatusBadge.setText(textRes)
            tvStatusBadge.setTextColor(getColor(textColorRes))
            ivStatusDot.setColorFilter(getColor(dotColorRes))
        }
    }

    private fun notifySystemServer(showNotification: Boolean) {
        try {
            val intent = Intent(Constants.ACTION_UPDATE_SETTINGS).apply {
                putExtra(Constants.EXTRA_SHOW_RESTORE_NOTIFICATION, showNotification)
            }
            sendBroadcast(intent)
        } catch (_: Throwable) {}
    }
}
