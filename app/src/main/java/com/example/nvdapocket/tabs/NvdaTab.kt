package com.example.nvdapocket.tabs

import android.widget.LinearLayout
import android.widget.ToggleButton
import com.example.nvdapocket.MainActivity
import com.example.nvdapocket.Remote
import com.example.nvdapocket.ui.Ui

class NvdaTab(act: MainActivity, ui: Ui) : Tab(act, ui) {
    override val title = "NVDA"

    private fun nvdaKey(shift: Boolean, vk: Int, ext: Boolean) {
        Remote.sendKey(0x2D, true, true)
        if (shift) Remote.sendKey(0x10, false, true)
        Remote.tap(vk, ext)
        if (shift) Remote.sendKey(0x10, false, false)
        Remote.sendKey(0x2D, true, false)
    }

    override fun build(p: LinearLayout) {
        p.addView(ui.label("NVDA commands (laptop and desktop layouts share these)"))
        ui.grid(p, listOf("Say all" to "say all", "Read line" to "read line", "Read title" to "read title",
            "Where am I" to "where am i", "NVDA menu" to "nvda menu", "Speech mode" to "speech mode",
            "Stop speech" to "stop speech", "Pause speech" to "pause speech", "Elements list" to "elements list",
            "NVDA settings" to "nvda settings"), 2)

        p.addView(ui.label("Browse mode navigation"))
        val prev = ToggleButton(act).apply { textOn = "Going backwards"; textOff = "Going forwards"; text = "Going forwards"; isAllCaps = false }
        p.addView(prev)
        val keys = listOf("Heading" to 0x48, "Link" to 0x4B, "Button" to 0x42, "Table" to 0x54, "Landmark" to 0x44,
            "Form field" to 0x46, "Edit field" to 0x45, "List" to 0x4C, "Graphic" to 0x47,
            "Heading 1" to 0x31, "Heading 2" to 0x32, "Heading 3" to 0x33)
        var i = 0
        while (i < keys.size) {
            val r = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            for (j in i until minOf(i + 3, keys.size)) {
                val (l, vk) = keys[j]
                r.addView(ui.button(l) { if (prev.isChecked) Remote.combo(0x10, vk) else Remote.tap(vk) }.apply { textSize = 13f },
                    LinearLayout.LayoutParams(0, ui.dp(56), 1f))
            }
            p.addView(r); i += 3
        }

        p.addView(ui.label("Object navigation (the closest thing to a mouse)"))
        val desktop = ui.check("Desktop layout (numpad keys). Unchecked = laptop layout", false)
        p.addView(desktop)
        p.addView(ui.row(
            ui.button("Parent") { if (desktop.isChecked) nvdaKey(false, 0x26, false) else nvdaKey(true, 0x26, true) },
            ui.button("Child") { if (desktop.isChecked) nvdaKey(false, 0x28, false) else nvdaKey(true, 0x28, true) }))
        p.addView(ui.row(
            ui.button("Previous") { if (desktop.isChecked) nvdaKey(false, 0x25, false) else nvdaKey(true, 0x25, true) },
            ui.button("Next") { if (desktop.isChecked) nvdaKey(false, 0x27, false) else nvdaKey(true, 0x27, true) }))
        p.addView(ui.row(
            ui.button("Activate") { if (desktop.isChecked) nvdaKey(false, 0x0D, true) else nvdaKey(false, 0x0D, false) },
            ui.button("Report") { if (desktop.isChecked) nvdaKey(false, 0x0C, false) else nvdaKey(true, 0x4F, false) }))

        p.addView(ui.label("Windows shortcuts"))
        ui.grid(p, listOf("Start menu" to "start menu", "Switch window" to "switch window", "Close window" to "close window",
            "Show desktop" to "show desktop", "Run dialog" to "run dialog", "File explorer" to "file explorer",
            "Task manager" to "task manager", "Lock screen" to "lock screen", "Select all" to "select all",
            "Copy" to "copy", "Paste" to "paste", "Undo" to "undo"), 3)
    }
}
