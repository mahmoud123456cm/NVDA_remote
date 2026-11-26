package com.example.nvdapocket

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/** Android key codes -> Windows virtual-key codes (the Boolean is the "extended key" flag). */
object KeyMap {
    fun toVk(code: Int): Pair<Int, Boolean>? {
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

    fun forward(e: KeyEvent): Boolean {
        val (vk, ext) = toVk(e.keyCode) ?: return false
        when (e.action) {
            KeyEvent.ACTION_DOWN -> Remote.sendKey(vk, ext, true)
            KeyEvent.ACTION_UP -> Remote.sendKey(vk, ext, false)
        }
        return true
    }
}

/** An invisible text field: everything typed on the phone keyboard goes straight to the PC. */
class KeyCaptureView(ctx: Context) : View(ctx) {
    init { isFocusable = true; isFocusableInTouchMode = true }
    override fun onCheckIsTextEditor() = true
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return RemoteConnection(this)
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        if (KeyMap.forward(event)) true else super.onKeyDown(keyCode, event)
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        if (KeyMap.forward(event)) true else super.onKeyUp(keyCode, event)
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.EditText"
        info.isEditable = true
    }
}

class RemoteConnection(v: View) : BaseInputConnection(v, false) {
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
    override fun sendKeyEvent(e: KeyEvent): Boolean = KeyMap.forward(e)
    override fun performEditorAction(a: Int): Boolean { Remote.tap(0x0D); return true }
}
