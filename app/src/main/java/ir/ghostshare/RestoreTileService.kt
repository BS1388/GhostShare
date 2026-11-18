package ir.ghostshare

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.app.NotificationManagerCompat

class RestoreTileService : TileService() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(base))
    }

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val enabled = isEnabled()
        if (!enabled && !NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            openApp()
            return
        }
        val next = !enabled
        getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(Constants.PREF_SHOW_RESTORE_NOTIFICATION, next)
            .apply()
        SettingsStore.broadcast(this, next)
        refresh()
    }

    private fun isEnabled(): Boolean =
        getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(Constants.PREF_SHOW_RESTORE_NOTIFICATION, true)

    private fun refresh() {
        val tile = qsTile ?: return
        val enabled = isEnabled()
        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(if (enabled) R.string.tile_state_on else R.string.tile_state_off)
        }
        tile.updateTile()
    }

    private fun openApp() {
        try {
            val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (_: Throwable) {
        }
    }
}
