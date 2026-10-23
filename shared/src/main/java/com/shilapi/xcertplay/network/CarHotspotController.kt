package com.shilapi.xcertplay.network

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.SystemClock
import com.shilapi.xcertplay.DiagLog
import com.shilapi.xcertplay.system.RootShellBridge
import java.lang.reflect.Method
import kotlin.math.min

/** WifiManager hotspot states used by this firmware. Only [WIFI_AP_STATE_DISABLED] is fully off. */
internal const val WIFI_AP_STATE_DISABLING = 10
internal const val WIFI_AP_STATE_DISABLED = 11
internal const val WIFI_AP_STATE_ENABLING = 12
internal const val WIFI_AP_STATE_ENABLED = 13

/** How long the native station call is polled before root is asked. The rest of the action budget remains. */
internal const val NATIVE_STATION_POLL_MILLIS = 800L

internal enum class ApPhase {
    ON,
    OFF,
    TRANSITION,
    UNKNOWN,
}

/**
 * Collapses the firmware's hotspot state into something a single-radio switch can wait on.
 *
 * Enabling and disabling are not done. An unreadable state is not "off": the station side must
 * not start while the access point might still hold the radio.
 */
internal fun classifyApPhase(apState: Int?, legacyEnabled: Boolean?): ApPhase = when {
    apState == WIFI_AP_STATE_ENABLED -> ApPhase.ON
    apState == WIFI_AP_STATE_DISABLED -> ApPhase.OFF
    apState == WIFI_AP_STATE_ENABLING || apState == WIFI_AP_STATE_DISABLING -> ApPhase.TRANSITION
    apState != null -> ApPhase.TRANSITION
    legacyEnabled == true -> ApPhase.ON
    legacyEnabled == false -> ApPhase.OFF
    else -> ApPhase.UNKNOWN
}

/**
 * Whether an attempt may turn station mode off first.
 *
 * The auto-hotspot switch and an explicit connect may spend it. A switch nobody asked for must
 * not: the driver's Wi-Fi, and on this head unit their ADB over it, would go with it.
 */
internal fun mayReleaseStationMode(spendDriverWifi: Boolean, stationEnabled: Boolean): Boolean =
    spendDriverWifi && stationEnabled

/**
 * Native poll, then whatever is left of [timeoutMillis] for the root command and its confirmation.
 *
 * The native wait must stay shorter than the action budget. Giving it the whole budget leaves the
 * root confirmation with nothing to wait on.
 */
internal fun stationPollSplit(timeoutMillis: Long): Pair<Long, Long> {
    val native = min(NATIVE_STATION_POLL_MILLIS, timeoutMillis.coerceAtLeast(0))
    return native to (timeoutMillis - native).coerceAtLeast(0)
}

/**
 * How an access-point request uses its budget.
 *
 * A native call that returned true keeps the whole budget and is not asked again through root.
 * A refusal goes to root at once, then still waits out the same budget: the native call can
 * finish after the root command has already failed.
 */
internal data class ApWait(val escalateToRoot: Boolean, val waitMillis: Long)

internal fun apWaitPlan(nativeAccepted: Boolean, timeoutMillis: Long): ApWait =
    ApWait(escalateToRoot = !nativeAccepted, waitMillis = timeoutMillis)

/**
 * What [CarHotspotController.releaseForStationMode] tells the connect path.
 *
 * An accepted request that has not been confirmed yet is not a refusal. The attach that follows
 * is what finds out whether the radio was free. A confirmed-off hotspot is success either way.
 */
internal fun sessionReleaseResult(callAccepted: Boolean, confirmedOff: Boolean, timeoutMillis: Long): String? =
    if (confirmedOff || callAccepted) {
        null
    } else {
        "the car did not report hotspot disabled within ${timeoutMillis}ms"
    }

/**
 * Turns the hotspot on only after station mode is confirmed off, and puts station mode back when
 * the hotspot still did not come on.
 *
 * [releaseStationMode] returns false when station mode is still on. The access point is not asked
 * in that case: on this radio the two cannot run together.
 */
internal fun enableHotspot(
    spendStation: Boolean,
    releaseStationMode: () -> Boolean,
    restoreStationMode: () -> Unit,
    enable: () -> HotspotStartResult,
): HotspotStartResult {
    var released = false
    if (spendStation) {
        released = releaseStationMode()
        if (!released) return HotspotStartResult.RADIO_BUSY
    }
    val result = enable()
    if (result == HotspotStartResult.READY) return result
    if (released) restoreStationMode()
    return result
}

/**
 * Why the switch did not finish. The driver is shown [reason], so a firmware that gates the
 * permission is reported differently from a car that simply would not start its hotspot.
 */
enum class HotspotStartResult(val reason: String) {
    READY("the hotspot is on"),
    NO_WIFI_SERVICE("there is no Wi-Fi service"),
    UNSUPPORTED("this firmware does not expose the hotspot API"),
    PERMISSION_REQUIRED("the firmware does not give DiPlay permission to switch it on"),
    FAILED("the car refused the request"),
    RADIO_BUSY("the car's Wi-Fi is still on, so the hotspot could not start"),
    TIMED_OUT("the car did not report the hotspot on in time"),
}

/**
 * Switches the head unit's own Wi-Fi hotspot and station radio on and off.
 *
 * Station and access-point changes each get their own budget. Station mode is confirmed off
 * before the access point is asked, because this radio cannot hold both. Native calls go first.
 * Root is only the fallback when that call is refused, and a slow native success is left to
 * finish inside the caller's budget.
 */
class CarHotspotController(context: Context) {
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

    /** Whether Wi-Fi Station (client) mode is currently active. */
    fun isStationEnabled(): Boolean = wifi?.isWifiEnabled == true

    fun isHotspotFullyOn(): Boolean = readApPhase() == ApPhase.ON

    fun isHotspotFullyOff(): Boolean = readApPhase() == ApPhase.OFF

    /**
     * Ensures Wi-Fi station mode is disabled, freeing the shared radio for SoftAP.
     *
     * The native call is polled for at most 800ms. Root `svc wifi disable` then uses the rest of
     * [timeoutMillis]. Returns false when station mode is still on at the deadline.
     */
    fun ensureStationDisabled(
        timeoutMillis: Long = STATION_OP_TIMEOUT_MILLIS,
        stillCurrent: () -> Boolean = { true },
    ): Boolean = ensureStation(enabled = false, timeoutMillis = timeoutMillis, stillCurrent = stillCurrent)

    /**
     * Ensures Wi-Fi station mode is enabled.
     *
     * Same split as [ensureStationDisabled]: a short native poll, then root `svc wifi enable`
     * inside the same deadline.
     */
    fun ensureStationEnabled(
        timeoutMillis: Long = STATION_OP_TIMEOUT_MILLIS,
        stillCurrent: () -> Boolean = { true },
    ): Boolean = ensureStation(enabled = true, timeoutMillis = timeoutMillis, stillCurrent = stillCurrent)

    /**
     * Turns the hotspot on when it is off and waits for the firmware to report it enabled.
     *
     * [timeoutMillis] is the access-point budget only. Turning station mode off, when [spendDriverWifi]
     * allows it, has its own two seconds and is not subtracted from this budget. The driver's Wi-Fi
     * is put back when station mode was spent and the hotspot still did not come on.
     */
    fun ensureEnabled(
        timeoutMillis: Long = DEFAULT_OP_TIMEOUT_MILLIS,
        spendDriverWifi: Boolean = true,
        stillCurrent: () -> Boolean = { true },
    ): HotspotStartResult {
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        if (!stillCurrent()) return HotspotStartResult.TIMED_OUT
        if (isHotspotFullyOn()) return HotspotStartResult.READY
        wifi ?: return HotspotStartResult.NO_WIFI_SERVICE
        if (setApEnabled == null && !RootShellBridge.isAvailable()) return HotspotStartResult.UNSUPPORTED

        return enableHotspot(
            spendStation = mayReleaseStationMode(spendDriverWifi, isStationEnabled()),
            releaseStationMode = {
                ensureStationDisabled(STATION_OP_TIMEOUT_MILLIS, stillCurrent) && stillCurrent()
            },
            restoreStationMode = {
                if (stillCurrent()) ensureStationEnabled(STATION_OP_TIMEOUT_MILLIS, stillCurrent)
            },
        ) { enableAccessPointUntil(timeoutMillis, stillCurrent) }
    }

    /**
     * Whether this build can switch the car's own hotspot off.
     *
     * Root counts: a firmware that hides [setWifiApEnabled] on [WifiManager] can still expose it
     * to a uid 0 process. [releaseForStationMode] tries that path.
     */
    fun canReleaseHotspot(): Boolean = wifi != null && (setApEnabled != null || RootShellBridge.isAvailable())

    /**
     * Switches the car's own hotspot off.
     *
     * A call the firmware accepted is not a refusal when the new state has not been confirmed yet:
     * the connect path carries on, and the reconciler checks [isHotspotFullyOff] itself before it
     * turns station mode on. State 10 and 12 are still in progress and are waited out.
     * An unreadable state is left alone.
     *
     * @return why the request was refused, or null when it was not.
     */
    fun releaseForStationMode(
        timeoutMillis: Long = DEFAULT_OP_TIMEOUT_MILLIS,
        stillCurrent: () -> Boolean = { true },
    ): String? {
        if (!stillCurrent()) return null
        when (readApPhase()) {
            ApPhase.OFF, ApPhase.UNKNOWN -> return null
            ApPhase.ON, ApPhase.TRANSITION -> Unit
        }
        val manager = wifi ?: return "there is no Wi-Fi service"
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        var accepted = false
        val method = setApEnabled
        if (method != null) {
            val native = try {
                method.invoke(manager, null, false) as Boolean
            } catch (_: Throwable) {
                false
            }
            if (native) {
                accepted = true
                if (awaitPhase(ApPhase.OFF, deadline, stillCurrent)) return null
            }
        }
        if (!accepted && stillCurrent() && RootShellBridge.isAvailable()) {
            val path = appContext.packageCodePath
            if (RootShellBridge.shellSafeApkPath(path)) {
                DiagLog.i(TAG, "Native hotspot disable was not accepted, trying root")
                if (RootShellBridge.execute(RootShellBridge.hotspotCommand(path, "disable"))) {
                    accepted = true
                }
            } else {
                DiagLog.w(TAG, "Hotspot apk path is not safe to pass to a root shell")
            }
        }
        if (!stillCurrent()) return null
        val confirmed = awaitPhase(ApPhase.OFF, deadline, stillCurrent)
        if (!stillCurrent()) return null
        return sessionReleaseResult(accepted, confirmed, timeoutMillis)
    }

    private fun ensureStation(enabled: Boolean, timeoutMillis: Long, stillCurrent: () -> Boolean): Boolean {
        if (!stillCurrent()) return false
        if (isStationEnabled() == enabled) return true
        val manager = wifi ?: return false
        val start = SystemClock.elapsedRealtime()
        val deadline = start + timeoutMillis
        val (nativeMillis, _) = stationPollSplit(timeoutMillis)
        manager.isWifiEnabled = enabled
        if (awaitStation(enabled, start + nativeMillis, stillCurrent)) return true
        if (!stillCurrent()) return false
        if (RootShellBridge.isAvailable()) {
            val command = if (enabled) "/system/bin/svc wifi enable" else "/system/bin/svc wifi disable"
            DiagLog.i(TAG, "Native station ${if (enabled) "enable" else "disable"} unconfirmed, trying root")
            RootShellBridge.execute(command)
        }
        if (!stillCurrent()) return false
        return awaitStation(enabled, deadline, stillCurrent)
    }

    private fun enableAccessPointUntil(timeoutMillis: Long, stillCurrent: () -> Boolean): HotspotStartResult {
        val manager = wifi ?: return HotspotStartResult.NO_WIFI_SERVICE
        val start = SystemClock.elapsedRealtime()
        val native = setApEnabled?.let { enableAccessPoint(it, manager) }
        val plan = apWaitPlan(nativeAccepted = native == true, timeoutMillis = timeoutMillis)
        val deadline = start + plan.waitMillis
        if (plan.escalateToRoot && stillCurrent() && RootShellBridge.isAvailable()) {
            val path = appContext.packageCodePath
            if (RootShellBridge.shellSafeApkPath(path)) {
                DiagLog.i(TAG, "Native hotspot enable was not accepted, trying root")
                RootShellBridge.execute(RootShellBridge.hotspotCommand(path, "enable"))
            } else {
                DiagLog.w(TAG, "Hotspot apk path is not safe to pass to a root shell")
            }
        }
        if (awaitPhase(ApPhase.ON, deadline, stillCurrent)) return HotspotStartResult.READY
        if (!stillCurrent()) return HotspotStartResult.TIMED_OUT
        return when {
            isHotspotFullyOn() -> HotspotStartResult.READY
            native == null -> HotspotStartResult.PERMISSION_REQUIRED
            else -> HotspotStartResult.TIMED_OUT
        }
    }

    /**
     * Passes a null configuration on purpose: it keeps whatever the car already has, where building
     * one would rewrite the head unit's own hotspot settings behind the driver's back.
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

    private fun deniedOrFailed(error: Exception): Boolean? {
        val denied = deniedByFirmware(error)
        DiagLog.w(
            TAG,
            if (denied) "the firmware refused the hotspot permission"
            else "the hotspot API rejected the call",
            error,
        )
        return if (denied) null else false
    }

    private fun deniedByFirmware(error: Exception): Boolean =
        error is SecurityException || error.cause is SecurityException

    private fun readApPhase(): ApPhase {
        val manager = wifi ?: return ApPhase.UNKNOWN
        val state = runCatching {
            WifiManager::class.java.getMethod("getWifiApState").invoke(manager) as Int
        }.getOrNull()
        val legacy = if (state != null) {
            null
        } else {
            runCatching {
                WifiManager::class.java.getMethod("isWifiApEnabled").invoke(manager) as Boolean
            }.getOrNull()
        }
        return classifyApPhase(state, legacy)
    }

    private fun awaitPhase(wanted: ApPhase, deadline: Long, stillCurrent: () -> Boolean): Boolean {
        while (stillCurrent()) {
            if (readApPhase() == wanted) return true
            val now = SystemClock.elapsedRealtime()
            if (now >= deadline) return false
            SystemClock.sleep(min(STATE_POLL_MILLIS, deadline - now))
        }
        return false
    }

    private fun awaitStation(expected: Boolean, deadline: Long, stillCurrent: () -> Boolean): Boolean {
        while (stillCurrent()) {
            if (isStationEnabled() == expected) return true
            val now = SystemClock.elapsedRealtime()
            if (now >= deadline) return false
            SystemClock.sleep(min(STATE_POLL_MILLIS, deadline - now))
        }
        return false
    }

    private companion object {
        const val TAG = "DiPlay-CarHotspot"
        const val DEFAULT_OP_TIMEOUT_MILLIS = 2000L
        const val STATION_OP_TIMEOUT_MILLIS = 2000L
        const val STATE_POLL_MILLIS = 100L
    }
}
