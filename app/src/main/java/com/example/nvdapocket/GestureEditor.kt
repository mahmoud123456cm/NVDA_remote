package com.example.nvdapocket

import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.example.nvdapocket.ui.Ui

/**
 * Lets the user choose what every gesture does. The list shows each gesture with its current action; tapping one
 * opens a small editor with a quick-pick list, free text (same words as voice commands, steps separated by ";")
 * and a Test button. "Detect a gesture" lets the user perform a gesture on the pad to jump straight to it.
 */
class GestureEditor(private val ui: Ui, private val cfg: GestureConfig, private val pad: GesturePad) {
    private val act = ui.act
    private var listDialog: AlertDialog? = null

    private fun save() = Store.saveGestureMap(act, cfg.map)

    fun open() {
        val lay = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
        }
        var group = ""
        for (g in Store.gestures) {
            if (g.group != group) {
                group = g.group
                lay.addView(TextView(act).apply {
                    text = group; textSize = 18f
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, ui.dp(14), 0, ui.dp(4))
                    if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
                })
            }
            val cur = cfg.map[g.id].orEmpty()
            val b = Button(act).apply {
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                text = g.label + ": " + (if (cur.isEmpty()) "not set" else cur)
                setOnClickListener { listDialog?.dismiss(); editOne(g) }
            }
            lay.addView(b)
        }
        lay.addView(ui.button("Clear every gesture") {
            listDialog?.dismiss()
            AlertDialog.Builder(act).setTitle("Clear every gesture?")
                .setMessage("All gestures will do nothing until you set them again.")
                .setPositiveButton("Clear all") { _, _ ->
                    cfg.map = LinkedHashMap<String, String>().also { m -> for (k in Store.gestureDefaults.keys) m[k] = "" }
                    save(); ui.toast("All gestures cleared"); open()
                }
                .setNegativeButton("Cancel") { _, _ -> open() }.show()
        })
        lay.addView(ui.button("Restore the original gestures") {
            cfg.map = LinkedHashMap(Store.gestureDefaults)
            save(); listDialog?.dismiss(); ui.toast("Original gestures restored"); open()
        })

        val assigned = cfg.map.values.count { it.isNotEmpty() }
        listDialog = AlertDialog.Builder(act)
            .setTitle("Gestures ($assigned of ${Store.gestures.size} set)")
            .setView(ScrollView(act).apply { addView(lay) })
            .setPositiveButton("Close", null)
            .setNeutralButton("Detect a gesture") { _, _ -> startDetect() }
            .show()
    }

    private fun startDetect() {
        ui.toast("Now do the gesture on the pad")
        Remote.say("Now do the gesture on the pad")
        pad.learn = { id ->
            val g = Store.gestures.firstOrNull { it.id == id }
            if (g != null) editOne(g)
        }
    }

    private fun quickPicks(): List<Pair<String, String>> = QuickPicks.list(act)

    private fun editOne(g: Store.GestureInfo) {
        val lay = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8))
        }
        val et = ui.field("What it does", cfg.map[g.id].orEmpty())
        et.contentDescription = "What " + g.label + " does"
        lay.addView(ui.note("Write it with the same words as voice commands, for example: control s. " +
            "Separate several steps with semicolons, for example: windows r; type notepad; enter."))
        lay.addView(et)

        val picks = quickPicks()
        val names = ArrayList<String>()
        names.add("Quick pick (choose an action)")
        picks.forEach { names.add(it.first) }
        val sp = Spinner(act)
        sp.adapter = ArrayAdapter(act, android.R.layout.simple_spinner_dropdown_item, names)
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos > 0) et.setText(picks[pos - 1].second)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        lay.addView(sp)
        lay.addView(ui.button("Test this action now") {
            val t = et.text.toString().trim()
            if (t.isEmpty()) ui.toast("Nothing to test") else if (!Voice.runLine(t, false)) ui.toast("Not understood")
        })

        AlertDialog.Builder(act).setTitle(g.label)
            .setView(ScrollView(act).apply { addView(lay) })
            .setPositiveButton("Save") { _, _ ->
                cfg.map[g.id] = et.text.toString().trim()
                save(); ui.toast("Saved"); open()
            }
            .setNeutralButton("Clear") { _, _ -> cfg.map[g.id] = ""; save(); ui.toast("Cleared"); open() }
            .setNegativeButton("Back") { _, _ -> open() }
            .show()
    }
}
