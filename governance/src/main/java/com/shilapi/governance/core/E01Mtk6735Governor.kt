package com.shilapi.governance.core

import android.content.Context
import android.util.Log
import com.shilapi.governance.platform.E01PrivilegedBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Geely E01 (MT6735) specific governor implementation.
 *
 * Uses memory-only single-line commands via ExtraUtilsService (uid 0)
 * to suppress or restore `natp` and `com.ecarx.xiaoka`.
 */
class E01Mtk6735Governor(
    private val context: Context,
    private val queue: CommandQueue = CommandQueue(),
) : IVehicleGovernor {

    companion object {
        private const val TAG = "E01Governor"

        // 68 bytes raw, 114 bytes wrapped <= 220 bytes budget
        const val CMD_SUPPRESS =
            "setprop ctl.stop run_natp; pm disable-user --user 0 com.ecarx.xiaoka"

        // 54 bytes raw, 100 bytes wrapped <= 220 bytes budget
        const val CMD_RESTORE =
            "pm enable com.ecarx.xiaoka; setprop ctl.start run_natp"

        // 26 bytes raw, 72 bytes wrapped <= 220 bytes budget
        const val CMD_SELF_HEAL =
            "pm enable com.ecarx.xiaoka"

        // 25 bytes raw, 68 bytes wrapped <= 220 bytes budget
        const val CMD_CHECK_NATP =
            "getprop init.svc.run_natp"

        fun parseNatpAlive(output: String): Boolean? {
            val trimmed = output.trim()
            if (trimmed.isEmpty()) return null
            val lower = trimmed.lowercase()
            return when {
                lower == "running" -> true
                lower == "stopping" || lower == "stopped" -> false
                lower.contains("running") && !lower.contains("stopping") && !lower.contains("stopped") -> true
                lower.contains("stopping") || lower.contains("stopped") -> false
                trimmed.lines().any { it.contains("natp") } -> true
                trimmed.matches(Regex("^[0-9\\s]+$")) -> true
                else -> null
            }
        }
    }

    override val isSupported: Boolean = true

    private val _stateFlow = MutableStateFlow(false)
    override val stateFlow: StateFlow<Boolean> = _stateFlow.asStateFlow()

    override suspend fun checkNatpAlive(): Boolean? = queue.execute {
        probeNatpInternal()
    }

    private fun probeNatpInternal(): Boolean? {
        val result = E01PrivilegedBridge.execute(CMD_CHECK_NATP)
        return when (result) {
            is E01PrivilegedBridge.Result.Success -> parseNatpAlive(result.output)
            // Non-zero exit code (e.g. 127 command not found) means probe failed, NOT that process is dead!
            else -> null
        }
    }

    override suspend fun applySuppression(reason: String): Boolean = queue.execute {
        com.shilapi.governance.VehicleGovernance.log(TAG, "Executing suppression [$reason]")
        val result = E01PrivilegedBridge.execute(CMD_SUPPRESS)
        val success = result is E01PrivilegedBridge.Result.Success
        if (success) {
            _stateFlow.value = true
        } else {
            com.shilapi.governance.VehicleGovernance.log(TAG, "Suppression failed [$reason]: $result")
        }
        var alive: Boolean? = null
        for (attempt in 0 until 4) {
            try {
                Thread.sleep(200L)
            } catch (_: InterruptedException) {}
            alive = probeNatpInternal()
            if (alive == false) break
        }
        com.shilapi.governance.VehicleGovernance.log(TAG, "Suppression completed [$reason] success=$success, natp alive=$alive")
        success
    }

    override suspend fun applyRestoration(reason: String): Boolean = queue.execute {
        com.shilapi.governance.VehicleGovernance.log(TAG, "Executing restoration [$reason]")
        val result = E01PrivilegedBridge.execute(CMD_RESTORE)
        val success = result is E01PrivilegedBridge.Result.Success
        if (success) {
            _stateFlow.value = false
        } else {
            com.shilapi.governance.VehicleGovernance.log(TAG, "Restoration failed [$reason]: $result")
        }
        val alive = if (reason == "app-exit") null else probeNatpInternal()
        com.shilapi.governance.VehicleGovernance.log(TAG, "Restoration completed [$reason] success=$success, natp alive=$alive")
        success
    }

    override suspend fun ensureSelfHealed(reason: String): Boolean = queue.execute {
        com.shilapi.governance.VehicleGovernance.log(TAG, "Executing self-healing check [$reason]")
        val result = E01PrivilegedBridge.execute(CMD_SELF_HEAL)
        val success = result is E01PrivilegedBridge.Result.Success
        if (success) {
            _stateFlow.value = false
        }
        val alive = probeNatpInternal()
        com.shilapi.governance.VehicleGovernance.log(TAG, "Self-healing completed [$reason] success=$success, natp alive=$alive")
        success
    }
}
