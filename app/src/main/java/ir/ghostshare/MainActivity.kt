package ir.ghostshare

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
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

open class MainActivity : AppCompatActivity() {

    private lateinit var layoutStatusBadge: LinearLayout
    private lateinit var tvStatusBadge: TextView
    private lateinit var ivStatusDot: ImageView
    private lateinit var switchNotification: Switch
    private lateinit var cardGithub: LinearLayout

    private lateinit var btnLangFa: TextView
    private lateinit var btnLangEn: TextView

    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>

    private var isUpdatingSwitchInternally = false

    private val restorePrefListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == Constants.PREF_SHOW_RESTORE_NOTIFICATION) {
            setSwitchCheckedSilently(prefs.getBoolean(key, true))
        }
    }
    private var waitingForNotificationSettings = false

    private var lastStatusState: StatusState? = null
    private var lastCheckTimestamp = 0L
    private var isCheckingInProgress = false

    private enum class StatusState {
        ACTIVE,
        NEEDS_REBOOT,
        INACTIVE
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    open fun isModuleActive(): Boolean {
        return false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.hide()
        actionBar?.hide()

        notificationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                onNotificationPermissionGranted()
            } else {
                onNotificationPermissionDenied()
            }
        }

        val appContext = applicationContext
        Thread { SettingsStore.load(appContext) }.start()

        setContentView(R.layout.activity_main)

        setupSystemBars()

        layoutStatusBadge = findViewById(R.id.layout_status_badge)
        tvStatusBadge = findViewById(R.id.tv_status_badge)
        ivStatusDot = findViewById(R.id.iv_status_dot)
        switchNotification = findViewById(R.id.switch_restore_notification)
        cardGithub = findViewById(R.id.card_github)

        btnLangFa = findViewById(R.id.btn_lang_fa)
        btnLangEn = findViewById(R.id.btn_lang_en)

        updateLanguageSelectorUI(LocaleHelper.getLanguage(this))

        btnLangFa.setOnClickListener {
            onLanguageButtonClicked(LocaleHelper.LANG_FA)
        }

        btnLangEn.setOnClickListener {
            onLanguageButtonClicked(LocaleHelper.LANG_EN)
        }

        cardGithub.clipToOutline = true

        layoutStatusBadge.setOnClickListener {
            onStatusBadgeClicked()
        }

        cardGithub.setOnClickListener {
            cardGithub.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            openGitHubRepository()
        }

        savedInstanceState?.getString("saved_status_state")?.let { name ->
            try {
                lastStatusState = StatusState.valueOf(name)
                renderStatus(lastStatusState!!, animateTransition = false)
            } catch (_: Throwable) {}
        }

        createNotificationChannelIfNeeded()

        initSwitchState()

        findViewById<View>(R.id.card_advanced).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<View>(R.id.card_link_test).setOnClickListener {
            startActivity(
                Intent(this, SettingsActivity::class.java)
                    .putExtra(SettingsActivity.EXTRA_START_SCREEN, SettingsScreen.LINK_TEST.name)
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !isNotificationPermissionGranted()) {
            val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            val hasPrompted = prefs.getBoolean("has_initial_prompted_notif", false)
            if (!hasPrompted) {
                prefs.edit().putBoolean("has_initial_prompted_notif", true).apply()
                requestNotificationAccess()
            }
        }

        switchNotification.setOnCheckedChangeListener { view, isChecked ->
            if (isUpdatingSwitchInternally) return@setOnCheckedChangeListener

            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

            if (isChecked) {
                setNotificationEnabled(enabled = true, showToast = true)

                if (!isNotificationPermissionGranted()) {
                    showNotificationPermissionDialog()
                }
            } else {
                setNotificationEnabled(enabled = false, showToast = true)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        lastStatusState?.let { outState.putString("saved_status_state", it.name) }
    }

    override fun onStart() {
        super.onStart()
        getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(restorePrefListener)
    }

    override fun onStop() {
        getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(restorePrefListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        setupSystemBars()

        if (waitingForNotificationSettings) {
            waitingForNotificationSettings = false
            if (isNotificationPermissionGranted()) {
                setNotificationEnabled(enabled = true, showToast = true)
            }
        }
        syncNotificationSwitchState()

        checkModuleStatus(silent = true)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        setupSystemBars()

        lastStatusState?.let { renderStatus(it, animateTransition = false) }
    }

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

    private fun onLanguageButtonClicked(targetLang: String) {
        val currentLang = LocaleHelper.getLanguage(this)
        if (currentLang == targetLang) {
            btnLangFa.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            return
        }

        try {
            btnLangFa.performHapticFeedback(
                HapticFeedbackConstants.KEYBOARD_TAP,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )
        } catch (_: Throwable) {
            btnLangFa.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }

        updateLanguageSelectorUI(targetLang)
        LocaleHelper.setLanguage(this, targetLang)

        val targetCtx = LocaleHelper.getLocalizedContext(this, targetLang)
        Toast.makeText(this, targetCtx.getString(R.string.msg_lang_changed), Toast.LENGTH_SHORT).show()

        recreate()
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun updateLanguageSelectorUI(currentLang: String) {
        val isFa = currentLang == LocaleHelper.LANG_FA

        btnLangFa.setBackgroundResource(
            if (isFa) R.drawable.bg_m3_segment_active else R.drawable.bg_m3_segment_inactive
        )
        btnLangFa.setTextColor(
            getColor(if (isFa) R.color.m3_sys_color_on_primary_container else R.color.m3_sys_color_on_surface_variant)
        )

        btnLangEn.setBackgroundResource(
            if (!isFa) R.drawable.bg_m3_segment_active else R.drawable.bg_m3_segment_inactive
        )
        btnLangEn.setTextColor(
            getColor(if (!isFa) R.color.m3_sys_color_on_primary_container else R.color.m3_sys_color_on_surface_variant)
        )
    }

    private fun isNotificationPermissionGranted(): Boolean {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissionCheck = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            )
            if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }

        val nmCompat = NotificationManagerCompat.from(this)
        if (!nmCompat.areNotificationsEnabled()) {
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val channel = nm?.getNotificationChannel(NotificationReceiver.CHANNEL_ID)
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
                return false
            }
        }

        return true
    }

    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                NotificationReceiver.CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
                enableVibration(false)
                setSound(null, null)
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun initSwitchState() {
        val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean(Constants.PREF_SHOW_RESTORE_NOTIFICATION, true)
        setSwitchCheckedSilently(isEnabled)
        notifySystemServer(isEnabled)
    }

    private fun syncNotificationSwitchState() {
        val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean(Constants.PREF_SHOW_RESTORE_NOTIFICATION, true)
        setSwitchCheckedSilently(isEnabled)
        notifySystemServer(isEnabled)
    }

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

    private fun onNotificationPermissionGranted() {
        if (isNotificationPermissionGranted()) {
            setNotificationEnabled(enabled = true, showToast = true)
        } else {

            openAppNotificationSettings()
        }
    }

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

    private fun onStatusBadgeClicked() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastCheckTimestamp < 1200L || isCheckingInProgress) {

            return
        }
        lastCheckTimestamp = now
        isCheckingInProgress = true

        try {
            layoutStatusBadge.performHapticFeedback(
                HapticFeedbackConstants.KEYBOARD_TAP,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )
        } catch (_: Throwable) {
            layoutStatusBadge.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }

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
            Toast.makeText(this, R.string.msg_browser_not_found, Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkModuleStatus(silent: Boolean) {
        val directHookActive = isModuleActive()
        val intent = Intent(Constants.ACTION_CHECK_STATUS).setPackage("android")

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

        if (!stateChanged && !animateTransition) {
            layoutStatusBadge.setBackgroundResource(bgRes)
            tvStatusBadge.setText(textRes)
            tvStatusBadge.setTextColor(getColor(textColorRes))
            ivStatusDot.setColorFilter(getColor(dotColorRes))
            return
        }

        if (stateChanged && animateTransition) {

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
        SettingsStore.broadcast(this, showNotification)
    }
}
