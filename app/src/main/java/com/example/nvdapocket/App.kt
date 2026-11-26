package com.example.nvdapocket

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        ConnLog.init(this)
        Cue.init(this)
        VoiceEngine.init(this)
    }
}
