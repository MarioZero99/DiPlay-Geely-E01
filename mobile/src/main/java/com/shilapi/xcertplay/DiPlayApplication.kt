package com.shilapi.xcertplay

import androidx.multidex.MultiDexApplication

class DiPlayApplication : MultiDexApplication() {
    override fun onCreate() {
        super.onCreate()
        DiPlayBootstrap.prewarm(this)
    }
}
