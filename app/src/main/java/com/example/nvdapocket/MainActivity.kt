package com.example.nvdapocket

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.View
import android.widget.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import javax.net.ssl.*

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private class K(val label: String, val vk: Int, val ext: Boolean = false)

    private var socket: SSLSocket? = null
    private var writer: PrintWriter? = null
    @Volatile private var connected = false
    private val sender = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private lateinit var status: TextView
    private lateinit var speakBox: CheckBox
    private lateinit var connectBtn: Button
    private val held = mutableSetOf<Int>()

    private val SHIFT = 0x10

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        tts = TextToSpeech(this, this)
        val prefs = getSharedPreferences("p", MODE_PRIVATE)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        fun field(hint: String, value: String, numeric: Boolean = false) = EditText(this).apply {
            this.hint = hint; setText(value); setSingleLine()
            contentDescription = hint
            if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
        }
        val host = field("Server host", prefs.getString("host", "") ?: "")
        val port = field("Port", prefs.getString("port", "6837") ?: "6837", true)
        val key = field("Key", prefs.getString("key", "") ?: "")
        val trustAll = CheckBox(this).apply { text = "Accept untrusted certificate"; isChecked = prefs.getBoolean("trust", false) }
        speakBox = CheckBox(this).apply { text = "Speak remote speech on this phone"; isChecked = true }
        status = TextView(this).apply {
            text = "Not connected"; textSize = 16f
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        connectBtn = Button(this).apply { text = "Connect" }
        connectBtn.setOnClickListener {
            if (connected) { disconnect(); return@setOnClickListener }
            prefs.edit().putString("host", host.text.toString()).putString("port", port.text.toString())
                .putString("key", key.text.toString()).putBoolean("trust", trustAll.isChecked).apply()
            connect(host.text.toString().trim(), port.text.toString().toIntOrNull() ?: 6837,
                key.text.toString().trim(), trustAll.isChecked)
        }
        listOf<View>(host, port, key, trustAll, speakBox, connectBtn, status).forEach { root.addView(it) }

        // Modifiers (stay held until toggled off)
        root.addView(label("Modifier keys (toggle to hold)"))
        root.addView(row(
            mod("Ctrl", 0x11), mod("Alt", 0x12), mod("Shift", SHIFT),
            mod("Win", 0x5B, true), mod("NVDA/Insert", 0x2D, true)))

        root.addView(label("Keys"))
        root.addView(row(key("Esc", 0x1B), key("Tab", 0x09), key("Enter", 0x0D), key("Space", 0x20), key("Backspace", 0x08)))
        root.addView(row(key("Up", 0x26, true), key("Down", 0x28, true), key("Left", 0x25, true), key("Right", 0x27, true)))
        root.addView(row(key("Home", 0x24, true), key("End", 0x23, true), key("Page up", 0x21, true), key("Page down", 0x22, true)))
        root.addView(row(key("Delete", 0x2E, true), key("Applications", 0x5D, true), key("Caps lock", 0x14), key("Num lock", 0x90, true)))
        for (r in 0 until 2) root.addView(row(*(1..6).map { key("F${r * 6 + it}", 0x70 + r * 6 + it - 1) }.toTypedArray()))

        val sas = Button(this).apply { text = "Send Ctrl+Alt+Delete" }
        sas.setOnClickListener { sendJson(JSONObject().put("type", "send_SAS")) }
        root.addView(sas)
        val release = Button(this).apply { text = "Release all held keys" }
        release.setOnClickListener { releaseAll() }
        root.addView(release)

        root.addView(label("Type text"))
        val text = field("Text to type on the PC", "")
        text.setSingleLine(false)
        val send = Button(this).apply { text = "Send text" }
        send.setOnClickListener { sendText(text.text.toString()); text.setText("") }
        val sendEnter = Button(this).apply { text = "Send text and Enter" }
        sendEnter.setOnClickListener { sendText(text.text.toString() + "\n"); text.setText("") }
        root.addView(text); root.addView(send); root.addView(sendEnter)
    }

    // ---------- UI helpers ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun label(t: String) = TextView(this).apply { text = t; textSize = 16f; setPadding(0, dp(12), 0, dp(4)) }
    private fun row(vararg v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        v.forEach { addView(it, LinearLayout.LayoutParams(0, dp(56), 1f)) }
    }
    private fun key(l: String, vk: Int, ext: Boolean = false) = Button(this).apply {
        text = l; contentDescription = l; isAllCaps = false; textSize = 12f
        setOnClickListener { tap(vk, ext) }
    }
    private fun mod(l: String, vk: Int, ext: Boolean = false) = ToggleButton(this).apply {
        textOn = "$l held"; textOff = l; text = l; isAllCaps = false; textSize = 11f
        setOnCheckedChangeListener { _, on ->
            if (on) held.add(vk) else held.remove(vk)
            sendKey(vk, ext, on)
        }
    }

    // ---------- Key sending ----------
    private fun sendJson(o: JSONObject) {
        if (!connected) return
        sender.execute { try { writer?.println(o.toString()); writer?.flush() } catch (_: Exception) {} }
    }
    private fun sendKey(vk: Int, ext: Boolean, pressed: Boolean) =
        sendJson(JSONObject().put("type", "key").put("vk_code", vk).put("extended", ext).put("pressed", pressed))
    private fun tap(vk: Int, ext: Boolean = false) { sendKey(vk, ext, true); sendKey(vk, ext, false) }
    private fun releaseAll() {
        held.toList().forEach { sendKey(it, it == 0x5B || it == 0x2D, false) }
        held.clear()
        val g = findViewById<View>(android.R.id.content)
        fun walk(v: View) { if (v is ToggleButton) v.isChecked = false else if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i)) }
        walk(g)
    }

    private fun sendText(s: String) {
        for (c in s) {
            val (vk, shift) = charToKey(c) ?: continue
            val needShift = shift && !held.contains(SHIFT)
            if (needShift) sendKey(SHIFT, false, true)
            tap(vk)
            if (needShift) sendKey(SHIFT, false, false)
        }
    }

    // US layout mapping
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

    // ---------- Connection ----------
    private fun setStatus(s: String) = ui.post { status.text = s }

    private fun connect(host: String, port: Int, key: String, trustAll: Boolean) {
        if (host.isEmpty() || key.isEmpty()) { setStatus("Enter host and key"); return }
        setStatus("Connecting...")
        Thread {
            try {
                val factory: SSLSocketFactory = if (trustAll) {
                    val tm = object : X509TrustManager {
                        override fun checkClientTrusted(c: Array<X509Certificate>?, a: String?) {}
                        override fun checkServerTrusted(c: Array<X509Certificate>?, a: String?) {}
                        override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
                    }
                    SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }.socketFactory
                } else SSLSocketFactory.getDefault() as SSLSocketFactory
                val s = factory.createSocket(host, port) as SSLSocket
                s.startHandshake()
                socket = s
                writer = PrintWriter(s.getOutputStream(), true)
                connected = true
                ui.post { connectBtn.text = "Disconnect" }
                sendJson(JSONObject().put("type", "protocol_version").put("version", 2))
                sendJson(JSONObject().put("type", "join").put("channel", key).put("connection_type", "master"))
                setStatus("Connected, joined channel")
                val r = BufferedReader(InputStreamReader(s.getInputStream()))
                r.forEachLine { handle(it) }
            } catch (e: Exception) {
                setStatus("Error: ${e.message}")
            } finally {
                connected = false
                ui.post { connectBtn.text = "Connect" }
                if (status.text.toString().startsWith("Connected")) setStatus("Disconnected")
            }
        }.start()
    }

    private fun disconnect() {
        connected = false
        try { socket?.close() } catch (_: Exception) {}
        setStatus("Disconnected")
    }

    private fun handle(line: String) {
        val m = try { JSONObject(line) } catch (_: Exception) { return }
        when (m.optString("type")) {
            "speak" -> {
                val seq = m.optJSONArray("sequence") ?: return
                val sb = StringBuilder()
                for (i in 0 until seq.length()) (seq.opt(i) as? String)?.let { if (it.isNotBlank()) sb.append(it).append(' ') }
                val t = sb.toString().trim()
                if (t.isNotEmpty() && ::speakBox.isInitialized) ui.post {
                    if (speakBox.isChecked && ttsReady) tts.speak(t, TextToSpeech.QUEUE_ADD, null, null)
                }
            }
            "cancel" -> ui.post { if (ttsReady) tts.stop() }
            "channel_joined" -> setStatus("Connected, channel joined")
            "client_joined" -> setStatus("Connected, PC joined the channel")
            "client_left" -> setStatus("Connected, PC left the channel")
            "motd" -> setStatus("Connected: " + m.optString("motd"))
        }
    }

    override fun onInit(st: Int) { ttsReady = st == TextToSpeech.SUCCESS }
    override fun onDestroy() { disconnect(); tts.shutdown(); super.onDestroy() }
}
