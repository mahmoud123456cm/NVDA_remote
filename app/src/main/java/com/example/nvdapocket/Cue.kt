package com.example.nvdapocket

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper

/**
 * Plays the short recorded voice clips from res/raw (cue_connected, cue_pc_left...). Clips play one after another,
 * never on top of each other. play() can be called from any thread; onDone runs on the main thread when the clip has
 * finished (or straight away when cues are switched off), which the voice loop uses to wait before it listens again.
 */
object Cue {
    @Volatile var enabled = true
    private var app: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private class Item(val name: String, val onDone: (() -> Unit)?)
    private val queue = ArrayDeque<Item>()
    private var player: MediaPlayer? = null
    private var playing = false

    fun init(c: Context) {
        app = c.applicationContext
        enabled = Store.prefs(c).getBoolean("cues", true)
    }

    fun setEnabled(c: Context, on: Boolean) {
        enabled = on
        Store.prefs(c).edit().putBoolean("cues", on).apply()
        if (!on) main.post { stopNow() }
    }

    fun play(name: String, onDone: (() -> Unit)? = null) {
        main.post {
            if (!enabled || app == null) { onDone?.invoke(); return@post }
            // never build up a long backlog: drop the oldest waiting clips (their onDone still runs)
            while (queue.size >= 4) queue.removeFirst().onDone?.invoke()
            queue.addLast(Item(name, onDone))
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
        val id = c.resources.getIdentifier("cue_" + item.name, "raw", c.packageName)
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
