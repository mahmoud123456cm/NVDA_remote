package com.example.nvdapocket

import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import com.example.nvdapocket.ui.Ui

/**
 * Create and edit a macro: its name, the phrases you say to run it, and its steps (one per row, with move up, move
 * down and remove). Recorded macros can be converted to readable steps. Warns about phrases that clash, and keeps
 * gestures that run a renamed macro pointing at it.
 */
class MacroEditor(private val ui: Ui, private val onChanged: () -> Unit) {
    private val act = ui.act

    fun open(existing: Store.Macro?) {
        val lay = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8))
        }
        val nameF = ui.field("Name", existing?.name.orEmpty())
        val phrasesF = ui.field("What you say (several allowed, separated by commas)", existing?.phrase.orEmpty())
        lay.addView(ui.label("Name")); lay.addView(nameF)
        lay.addView(ui.label("What you say to run it")); lay.addView(phrasesF)
        lay.addView(ui.note("Several phrases are fine, separated by commas, for example: open notepad, ouvre le bloc notes. If you leave it empty the name is used."))

        var kind = existing?.kind ?: "voice"
        val rawData = if (existing?.kind == "raw") existing.data else ""

        val stepsBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        fun addStep(text: String) {
            val row = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(0, ui.dp(6), 0, ui.dp(6)) }
            row.addView(ui.field("Step (same words as voice commands)", text))
            val btns = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            val lp = { LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            btns.addView(ui.button("Move up") {
                val i = stepsBox.indexOfChild(row)
                if (i > 0) { stepsBox.removeViewAt(i); stepsBox.addView(row, i - 1) }
            }, lp())
            btns.addView(ui.button("Move down") {
                val i = stepsBox.indexOfChild(row)
                if (i >= 0 && i < stepsBox.childCount - 1) { stepsBox.removeViewAt(i); stepsBox.addView(row, i + 1) }
            }, lp())
            btns.addView(ui.button("Remove") { stepsBox.removeView(row) }, lp())
            row.addView(btns)
            stepsBox.addView(row)
        }
        fun steps(): List<String> = (0 until stepsBox.childCount).mapNotNull { i ->
            val row = stepsBox.getChildAt(i) as ViewGroup
            (row.getChildAt(0) as EditText).text.toString().trim().ifEmpty { null }
        }

        val stepsSection = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        stepsSection.addView(ui.label("Steps (they run in this order)"))
        stepsSection.addView(stepsBox)
        stepsSection.addView(ui.button("Add a step from the list") {
            val picks = QuickPicks.list(act, existing?.phrases()?.toSet() ?: emptySet())
            AlertDialog.Builder(act).setTitle("Add a step")
                .setItems(picks.map { it.first }.toTypedArray()) { _, i -> addStep(picks[i].second) }
                .setNegativeButton("Cancel", null).show()
        })
        stepsSection.addView(ui.row(
            ui.button("Add an empty step") { addStep("") },
            ui.button("Add a pause") { addStep("wait 1") }))
        stepsSection.addView(ui.note("A step is written like a voice command, for example: control s, or: type hello. \"wait 2\" pauses for 2 seconds, \"wait 500 ms\" for half a second."))

        val rawNote = ui.note("")
        lateinit var convertBtn: Button
        convertBtn = ui.button("Convert to steps (timing between keys is lost)") {
            val st = Voice.rawToSteps(rawData)
            if (st.isEmpty()) ui.toast("This recording could not be turned into steps")
            else {
                stepsBox.removeAllViews()
                st.forEach { addStep(it) }
                kind = "voice"
                stepsSection.visibility = View.VISIBLE
                rawNote.text = "Converted to steps. Check them and save."
                convertBtn.visibility = View.GONE
            }
        }
        if (kind == "raw") {
            rawNote.text = "This macro replays recorded key presses exactly (${rawData.split(";").count { it.isNotBlank() }} key events). " +
                "You can keep it as it is, or convert it to steps you can edit."
            stepsSection.visibility = View.GONE
            lay.addView(rawNote); lay.addView(convertBtn)
        } else {
            existing?.data?.split(";")?.map { it.trim() }?.filter { it.isNotEmpty() }?.forEach { addStep(it) }
            if (existing == null) addStep("")
        }
        lay.addView(stepsSection)

        val gapF = ui.field("Pause between steps in milliseconds", (existing?.gap ?: 400).toString(), true)
        lay.addView(ui.label("Pause between steps (milliseconds)")); lay.addView(gapF)

        lay.addView(ui.button("Test this macro now") {
            if (!Remote.connected) { ui.toast("Not connected"); return@button }
            val gap = gapF.text.toString().toIntOrNull()?.coerceIn(0, 10000) ?: 400
            if (kind == "voice") {
                val st = steps()
                if (st.isEmpty()) ui.toast("Nothing to test") else Voice.runLine(st.joinToString("; "), false, gap)
            } else Voice.runMacro(Store.Macro("test", "", "raw", rawData))
        })

        val dlg = AlertDialog.Builder(act)
            .setTitle(if (existing == null) "New macro" else "Edit macro")
            .setView(ScrollView(act).apply { addView(lay) })
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameF.text.toString().trim()
                if (name.isEmpty()) { ui.toast("Give the macro a name"); return@setOnClickListener }
                var phrases = phrasesF.text.toString().split(",").map { it.trim() }.filter { it.isNotEmpty() }
                if (phrases.isEmpty()) phrases = listOf(name)
                val lowPhrases = phrases.map { it.lowercase() }
                val st = steps()
                if (kind == "voice") {
                    if (st.isEmpty()) { ui.toast("Add at least one step"); return@setOnClickListener }
                    if (st.any { s -> s.lowercase().let { it in lowPhrases || it == name.lowercase() } }) {
                        ui.toast("A macro cannot run itself"); return@setOnClickListener
                    }
                }
                val gap = gapF.text.toString().toIntOrNull()?.coerceIn(0, 10000) ?: 400
                val m = Store.Macro(name, phrases.joinToString(", "), kind, if (kind == "voice") st.joinToString("; ") else rawData, gap)

                val clashes = ArrayList<String>()
                val others = Store.macros(act).filter { it.name != existing?.name }
                val builtin = Voice.builtinPhrases()
                for (ph in (lowPhrases + name.lowercase()).distinct()) {
                    others.firstOrNull { it.matches(ph) }?.let { clashes.add("\"$ph\" is already used by the macro \"${it.name}\".") }
                    if (ph in builtin) clashes.add("\"$ph\" is also a built-in command. Your macro will run instead of it.")
                }
                if (clashes.isEmpty()) { commit(existing, m); dlg.dismiss() }
                else AlertDialog.Builder(act).setTitle("Phrases that clash")
                    .setMessage(clashes.joinToString("\n\n"))
                    .setPositiveButton("Save anyway") { _, _ -> commit(existing, m); dlg.dismiss() }
                    .setNegativeButton("Go back", null).show()
            }
        }
        dlg.show()
    }

    private fun commit(old: Store.Macro?, m: Store.Macro) {
        val list = Store.macros(act)
        val pos = if (old != null) list.indexOfFirst { it.name == old.name } else -1
        val rest = list.filter { it.name != m.name && (old == null || it.name != old.name) }.toMutableList()
        rest.add(if (pos >= 0) minOf(pos, rest.size) else rest.size, m)
        Store.saveMacros(act, rest)
        if (old != null) repointGestures(old, m)
        Voice.reload()
        ui.toast("Saved \"${m.name}\"")
        onChanged()
    }

    /** Gestures that run the macro by one of its old phrases are switched to the new first phrase. */
    private fun repointGestures(old: Store.Macro, m: Store.Macro) {
        val newFirst = m.phrases().firstOrNull() ?: return
        val oldPhrases = old.phrases()
        val newPhrases = m.phrases()
        val cfg = GestureConfig.shared
        if (cfg.map.isEmpty()) cfg.load(act)
        var changed = false
        for ((k, v) in cfg.map.toMap()) {
            if (v.isEmpty()) continue
            val parts = v.split(";").map { it.trim() }
            val fixed = parts.map { p -> if (p.lowercase() in oldPhrases && p.lowercase() !in newPhrases) newFirst else p }
            if (fixed != parts) { cfg.map[k] = fixed.joinToString("; "); changed = true }
        }
        if (changed) Store.saveGestureMap(act, cfg.map)
    }
}
