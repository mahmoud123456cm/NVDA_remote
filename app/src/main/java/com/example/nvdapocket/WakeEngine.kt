package com.example.nvdapocket

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.IOException

/**
 * Silent, always-on listening (like a smart speaker). The microphone is read continuously and recognised on the phone,
 * so nothing restarts and Android never plays its "listening" beep, and the phone's volume is never touched.
 *
 * Two recognisers share the one audio stream:
 *  - a tiny "gate" that only knows the wake word (cheap, runs all the time while waiting);
 *  - a full one that takes over when the gate hears the wake word. It is first fed the last 1.5 s of audio, so
 *    "computer, select all" said in one breath works, and then runs until the sentence is finished.
 * With the wake word switched off, or in dictation, only the full recogniser runs. Every finished sentence goes to
 * VoiceEngine.onVoiceText, the same path the Android recogniser uses.
 * While one of the app's own cues (or, if chosen, the phone's speech) is playing, the audio is thrown away so the
 * phone never hears itself.
 */
object WakeEngine {
    private const val RATE = 16000
    private const val CHUNK = 1600          // 100 ms
    private const val PRE_ROLL = 24000      // 1.5 s
    private const val TRIGGER_MS = 12000L   // after the wake word, give up if no sentence ends within this time

    @Volatile private var running = false
    private var thread: Thread? = null

    private class Ring(val size: Int) {
        val d = ShortArray(size)
        var pos = 0
        var fill = 0
        fun push(src: ShortArray, n: Int) {
            for (i in 0 until n) { d[pos] = src[i]; pos = (pos + 1) % size }
            fill = minOf(size, fill + n)
        }
        fun clear() { pos = 0; fill = 0 }
        fun snapshot(): ShortArray {      // oldest to newest
            val out = ShortArray(fill)
            val start = (pos - fill + size) % size
            for (i in 0 until fill) out[i] = d[(start + i) % size]
            return out
        }
    }

    fun ensureStarted(c: Context, lang: String) {
        if (running && thread?.isAlive == true) return
        running = false
        try { thread?.join(800) } catch (_: InterruptedException) {}
        running = true
        val app = c.applicationContext
        val t = Thread { runLoop(app, lang) }
        t.name = "wake-engine"
        thread = t
        t.start()
    }

    fun stop() { running = false }

    private fun textOf(json: String): String = try { JSONObject(json).optString("text", "") } catch (_: Exception) { "" }

    private fun runLoop(app: Context, lang: String) {
        var model: Model? = null
        var gate: Recognizer? = null
        var free: Recognizer? = null
        var ar: AudioRecord? = null
        var failure: String? = null
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            model = Model(VoiceModels.dir(app, VoiceModels.forLang(lang)).absolutePath)
            val full = Recognizer(model, RATE.toFloat())
            free = full

            val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) throw IOException("this phone cannot record at 16 kHz")
            val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, CHUNK * 2 * 4))
            ar = rec
            if (rec.state != AudioRecord.STATE_INITIALIZED) throw IOException("the microphone could not be opened (is another app using it?)")
            rec.startRecording()

            val buf = ShortArray(CHUNK)
            val ring = Ring(PRE_ROLL)
            var wakeOn = true
            var wake = ""
            var ignoreSelf = true
            var lastPrefs = 0L
            var gateWord = ""
            var triggered = false
            var triggerAt = 0L
            var inGate = false
            var wasDeaf = false
            var shown = ""

            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) throw IOException("microphone read error $n")
                if (n == 0) continue
                val now = SystemClock.uptimeMillis()

                if (now - lastPrefs > 1000) {          // settings can change while listening
                    lastPrefs = now
                    val p = Store.prefs(app)
                    wakeOn = p.getBoolean("wakeOn", true)
                    wake = (p.getString("wake", "computer") ?: "").trim().lowercase()
                    ignoreSelf = p.getBoolean("ignoreSelf", true)
                }

                // never listen to ourselves
                val deaf = Cue.busy || (ignoreSelf && Remote.ttsBusy())
                if (deaf) {
                    if (!wasDeaf) { wasDeaf = true; gate?.reset(); full.reset(); ring.clear(); triggered = false; inGate = false }
                    continue
                }
                wasDeaf = false

                val gateWanted = wakeOn && wake.isNotEmpty() && !VoiceEngine.dictation && now >= VoiceEngine.awaitUntil
                if (gateWanted && (gate == null || gateWord != wake)) {
                    try { gate?.close() } catch (_: Exception) {}
                    gate = Recognizer(model, RATE.toFloat(), JSONArray().put(wake).put("[unk]").toString())
                    gateWord = wake
                }

                val status = when {
                    gateWanted && !triggered -> "Voice: silent, waiting for \"$wake\""
                    VoiceEngine.dictation -> "Voice: dictation on (say stop dictation to end)"
                    else -> "Voice: listening for your command..."
                }
                if (status != shown) { shown = status; VoiceEngine.setStatus(status) }

                val g = gate
                if (gateWanted && !triggered && g != null) {
                    if (!inGate) { inGate = true; full.reset(); g.reset(); ring.clear() }
                    ring.push(buf, n)
                    val fin = g.acceptWaveForm(buf, n)
                    val heard = if (fin) g.result else g.partialResult
                    if (heard.contains(wake)) {
                        triggered = true
                        triggerAt = now
                        inGate = false
                        full.reset()
                        val pre = ring.snapshot()
                        if (full.acceptWaveForm(pre, pre.size)) full.result    // discard: it was only the lead-in
                        g.reset()
                    }
                } else {
                    inGate = false
                    val end = full.acceptWaveForm(buf, n)
                    if (end) {
                        val text = textOf(full.result).trim()
                        full.reset(); g?.reset(); ring.clear(); triggered = false
                        if (text.isNotEmpty()) VoiceEngine.onVoiceText(text)
                    } else if (triggered && now - triggerAt > TRIGGER_MS) {
                        full.reset(); g?.reset(); ring.clear(); triggered = false
                    }
                }
            }
        } catch (e: Throwable) {
            if (running) {
                failure = e.message ?: e.javaClass.simpleName
                CrashLog.log("wake engine", e)
            }
        } finally {
            running = false
            try { ar?.stop() } catch (_: Exception) {}
            try { ar?.release() } catch (_: Exception) {}
            try { gate?.close() } catch (_: Exception) {}
            try { free?.close() } catch (_: Exception) {}
            try { model?.close() } catch (_: Exception) {}
        }
        if (failure != null) VoiceEngine.engineFailed(failure)
    }
}
