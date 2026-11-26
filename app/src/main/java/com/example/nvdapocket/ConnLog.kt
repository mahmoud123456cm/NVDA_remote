package com.example.nvdapocket

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The last 200 connection events (connected, lost, reconnecting...) with times, kept in a small file. */
object ConnLog {
    private const val MAX = 200
    private val lines = ArrayDeque<String>()
    private var file: File? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    @Synchronized fun init(c: Context) {
        if (file != null) return
        val f = File(c.applicationContext.filesDir, "connlog.txt")
        file = f
        try { if (f.exists()) f.readLines().takeLast(MAX).forEach { lines.addLast(it) } } catch (_: Exception) {}
    }

    @Synchronized fun add(msg: String) {
        lines.addLast(fmt.format(Date()) + "  " + msg)
        while (lines.size > MAX) lines.removeFirst()
        try { file?.writeText(lines.joinToString("\n")) } catch (_: Exception) {}
    }

    @Synchronized fun read(): String = lines.joinToString("\n")

    @Synchronized fun clear() {
        lines.clear()
        try { file?.writeText("") } catch (_: Exception) {}
    }
}
