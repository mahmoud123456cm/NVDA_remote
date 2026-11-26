package com.example.nvdapocket.tabs

import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import com.example.nvdapocket.MainActivity
import com.example.nvdapocket.Remote
import com.example.nvdapocket.Store
import com.example.nvdapocket.ui.Ui

class ConnectTab(act: MainActivity, ui: Ui) : Tab(act, ui) {
    override val title = "Connect"

    private lateinit var profileSpinner: Spinner
    private lateinit var nameField: EditText
    private lateinit var hostField: EditText
    private lateinit var portField: EditText
    private lateinit var keyField: EditText
    private lateinit var trustBox: CheckBox
    private var profiles = mutableListOf<Store.Profile>()

    override fun build(p: LinearLayout) {
        profileSpinner = Spinner(act)
        nameField = ui.field("Profile name", "")
        hostField = ui.field("Server host", "")
        portField = ui.field("Port", "6837", true)
        keyField = ui.field("Key", "")
        trustBox = ui.check("Accept untrusted certificate", false)
        val auto = ui.check("Connect automatically when the app opens", act.prefs.getBoolean("auto", false))
        auto.setOnCheckedChangeListener { _, c -> act.prefs.edit().putBoolean("auto", c).apply() }
        p.addView(ui.label("Saved connections"))
        p.addView(profileSpinner)
        listOf<View>(nameField, hostField, portField, keyField, trustBox, auto).forEach { p.addView(it) }
        p.addView(ui.row(
            ui.button("Save profile") { saveProfile() },
            ui.button("Delete profile") { deleteProfile() }))
        p.addView(ui.note("Pick a profile above, then press Connect at the top."))
        profileSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos < profiles.size) {
                    val pr = profiles[pos]
                    nameField.setText(pr.name); hostField.setText(pr.host); portField.setText(pr.port.toString())
                    keyField.setText(pr.key); trustBox.isChecked = pr.trust
                    act.prefs.edit().putString("last", pr.name).apply()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        refreshProfiles(act.prefs.getString("last", null))
    }

    private fun refreshProfiles(select: String?) {
        profiles = Store.profiles(act)
        val names = ArrayList<String>()
        profiles.forEach { names.add(it.name) }
        names.add("New profile")
        profileSpinner.adapter = ArrayAdapter<String>(act, android.R.layout.simple_spinner_dropdown_item, names)
        val idx = if (select != null) profiles.indexOfFirst { it.name == select } else -1
        profileSpinner.setSelection(if (idx >= 0) idx else names.size - 1)
    }

    private fun saveProfile() {
        val host = hostField.text.toString().trim()
        val key = keyField.text.toString().trim()
        if (host.isEmpty() || key.isEmpty()) { ui.toast("Enter host and key first"); return }
        var name = nameField.text.toString().trim()
        if (name.isEmpty()) name = host
        val port = portField.text.toString().toIntOrNull() ?: 6837
        val list = Store.profiles(act).filter { it.name != name }.toMutableList()
        list.add(Store.Profile(name, host, port, key, trustBox.isChecked))
        Store.saveProfiles(act, list)
        act.prefs.edit().putString("last", name).apply()
        refreshProfiles(name)
        ui.toast("Saved \"$name\"")
    }

    private fun deleteProfile() {
        val pos = profileSpinner.selectedItemPosition
        if (pos < 0 || pos >= profiles.size) { ui.toast("Select a saved profile first"); return }
        val name = profiles[pos].name
        Store.saveProfiles(act, Store.profiles(act).filter { it.name != name })
        refreshProfiles(null)
        ui.toast("Deleted \"$name\"")
    }

    fun connectFromFields() {
        val h = hostField.text.toString().trim()
        val k = keyField.text.toString().trim()
        if (h.isEmpty() || k.isEmpty()) { act.setStatusText("Enter host and key on the Connect tab"); act.showTab(0); return }
        val name = nameField.text.toString().trim()
        if (name.isNotEmpty()) act.prefs.edit().putString("last", name).apply()
        Remote.start(act, h, portField.text.toString().toIntOrNull() ?: 6837, k, trustBox.isChecked)
        act.updateUi()
    }

    fun connectLast() {
        val ps = Store.profiles(act)
        val last = act.prefs.getString("last", null)
        val pr = ps.firstOrNull { it.name == last } ?: ps.firstOrNull()
        if (pr != null) { Remote.start(act, pr.host, pr.port, pr.key, pr.trust); act.updateUi() }
        else if (hostField.text.isNotEmpty()) connectFromFields()
    }
}
