package com.example.nvdapocket

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
    private var distDp = 100
    private var tapsOn = true

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

        val distLabel = TextView(this)
        val seek = SeekBar(this).apply { max = 160; progress = prefs.getInt("dist", 60) }
        distDp = 40 + seek.progress
        distLabel.text = "Gesture distance: $distDp (higher = less sensitive)"
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                distDp = 40 + p
                distLabel.text = "Gesture distance: $distDp (higher = less sensitive)"
                prefs.edit().putInt("dist", p).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        tapsOn = prefs.getBoolean("taps", true)
        val tapBox = CheckBox(this).apply { text = "Taps and hold on the pad (Enter, Space, Alt+Tab, Escape)"; isChecked = tapsOn }
        tapBox.setOnCheckedChangeListener { _, c -> tapsOn = c; prefs.edit().putBoolean("taps", c).apply() }
        root.addView(distLabel); root.addView(seek); root.addView(tapBox)

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
        private var ax = 0f; private var ay = 0f      // anchor for repeat / swipe
        private var sx = 0f; private var sy = 0f      // where the touch started
        private var lx = 0f; private var ly = 0f      // last centroid (multi finger)
        private var maxP = 0
        private var moved = false
        private var drift = 0f
        private var downT = 0L
        private var lastFire = 0L
        private fun thr() = dp(distDp).toFloat()

        init {
            text = "GESTURE PAD\n\n" +
                "1 finger: swipe = arrows (keep dragging to repeat)\ntap = Enter, hold = Escape\n\n" +
                "2 fingers: swipe right/left = Tab / Shift+Tab\nswipe up/down = Page Up / Page Down\ntap = Space\n\n" +
                "3 fingers: swipe up/down = Home / End\nswipe left/right = Backspace / Delete\ntap = Alt+Tab"
            textSize = 14f
            gravity = Gravity.CENTER
            setBackgroundColor(0xFFCFE8FF.toInt())
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        private fun cx(e: MotionEvent): Float { var t = 0f; for (i in 0 until e.pointerCount) t += e.getX(i); return t / e.pointerCount }
        private fun cy(e: MotionEvent): Float { var t = 0f; for (i in 0 until e.pointerCount) t += e.getY(i); return t / e.pointerCount }
        private fun dir(dx: Float, dy: Float): Char =
            if (abs(dx) > abs(dy)) (if (dx > 0) 'R' else 'L') else (if (dy > 0) 'D' else 'U')

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent.requestDisallowInterceptTouchEvent(true)
                    maxP = 1; moved = false; drift = 0f; downT = e.eventTime
                    ax = cx(e); ay = cy(e); sx = ax; sy = ay; lx = ax; ly = ay
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (e.pointerCount > maxP) {
                        maxP = e.pointerCount; moved = false; drift = 0f
                        ax = cx(e); ay = cy(e); sx = ax; sy = ay; lx = ax; ly = ay
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (e.pointerCount == maxP) {
                        val x = cx(e); val y = cy(e)
                        drift = maxOf(drift, abs(x - sx), abs(y - sy))
                        if (maxP == 1) {
                            val dx = x - ax; val dy = y - ay
                            if (abs(dx) > thr() || abs(dy) > thr()) { fire(1, dir(dx, dy)); ax = x; ay = y; moved = true }
                        } else {
                            lx = x; ly = y
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (maxP in 2..3) {
                        val dx = lx - ax; val dy = ly - ay
                        if (abs(dx) >= thr() * 1.3f || abs(dy) >= thr() * 1.3f) fire(maxP, dir(dx, dy))
                        else if (tapsOn && drift < dp(24) && e.eventTime - downT < 350) fire(maxP, 'T')
                    } else if (maxP == 1 && !moved && tapsOn && drift < dp(24)) {
                        val d = e.eventTime - downT
                        if (d >= 700) fire(1, 'H') else if (d < 350) fire(1, 'T')
                    }
                    maxP = 0
                }
                MotionEvent.ACTION_CANCEL -> maxP = 0
            }
            return true
        }

        private fun fire(n: Int, d: Char) {
            val now = SystemClock.uptimeMillis()
            if (d == 'T' || d == 'H' || n > 1) {
                if (now - lastFire < 400) return     // debounce
                lastFire = now
            }
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
                    val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (list != null && list.isNotEmpty()) {
                        if (list.any { it.lowercase().contains("stop listening") }) { stopVoice(); return }
                        var done = false
                        for (h in list) {
                            val acts = parseVoice(h)
                            if (acts.isNotEmpty()) { voiceStatus.text = "Heard: $h"; execute(acts); done = true; break }
                        }
                        if (!done) voiceStatus.text = "Heard: ${list[0]} (not a command)"
                    }
                    if (voiceOn) ui.postDelayed({ startVoice() }, 300)
                }
            })
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        recognizer!!.startListening(i)
    }

    private val numWords = mapOf("zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12)
    private val homoWords = mapOf("to" to 2, "too" to 2, "for" to 4)

    private fun buildKeyWords(): Map<String, Pair<Int, Boolean>> {
        val m = HashMap<String, Pair<Int, Boolean>>()
        fun k(vk: Int, ext: Boolean, vararg names: String) { for (n in names) m[n] = Pair(vk, ext) }
        k(0x0D, false, "enter", "return")
        k(0x09, false, "tab")
        k(0x1B, false, "escape", "esc")
        k(0x20, false, "space", "spacebar")
        k(0x08, false, "backspace")
        k(0x2E, true, "delete", "del")
        k(0x26, true, "up"); k(0x28, true, "down"); k(0x25, true, "left"); k(0x27, true, "right")
        k(0x24, true, "home"); k(0x23, true, "end")
        k(0x21, true, "pageup"); k(0x22, true, "pagedown")
        k(0x2D, true, "ins")
        k(0x14, false, "capslock")
        k(0x5D, true, "menu", "applications")
        k(0x2C, true, "printscreen")
        k(0x91, false, "scrolllock")
        k(0x13, false, "pause", "break")
        k(0x90, true, "numlock")
        k(0xBD, false, "minus", "dash", "hyphen")
        k(0xBB, false, "equals", "equal")
        k(0xDB, false, "openbracket")
        k(0xDD, false, "closebracket")
        k(0xDC, false, "backslash")
        k(0xBA, false, "semicolon")
        k(0xDE, false, "quote", "apostrophe")
        k(0xBC, false, "comma")
        k(0xBE, false, "period", "dot")
        k(0xBF, false, "slash")
        k(0xC0, false, "backtick", "grave", "tilde")
        val dw = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
        for (d in 0..9) { m["numpad$d"] = Pair(0x60 + d, false); m["numpad" + dw[d]] = Pair(0x60 + d, false) }
        k(0x6B, false, "numpadplus", "numpadadd")
        k(0x6D, false, "numpadminus", "numpadsubtract")
        k(0x6A, false, "numpadmultiply", "numpadstar")
        k(0x6F, true, "numpaddivide", "numpadslash")
        k(0x6E, false, "numpaddot", "numpaddecimal", "numpadperiod")
        k(0x0D, true, "numpadenter")
        return m
    }
    private val keyWords = buildKeyWords()

    private val modWords = mapOf(
        "control" to Pair(0x11, false), "ctrl" to Pair(0x11, false), "alt" to Pair(0x12, false),
        "shift" to Pair(0x10, false),
        "insert" to Pair(0x2D, true), "nvda" to Pair(0x2D, true), "nvidia" to Pair(0x2D, true),
        "invidia" to Pair(0x2D, true), "envy" to Pair(0x2D, true),
        "windows" to Pair(0x5B, true), "window" to Pair(0x5B, true), "win" to Pair(0x5B, true))

    private val nato = mapOf("alpha" to 'a', "bravo" to 'b', "charlie" to 'c', "delta" to 'd', "echo" to 'e',
        "foxtrot" to 'f', "golf" to 'g', "hotel" to 'h', "india" to 'i', "juliet" to 'j', "kilo" to 'k',
        "lima" to 'l', "mike" to 'm', "november" to 'n', "oscar" to 'o', "papa" to 'p', "quebec" to 'q',
        "romeo" to 'r', "sierra" to 's', "tango" to 't', "uniform" to 'u', "victor" to 'v', "whiskey" to 'w',
        "xray" to 'x', "yankee" to 'y', "zulu" to 'z')

    // Spoken forms the recognizer often produces for a letter; only used right after a modifier (NVDA, Ctrl, Alt...)
    private val letterAlias = mapOf(
        "be" to 'b', "bee" to 'b', "see" to 'c', "sea" to 'c', "dee" to 'd', "ee" to 'e', "eff" to 'f', "ef" to 'f',
        "gee" to 'g', "aitch" to 'h', "eye" to 'i', "jay" to 'j', "kay" to 'k', "el" to 'l', "em" to 'm',
        "en" to 'n', "in" to 'n', "and" to 'n', "an" to 'n', "oh" to 'o', "owe" to 'o', "pee" to 'p', "pea" to 'p',
        "cue" to 'q', "queue" to 'q', "are" to 'r', "ess" to 's', "es" to 's', "tea" to 't', "tee" to 't',
        "you" to 'u', "vee" to 'v', "dub" to 'w', "ex" to 'x', "why" to 'y', "zee" to 'z', "zed" to 'z')

    private val norm = listOf(
        "page up" to "pageup", "page down" to "pagedown", "caps lock" to "capslock", "back space" to "backspace",
        "num lock" to "numlock", "scroll lock" to "scrolllock", "print screen" to "printscreen",
        "forward slash" to "slash", "back slash" to "backslash", "open bracket" to "openbracket",
        "left bracket" to "openbracket", "close bracket" to "closebracket", "right bracket" to "closebracket",
        "windows key" to "windows", "context menu" to "menu", "double u" to "w", "n v d a" to "nvda",
        "n.v.d.a" to "nvda")

    // Spoken shortcuts. The right side is parsed again by the normal parser.
    private val macros = listOf(
        "select all" to "control a", "copy" to "control c", "cut" to "control x", "paste" to "control v",
        "undo" to "control z", "redo" to "control y", "save" to "control s", "find" to "control f",
        "new tab" to "control t", "close tab" to "control w", "close window" to "alt f4",
        "switch window" to "alt tab", "next window" to "alt tab", "start menu" to "windows",
        "show desktop" to "windows d", "lock screen" to "windows l", "run dialog" to "windows r",
        "file explorer" to "windows e", "task manager" to "control shift escape", "refresh" to "f5",
        "stop speech" to "control", "pause speech" to "shift",
        "nvda menu" to "nvda n", "say all" to "nvda down", "read all" to "nvda down", "read line" to "nvda up",
        "read title" to "nvda t", "where am i" to "nvda tab", "elements list" to "nvda f7",
        "speech mode" to "nvda s", "nvda settings" to "nvda control g",
        "next heading" to "h", "previous heading" to "shift h", "next link" to "k", "next button" to "b",
        "next table" to "t", "next landmark" to "d", "next form field" to "f", "next edit field" to "e"
    ).sortedByDescending { it.first.length }

    private class Act(val mods: List<Pair<Int, Boolean>>, val key: Pair<Int, Boolean>?, val count: Int, val text: String? = null)

    private fun numOf(t: String, loose: Boolean): Int? =
        t.toIntOrNull() ?: numWords[t] ?: (if (loose) homoWords[t] else null)

    /**
     * Examples: "down 5", "NVDA n", "NVDA plus s", "control shift escape", "alt f four",
     * "page down", "numpad plus", "select all", "say all", "type hello world".
     */
    private fun parseVoice(raw: String): List<Act> {
        var s = raw.lowercase().trim()
        for (p in listOf("type ", "write ", "dictate ", "text ")) {
            if (s.startsWith(p)) return listOf(Act(emptyList(), null, 1, raw.trim().substring(p.length)))
        }
        s = " $s "
        for ((k, v) in macros) s = Regex("\\b" + Regex.escape(k) + "\\b").replace(s, " $v ")
        s = s.replace("+", " ").replace(",", " ").replace(".", " ").replace("-", " ")
        for ((a, b) in norm) s = s.replace(a, b)
        s = Regex("numpad\\s+(\\w+)").replace(s) { "numpad" + it.groupValues[1] }
        s = Regex("\\bplus\\b").replace(s, " ")
        s = s.replace("arrow", " ")
        val skip = setOf("press", "hit", "key", "the", "times", "time", "then", "letter", "number")
        val toks = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() && it !in skip }

        val acts = mutableListOf<Act>()
        val mods = mutableListOf<Pair<Int, Boolean>>()
        var i = 0
        while (i < toks.size) {
            val t = toks[i]
            val m = modWords[t]
            if (m != null) { mods.add(m); i++; continue }
            var k: Pair<Int, Boolean>? = null
            var consumed = 1
            val fm = Regex("^f(\\d{1,2})$").find(t)
            val fnum = if (t == "f" && i + 1 < toks.size) numOf(toks[i + 1], false) else null
            if (fm != null && fm.groupValues[1].toInt() in 1..12) {
                k = Pair(0x70 + fm.groupValues[1].toInt() - 1, false)
            } else if (fnum != null && fnum in 1..12) {
                k = Pair(0x70 + fnum - 1, false); consumed = 2
            } else {
                k = keyWords[t]
            }
            if (k == null && t.length == 1 && t[0] in 'a'..'z') k = Pair(t[0].uppercaseChar().code, false)
            if (k == null) { val c = nato[t]; if (c != null) k = Pair(c.uppercaseChar().code, false) }
            if (k == null) { val n = numOf(t, false); if (n != null && n in 0..9) k = Pair(0x30 + n, false) }
            if (k == null && mods.isNotEmpty()) { val c = letterAlias[t]; if (c != null) k = Pair(c.uppercaseChar().code, false) }
            if (k == null) { i++; continue }
            val kk: Pair<Int, Boolean> = k
            i += consumed
            var count = 1
            if (mods.isEmpty() && i < toks.size) {
                val n = numOf(toks[i], true)
                if (n != null && n in 1..50) { count = n; i++ }
            }
            acts.add(Act(mods.toList(), kk, count))
            mods.clear()
        }
        if (mods.isNotEmpty()) acts.add(Act(mods.toList(), null, 1))   // e.g. "NVDA" or "control" alone = tap that key
        return acts
    }

    private fun execute(acts: List<Act>) {
        for (a in acts) {
            if (a.text != null) { Remote.sendText(a.text); continue }
            a.mods.forEach { Remote.sendKey(it.first, it.second, true) }
            val k = a.key
            if (k != null) repeat(a.count) { Remote.tap(k.first, k.second) }
            a.mods.reversed().forEach { Remote.sendKey(it.first, it.second, false) }
        }
    }
}
