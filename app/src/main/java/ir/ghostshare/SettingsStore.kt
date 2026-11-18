package ir.ghostshare

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

object SettingsStore {

    private val writer: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "ghostshare-settings").apply { isDaemon = true }
        }
    }

    private val pendingSave = AtomicReference<GhostConfig?>(null)

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

    fun load(context: Context): GhostConfig {
        val stored = prefs(context)
        val config = GhostConfig.fromPrefs(stored)
        if (stored.getInt(Constants.PREF_DEFAULTS_VERSION, 0) >= Constants.DEFAULTS_VERSION) return config
        stored.edit().putInt(Constants.PREF_DEFAULTS_VERSION, Constants.DEFAULTS_VERSION).apply()
        val merged = LinkedHashMap(config.siteRules)
        for ((domain, rule) in DefaultSiteRules.ALL) {
            val existing = merged[domain]
            merged[domain] = if (existing == null) rule else SiteRule(existing.keep + rule.keep, existing.strip + rule.strip)
        }
        val migrated = config.copy(siteRules = merged)
        save(context, migrated)
        return migrated
    }

    fun update(context: Context, transform: (GhostConfig) -> GhostConfig): GhostConfig {
        val updated = transform(load(context))
        save(context, updated)
        return updated
    }

    fun saveAsync(context: Context, config: GhostConfig) {
        val appContext = context.applicationContext
        pendingSave.set(config)
        writer.execute {
            val next = pendingSave.getAndSet(null) ?: return@execute
            try {
                save(appContext, next)
            } catch (_: Throwable) {
            }
        }
    }

    fun save(context: Context, updated: GhostConfig) {
        prefs(context).edit()
            .putBoolean(Constants.PREF_REDIRECT_UNWRAP, updated.redirectUnwrap)
            .putBoolean(Constants.PREF_PER_APP_ENABLED, updated.perAppEnabled)
            .putString(Constants.PREF_PER_APP_PACKAGES, GhostConfig.joinList(updated.perAppPackages))
            .putBoolean(Constants.PREF_BYPASS_ENABLED, updated.bypassEnabled)
            .putString(Constants.PREF_BYPASS_PACKAGES, GhostConfig.joinList(updated.bypassPackages))
            .putString(Constants.PREF_ADDED_PARAMS, GhostConfig.joinList(updated.addedParameters))
            .putString(Constants.PREF_REMOVED_PARAMS, GhostConfig.joinList(updated.removedParameters))
            .putString(Constants.PREF_EXCEPTION_DOMAINS, GhostConfig.joinList(updated.exceptionDomains))
            .putString(Constants.PREF_SITE_RULES, GhostConfig.joinSiteRules(updated.siteRules))
            .putString(Constants.PREF_REDIRECT_RULES, GhostConfig.joinRedirectRules(updated.redirectRules))
            .remove(Constants.PREF_USER_EXCEPTIONS)
            .remove("exception_params")
            .apply()
        broadcast(context, config = updated)
    }

    fun broadcast(context: Context, showNotification: Boolean? = null, config: GhostConfig? = null) {
        try {
            val show = showNotification
                ?: prefs(context).getBoolean(Constants.PREF_SHOW_RESTORE_NOTIFICATION, true)
            val intent = Intent(Constants.ACTION_UPDATE_SETTINGS).apply {
                setPackage("android")
                putExtra(Constants.EXTRA_SHOW_RESTORE_NOTIFICATION, show)
                putExtra(Constants.EXTRA_CONFIG, (config ?: load(context)).encode())
            }
            context.sendBroadcast(intent)
        } catch (_: Throwable) {
        }
    }
}
