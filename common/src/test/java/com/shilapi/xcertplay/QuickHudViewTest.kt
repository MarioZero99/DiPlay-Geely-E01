package com.shilapi.xcertplay

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.ui.component.QuickHudView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The quick bar is the three-finger destination over a live picture, so its contract is small: it
 * opens, it offers the four controls, and the two destructive ones are not single-press accidents.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en")
class QuickHudViewTest {

    private var disconnects = 0
    private var nightToggles = 0
    private var exits = 0
    private var dismissals = 0
    private val pickedStreams = mutableListOf<Int>()

    private fun hud(night: Boolean = false, stream: Int = 10): QuickHudView =
        QuickHudView(
            RuntimeEnvironment.getApplication(),
            night = night,
            audioStream = stream,
            onDisconnect = { disconnects++ },
            onAudioSelected = { pickedStreams += it },
            onToggleNight = { nightToggles++ },
            onExit = { exits++ },
            onDismiss = { dismissals++ },
        )

    @Test
    fun opensOnDemandAndDismissesFromOutside() {
        val view = hud()
        assertEquals(View.GONE, view.visibility)

        view.open()
        assertEquals(View.VISIBLE, view.visibility)

        view.performClick()
        assertEquals(1, dismissals)

        view.dismissNow()
        assertEquals(View.GONE, view.visibility)
    }

    @Test
    fun audioControlOpensTheChannelStripAndKeepsTheChoice() {
        val view = hud(stream = 10)
        view.open()

        assertTrue(clickText(view, "Audio routing · 10"))
        assertTrue(clickText(view, "13"))

        assertEquals(listOf(13), pickedStreams)
        assertTrue(hasText(view, "Audio routing · 13"))
        assertFalse(hasText(view, "Audio routing · 10"))
    }

    @Test
    fun exitNeedsASecondPress() {
        val view = hud()
        view.open()

        assertTrue(clickText(view, "Close DiPlay"))
        assertEquals(0, exits)
        assertTrue("the first press has to say what the second one does", hasText(view, "EXIT APPLICATION"))

        assertTrue(clickText(view, "EXIT APPLICATION"))
        assertEquals(1, exits)
    }

    @Test
    fun nightControlFlipsTheLabelAndTheHostFlag() {
        val view = hud(night = false)
        view.open()
        assertTrue(hasText(view, "Day"))

        assertTrue(clickText(view, "Day"))
        assertEquals(1, nightToggles)
        assertTrue(hasText(view, "Night"))
    }

    @Test
    fun disconnectIsOnePress() {
        val view = hud()
        view.open()

        assertTrue(clickText(view, "Disconnect"))
        assertEquals(1, disconnects)
    }

    private fun clickText(root: View, text: String): Boolean {
        if (root is TextView && root.text?.toString() == text) return root.performClick()
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                if (clickText(root.getChildAt(index), text)) return true
            }
        }
        return false
    }

    private fun hasText(root: View, text: String): Boolean {
        if (root is TextView && root.text?.toString() == text) return true
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                if (hasText(root.getChildAt(index), text)) return true
            }
        }
        return false
    }
}
