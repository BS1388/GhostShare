package ir.ghostshare

import android.content.Context
import android.os.Build
import android.util.Log
import java.util.Date

object CrashLogger {

    private const val PREFS = "ghostshare_crash_log"
    private const val KEY = "last_crash"
    private const val MAX_LENGTH = 24000

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext ?: context
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val report = buildString {
                    append("Time: ").append(Date()).append('\n')
                    append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                    append(" | Android ").append(Build.VERSION.RELEASE)
                    append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
                    append("Thread: ").append(thread.name).append("\n\n")
                    append(Log.getStackTraceString(throwable))
                }
                appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY, report.take(MAX_LENGTH))
                    .commit()
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun consume(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val report = prefs.getString(KEY, null)
        if (report != null) prefs.edit().remove(KEY).commit()
        return report
    }
}
