package com.shilapi.xcertplay

import android.content.Intent
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Opening the app with the switch on actually reaches the hotspot controller.
 *
 * The decision itself — that the switch is off by default and only means anything for the car's own
 * hotspot — is [CarHotspotAutoStartTest]. What this pins is the half a decision cannot show: that the
 * answer is acted on, off the main thread, and written where a report can read it.
 *
 * Only the switch-on direction is asserted. A negative here would be "nothing was logged in the time
 * we waited", which a slow machine turns into a passing test for the wrong reason; the switch-off
 * direction is the pure test's, where it is an equality rather than a race.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en")
class CarHotspotStartTest {
    @Test fun openingTheAppWithTheSwitchOnAsksForTheCarsHotspot() {
        AirPlayPersistence.saveAutoCarHotspot(RuntimeEnvironment.getApplication(), true)

        Robolectric.buildActivity(
            DiPlayActivity::class.java,
            Intent(RuntimeEnvironment.getApplication(), DiPlayActivity::class.java),
        ).setup().get()

        assertTrue("the attempt is made and written down", hotspotAttemptWasLogged())
    }

    /** The controller is polled off the main thread, so the line arrives after `setup` returns. */
    private fun hotspotAttemptWasLogged(): Boolean {
        val deadline = System.currentTimeMillis() + WAIT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            val logged = ShadowLog.getLogsForTag("DiPlay-CarHotspot")
                .any { it.msg.startsWith("app-start hotspot") }
            if (logged) return true
            Thread.sleep(POLL_MILLIS)
        }
        return false
    }

    private companion object {
        const val WAIT_MILLIS = 5_000L
        const val POLL_MILLIS = 25L
    }
}
