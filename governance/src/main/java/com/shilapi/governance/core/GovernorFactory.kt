package com.shilapi.governance.core

import android.content.Context
import android.os.Build
import com.shilapi.governance.platform.E01PrivilegedBridge

/**
 * Factory for creating the appropriate [IVehicleGovernor] for the current environment.
 */
object GovernorFactory {
    fun create(context: Context): IVehicleGovernor {
        val hasBridge = E01PrivilegedBridge.isAvailable()
        val isMt6735 = Build.HARDWARE.contains("mt6735", ignoreCase = true) ||
                Build.BOARD.contains("mt6735", ignoreCase = true)

        return if (hasBridge || isMt6735) {
            E01Mtk6735Governor(context.applicationContext)
        } else {
            NoOpGovernor
        }
    }
}
