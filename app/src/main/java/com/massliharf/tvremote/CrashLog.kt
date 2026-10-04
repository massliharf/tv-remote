package com.massliharf.tvremote

import android.content.Context
import android.os.Build
import java.io.PrintWriter
import java.io.StringWriter

/** Saves the stack trace of a crash so the next launch can show it and let the user copy it. */
object CrashLog {
    private const val KEY = "last_crash"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Handler) return
        Thread.setDefaultUncaughtExceptionHandler(Handler(app, previous))
    }

    /** Returns the saved crash report once, then forgets it. */
    fun take(context: Context): String? {
        val sp = context.getSharedPreferences("crash", Context.MODE_PRIVATE)
        val report = sp.getString(KEY, null) ?: return null
        sp.edit().remove(KEY).apply()
        return report
    }

    private class Handler(
        private val context: Context,
        private val previous: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val version = try {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                } catch (_: Exception) { "?" }
                val report = "Uygulama $version · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
                    "${Build.MANUFACTURER} ${Build.MODEL}\nThread: ${t.name}\n\n$sw"
                context.getSharedPreferences("crash", Context.MODE_PRIVATE)
                    .edit().putString(KEY, report.take(20_000)).commit()
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(t, e)
        }
    }
}
