package com.shilapi.xcertplay.network

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.SystemClock
import com.shilapi.xcertplay.DiagLog
import java.lang.reflect.Method

/**
 * The attempts to make when switching the car hotspot on, in order. A single-radio firmware cannot
 * hold the access point while station mode owns the radio, so the last attempt releases it first;
 * when station mode is already off there is nothing left to try after the first.
 */
internal fun hotspotEnableAttempts(stationEnabled: Boolean): List<Boolean> =
    if (stationEnabled) listOf(false, true) else listOf(false)

/**
 * Whether turning the driver's Wi-Fi off could still change the answer.
 *
 * A firmware that refused the permission, or that has no hotspot API at all, refuses exactly the same
 * way with station mode gone — the radio was never what it was gating on. Releasing station mode is
 * expensive on this head unit (the driver's Wi-Fi, and their ADB over it, go with it), so it is only
 * spent where it can still buy something.
 */
internal fun releasingStationModeCouldHelp(previous: HotspotStartResult): Boolean = when (previous) {
    HotspotStartResult.FAILED, HotspotStartResult.TIMED_OUT -> true
    else -> false
}

/**
 * Makes the attempts [hotspotEnableAttempts] asks for, and puts the driver's Wi-Fi back if the hotspot
 * still did not come on.
 *
 * The restore matters as much as the restraint: once station mode has been spent and the hotspot still
 * refused, leaving it off buys nothing and costs the driver the connection they had before the switch
 * was ever attempted.
 */
internal fun enableHotspot(
    stationEnabled: Boolean,
    releaseStationMode: () -> Unit,
    restoreStationMode: () -> Unit,
    enable: () -> HotspotStartResult,
): HotspotStartResult {
    var last = HotspotStartResult.FAILED
    var released = false
    for (releaseStation in hotspotEnableAttempts(stationEnabled)) {
        if (releaseStation) {
            if (!releasingStationModeCouldHelp(last)) break
            releaseStationMode()
            released = true
        }
        last = enable()
        if (last == HotspotStartResult.READY) return last
    }
    if (released) restoreStationMode()
    return last
}

/**
 * Why the switch did not finish. The driver is shown [reason], so a firmware that gates the
 * permission is reported differently from a car that simply would not start its hotspot.
 */
internal enum class HotspotStartResult(val reason: String) {
    READY("the hotspot is on"),
    NO_WIFI_SERVICE("there is no Wi-Fi service"),
    UNSUPPORTED("this firmware does not expose the hotspot API"),
    PERMISSION_REQUIRED("the firmware does not give DiPlay permission to switch it on"),
    FAILED("the car refused the request"),
    TIMED_OUT("the car did not report the hotspot on in time"),
}

/**
 * Switches the head unit's own Wi-Fi hotspot on.
 *
 * DiPlay used to only read the hotspot state ([CarHotspotStatus]) and leave the driver to turn it
 * on in the car settings, because enabling it needs a permission many firmwares grant only over ADB.
 * This attempts the switch anyway and reports why it could not, so the caller can keep falling back
 * to the old message rather than failing differently.
 *
 * The AP methods are reached reflectively only because compileSdk no longer exposes them; on this
 * project's API 19 floor `setWifiApEnabled` is the platform's own public method. No vendor interface
 * is used — ECARX's own AP service belongs to head units newer than this fork targets, and the
 * tethering route other builds take (`IConnectivityManager.startTethering` with a caller package)
 * does not exist before Android 11, so neither would ever run here.
 */
internal class CarHotspotController(context: Context) {
    private val appContext: Context = context.applicationContext ?: context
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val setApEnabled: Method? = wifi?.let {
        runCatching {
            WifiManager::class.java.getMethod(
                "setWifiApEnabled",
                WifiConfiguration::class.java,
                Boolean::class.javaPrimitiveType,
            )
        }.getOrNull()
    }

    /** Turns the hotspot on when it is off and waits for the firmware to report it enabled. */
    fun ensureEnabled(timeoutMillis: Long): HotspotStartResult {
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        if (CarHotspotStatus.isEnabled(appContext) == true) return HotspotStartResult.READY
        val wifi = wifi ?: return HotspotStartResult.NO_WIFI_SERVICE
        val setAp = setApEnabled ?: return HotspotStartResult.UNSUPPORTED

        return enableHotspot(
            stationEnabled = wifi.isWifiEnabled,
            releaseStationMode = { wifi.isWifiEnabled = false },
            restoreStationMode = { wifi.isWifiEnabled = true },
        ) { attemptEnable(setAp, wifi, timeoutMillis) }
    }

    private fun attemptEnable(setAp: Method, wifi: WifiManager, timeoutMillis: Long): HotspotStartResult =
        when (enableAccessPoint(setAp, wifi)) {
            null -> HotspotStartResult.PERMISSION_REQUIRED
            false -> HotspotStartResult.FAILED
            true -> if (awaitEnabled(timeoutMillis)) {
                HotspotStartResult.READY
            } else {
                HotspotStartResult.TIMED_OUT
            }
        }

    /**
     * Passes a null configuration on purpose: it keeps whatever the car already has, where building
     * one would rewrite the head unit's own hotspot settings behind the driver's back.
     *
     * Returns null when the firmware refused the permission, which is the usual failure and worth
     * separating from a car that accepted the call but did not switch its hotspot on.
     */
    private fun enableAccessPoint(setAp: Method, wifi: WifiManager): Boolean? = try {
        setAp.invoke(wifi, null, true) as Boolean
    } catch (error: ReflectiveOperationException) {
        deniedOrFailed(error)
    } catch (error: SecurityException) {
        deniedOrFailed(error)
    } catch (error: RuntimeException) {
        deniedOrFailed(error)
    }

    /** A SecurityException, direct or inside the InvocationTargetException, is the firmware gating us. */
    private fun deniedOrFailed(error: Exception): Boolean? {
        val denied = error is SecurityException || error.cause is SecurityException
        DiagLog.w(
            TAG,
            if (denied) "the firmware refused the hotspot permission"
            else "the hotspot API rejected the call",
            error,
        )
        return if (denied) null else false
    }

    private fun awaitEnabled(timeoutMillis: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (CarHotspotStatus.isEnabled(appContext) == true) return true
            SystemClock.sleep(STATE_POLL_MILLIS)
        }
        return CarHotspotStatus.isEnabled(appContext) == true
    }

    private companion object {
        const val TAG = "DiPlay-CarHotspot"
        const val STATE_POLL_MILLIS = 250L
    }
}
