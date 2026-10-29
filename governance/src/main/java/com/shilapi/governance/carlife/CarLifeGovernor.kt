package com.shilapi.governance.carlife

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import com.shilapi.governance.VehicleGovernance
import com.shilapi.governance.core.CommandQueue
import com.shilapi.governance.platform.E01PrivilegedBridge

data class ComponentSpec(val className: String, val isService: Boolean = false)

/**
 * Keeps Baidu CarLife off the USB port CarPlay needs.
 *
 * CarLife is a system app on the E01 (`/system/app/SmartBox`, `com.baidu.carlifevehicle`). Two of its
 * receivers listen for `USB_DEVICE_ATTACHED`, so plugging the iPhone in starts CarLife before CarPlay
 * finishes connecting, and the connect service under them holds the port.
 *
 * Two one-off actions, not a switch: disabling writes the components' state into the package manager,
 * so it holds across a reboot by itself, and restoring is something the driver asks for. Nothing is
 * saved in this app and nothing is re-applied at startup.
 *
 * One `pm` call per component: the bridge carries a whole command in 220 bytes.
 *
 * [runCommand], [declared], and [processRunningProbe] are injected with production defaults.
 *
 * - The shell: a desk has no vendor service, so `pm disable` cannot be run. Without this the whole
 *   feature is untestable off the car.
 * - [declared]: Robolectric's package manager ignores the `flags` argument entirely, so a test built
 *   on it cannot tell whether `GET_DISABLED_COMPONENTS` was passed -- and forgetting that flag is the
 *   one bug that makes this feature report the opposite of what it did. One parameter is a smaller
 *   price than losing the only guard. Dispatches directly between receiver and service to eliminate
 *   spurious NameNotFoundException IPC overhead.
 * - [processRunningProbe]: In-memory process status check via ActivityManager.
 *
 * Reading the enabled state needs no seam: `getComponentEnabledSetting` is a plain call the shadow
 * implements faithfully.
 */
class CarLifeGovernor(
    private val context: Context,
    private val runCommand: (String) -> E01PrivilegedBridge.Result = E01PrivilegedBridge::execute,
    private val declared: (ComponentName, Int, Boolean) -> Boolean = { name, flags, isService ->
        try {
            if (isService) {
                context.packageManager.getServiceInfo(name, flags)
            } else {
                context.packageManager.getReceiverInfo(name, flags)
            }
            true
        } catch (_: Throwable) {
            false
        }
    },
    private val processRunningProbe: () -> Boolean = {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            am?.runningAppProcesses?.any {
                it.processName == PACKAGE_NAME || it.processName.startsWith("$PACKAGE_NAME:")
            } ?: false
        } catch (_: Throwable) {
            false
        }
    },
    private val queue: CommandQueue = CommandQueue(),
) {
    companion object {
        private const val TAG = "CarLifeGovernor"
        private const val DEFAULT_TIMEOUT_MS = 3000L

        const val PACKAGE_NAME = "com.baidu.carlifevehicle"

        const val RECEIVER_BROADCAST = "$PACKAGE_NAME.broadcast.BroadcastActionReceiver"
        const val RECEIVER_AOA = "$PACKAGE_NAME.connect.AOAAccessoryReceiver"
        const val SERVICE_CONNECT = "$PACKAGE_NAME.connect.ConnectService"

        /**
         * Both USB attach receivers and the service they start. The launcher activity, the
         * media-button receiver and the recording service are left alone, so opening CarLife by hand
         * and the car's media keys still work.
         */
        val TARGET_COMPONENTS = listOf(
            ComponentSpec(RECEIVER_BROADCAST, isService = false),
            ComponentSpec(RECEIVER_AOA, isService = false),
            ComponentSpec(SERVICE_CONNECT, isService = true),
        )

        val COMPONENTS = TARGET_COMPONENTS.map { it.className }

        const val FORCE_STOP_COMMAND = "am force-stop $PACKAGE_NAME"

        fun qualified(className: String) = "$PACKAGE_NAME/$className"

        fun disableCommand(className: String) = "pm disable ${qualified(className)}"

        fun enableCommand(className: String) = "pm enable ${qualified(className)}"
    }

    fun isInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE_NAME, 0)
        true
    } catch (_: Throwable) {
        false
    }

    /**
     * Checks if CarLife process is currently alive in memory.
     */
    fun isProcessRunning(): Boolean = processRunningProbe()

    /**
     * Whether every component this CarLife build carries is switched off.
     *
     * The flag matters. On API 22 a disabled component is invisible to `getReceiverInfo` unless
     * `GET_DISABLED_COMPONENTS` is passed, and the app-side wrapper turns that null into a thrown
     * `NameNotFoundException`. Without it a component that was just turned off reads as one the
     * package does not have -- so a successful disable would show as "still on" for ever.
     *
     * Presence is asked for separately because `getComponentEnabledSetting` answers `DEFAULT` both
     * for "nobody has touched it" and for "there is no such component".
     *
     * A name the build does not carry is skipped: `pm disable` exits non-zero for it, and demanding
     * all three would report "not off" for ever on such a build. If the build carries none of the
     * vulnerable components at all (e.g. customized stripped ROM), it is treated as inherently safe
     * (suppressed) rather than stuck unsuppressed forever.
     */
    fun isSuppressed(): Boolean {
        if (!isInstalled()) return false

        val declaredList = TARGET_COMPONENTS.filter { target ->
            val name = ComponentName(PACKAGE_NAME, target.className)
            declared(name, PackageManager.GET_DISABLED_COMPONENTS, target.isService)
        }
        if (declaredList.isEmpty()) {
            return true
        }

        return declaredList.all { target ->
            val name = ComponentName(PACKAGE_NAME, target.className)
            isOff(try {
                context.packageManager.getComponentEnabledSetting(name)
            } catch (_: Throwable) {
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            })
        }
    }

    private fun isOff(setting: Int) =
        setting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
            setting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
            setting == 4 // COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED

    /**
     * Stops CarLife first, then disables what would bring it back.
     *
     * The order matters. `pm disable` carries no `DONT_KILL_APP`, so it kills the package anyway --
     * but that frees the port on the *last* command, while a running CarLife that already holds it
     * keeps holding it in the meantime. Stopping it first frees the port before anything is
     * disallowed.
     *
     * [timeoutMs] is a deadline checked between commands, not a coroutine timeout: the bridge's
     * transaction is a blocking binder call that cancellation cannot interrupt.
     */
    suspend fun applySuppression(timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean = queue.execute {
        if (!isInstalled()) {
            VehicleGovernance.log(TAG, "CarLife is not installed, nothing to suppress")
            return@execute true
        }

        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        val stopped = runCommand(FORCE_STOP_COMMAND)
        if (stopped !is E01PrivilegedBridge.Result.Success) {
            VehicleGovernance.log(TAG, "Could not force-stop CarLife: $stopped")
        }

        eachCarried("Suppression", deadline) { className -> disableCommand(className) }

        // The state the commands landed in, not the fact that they were sent.
        val off = isSuppressed()
        VehicleGovernance.log(TAG, "Suppression finished, off=$off, processRunning=${isProcessRunning()}")
        off
    }

    suspend fun applyRestoration(timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean = queue.execute {
        if (!isInstalled()) {
            VehicleGovernance.log(TAG, "CarLife is not installed, nothing to restore")
            return@execute true
        }

        eachCarried("Restoration", SystemClock.elapsedRealtime() + timeoutMs) { className -> enableCommand(className) }

        val back = !isSuppressed()
        VehicleGovernance.log(TAG, "Restoration finished, back=$back")
        back
    }

    /** Sends one command per component the package carries, carrying on past a refusal. */
    private fun eachCarried(what: String, deadline: Long, command: (String) -> String) {
        for (target in TARGET_COMPONENTS) {
            val className = target.className
            if (!declared(ComponentName(PACKAGE_NAME, className), PackageManager.GET_DISABLED_COMPONENTS, target.isService)) {
                VehicleGovernance.log(TAG, "This CarLife build has no $className, skipping it")
                continue
            }
            if (SystemClock.elapsedRealtime() > deadline) {
                VehicleGovernance.log(TAG, "$what ran out of time before $className")
                return
            }
            val result = runCommand(command(className))
            if (result !is E01PrivilegedBridge.Result.Success) {
                // Carry on: one refusal must not leave the others enabled.
                VehicleGovernance.log(TAG, "Could not run ${command(className)}: $result")
            }
        }
    }
}
