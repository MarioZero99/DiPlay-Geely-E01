package com.shilapi.governance

import android.app.Application
import com.shilapi.governance.carlife.CarLifeGovernor
import com.shilapi.governance.core.CommandQueue
import com.shilapi.governance.core.GovernorFactory
import com.shilapi.governance.core.IVehicleGovernor
import com.shilapi.governance.core.NoOpGovernor
import com.shilapi.governance.store.GovernancePersistence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Public facade for vehicle process governance.
 *
 * This singleton is the single point of entry for the host app.
 * It encapsulates hardware detection, persistence, coroutine execution,
 * and build-time gating.
 */
object VehicleGovernance {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val installed = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    private var governor: IVehicleGovernor = NoOpGovernor

    @Volatile
    private var persistence: GovernancePersistence? = null

    /**
     * CarLife's own governor, kept apart from [governor].
     *
     * [governor] is the voice service, gated on [isSupported] and put back on exit; CarLife is a pair
     * of one-off actions the driver runs, offered even on a head unit the voice governor does not
     * support, and holding until the driver restores it. Sharing one slot would make either build gate
     * silence it.
     */
    @Volatile
    private var carLife: CarLifeGovernor? = null

    /**
     * Serialises CarLife's own commands.
     *
     * Not shared with the voice governor: that one builds its own queue inside `E01Mtk6735Governor`.
     * What actually keeps two binder transactions apart is the lock in `E01PrivilegedBridge.execute`.
     */
    private val commandQueue = CommandQueue()

    /**
     * Optional listener to bridge governance log events to the app's diagnostic logger.
     */
    var diagnosticLogger: ((tag: String, message: String) -> Unit)? = null

    fun log(tag: String, message: String) {
        android.util.Log.i(tag, message)
        diagnosticLogger?.invoke(tag, message)
    }

    val isSupported: Boolean get() = governor.isSupported
    val isSuppressedFlow: StateFlow<Boolean> get() = governor.stateFlow

    /**
     * Probes whether the natp process is currently running.
     */
    suspend fun checkNatpAlive(): Boolean? = governor.checkNatpAlive()

    /**
     * Synchronously probes whether the natp process is currently running with a brief timeout.
     * Falls back to the latest cached alive state if probe times out to avoid blocking threads.
     */
    fun checkNatpAliveSync(timeoutMs: Long = 600L): Boolean? {
        if (!governor.isSupported) return null
        val cached = governor.cachedNatpAlive()
        return runCatching {
            runBlocking {
                withTimeoutOrNull(timeoutMs) {
                    governor.checkNatpAlive()
                }
            }
        }.getOrNull() ?: cached
    }

    /**
     * Initializes the governance module at application startup.
     *
     * @param application Host application context.
     * @param isExperimentalAllowed Whether experimental features are enabled for this build.
     *                              Defaults to true as governance is graduated to standard release.
     * @param customGovernor Optional custom governor implementation (primarily for unit testing).
     */
    fun install(
        application: Application,
        isExperimentalAllowed: Boolean = true,
        customGovernor: IVehicleGovernor? = null,
    ) {
        if (!installed.compareAndSet(false, true)) {
            log("VehicleGovernance", "Already installed, ignoring duplicate install call")
            return
        }
        val store = GovernancePersistence(application)
        persistence = store

        governor = when {
            !isExperimentalAllowed -> {
                NoOpGovernor.also {
                    // Physical fuse: ensure factory normal state on release builds
                    scope.launch { it.ensureSelfHealed("release-gated") }
                }
            }
            customGovernor != null -> customGovernor
            else -> GovernorFactory.create(application)
        }

        // CarLife is not part of the voice/experimental gate: the USB port it takes is the port
        // CarPlay needs, so the controls are offered even where the voice governor is fenced off.
        carLife = CarLifeGovernor(application, commandQueue)

        scope.launch {
            if (isExperimentalAllowed && governor.isSupported && store.isSuppressionConfigured()) {
                governor.applySuppression("startup-auto-suppress")
            } else {
                governor.ensureSelfHealed("startup-auto-heal")
            }
        }

        // Nothing to replay for CarLife. It is not a loan that has to be given back or re-taken at
        // startup: disabling its components writes to the package state and stays written across a
        // reboot. Re-applying on every start would be a second mechanism for something that already
        // holds, and would quietly undo the driver's "restore" the next time the app launched.
    }

    /**
     * Checks whether native voice suppression is configured on in persistent storage.
     */
    fun isSuppressionConfigured(): Boolean = persistence?.isSuppressionConfigured() ?: false

    /**
     * Reconciles current running state with persisted user configuration if needed.
     */
    fun ensureConfiguredState() {
        val store = persistence ?: return
        if (!governor.isSupported) return
        val configured = store.isSuppressionConfigured()
        if (configured != governor.stateFlow.value) {
            scope.launch {
                if (configured) {
                    governor.applySuppression("recheck-configured-suppress")
                } else {
                    governor.applyRestoration("recheck-configured-restore")
                }
            }
        }
    }

    /**
     * Enables or disables voice service suppression.
     */
    fun setSuppressionEnabled(enabled: Boolean, onComplete: ((Boolean) -> Unit)? = null) {
        persistence?.setSuppressionConfigured(enabled)
        scope.launch {
            val ok = if (enabled) {
                governor.applySuppression("manual-switch-on")
            } else {
                governor.applyRestoration("manual-switch-off")
            }
            onComplete?.invoke(ok)
        }
    }

    /**
     * Whether Baidu CarLife is on this head unit at all.
     *
     * A plain package-manager read: cheap enough to call while laying out the settings page.
     */
    fun isCarLifeInstalled(): Boolean = carLife?.isInstalled() ?: false

    /**
     * Whether CarLife's USB receivers and its connect service are all off right now.
     *
     * Read from the live component state, and that read is the only source of truth on this page:
     * there is no saved switch beside it, because disabling the components writes to the package
     * state and nothing has to be re-applied afterwards.
     */
    fun isCarLifeSuppressed(): Boolean = carLife?.isSuppressed() ?: false

    /**
     * Disables CarLife's USB components, or puts them back. One action, done once.
     *
     * [onComplete] runs on the IO scope the work happened on; the caller hops back to the main thread
     * itself. It reports the state the car ended up in, which is what the buttons redraw from.
     *
     * An exception is reported as failure rather than left to escape. This runs in a
     * `SupervisorJob` scope, where an escaping throwable is swallowed and the callback never fires --
     * which on the settings page left both buttons disabled until the app restarted. The bridge
     * `require`s a 220-byte command, so a throw here is a real possibility, not a theoretical one.
     */
    fun setCarLifeSuppressionEnabled(enabled: Boolean, onComplete: ((Boolean) -> Unit)? = null) {
        val governor = carLife ?: run {
            onComplete?.invoke(false)
            return
        }
        scope.launch {
            val ok = runCatching {
                if (enabled) governor.applySuppression() else governor.applyRestoration()
            }.getOrElse { error ->
                log("VehicleGovernance", "CarLife ${if (enabled) "disable" else "restore"} failed: $error")
                false
            }
            onComplete?.invoke(ok)
        }
    }

    /**
     * Called when the app is terminating (before process death).
     * Blocks synchronously with a timeout to guarantee restoration before process death.
     *
     * Only the voice service is put back. CarLife is not: its components stay however the driver left
     * them, which is the point of the two buttons -- a restore is something the driver asked for, not
     * something the app does behind their back when it closes.
     */
    fun onAppExit(timeoutMs: Long = 1500L, onComplete: (() -> Unit)? = null) {
        if (!governor.isSupported) {
            onComplete?.invoke()
            return
        }
        try {
            runBlocking {
                withTimeoutOrNull(timeoutMs) {
                    governor.applyRestoration("app-exit")
                }
            }
        } catch (_: Throwable) {
        } finally {
            onComplete?.invoke()
        }
    }

    internal fun resetForTesting() {
        installed.set(false)
        governor = NoOpGovernor
        persistence = null
        carLife = null
    }
}
