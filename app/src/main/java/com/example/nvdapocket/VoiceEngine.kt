package com.example.nvdapocket

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Voice commands that keep listening until you stop them, also while the app is in the background (a microphone
 * foreground service keeps the process alive).
 *
 * Loop: listen -> something is heard -> (wake word on: only if it starts with the wake word, otherwise ignored
 * silently) -> run it -> play the "done" or "not understood" voice cue -> listen again.
 * Saying only the wake word plays "Listening" and the next thing you say is taken as the command.
 */
object VoiceEngine {
    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var rec: SpeechRecognizer? = null
    @Volatile var on = false; private set
    @Volatile var dictation = false; private set
    @Volatile var statusText = "Voice: off"; private set
    /** Called on the main thread whenever the state or status text changes. */
    var listener: (() -> Unit)? = null
    private var errors = 0
    @Volatile var awaitUntil = 0L; private set      // after the wake word alone, the next sentence is the command until this time
    @Volatile private var silentActive = false      // the silent offline engine is the one listening (see WakeEngine)
    private val restart = Runnable { listen() }

    fun init(c: Context) { app = c.applicationContext }

    private fun prefs() = Store.prefs(app!!)

    fun hasMic(c: Context) = c.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun setStatus(s: String) { statusText = s; main.post { listener?.invoke() } }

    private fun recLang() = when (Voice.lang) { "fr" -> "fr-FR"; "ar" -> "ar-TN"; else -> "en-US" }

    /** The caller must already hold the microphone permission. */
    fun start(firstCue: String = "voice_on") {
        main.post {
            val c = app ?: return@post
            if (on) return@post
            if (!hasMic(c)) { setStatus("Voice: microphone permission needed"); return@post }
            on = true; errors = 0; awaitUntil = 0
            silentActive = silentWanted(c)
            try {
                val i = Intent(c, VoiceService::class.java)
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
            } catch (e: Exception) { CrashLog.log("voice service", e) }
            setStatus(if (dictation) "Voice: dictation on (say stop dictation to end)" else "Voice: starting...")
            if (silentPref(c) && !silentActive) setStatus("Voice: no offline voice model yet, using the Android recognizer. Download one in the Voice tab for silent listening.")
            Cue.play(firstCue) { listenSoon(100) }
        }
    }

    fun stop(playCue: Boolean = true) {
        main.post {
            if (!on) return@post
            on = false; dictation = false
            main.removeCallbacks(restart)
            WakeEngine.stop()
            silentActive = false
            destroyRecognizer()
            app?.let { try { it.stopService(Intent(it, VoiceService::class.java)) } catch (_: Exception) {} }
            setStatus("Voice: off")
            if (playCue) Cue.play("voice_off")
        }
    }

    fun setDictation(b: Boolean) {
        main.post {
            if (b == dictation) return@post
            dictation = b
            if (!on) { start(if (b) "dictation_on" else "voice_on"); return@post }
            setStatus(if (b) "Voice: dictation on (say stop dictation to end)" else "Voice: listening...")
            cueThenListen(if (b) "dictation_on" else "dictation_off")
        }
    }

    // ---------- the loop ----------
    private fun listenSoon(delay: Long) {
        main.removeCallbacks(restart)
        main.postDelayed(restart, delay)
    }

    private fun cueThenListen(cue: String) {
        main.removeCallbacks(restart)
        try { rec?.cancel() } catch (_: Exception) {}
        Cue.play(cue) { listenSoon(150) }
    }

    private fun destroyRecognizer() {
        try { rec?.cancel() } catch (_: Exception) {}
        try { rec?.destroy() } catch (_: Exception) {}
        rec = null
    }

    private fun listen() {
        if (!on) return
        val c = app ?: return
        if (!hasMic(c)) { setStatus("Voice: microphone permission needed"); stop(false); return }
        if (silentActive) { WakeEngine.ensureStarted(c, Voice.lang); return }     // silent engine: no recogniser restarts, no beeps
        if (!SpeechRecognizer.isRecognitionAvailable(c)) { setStatus("Voice: speech recognition is not available on this phone"); stop(false); return }
        try {
            val r = rec ?: SpeechRecognizer.createSpeechRecognizer(c).also {
                it.setRecognitionListener(listenerImpl)
                rec = it
            }
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, recLang())
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            r.startListening(i)
        } catch (e: Exception) {
            CrashLog.log("voice listen", e)
            destroyRecognizer()
            listenSoon(1500)
        }
    }

    private val listenerImpl = object : RecognitionListener {
        override fun onReadyForSpeech(p: Bundle?) {
            setStatus(if (dictation) "Voice: dictation on (say stop dictation to end)" else "Voice: listening...")
        }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(v: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(b: Bundle?) {}
        override fun onEvent(t: Int, b: Bundle?) {}
        override fun onError(error: Int) {
            if (!on) return
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { setStatus("Voice: microphone permission needed"); stop(false) }
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> listenSoon(100)   // silence: just listen again
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> { destroyRecognizer(); listenSoon(800) }
                else -> {
                    errors++
                    if (errors > 2) destroyRecognizer()
                    listenSoon(minOf(5000L, 400L * errors))
                }
            }
        }
        override fun onResults(results: Bundle?) {
            if (!on) return
            errors = 0
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (list.isNullOrEmpty()) { listenSoon(100); return }
            handle(list)
        }
    }

    // ---------- the silent engine ----------
    private fun silentPref(c: Context) = (Store.prefs(c).getString("vengine", "silent") ?: "silent") == "silent"
    private fun silentWanted(c: Context) = silentPref(c) && VoiceModels.installed(c, Voice.lang)

    /** A finished sentence from the silent engine (any thread). */
    fun onVoiceText(text: String) {
        main.post {
            if (!on) return@post
            var t = text.trim().lowercase()
            if (t.isEmpty()) return@post
            val p = prefs()
            val wake = (p.getString("wake", "computer") ?: "").trim().lowercase()
            if (!dictation && p.getBoolean("wakeOn", true) && wake.isNotEmpty() && SystemClock.uptimeMillis() >= awaitUntil) {
                val i = t.indexOf(wake)
                if (i < 0) return@post                 // not for us
                t = t.substring(i)                      // drop any word recognised before the wake word
            }
            handle(listOf(t))
        }
    }

    /** The silent engine could not run (no microphone, model would not load...). */
    fun engineFailed(msg: String) {
        main.post {
            if (!on) return@post
            Cue.play("cmd_fail")
            stop(false)
            main.post { setStatus("Voice: $msg") }      // after stop() has written "Voice: off", so the reason stays visible
        }
    }

    // ---------- what was heard ----------
    private fun handle(list: List<String>) {
        val low = list.map { it.lowercase() }
        if (low.any { it.contains("stop listening") }) { stop(); return }
        if (dictation) {
            if (low.any { it.contains("stop dictation") }) { dictation = false; setStatus("Voice: listening..."); cueThenListen("dictation_off"); return }
            Remote.sendText(list[0] + " ")
            setStatus("Dictated: ${list[0]}")
            listenSoon(100)
            return
        }
        val p = prefs()
        var cands = list
        val wakeOn = p.getBoolean("wakeOn", true)
        val wake = (p.getString("wake", "computer") ?: "").trim().lowercase()
        val now = SystemClock.uptimeMillis()
        if (wakeOn && wake.isNotEmpty() && now >= awaitUntil) {
            val heard = list.filter { it.lowercase().startsWith(wake) }
            if (heard.isEmpty()) { setStatus("Voice: listening..."); listenSoon(100); return }   // not for us: ignore silently
            cands = heard.map { it.substring(wake.length).trimStart(',', ' ', '.') }
            if (cands.all { it.isBlank() }) {
                awaitUntil = now + 8000
                setStatus("Voice: listening for your command...")
                cueThenListen("yes")
                return
            }
        }
        awaitUntil = 0
        if (cands.any { it.lowercase().contains("start dictation") }) {
            dictation = true
            setStatus("Voice: dictation on (say stop dictation to end)")
            cueThenListen("dictation_on")
            return
        }
        for (h in cands) {
            if (h.isNotBlank() && Voice.run(h, true)) { setStatus("Heard: $h"); result(true); return }
        }
        setStatus("Heard: ${cands.firstOrNull().orEmpty()} (not a command)")
        result(false)
    }

    private fun result(ok: Boolean) {
        val p = prefs()
        if (p.getBoolean("vib", true)) Remote.vibrate(if (ok) 40 else 150)
        if (p.getBoolean("cueCmd", true)) cueThenListen(if (ok) "cmd_ok" else "cmd_fail") else listenSoon(200)
    }
}
