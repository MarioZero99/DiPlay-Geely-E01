package com.shilapi.governance

import android.app.Application
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

        scope.launch {
            if (isExperimentalAllowed && governor.isSupported && store.isSuppressionConfigured()) {
                governor.applySuppression("startup-auto-suppress")
            } else {
                governor.ensureSelfHealed("startup-auto-heal")
            }
        }
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
     * Called when the app is terminating (before process death).
     * Blocks synchronously with a timeout to guarantee restoration before process death.
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
    }
}
