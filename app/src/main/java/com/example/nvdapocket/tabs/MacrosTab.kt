package com.example.nvdapocket.tabs

import android.widget.Button
import android.widget.LinearLayout
import com.example.nvdapocket.MainActivity
import com.example.nvdapocket.Remote
import com.example.nvdapocket.Store
import com.example.nvdapocket.Voice
import com.example.nvdapocket.ui.Ui

class MacrosTab(act: MainActivity, ui: Ui) : Tab(act, ui) {
    override val title = "Macros"

    private lateinit var macroList: LinearLayout
    private lateinit var recordBtn: Button

    override fun build(p: LinearLayout) {
        p.addView(ui.label("Record a macro"))
        p.addView(ui.note("Press Start, use any control (gestures, keys, voice), press Stop, then give it a name. You can say the name to replay it."))
        recordBtn = ui.button("Start recording") { toggleRecording() }
        p.addView(recordBtn)

        p.addView(ui.label("Add a voice shortcut"))
        val phrase = ui.field("What you say (e.g. open notepad)", "")
        val action = ui.field("What it does (e.g. windows r; type notepad; enter)", "")
        p.addView(phrase); p.addView(action)
        p.addView(ui.note("Steps are separated by semicolons. Each step uses the same words as voice commands. Macros can also be chosen as the action of a gesture."))
        p.addView(ui.button("Save shortcut") {
            val ph = phrase.text.toString().trim()
            val ac = action.text.toString().trim()
            if (ph.isEmpty() || ac.isEmpty()) { ui.toast("Fill both fields"); return@button }
            saveMacro(Store.Macro(ph, ph.lowercase(), "voice", ac))
            phrase.setText(""); action.setText("")
        })

        p.addView(ui.label("Saved macros and shortcuts (tap to run)"))
        macroList = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        p.addView(macroList)
        refreshMacros()
    }

    private fun saveMacro(m: Store.Macro) {
        val l = Store.macros(act).filter { it.name != m.name }.toMutableList()
        l.add(m)
        Store.saveMacros(act, l)
        Voice.reload()
        refreshMacros()
        ui.toast("Saved \"${m.name}\"")
    }

    private fun toggleRecording() {
        val r = Remote.recording
        if (r == null) {
            Remote.recording = mutableListOf()
            recordBtn.text = "Stop recording"
            ui.toast("Recording started")
        } else {
            Remote.recording = null
            recordBtn.text = "Start recording"
            val data = synchronized(r) { r.joinToString(";") }
            if (r.isEmpty()) ui.toast("Nothing was recorded")
            else ui.askText("Name this macro", "Name (also what you say)") { n -> saveMacro(Store.Macro(n, n.lowercase(), "raw", data)) }
        }
    }

    private fun refreshMacros() {
        macroList.removeAllViews()
        val ms = Store.macros(act)
        if (ms.isEmpty()) macroList.addView(ui.note("No macros yet."))
        for (m in ms) {
            val r = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            r.addView(ui.button("${m.name}  (say: ${m.phrase})") { Voice.runMacro(m) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(ui.button("Delete") {
                Store.saveMacros(act, Store.macros(act).filter { it.name != m.name })
                Voice.reload(); refreshMacros()
            })
            macroList.addView(r)
        }
    }
}
