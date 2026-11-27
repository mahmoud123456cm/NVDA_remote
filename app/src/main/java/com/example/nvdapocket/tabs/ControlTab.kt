package com.example.nvdapocket.tabs

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.content.Intent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ToggleButton
import org.json.JSONObject
import com.example.nvdapocket.GestureConfig
import com.example.nvdapocket.GestureEditor
import com.example.nvdapocket.GestureModeActivity
import com.example.nvdapocket.GesturePad
import com.example.nvdapocket.KeyCaptureView
import com.example.nvdapocket.MainActivity
import com.example.nvdapocket.Remote
import com.example.nvdapocket.Store
import com.example.nvdapocket.ui.Ui

class ControlTab(act: MainActivity, ui: Ui) : Tab(act, ui) {
    override val title = "Control"

    private val handler = Handler(Looper.getMainLooper())
    private val gcfg = GestureConfig.shared
    private val minPressMs = 60L
    private lateinit var pad: GesturePad
    private lateinit var capture: KeyCaptureView
    private lateinit var pcClipView: TextView
    private val modToggles = HashMap<Int, ToggleButton>()
    private var syncing = false

    override fun build(p: LinearLayout) {
        gcfg.load(act)

        // Gesture mode: the whole screen becomes the pad, so nothing else can be touched or scrolled by accident.
        p.addView(ui.button("Start gesture mode (full screen, press Back twice to leave)") {
            act.startActivity(Intent(act, GestureModeActivity::class.java))
        })
        p.addView(ui.button("Practice gestures (nothing is sent to the PC)") {
            act.startActivity(Intent(act, GestureModeActivity::class.java).putExtra("practice", true))
        })
        val blackBox = ui.check("Black screen in gesture mode", act.prefs.getBoolean("gmBlack", false))
        blackBox.setOnCheckedChangeListener { _, c -> act.prefs.edit().putBoolean("gmBlack", c).apply() }
        p.addView(blackBox)
        p.addView(ui.note("Or try gestures on the small pad below. Gesture mode is more reliable."))

        pad = GesturePad(act, ui, gcfg)
        p.addView(pad, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(300)))
        ui.seekRow(p, 160, act.prefs.getInt("dist", 60), { v -> "Gesture distance: ${40 + v} (higher = less sensitive)" },
            { v -> gcfg.distDp = 40 + v; act.prefs.edit().putInt("dist", v).apply() })
        val tapBox = ui.check("Taps and hold on the pad", gcfg.tapsOn)
        tapBox.setOnCheckedChangeListener { _, c -> gcfg.tapsOn = c; act.prefs.edit().putBoolean("taps", c).apply() }
        p.addView(tapBox)
        val editor = GestureEditor(ui, gcfg, pad)
        p.addView(ui.button("Edit gestures (choose what each one does)") { editor.open() })

        p.addView(ui.label("Live keyboard (tap here to type)"))
        capture = KeyCaptureView(act)
        capture.setBackgroundColor(0xFFE0E0E0.toInt())
        capture.contentDescription = "Live keyboard. Tap to open the keyboard. Everything you type goes straight to the PC."
        capture.setOnClickListener { showKeyboard() }
        p.addView(capture, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(80)))

        p.addView(ui.label("Modifier keys (toggle to hold)"))
        p.addView(ui.row(mod("Ctrl", 0x11), mod("Alt", 0x12), mod("Shift", 0x10), mod("Win", 0x5B, true), mod("NVDA/Insert", 0x2D, true)))
        p.addView(ui.label("Extra keys (tap to press, hold to repeat)"))
        p.addView(ui.row(holdKey("Esc", 0x1B), holdKey("Tab", 0x09), holdKey("Enter", 0x0D), holdKey("Space", 0x20), holdKey("Backspace", 0x08)))
        p.addView(ui.row(holdKey("Up", 0x26, true), holdKey("Down", 0x28, true), holdKey("Left", 0x25, true), holdKey("Right", 0x27, true)))
        p.addView(ui.row(holdKey("Home", 0x24, true), holdKey("End", 0x23, true), holdKey("Page up", 0x21, true), holdKey("Page down", 0x22, true)))
        p.addView(ui.row(holdKey("Delete", 0x2E, true), holdKey("Applications", 0x5D, true), holdKey("Caps lock", 0x14)))
        for (r in 0 until 2) p.addView(ui.row(*(1..6).map { holdKey("F${r * 6 + it}", 0x70 + r * 6 + it - 1) }.toTypedArray()))
        p.addView(ui.button("Send Ctrl+Alt+Delete") { Remote.sendJson(JSONObject().put("type", "send_SAS")) })

        // ----- clipboard, both directions -----
        p.addView(ui.label("Clipboard"))
        p.addView(ui.button("Send phone clipboard to PC") {
            ui.toast(if (Remote.pushClipboard(act)) "Clipboard sent" else "Clipboard is empty or not connected")
        })
        val auto = ui.check("Copy text the PC sends to its clipboard onto this phone automatically", Remote.clipIn)
        auto.setOnCheckedChangeListener { _, c -> Remote.clipIn = c; act.prefs.edit().putBoolean("clipIn", c).apply() }
        p.addView(auto)
        pcClipView = TextView(act).apply {
            text = "No text received from the PC yet."
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        p.addView(pcClipView)
        p.addView(ui.row(
            ui.button("Get PC clipboard") { Remote.fetchPcClipboard(false) },
            ui.button("Copy PC selection") { Remote.fetchPcClipboard(true) }))
        p.addView(ui.button("Copy the last PC text again") { Remote.recopyLastPcClip() })
        p.addView(ui.note("\"Get PC clipboard\" asks NVDA to read the PC clipboard aloud (NVDA+C) and copies what it says onto " +
            "this phone, so it works even when the PC does not send its clipboard by itself. \"Copy PC selection\" presses " +
            "Ctrl+C on the PC first. Voice or gesture words: get clipboard, get selection, last clipboard."))

        p.addView(ui.label("Send text to the PC"))
        val sendBox = ui.field("Text to send (any language works with the clipboard)", "")
        sendBox.setSingleLine(false)
        p.addView(sendBox)
        fun deliverBox(mode: String) {
            val t = sendBox.text.toString()
            if (t.isEmpty()) { ui.toast("Type something first"); return }
            ui.toast(Remote.deliver(mode, t))
        }
        p.addView(ui.row(
            ui.button("To clipboard") { deliverBox("clip") },
            ui.button("Clipboard + paste") { deliverBox("paste") },
            ui.button("Type it") { deliverBox("type") }))
        p.addView(ui.button("Release all held keys") { releaseAll() })
    }

    override fun onResume() {
        Remote.heldListener = { syncMods() }
        syncMods()
        Remote.clipListener = { t -> showPcClip(t) }
        Remote.lastPcClip?.let { showPcClip(it) }
    }

    override fun onPause() {
        Remote.heldListener = null
        Remote.clipListener = null
        pad.learn = null
    }

    private fun showPcClip(t: String) {
        val shown = if (t.length > 200) t.substring(0, 200) + "..." else t
        pcClipView.text = "From the PC (${t.length} characters): $shown"
    }

    private fun showKeyboard() {
        capture.requestFocus()
        (act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(capture, InputMethodManager.SHOW_IMPLICIT)
    }

    /**
     * A key button. A touch sends the key down at once and up when the finger lifts (never sooner than minPressMs
     * after the press, because a PC can miss a key that goes down and up in the same instant); holding repeats it.
     * A click that comes without touch events (a screen reader's double tap) presses the key once the same way.
     */
    private fun holdKey(l: String, vk: Int, ext: Boolean = false): Button {
        val b = Button(act)
        b.text = l; b.contentDescription = l; b.isAllCaps = false; b.textSize = 12f
        var downAt = 0L
        var down = false
        var pendingUp: Runnable? = null
        val rep = object : Runnable { override fun run() { Remote.sendKey(vk, ext, true); handler.postDelayed(this, 60) } }
        fun flushPendingUp() {
            val r = pendingUp ?: return
            handler.removeCallbacks(r)
            pendingUp = null
            r.run()
        }
        fun scheduleUp(delayMs: Long) {
            val r = Runnable { pendingUp = null; Remote.sendKey(vk, ext, false) }
            pendingUp = r
            if (delayMs > 0) handler.postDelayed(r, delayMs) else r.run()
        }
        b.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    flushPendingUp()
                    v.isPressed = true; down = true; downAt = SystemClock.uptimeMillis()
                    Remote.sendKey(vk, ext, true)
                    handler.postDelayed(rep, 450)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    handler.removeCallbacks(rep)
                    if (down) { down = false; scheduleUp(minPressMs - (SystemClock.uptimeMillis() - downAt)) }
                }
            }
            true
        }
        b.setOnClickListener {
            flushPendingUp()
            Remote.sendKey(vk, ext, true)
            scheduleUp(minPressMs)
        }
        return b
    }

    private fun mod(l: String, vk: Int, ext: Boolean = false) = ToggleButton(act).apply {
        textOn = "$l held"; textOff = l; text = l; isAllCaps = false; textSize = 11f
        modToggles[vk] = this
        setOnCheckedChangeListener { _, on ->
            if (syncing) return@setOnCheckedChangeListener
            Remote.setHeld(vk, on)
            Remote.sendKey(vk, ext, on)
        }
    }

    /** Makes the toggles show what is really held (keys are released when the connection drops or ends). */
    private fun syncMods() {
        syncing = true
        for ((vk, t) in modToggles) t.isChecked = Remote.isHeld(vk)
        syncing = false
    }

    private fun releaseAll() {
        fun walk(v: View) {
            if (v is ToggleButton) v.isChecked = false
            else if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(panel)
    }
}
