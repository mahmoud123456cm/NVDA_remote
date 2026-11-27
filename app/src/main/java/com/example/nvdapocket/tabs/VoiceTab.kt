package com.example.nvdapocket.tabs

import android.app.AlertDialog
import android.content.pm.PackageManager
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import com.example.nvdapocket.MainActivity
import com.example.nvdapocket.Voice
import com.example.nvdapocket.VoiceEngine
import com.example.nvdapocket.VoiceModels
import com.example.nvdapocket.ui.Ui

/** Settings and buttons for voice commands. The listening itself lives in VoiceEngine, so it keeps going when you leave this tab or the app. */
class VoiceTab(act: MainActivity, ui: Ui) : Tab(act, ui) {
    override val title = "Voice"

    private lateinit var voiceBtn: Button
    private lateinit var voiceStatus: TextView
    private lateinit var dictBtn: Button
    private lateinit var wakeBox: CheckBox
    private lateinit var wakeField: EditText
    private lateinit var vibrateBox: CheckBox
    private lateinit var cueBox: CheckBox
    private var pendingDictation = false
    private lateinit var modelNote: TextView
    private lateinit var modelBtn: Button
    private lateinit var modelDel: Button
    private val wakeDefaults = mapOf("en" to "computer", "fr" to "ordinateur", "ar" to "كمبيوتر")

    override fun build(p: LinearLayout) {
        voiceBtn = ui.button("Start voice commands") { if (VoiceEngine.on) VoiceEngine.stop() else begin(false) }
        voiceStatus = TextView(act).apply { text = VoiceEngine.statusText }
        p.addView(voiceBtn); p.addView(voiceStatus)
        p.addView(ui.note("Once started it keeps listening, also when you leave the app or lock the screen, until you press Stop or say \"stop listening\". After each command you hear a voice cue (done or not understood) and it listens again."))

        p.addView(ui.label("Voice language"))
        val langs = listOf("English" to "en", "Français" to "fr", "العربية" to "ar")
        val sp = Spinner(act)
        sp.adapter = ArrayAdapter<String>(act, android.R.layout.simple_spinner_dropdown_item, langs.map { it.first })
        sp.setSelection(maxOf(0, langs.indexOfFirst { it.second == Voice.lang }))
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val nl = langs[pos].second
                val old = Voice.lang
                if (nl == old) return
                Voice.lang = nl
                act.prefs.edit().putString("vlang", nl).apply()
                // a wake word left at the old language's default follows the language (the model must know the word)
                if (wakeField.text.toString().trim().lowercase() == wakeDefaults[old]) wakeField.setText(wakeDefaults[nl])
                refreshModel()
                restartVoice()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        p.addView(sp)

        p.addView(ui.label("Listening engine"))
        val engines = listOf("Silent and offline: no beeps, no restarts, waits for the wake word (recommended)" to "silent",
            "Android recognizer: restarts every few seconds and may beep" to "android")
        val esp = Spinner(act)
        esp.adapter = ArrayAdapter<String>(act, android.R.layout.simple_spinner_dropdown_item, engines.map { it.first })
        esp.setSelection(maxOf(0, engines.indexOfFirst { it.second == (act.prefs.getString("vengine", "silent") ?: "silent") }))
        esp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val ne = engines[pos].second
                if (ne == (act.prefs.getString("vengine", "silent") ?: "silent")) return
                act.prefs.edit().putString("vengine", ne).apply()
                restartVoice()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        p.addView(esp)
        modelNote = TextView(act)
        modelBtn = ui.button("") { confirmDownload() }
        modelDel = ui.button("Delete the voice model") {
            VoiceModels.delete(act, Voice.lang); refreshModel(); restartVoice()
        }
        p.addView(modelNote)
        p.addView(ui.row(modelBtn, modelDel))
        val selfBox = ui.check("Ignore the microphone while the phone is speaking (stops it hearing itself; switch off with headphones to interrupt by voice)", act.prefs.getBoolean("ignoreSelf", true))
        selfBox.setOnCheckedChangeListener { _, c -> act.prefs.edit().putBoolean("ignoreSelf", c).apply() }
        p.addView(selfBox)

        wakeBox = ui.check("Only react after a wake word", act.prefs.getBoolean("wakeOn", true))
        wakeBox.setOnCheckedChangeListener { _, c -> act.prefs.edit().putBoolean("wakeOn", c).apply() }
        wakeField = ui.field("Wake word", act.prefs.getString("wake", "computer") ?: "computer")
        wakeField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { act.prefs.edit().putString("wake", s?.toString() ?: "").apply() }
        })
        p.addView(wakeBox); p.addView(wakeField)
        p.addView(ui.note("The wake word must be a common word the voice model knows, in the voice language (for example computer, ordinateur, كمبيوتر). If it never reacts, try another word. With the wake word on, only speech that starts with it is used and everything else is ignored silently. Say just the wake word and you hear \"Listening\"; your next sentence is then the command. With it off, everything you say is treated as a command."))

        dictBtn = ui.button("Start dictation (everything you say is typed)") {
            if (VoiceEngine.dictation) VoiceEngine.setDictation(false) else begin(true)
        }
        p.addView(dictBtn)

        p.addView(ui.label("What you can say"))
        p.addView(ui.note(
            "Keys: enter, tab, escape, space, backspace, delete, up, down, left, right, home, end, page up, page down, f 1 to f 12, numpad 5, comma, period, slash...\n" +
            "Repeat: \"down 5\".\n" +
            "Combinations: \"control shift escape\", \"alt f4\", \"NVDA n\", \"NVDA plus s\", \"insert space\".\n" +
            "Letters after a modifier: say the letter or use alpha, bravo, charlie...\n" +
            "Shortcuts: select all, copy, paste, undo, save, find, new tab, close window, switch window, start menu, show desktop, task manager.\n" +
            "NVDA: say all, read line, read title, where am I, nvda menu, speech mode, stop speech, pause speech, elements list.\n" +
            "Browse mode: next heading, previous heading, next link, next button, next table, next landmark, next form field.\n" +
            "Text: \"type hello world\".\n" +
            "Clipboard: \"push clipboard\" (phone to PC), \"get clipboard\" (PC to phone), \"get selection\" (PC selection to phone), \"last clipboard\".\n" +
            "Other: \"toggle mute\", \"control alt delete\".\n" +
            "Control: \"start dictation\", \"stop dictation\", \"stop listening\".\n" +
            "Your own phrases from the Macros tab work too."))

        p.addView(ui.label("Feedback"))
        vibrateBox = ui.check("Vibrate when a command is understood or not", act.prefs.getBoolean("vib", true))
        vibrateBox.setOnCheckedChangeListener { _, c -> act.prefs.edit().putBoolean("vib", c).apply() }
        cueBox = ui.check("Play a voice cue when a command is understood or not", act.prefs.getBoolean("cueCmd", true))
        cueBox.setOnCheckedChangeListener { _, c -> act.prefs.edit().putBoolean("cueCmd", c).apply() }
        p.addView(vibrateBox); p.addView(cueBox)
        refresh()
    }

    private fun begin(dictation: Boolean) {
        if (!VoiceEngine.hasMic(act)) {
            pendingDictation = dictation
            act.requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 2)
            return
        }
        if (dictation) VoiceEngine.setDictation(true) else VoiceEngine.start()
    }

    fun onPermissionResult(requestCode: Int, grantResults: IntArray) {
        if (requestCode != 2) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) begin(pendingDictation)
        else voiceStatus.text = "Voice: microphone permission denied"
    }

    private fun restartVoice() {
        if (VoiceEngine.on) { VoiceEngine.stop(false); VoiceEngine.start() }
    }

    private fun refreshModel() {
        if (!::modelNote.isInitialized) return
        val info = VoiceModels.forLang(Voice.lang)
        val ok = VoiceModels.installed(act, Voice.lang)
        modelNote.text = "Voice model for ${info.label}: " + (if (ok) "installed." else "not installed (about ${info.mb} MB). Silent listening needs it; until then the Android recognizer is used.")
        modelBtn.text = if (ok) "Download again" else "Download the voice model"
        modelDel.isEnabled = ok
    }

    private fun confirmDownload() {
        val info = VoiceModels.forLang(Voice.lang)
        AlertDialog.Builder(act).setTitle("Download voice model")
            .setMessage("${info.label}, about ${info.mb} MB, from alphacephei.com. Use Wi-Fi if you can. It is stored on this phone and recognition then works offline.")
            .setPositiveButton("Download") { _, _ -> runDownload(info) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun runDownload(info: VoiceModels.Info) {
        val tv = TextView(act).apply {
            text = "Starting..."; setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        var task: VoiceModels.Task? = null
        val dlg = AlertDialog.Builder(act).setTitle("Downloading the voice model").setView(tv).setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> task?.cancel() }.create()
        dlg.show()
        task = VoiceModels.download(act, info,
            { line -> act.runOnUiThread { tv.text = line } },
            { err -> act.runOnUiThread {
                try { dlg.dismiss() } catch (_: Exception) {}
                refreshModel()
                ui.toast(if (err == null) "Voice model installed" else "Download failed: $err")
                if (err == null) restartVoice()
            } })
    }

    private fun refresh() {
        if (!::voiceBtn.isInitialized) return
        refreshModel()
        voiceBtn.text = if (VoiceEngine.on) "Stop voice commands" else "Start voice commands"
        dictBtn.text = if (VoiceEngine.dictation) "Stop dictation" else "Start dictation (everything you say is typed)"
        voiceStatus.text = VoiceEngine.statusText
    }

    override fun onResume() { VoiceEngine.listener = { refresh() }; refresh() }
    override fun onPause() { VoiceEngine.listener = null }
}
