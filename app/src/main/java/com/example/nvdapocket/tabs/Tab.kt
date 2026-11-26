package com.example.nvdapocket.tabs

import android.widget.LinearLayout
import com.example.nvdapocket.MainActivity
import com.example.nvdapocket.ui.Ui

/** One screen of the app. MainActivity creates the panel, calls build() once, and forwards lifecycle events. */
abstract class Tab(protected val act: MainActivity, protected val ui: Ui) {
    abstract val title: String
    lateinit var panel: LinearLayout
    abstract fun build(p: LinearLayout)
    open fun onResume() {}
    open fun onPause() {}
    open fun onDestroy() {}
}
