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
     */
    fun checkNatpAliveSync(timeoutMs: Long = 1000L): Boolean? {
        if (!governor.isSupported) return null
        return runCatching {
            runBlocking {
                withTimeoutOrNull(timeoutMs) {
                    governor.checkNatpAlive()
                }
            }
        }.getOrNull()
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
        governor = NoOpGovernor
        persistence = null
    }
}
