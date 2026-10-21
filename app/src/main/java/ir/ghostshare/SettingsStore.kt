package ir.ghostshare

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences

object SettingsStore {

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

    fun load(context: Context): GhostConfig = GhostConfig.fromPrefs(prefs(context))

    fun update(context: Context, transform: (GhostConfig) -> GhostConfig): GhostConfig {
        val updated = transform(load(context))
        prefs(context).edit()
            .putBoolean(Constants.PREF_REDIRECT_UNWRAP, updated.redirectUnwrap)
            .putBoolean(Constants.PREF_PER_APP_ENABLED, updated.perAppEnabled)
            .putString(Constants.PREF_PER_APP_PACKAGES, GhostConfig.joinList(updated.perAppPackages))
            .putBoolean(Constants.PREF_BYPASS_ENABLED, updated.bypassEnabled)
            .putString(Constants.PREF_BYPASS_PACKAGES, GhostConfig.joinList(updated.bypassPackages))
            .putString(Constants.PREF_ADDED_PARAMS, GhostConfig.joinList(updated.addedParameters))
            .putString(Constants.PREF_REMOVED_PARAMS, GhostConfig.joinList(updated.removedParameters))
            .putString(Constants.PREF_EXCEPTION_DOMAINS, GhostConfig.joinList(updated.exceptionDomains))
            .putString(Constants.PREF_EXCEPTION_PARAMS, GhostConfig.joinList(updated.exceptionParameters))
            .remove(Constants.PREF_USER_EXCEPTIONS)
            .apply()
        broadcast(context)
        return updated
    }

    fun broadcast(context: Context, showNotification: Boolean? = null) {
        try {
            val show = showNotification
                ?: prefs(context).getBoolean(Constants.PREF_SHOW_RESTORE_NOTIFICATION, true)
            val intent = Intent(Constants.ACTION_UPDATE_SETTINGS).apply {
                putExtra(Constants.EXTRA_SHOW_RESTORE_NOTIFICATION, show)
                putExtra(Constants.EXTRA_CONFIG, load(context).encode())
            }
            context.sendBroadcast(intent)
        } catch (_: Throwable) {
        }
    }
}
