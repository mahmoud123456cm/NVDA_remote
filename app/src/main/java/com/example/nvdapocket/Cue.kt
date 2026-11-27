package com.example.nvdapocket

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper

/**
 * Plays the short voice clips (cue_*) and sound effects (sfx_*) from res/raw. What plays depends on the style setting:
 * "both" = the sound then the voice, "voice" = only the voice, "sound" = only the sound (the quickest). Clips play one
 * after another, never on top of each other. play() can be called from any thread; onDone runs on the main thread when the clip has
 * finished (or straight away when cues are switched off), which the voice loop uses to wait before it listens again.
 */
object Cue {
    @Volatile var enabled = true
    /** "both", "voice" or "sound" */
    @Volatile var style = "both"

    // which sound goes with which cue
    private val sfxFor = mapOf(
        "connected" to "up", "connection_restored" to "up", "disconnected" to "down", "connection_lost" to "warn",
        "pc_joined" to "join", "pc_left" to "leave", "cmd_ok" to "ok", "cmd_fail" to "fail", "yes" to "yes",
        "voice_on" to "on", "dictation_on" to "on", "gesture_on" to "on", "practice_on" to "on",
        "voice_off" to "off", "dictation_off" to "off", "gesture_off" to "off",
        "clip_to_phone" to "clip", "clip_not_copied" to "clip", "clip_getting" to "clip", "clip_selection" to "clip",
        "clip_sent" to "send", "to_pc_clip" to "send", "pasting" to "send", "typed" to "send",
        "clip_empty" to "alert", "clip_none" to "alert", "clip_nothing" to "alert", "clip_too_big" to "alert",
        "not_connected" to "alert", "cert_changed" to "alert",
        "muted" to "info", "unmuted" to "info", "back_again" to "info")
    private var app: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private class Item(val name: String, val onDone: (() -> Unit)?)
    private val queue = ArrayDeque<Item>()
    private var player: MediaPlayer? = null
    private var playing = false

    fun init(c: Context) {
        app = c.applicationContext
        enabled = Store.prefs(c).getBoolean("cues", true)
        style = Store.prefs(c).getString("cueStyle", "both") ?: "both"
    }

    fun setStyle(c: Context, s: String) {
        style = s
        Store.prefs(c).edit().putString("cueStyle", s).apply()
    }

    fun setEnabled(c: Context, on: Boolean) {
        enabled = on
        Store.prefs(c).edit().putBoolean("cues", on).apply()
        if (!on) main.post { stopNow() }
    }

    fun play(name: String, onDone: (() -> Unit)? = null) {
        main.post {
            if (!enabled || app == null) { onDone?.invoke(); return@post }
            // which clips to play for this cue
            val sfx = sfxFor[name]?.let { "sfx_$it" }
            val clips = when {
                style == "sound" && sfx != null -> listOf(sfx)
                style == "both" && sfx != null -> listOf(sfx, "cue_$name")
                else -> listOf("cue_$name")
            }
            // never build up a long backlog: drop the oldest waiting clips (their onDone still runs)
            while (queue.size + clips.size > 8 && queue.isNotEmpty()) queue.removeFirst().onDone?.invoke()
            clips.forEachIndexed { i, res -> queue.addLast(Item(res, if (i == clips.lastIndex) onDone else null)) }
            if (!playing) next()
        }
    }

    private fun stopNow() {
        try { player?.release() } catch (_: Exception) {}
        player = null
        playing = false
        while (queue.isNotEmpty()) queue.removeFirst().onDone?.invoke()
    }

    private fun next() {
        val c = app ?: return
        val item = if (queue.isEmpty()) null else queue.removeFirst()
        if (item == null) { playing = false; return }
        playing = true
        val id = c.resources.getIdentifier(item.name, "raw", c.packageName)
        if (id == 0) { item.onDone?.invoke(); next(); return }
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            val mp = MediaPlayer.create(c, id, attrs, AudioManager.AUDIO_SESSION_ID_GENERATE)
            if (mp == null) { item.onDone?.invoke(); next(); return }
            player = mp
            val finish = {
                try { mp.release() } catch (_: Exception) {}
                if (player === mp) player = null
                item.onDone?.invoke()
                next()
            }
            mp.setOnCompletionListener { finish() }
            mp.setOnErrorListener { _, _, _ -> finish(); true }
            mp.start()
        } catch (e: Exception) {
            CrashLog.log("cue", e)
            item.onDone?.invoke()
            next()
        }
    }
}
