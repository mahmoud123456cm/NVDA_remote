package com.example.nvdapocket.tabs

import android.app.AlertDialog
import android.content.Intent
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Spinner
import com.example.nvdapocket.CrashLog
import com.example.nvdapocket.MainActivity
import com.example.nvdapocket.Remote
import com.example.nvdapocket.Updater
import com.example.nvdapocket.ui.Ui

class SettingsTab(act: MainActivity, ui: Ui) : Tab(act, ui) {
    override val title = "Settings"

    private lateinit var speakBox: CheckBox
    private lateinit var volumeBox: CheckBox

    fun volumeKeysOn() = ::volumeBox.isInitialized && volumeBox.isChecked
    fun syncSpeak() { if (::speakBox.isInitialized && speakBox.isChecked != Remote.speak) speakBox.isChecked = Remote.speak }

    override fun build(p: LinearLayout) {
        val prefs = act.prefs
        p.addView(ui.label("Speech from the PC"))
        speakBox = ui.check("Speak remote speech on this phone", Remote.speak)
        speakBox.setOnCheckedChangeListener { _, c -> if (c != Remote.speak) Remote.applySpeak(c); act.updateUi() }
        p.addView(speakBox)
        ui.seekRow(p, 30, ((Remote.ttsRate - 0.5f) / 0.05f).toInt(), { v -> "Speech speed: %.2f".format(0.5f + v * 0.05f) },
            { v -> Remote.ttsRate = 0.5f + v * 0.05f; Remote.applyTts(); prefs.edit().putFloat("rate", Remote.ttsRate).apply() })
        ui.seekRow(p, 30, ((Remote.ttsPitch - 0.5f) / 0.05f).toInt(), { v -> "Speech pitch: %.2f".format(0.5f + v * 0.05f) },
            { v -> Remote.ttsPitch = 0.5f + v * 0.05f; Remote.applyTts(); prefs.edit().putFloat("pitch", Remote.ttsPitch).apply() })
        p.addView(ui.button("Choose the speech voice and engine (Android settings)") {
            try { act.startActivity(Intent("com.android.settings.TTS_SETTINGS")) } catch (_: Exception) { ui.toast("Could not open settings") }
        })
        val shake = ui.check("Shake the phone to mute or unmute speech (applies next time you connect)", prefs.getBoolean("shake", true))
        shake.setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("shake", c).apply() }
        p.addView(shake)

        p.addView(ui.label("When I share text to NVDA Pocket from another app"))
        val modes = listOf("Ask each time" to "ask", "Put it in the PC clipboard" to "clip",
            "Put in clipboard and paste (Ctrl+V)" to "paste", "Type it as keys" to "type")
        val shareSpinner = Spinner(act)
        shareSpinner.adapter = ArrayAdapter<String>(act, android.R.layout.simple_spinner_dropdown_item, modes.map { it.first })
        shareSpinner.setSelection(maxOf(0, modes.indexOfFirst { it.second == (prefs.getString("shareMode", "ask") ?: "ask") }))
        shareSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                prefs.edit().putString("shareMode", modes[pos].second).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        p.addView(shareSpinner)
        p.addView(ui.note("In any app choose Share, then \"Send to PC\". The clipboard works for every language including Arabic. Typing only supports US keyboard characters."))

        p.addView(ui.label("Controls"))
        volumeBox = ui.check("Volume keys act as Up and Down arrows", false)
        p.addView(volumeBox)

        p.addView(ui.label("Background"))
        p.addView(ui.button("Battery settings (set this app to Unrestricted)") {
            act.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        })

        p.addView(ui.label("Updates"))
        p.addView(ui.note("Installed build: ${Updater.currentBuild(act)}"))
        p.addView(ui.button("Check for updates") { Updater.check(act) })
        p.addView(ui.note("Quick tile: swipe down the Android quick settings, edit tiles, and add \"NVDA Pocket\" for one-tap connect."))

        p.addView(ui.label("Problems"))
        p.addView(ui.note("If the app crashes or hits an error, it is written to a log on this phone. You can read it or send it to the developer."))
        p.addView(ui.row(
            ui.button("View log") { viewLog() },
            ui.button("Send log") { shareLog() },
            ui.button("Clear log") { CrashLog.clear(act); ui.toast("Log cleared") }))
    }

    private fun viewLog() {
        val t = CrashLog.read(act)
        if (t.isEmpty()) { ui.toast("No problems recorded"); return }
        AlertDialog.Builder(act).setTitle("Crash and error log")
            .setMessage(t.takeLast(4000))
            .setPositiveButton("Close", null).show()
    }

    private fun shareLog() {
        val t = CrashLog.read(act)
        if (t.isEmpty()) { ui.toast("No problems recorded"); return }
        val i = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "NVDA Pocket crash log")
            .putExtra(Intent.EXTRA_TEXT, t.takeLast(50_000))
        act.startActivity(Intent.createChooser(i, "Send log"))
    }
}
