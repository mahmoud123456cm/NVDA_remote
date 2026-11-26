package com.example.nvdapocket

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
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
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
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

    // reconnecting, keep-alive and network watching
    private val reconnectLock = Object()
    @Volatile private var retryNow = false
    @Volatile private var attempts = 0
    @Volatile private var hadLoss = false          // the connection was up and then dropped, so the next success is a "restore"
    @Volatile private var lastRx = 0L
    @Volatile private var serverTalks = false      // the relay has sent us a ping/pong, so silence from it means trouble
    private var netCb: ConnectivityManager.NetworkCallback? = null
    @Volatile private var currentNet: Network? = null
    private const val PING_MS = 25_000L
    private const val WATCHDOG_MS = 90_000L

    private class CertChangedException(msg: String) : CertificateException(msg)

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
    fun toggleMute() { applySpeak(!speak); vibrate(60); Cue.play(if (speak) "unmuted" else "muted") }

    /** Speaks on the phone (used for the gesture practice mode). flush = cut off anything still being spoken. */
    fun say(msg: String, flush: Boolean = false) {
        if (ttsReady) tts?.speak(msg, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "local")
    }

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
        hadLoss = false
        attempts = 0
        generation++
        val gen = generation
        watchNetwork(app)
        ConnLog.add("Connect requested: $host:$port")
        val svc = Intent(app, RemoteService::class.java)
        if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(svc) else app.startService(svc)
        Thread {
            var lastError: String? = null
            while (active && gen == generation) {
                retryNow = false
                var certProblem = false
                try {
                    connectOnce(host, port, chan, trust)
                    lastError = null
                } catch (e: Exception) {
                    if (active && gen == generation) {
                        if (isCertChanged(e)) certProblem = true
                        else {
                            lastError = e.message ?: e.javaClass.simpleName
                            ConnLog.add("Connection error: $lastError")
                            CrashLog.log("connect", e)
                        }
                    }
                }
                val wasUp = connected
                connected = false
                peers = 0
                if (!(active && gen == generation)) break
                if (certProblem) {
                    ConnLog.add("The server's certificate is different from the remembered one. Stopped.")
                    Cue.play("cert_changed")
                    haltWith("The server certificate changed. If that is expected, press Forget certificate on the Connect tab, then connect again.")
                    break
                }
                if (wasUp) {
                    hadLoss = true
                    attempts = 0
                    ConnLog.add("Connection lost")
                    Cue.play("connection_lost")
                }
                attempts++
                val delaySec = minOf(30, 2 shl minOf(attempts - 1, 4))   // 2, 4, 8, 16, 30, 30...
                var left = delaySec
                while (left > 0 && active && gen == generation && !retryNow) {
                    setStatus("Connection lost. Reconnecting in $left s (attempt $attempts)" + (lastError?.let { ": $it" } ?: ""))
                    synchronized(reconnectLock) {
                        if (!retryNow) try { reconnectLock.wait(1000) } catch (_: InterruptedException) {}
                    }
                    left--
                }
                if (active && gen == generation) ConnLog.add("Reconnecting (attempt $attempts)")
            }
        }.start()
    }

    fun stop(ctx: Context) {
        val wasActive = active
        active = false
        generation++
        connected = false
        peers = 0
        cancelCapture()
        unwatchNetwork(ctx.applicationContext)
        closeSocket()
        ctx.applicationContext.stopService(Intent(ctx.applicationContext, RemoteService::class.java))
        tts?.stop()
        setStatus("Disconnected")
        if (wasActive) { ConnLog.add("Disconnected"); Cue.play("disconnected") }
    }

    /** Stops for good after a problem that retrying cannot fix. */
    private fun haltWith(msg: String) {
        active = false
        generation++
        connected = false
        peers = 0
        val c = appCtx
        if (c != null) {
            unwatchNetwork(c)
            try { c.stopService(Intent(c, RemoteService::class.java)) } catch (_: Exception) {}
        }
        closeSocket()
        setStatus(msg)
    }

    private fun isCertChanged(e: Throwable): Boolean {
        var t: Throwable? = e
        var n = 0
        while (t != null && n++ < 8) { if (t is CertChangedException) return true; t = t.cause }
        return false
    }

    private fun closeSocket() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    /** Wakes the reconnect loop so it tries again at once (used when the network comes back). */
    private fun kick() {
        if (connected) return
        synchronized(reconnectLock) { retryNow = true; reconnectLock.notifyAll() }
    }

    // ---------- watching the network ----------
    private fun watchNetwork(app: Context) {
        unwatchNetwork(app)
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) {
                val prev = currentNet
                currentNet = n
                if (prev != null && prev != n) {
                    ConnLog.add("Network changed")
                    if (connected) { ConnLog.add("Dropping the connection to use the new network"); closeSocket() }
                } else if (prev == null) ConnLog.add("Network available")
                kick()
            }
            override fun onLost(n: Network) {
                if (currentNet == n) {
                    currentNet = null
                    ConnLog.add("Network lost")
                    if (connected) closeSocket()
                }
            }
        }
        try { cm.registerDefaultNetworkCallback(cb); netCb = cb } catch (e: Exception) { CrashLog.log("network", e) }
    }

    private fun unwatchNetwork(app: Context) {
        val cb = netCb ?: return
        netCb = null
        currentNet = null
        try { (app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.unregisterNetworkCallback(cb) } catch (_: Exception) {}
    }

    // ---------- certificate: remember on first use, refuse if it changes ----------
    private fun fingerprint(c: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(c.encoded).joinToString(":") { "%02X".format(it) }

    private fun pinningFactory(host: String, port: Int): SSLSocketFactory {
        val id = "$host:$port"
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>?, a: String?) {}
            override fun checkServerTrusted(c: Array<X509Certificate>?, a: String?) {
                val leaf = c?.firstOrNull() ?: throw CertificateException("The server sent no certificate")
                val fp = fingerprint(leaf)
                val ctx = appCtx ?: return
                val pin = Store.pin(ctx, id)
                when {
                    pin == null -> { Store.setPin(ctx, id, fp); ConnLog.add("Remembered the certificate of $id (${fp.take(23)}...)") }
                    pin == fp -> {}
                    else -> throw CertChangedException("The certificate of $id changed")
                }
            }
            override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
        }
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }.socketFactory
    }

    private fun connectOnce(host: String, port: Int, chan: String, trust: Boolean) {
        setStatus("Connecting...")
        ConnLog.add("Connecting to $host:$port")
        val factory: SSLSocketFactory = if (trust) pinningFactory(host, port) else SSLSocketFactory.getDefault() as SSLSocketFactory
        val raw = Socket()
        raw.keepAlive = true
        raw.connect(InetSocketAddress(host, port), 10000)
        val s = factory.createSocket(raw, host, port, true) as SSLSocket
        s.startHandshake()
        socket = s
        writer = PrintWriter(s.getOutputStream(), true)
        lastRx = SystemClock.elapsedRealtime()
        serverTalks = false
        connected = true
        attempts = 0
        sendJson(JSONObject().put("type", "protocol_version").put("version", 2))
        sendJson(JSONObject().put("type", "join").put("channel", chan).put("connection_type", "master"))
        if (hadLoss) { hadLoss = false; ConnLog.add("Connection restored"); Cue.play("connection_restored") }
        else { ConnLog.add("Connected"); Cue.play("connected") }
        publish()
        startKeepAlive(s)
        val r = BufferedReader(InputStreamReader(s.getInputStream()))
        r.forEachLine { lastRx = SystemClock.elapsedRealtime(); handle(it) }
        if (active) ConnLog.add("The server closed the connection")
    }

    /** Sends a small ping now and then and drops the connection when the relay has gone silent. */
    private fun startKeepAlive(s: SSLSocket) {
        Thread {
            while (socket === s && connected && active) {
                try { Thread.sleep(PING_MS) } catch (_: InterruptedException) { return@Thread }
                if (socket !== s || !connected) return@Thread
                val idle = SystemClock.elapsedRealtime() - lastRx
                if (serverTalks && idle > WATCHDOG_MS) {
                    ConnLog.add("Nothing from the server for ${idle / 1000} s, reconnecting")
                    try { s.close() } catch (_: Exception) {}
                    return@Thread
                }
                val c = appCtx
                if (c != null && !Store.prefs(c).getBoolean("ping", true)) continue
                sender.execute {
                    val w = writer
                    if (socket === s && w != null) {
                        try {
                            w.println("{\"type\":\"ping\"}"); w.flush()
                            if (w.checkError()) {
                                ConnLog.add("The keep-alive could not be sent, reconnecting")
                                try { s.close() } catch (_: Exception) {}
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
        }.apply { isDaemon = true; start() }
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
            "ping", "pong" -> serverTalks = true
            "channel_joined" -> {
                var n = 0
                val cl = m.optJSONArray("clients")
                if (cl != null) for (i in 0 until cl.length()) {
                    if (cl.optJSONObject(i)?.optString("connection_type") == "slave") n++
                }
                peers = n
                if (n > 0) Cue.play("pc_joined")
                publish()
            }
            "client_joined" -> {
                val ct = m.optJSONObject("client")?.optString("connection_type")
                if (ct == null || ct == "slave") { peers++; vibrate(40); ConnLog.add("PC joined the channel"); Cue.play("pc_joined") }
                publish()
            }
            "client_left" -> {
                val ct = m.optJSONObject("client")?.optString("connection_type")
                if (ct == null || ct == "slave") {
                    peers = maxOf(0, peers - 1)
                    ConnLog.add("PC left the channel")
                    if (peers == 0) { vibrate(150); Cue.play("pc_left") }
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
        if (t == null) { Cue.play("clip_none"); vibrate(150); return false }
        val ok = copyToPhone(t)
        if (ok) { vibrate(40); Cue.play("clip_to_phone") } else vibrate(150)
        return ok
    }

    /** explicit = the user asked for it, so it is copied even when automatic copying is switched off. */
    private fun onPcClipboard(text: String, explicit: Boolean) {
        if (text.length > MAX_CLIP) { vibrate(150); Cue.play("clip_too_big"); return }
        lastPcClip = text
        clipListener?.invoke(text)
        if (explicit || clipIn) {
            if (copyToPhone(text)) { vibrate(40); Cue.play("clip_to_phone") } else vibrate(150)
        } else {
            vibrate(40); Cue.play("clip_not_copied")
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
        if (!connected) { vibrate(150); Cue.play("not_connected"); return }
        val token = ++captureToken
        if (copySelectionFirst) combo(0x11, 0x43)
        ui.postDelayed({ beginCapture(token) }, if (copySelectionFirst) 350L else 0L)
        Cue.play(if (copySelectionFirst) "clip_selection" else "clip_getting")
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
        if (text.isNullOrEmpty()) { vibrate(150); Cue.play("clip_nothing"); return }
        if (text.contains("no text on the clipboard", ignoreCase = true)) { vibrate(150); Cue.play("clip_empty"); return }
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
        if (t.isNullOrEmpty()) { vibrate(150); Cue.play("clip_empty"); return false }
        if (!connected) { vibrate(150); Cue.play("not_connected"); return false }
        lastSentClip = t
        sendJson(JSONObject().put("type", "set_clipboard_text").put("text", t))
        vibrate(40)
        Cue.play("clip_sent")
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
        if (!connected) { Cue.play("not_connected"); return "Not connected" }
        return when (mode) {
            "clip" -> { pushText(text); vibrate(40); Cue.play("to_pc_clip"); "Copied to the PC clipboard" }
            "paste" -> { pushText(text); ui.postDelayed({ combo(0x11, 0x56) }, 400); vibrate(40); Cue.play("pasting"); "Pasting on the PC" }
            else -> {
                val skipped = sendText(text)
                Cue.play("typed")
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
