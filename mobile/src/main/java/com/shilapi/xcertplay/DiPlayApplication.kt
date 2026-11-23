package com.shilapi.xcertplay

import androidx.multidex.MultiDexApplication
import com.shilapi.governance.VehicleGovernance

class DiPlayApplication : MultiDexApplication() {
    override fun onCreate() {
        super.onCreate()
        // A previous process may have died while still the head unit's media-button target.
        // Opening DiPlay is the moment that registration can be cleared without a unit reboot.
        HeadUnitMediaButtons.release(this)
        VehicleGovernance.diagnosticLogger = { tag, msg -> DiagLog.i(tag, msg) }
        VehicleGovernance.install(this, isExperimentalAllowed = true)
        DiPlayBootstrap.prewarm(this)
    }
}
