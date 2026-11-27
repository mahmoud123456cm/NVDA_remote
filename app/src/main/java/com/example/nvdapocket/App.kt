package com.example.nvdapocket

import android.app.Application
import android.content.Context

class App : Application() {
    companion object {
        /** The app's name as shown on the phone. It lives in res/values/strings.xml, so it is changed in one place. */
        fun name(c: Context): String = try {
            c.applicationInfo.loadLabel(c.packageManager).toString()
        } catch (_: Exception) { "Pocket Link" }
    }

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        ConnLog.init(this)
        Cue.init(this)
        VoiceEngine.init(this)
    }
}
