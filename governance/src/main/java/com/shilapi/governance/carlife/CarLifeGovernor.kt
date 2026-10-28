package com.shilapi.governance.carlife

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import com.shilapi.governance.VehicleGovernance
import com.shilapi.governance.core.CommandQueue
import com.shilapi.governance.platform.E01PrivilegedBridge

/**
 * The one thing this class asks of the head unit's shell: run a command as uid 0.
 *
 * An injectable seam rather than the object itself. On a desk the vendor service is unavailable and
 * every command comes back `ServiceUnavailable`, so none of the decisions that matter here -- which
 * component to skip, whether to carry on after a refusal, that the force-stop goes last -- could be
 * exercised at all.
 */
interface PrivilegedShell {
    fun run(command: String): E01PrivilegedBridge.Result
}

/** What one CarLife component currently looks like: is it in the package, and is it off. */
data class CarLifeComponentState(val exists: Boolean, val disabled: Boolean)

/**
 * What CarLife's package currently looks like on this device.
 *
 * Split out for the same reason: presence and the on/off state of one component are the two inputs
 * this class turns on, and a test has to be able to set them without half-reimplementing
 * `PackageManager`.
 */
interface CarLifePackage {
    fun isInstalled(): Boolean

    /** One entry per [CarLifeGovernor.COMPONENTS_TO_SUPPRESS], in that order. */
    fun componentStates(): List<CarLifeComponentState>
}

/**
 * Keeps Baidu CarLife off the USB port CarPlay needs.
 *
 * CarLife is a system app on the E01 (`/system/app/SmartBox`, package `com.baidu.carlifevehicle`).
 * Two of its receivers listen for `USB_DEVICE_ATTACHED`, so plugging the iPhone in starts CarLife
 * before CarPlay can finish connecting, and the connect service underneath them is what ends up
 * holding the port. This turns those components off and puts them back on request.
 *
 * Unlike the voice suppression next door this is a standing choice: it is written to storage and
 * re-applied at every start, because a package-state reset turns the components back on.
 *
 * Each command is one `pm` call for one component. The bridge carries a whole command in a 220-byte
 * byte array, so joining three disables with semicolons is not available, and a line that silently
 * overflowed would read as a successful disable.
 */
class CarLifeGovernor(
    private val packageState: CarLifePackage,
    private val shell: PrivilegedShell,
    private val queue: CommandQueue = CommandQueue(),
) {

    constructor(context: Context, queue: CommandQueue = CommandQueue()) : this(
        packageState = AndroidCarLifePackage(context),
        shell = object : PrivilegedShell {
            override fun run(command: String) = E01PrivilegedBridge.execute(command)
        },
        queue = queue,
    )

    companion object {
        private const val TAG = "CarLifeGovernor"

        const val PACKAGE_NAME = "com.baidu.carlifevehicle"

        const val RECEIVER_BROADCAST = "$PACKAGE_NAME.broadcast.BroadcastActionReceiver"
        const val RECEIVER_AOA = "$PACKAGE_NAME.connect.AOAAccessoryReceiver"
        const val SERVICE_CONNECT = "$PACKAGE_NAME.connect.ConnectService"

        /**
         * What has to be off for CarLife to stop taking the port: both USB attach receivers and the
         * service they start. The launcher activity, the media-button receiver and the recording
         * service are left alone, so opening CarLife by hand and the car's media keys still work.
         *
         * Names are from CarLife's own `AndroidManifest.xml`. A build that carries only some of them
         * is handled by [suppressionVerdict] rather than by assuming all three are always there.
         */
        val COMPONENTS_TO_SUPPRESS = listOf(RECEIVER_BROADCAST, RECEIVER_AOA, SERVICE_CONNECT)

        val FORCE_STOP_COMMAND = "am force-stop $PACKAGE_NAME"

        /** A component as `pm` and `ComponentName` both want it: `package/class`. */
        fun qualified(className: String) = "$PACKAGE_NAME/$className"

        fun disableCommand(className: String) = "pm disable ${qualified(className)}"

        fun enableCommand(className: String) = "pm enable ${qualified(className)}"

        /**
         * Suppressed means every component this CarLife build actually has is off.
         *
         * Two cases make this more than "count the disabled ones":
         *
         * - A component the build does not carry is skipped, not counted as a failure. `pm disable`
         *   exits non-zero for an unknown class, and a verdict that demanded all three would report
         *   "not suppressed" forever on such a build -- the switch would look broken while the
         *   receivers it was really after were already off.
         * - A component that exists but is still enabled makes the verdict false even when the other
         *   two are off. Reading back only the first component would call a CarLife that is running
         *   and holding the port "suppressed".
         */
        fun suppressionVerdict(states: List<CarLifeComponentState>): Boolean {
            val present = states.filter { it.exists }
            return present.isNotEmpty() && present.all { it.disabled }
        }

        const val DEFAULT_TIMEOUT_MS = 3000L
    }

    fun isInstalled(): Boolean = packageState.isInstalled()

    /**
     * Reads the live component state. The same table `pm disable` writes, read without a shell round
     * trip: no binder call to the vendor service, so nothing here can block a redraw.
     */
    fun isSuppressed(): Boolean = suppressionVerdict(packageState.componentStates())

    /**
     * Disables every component the package carries, then stops CarLife so one that already holds the
     * port lets go.
     *
     * [timeoutMs] is a deadline checked between commands rather than a coroutine timeout around them:
     * the bridge's transaction is a blocking binder call that cancellation cannot interrupt, so a
     * `withTimeout` would return while a command was still running. Checking the clock stops the batch
     * at the next component boundary and reports the state that actually landed.
     */
    suspend fun applySuppression(timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean = queue.execute {
        val present = packageState.componentStates()
        if (present.none { it.exists }) {
            VehicleGovernance.log(TAG, "CarLife is not installed, nothing to suppress")
            return@execute true
        }

        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        COMPONENTS_TO_SUPPRESS.forEachIndexed { index, className ->
            if (!present[index].exists) {
                VehicleGovernance.log(TAG, "This CarLife build has no $className, skipping it")
                return@forEachIndexed
            }
            if (SystemClock.elapsedRealtime() > deadline) {
                VehicleGovernance.log(TAG, "Suppression ran out of time before $className")
                return@forEachIndexed
            }
            val result = shell.run(disableCommand(className))
            if (result !is E01PrivilegedBridge.Result.Success) {
                // Carry on: one component refusing must not leave the others enabled.
                VehicleGovernance.log(TAG, "Could not disable $className: $result")
            }
        }

        if (SystemClock.elapsedRealtime() <= deadline) {
            val stopped = shell.run(FORCE_STOP_COMMAND)
            if (stopped !is E01PrivilegedBridge.Result.Success) {
                VehicleGovernance.log(TAG, "Could not force-stop CarLife: $stopped")
            }
        }

        val suppressed = isSuppressed()
        VehicleGovernance.log(TAG, "Suppression finished, suppressed=$suppressed")
        suppressed
    }

    suspend fun applyRestoration(timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean = queue.execute {
        val present = packageState.componentStates()
        if (present.none { it.exists }) {
            VehicleGovernance.log(TAG, "CarLife is not installed, nothing to restore")
            return@execute true
        }

        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        COMPONENTS_TO_SUPPRESS.forEachIndexed { index, className ->
            if (!present[index].exists) return@forEachIndexed
            if (SystemClock.elapsedRealtime() > deadline) {
                VehicleGovernance.log(TAG, "Restoration ran out of time before $className")
                return@forEachIndexed
            }
            val result = shell.run(enableCommand(className))
            if (result !is E01PrivilegedBridge.Result.Success) {
                VehicleGovernance.log(TAG, "Could not enable $className: $result")
            }
        }

        val restored = !isSuppressed()
        VehicleGovernance.log(TAG, "Restoration finished, restored=$restored")
        restored
    }
}

/** The real package, read straight out of the package manager. */
class AndroidCarLifePackage(private val context: Context) : CarLifePackage {

    override fun isInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(CarLifeGovernor.PACKAGE_NAME, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: Throwable) {
        false
    }

    /**
     * Both halves of every component in one pass.
     *
     * Presence is asked for separately because [PackageManager.getComponentEnabledSetting] answers
     * `DEFAULT` both for "nobody has touched it" and for "there is no such component", and on this
     * screen those two mean opposite things.
     */
    override fun componentStates(): List<CarLifeComponentState> =
        CarLifeGovernor.COMPONENTS_TO_SUPPRESS.map { className ->
            val name = ComponentName(CarLifeGovernor.PACKAGE_NAME, className)
            val setting = try {
                context.packageManager.getComponentEnabledSetting(name)
            } catch (_: Throwable) {
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            }
            CarLifeComponentState(
                exists = exists(name),
                disabled = setting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                    setting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            )
        }

    private fun exists(name: ComponentName): Boolean = try {
        context.packageManager.getReceiverInfo(name, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        try {
            context.packageManager.getServiceInfo(name, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Throwable) {
            false
        }
    } catch (_: Throwable) {
        false
    }
}
