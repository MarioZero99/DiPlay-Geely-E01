package com.shilapi.governance.core

import kotlinx.coroutines.flow.StateFlow

/**
 * Common SPI interface for vehicle hardware & process governor implementations.
 */
interface IVehicleGovernor {
    /**
     * Whether the current vehicle platform supports process governance.
     */
    val isSupported: Boolean

    /**
     * Flow tracking whether suppression is currently active.
     */
    val stateFlow: StateFlow<Boolean>

    /**
     * Suppresses background voice services.
     */
    suspend fun applySuppression(reason: String): Boolean

    /**
     * Restores background voice services.
     */
    suspend fun applyRestoration(reason: String): Boolean

    /**
     * Ensures native voice services are in factory normal state.
     */
    suspend fun ensureSelfHealed(reason: String): Boolean
}
