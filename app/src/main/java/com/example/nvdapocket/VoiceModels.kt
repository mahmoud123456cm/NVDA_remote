package com.example.nvdapocket

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** The offline speech models (Vosk) used for silent wake-word listening: where they come from, and download/unpack/delete. */
object VoiceModels {
    class Info(val lang: String, val label: String, val url: String, val dir: String, val mb: Int)

    val all = listOf(
        Info("en", "English", "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip", "vosk-model-small-en-us-0.15", 40),
        Info("fr", "Français", "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip", "vosk-model-small-fr-0.22", 41),
        Info("ar", "العربية (Tunisian)", "https://alphacephei.com/vosk/models/vosk-model-small-ar-tn-0.1-linto.zip", "vosk-model-small-ar-tn-0.1-linto", 158))

    fun forLang(l: String): Info = all.firstOrNull { it.lang == l } ?: all[0]

    private fun root(c: Context) = File(c.applicationContext.filesDir, "vosk")
    fun dir(c: Context, i: Info) = File(root(c), i.dir)

    fun installed(c: Context, lang: String): Boolean {
        val d = dir(c, forLang(lang))
        return File(d, "am").isDirectory && File(d, "conf").isDirectory
    }

    fun delete(c: Context, lang: String) { try { dir(c, forLang(lang)).deleteRecursively() } catch (_: Exception) {} }

    class Task { @Volatile var cancelled = false; fun cancel() { cancelled = true } }

    /** Downloads and unpacks a model on its own thread. progress gets a readable line, done gets null on success or the error text. Both are called from that thread. */
    fun download(c: Context, info: Info, progress: (String) -> Unit, done: (String?) -> Unit): Task {
        val app = c.applicationContext
        val t = Task()
        Thread {
            var err: String? = null
            val tmp = File(app.cacheDir, "voskmodel.zip")
            val staging = File(root(app), info.dir + ".part")
            try {
                root(app).mkdirs()
                val conn = URL(info.url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.instanceFollowRedirects = true
                if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode} from ${info.url}")
                val total = conn.contentLengthLong
                conn.inputStream.use { ins ->
                    FileOutputStream(tmp).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var got = 0L
                        var lastUi = 0L
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            if (t.cancelled) throw IOException("Cancelled")
                            out.write(buf, 0, n)
                            got += n
                            val now = System.currentTimeMillis()
                            if (now - lastUi > 500) {
                                lastUi = now
                                progress("Downloading: " + (got / 1_000_000) + (if (total > 0) " of " + (total / 1_000_000) else "") + " MB")
                            }
                        }
                    }
                }
                progress("Unpacking...")
                staging.deleteRecursively()
                staging.mkdirs()
                val base = staging.canonicalPath + File.separator
                ZipInputStream(BufferedInputStream(FileInputStream(tmp))).use { zin ->
                    var e = zin.nextEntry
                    while (e != null) {
                        if (t.cancelled) throw IOException("Cancelled")
                        val out = File(staging, e.name)
                        if (!out.canonicalPath.startsWith(base)) throw IOException("The download contains an unsafe file name")
                        if (e.isDirectory) out.mkdirs()
                        else { out.parentFile?.mkdirs(); FileOutputStream(out).use { zin.copyTo(it) } }
                        zin.closeEntry()
                        e = zin.nextEntry
                    }
                }
                val inner = File(staging, info.dir)           // the zip holds one folder named like the model
                val src = if (inner.isDirectory) inner else staging
                val target = dir(app, info)
                target.deleteRecursively()
                if (!src.renameTo(target)) throw IOException("Could not move the model into place")
                staging.deleteRecursively()
                if (!installed(app, info.lang)) throw IOException("The download does not look like a voice model")
            } catch (e: Exception) {
                err = e.message ?: e.javaClass.simpleName
                if (!t.cancelled) CrashLog.log("model download", e)
                try { staging.deleteRecursively() } catch (_: Exception) {}
            } finally {
                try { tmp.delete() } catch (_: Exception) {}
            }
            done(err)
        }.start()
        return t
    }
}
