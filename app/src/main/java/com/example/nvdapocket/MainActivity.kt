package com.example.nvdapocket

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.*
import org.json.JSONObject
import kotlin.math.abs

class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var voiceStatus: TextView
    private lateinit var speakBox: CheckBox
    private lateinit var volumeBox: CheckBox
    private lateinit var connectBtn: Button
    private lateinit var voiceBtn: Button
    private lateinit var form: LinearLayout
    private lateinit var capture: KeyCaptureView
    private var recognizer: SpeechRecognizer? = null
    private var voiceOn = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val prefs = getSharedPreferences("p", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        setContentView(ScrollView(this).apply { addView(root) })

        fun field(hint: String, value: String, numeric: Boolean = false) = EditText(this).apply {
            this.hint = hint; setText(value); setSingleLine()
            contentDescription = hint
            if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
        }
        val host = field("Server host", prefs.getString("host", "") ?: "")
        val port = field("Port", prefs.getString("port", "6837") ?: "6837", true)
        val keyField = field("Key", prefs.getString("key", "") ?: "")
        val trustAll = CheckBox(this).apply { text = "Accept untrusted certificate"; isChecked = prefs.getBoolean("trust", false) }
        val battery = Button(this).apply { text = "Battery settings (set this app to Unrestricted)" }
        battery.setOnClickListener { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listOf<View>(host, port, keyField, trustAll, battery).forEach { form.addView(it) }

        speakBox = CheckBox(this).apply { text = "Speak remote speech on this phone"; isChecked = Remote.speak }
        speakBox.setOnCheckedChangeListener { _, c -> Remote.speak = c }
        volumeBox = CheckBox(this).apply { text = "Volume keys act as Up and Down arrows" }
        status = TextView(this).apply { text = Remote.lastStatus; textSize = 16f }
        connectBtn = Button(this).apply { text = "Connect" }
        connectBtn.setOnClickListener {
            if (Remote.active) { Remote.stop(this); updateUi(); return@setOnClickListener }
            prefs.edit().putString("host", host.text.toString()).putString("port", port.text.toString())
                .putString("key", keyField.text.toString()).putBoolean("trust", trustAll.isChecked).apply()
            val h = host.text.toString().trim(); val k = keyField.text.toString().trim()
            if (h.isEmpty() || k.isEmpty()) { status.text = "Enter host and key"; return@setOnClickListener }
            Remote.start(this, h, port.text.toString().toIntOrNull() ?: 6837, k, trustAll.isChecked)
            updateUi()
        }
        listOf<View>(connectBtn, status, form, speakBox, volumeBox).forEach { root.addView(it) }

        // Gesture pad
        val pad = GesturePad(this)
        root.addView(pad, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(300)).apply { topMargin = dp(8) })

        // Voice
        voiceBtn = Button(this).apply { text = "Start voice commands" }
        voiceBtn.setOnClickListener {
            if (voiceOn) stopVoice() else { voiceOn = true; voiceBtn.text = "Stop voice commands"; startVoice() }
        }
        voiceStatus = TextView(this).apply { text = "Voice: off" }
        root.addView(voiceBtn); root.addView(voiceStatus)

        // Live keyboard
        capture = KeyCaptureView(this)
        capture.setBackgroundColor(0xFFE0E0E0.toInt())
        capture.contentDescription = "Live keyboard. Tap to open the keyboard. Everything you type goes straight to the PC."
        capture.setOnClickListener { showKeyboard() }
        root.addView(label("Live keyboard (tap here to type)"))
        root.addView(capture, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(80)))

        root.addView(label("Modifier keys (toggle to hold)"))
        root.addView(row(mod("Ctrl", 0x11), mod("Alt", 0x12), mod("Shift", 0x10), mod("Win", 0x5B, true), mod("NVDA/Insert", 0x2D, true)))
        root.addView(label("Extra keys"))
        root.addView(row(key("Esc", 0x1B), key("Tab", 0x09), key("Enter", 0x0D), key("Space", 0x20), key("Backspace", 0x08)))
        root.addView(row(key("Home", 0x24, true), key("End", 0x23, true), key("Page up", 0x21, true), key("Page down", 0x22, true)))
        root.addView(row(key("Delete", 0x2E, true), key("Applications", 0x5D, true), key("Caps lock", 0x14)))
        for (r in 0 until 2) root.addView(row(*(1..6).map { key("F${r * 6 + it}", 0x70 + r * 6 + it - 1) }.toTypedArray()))
        val sas = Button(this).apply { text = "Send Ctrl+Alt+Delete" }
        sas.setOnClickListener { Remote.sendJson(JSONObject().put("type", "send_SAS")) }
        root.addView(sas)
        val release = Button(this).apply { text = "Release all held keys" }
        release.setOnClickListener { releaseAll() }
        root.addView(release)
        updateUi()
    }

    override fun onResume() {
        super.onResume()
        Remote.statusListener = { s -> status.text = s; updateUi() }
        status.text = Remote.lastStatus
        updateUi()
    }

    override fun onPause() {
        Remote.statusListener = null
        stopVoice()
        super.onPause()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        super.onDestroy()
    }

    private fun updateUi() {
        connectBtn.text = if (Remote.active) "Disconnect" else "Connect"
        form.visibility = if (Remote.active) View.GONE else View.VISIBLE
    }

    private fun showKeyboard() {
        capture.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(capture, InputMethodManager.SHOW_IMPLICIT)
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
        setOnClickListener { Remote.tap(vk, ext) }
    }
    private fun mod(l: String, vk: Int, ext: Boolean = false) = ToggleButton(this).apply {
        textOn = "$l held"; textOff = l; text = l; isAllCaps = false; textSize = 11f
        setOnCheckedChangeListener { _, on ->
            if (on) Remote.held.add(vk) else Remote.held.remove(vk)
            Remote.sendKey(vk, ext, on)
        }
    }
    private fun releaseAll() {
        fun walk(v: View) {
            if (v is ToggleButton) v.isChecked = false
            else if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(findViewById<View>(android.R.id.content))
    }

    // ---------- Gesture pad ----------
    inner class GesturePad(ctx: Context) : TextView(ctx) {
        private var ax = 0f; private var ay = 0f
        private var lx = 0f; private var ly = 0f
        private var maxP = 0
        private var moved = false
        private var downT = 0L
        private val thr = dp(48).toFloat()

        init {
            text = "GESTURE PAD\n\n" +
                "1 finger: swipe = arrows (keep dragging to repeat)\ntap = Enter, hold = Escape\n\n" +
                "2 fingers: swipe right/left = Tab / Shift+Tab\nswipe up/down = Page Up / Page Down\ntap = Space\n\n" +
                "3 fingers: swipe up/down = Home / End\nswipe left/right = Backspace / Delete\ntap = Alt+Tab\n\n" +
                "4 fingers: tap = Windows key"
            textSize = 14f
            gravity = Gravity.CENTER
            setBackgroundColor(0xFFCFE8FF.toInt())
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        private fun cx(e: MotionEvent): Float { var s = 0f; for (i in 0 until e.pointerCount) s += e.getX(i); return s / e.pointerCount }
        private fun cy(e: MotionEvent): Float { var s = 0f; for (i in 0 until e.pointerCount) s += e.getY(i); return s / e.pointerCount }
        private fun dir(dx: Float, dy: Float): Char =
            if (abs(dx) > abs(dy)) (if (dx > 0) 'R' else 'L') else (if (dy > 0) 'D' else 'U')

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent.requestDisallowInterceptTouchEvent(true)
                    maxP = 1; moved = false; downT = e.eventTime
                    ax = cx(e); ay = cy(e); lx = ax; ly = ay
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (e.pointerCount > maxP) {
                        maxP = e.pointerCount; moved = false
                        ax = cx(e); ay = cy(e); lx = ax; ly = ay
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (e.pointerCount == maxP) {
                        val x = cx(e); val y = cy(e)
                        if (maxP == 1) {
                            val dx = x - ax; val dy = y - ay
                            if (abs(dx) > thr || abs(dy) > thr) { fire(1, dir(dx, dy)); ax = x; ay = y; moved = true }
                        } else {
                            lx = x; ly = y
                            if (abs(lx - ax) > thr / 2 || abs(ly - ay) > thr / 2) moved = true
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (maxP > 1 && moved) fire(maxP, dir(lx - ax, ly - ay))
                    else if (!moved) {
                        val d = e.eventTime - downT
                        if (maxP == 1 && d >= 500) fire(1, 'H') else if (d < 500) fire(maxP, 'T')
                    }
                    maxP = 0
                }
                MotionEvent.ACTION_CANCEL -> maxP = 0
            }
            return true
        }

        private fun fire(n: Int, d: Char) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            when (n) {
                1 -> when (d) {
                    'U' -> Remote.tap(0x26, true)
                    'D' -> Remote.tap(0x28, true)
                    'L' -> Remote.tap(0x25, true)
                    'R' -> Remote.tap(0x27, true)
                    'T' -> Remote.tap(0x0D)
                    'H' -> Remote.tap(0x1B)
                }
                2 -> when (d) {
                    'R' -> Remote.tap(0x09)
                    'L' -> Remote.combo(0x10, 0x09)
                    'U' -> Remote.tap(0x21, true)
                    'D' -> Remote.tap(0x22, true)
                    'T' -> Remote.tap(0x20)
                }
                3 -> when (d) {
                    'U' -> Remote.tap(0x24, true)
                    'D' -> Remote.tap(0x23, true)
                    'L' -> Remote.tap(0x08)
                    'R' -> Remote.tap(0x2E, true)
                    'T' -> Remote.combo(0x12, 0x09)
                }
                else -> if (d == 'T') Remote.tap(0x5B, true)
            }
        }
    }

    // ---------- Live keyboard capture ----------
    private fun keyToVk(code: Int): Pair<Int, Boolean>? {
        return when (code) {
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> Pair(0x41 + (code - KeyEvent.KEYCODE_A), false)
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> Pair(0x30 + (code - KeyEvent.KEYCODE_0), false)
            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> Pair(0x70 + (code - KeyEvent.KEYCODE_F1), false)
            KeyEvent.KEYCODE_DEL -> Pair(0x08, false)
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> Pair(0x0D, false)
            KeyEvent.KEYCODE_TAB -> Pair(0x09, false)
            KeyEvent.KEYCODE_SPACE -> Pair(0x20, false)
            KeyEvent.KEYCODE_ESCAPE -> Pair(0x1B, false)
            KeyEvent.KEYCODE_FORWARD_DEL -> Pair(0x2E, true)
            KeyEvent.KEYCODE_DPAD_UP -> Pair(0x26, true)
            KeyEvent.KEYCODE_DPAD_DOWN -> Pair(0x28, true)
            KeyEvent.KEYCODE_DPAD_LEFT -> Pair(0x25, true)
            KeyEvent.KEYCODE_DPAD_RIGHT -> Pair(0x27, true)
            KeyEvent.KEYCODE_MOVE_HOME -> Pair(0x24, true)
            KeyEvent.KEYCODE_MOVE_END -> Pair(0x23, true)
            KeyEvent.KEYCODE_PAGE_UP -> Pair(0x21, true)
            KeyEvent.KEYCODE_PAGE_DOWN -> Pair(0x22, true)
            KeyEvent.KEYCODE_INSERT -> Pair(0x2D, true)
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> Pair(0x11, false)
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> Pair(0x12, false)
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> Pair(0x10, false)
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> Pair(0x5B, true)
            KeyEvent.KEYCODE_CAPS_LOCK -> Pair(0x14, false)
            KeyEvent.KEYCODE_MINUS -> Pair(0xBD, false)
            KeyEvent.KEYCODE_EQUALS -> Pair(0xBB, false)
            KeyEvent.KEYCODE_LEFT_BRACKET -> Pair(0xDB, false)
            KeyEvent.KEYCODE_RIGHT_BRACKET -> Pair(0xDD, false)
            KeyEvent.KEYCODE_BACKSLASH -> Pair(0xDC, false)
            KeyEvent.KEYCODE_SEMICOLON -> Pair(0xBA, false)
            KeyEvent.KEYCODE_APOSTROPHE -> Pair(0xDE, false)
            KeyEvent.KEYCODE_COMMA -> Pair(0xBC, false)
            KeyEvent.KEYCODE_PERIOD -> Pair(0xBE, false)
            KeyEvent.KEYCODE_SLASH -> Pair(0xBF, false)
            KeyEvent.KEYCODE_GRAVE -> Pair(0xC0, false)
            else -> null
        }
    }

    private fun forwardKey(e: KeyEvent): Boolean {
        val (vk, ext) = keyToVk(e.keyCode) ?: return false
        when (e.action) {
            KeyEvent.ACTION_DOWN -> Remote.sendKey(vk, ext, true)
            KeyEvent.ACTION_UP -> Remote.sendKey(vk, ext, false)
        }
        return true
    }

    inner class KeyCaptureView(ctx: Context) : View(ctx) {
        init { isFocusable = true; isFocusableInTouchMode = true }
        override fun onCheckIsTextEditor() = true
        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            return RemoteConnection(this)
        }
        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
            if (forwardKey(event)) true else super.onKeyDown(keyCode, event)
        override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
            if (forwardKey(event)) true else super.onKeyUp(keyCode, event)
        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className = "android.widget.EditText"
            info.isEditable = true
        }
    }

    inner class RemoteConnection(v: View) : BaseInputConnection(v, false) {
        private var composing = ""
        override fun setComposingText(t: CharSequence, p: Int): Boolean {
            val n = t.toString()
            var i = 0
            while (i < composing.length && i < n.length && composing[i] == n[i]) i++
            repeat(composing.length - i) { Remote.tap(0x08) }
            Remote.sendText(n.substring(i))
            composing = n
            return true
        }
        override fun commitText(t: CharSequence, p: Int): Boolean {
            setComposingText(t, p)
            composing = ""
            return true
        }
        override fun finishComposingText(): Boolean { composing = ""; return true }
        override fun deleteSurroundingText(b: Int, a: Int): Boolean {
            repeat(b) { Remote.tap(0x08) }
            repeat(a) { Remote.tap(0x2E, true) }
            composing = ""
            return true
        }
        override fun sendKeyEvent(e: KeyEvent): Boolean = forwardKey(e)
        override fun performEditorAction(a: Int): Boolean { Remote.tap(0x0D); return true }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (volumeBox.isChecked && Remote.connected) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) { Remote.sendKey(0x26, true, true); return true }
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) { Remote.sendKey(0x28, true, true); return true }
        }
        return super.onKeyDown(keyCode, event)
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (volumeBox.isChecked && Remote.connected) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) { Remote.sendKey(0x26, true, false); return true }
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) { Remote.sendKey(0x28, true, false); return true }
        }
        return super.onKeyUp(keyCode, event)
    }

    // ---------- Voice commands (Android built-in recognizer, free) ----------
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) startVoice()
            else { voiceStatus.text = "Voice: microphone permission denied"; stopVoice() }
        }
    }

    private fun stopVoice() {
        voiceOn = false
        try { recognizer?.cancel() } catch (_: Exception) {}
        if (::voiceBtn.isInitialized) voiceBtn.text = "Start voice commands"
        if (::voiceStatus.isInitialized) voiceStatus.text = "Voice: off"
    }

    private fun startVoice() {
        if (!voiceOn) return
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 2); return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            voiceStatus.text = "Voice: speech recognition not available on this phone"; stopVoice(); return
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer!!.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) { voiceStatus.text = "Voice: listening..." }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(b: Bundle?) {}
                override fun onEvent(t: Int, b: Bundle?) {}
                override fun onError(error: Int) {
                    if (!voiceOn) return
                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) { stopVoice(); return }
                    ui.postDelayed({ startVoice() }, 600)
                }
                override fun onResults(results: Bundle?) {
                    val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (heard != null) { voiceStatus.text = "Heard: $heard"; runVoice(heard) }
                    if (voiceOn) ui.postDelayed({ startVoice() }, 300)
                }
            })
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
        recognizer!!.startListening(i)
    }

    private val numWords = mapOf("one" to 1, "two" to 2, "to" to 2, "too" to 2, "three" to 3, "four" to 4, "for" to 4,
        "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12)
    private val keyWords = mapOf(
        "enter" to Pair(0x0D, false), "return" to Pair(0x0D, false), "tab" to Pair(0x09, false),
        "escape" to Pair(0x1B, false), "space" to Pair(0x20, false), "backspace" to Pair(0x08, false),
        "delete" to Pair(0x2E, true), "up" to Pair(0x26, true), "down" to Pair(0x28, true),
        "left" to Pair(0x25, true), "right" to Pair(0x27, true), "home" to Pair(0x24, true),
        "end" to Pair(0x23, true), "pageup" to Pair(0x21, true), "pagedown" to Pair(0x22, true),
        "capslock" to Pair(0x14, false),
        "applications" to Pair(0x5D, true), "menu" to Pair(0x5D, true))
    private val modWords = mapOf(
        "control" to Pair(0x11, false), "ctrl" to Pair(0x11, false), "alt" to Pair(0x12, false),
        "shift" to Pair(0x10, false), "insert" to Pair(0x2D, true), "nvda" to Pair(0x2D, true), "windows" to Pair(0x5B, true), "window" to Pair(0x5B, true), "win" to Pair(0x5B, true))

    private fun numOf(t: String): Int? = t.toIntOrNull() ?: numWords[t]

    /** Examples: "down 5", "control shift escape", "alt f four", "page down", "type hello world". */
    private fun runVoice(raw: String) {
        var s = raw.lowercase().trim()
        for (p in listOf("type ", "write ", "dictate ", "text ")) {
            if (s.startsWith(p)) { Remote.sendText(raw.trim().substring(p.length)); return }
        }
        s = s.replace("page up", "pageup").replace("page down", "pagedown").replace("caps lock", "capslock")
            .replace("back space", "backspace").replace("n v d a", "nvda").replace("insert plus", "insert").replace("arrow", "").replace(",", " ").replace(".", " ")
        val skip = setOf("press", "hit", "key", "then", "and", "the", "times", "time")
        val toks = s.split(Regex("\\s+")).filter { it.isNotEmpty() && it !in skip }
        val mods = mutableListOf<Pair<Int, Boolean>>()
        var i = 0
        var understood = false
        while (i < toks.size) {
            val t = toks[i]
            val m = modWords[t]
            if (m != null) { mods.add(m); i++; continue }
            var k: Pair<Int, Boolean>? = null
            var consumed = 1
            val fm = Regex("^f(\\d{1,2})$").find(t)
            val fnum = if (t == "f" && i + 1 < toks.size) numOf(toks[i + 1]) else null
            if (fm != null && fm.groupValues[1].toInt() in 1..12) {
                k = Pair(0x70 + fm.groupValues[1].toInt() - 1, false)
            } else if (fnum != null && fnum in 1..12) {
                k = Pair(0x70 + fnum - 1, false); consumed = 2
            } else {
                k = keyWords[t]
            }
            if (k == null && t.length == 1 && t[0] in 'a'..'z') k = Pair(t[0].uppercaseChar().code, false)
            if (k == null && mods.isNotEmpty()) {
                val n = numOf(t)
                if (n != null && n in 0..9) k = Pair(0x30 + n, false)
            }
            if (k == null) { i++; continue }
            val kk: Pair<Int, Boolean> = k
            i += consumed
            var count = 1
            if (mods.isEmpty() && i < toks.size) {
                val n = numOf(toks[i])
                if (n != null && n in 1..50) { count = n; i++ }
            }
            mods.forEach { Remote.sendKey(it.first, it.second, true) }
            repeat(count) { Remote.tap(kk.first, kk.second) }
            mods.reversed().forEach { Remote.sendKey(it.first, it.second, false) }
            mods.clear()
            understood = true
        }
        if (mods.isNotEmpty()) { mods.forEach { Remote.tap(it.first, it.second) }; understood = true }
        if (!understood) voiceStatus.text = "Heard: $raw (not a command)"
    }
}
