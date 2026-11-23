package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HeadUnitMediaButtonsTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun aLivePressIsTakenAndNotSentBack() {
        val ordered = WheelKeyDelivery.decide(claimed = true, wheelHeld = true, ordered = true)
        assertTrue(ordered.abort)
        assertFalse(ordered.releaseTarget)
        assertFalse(ordered.redispatch)

        val exclusive = WheelKeyDelivery.decide(claimed = true, wheelHeld = true, ordered = false)
        assertFalse(exclusive.abort)
        assertFalse(exclusive.releaseTarget)
        assertFalse(exclusive.redispatch)
    }

    @Test
    fun anUnboundPressWhileLiveStaysWithWhoeverHasTheBroadcast() {
        val decision = WheelKeyDelivery.decide(claimed = false, wheelHeld = true, ordered = true)
        assertFalse(decision.abort)
        assertFalse(decision.releaseTarget)
        assertFalse(decision.redispatch)
    }

    /**
     * Bluetooth track change delivers the wheel as an exclusive press to whoever last registered.
     * Declining that press does not reach the car's player; the slot has to be cleared and the
     * press sent again. An ordered press already continues, so it must not be sent twice.
     */
    @Test
    fun aPressAfterCarPlayReleasesTheSlot() {
        val exclusive = WheelKeyDelivery.decide(claimed = false, wheelHeld = false, ordered = false)
        assertFalse(exclusive.abort)
        assertTrue(exclusive.releaseTarget)
        assertTrue(exclusive.redispatch)

        val ordered = WheelKeyDelivery.decide(claimed = false, wheelHeld = false, ordered = true)
        assertFalse(ordered.abort)
        assertTrue(ordered.releaseTarget)
        assertFalse(ordered.redispatch)
    }

    @Test
    fun claimEnablesTheReceiverAndReleaseDisablesIt() {
        val component = ComponentName(context, CarPlayMediaButtonReceiver::class.java)
        HeadUnitMediaButtons.claim(context)
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            context.packageManager.getComponentEnabledSetting(component),
        )

        HeadUnitMediaButtons.release(context)
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            context.packageManager.getComponentEnabledSetting(component),
        )

        HeadUnitMediaButtons.release(context)
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            context.packageManager.getComponentEnabledSetting(component),
        )
    }

    @Test
    fun returningTheSamePressTwiceDoesNotThrow() {
        val event = KeyEvent(5L, 5L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT, 0)
        HeadUnitMediaButtons.claim(context)
        HeadUnitMediaButtons.returnDeclinedKey(context, event)
        HeadUnitMediaButtons.returnDeclinedKey(context, event)
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            context.packageManager.getComponentEnabledSetting(
                ComponentName(context, CarPlayMediaButtonReceiver::class.java),
            ),
        )
    }
}
