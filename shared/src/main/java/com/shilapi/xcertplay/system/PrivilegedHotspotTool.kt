package com.shilapi.xcertplay.system

import android.os.IBinder

/**
 * A standalone entrypoint executed under `app_process` with root (uid 0) privileges.
 * Directly reaches `IWifiManager.setWifiApEnabled` to bypass firmware permission restrictions.
 *
 * On Android 5.1, IWifiManager.setWifiApEnabled is void, so a null result is success. A boolean
 * false is a refusal and exits 4. Any argument other than `enable` or `disable` exits 2 and does
 * not change the radio.
 */
object PrivilegedHotspotTool {
    @JvmStatic
    fun main(args: Array<String>) {
        val enable = hotspotAction(args)
        if (enable == null) {
            System.err.println("BAD_ACTION")
            System.exit(2)
            return
        }
        try {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getMethod("getService", String::class.java)
            val wifiBinder = getService.invoke(null, "wifi") as? IBinder
                ?: run {
                    System.err.println("NO_WIFI_SERVICE")
                    System.exit(1)
                    return
                }
            val stubClass = Class.forName("android.net.wifi.IWifiManager\$Stub")
            val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
            val wifiService = asInterface.invoke(null, wifiBinder)
            val setWifiApEnabled = wifiService.javaClass.methods.firstOrNull {
                it.name == "setWifiApEnabled" && it.parameterTypes.size == 2
            } ?: run {
                System.err.println("NO_SET_WIFI_AP")
                System.exit(2)
                return
            }
            val result = setWifiApEnabled.invoke(wifiService, null, enable)
            println("RESULT:$result")
            System.exit(if (hotspotCallAccepted(result)) 0 else 4)
        } catch (error: Throwable) {
            error.printStackTrace()
            System.exit(3)
        }
    }
}

/** `enable` and `disable` only. Anything else, including a missing argument, is not a radio change. */
internal fun hotspotAction(args: Array<out String>): Boolean? = when (args.firstOrNull()) {
    "enable" -> true
    "disable" -> false
    else -> null
}

/**
 * Whether [android.net.wifi.IWifiManager.setWifiApEnabled] accepted the call.
 *
 * Android 5.1 declares the method as void, so reflection returns null on success. Later
 * platform builds return a boolean.
 */
internal fun hotspotCallAccepted(result: Any?): Boolean = result == null || result == true
