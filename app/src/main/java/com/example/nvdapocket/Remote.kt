package com.example.nvdapocket

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
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
    @Volatile private var pending: Socket? = null   // a socket still in TCP connect / TLS handshake, so stop() can cut it short
    private var writer: PrintWriter? = null
    private val sender = Executors.newSingleThreadExecutor()
    private val speaker = Executors.newSingleThreadExecutor()          // speech runs here so a slow engine can never stall reading from the server
    private val pendingSpeech = java.util.concurrent.atomic.AtomicInteger(0)
    private const val MAX_SPEECH_BACKLOG = 5                            // beyond this, old queued speech is dropped so you hear the latest text
    private val ui = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    @Volatile private var ttsGen = 0            // bumps each time the speech engine is recreated, so a late start-up callback of an old one is ignored
    private var ttsEngine: String? = null       // package name of the chosen engine, null = the system default
    private var ttsVoiceName: String? = null    // chosen voice, null = the engine's default
    /** Called on the main thread when a (new) speech engine has finished starting. */
    var ttsListener: (() -> Unit)? = null
    private var base = "Not connected"
    var lastStatus = "Not connected"
    var statusListener: ((String) -> Unit)? = null
    var serviceListener: ((String) -> Unit)? = null
    val held = mutableSetOf<Int>()                       // modifier keys toggled on in the Control tab; always access under synchronized(held)
    private val staleHeld = mutableSetOf<Int>()           // were held when the link dropped: released on the PC as soon as we are back
    var heldListener: (() -> Unit)? = null               // lets the Control tab untick its toggles when keys are released

    private fun isExtMod(vk: Int) = vk == 0x5B || vk == 0x2D    // Windows key and Insert/NVDA are "extended" keys

    fun setHeld(vk: Int, on: Boolean) { synchronized(held) { if (on) held.add(vk) else held.remove(vk) } }
    fun isHeld(vk: Int): Boolean = synchronized(held) { vk in held }

    private fun rawKeyUp(vk: Int) =
        sendJson(JSONObject().put("type", "key").put("vk_code", vk).put("extended", isExtMod(vk)).put("pressed", false))

    /**
     * Lets go of every toggled-on modifier. sendUps = tell the PC now (the link is up). When the link is down the keys are
     * remembered and released on the PC right after the next connect, so Ctrl/Shift/Alt/Win/NVDA can never stay stuck there.
     */
    fun releaseHeld(sendUps: Boolean) {
        val snap = synchronized(held) { val l = held.toList(); held.clear(); l }
        if (snap.isEmpty()) return
        if (sendUps && connected) snap.forEach { rawKeyUp(it) }
        else synchronized(staleHeld) { staleHeld.addAll(snap) }
        ConnLog.add("Released held keys (" + snap.joinToString(", ") { "0x" + Integer.toHexString(it) } + ")")
        ui.post { heldListener?.invoke() }
    }

    private fun flushStale() {
        val snap = synchronized(staleHeld) { val l = staleHeld.toList(); staleHeld.clear(); l }
        if (snap.isNotEmpty()) { snap.forEach { rawKeyUp(it) }; ConnLog.add("Released keys that were left held before the connection dropped") }
    }

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
    private const val ECHO_MS = 15_000L          // a ping/pong this soon after our ping counts as the relay answering us
    private const val ECHOES_NEEDED = 3          // the relay must have answered this many pings before silence is treated as a dead link
    @Volatile private var pingSentAt = 0L
    @Volatile private var echoes = 0             // pings the relay has answered quickly on this connection
    @Volatile private var connectedAt = 0L

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
        val p = Store.prefs(app)
        clipIn = p.getBoolean("clipIn", true)
        if (tts == null) {
            ttsEngine = p.getString("ttsEngine", null)?.ifEmpty { null }
            ttsVoiceName = p.getString("ttsVoice", null)?.ifEmpty { null }
            createTts()
        }
    }

    /** (Re)creates the speech engine from the saved choice. This is what makes a new engine or voice take effect. */
    private fun createTts() {
        val app = appCtx ?: return
        val g = ++ttsGen
        ttsReady = false
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        tts = null
        val listener = TextToSpeech.OnInitListener { st ->
            if (g == ttsGen) {
                ttsReady = st == TextToSpeech.SUCCESS
                if (ttsReady) {
                    applyTts(); applyVoice()
                    try {
                        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            private fun done(id: String?) { if (id != null && id.startsWith("pc") && pendingSpeech.get() > 0) pendingSpeech.decrementAndGet() }
                            override fun onStart(utteranceId: String?) {}
                            override fun onDone(utteranceId: String?) = done(utteranceId)
                            @Deprecated("Deprecated in Java")
                            override fun onError(utteranceId: String?) = done(utteranceId)
                            override fun onError(utteranceId: String?, errorCode: Int) = done(utteranceId)
                            override fun onStop(utteranceId: String?, interrupted: Boolean) = done(utteranceId)
                        })
                    } catch (e: Exception) { CrashLog.log("tts listener", e) }
                    ui.post { ttsListener?.invoke() }
                }
                else if (ttsEngine != null) ui.post { fallBackToDefaultEngine() }   // the chosen engine would not start
            }
        }
        val eng = ttsEngine
        tts = try {
            if (eng == null) TextToSpeech(app, listener) else TextToSpeech(app, listener, eng)
        } catch (e: Exception) { CrashLog.log("tts", e); null }
    }

    private fun fallBackToDefaultEngine() {
        val c = appCtx ?: return
        ConnLog.add("The chosen speech engine would not start, using the system default")
        ttsEngine = null; ttsVoiceName = null
        Store.prefs(c).edit().remove("ttsEngine").remove("ttsVoice").apply()
        createTts()
    }

    private fun applyVoice() {
        val name = ttsVoiceName ?: return
        try { tts?.voices?.firstOrNull { it.name == name }?.let { tts?.setVoice(it) } } catch (e: Exception) { CrashLog.log("tts voice", e) }
    }

    /** True while the phone is speaking the PC's text. */
    fun ttsBusy(): Boolean = try { tts?.isSpeaking == true } catch (_: Exception) { false }

    fun applyTts() { tts?.setSpeechRate(ttsRate); tts?.setPitch(ttsPitch) }

    // ----- choosing the speech engine and voice (Settings tab) -----
    fun ttsIsReady() = ttsReady
    fun ttsEngineName(): String? = ttsEngine
    fun ttsVoiceName(): String? = ttsVoiceName

    /** Installed speech engines as (package, label). */
    fun ttsEngines(): List<Pair<String, String>> = try {
        tts?.engines?.map { it.name to it.label } ?: emptyList()
    } catch (e: Exception) { CrashLog.log("tts engines", e); emptyList() }

    fun currentEngineLabel(): String {
        val e = ttsEngine ?: try { tts?.defaultEngine } catch (_: Exception) { null }
        return ttsEngines().firstOrNull { it.first == e }?.second ?: (e ?: "System default")
    }

    /** null = the system default engine. */
    fun setEngine(pkg: String?) {
        val c = appCtx ?: return
        ttsEngine = pkg; ttsVoiceName = null
        val ed = Store.prefs(c).edit().remove("ttsVoice")
        if (pkg == null) ed.remove("ttsEngine") else ed.putString("ttsEngine", pkg)
        ed.apply()
        createTts()
    }

    /** The voices of the running engine (empty until it has finished starting). */
    fun ttsVoices(): List<android.speech.tts.Voice> = try {
        tts?.voices?.sortedWith(compareBy({ it.locale.displayName }, { it.name })) ?: emptyList()
    } catch (e: Exception) { CrashLog.log("tts voices", e); emptyList() }

    /** null = the engine's default voice. */
    fun setVoice(name: String?) {
        val c = appCtx ?: return
        ttsVoiceName = name
        val ed = Store.prefs(c).edit()
        if (name == null) ed.remove("ttsVoice") else ed.putString("ttsVoice", name)
        ed.apply()
        if (name == null) createTts() else applyVoice()   // there is no way to unset a voice, so go back to the engine default by restarting it
    }

    /** Restarts the speech engine, for example after the user changed the default in Android's own settings. */
    fun reinitTts() { createTts() }

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
        releaseHeld(true)
        closeSocketDeferred()
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
                    connectOnce(host, port, chan, trust, gen)
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
                if (!(active && gen == generation)) break     // a newer connection owns the shared state now: do not touch it
                val wasUp = connected
                connected = false
                peers = 0
                if (certProblem) {
                    ConnLog.add("The server's certificate is different from the remembered one. Stopped.")
                    Cue.play("cert_changed")
                    haltWith("The server certificate changed. If that is expected, press Forget certificate on the Connect tab, then connect again.")
                    break
                }
                if (wasUp) {
                    releaseHeld(false)
                    hadLoss = true
                    attempts = 0
                    ConnLog.add("Connection lost after ${(SystemClock.elapsedRealtime() - connectedAt) / 1000} s" + (lastError?.let { " ($it)" } ?: ""))
                    Cue.play("connection_lost")
                }
                attempts++
                val delaySec = if (attempts == 1) 1 else minOf(15, 2 shl minOf(attempts - 2, 3))   // 1, 2, 4, 8, 15, 15... (each retry also tries another IP / network)
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
        releaseHeld(true)                 // while the link is still up
        active = false
        generation++
        connected = false
        peers = 0
        cancelCapture()
        unwatchNetwork(ctx.applicationContext)
        closeSocketDeferred()
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

    /** Closes the connection after everything already queued for sending (for example key releases) has gone out. */
    private fun closeSocketDeferred() {
        val s = socket
        val pend = pending
        socket = null
        pending = null
        try { pend?.close() } catch (_: Exception) {}
        sender.execute { try { s?.close() } catch (_: Exception) {} }
    }

    private fun closeSocket() {
        try { pending?.close() } catch (_: Exception) {}
        pending = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    /** Wakes the reconnect loop so it tries again at once (used when the network comes back). */
    private fun kick() {
        if (connected) return
        synchronized(reconnectLock) { retryNow = true; reconnectLock.notifyAll() }
    }

    /** Sends a ping now and drops the connection if nothing at all arrives within 8 seconds. */
    private fun probeConnection() {
        val s = socket ?: return
        val before = lastRx
        sendPing(s)
        Thread {
            try { Thread.sleep(8000) } catch (_: InterruptedException) { return@Thread }
            if (socket === s && connected && lastRx == before) {
                ConnLog.add("No answer after the network change, reconnecting")
                try { s.close() } catch (_: Exception) {}
            }
        }.apply { isDaemon = true; start() }
    }

    private fun sendPing(s: SSLSocket) {
        sender.execute {
            val w = writer
            if (socket === s && w != null) {
                try {
                    pingSentAt = SystemClock.elapsedRealtime()
                    w.println("{\"type\":\"ping\"}"); w.flush()
                    if (w.checkError()) {
                        ConnLog.add("The keep-alive could not be sent, reconnecting")
                        try { s.close() } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }
        }
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
                    if (connected) {
                        val oldAlive = try { cm.getNetworkCapabilities(prev) != null } catch (_: Exception) { false }
                        if (oldAlive && echoes >= ECHOES_NEEDED) probeConnection()   // old network still exists and the relay answers pings: just check the link
                        else { ConnLog.add("Dropping the connection to use the new network"); closeSocket() }
                    }
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

    private fun transportName(cm: ConnectivityManager?, n: Network?): String {
        if (n == null || cm == null) return "default network"
        val c = try { cm.getNetworkCapabilities(n) } catch (_: Exception) { null } ?: return "network"
        return when {
            c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "network"
        }
    }

    /** The default network first, then every other network that has internet, so a retry can leave a Wi-Fi that swallows the connection. */
    private fun candidateNetworks(cm: ConnectivityManager?): List<Network?> {
        if (cm == null) return listOf(null)
        val first: Network? = currentNet ?: try { cm.activeNetwork } catch (_: Exception) { null }
        val out = ArrayList<Network?>()
        out.add(first)
        try {
            for (n in cm.allNetworks) {
                if (n == first) continue
                val c = cm.getNetworkCapabilities(n) ?: continue
                if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) out.add(n)
            }
        } catch (e: Exception) { CrashLog.log("networks", e) }
        return out
    }

    /**
     * TCP connect + TLS handshake with short time limits. Tries the addresses of the host one by one (IPv4 and IPv6),
     * and on every failed round starts on the next network, because the log showed hours of "connected, then the
     * handshake never answered" on one network while another network worked at once.
     */
    private fun openSecure(host: String, port: Int, trust: Boolean, gen: Int, attempt: Int): SSLSocket {
        val cm = appCtx?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val nets = candidateNetworks(cm)
        val net = nets[attempt % nets.size]
        val netName = transportName(cm, net)
        val all = try { (if (net != null) net.getAllByName(host) else InetAddress.getAllByName(host)).toList() }
                  catch (e: Exception) { throw java.io.IOException("Cannot find $host on $netName (${e.message})", e) }
        // IPv4 and IPv6 alternate so one broken family can never block every try
        val v4 = all.filter { it is java.net.Inet4Address }
        val v6 = all.filter { it !is java.net.Inet4Address }
        val ordered = ArrayList<InetAddress>()
        for (i in 0 until maxOf(v4.size, v6.size)) { v4.getOrNull(i)?.let { ordered.add(it) }; v6.getOrNull(i)?.let { ordered.add(it) } }
        val start = (attempt / nets.size) % ordered.size
        val factory: SSLSocketFactory = if (trust) pinningFactory(host, port) else SSLSocketFactory.getDefault() as SSLSocketFactory
        val began = SystemClock.elapsedRealtime()
        var last: Exception? = null
        for (k in 0 until minOf(3, ordered.size)) {
            if (!(active && gen == generation)) throw java.io.IOException("Cancelled")
            if (k > 0 && SystemClock.elapsedRealtime() - began > 20_000) break
            val addr = ordered[(start + k) % ordered.size]
            val raw: Socket = net?.socketFactory?.createSocket() ?: Socket()
            if (!(active && gen == generation)) { try { raw.close() } catch (_: Exception) {}; throw java.io.IOException("Cancelled") }
            pending = raw
            try {
                raw.keepAlive = true
                raw.tcpNoDelay = true
                raw.connect(InetSocketAddress(addr, port), 6000)
                val tcpMs = SystemClock.elapsedRealtime() - began
                raw.soTimeout = 8000                        // the TLS handshake must answer within 8 s
                val s = factory.createSocket(raw, host, port, true) as SSLSocket
                if (!trust) {                               // verify the certificate is really for this host (not done by default on a bare SSLSocket)
                    val ps = s.sslParameters
                    ps.endpointIdentificationAlgorithm = "HTTPS"
                    s.sslParameters = ps
                }
                s.startHandshake()
                s.soTimeout = 0                             // reads may block for as long as the link is quiet
                if (attempt > 0 || k > 0) ConnLog.add("Secure connection to ${addr.hostAddress} over $netName (TCP ${tcpMs} ms)")
                return s
            } catch (e: Exception) {
                try { raw.close() } catch (_: Exception) {}
                last = e
                if (isCertChanged(e)) throw e
                if (active && gen == generation) {
                    val why = if (e is SocketTimeoutException && raw.isConnected) "TCP connected but the secure handshake got no answer within 8 s"
                              else (e.message ?: e.javaClass.simpleName)
                    ConnLog.add("Could not reach ${addr.hostAddress} over $netName: $why")
                }
            } finally {
                if (pending === raw) pending = null
            }
        }
        throw last ?: java.io.IOException("No connection")
    }

    private fun connectOnce(host: String, port: Int, chan: String, trust: Boolean, gen: Int) {
        setStatus("Connecting...")
        ConnLog.add("Connecting to $host:$port")
        val s = openSecure(host, port, trust, gen, attempts)
        if (!(active && gen == generation)) { try { s.close() } catch (_: Exception) {}; return }   // stopped or replaced while connecting: never become the live connection
        socket = s
        writer = PrintWriter(s.getOutputStream(), true)
        lastRx = SystemClock.elapsedRealtime()
        connectedAt = lastRx
        serverTalks = false
        echoes = 0
        pingSentAt = 0L
        connected = true
        attempts = 0
        sendJson(JSONObject().put("type", "protocol_version").put("version", 2))
        sendJson(JSONObject().put("type", "join").put("channel", chan).put("connection_type", "master"))
        flushStale()
        if (hadLoss) { hadLoss = false; ConnLog.add("Connection restored"); Cue.play("connection_restored") }
        else { ConnLog.add("Connected"); Cue.play("connected") }
        publish()
        startKeepAlive(s)
        val r = BufferedReader(InputStreamReader(s.getInputStream()))
        r.forEachLine {
            lastRx = SystemClock.elapsedRealtime()
            try { handle(it) } catch (e: Exception) { CrashLog.log("handle", e) }   // one bad message must never drop the connection
        }
        if (active) ConnLog.add("The server closed the connection after ${(SystemClock.elapsedRealtime() - connectedAt) / 1000} s")
    }

    /**
     * Sends a small ping now and then. The connection is only declared dead when the relay has been proven to answer
     * our pings (so silence really means trouble) and pings are switched on. A relay that never answers and is just
     * quiet is left alone: a quiet link is normal, and cutting it was the cause of the repeated "connection lost".
     */
    private fun startKeepAlive(s: SSLSocket) {
        Thread {
            while (socket === s && connected && active) {
                try { Thread.sleep(PING_MS) } catch (_: InterruptedException) { return@Thread }
                if (socket !== s || !connected) return@Thread
                val c = appCtx
                val pingOn = c == null || Store.prefs(c).getBoolean("ping", true)
                val idle = SystemClock.elapsedRealtime() - lastRx
                if (pingOn && echoes >= ECHOES_NEEDED && idle > WATCHDOG_MS) {
                    ConnLog.add("Nothing from the server for ${idle / 1000} s although it normally answers, reconnecting")
                    try { s.close() } catch (_: Exception) {}
                    return@Thread
                }
                if (pingOn) sendPing(s)
            }
        }.apply { isDaemon = true; start() }
    }

    /** Speaks text from the PC on its own thread. If the engine falls behind, older waiting speech is dropped. */
    private fun speakFromPc(t: String) {
        speaker.execute {
            try {
                val e = tts ?: return@execute
                val behind = pendingSpeech.get() >= MAX_SPEECH_BACKLOG
                if (behind) pendingSpeech.set(0)
                pendingSpeech.incrementAndGet()
                e.speak(t, if (behind) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "pc" + System.nanoTime())
            } catch (ex: Exception) { CrashLog.log("speak", ex) }
        }
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
                if (t.isNotEmpty() && speak && ttsReady) speakFromPc(t)
            }
            "set_clipboard_text" -> {
                val t = m.optString("text", "")
                if (t.isNotEmpty() && t != lastSentClip) ui.post { onPcClipboard(t, false) }
            }
            "cancel" -> if (ttsReady) speaker.execute { try { pendingSpeech.set(0); tts?.stop() } catch (_: Exception) {} }
            "ping", "pong" -> {
                serverTalks = true
                val sent = pingSentAt
                if (sent != 0L && SystemClock.elapsedRealtime() - sent < ECHO_MS) { pingSentAt = 0L; echoes++ }
            }
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
            cm.setPrimaryClip(ClipData.newPlainText("PC clipboard", text))
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
        val s = socket
        sender.execute {
            try {
                val w = writer
                w?.println(o.toString()); w?.flush()
                if (w != null && w.checkError() && socket === s) {
                    ConnLog.add("Sending failed, reconnecting")
                    try { s?.close() } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
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
            val needShift = shift && !isHeld(0x10)
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
