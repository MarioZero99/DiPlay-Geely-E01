package com.shilapi.xcertplay.ui.component

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.shilapi.xcertplay.SessionPhase
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.ui.theme.CockpitThemeManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * What the connection stage says, and in what colour.
 *
 * Two of these are the card's own words: the main button is the only control that starts a session,
 * and while an attempt is in flight it is repainted in `accentSoft` — a 12% wash. The label stayed in
 * `btnMainFg`, which is white in both light skins and black in the night one, so the button said
 * "Connecting to your iPhone…" in a colour the eye cannot separate from the wash behind it, at the
 * one moment the driver is waiting on it.
 *
 * The third is the line that was copied in with the old readiness card: "CarPlay is running. To change
 * this, disconnect on the home page first." It belongs under a setting that a live session locks, and
 * this card *is* the home page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en")
class HeroStageCardTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val palette = CockpitThemeManager.ZEEKR_WHITE_PALETTE

    private fun card(
        phase: SessionPhase,
        gaps: List<String>? = null,
        blocked: Boolean = false,
        mode: Boolean? = null,
        hasUsbHost: Boolean = false,
    ): HeroStageCard = HeroStageCard(
        context = context,
        palette = palette,
        sessionPhase = phase,
        pairedPhoneName = null,
        hasUsbHost = hasUsbHost,
        setupError = null,
        supportedConnectionView = null,
        adapterStatusView = null,
        hotspotOffNote = null,
        onConnectWireless = {},
        onConnectUsb = {},
        onOpenProjection = {},
        onDisconnect = {},
        onOpenSettings = {},
        initialConnectionMode = mode,
    ).also { it.updateState(phase, null, gaps, blocked) }

    @Test
    fun `the connecting label is drawn in the accent colour, not in the button's own`() {
        val connecting = card(SessionPhase.Connecting)
        val main = mainButton(connecting)
        assertEquals("Connecting to your iPhone…", main.text.toString())
        assertEquals(
            "white on a 12% wash is a label nobody can read",
            palette.accent,
            main.currentTextColor,
        )
    }

    /** And back again: the idle button is a solid `btnMainBg` and keeps its own foreground. */
    @Test
    fun `the idle label keeps the foreground its fill was chosen for`() {
        assertEquals(palette.btnMainFg, mainButton(card(SessionPhase.Idle)).currentTextColor)
        assertEquals(palette.btnMainFg, mainButton(card(SessionPhase.Live)).currentTextColor)
    }

    @Test
    fun `the stage never tells the driver to disconnect on the home page`() {
        val forbidden = context.getString(R.string.carplay_is_running_this_needs_it_closed)
        listOf(SessionPhase.Idle, SessionPhase.Connecting, SessionPhase.Live).forEach { phase ->
            assertFalse(
                "$phase draws a settings-page instruction on the home page",
                forbidden in texts(card(phase)),
            )
        }
    }

    /**
     * The subtitle already leads with the first reason. Printing it again at the top of the list put
     * the same red sentence on one card twice, which reads as a rendering fault.
     */
    @Test
    fun `a blocked route states each reason once`() {
        val texts = texts(
            card(
                SessionPhase.Idle,
                gaps = listOf("Turn on the car's Bluetooth", "Save the car hotspot name and password"),
                blocked = true,
            ),
        )
        assertEquals(
            "the first reason is the subtitle, and only the subtitle",
            1L,
            texts.count { it == "● Turn on the car's Bluetooth" }.toLong(),
        )
        assertEquals(
            "the rest of the list is still said once",
            1L,
            texts.count { it == "● Save the car hotspot name and password" }.toLong(),
        )
    }

    /** The rest of the list is still there, and so is the way to the controls that close it. */
    @Test
    fun `the remaining reasons and the way to settings survive`() {
        val card = card(
            SessionPhase.Idle,
            gaps = listOf("Turn on the car's Bluetooth", "Save the car hotspot name and password"),
            blocked = true,
        )
        val texts = texts(card)
        assertTrue("the second reason is still on the card", "● Save the car hotspot name and password" in texts)
        assertTrue("and so is the way to the settings", "Open settings" in texts)
    }

    /**
     * An attempt that is not going to land used to leave the driver with no way out of it: the exit
     * existed, was drawn only once the session was live, and the only other door was the car's own
     * launcher. While the attempt is in flight the button carries the word for what it does then.
     */
    @Test
    fun `the cancel button is on the card while an attempt is in flight`() {
        val connecting = card(SessionPhase.Connecting)
        val cancel = buttons(connecting)
            .firstOrNull { it.text?.toString() == context.getString(R.string.cancel) }

        assertNotNull("连接中必须能退出这次尝试", cancel)
        assertEquals(View.VISIBLE, cancel?.visibility)
        assertTrue("连接中它得能按", cancel?.isEnabled == true)
    }

    /** A live session still says Disconnect, which is the same exit under the honest name. */
    @Test
    fun `a live session says disconnect and not cancel`() {
        val live = card(SessionPhase.Live)
        val disconnect = buttons(live)
            .firstOrNull { it.text?.toString() == context.getString(R.string.disconnect) }

        assertEquals(View.VISIBLE, disconnect?.visibility)
        assertTrue(
            "取消是给还没连上的尝试用的",
            buttons(live).none { it.text?.toString() == context.getString(R.string.cancel) },
        )
    }

    /**
     * Nobody has chosen a transport yet, so the card asks instead of assuming wireless — the old
     * main button started a wireless attempt the driver never picked.
     */
    @Test
    fun `the first card offers both transports instead of assuming wireless`() {
        val idle = card(SessionPhase.Idle, mode = null, hasUsbHost = true)
        val texts = texts(idle)

        assertTrue("无线要有按钮", context.getString(R.string.connect_wireless) in texts)
        assertTrue("有线也要有按钮", context.getString(R.string.connect_wired) in texts)
        assertTrue(
            "主按钮只说该做选择",
            context.getString(R.string.choose_connection_mode) in texts,
        )
    }

    /** A wired choice on a head unit with no USB host is a choice that cannot run, and it says so. */
    @Test
    fun `wired without a usb host greys its button out and says why`() {
        val wired = card(SessionPhase.Idle, mode = false, hasUsbHost = false)
        val texts = texts(wired)

        assertTrue(
            "必须说明这台车机没有 USB 主机模式",
            context.getString(R.string.usb_host_not_supported) in texts,
        )
        val wiredBtn = buttons(wired).first { it.text?.toString() == context.getString(R.string.connect_wired) }
        assertFalse("不能用就不能按", wiredBtn.isEnabled)
    }

    /**
     * The readiness list is the wireless route's. Under a wired choice it would tell the driver to
     * turn on a Bluetooth radio that this attempt does not use.
     */
    @Test
    fun `a wired choice drops the wireless readiness lines`() {
        val wired = card(
            SessionPhase.Idle,
            gaps = listOf("Turn on the car's Bluetooth", "Save the car hotspot name and password"),
            blocked = true,
            mode = false,
            hasUsbHost = true,
        )
        val texts = texts(wired)

        assertFalse("有线不该念无线的话", "● Turn on the car's Bluetooth" in texts)
        assertTrue(
            "有线该说的是插线",
            context.getString(R.string.connect_your_iphone_with_a_usb_cable) in texts,
        )
    }

    /** The three labels the main control can carry, so it can be found whatever phase it is in. */
    private fun mainButton(card: HeroStageCard): Button {
        val labels = listOf(
            context.getString(R.string.connect_phone),
            context.getString(R.string.connecting_to_your_iphone),
            context.getString(R.string.open_carplay),
            context.getString(R.string.choose_connection_mode),
            context.getString(R.string.connect_wired),
            context.getString(R.string.connect_wireless),
        )
        return buttons(card).first { it.text?.toString() in labels }
    }

    private fun texts(card: View): List<String> = descendants(card)
        .filterIsInstance<TextView>()
        .mapNotNull { it.text?.toString() }
        .filter { it.isNotBlank() }
        .toList()

    private fun buttons(card: View): List<Button> = descendants(card).filterIsInstance<Button>().toList()

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
