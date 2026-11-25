package com.example.nvdapocket

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/** Process-wide connection to the NVDA Remote relay. Survives the Activity being closed. */
object Remote {
    @Volatile var active = false       // user wants to be connected (auto-reconnects)
    @Volatile var connected = false    // socket currently up
    @Volatile var speak = true
    @Volatile private var generation = 0
    private var socket: SSLSocket? = null
    private var writer: PrintWriter? = null
    private val sender = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    var lastStatus = "Not connected"
    var statusListener: ((String) -> Unit)? = null
    val held = mutableSetOf<Int>()

    fun setStatus(s: String) { lastStatus = s; ui.post { statusListener?.invoke(s) } }

    fun start(ctx: Context, host: String, port: Int, chan: String, trust: Boolean) {
        val app = ctx.applicationContext
        if (tts == null) tts = TextToSpeech(app) { ttsReady = it == TextToSpeech.SUCCESS }
        closeSocket()
        active = true
        generation++
        val gen = generation
        val svc = Intent(app, RemoteService::class.java)
        if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(svc) else app.startService(svc)
        Thread {
            while (active && gen == generation) {
                try {
                    connectOnce(host, port, chan, trust)
                } catch (e: Exception) {
                    if (active && gen == generation) setStatus("Error: ${e.message}")
                }
                connected = false
                if (!(active && gen == generation)) break
                setStatus("Connection lost. Reconnecting in 5 seconds...")
                try { Thread.sleep(5000) } catch (_: InterruptedException) {}
            }
        }.start()
    }

    fun stop(ctx: Context) {
        active = false
        generation++
        connected = false
        closeSocket()
        ctx.applicationContext.stopService(Intent(ctx.applicationContext, RemoteService::class.java))
        tts?.stop()
        setStatus("Disconnected")
    }

    private fun closeSocket() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    private fun connectOnce(host: String, port: Int, chan: String, trust: Boolean) {
        setStatus("Connecting...")
        val factory: SSLSocketFactory = if (trust) {
            val tm = object : X509TrustManager {
                override fun checkClientTrusted(c: Array<X509Certificate>?, a: String?) {}
                override fun checkServerTrusted(c: Array<X509Certificate>?, a: String?) {}
                override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
            }
            SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }.socketFactory
        } else SSLSocketFactory.getDefault() as SSLSocketFactory
        val raw = Socket()
        raw.keepAlive = true
        raw.connect(InetSocketAddress(host, port), 10000)
        val s = factory.createSocket(raw, host, port, true) as SSLSocket
        s.startHandshake()
        socket = s
        writer = PrintWriter(s.getOutputStream(), true)
        connected = true
        sendJson(JSONObject().put("type", "protocol_version").put("version", 2))
        sendJson(JSONObject().put("type", "join").put("channel", chan).put("connection_type", "master"))
        setStatus("Connected, joined channel")
        val r = BufferedReader(InputStreamReader(s.getInputStream()))
        r.forEachLine { handle(it) }
    }

    private fun handle(line: String) {
        val m = try { JSONObject(line) } catch (_: Exception) { return }
        when (m.optString("type")) {
            "speak" -> {
                val seq = m.optJSONArray("sequence") ?: return
                val sb = StringBuilder()
                for (i in 0 until seq.length()) (seq.opt(i) as? String)?.let { if (it.isNotBlank()) sb.append(it).append(' ') }
                val t = sb.toString().trim()
                if (t.isNotEmpty() && speak && ttsReady) tts?.speak(t, TextToSpeech.QUEUE_ADD, null, null)
            }
            "cancel" -> if (ttsReady) tts?.stop()
            "channel_joined" -> setStatus("Connected, channel joined")
            "client_joined" -> setStatus("Connected, PC joined the channel")
            "client_left" -> setStatus("Connected, PC left the channel")
            "motd" -> setStatus("Connected: " + m.optString("motd"))
        }
    }

    // ---------- sending ----------
    fun sendJson(o: JSONObject) {
        if (!connected) return
        sender.execute { try { writer?.println(o.toString()); writer?.flush() } catch (_: Exception) {} }
    }
    fun sendKey(vk: Int, ext: Boolean, pressed: Boolean) =
        sendJson(JSONObject().put("type", "key").put("vk_code", vk).put("extended", ext).put("pressed", pressed))
    fun tap(vk: Int, ext: Boolean = false) { sendKey(vk, ext, true); sendKey(vk, ext, false) }
    fun combo(mod: Int, vk: Int, ext: Boolean = false) {
        sendKey(mod, false, true); tap(vk, ext); sendKey(mod, false, false)
    }

    fun sendText(s: String) {
        for (c in s) {
            val (vk, shift) = charToKey(c) ?: continue
            val needShift = shift && !held.contains(0x10)
            if (needShift) sendKey(0x10, false, true)
            tap(vk)
            if (needShift) sendKey(0x10, false, false)
        }
    }

    private fun charToKey(c: Char): Pair<Int, Boolean>? {
        if (c in 'a'..'z') return Pair(c.uppercaseChar().code, false)
        if (c in 'A'..'Z') return Pair(c.code, true)
        if (c in '0'..'9') return Pair(c.code, false)
        val shiftedDigits = ")!@#$%^&*("
        val i = shiftedDigits.indexOf(c); if (i >= 0) return Pair('0'.code + i, true)
        return when (c) {
            ' ' -> Pair(0x20, false); '\n' -> Pair(0x0D, false); '\t' -> Pair(0x09, false)
            '-' -> Pair(0xBD, false); '_' -> Pair(0xBD, true)
            '=' -> Pair(0xBB, false); '+' -> Pair(0xBB, true)
            '[' -> Pair(0xDB, false); '{' -> Pair(0xDB, true)
            ']' -> Pair(0xDD, false); '}' -> Pair(0xDD, true)
            '\\' -> Pair(0xDC, false); '|' -> Pair(0xDC, true)
            ';' -> Pair(0xBA, false); ':' -> Pair(0xBA, true)
            '\'' -> Pair(0xDE, false); '"' -> Pair(0xDE, true)
            ',' -> Pair(0xBC, false); '<' -> Pair(0xBC, true)
            '.' -> Pair(0xBE, false); '>' -> Pair(0xBE, true)
            '/' -> Pair(0xBF, false); '?' -> Pair(0xBF, true)
            '`' -> Pair(0xC0, false); '~' -> Pair(0xC0, true)
            else -> null
        }
    }
}
