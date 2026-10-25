package com.shilapi.xcertplay

import androidx.multidex.MultiDexApplication
import com.shilapi.governance.VehicleGovernance

class DiPlayApplication : MultiDexApplication() {
    override fun onCreate() {
        super.onCreate()
        VehicleGovernance.diagnosticLogger = { tag, msg -> DiagLog.i(tag, msg) }
        VehicleGovernance.install(this, isExperimentalAllowed = true)
        DiPlayBootstrap.prewarm(this)
    }
}
