package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where the session is, decided once for the whole page.
 *
 * Three states rather than two flags, because "a connection exists" and "there is a picture on the
 * screen" are not the same question and the page answers them differently — see 49 §一. The page asks
 * this and nothing else; a control that reads the session for itself is how the controls came to
 * disagree with each other.
 */
class WirelessPageStateTest {
    @Test
    fun theThreePhasesComeFromTheTwoFactsTheSessionReports() {
        assertEquals(SessionPhase.Idle, sessionPhase(hasSession = false, active = false))
        assertEquals(SessionPhase.Connecting, sessionPhase(hasSession = true, active = false))
        assertEquals(SessionPhase.Live, sessionPhase(hasSession = true, active = true))
    }

    /**
     * Only a session that has a picture asks before a connection setting is applied.
     *
     * A picture is something to lose, so changing what the next connection is built from is worth a
     * question. An attempt that has not produced one yet has nothing to lose — asking there is one
     * more tap for nothing.
     */
    @Test
    fun onlyALiveSessionAsksBeforeAConnectionSettingIsApplied() {
        assertEquals(false, connectionSettingNeedsConfirm(SessionPhase.Idle))
        assertEquals(false, connectionSettingNeedsConfirm(SessionPhase.Connecting))
        assertEquals(true, connectionSettingNeedsConfirm(SessionPhase.Live))
    }
}
