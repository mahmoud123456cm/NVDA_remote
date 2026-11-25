package com.example.nvdapocket

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/** Receives "Share" from other apps and sends the text to the PC as clipboard, clipboard + paste, or typed keys. */
class ShareActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Remote.initTts(this)
        val text = intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        if (text.isNullOrEmpty()) { finish(); return }
        if (!Remote.connected) {
            Toast.makeText(this, "Not connected. Open NVDA Pocket and connect first.", Toast.LENGTH_LONG).show()
            finish(); return
        }
        val mode = Store.prefs(this).getString("shareMode", "ask") ?: "ask"
        if (mode == "ask") {
            AlertDialog.Builder(this)
                .setTitle("Send to PC")
                .setMessage(if (text.length > 120) text.substring(0, 120) + "..." else text)
                .setPositiveButton("Put in PC clipboard") { _, _ -> go("clip", text) }
                .setNeutralButton("Clipboard and paste") { _, _ -> go("paste", text) }
                .setNegativeButton("Type it") { _, _ -> go("type", text) }
                .setOnCancelListener { finish() }
                .show()
        } else {
            go(mode, text)
        }
    }

    private fun go(mode: String, text: String) {
        Toast.makeText(this, Remote.deliver(mode, text), Toast.LENGTH_SHORT).show()
        finish()
    }
}
