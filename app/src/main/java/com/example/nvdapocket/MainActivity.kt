package com.example.nvdapocket

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.nvdapocket.tabs.ConnectTab
import com.example.nvdapocket.tabs.ControlTab
import com.example.nvdapocket.tabs.MacrosTab
import com.example.nvdapocket.tabs.NvdaTab
import com.example.nvdapocket.tabs.SettingsTab
import com.example.nvdapocket.tabs.SupportTab
import com.example.nvdapocket.tabs.Tab
import com.example.nvdapocket.tabs.VoiceTab
import com.example.nvdapocket.ui.Ui

/**
 * The shell of the app: the Connect/Mute header, the tab strip, intents from the quick tile, and lifecycle
 * forwarding. Each screen lives in its own class under tabs/.
 */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    lateinit var prefs: SharedPreferences
        private set
    private lateinit var ui: Ui
    private lateinit var status: TextView
    private lateinit var connectBtn: Button
    private lateinit var muteBtn: Button
    private lateinit var content: LinearLayout
    private val tabs = mutableListOf<Tab>()
    private val tabBtns = mutableListOf<Button>()

    private lateinit var connectTab: ConnectTab
    private lateinit var voiceTab: VoiceTab
    private lateinit var settingsTab: SettingsTab

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = Store.prefs(this)
        ui = Ui(this)
        Remote.initTts(this)
        Voice.init(this)
        Remote.ttsRate = prefs.getFloat("rate", 1f)
        Remote.ttsPitch = prefs.getFloat("pitch", 1f)
        Remote.applyTts()
        Voice.lang = prefs.getString("vlang", "en") ?: "en"
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        migrateOldConnection()

        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        applySystemBarInsets(outer)
        val header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(8), ui.dp(8), ui.dp(8), 0) }
        connectBtn = Button(this).apply { text = "Connect" }
        connectBtn.setOnClickListener {
            if (Remote.active) { Remote.stop(this); updateUi() } else connectTab.connectFromFields()
        }
        muteBtn = Button(this).apply { text = "Mute speech" }
        muteBtn.setOnClickListener { Remote.toggleMute(); updateUi() }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(connectBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(muteBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        status = TextView(this).apply { text = Remote.lastStatus; textSize = 16f }
        header.addView(top); header.addView(status)
        outer.addView(header)

        connectTab = ConnectTab(this, ui)
        voiceTab = VoiceTab(this, ui)
        settingsTab = SettingsTab(this, ui)
        tabs.add(connectTab)
        tabs.add(ControlTab(this, ui))
        tabs.add(NvdaTab(this, ui))
        tabs.add(voiceTab)
        tabs.add(MacrosTab(this, ui))
        tabs.add(settingsTab)
        tabs.add(SupportTab(this, ui))

        // The tabs sit in fixed rows of three, never in a scroller, so a swipe can't push Connect or Settings off screen.
        val tabBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(4), 0, ui.dp(4), 0) }
        var tabRow: LinearLayout? = null
        tabs.forEachIndexed { i, t ->
            if (i % 3 == 0) { tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; tabBox.addView(tabRow) }
            val tb = Button(this).apply { text = t.title; isAllCaps = false; textSize = 13f }
            tb.setOnClickListener { showTab(i) }
            tabBtns.add(tb)
            tabRow!!.addView(tb, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        // keep the buttons of a short last row the same width as the others
        repeat((3 - tabs.size % 3) % 3) { tabRow!!.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
        outer.addView(tabBox)

        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        outer.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(outer)

        for (t in tabs) {
            val p = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)) }
            content.addView(p)
            t.panel = p
            t.build(p)
        }
        showTab(1)
        updateUi()

        if (CrashLog.consumeCrashFlag(this)) ui.toast("The app closed unexpectedly last time. See Settings, Problems.")
        if (prefs.getBoolean("auto", false) && !Remote.active) connectTab.connectLast()
        handleIntent(intent)
    }

    /** With target SDK 35 Android draws apps edge to edge, so keep the content clear of the system bars and keyboard. */
    private fun applySystemBarInsets(root: LinearLayout) {
        if (Build.VERSION.SDK_INT < 35) return
        root.setOnApplyWindowInsetsListener { v, insets ->
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout())
            v.setPadding(i.left, i.top, i.right, i.bottom)
            insets
        }
        window.insetsController?.setSystemBarsAppearance(
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
    }

    /** Older versions saved a single connection; turn it into a profile. */
    private fun migrateOldConnection() {
        val oldHost = prefs.getString("host", "") ?: ""
        if (Store.profiles(this).isEmpty() && oldHost.isNotEmpty()) {
            Store.saveProfiles(this, listOf(Store.Profile("Default", oldHost,
                (prefs.getString("port", "6837") ?: "6837").toIntOrNull() ?: 6837,
                prefs.getString("key", "") ?: "", prefs.getBoolean("trust", false))))
            prefs.edit().putString("last", "Default").apply()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.getBooleanExtra("connect_last", false) == true) {
            i.removeExtra("connect_last")
            if (!Remote.active) connectTab.connectLast()
            handler.postDelayed({ moveTaskToBack(true) }, 500)
        }
    }

    override fun onResume() {
        super.onResume()
        Remote.statusListener = { s -> status.text = s; updateUi() }
        status.text = Remote.lastStatus
        updateUi()
        tabs.forEach { it.onResume() }
    }

    override fun onPause() {
        Remote.statusListener = null
        tabs.forEach { it.onPause() }
        super.onPause()
    }

    override fun onDestroy() {
        tabs.forEach { it.onDestroy() }
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        voiceTab.onPermissionResult(requestCode, grantResults)
    }

    // ---------- used by the tabs ----------
    fun showTab(i: Int) {
        tabs.forEachIndexed { idx, t -> t.panel.visibility = if (idx == i) android.view.View.VISIBLE else android.view.View.GONE }
        tabBtns.forEachIndexed { idx, tb -> tb.text = if (idx == i) "[" + tabs[idx].title + "]" else tabs[idx].title }
    }

    fun setStatusText(s: String) { status.text = s }

    fun updateUi() {
        connectBtn.text = if (Remote.active) "Disconnect" else "Connect"
        muteBtn.text = if (Remote.speak) "Mute speech" else "Unmute speech"
        settingsTab.syncSpeak()
    }

    // ---------- volume keys as Up / Down arrows ----------
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        VolKeys.handle(this, event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        VolKeys.handle(this, event) || super.onKeyUp(keyCode, event)
}
