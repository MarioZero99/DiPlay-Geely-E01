package com.shilapi.xcertplay

import androidx.multidex.MultiDexApplication
import com.shilapi.governance.VehicleGovernance

class DiPlayApplication : MultiDexApplication() {
    override fun onCreate() {
        super.onCreate()
        VehicleGovernance.install(this, isExperimentalAllowed = ExperimentalFeatures.enabled)
        DiPlayBootstrap.prewarm(this)
    }
}
