package com.example.nvdapocket.tabs

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
import com.example.nvdapocket.Remote
import com.example.nvdapocket.Voice
import com.example.nvdapocket.ui.Ui

class VoiceTab(act: MainActivity, ui: Ui) : Tab(act, ui) {
    override val title = "Voice"

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var voiceBtn: Button
    private lateinit var voiceStatus: TextView
    private lateinit var dictBtn: Button
    private lateinit var wakeBox: CheckBox
    private lateinit var wakeField: EditText
    private lateinit var vibrateBox: CheckBox
    private lateinit var speakResultBox: CheckBox
    private var recognizer: SpeechRecognizer? = null
    private var voiceOn = false
    private var dictationOn = false

    private fun recLang() = when (Voice.lang) { "fr" -> "fr-FR"; "ar" -> "ar-TN"; else -> "en-US" }

    override fun build(p: LinearLayout) {
        voiceBtn = ui.button("Start voice commands") {
            if (voiceOn) stopVoice() else { voiceOn = true; voiceBtn.text = "Stop voice commands"; startVoice() }
        }
        voiceStatus = TextView(act).apply { text = "Voice: off" }
        p.addView(voiceBtn); p.addView(voiceStatus)

        p.addView(ui.label("Voice language"))
        val langs = listOf("English" to "en", "Français" to "fr", "العربية" to "ar")
        val sp = Spinner(act)
        sp.adapter = ArrayAdapter<String>(act, android.R.layout.simple_spinner_dropdown_item, langs.map { it.first })
        sp.setSelection(maxOf(0, langs.indexOfFirst { it.second == Voice.lang }))
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                Voice.lang = langs[pos].second
                act.prefs.edit().putString("vlang", Voice.lang).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        p.addView(sp)

        wakeBox = ui.check("Only react after a wake word", act.prefs.getBoolean("wakeOn", false))
        wakeBox.setOnCheckedChangeListener { _, c -> act.prefs.edit().putBoolean("wakeOn", c).apply() }
        wakeField = ui.field("Wake word", act.prefs.getString("wake", "computer") ?: "computer")
        wakeField.setOnFocusChangeListener { _, _ -> act.prefs.edit().putString("wake", wakeField.text.toString()).apply() }
        p.addView(wakeBox); p.addView(wakeField)

        dictBtn = ui.button("Start dictation (everything you say is typed)") { setDictation(!dictationOn) }
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
        speakResultBox = ui.check("Say \"done\" or \"not understood\" on the phone", act.prefs.getBoolean("sayres", false))
        speakResultBox.setOnCheckedChangeListener { _, c -> act.prefs.edit().putBoolean("sayres", c).apply() }
        p.addView(vibrateBox); p.addView(speakResultBox)
    }

    override fun onPause() { stopVoice() }
    override fun onDestroy() { recognizer?.destroy(); recognizer = null }

    fun onPermissionResult(requestCode: Int, grantResults: IntArray) {
        if (requestCode == 2) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) startVoice()
            else { voiceStatus.text = "Voice: microphone permission denied"; stopVoice() }
        }
    }

    private fun feedback(ok: Boolean) {
        if (vibrateBox.isChecked) Remote.vibrate(if (ok) 40 else 150)
        if (speakResultBox.isChecked) Remote.say(if (ok) "Done" else "Not understood")
    }

    private fun setDictation(on: Boolean) {
        dictationOn = on
        dictBtn.text = if (on) "Stop dictation" else "Start dictation (everything you say is typed)"
        voiceStatus.text = if (on) "Voice: dictation on (say stop dictation to end)" else "Voice: listening..."
        if (on && !voiceOn) { voiceOn = true; voiceBtn.text = "Stop voice commands"; startVoice() }
    }

    private fun stopVoice() {
        voiceOn = false
        dictationOn = false
        try { recognizer?.cancel() } catch (_: Exception) {}
        if (::voiceBtn.isInitialized) voiceBtn.text = "Start voice commands"
        if (::dictBtn.isInitialized) dictBtn.text = "Start dictation (everything you say is typed)"
        if (::voiceStatus.isInitialized) voiceStatus.text = "Voice: off"
    }

    private fun startVoice() {
        if (!voiceOn) return
        if (act.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            act.requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 2); return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(act)) {
            voiceStatus.text = "Voice: speech recognition not available on this phone"; stopVoice(); return
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(act)
            recognizer!!.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) { if (!dictationOn) voiceStatus.text = "Voice: listening..." }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(b: Bundle?) {}
                override fun onEvent(t: Int, b: Bundle?) {}
                override fun onError(error: Int) {
                    if (!voiceOn) return
                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) { stopVoice(); return }
                    handler.postDelayed({ startVoice() }, 600)
                }
                override fun onResults(results: Bundle?) {
                    val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (list != null && list.isNotEmpty()) handleVoice(list)
                    if (voiceOn) handler.postDelayed({ startVoice() }, 300)
                }
            })
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, recLang())
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        recognizer!!.startListening(i)
    }

    private fun handleVoice(list: List<String>) {
        val low = list.map { it.lowercase() }
        if (low.any { it.contains("stop listening") }) { stopVoice(); return }
        if (dictationOn) {
            if (low.any { it.contains("stop dictation") }) { setDictation(false); feedback(true); return }
            Remote.sendText(list[0] + " ")
            voiceStatus.text = "Dictated: ${list[0]}"
            return
        }
        var cands = list
        if (wakeBox.isChecked) {
            val w = wakeField.text.toString().trim().lowercase()
            if (w.isNotEmpty()) {
                cands = list.filter { it.lowercase().startsWith(w) }.map { it.substring(w.length).trimStart(',', ' ', '.') }
                if (cands.isEmpty()) return
            }
        }
        if (cands.any { it.lowercase().contains("start dictation") }) { setDictation(true); feedback(true); return }
        for (h in cands) {
            if (Voice.run(h, true)) { voiceStatus.text = "Heard: $h"; feedback(true); return }
        }
        voiceStatus.text = "Heard: ${cands[0]} (not a command)"
        feedback(false)
    }
}
