package com.shilapi.xcertplay.instrument.fly

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FlyOutputHandoffTest {
    private val calls = mutableListOf<String>()
    private var center: Surface? = null

    @Test fun beginDetachesBeforeTheMirrorExists() {
        val handoff = handoff(detachOk = true, attachOk = true)
        handoff.begin(newCenter(), surface("cluster"), onReady = { calls += "ready" }, onFailed = { calls += "failed" })
        assertEquals(listOf("detach", "open", "attach", "ready"), calls)
    }

    @Test fun aFailedAttachRestoresTheCenterAndDoesNotStayOpen() {
        val handoff = handoff(detachOk = true, attachOk = false)
        handoff.begin(newCenter(), surface("cluster"), onReady = { calls += "ready" }, onFailed = { calls += "failed" })
        assertEquals(listOf("detach", "open", "attach", "close", "restore", "failed"), calls)
    }

    @Test fun aBrokenMirrorRestoresTheCenter() {
        val handoff = handoff(detachOk = true, attachOk = true, mirrorBreaks = true)
        handoff.begin(newCenter(), surface("cluster"), onReady = { calls += "ready" }, onFailed = { calls += "failed" })
        assertEquals(listOf("detach", "open", "restore", "failed"), calls)
    }

    @Test fun endWhileShowingRestoresThenCloses() {
        val handoff = handoff(detachOk = true, attachOk = true)
        handoff.begin(newCenter(), surface("cluster"), onReady = {}, onFailed = {})
        calls.clear()
        handoff.end { calls += "done" }
        assertEquals(listOf("restore", "close", "done"), calls)
    }

    @Test fun endDuringDetachRestoresTheCenterAndDropsTheLateCallback() {
        var deliverDetach: ((Boolean) -> Unit)? = null
        val handoff = FlyOutputHandoff(
            replaceOutput = { surface, applied ->
                val kind = when {
                    surface == null -> "detach"
                    surface === center -> "restore"
                    else -> "attach"
                }
                calls += kind
                if (kind == "detach") deliverDetach = applied else applied(true)
            },
            openMirror = { _, _, _, _ -> calls += "open" },
            closeMirror = { calls += "close" },
        )
        handoff.begin(newCenter(), surface("cluster"), onReady = { calls += "ready" }, onFailed = { calls += "failed" })
        handoff.end { calls += "done" }
        deliverDetach?.invoke(true)
        assertEquals(listOf("detach", "restore", "close", "done"), calls)
    }

    private fun newCenter(): Surface = surface("center").also { center = it }

    private fun surface(name: String) = Surface(android.graphics.SurfaceTexture(name.hashCode()))

    private fun handoff(detachOk: Boolean, attachOk: Boolean, mirrorBreaks: Boolean = false) =
        FlyOutputHandoff(
            replaceOutput = { surface, applied ->
                val kind = when {
                    surface == null -> "detach"
                    surface === center -> "restore"
                    else -> "attach"
                }
                calls += kind
                applied(if (kind == "detach") detachOk else if (kind == "restore") true else attachOk)
            },
            openMirror = { _, _, onTexture, onBroken ->
                calls += "open"
                if (mirrorBreaks) onBroken() else onTexture(surface("texture"))
            },
            closeMirror = { calls += "close" },
        )
}
