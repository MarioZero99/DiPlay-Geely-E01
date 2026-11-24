package com.shilapi.xcertplay.instrument.fly

import android.view.Surface

/**
 * Moves the centre decoder onto a texture and back.
 *
 * The decoder must release the centre Surface before EGL creates a window on it, and must be
 * pointed back at that Surface before the mirror is destroyed. The callbacks arrive on the thread
 * the caller used to deliver `replaceOutput`'s result.
 */
internal class FlyOutputHandoff(
    private val replaceOutput: (Surface?, (Boolean) -> Unit) -> Unit,
    private val openMirror: (Surface, Surface, (Surface) -> Unit, () -> Unit) -> Unit,
    private val closeMirror: () -> Unit,
    /** The detach and attach results, for the picture chain. Restores are not reported. */
    private val onStep: (String, Boolean) -> Unit = { _, _ -> },
) {
    private enum class Phase { IDLE, DETACHING, OPENING, ATTACHING, SHOWING, RESTORING }

    private var center: Surface? = null
    private var generation = 0
    private var phase = Phase.IDLE
    private val afterRestore = mutableListOf<() -> Unit>()

    fun begin(center: Surface, cluster: Surface, onReady: () -> Unit, onFailed: () -> Unit) {
        val ticket = ++generation
        this.center = center
        phase = Phase.DETACHING
        replaceOutput(null) { detached ->
            if (ticket != generation) return@replaceOutput
            onStep("detach", detached)
            if (!detached) {
                fail(ticket, onFailed)
                return@replaceOutput
            }
            phase = Phase.OPENING
            openMirror(center, cluster, { texture ->
                if (ticket != generation) return@openMirror
                phase = Phase.ATTACHING
                replaceOutput(texture) { attached ->
                    if (ticket != generation) return@replaceOutput
                    onStep("attach", attached)
                    if (!attached) {
                        closeMirror()
                        fail(ticket, onFailed)
                        return@replaceOutput
                    }
                    phase = Phase.SHOWING
                    onReady()
                }
            }, {
                if (ticket != generation) return@openMirror
                fail(ticket, onFailed)
            })
        }
    }

    fun end(onDone: () -> Unit) {
        if (phase == Phase.IDLE) {
            onDone()
            return
        }
        if (phase == Phase.RESTORING) {
            afterRestore += onDone
            return
        }
        val ticket = ++generation
        val back = center
        phase = Phase.RESTORING
        if (back == null) {
            closeMirror()
            finish(onDone)
            return
        }
        replaceOutput(back) {
            if (ticket != generation) return@replaceOutput
            closeMirror()
            finish(onDone)
        }
    }

    private fun fail(ticket: Int, onFailed: () -> Unit) {
        val back = center
        phase = Phase.RESTORING
        if (back == null) {
            finish(onFailed)
            return
        }
        replaceOutput(back) {
            if (ticket != generation) return@replaceOutput
            finish(onFailed)
        }
    }

    private fun finish(onDone: () -> Unit) {
        phase = Phase.IDLE
        center = null
        onDone()
        val pending = afterRestore.toList()
        afterRestore.clear()
        pending.forEach { it() }
    }
}
