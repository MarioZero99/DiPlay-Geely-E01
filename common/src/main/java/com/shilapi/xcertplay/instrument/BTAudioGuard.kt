package com.shilapi.xcertplay.instrument

import android.content.Context
import android.provider.Settings
import com.shilapi.xcertplay.DiagLog

internal object BTAudioGuard {
    private const val TAG = "BTAudioGuard"
    const val KEY_BT_AUDIO = "BTAudio"
    const val STATE_CONNECTED = "Connected"
    const val STATE_DISCONNECTED = "Disconnected"

    internal var accessorOverrideForTest: SystemSettingsAccessor? = null

    internal interface SystemSettingsAccessor {
        fun getString(context: Context, key: String): String?
        fun putString(context: Context, key: String, value: String): Boolean
        fun executeShell(command: String): Boolean
    }

    private val defaultAccessor = object : SystemSettingsAccessor {
        override fun getString(context: Context, key: String): String? {
            return runCatching {
                Settings.System.getString(context.contentResolver, key)
            }.getOrNull()
        }

        override fun putString(context: Context, key: String, value: String): Boolean {
            return runCatching {
                Settings.System.putString(context.contentResolver, key, value)
            }.getOrDefault(false)
        }

        override fun executeShell(command: String): Boolean {
            val bridge = com.shilapi.xcertplay.system.RootShellBridge
            if (!bridge.isAvailable()) return false
            return bridge.execute(command)
        }
    }

    /**
     * 检查并确保 BTAudio 为 Connected 状态。
     * 读取耗时通常 < 0.1ms。若已被车机后台改回 Disconnected，则立即纠正。
     *
     * @return true 若当前已是 Connected 或成功纠正为 Connected
     */
    fun ensureConnected(context: Context): Boolean {
        val accessor = accessorOverrideForTest ?: defaultAccessor
        val before = accessor.getString(context, KEY_BT_AUDIO)
        if (STATE_CONNECTED == before) {
            DiagLog.i(TAG, "BTAudio before='Connected' after='Connected' write=none")
            return true
        }

        val putOk = accessor.putString(context, KEY_BT_AUDIO, STATE_CONNECTED)
        if (putOk) {
            val after = accessor.getString(context, KEY_BT_AUDIO)
            DiagLog.i(TAG, "BTAudio before='$before' after='$after' write=settings")
            return true
        }

        val shellOk = accessor.executeShell("settings put system $KEY_BT_AUDIO $STATE_CONNECTED")
        val after = accessor.getString(context, KEY_BT_AUDIO)
        DiagLog.i(TAG, "BTAudio before='$before' after='$after' write=shell shellOk=$shellOk")
        return shellOk
    }
}
