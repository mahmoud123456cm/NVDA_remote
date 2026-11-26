package com.example.nvdapocket

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import com.example.nvdapocket.ui.Ui

/**
 * A screen that is only the gesture pad: no buttons to hit, nothing to scroll, status and navigation bars hidden.
 * Press Back twice within two seconds to leave. In practice mode the pad says what each gesture is and what it is set
 * to do, and nothing is sent to the PC.
 */
class GestureModeActivity : Activity() {
    private lateinit var pad: GesturePad
    private var practice = false
    private var lastBack = 0L

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        practice = intent?.getBooleanExtra("practice", false) == true
        val ui = Ui(this)
        Remote.initTts(this)
        val cfg = GestureConfig.shared
        cfg.load(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        pad = GesturePad(this, ui, cfg)
        pad.practice = practice
        val black = Store.prefs(this).getBoolean("gmBlack", false)
        if (black) { pad.setBackgroundColor(0xFF000000.toInt()); pad.setTextColor(0xFFBBBBBB.toInt()) }
        pad.text = if (practice)
            "PRACTICE MODE\n\nDo any gesture and the phone says what it is and what it is set to do.\nNothing is sent to the PC.\n\nPress Back twice to leave."
        else
            "GESTURE MODE\n\nThe whole screen is the gesture pad.\n\nPress Back twice to leave."
        setContentView(FrameLayout(this).apply {
            addView(pad, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        })
        goImmersive()
        Cue.play(if (practice) "practice_on" else "gesture_on")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    @Suppress("DEPRECATION")
    private fun goImmersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        val now = SystemClock.uptimeMillis()
        if (now - lastBack < 2000) {
            Cue.play("gesture_off")
            finish()
        } else {
            lastBack = now
            Remote.vibrate(60)
            Cue.play("back_again")
        }
    }

    // Volume keys can act as Up/Down arrows (Settings). Not in practice mode: nothing is sent there.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        (!practice && VolKeys.handle(this, event)) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        (!practice && VolKeys.handle(this, event)) || super.onKeyUp(keyCode, event)
}

/** Volume keys as Up / Down arrow keys on the PC, when the setting is on and the PC is connected. */
object VolKeys {
    fun on(c: Context): Boolean = Store.prefs(c).getBoolean("volkeys", false)

    /** Returns true when the key was used. */
    fun handle(c: Context, e: KeyEvent): Boolean {
        if (!on(c) || !Remote.connected) return false
        val vk = when (e.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> 0x26
            KeyEvent.KEYCODE_VOLUME_DOWN -> 0x28
            else -> return false
        }
        when (e.action) {
            KeyEvent.ACTION_DOWN -> Remote.sendKey(vk, true, true)
            KeyEvent.ACTION_UP -> Remote.sendKey(vk, true, false)
        }
        return true
    }
}

/** The ready-made actions offered when choosing what a gesture or a macro step does. */
object QuickPicks {
    fun list(c: Context, excludePhrases: Set<String> = emptySet(), withMacros: Boolean = true): List<Pair<String, String>> {
        val l = ArrayList<Pair<String, String>>()
        l.addAll(listOf(
            "Enter" to "enter", "Escape" to "escape", "Tab" to "tab", "Shift+Tab" to "shift tab", "Space" to "space",
            "Backspace" to "backspace", "Delete" to "delete",
            "Up arrow" to "up", "Down arrow" to "down", "Left arrow" to "left", "Right arrow" to "right",
            "Home" to "home", "End" to "end", "Page up" to "page up", "Page down" to "page down",
            "Copy" to "copy", "Cut" to "cut", "Paste" to "paste", "Select all" to "select all",
            "Undo" to "undo", "Redo" to "redo", "Save" to "save", "Find" to "find",
            "Alt+Tab (switch window)" to "alt tab", "Close window" to "close window", "Close tab" to "close tab", "New tab" to "new tab",
            "Start menu" to "start menu", "Show desktop" to "show desktop", "Task manager" to "task manager", "Lock the PC" to "lock screen",
            "NVDA: say all" to "say all", "NVDA: read line" to "read line", "NVDA: read title" to "read title",
            "NVDA: where am I" to "where am i", "NVDA menu" to "nvda menu", "NVDA: elements list" to "elements list",
            "Stop speech" to "stop speech", "Pause speech" to "pause speech", "Speech mode" to "speech mode",
            "Next heading" to "next heading", "Previous heading" to "previous heading", "Next link" to "next link",
            "Next button" to "next button", "Next landmark" to "next landmark", "Next form field" to "next form field",
            "Next edit field" to "next edit field", "Next table" to "next table",
            "Send phone clipboard to PC" to "push clipboard", "Get PC clipboard onto the phone" to "get clipboard",
            "Copy PC selection onto the phone" to "get selection", "Copy last PC clipboard again" to "last clipboard",
            "Mute or unmute phone speech" to "toggle mute", "Send Ctrl+Alt+Delete" to "control alt delete"))
        if (withMacros) {
            for (m in Store.macros(c)) {
                val first = m.phrases().firstOrNull() ?: continue
                if (first !in excludePhrases) l.add("Macro: ${m.name}" to first)
            }
        }
        return l
    }
}
