package com.example.nvdapocket.tabs

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.CheckBox
import android.widget.LinearLayout
import com.example.nvdapocket.App
import com.example.nvdapocket.ConnLog
import com.example.nvdapocket.CrashLog
import com.example.nvdapocket.MainActivity
import com.example.nvdapocket.ui.Ui

/** Ways to reach the developer: WhatsApp and email, with a ready-made message that can include app and phone details. */
class SupportTab(act: MainActivity, ui: Ui) : Tab(act, ui) {
    override val title = "Support"

    private val whatsappShown = "+216 25 188 309"
    private val whatsappDigits = "21625188309"
    private val email = "mejri7156@gmail.com"

    private lateinit var infoBox: CheckBox
    private lateinit var historyBox: CheckBox
    private lateinit var logBox: CheckBox

    override fun build(p: LinearLayout) {
        p.addView(ui.label("Need help or found a problem?"))
        p.addView(ui.note("Write to me and I will answer as soon as I can. Tell me what you did, what you expected, and what happened instead."))

        p.addView(ui.label("WhatsApp"))
        p.addView(ui.note(whatsappShown).apply { setTextIsSelectable(true); textSize = 18f })
        p.addView(ui.button("Open a WhatsApp chat with support") { openWhatsApp() })
        p.addView(ui.button("Copy the WhatsApp number") { copy("WhatsApp number", "+$whatsappDigits") })

        p.addView(ui.label("Email"))
        p.addView(ui.note(email).apply { setTextIsSelectable(true); textSize = 18f })
        p.addView(ui.button("Write an email to support") { writeEmail() })
        p.addView(ui.button("Copy the email address") { copy("Email address", email) })

        p.addView(ui.label("What to put in my message"))
        infoBox = ui.check("App version and phone model (recommended)", true)
        historyBox = ui.check("Connection history (it contains your server address, check it first)", false)
        logBox = ui.check("Error log", false)
        p.addView(infoBox); p.addView(historyBox); p.addView(logBox)
        p.addView(ui.button("Preview my message") {
            AlertDialog.Builder(act).setTitle("Your message").setMessage(message(false))
                .setPositiveButton("Close", null).show()
        })
        p.addView(ui.note("Nothing is sent by itself. WhatsApp or your email app opens with the text ready, and you press send."))

        p.addView(ui.label("About"))
        p.addView(ui.note(App.name(act) + " " + version() + "\nAndroid " + Build.VERSION.RELEASE + ", " + Build.MANUFACTURER + " " + Build.MODEL))
    }

    private fun version(): String = try {
        val pi = act.packageManager.getPackageInfo(act.packageName, 0)
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
        "${pi.versionName} (build $code)"
    } catch (_: Exception) { "" }

    /** The text to send. WhatsApp links have a length limit, so less of the logs go there. */
    private fun message(forWhatsApp: Boolean): String {
        val sb = StringBuilder()
        sb.append("Hello, I need help with ").append(App.name(act)).append(".\n\n[Please describe what happened here]\n")
        if (infoBox.isChecked) {
            sb.append("\n--- Phone and app ---\n")
            sb.append("App: ").append(App.name(act)).append(' ').append(version()).append('\n')
            sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            sb.append("Phone: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        }
        if (historyBox.isChecked) {
            val lines = ConnLog.read().lines().filter { it.isNotBlank() }.takeLast(if (forWhatsApp) 12 else 60)
            sb.append("\n--- Connection history ---\n").append(if (lines.isEmpty()) "(empty)" else lines.joinToString("\n")).append('\n')
        }
        if (logBox.isChecked) {
            val t = CrashLog.read(act)
            val cut = if (forWhatsApp) 1200 else 6000
            sb.append("\n--- Error log ---\n").append(if (t.isBlank()) "(empty)" else if (t.length > cut) t.takeLast(cut) else t).append('\n')
        }
        return sb.toString()
    }

    private fun openWhatsApp() {
        val uri = Uri.parse("https://wa.me/$whatsappDigits?text=" + Uri.encode(message(true)))
        try { act.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        catch (_: ActivityNotFoundException) { ui.toast("WhatsApp or a web browser is needed. You can copy the number instead.") }
    }

    private fun writeEmail() {
        val i = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
            putExtra(Intent.EXTRA_SUBJECT, App.name(act) + " support")
            putExtra(Intent.EXTRA_TEXT, message(false))
        }
        try { act.startActivity(i) }
        catch (_: ActivityNotFoundException) { ui.toast("No email app found. You can copy the address instead.") }
    }

    private fun copy(label: String, text: String) {
        (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
        ui.toast("Copied: $text")
    }
}
