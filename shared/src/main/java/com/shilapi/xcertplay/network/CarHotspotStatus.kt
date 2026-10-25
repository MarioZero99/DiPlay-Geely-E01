package com.shilapi.xcertplay.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager

/** What the head unit's own hotspot is configured with, when the firmware will say. */
data class CarHotspotAccessPoint(val ssid: String, val passphrase: String?)

/**
 * Reads the head unit's own Wi-Fi hotspot, for the "Car hotspot" link.
 *
 * DiPlay does not turn the hotspot on itself: that needs a permission Android only grants over
 * ADB. The user turns it on in the car settings, or automates it with a tool such as BYDMate.
 */
object CarHotspotStatus {
    private const val WIFI_AP_STATE_ENABLED = 13

    /**
     * True/false from the Wi-Fi AP state, or null when the firmware hides it (then callers must
     * not block the connection). Interface flags are not used: BYD keeps wlan1 up with an address
     * while tethering is off.
     */
    fun isEnabled(context: Context): Boolean? {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        return runCatching {
            WifiManager::class.java.getMethod("getWifiApState").invoke(wifi) as Int == WIFI_AP_STATE_ENABLED
        }.recoverCatching {
            WifiManager::class.java.getMethod("isWifiApEnabled").invoke(wifi) as Boolean
        }.getOrNull()
    }

    /**
     * What the car's own hotspot is configured with, or null when the firmware hides it.
     *
     * The wireless path has to name the access point it attaches to, and on this fork's API floor
     * the car's own hotspot is the only mode left. Its name and key are already in the firmware, so
     * a driver who is never asked for them cannot leave them blank.
     */
    fun accessPoint(context: Context): CarHotspotAccessPoint? {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return null
        return legacyApAccessPoint(wifi)
    }

    @SuppressLint("PrivateApi")
    private fun legacyApAccessPoint(wifi: WifiManager): CarHotspotAccessPoint? = try {
        val configuration = WifiManager::class.java
            .getMethod("getWifiApConfiguration")
            .invoke(wifi) as? WifiConfiguration
        val ssid = unquote(configuration?.SSID)?.takeIf { it.isNotBlank() }
        if (configuration == null || ssid == null) null
        else CarHotspotAccessPoint(ssid, unquote(configuration.preSharedKey))
    } catch (_: Throwable) {
        null
    }

    /** A legacy AP configuration stores both values quoted; neither quote is part of the value. */
    private fun unquote(value: String?): String? =
        if (value != null && value.length >= 2 && value.first() == '"' && value.last() == '"') {
            value.substring(1, value.length - 1)
        } else {
            value
        }
}
