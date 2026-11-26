package com.example.nvdapocket

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes uncaught crashes (and errors the app catches on purpose) to a small text file in the app's
 * private storage, so a bug report is possible even after the app has closed. Settings has buttons to view,
 * share and clear it.
 */
object CrashLog {
    private const val FILE = "crash.log"
    private const val FLAG = "crash.flag"
    private const val MAX_CHARS = 60_000
    @Volatile private var app: Application? = null
    private var installed = false

    fun install(a: Application) {
        app = a
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                write("CRASH in thread ${t.name}", e)
                File(a.filesDir, FLAG).writeText("1")
            } catch (_: Throwable) {}
            previous?.uncaughtException(t, e)
        }
    }

    /** Records an error that was caught and handled, so it can still be reported. */
    fun log(tag: String, e: Throwable) {
        try { write("[$tag]", e) } catch (_: Throwable) {}
    }

    @Suppress("DEPRECATION")
    private fun buildInfo(a: Application): String = try {
        val pi = a.packageManager.getPackageInfo(a.packageName, 0)
        "build ${pi.versionCode}, v${pi.versionName}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}"
    } catch (_: Exception) { "unknown build" }

    @Synchronized
    private fun write(head: String, e: Throwable) {
        val a = app ?: return
        val f = File(a.filesDir, FILE)
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val entry = "== $stamp $head ==\n${buildInfo(a)}\n$sw\n"
        var all = (if (f.exists()) f.readText() else "") + entry
        if (all.length > MAX_CHARS) all = all.substring(all.length - MAX_CHARS)
        f.writeText(all)
    }

    fun read(c: Context): String = try {
        val f = File(c.applicationContext.filesDir, FILE)
        if (f.exists()) f.readText() else ""
    } catch (_: Exception) { "" }

    fun clear(c: Context) {
        try {
            File(c.applicationContext.filesDir, FILE).delete()
            File(c.applicationContext.filesDir, FLAG).delete()
        } catch (_: Exception) {}
    }

    /** True once after the app died from an uncaught exception, so the UI can point the user to the log. */
    fun consumeCrashFlag(c: Context): Boolean {
        val f = File(c.applicationContext.filesDir, FLAG)
        val had = f.exists()
        if (had) f.delete()
        return had
    }
}
