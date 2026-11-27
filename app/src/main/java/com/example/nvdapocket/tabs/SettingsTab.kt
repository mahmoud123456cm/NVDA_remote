package com.example.nvdapocket.tabs

import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Spinner
import com.example.nvdapocket.App
import com.example.nvdapocket.Backup
import com.example.nvdapocket.CrashLog
import com.example.nvdapocket.Cue
import com.example.nvdapocket.VolKeys
import com.example.nvdapocket.MainActivity
import com.example.nvdapocket.Remote
import com.example.nvdapocket.Updater
import com.example.nvdapocket.ui.Ui

class SettingsTab(act: MainActivity, ui: Ui) : Tab(act, ui) {
    override val title = "Settings"

    private lateinit var speakBox: CheckBox
    private lateinit var engineBtn: Button
    private lateinit var voiceBtn: Button
    private var openedSystemTts = false
    private lateinit var volumeBox: CheckBox

    fun volumeKeysOn() = VolKeys.on(act)
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
        p.addView(ui.label("Speech engine and voice"))
        engineBtn = ui.button("") { chooseEngine() }
        voiceBtn = ui.button("") { chooseVoice() }
        p.addView(engineBtn); p.addView(voiceBtn)
        p.addView(ui.button("Open Android speech settings") {
            openedSystemTts = true
            try { act.startActivity(Intent("com.android.settings.TTS_SETTINGS")) } catch (_: Exception) { openedSystemTts = false; ui.toast("Could not open settings") }
        })
        p.addView(ui.note("The choice applies at once and is remembered. If you change the default engine in Android's own settings, come back here and it is picked up."))
        refreshSpeechButtons()
        val shake = ui.check("Shake the phone to mute or unmute speech (applies next time you connect)", prefs.getBoolean("shake", true))
        shake.setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("shake", c).apply() }
        p.addView(shake)

        p.addView(ui.label("When I share text to ${App.name(act)} from another app"))
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
        volumeBox = ui.check("Volume keys act as Up and Down arrows", prefs.getBoolean("volkeys", false))
        volumeBox.setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("volkeys", c).apply() }
        p.addView(volumeBox)

        p.addView(ui.label("Voice cues"))
        val cuesBox = ui.check("Play voice cues (connected, PC joined or left, clipboard, command understood or not)", Cue.enabled)
        cuesBox.setOnCheckedChangeListener { _, c -> Cue.setEnabled(act, c) }
        p.addView(cuesBox)
        p.addView(ui.label("Cue style"))
        val styles = listOf("Sound and voice" to "both", "Voice only" to "voice", "Sound only (quickest)" to "sound")
        val styleSpinner = Spinner(act)
        styleSpinner.adapter = ArrayAdapter<String>(act, android.R.layout.simple_spinner_dropdown_item, styles.map { it.first })
        styleSpinner.setSelection(maxOf(0, styles.indexOfFirst { it.second == Cue.style }))
        styleSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) { Cue.setStyle(act, styles[pos].second) }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        p.addView(styleSpinner)
        p.addView(ui.button("Play a sample of the voice cues") {
            if (!Cue.enabled) ui.toast("Voice cues are switched off")
            else playSample(listOf("connected", "pc_joined", "clip_to_phone", "cmd_ok", "cmd_fail", "pc_left", "connection_lost", "disconnected"), 0)
        })

        p.addView(ui.label("Background"))
        p.addView(ui.button("Battery settings (set this app to Unrestricted)") {
            act.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        })

        p.addView(ui.label("Backup of everything"))
        p.addView(ui.note("Saves your connection profiles, macros, gestures and settings as text, so you can restore them after reinstalling or move them to another phone."))
        p.addView(ui.row(
            ui.button("Export backup") { exportBackup() },
            ui.button("Import from clipboard") { importBackup() }))

        p.addView(ui.label("Updates"))
        p.addView(ui.note("Installed build: ${Updater.currentBuild(act)}"))
        p.addView(ui.button("Check for updates") { Updater.check(act) })
        p.addView(ui.note("Quick tile: swipe down the Android quick settings, edit tiles, and add \"${App.name(act)}\" for one-tap connect."))

        p.addView(ui.label("Problems"))
        p.addView(ui.note("If the app crashes or hits an error, it is written to a log on this phone. You can read it or send it to the developer."))
        p.addView(ui.row(
            ui.button("View log") { viewLog() },
            ui.button("Send log") { shareLog() },
            ui.button("Clear log") { CrashLog.clear(act); ui.toast("Log cleared") }))
    }

    private fun exportBackup() {
        AlertDialog.Builder(act).setTitle("Export backup")
            .setMessage("Include your connection keys? They would be in the exported text in plain letters, so anyone who sees the text can join your channel. Without keys you type them in again after restoring.")
            .setPositiveButton("Without keys") { _, _ -> shareBackup(false) }
            .setNeutralButton("With keys") { _, _ -> shareBackup(true) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun shareBackup(includeKeys: Boolean) {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "${App.name(act)} backup")
            .putExtra(Intent.EXTRA_TEXT, Backup.export(act, includeKeys))
        act.startActivity(Intent.createChooser(i, "Save the backup (copy it, or send it to yourself)"))
    }

    private fun importBackup() {
        val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(act)?.toString() else null
        val b = if (text.isNullOrBlank()) null else Backup.parse(text)
        if (b == null) { ui.toast("The clipboard does not hold a backup made by this app. Copy the exported text first."); return }
        AlertDialog.Builder(act).setTitle("Import this backup?")
            .setMessage(Backup.summary(b) + "\n\nOnly import a backup you made yourself: it can contain connection settings.")
            .setPositiveButton("Import") { _, _ ->
                Backup.apply(act, b)
                ui.toast("Backup imported")
                act.recreate()          // rebuild the screens so every tab shows the restored values
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun refreshSpeechButtons() {
        if (!::engineBtn.isInitialized) return
        engineBtn.text = "Speech engine: " + Remote.currentEngineLabel()
        val v = Remote.ttsVoices().firstOrNull { it.name == Remote.ttsVoiceName() }
        voiceBtn.text = "Voice: " + (if (v != null) v.locale.displayName + " - " + v.name else Remote.ttsVoiceName() ?: "engine default")
    }

    private fun chooseEngine() {
        val engines = Remote.ttsEngines()
        if (engines.isEmpty()) { ui.toast("No speech engines found"); return }
        val labels = listOf("System default") + engines.map { it.second }
        val cur = Remote.ttsEngineName()
        val checked = if (cur == null) 0 else 1 + maxOf(0, engines.indexOfFirst { it.first == cur })
        AlertDialog.Builder(act).setTitle("Speech engine")
            .setSingleChoiceItems(labels.toTypedArray(), checked) { d, i ->
                Remote.setEngine(if (i == 0) null else engines[i - 1].first)
                d.dismiss()
                refreshSpeechButtons()
                ui.toast("Speech engine: " + labels[i])
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun chooseVoice() {
        val voices = Remote.ttsVoices()
        if (voices.isEmpty()) {
            ui.toast(if (Remote.ttsIsReady()) "This engine has no selectable voices" else "The speech engine is still starting, try again in a moment")
            return
        }
        val labels = listOf("Engine default voice") + voices.map {
            it.locale.displayName + " - " + it.name + (if (it.isNetworkConnectionRequired) " (needs internet)" else "")
        }
        val cur = Remote.ttsVoiceName()
        val checked = if (cur == null) 0 else 1 + maxOf(0, voices.indexOfFirst { it.name == cur })
        AlertDialog.Builder(act).setTitle("Voice")
            .setSingleChoiceItems(labels.toTypedArray(), checked) { d, i ->
                Remote.setVoice(if (i == 0) null else voices[i - 1].name)
                d.dismiss()
                refreshSpeechButtons()
                if (i > 0) Remote.say("This is the selected voice.", true)
            }
            .setNegativeButton("Cancel", null).show()
    }

    override fun onResume() {
        Remote.ttsListener = { refreshSpeechButtons() }
        if (openedSystemTts) { openedSystemTts = false; Remote.reinitTts() }   // the default engine or voice may have changed in Android's settings
        refreshSpeechButtons()
    }

    override fun onPause() { Remote.ttsListener = null }

    private fun playSample(names: List<String>, i: Int) {
        if (i >= names.size) return
        Cue.play(names[i]) { playSample(names, i + 1) }
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
            .putExtra(Intent.EXTRA_SUBJECT, "${App.name(act)} crash log")
            .putExtra(Intent.EXTRA_TEXT, t.takeLast(50_000))
        act.startActivity(Intent.createChooser(i, "Send log"))
    }
}
