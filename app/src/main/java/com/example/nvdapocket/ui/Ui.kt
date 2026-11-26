package com.example.nvdapocket.ui

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.example.nvdapocket.Voice

/** Small helpers for building the screens in code, shared by every tab. */
class Ui(val act: Activity) {
    fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()
    fun toast(m: String) = Toast.makeText(act, m, Toast.LENGTH_SHORT).show()

    fun label(t: String) = TextView(act).apply { text = t; textSize = 16f; setPadding(0, dp(12), 0, dp(4)) }
    fun note(t: String) = TextView(act).apply { text = t }

    fun field(hint: String, value: String, numeric: Boolean = false) = EditText(act).apply {
        this.hint = hint; setText(value); setSingleLine(); contentDescription = hint
        if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
    }

    fun check(t: String, on: Boolean) = CheckBox(act).apply { text = t; isChecked = on }

    fun button(t: String, onClick: () -> Unit) = Button(act).apply {
        text = t; isAllCaps = false; setOnClickListener { onClick() }
    }

    fun row(vararg v: View) = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        v.forEach { addView(it, LinearLayout.LayoutParams(0, dp(56), 1f)) }
    }

    fun grid(p: LinearLayout, items: List<Pair<String, String>>, per: Int) {
        var i = 0
        while (i < items.size) {
            val r = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            for (j in i until minOf(i + per, items.size)) {
                val (l, a) = items[j]
                r.addView(button(l) { Voice.run(a, false) }.apply { textSize = 13f }, LinearLayout.LayoutParams(0, dp(56), 1f))
            }
            p.addView(r); i += per
        }
    }

    fun seekRow(p: LinearLayout, max: Int, progress: Int, fmt: (Int) -> String, onChange: (Int) -> Unit) {
        val tv = TextView(act).apply { text = fmt(progress) }
        val sb = SeekBar(act).apply { this.max = max; this.progress = progress }
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) { tv.text = fmt(v); onChange(v) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        p.addView(tv); p.addView(sb)
    }

    fun askText(title: String, hint: String, onOk: (String) -> Unit) {
        val et = EditText(act); et.hint = hint
        AlertDialog.Builder(act).setTitle(title).setView(et)
            .setPositiveButton("Save") { _, _ -> val t = et.text.toString().trim(); if (t.isNotEmpty()) onOk(t) }
            .setNegativeButton("Cancel", null).show()
    }
}
