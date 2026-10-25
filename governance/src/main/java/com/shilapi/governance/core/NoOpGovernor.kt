package com.shilapi.governance.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fallback implementation used when running on non-supported head units or mobile test devices.
 */
object NoOpGovernor : IVehicleGovernor {
    override val isSupported: Boolean = false
    override val stateFlow: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

    override suspend fun applySuppression(reason: String): Boolean = false
    override suspend fun applyRestoration(reason: String): Boolean = true
    override suspend fun ensureSelfHealed(reason: String): Boolean = true
    override suspend fun checkNatpAlive(): Boolean? = null
}
