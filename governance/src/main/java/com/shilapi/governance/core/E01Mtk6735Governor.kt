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
    }

    override val isSupported: Boolean = true

    private val _stateFlow = MutableStateFlow(false)
    override val stateFlow: StateFlow<Boolean> = _stateFlow.asStateFlow()

    override suspend fun applySuppression(reason: String): Boolean = queue.execute {
        Log.i(TAG, "Executing suppression [$reason]")
        val result = E01PrivilegedBridge.execute(CMD_SUPPRESS)
        val success = result is E01PrivilegedBridge.Result.Success
        if (success) {
            _stateFlow.value = true
        } else {
            Log.w(TAG, "Suppression failed [$reason]: $result")
        }
        success
    }

    override suspend fun applyRestoration(reason: String): Boolean = queue.execute {
        Log.i(TAG, "Executing restoration [$reason]")
        val result = E01PrivilegedBridge.execute(CMD_RESTORE)
        val success = result is E01PrivilegedBridge.Result.Success
        if (success) {
            _stateFlow.value = false
        } else {
            Log.w(TAG, "Restoration failed [$reason]: $result")
        }
        success
    }

    override suspend fun ensureSelfHealed(reason: String): Boolean = queue.execute {
        Log.i(TAG, "Executing self-healing check [$reason]")
        val result = E01PrivilegedBridge.execute(CMD_SELF_HEAL)
        val success = result is E01PrivilegedBridge.Result.Success
        if (success) {
            _stateFlow.value = false
        }
        success
    }
}
