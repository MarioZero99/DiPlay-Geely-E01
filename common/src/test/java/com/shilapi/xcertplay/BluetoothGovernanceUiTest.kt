package com.shilapi.xcertplay

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.shilapi.xcertplay.host.R
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN")
class BluetoothGovernanceUiTest {

    @After
    fun tearDown() {
        CarPlayBackgroundSession.clear()
    }

    private fun activity(page: String = "settings"): DiPlayActivity =
        Robolectric.buildActivity(DiPlayActivity::class.java, Intent().putExtra("page", page)).setup().get()

    private fun allViews(view: View): List<View> = when (view) {
        is ViewGroup -> (0 until view.childCount).map { view.getChildAt(it) }.flatMap { allViews(it) } + view
        else -> listOf(view)
    }

    private fun setSessionLive(live: Boolean) {
        val field = CarPlayBackgroundSession::class.java.getDeclaredField("stopAction")
        field.isAccessible = true
        if (live) {
            val dummyAction: (Boolean, () -> Unit) -> Unit = { _, cb -> cb() }
            field.set(CarPlayBackgroundSession, dummyAction)
        } else {
            field.set(CarPlayBackgroundSession, null)
        }
    }

    @Test
    fun `governance page renders car bluetooth restart button`() {
        val activity = activity("settings")
        val buttons = allViews(activity.findViewById(android.R.id.content)).filterIsInstance<Button>()
        val restartBtn = buttons.firstOrNull { it.text.toString() == activity.getString(R.string.bluetooth_restart_action) }

        assertNotNull("Bluetooth restart button must be present in vehicle governance", restartBtn)
        assertTrue("Bluetooth restart button must be enabled when no session is live", restartBtn!!.isEnabled)
    }

    @Test
    fun `bluetooth restart button is disabled when carplay session is active`() {
        setSessionLive(true)
        val activity = activity("settings")
        val buttons = allViews(activity.findViewById(android.R.id.content)).filterIsInstance<Button>()
        val restartBtn = buttons.firstOrNull { it.text.toString() == activity.getString(R.string.bluetooth_restart_action) }

        assertNotNull("Bluetooth restart button must be present", restartBtn)
        assertFalse("Bluetooth restart button must be disabled during active CarPlay session", restartBtn!!.isEnabled)
    }

    @Test
    fun `clicking bluetooth restart button shows confirmation dialog`() {
        val activity = activity("settings")
        val buttons = allViews(activity.findViewById(android.R.id.content)).filterIsInstance<Button>()
        val restartBtn = buttons.first { it.text.toString() == activity.getString(R.string.bluetooth_restart_action) }

        restartBtn.performClick()

        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull("Confirmation dialog must be shown on click", dialog)
        assertTrue("Dialog must be showing", dialog.isShowing)
    }
}
