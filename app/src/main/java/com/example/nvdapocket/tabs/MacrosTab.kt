package com.example.nvdapocket.tabs

import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Button
import android.widget.LinearLayout
import com.example.nvdapocket.MacroEditor
import com.example.nvdapocket.MainActivity
import com.example.nvdapocket.Remote
import com.example.nvdapocket.Store
import com.example.nvdapocket.Voice
import com.example.nvdapocket.ui.Ui

class MacrosTab(act: MainActivity, ui: Ui) : Tab(act, ui) {
    override val title = "Macros"

    private lateinit var macroList: LinearLayout
    private lateinit var recordBtn: Button
    private val editor = MacroEditor(ui) { refreshMacros() }

    override fun build(p: LinearLayout) {
        p.addView(ui.button("New macro") { editor.open(null) })
        p.addView(ui.note("A macro is a list of steps you can run by saying its phrase or by choosing it as a gesture's action."))

        p.addView(ui.label("Record a macro"))
        p.addView(ui.note("Press Start, use any control (gestures, keys, voice), press Stop, then give it a name. You can then edit it, or convert it to steps."))
        recordBtn = ui.button("Start recording") { toggleRecording() }
        p.addView(recordBtn)

        p.addView(ui.label("Saved macros (tap to run)"))
        macroList = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        p.addView(macroList)

        p.addView(ui.label("Backup"))
        p.addView(ui.row(
            ui.button("Export macros") { exportMacros() },
            ui.button("Import from clipboard") { importMacros() }))
        p.addView(ui.note("Export opens the share sheet (choose Copy, or send it to yourself). To import, copy the exported text, then press Import from clipboard. Macros with the same name are replaced."))
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
            val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            val kindNote = if (m.kind == "raw") "recorded keys" else "${m.data.split(";").count { it.isNotBlank() }} steps"
            box.addView(ui.button("${m.name}  (say: ${m.phrases().joinToString(", ")}; $kindNote)") { Voice.runMacro(m) })
            box.addView(ui.row(
                ui.button("Edit") { editor.open(m) },
                ui.button("Delete") { confirmDelete(m) }))
            macroList.addView(box)
        }
    }

    private fun confirmDelete(m: Store.Macro) {
        AlertDialog.Builder(act).setTitle("Delete \"${m.name}\"?")
            .setMessage("Gestures that run this macro will stop working until you change them.")
            .setPositiveButton("Delete") { _, _ ->
                Store.saveMacros(act, Store.macros(act).filter { it.name != m.name })
                Voice.reload(); refreshMacros()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun exportMacros() {
        val ms = Store.macros(act)
        if (ms.isEmpty()) { ui.toast("No macros to export"); return }
        val i = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "NVDA Pocket macros")
            .putExtra(Intent.EXTRA_TEXT, Store.macrosToJson(ms))
        act.startActivity(Intent.createChooser(i, "Export macros"))
    }

    private fun importMacros() {
        val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(act)?.toString() else null
        val list = if (text.isNullOrBlank()) null else Store.macrosFromJson(text)
        if (list == null) { ui.toast("The clipboard does not hold exported macros"); return }
        if (list.isEmpty()) { ui.toast("No macros found in the clipboard"); return }
        AlertDialog.Builder(act).setTitle("Import ${list.size} macros?")
            .setMessage(list.joinToString("\n") { it.name })
            .setPositiveButton("Import") { _, _ ->
                val names = list.map { it.name }.toSet()
                val merged = Store.macros(act).filter { it.name !in names }.toMutableList()
                merged.addAll(list)
                Store.saveMacros(act, merged)
                Voice.reload(); refreshMacros()
                ui.toast("Imported ${list.size} macros")
            }
            .setNegativeButton("Cancel", null).show()
    }
}
