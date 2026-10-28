package com.shilapi.governance.carlife

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import com.shilapi.governance.VehicleGovernance
import com.shilapi.governance.core.CommandQueue
import com.shilapi.governance.platform.E01PrivilegedBridge

/** One CarLife component: does the package declare it, and is it switched off. */
data class CarLifeComponentState(val exists: Boolean, val disabled: Boolean)

/**
 * What this feature needs from the device: three reads and one command.
 *
 * One seam, not a layer. It exists so "off" can be told apart from "gone" and so a head unit with no
 * vendor shell can be exercised on a desk; production uses [AndroidCarLifeSystem].
 */
interface CarLifeSystem {
    fun installed(): Boolean
    fun components(): List<CarLifeComponentState>
    fun run(command: String): E01PrivilegedBridge.Result
}

class AndroidCarLifeSystem(
    private val context: Context,
    /**
     * Whether the package declares a class. Takes the caller's flags because the flag is the whole
     * point: see [components]. Injectable so that flag is assertable.
     */
    private val declared: (ComponentName, Int) -> Boolean = { name, flags ->
        try {
            context.packageManager.getReceiverInfo(name, flags)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            try {
                context.packageManager.getServiceInfo(name, flags)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            } catch (_: Throwable) {
                false
            }
        } catch (_: Throwable) {
            false
        }
    },
) : CarLifeSystem {

    override fun installed(): Boolean = try {
        context.packageManager.getPackageInfo(CarLifeGovernor.PACKAGE_NAME, 0)
        true
    } catch (_: Throwable) {
        false
    }

    /**
     * `GET_DISABLED_COMPONENTS` is required, not cosmetic.
     *
     * On API 22 a disabled component is invisible to `getReceiverInfo`/`getServiceInfo` unless that
     * flag is passed; `ApplicationPackageManager` turns the null into `NameNotFoundException`. Without
     * it, every component this class just turned off reads back as "the package does not have it" --
     * so a successful disable shows as "still on" for ever.
     */
    override fun components(): List<CarLifeComponentState> =
        CarLifeGovernor.COMPONENTS.map { className ->
            val name = ComponentName(CarLifeGovernor.PACKAGE_NAME, className)
            val setting = try {
                context.packageManager.getComponentEnabledSetting(name)
            } catch (_: Throwable) {
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            }
            CarLifeComponentState(
                exists = declared(name, PackageManager.GET_DISABLED_COMPONENTS),
                disabled = setting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                    setting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            )
        }

    override fun run(command: String): E01PrivilegedBridge.Result = E01PrivilegedBridge.execute(command)
}

/**
 * Keeps Baidu CarLife off the USB port CarPlay needs.
 *
 * CarLife is a system app on the E01 (`/system/app/SmartBox`, `com.baidu.carlifevehicle`). Two
 * receivers listen for `USB_DEVICE_ATTACHED`, so plugging the iPhone in starts CarLife before CarPlay
 * finishes connecting, and the connect service under them holds the port.
 *
 * Two one-off actions, not a switch. Disabling writes the components' state into the package manager,
 * so it holds across a reboot by itself; restoring is something the driver asks for. Nothing is saved
 * in this app and nothing is re-applied at startup.
 *
 * One `pm` call per component: the bridge carries a whole command in 220 bytes.
 */
class CarLifeGovernor(
    private val system: CarLifeSystem,
    private val queue: CommandQueue = CommandQueue(),
) {
    constructor(context: Context, queue: CommandQueue = CommandQueue()) : this(AndroidCarLifeSystem(context), queue)

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
        val COMPONENTS = listOf(RECEIVER_BROADCAST, RECEIVER_AOA, SERVICE_CONNECT)

        const val FORCE_STOP_COMMAND = "am force-stop $PACKAGE_NAME"

        fun qualified(className: String) = "$PACKAGE_NAME/$className"

        fun disableCommand(className: String) = "pm disable ${qualified(className)}"

        fun enableCommand(className: String) = "pm enable ${qualified(className)}"

        /**
         * Off means every component the package actually has is off.
         *
         * A name the build does not carry is skipped: `pm disable` exits non-zero for it, and a
         * verdict demanding all three would say "not off" for ever on such a build. A component that
         * exists and is still on makes the verdict false even when the others are off -- otherwise a
         * running CarLife holding the port would read as handled.
         */
        fun isOff(states: List<CarLifeComponentState>): Boolean {
            val present = states.filter { it.exists }
            return present.isNotEmpty() && present.all { it.disabled }
        }
    }

    fun isInstalled(): Boolean = system.installed()

    /** Live state from the package manager; nothing here is cached or saved. */
    fun isSuppressed(): Boolean = isOff(system.components())

    /**
     * Stops CarLife first, then disables what would bring it back.
     *
     * The order matters. `pm disable` carries no `DONT_KILL_APP`, so it kills the package anyway --
     * but that would make the port fall free on the *last* command, while a running CarLife that
     * already holds it keeps holding it in the meantime. Stopping it first frees the port before
     * anything is disallowed.
     */
    suspend fun applySuppression(timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean = queue.execute {
        val present = system.components()
        if (present.none { it.exists }) {
            VehicleGovernance.log(TAG, "CarLife is not installed, nothing to suppress")
            return@execute true
        }

        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        // A deadline checked between commands, not a coroutine timeout: the bridge's transaction is a
        // blocking binder call that cancellation cannot interrupt.
        if (SystemClock.elapsedRealtime() <= deadline) {
            val stopped = system.run(FORCE_STOP_COMMAND)
            if (stopped !is E01PrivilegedBridge.Result.Success) {
                VehicleGovernance.log(TAG, "Could not force-stop CarLife: $stopped")
            }
        }

        COMPONENTS.forEachIndexed { index, className ->
            if (!present[index].exists) {
                VehicleGovernance.log(TAG, "This CarLife build has no $className, skipping it")
                return@forEachIndexed
            }
            if (SystemClock.elapsedRealtime() > deadline) {
                VehicleGovernance.log(TAG, "Suppression ran out of time before $className")
                return@forEachIndexed
            }
            val result = system.run(disableCommand(className))
            if (result !is E01PrivilegedBridge.Result.Success) {
                // Carry on: one refusal must not leave the others enabled.
                VehicleGovernance.log(TAG, "Could not disable $className: $result")
            }
        }

        // Read the state the commands actually landed in, not the fact that they were sent.
        val off = isSuppressed()
        VehicleGovernance.log(TAG, "Suppression finished, off=$off")
        off
    }

    suspend fun applyRestoration(timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean = queue.execute {
        val present = system.components()
        if (present.none { it.exists }) {
            VehicleGovernance.log(TAG, "CarLife is not installed, nothing to restore")
            return@execute true
        }

        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        COMPONENTS.forEachIndexed { index, className ->
            if (!present[index].exists) return@forEachIndexed
            if (SystemClock.elapsedRealtime() > deadline) {
                VehicleGovernance.log(TAG, "Restoration ran out of time before $className")
                return@forEachIndexed
            }
            val result = system.run(enableCommand(className))
            if (result !is E01PrivilegedBridge.Result.Success) {
                VehicleGovernance.log(TAG, "Could not enable $className: $result")
            }
        }

        val back = !isSuppressed()
        VehicleGovernance.log(TAG, "Restoration finished, back=$back")
        back
    }
}
