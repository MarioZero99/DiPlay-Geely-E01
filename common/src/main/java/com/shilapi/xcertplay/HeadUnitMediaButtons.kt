package com.shilapi.xcertplay

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.view.KeyEvent

/**
 * The head unit's media-button slot.
 *
 * On this Android 5.1 stack, [android.media.session.MediaSession.setActive] going false does not
 * take DiPlay off the wheel. The session's media-button receiver stays the fallback, and the next
 * Bluetooth track change — when the car's own player flickers inactive — delivers the wheel there.
 * That registration lives in system_server, so reopening DiPlay or restarting the Bluetooth daemon
 * does not clear it. The manifest component has to be disabled and the legacy receiver unregistered
 * while the process is still alive to answer.
 */
object HeadUnitMediaButtons {
    private const val TAG = "DiPlay-MediaKeys"
    private val returnedKeys = ArrayDeque<String>()

    /** Drop whatever this install left in the head unit's media-button slot. */
    fun release(context: Context) {
        val app = context.applicationContext
        unregister(app)
        setEnabled(app, false)
    }

    /** Accept wheel keys again. Only a live CarPlay session may call this. */
    internal fun claim(context: Context) {
        val app = context.applicationContext
        setEnabled(app, true)
        register(app)
    }

    /**
     * Hand an exclusive delivery back to the head unit.
     *
     * A press that arrived as a direct [PendingIntent] is not an ordered broadcast: declining it
     * does not continue on to the car's player. The same event can be delivered again once the
     * slot is clear, and a second pass of the same press must not return it again.
     */
    internal fun returnDeclinedKey(context: Context, event: KeyEvent) {
        val token = "${event.downTime}:${event.keyCode}:${event.action}:${event.repeatCount}"
        synchronized(returnedKeys) {
            if (token in returnedKeys) return
            returnedKeys.addLast(token)
            while (returnedKeys.size > 32) returnedKeys.removeFirst()
        }
        release(context)
        val audio = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return
        runCatching { audio.dispatchMediaKeyEvent(event) }
            .onFailure { DiagLog.w(TAG, "could not return steering key ${event.keyCode} to the head unit", it) }
        DiagLog.i(TAG, "steering key returned to head unit keyCode=${event.keyCode}")
    }

    private fun component(context: Context) =
        ComponentName(context, CarPlayMediaButtonReceiver::class.java)

    @Suppress("DEPRECATION")
    private fun register(context: Context) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching { audio.registerMediaButtonEventReceiver(component(context)) }
            .onFailure { DiagLog.w(TAG, "register media button receiver failed", it) }
    }

    @Suppress("DEPRECATION")
    private fun unregister(context: Context) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val name = component(context)
        runCatching { audio.unregisterMediaButtonEventReceiver(name) }
        val pending = PendingIntent.getBroadcast(
            context,
            0,
            Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(name),
            PendingIntent.FLAG_UPDATE_CURRENT,
        )
        runCatching { audio.unregisterMediaButtonEventReceiver(pending) }
    }

    private fun setEnabled(context: Context, enabled: Boolean) {
        val pm = context.packageManager
        val name = component(context)
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        if (pm.getComponentEnabledSetting(name) == state) return
        runCatching {
            pm.setComponentEnabledSetting(name, state, PackageManager.DONT_KILL_APP)
        }.onFailure {
            DiagLog.w(TAG, "media button receiver enabled=$enabled failed", it)
            return
        }
        DiagLog.i(TAG, "media button receiver enabled=$enabled")
    }
}

/**
 * What a delivered wheel press should do once DiPlay has decided whether the press is its own.
 *
 * [abort] is only meaningful for an ordered broadcast. An exclusive delivery has nobody after us,
 * so a press we do not own has to release the slot and be sent again.
 */
internal object WheelKeyDelivery {
    data class Decision(val abort: Boolean, val releaseTarget: Boolean, val redispatch: Boolean)

    fun decide(claimed: Boolean, wheelHeld: Boolean, ordered: Boolean): Decision = when {
        claimed -> Decision(abort = ordered, releaseTarget = false, redispatch = false)
        wheelHeld -> Decision(abort = false, releaseTarget = false, redispatch = false)
        else -> Decision(abort = false, releaseTarget = true, redispatch = !ordered)
    }
}
