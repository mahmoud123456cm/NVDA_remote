package com.example.nvdapocket

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
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
    @Volatile var peers = 0            // controlled PCs seen in the channel
    @Volatile var recording: MutableList<String>? = null
    @Volatile private var generation = 0
    @Volatile var clipIn = true        // copy text the PC puts on its clipboard to this phone automatically
    @Volatile var lastPcClip: String? = null
    var clipListener: ((String) -> Unit)? = null
    @Volatile private var lastSentClip: String? = null
    private const val MAX_CLIP = 200_000
    var ttsRate = 1f
    var ttsPitch = 1f
    private var appCtx: Context? = null
    private var socket: SSLSocket? = null
    private var writer: PrintWriter? = null
    private val sender = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var base = "Not connected"
    var lastStatus = "Not connected"
    var statusListener: ((String) -> Unit)? = null
    var serviceListener: ((String) -> Unit)? = null
    val held = mutableSetOf<Int>()

    // ---------- status ----------
    private fun compose(): String =
        if (connected) "Connected | " + (if (peers > 0) "PC online" else "waiting for PC") + (if (!speak) " | speech muted" else "")
        else base

    fun publish() {
        val t = compose()
        lastStatus = t
        ui.post { statusListener?.invoke(t); serviceListener?.invoke(t) }
    }

    fun setStatus(s: String) { base = s; publish() }

    // ---------- speech / feedback ----------
    fun initTts(ctx: Context) {
        val app = ctx.applicationContext
        appCtx = app
        clipIn = Store.prefs(app).getBoolean("clipIn", true)
        if (tts == null) tts = TextToSpeech(app) { st -> ttsReady = st == TextToSpeech.SUCCESS; if (ttsReady) applyTts() }
    }

    fun applyTts() { tts?.setSpeechRate(ttsRate); tts?.setPitch(ttsPitch) }

    fun applySpeak(b: Boolean) { speak = b; if (!b) tts?.stop(); publish() }
    fun toggleMute() { applySpeak(!speak); vibrate(60) }

    fun say(msg: String) { if (ttsReady) tts?.speak(msg, TextToSpeech.QUEUE_ADD, null, "local") }

    fun vibrate(ms: Long) {
        val c = appCtx ?: return
        val v = c.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        else v.vibrate(ms)
    }

    // ---------- connection ----------
    fun start(ctx: Context, host: String, port: Int, chan: String, trust: Boolean) {
        initTts(ctx)
        val app = ctx.applicationContext
        closeSocket()
        active = true
        peers = 0
        generation++
        val gen = generation
        val svc = Intent(app, RemoteService::class.java)
        if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(svc) else app.startService(svc)
        Thread {
            while (active && gen == generation) {
                try {
                    connectOnce(host, port, chan, trust)
                } catch (e: Exception) {
                    if (active && gen == generation) { setStatus("Error: ${e.message}"); CrashLog.log("connect", e) }
                }
                connected = false
                peers = 0
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
        peers = 0
        cancelCapture()
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
        publish()
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
                if (t.isNotEmpty() && addToCapture(t)) return   // being captured as clipboard text: don't read it aloud
                if (t.isNotEmpty() && speak && ttsReady) tts?.speak(t, TextToSpeech.QUEUE_ADD, null, null)
            }
            "set_clipboard_text" -> {
                val t = m.optString("text", "")
                if (t.isNotEmpty() && t != lastSentClip) ui.post { onPcClipboard(t, false) }
            }
            "cancel" -> if (ttsReady) tts?.stop()
            "channel_joined" -> {
                var n = 0
                val cl = m.optJSONArray("clients")
                if (cl != null) for (i in 0 until cl.length()) {
                    if (cl.optJSONObject(i)?.optString("connection_type") == "slave") n++
                }
                peers = n
                publish()
            }
            "client_joined" -> {
                val ct = m.optJSONObject("client")?.optString("connection_type")
                if (ct == null || ct == "slave") { peers++; vibrate(40) }
                publish()
            }
            "client_left" -> {
                val ct = m.optJSONObject("client")?.optString("connection_type")
                if (ct == null || ct == "slave") {
                    peers = maxOf(0, peers - 1)
                    if (peers == 0) { vibrate(150); say("PC disconnected") }
                }
                publish()
            }
        }
    }

    // ---------- PC clipboard -> phone ----------
    /** Puts text on the phone's clipboard. Must run on the main thread. */
    fun copyToPhone(text: String): Boolean {
        val c = appCtx ?: return false
        return try {
            val cm = c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("NVDA Pocket", text))
            true
        } catch (e: Exception) { CrashLog.log("clipboard", e); false }
    }

    /** Copies the last text received from the PC to the phone clipboard again. */
    fun recopyLastPcClip(): Boolean {
        val t = lastPcClip
        if (t == null) { say("No clipboard text received from the PC yet"); vibrate(150); return false }
        val ok = copyToPhone(t)
        if (ok) { vibrate(40); say("Copied to the phone clipboard") } else vibrate(150)
        return ok
    }

    /** explicit = the user asked for it, so it is copied even when automatic copying is switched off. */
    private fun onPcClipboard(text: String, explicit: Boolean) {
        if (text.length > MAX_CLIP) { vibrate(150); say("The PC clipboard text is too large to copy"); return }
        lastPcClip = text
        clipListener?.invoke(text)
        if (explicit || clipIn) {
            if (copyToPhone(text)) { vibrate(40); say("Copied from the PC, ${text.length} characters") } else vibrate(150)
        } else {
            vibrate(40); say("PC clipboard received. Not copied")
        }
    }

    // Fallback that works with the stock NVDA Remote add-on, which does not send its clipboard to the controlling side:
    // ask NVDA to read the clipboard aloud (NVDA+C) and collect the speech it sends us. The text is what NVDA
    // speaks, so symbols and line breaks can differ a little from the original.
    private val capLock = Any()
    private var capture: StringBuilder? = null
    private var captureLast = 0L
    private var captureStart = 0L
    @Volatile private var captureToken = 0

    /** copySelectionFirst = press Ctrl+C on the PC first, so the current selection is what comes back. */
    fun fetchPcClipboard(copySelectionFirst: Boolean) {
        if (!connected) { vibrate(150); say("Not connected"); return }
        val token = ++captureToken
        if (copySelectionFirst) combo(0x11, 0x43)
        ui.postDelayed({ beginCapture(token) }, if (copySelectionFirst) 350L else 0L)
        say(if (copySelectionFirst) "Copying the selection" else "Getting the PC clipboard")
    }

    private fun beginCapture(token: Int) {
        if (token != captureToken || !connected) return
        synchronized(capLock) { capture = StringBuilder(); captureStart = SystemClock.uptimeMillis(); captureLast = 0L }
        sendKey(0x2D, true, true); tap(0x43); sendKey(0x2D, true, false)   // NVDA+C
        ui.postDelayed({ pollCapture(token) }, 400)
    }

    /** Called for every piece of PC speech. Returns true when the speech was taken as clipboard text. */
    private fun addToCapture(t: String): Boolean = synchronized(capLock) {
        val c = capture ?: return@synchronized false
        if (c.isNotEmpty()) c.append(' ')
        c.append(t)
        captureLast = SystemClock.uptimeMillis()
        true
    }

    private fun pollCapture(token: Int) {
        if (token != captureToken) return
        val now = SystemClock.uptimeMillis()
        val (done, text) = synchronized(capLock) {
            val c = capture
            val got = c != null && c.isNotEmpty()
            val finished = (got && now - captureLast > 1000) || now - captureStart > 15000
            val result = if (finished) c?.toString()?.trim() else null
            if (finished) capture = null
            Pair(finished, result)
        }
        if (!done) { ui.postDelayed({ pollCapture(token) }, 300); return }
        if (text.isNullOrEmpty()) { vibrate(150); say("Nothing came back from the PC"); return }
        if (text.contains("no text on the clipboard", ignoreCase = true)) { vibrate(150); say("The PC clipboard has no text"); return }
        onPcClipboard(text, true)
    }

    private fun cancelCapture() {
        captureToken++
        synchronized(capLock) { capture = null }
    }

    // ---------- sending ----------
    fun sendJson(o: JSONObject) {
        if (!connected) return
        sender.execute { try { writer?.println(o.toString()); writer?.flush() } catch (_: Exception) {} }
    }

    fun sendKey(vk: Int, ext: Boolean, pressed: Boolean) {
        recording?.let { r -> synchronized(r) { r.add("$vk,${if (ext) 1 else 0},${if (pressed) 1 else 0}") } }
        sendJson(JSONObject().put("type", "key").put("vk_code", vk).put("extended", ext).put("pressed", pressed))
    }

    fun tap(vk: Int, ext: Boolean = false) { sendKey(vk, ext, true); sendKey(vk, ext, false) }
    fun combo(mod: Int, vk: Int, ext: Boolean = false) { sendKey(mod, false, true); tap(vk, ext); sendKey(mod, false, false) }

    fun pushClipboard(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        val t = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(ctx)?.toString() else null
        if (t.isNullOrEmpty()) { vibrate(150); return false }
        lastSentClip = t
        sendJson(JSONObject().put("type", "set_clipboard_text").put("text", t))
        vibrate(40)
        return true
    }

    /** Types the text as key presses. Returns how many characters could not be typed. */
    fun sendText(s: String): Int {
        var skipped = 0
        for (c in s) {
            val k = charToKey(c)
            if (k == null) { skipped++; continue }
            val (vk, shift) = k
            val needShift = shift && !held.contains(0x10)
            if (needShift) sendKey(0x10, false, true)
            tap(vk)
            if (needShift) sendKey(0x10, false, false)
        }
        return skipped
    }

    fun pushText(t: String) { lastSentClip = t; sendJson(JSONObject().put("type", "set_clipboard_text").put("text", t)) }

    /** mode: "clip" = PC clipboard only, "paste" = clipboard then Ctrl+V, anything else = type as keys. */
    fun deliver(mode: String, text: String): String {
        if (!connected) return "Not connected"
        return when (mode) {
            "clip" -> { pushText(text); vibrate(40); "Copied to the PC clipboard" }
            "paste" -> { pushText(text); ui.postDelayed({ combo(0x11, 0x56) }, 400); vibrate(40); "Pasting on the PC" }
            else -> {
                val skipped = sendText(text)
                if (skipped > 0) "Typed. $skipped characters could not be typed (use the clipboard for those)" else "Typed on the PC"
            }
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
