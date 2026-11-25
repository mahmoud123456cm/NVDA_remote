package com.example.nvdapocket

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
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
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var status: TextView
    private lateinit var connectBtn: Button
    private lateinit var muteBtn: Button
    private lateinit var content: LinearLayout
    private val panels = mutableListOf<LinearLayout>()
    private val tabBtns = mutableListOf<Button>()
    private val tabNames = listOf("Connect", "Control", "NVDA", "Voice", "Macros", "Settings")

    // connect tab
    private lateinit var profileSpinner: Spinner
    private lateinit var nameField: EditText
    private lateinit var hostField: EditText
    private lateinit var portField: EditText
    private lateinit var keyField: EditText
    private lateinit var trustBox: CheckBox
    private var profiles = mutableListOf<Store.Profile>()

    // control tab
    private lateinit var capture: KeyCaptureView
    private var distDp = 100
    private var tapsOn = true
    private var gMap: MutableMap<String, String> = mutableMapOf()

    // settings tab
    private lateinit var speakBox: CheckBox
    private lateinit var volumeBox: CheckBox
    private lateinit var vibrateBox: CheckBox
    private lateinit var speakResultBox: CheckBox

    // voice tab
    private lateinit var voiceBtn: Button
    private lateinit var voiceStatus: TextView
    private lateinit var dictBtn: Button
    private lateinit var wakeBox: CheckBox
    private lateinit var wakeField: EditText
    private var recognizer: SpeechRecognizer? = null
    private var voiceOn = false
    private var dictationOn = false

    // macros tab
    private lateinit var macroList: LinearLayout
    private lateinit var recordBtn: Button

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = Store.prefs(this)
        Remote.initTts(this)
        Voice.init(this)
        Remote.ttsRate = prefs.getFloat("rate", 1f)
        Remote.ttsPitch = prefs.getFloat("pitch", 1f)
        Remote.applyTts()
        Voice.lang = prefs.getString("vlang", "en") ?: "en"
        gMap = Store.gestureMap(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        // migrate the single connection saved by older versions
        val oldHost = prefs.getString("host", "") ?: ""
        if (Store.profiles(this).isEmpty() && oldHost.isNotEmpty()) {
            Store.saveProfiles(this, listOf(Store.Profile("Default", oldHost,
                (prefs.getString("port", "6837") ?: "6837").toIntOrNull() ?: 6837,
                prefs.getString("key", "") ?: "", prefs.getBoolean("trust", false))))
            prefs.edit().putString("last", "Default").apply()
        }

        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), 0) }
        connectBtn = Button(this).apply { text = "Connect" }
        connectBtn.setOnClickListener {
            if (Remote.active) { Remote.stop(this); updateUi() } else connectFromFields()
        }
        muteBtn = Button(this).apply { text = "Mute speech" }
        muteBtn.setOnClickListener { Remote.toggleMute(); updateUi() }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(connectBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(muteBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        status = TextView(this).apply { text = Remote.lastStatus; textSize = 16f }
        header.addView(top); header.addView(status)
        outer.addView(header)

        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(4), 0, dp(4), 0) }
        tabNames.forEachIndexed { i, n ->
            val tb = Button(this).apply { text = n; isAllCaps = false; textSize = 13f }
            tb.setOnClickListener { showTab(i) }
            tabBtns.add(tb); tabRow.addView(tb)
        }
        outer.addView(HorizontalScrollView(this).apply { addView(tabRow) })

        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        outer.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(outer)

        buildConnect(newPanel())
        buildControl(newPanel())
        buildNvda(newPanel())
        buildVoice(newPanel())
        buildMacros(newPanel())
        buildSettings(newPanel())
        showTab(1)
        updateUi()

        if (prefs.getBoolean("auto", false) && !Remote.active) connectLast()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.getBooleanExtra("connect_last", false) == true) {
            i.removeExtra("connect_last")
            if (!Remote.active) connectLast()
            ui.postDelayed({ moveTaskToBack(true) }, 500)
        }
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

    // ---------- common helpers ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    private fun label(t: String) = TextView(this).apply { text = t; textSize = 16f; setPadding(0, dp(12), 0, dp(4)) }
    private fun field(hint: String, value: String, numeric: Boolean = false) = EditText(this).apply {
        this.hint = hint; setText(value); setSingleLine(); contentDescription = hint
        if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
    }
    private fun check(t: String, on: Boolean) = CheckBox(this).apply { text = t; isChecked = on }
    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setOnClickListener { onClick() }
    }
    private fun newPanel(): LinearLayout {
        val p = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        content.addView(p); panels.add(p); return p
    }
    private fun showTab(i: Int) {
        panels.forEachIndexed { idx, p -> p.visibility = if (idx == i) View.VISIBLE else View.GONE }
        tabBtns.forEachIndexed { idx, tb -> tb.text = if (idx == i) "[" + tabNames[idx] + "]" else tabNames[idx] }
    }
    private fun row(vararg v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        v.forEach { addView(it, LinearLayout.LayoutParams(0, dp(56), 1f)) }
    }
    private fun grid(p: LinearLayout, items: List<Pair<String, String>>, per: Int) {
        var i = 0
        while (i < items.size) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (j in i until minOf(i + per, items.size)) {
                val (l, a) = items[j]
                r.addView(button(l) { Voice.run(a, false) }.apply { textSize = 13f }, LinearLayout.LayoutParams(0, dp(56), 1f))
            }
            p.addView(r); i += per
        }
    }
    private fun seekRow(p: LinearLayout, max: Int, progress: Int, fmt: (Int) -> String, onChange: (Int) -> Unit) {
        val tv = TextView(this).apply { text = fmt(progress) }
        val sb = SeekBar(this).apply { this.max = max; this.progress = progress }
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) { tv.text = fmt(v); onChange(v) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        p.addView(tv); p.addView(sb)
    }
    private fun askText(title: String, hint: String, onOk: (String) -> Unit) {
        val et = EditText(this); et.hint = hint
        AlertDialog.Builder(this).setTitle(title).setView(et)
            .setPositiveButton("Save") { _, _ -> val t = et.text.toString().trim(); if (t.isNotEmpty()) onOk(t) }
            .setNegativeButton("Cancel", null).show()
    }
    private fun updateUi() {
        connectBtn.text = if (Remote.active) "Disconnect" else "Connect"
        muteBtn.text = if (Remote.speak) "Mute speech" else "Unmute speech"
        if (::speakBox.isInitialized && speakBox.isChecked != Remote.speak) speakBox.isChecked = Remote.speak
    }
    private fun showKeyboard() {
        capture.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(capture, InputMethodManager.SHOW_IMPLICIT)
    }

    // ============ CONNECT TAB ============
    private fun buildConnect(p: LinearLayout) {
        profileSpinner = Spinner(this)
        nameField = field("Profile name", "")
        hostField = field("Server host", "")
        portField = field("Port", "6837", true)
        keyField = field("Key", "")
        trustBox = check("Accept untrusted certificate", false)
        val auto = check("Connect automatically when the app opens", prefs.getBoolean("auto", false))
        auto.setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("auto", c).apply() }
        p.addView(label("Saved connections"))
        p.addView(profileSpinner)
        listOf<View>(nameField, hostField, portField, keyField, trustBox, auto).forEach { p.addView(it) }
        p.addView(row(
            button("Save profile") { saveProfile() },
            button("Delete profile") { deleteProfile() }))
        p.addView(TextView(this).apply { text = "Pick a profile above, then press Connect at the top." })
        profileSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos < profiles.size) {
                    val pr = profiles[pos]
                    nameField.setText(pr.name); hostField.setText(pr.host); portField.setText(pr.port.toString())
                    keyField.setText(pr.key); trustBox.isChecked = pr.trust
                    prefs.edit().putString("last", pr.name).apply()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        refreshProfiles(prefs.getString("last", null))
    }

    private fun refreshProfiles(select: String?) {
        profiles = Store.profiles(this)
        val names = ArrayList<String>()
        profiles.forEach { names.add(it.name) }
        names.add("New profile")
        profileSpinner.adapter = ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, names)
        val idx = if (select != null) profiles.indexOfFirst { it.name == select } else -1
        profileSpinner.setSelection(if (idx >= 0) idx else names.size - 1)
    }

    private fun saveProfile() {
        val host = hostField.text.toString().trim()
        val key = keyField.text.toString().trim()
        if (host.isEmpty() || key.isEmpty()) { toast("Enter host and key first"); return }
        var name = nameField.text.toString().trim()
        if (name.isEmpty()) name = host
        val port = portField.text.toString().toIntOrNull() ?: 6837
        val list = Store.profiles(this).filter { it.name != name }.toMutableList()
        list.add(Store.Profile(name, host, port, key, trustBox.isChecked))
        Store.saveProfiles(this, list)
        prefs.edit().putString("last", name).apply()
        refreshProfiles(name)
        toast("Saved \"$name\"")
    }

    private fun deleteProfile() {
        val pos = profileSpinner.selectedItemPosition
        if (pos < 0 || pos >= profiles.size) { toast("Select a saved profile first"); return }
        val name = profiles[pos].name
        Store.saveProfiles(this, Store.profiles(this).filter { it.name != name })
        refreshProfiles(null)
        toast("Deleted \"$name\"")
    }

    private fun connectFromFields() {
        val h = hostField.text.toString().trim()
        val k = keyField.text.toString().trim()
        if (h.isEmpty() || k.isEmpty()) { status.text = "Enter host and key on the Connect tab"; showTab(0); return }
        val name = nameField.text.toString().trim()
        if (name.isNotEmpty()) prefs.edit().putString("last", name).apply()
        Remote.start(this, h, portField.text.toString().toIntOrNull() ?: 6837, k, trustBox.isChecked)
        updateUi()
    }

    private fun connectLast() {
        val ps = Store.profiles(this)
        val last = prefs.getString("last", null)
        val pr = ps.firstOrNull { it.name == last } ?: ps.firstOrNull()
        if (pr != null) { Remote.start(this, pr.host, pr.port, pr.key, pr.trust); updateUi() }
        else if (hostField.text.isNotEmpty()) connectFromFields()
    }

    // ============ CONTROL TAB ============
    private fun buildControl(p: LinearLayout) {
        val pad = GesturePad(this)
        p.addView(pad, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(300)))
        seekRow(p, 160, prefs.getInt("dist", 60), { v -> "Gesture distance: ${40 + v} (higher = less sensitive)" },
            { v -> distDp = 40 + v; prefs.edit().putInt("dist", v).apply() })
        distDp = 40 + prefs.getInt("dist", 60)
        tapsOn = prefs.getBoolean("taps", true)
        val tapBox = check("Taps and hold on the pad", tapsOn)
        tapBox.setOnCheckedChangeListener { _, c -> tapsOn = c; prefs.edit().putBoolean("taps", c).apply() }
        p.addView(tapBox)
        p.addView(button("Edit gestures (choose what each one does)") { editGestures() })

        p.addView(label("Live keyboard (tap here to type)"))
        capture = KeyCaptureView(this)
        capture.setBackgroundColor(0xFFE0E0E0.toInt())
        capture.contentDescription = "Live keyboard. Tap to open the keyboard. Everything you type goes straight to the PC."
        capture.setOnClickListener { showKeyboard() }
        p.addView(capture, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(80)))

        p.addView(label("Modifier keys (toggle to hold)"))
        p.addView(row(mod("Ctrl", 0x11), mod("Alt", 0x12), mod("Shift", 0x10), mod("Win", 0x5B, true), mod("NVDA/Insert", 0x2D, true)))
        p.addView(label("Extra keys (hold a key to repeat it)"))
        p.addView(row(holdKey("Esc", 0x1B), holdKey("Tab", 0x09), holdKey("Enter", 0x0D), holdKey("Space", 0x20), holdKey("Backspace", 0x08)))
        p.addView(row(holdKey("Up", 0x26, true), holdKey("Down", 0x28, true), holdKey("Left", 0x25, true), holdKey("Right", 0x27, true)))
        p.addView(row(holdKey("Home", 0x24, true), holdKey("End", 0x23, true), holdKey("Page up", 0x21, true), holdKey("Page down", 0x22, true)))
        p.addView(row(holdKey("Delete", 0x2E, true), holdKey("Applications", 0x5D, true), holdKey("Caps lock", 0x14)))
        for (r in 0 until 2) p.addView(row(*(1..6).map { holdKey("F${r * 6 + it}", 0x70 + r * 6 + it - 1) }.toTypedArray()))
        p.addView(button("Send Ctrl+Alt+Delete") { Remote.sendJson(JSONObject().put("type", "send_SAS")) })
        p.addView(button("Send phone clipboard to PC") {
            toast(if (Remote.pushClipboard(this)) "Clipboard sent" else "Clipboard is empty or not connected")
        })
        p.addView(button("Release all held keys") { releaseAll() })
    }

    private fun holdKey(l: String, vk: Int, ext: Boolean = false): Button {
        val b = Button(this)
        b.text = l; b.contentDescription = l; b.isAllCaps = false; b.textSize = 12f
        val rep = object : Runnable { override fun run() { Remote.sendKey(vk, ext, true); ui.postDelayed(this, 60) } }
        b.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { v.isPressed = true; Remote.sendKey(vk, ext, true); ui.postDelayed(rep, 450) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; ui.removeCallbacks(rep); Remote.sendKey(vk, ext, false) }
            }
            true
        }
        return b
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
        walk(content)
    }

    private fun editGestures() {
        val lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        val fields = LinkedHashMap<String, EditText>()
        for ((k, lb) in Store.gestureLabels) {
            lay.addView(TextView(this).apply { text = lb })
            val et = EditText(this).apply { setText(gMap[k] ?: ""); setSingleLine(); contentDescription = lb }
            fields[k] = et; lay.addView(et)
        }
        AlertDialog.Builder(this).setTitle("Edit gestures")
            .setView(ScrollView(this).apply { addView(lay) })
            .setPositiveButton("Save") { _, _ ->
                val m = LinkedHashMap<String, String>()
                for ((k, et) in fields) m[k] = et.text.toString().trim()
                gMap = m; Store.saveGestureMap(this, m)
            }
            .setNeutralButton("Reset") { _, _ -> gMap = LinkedHashMap(Store.gestureDefaults); Store.saveGestureMap(this, gMap) }
            .setNegativeButton("Cancel", null).show()
    }

    // ---------- gesture pad ----------
    inner class GesturePad(ctx: Context) : TextView(ctx) {
        private var ax = 0f; private var ay = 0f
        private var sx = 0f; private var sy = 0f
        private var lx = 0f; private var ly = 0f
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
                "3 fingers: swipe up/down = Home / End\nswipe left/right = Backspace / Delete\ntap = Alt+Tab\n\n" +
                "(You can change all of these with Edit gestures.)"
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
                if (now - lastFire < 400) return
                lastFire = now
            }
            val action = gMap["$n$d"] ?: return
            if (action.isEmpty()) return
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            Voice.run(action, false)
        }
    }

    // ============ NVDA TAB ============
    private fun nvdaKey(shift: Boolean, vk: Int, ext: Boolean) {
        Remote.sendKey(0x2D, true, true)
        if (shift) Remote.sendKey(0x10, false, true)
        Remote.tap(vk, ext)
        if (shift) Remote.sendKey(0x10, false, false)
        Remote.sendKey(0x2D, true, false)
    }

    private fun buildNvda(p: LinearLayout) {
        p.addView(label("NVDA commands (laptop and desktop layouts share these)"))
        grid(p, listOf("Say all" to "say all", "Read line" to "read line", "Read title" to "read title",
            "Where am I" to "where am i", "NVDA menu" to "nvda menu", "Speech mode" to "speech mode",
            "Stop speech" to "stop speech", "Pause speech" to "pause speech", "Elements list" to "elements list",
            "NVDA settings" to "nvda settings"), 2)

        p.addView(label("Browse mode navigation"))
        val prev = ToggleButton(this).apply { textOn = "Going backwards"; textOff = "Going forwards"; text = "Going forwards"; isAllCaps = false }
        p.addView(prev)
        val keys = listOf("Heading" to 0x48, "Link" to 0x4B, "Button" to 0x42, "Table" to 0x54, "Landmark" to 0x44,
            "Form field" to 0x46, "Edit field" to 0x45, "List" to 0x4C, "Graphic" to 0x47,
            "Heading 1" to 0x31, "Heading 2" to 0x32, "Heading 3" to 0x33)
        var i = 0
        while (i < keys.size) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (j in i until minOf(i + 3, keys.size)) {
                val (l, vk) = keys[j]
                r.addView(button(l) { if (prev.isChecked) Remote.combo(0x10, vk) else Remote.tap(vk) }.apply { textSize = 13f },
                    LinearLayout.LayoutParams(0, dp(56), 1f))
            }
            p.addView(r); i += 3
        }

        p.addView(label("Object navigation (the closest thing to a mouse)"))
        val desktop = check("Desktop layout (numpad keys). Unchecked = laptop layout", false)
        p.addView(desktop)
        p.addView(row(
            button("Parent") { if (desktop.isChecked) nvdaKey(false, 0x26, false) else nvdaKey(true, 0x26, true) },
            button("Child") { if (desktop.isChecked) nvdaKey(false, 0x28, false) else nvdaKey(true, 0x28, true) }))
        p.addView(row(
            button("Previous") { if (desktop.isChecked) nvdaKey(false, 0x25, false) else nvdaKey(true, 0x25, true) },
            button("Next") { if (desktop.isChecked) nvdaKey(false, 0x27, false) else nvdaKey(true, 0x27, true) }))
        p.addView(row(
            button("Activate") { if (desktop.isChecked) nvdaKey(false, 0x0D, true) else nvdaKey(false, 0x0D, false) },
            button("Report") { if (desktop.isChecked) nvdaKey(false, 0x0C, false) else nvdaKey(true, 0x4F, false) }))

        p.addView(label("Windows shortcuts"))
        grid(p, listOf("Start menu" to "start menu", "Switch window" to "switch window", "Close window" to "close window",
            "Show desktop" to "show desktop", "Run dialog" to "run dialog", "File explorer" to "file explorer",
            "Task manager" to "task manager", "Lock screen" to "lock screen", "Select all" to "select all",
            "Copy" to "copy", "Paste" to "paste", "Undo" to "undo"), 3)
    }

    // ============ VOICE TAB ============
    private fun recLang() = when (Voice.lang) { "fr" -> "fr-FR"; "ar" -> "ar-TN"; else -> "en-US" }

    private fun buildVoice(p: LinearLayout) {
        voiceBtn = button("Start voice commands") {
            if (voiceOn) stopVoice() else { voiceOn = true; voiceBtn.text = "Stop voice commands"; startVoice() }
        }
        voiceStatus = TextView(this).apply { text = "Voice: off" }
        p.addView(voiceBtn); p.addView(voiceStatus)

        p.addView(label("Voice language"))
        val langs = listOf("English" to "en", "Français" to "fr", "العربية" to "ar")
        val sp = Spinner(this)
        sp.adapter = ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, langs.map { it.first })
        sp.setSelection(maxOf(0, langs.indexOfFirst { it.second == Voice.lang }))
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                Voice.lang = langs[pos].second
                prefs.edit().putString("vlang", Voice.lang).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        p.addView(sp)

        wakeBox = check("Only react after a wake word", prefs.getBoolean("wakeOn", false))
        wakeBox.setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("wakeOn", c).apply() }
        wakeField = field("Wake word", prefs.getString("wake", "computer") ?: "computer")
        wakeField.setOnFocusChangeListener { _, _ -> prefs.edit().putString("wake", wakeField.text.toString()).apply() }
        p.addView(wakeBox); p.addView(wakeField)

        dictBtn = button("Start dictation (everything you say is typed)") { setDictation(!dictationOn) }
        p.addView(dictBtn)

        p.addView(label("What you can say"))
        p.addView(TextView(this).apply {
            text = "Keys: enter, tab, escape, space, backspace, delete, up, down, left, right, home, end, page up, page down, f 1 to f 12, numpad 5, comma, period, slash...\n" +
                "Repeat: \"down 5\".\n" +
                "Combinations: \"control shift escape\", \"alt f4\", \"NVDA n\", \"NVDA plus s\", \"insert space\".\n" +
                "Letters after a modifier: say the letter or use alpha, bravo, charlie...\n" +
                "Shortcuts: select all, copy, paste, undo, save, find, new tab, close window, switch window, start menu, show desktop, task manager.\n" +
                "NVDA: say all, read line, read title, where am I, nvda menu, speech mode, stop speech, pause speech, elements list.\n" +
                "Browse mode: next heading, previous heading, next link, next button, next table, next landmark, next form field.\n" +
                "Text: \"type hello world\".\n" +
                "Clipboard: \"push clipboard\".\n" +
                "Control: \"start dictation\", \"stop dictation\", \"stop listening\".\n" +
                "Your own phrases from the Macros tab work too."
        })

        p.addView(label("Feedback"))
        vibrateBox = check("Vibrate when a command is understood or not", prefs.getBoolean("vib", true))
        vibrateBox.setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("vib", c).apply() }
        speakResultBox = check("Say \"done\" or \"not understood\" on the phone", prefs.getBoolean("sayres", false))
        speakResultBox.setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("sayres", c).apply() }
        p.addView(vibrateBox); p.addView(speakResultBox)
    }

    private fun feedback(ok: Boolean) {
        if (vibrateBox.isChecked) Remote.vibrate(if (ok) 40 else 150)
        if (speakResultBox.isChecked) Remote.say(if (ok) "Done" else "Not understood")
    }

    private fun setDictation(on: Boolean) {
        dictationOn = on
        dictBtn.text = if (on) "Stop dictation" else "Start dictation (everything you say is typed)"
        voiceStatus.text = if (on) "Voice: dictation on (say stop dictation to end)" else "Voice: listening..."
        if (on && !voiceOn) { voiceOn = true; voiceBtn.text = "Stop voice commands"; startVoice() }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) startVoice()
            else { voiceStatus.text = "Voice: microphone permission denied"; stopVoice() }
        }
    }

    private fun stopVoice() {
        voiceOn = false
        dictationOn = false
        try { recognizer?.cancel() } catch (_: Exception) {}
        if (::voiceBtn.isInitialized) voiceBtn.text = "Start voice commands"
        if (::dictBtn.isInitialized) dictBtn.text = "Start dictation (everything you say is typed)"
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
                override fun onReadyForSpeech(p: Bundle?) { if (!dictationOn) voiceStatus.text = "Voice: listening..." }
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
                    if (list != null && list.isNotEmpty()) handleVoice(list)
                    if (voiceOn) ui.postDelayed({ startVoice() }, 300)
                }
            })
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, recLang())
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        recognizer!!.startListening(i)
    }

    private fun handleVoice(list: List<String>) {
        val low = list.map { it.lowercase() }
        if (low.any { it.contains("stop listening") }) { stopVoice(); return }
        if (dictationOn) {
            if (low.any { it.contains("stop dictation") }) { setDictation(false); feedback(true); return }
            Remote.sendText(list[0] + " ")
            voiceStatus.text = "Dictated: ${list[0]}"
            return
        }
        var cands = list
        if (wakeBox.isChecked) {
            val w = wakeField.text.toString().trim().lowercase()
            if (w.isNotEmpty()) {
                cands = list.filter { it.lowercase().startsWith(w) }.map { it.substring(w.length).trimStart(',', ' ', '.') }
                if (cands.isEmpty()) return
            }
        }
        if (cands.any { it.lowercase().contains("start dictation") }) { setDictation(true); feedback(true); return }
        for (h in cands) {
            if (Voice.run(h, true)) { voiceStatus.text = "Heard: $h"; feedback(true); return }
        }
        voiceStatus.text = "Heard: ${cands[0]} (not a command)"
        feedback(false)
    }

    // ============ MACROS TAB ============
    private fun buildMacros(p: LinearLayout) {
        p.addView(label("Record a macro"))
        p.addView(TextView(this).apply { text = "Press Start, use any control (gestures, keys, voice), press Stop, then give it a name. You can say the name to replay it." })
        recordBtn = button("Start recording") { toggleRecording() }
        p.addView(recordBtn)

        p.addView(label("Add a voice shortcut"))
        val phrase = field("What you say (e.g. open notepad)", "")
        val action = field("What it does (e.g. windows r; type notepad; enter)", "")
        p.addView(phrase); p.addView(action)
        p.addView(TextView(this).apply { text = "Steps are separated by semicolons. Each step uses the same words as voice commands." })
        p.addView(button("Save shortcut") {
            val ph = phrase.text.toString().trim()
            val ac = action.text.toString().trim()
            if (ph.isEmpty() || ac.isEmpty()) { toast("Fill both fields"); return@button }
            saveMacro(Store.Macro(ph, ph.lowercase(), "voice", ac))
            phrase.setText(""); action.setText("")
        })

        p.addView(label("Saved macros and shortcuts (tap to run)"))
        macroList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        p.addView(macroList)
        refreshMacros()
    }

    private fun saveMacro(m: Store.Macro) {
        val l = Store.macros(this).filter { it.name != m.name }.toMutableList()
        l.add(m)
        Store.saveMacros(this, l)
        Voice.reload()
        refreshMacros()
        toast("Saved \"${m.name}\"")
    }

    private fun toggleRecording() {
        val r = Remote.recording
        if (r == null) {
            Remote.recording = mutableListOf()
            recordBtn.text = "Stop recording"
            toast("Recording started")
        } else {
            Remote.recording = null
            recordBtn.text = "Start recording"
            val data = synchronized(r) { r.joinToString(";") }
            if (r.isEmpty()) toast("Nothing was recorded")
            else askText("Name this macro", "Name (also what you say)") { n -> saveMacro(Store.Macro(n, n.lowercase(), "raw", data)) }
        }
    }

    private fun refreshMacros() {
        macroList.removeAllViews()
        val ms = Store.macros(this)
        if (ms.isEmpty()) macroList.addView(TextView(this).apply { text = "No macros yet." })
        for (m in ms) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            r.addView(button("${m.name}  (say: ${m.phrase})") { Voice.runMacro(m) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(button("Delete") {
                Store.saveMacros(this, Store.macros(this).filter { it.name != m.name })
                Voice.reload(); refreshMacros()
            })
            macroList.addView(r)
        }
    }

    // ============ SETTINGS TAB ============
    private fun buildSettings(p: LinearLayout) {
        p.addView(label("Speech from the PC"))
        speakBox = check("Speak remote speech on this phone", Remote.speak)
        speakBox.setOnCheckedChangeListener { _, c -> if (c != Remote.speak) Remote.setSpeak(c); updateUi() }
        p.addView(speakBox)
        seekRow(p, 30, ((Remote.ttsRate - 0.5f) / 0.05f).toInt(), { v -> "Speech speed: %.2f".format(0.5f + v * 0.05f) },
            { v -> Remote.ttsRate = 0.5f + v * 0.05f; Remote.applyTts(); prefs.edit().putFloat("rate", Remote.ttsRate).apply() })
        seekRow(p, 30, ((Remote.ttsPitch - 0.5f) / 0.05f).toInt(), { v -> "Speech pitch: %.2f".format(0.5f + v * 0.05f) },
            { v -> Remote.ttsPitch = 0.5f + v * 0.05f; Remote.applyTts(); prefs.edit().putFloat("pitch", Remote.ttsPitch).apply() })
        p.addView(button("Choose the speech voice and engine (Android settings)") {
            try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) } catch (_: Exception) { toast("Could not open settings") }
        })
        val shake = check("Shake the phone to mute or unmute speech (applies next time you connect)", prefs.getBoolean("shake", true))
        shake.setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("shake", c).apply() }
        p.addView(shake)

        p.addView(label("Controls"))
        volumeBox = check("Volume keys act as Up and Down arrows", false)
        p.addView(volumeBox)

        p.addView(label("Background"))
        p.addView(button("Battery settings (set this app to Unrestricted)") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        })

        p.addView(label("Updates"))
        p.addView(TextView(this).apply { text = "Installed build: ${currentBuild()}" })
        p.addView(button("Check for updates") { checkUpdate() })
        p.addView(TextView(this).apply { text = "Quick tile: swipe down the Android quick settings, edit tiles, and add \"NVDA Pocket\" for one-tap connect." })
    }

    @Suppress("DEPRECATION")
    private fun currentBuild(): Int = try { packageManager.getPackageInfo(packageName, 0).versionCode } catch (_: Exception) { 0 }

    private fun checkUpdate() {
        toast("Checking for updates...")
        Thread {
            try {
                val c = URL("https://api.github.com/repos/mahmoud123456cm/NVDA_remote/releases/latest").openConnection() as HttpURLConnection
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.connectTimeout = 10000; c.readTimeout = 10000
                val j = JSONObject(c.inputStream.bufferedReader().readText())
                val n = j.getString("tag_name").removePrefix("build-").toIntOrNull() ?: 0
                val assets = j.optJSONArray("assets")
                val dl = if (assets != null && assets.length() > 0) assets.getJSONObject(0).getString("browser_download_url") else j.getString("html_url")
                ui.post {
                    if (n > currentBuild()) {
                        toast("New build $n found. Opening the download...")
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(dl)))
                    } else toast("You are up to date (build ${currentBuild()})")
                }
            } catch (e: Exception) {
                ui.post { toast("Update check failed: ${e.message}") }
            }
        }.start()
    }

    // ============ LIVE KEYBOARD CAPTURE ============
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
        if (::volumeBox.isInitialized && volumeBox.isChecked && Remote.connected) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) { Remote.sendKey(0x26, true, true); return true }
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) { Remote.sendKey(0x28, true, true); return true }
        }
        return super.onKeyDown(keyCode, event)
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (::volumeBox.isInitialized && volumeBox.isChecked && Remote.connected) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) { Remote.sendKey(0x26, true, false); return true }
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) { Remote.sendKey(0x28, true, false); return true }
        }
        return super.onKeyUp(keyCode, event)
    }
}
