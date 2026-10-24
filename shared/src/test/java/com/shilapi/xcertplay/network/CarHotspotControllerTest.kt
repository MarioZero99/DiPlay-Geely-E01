package com.shilapi.xcertplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarHotspotControllerTest {
    @Test
    fun aSwitchNobodyAskedForNeverSpendsTheDriversWifi() {
        assertFalse(mayReleaseStationMode(spendDriverWifi = false, stationEnabled = true))
        val calls = mutableListOf<String>()
        val result = enableHotspot(
            spendStation = false,
            releaseStationMode = { calls += "release"; true },
        ) {
            calls += "enable"
            HotspotStartResult.PERMISSION_REQUIRED
        }
        assertEquals(HotspotStartResult.PERMISSION_REQUIRED, result)
        assertEquals(listOf("enable"), calls)
    }

    @Test
    fun aSwitchTheDriverAskedForMaySpendIt() {
        assertTrue(mayReleaseStationMode(spendDriverWifi = true, stationEnabled = true))
    }

    @Test
    fun thereIsNothingToSpendWhenStationModeIsAlreadyOff() {
        assertFalse(mayReleaseStationMode(spendDriverWifi = true, stationEnabled = false))
    }

    @Test
    fun doesNotAskForTheHotspotWhileStationModeIsStillOn() {
        var enables = 0
        val result = enableHotspot(
            spendStation = true,
            releaseStationMode = { false },
        ) {
            enables += 1
            HotspotStartResult.READY
        }
        assertEquals(HotspotStartResult.RADIO_BUSY, result)
        assertEquals(0, enables)
    }

    @Test
    fun releasesStationBeforeTheHotspotAndLeavesItOffWhenTheHotspotFails() {
        val calls = mutableListOf<String>()
        val result = enableHotspot(
            spendStation = true,
            releaseStationMode = { calls += "release"; true },
        ) {
            calls += "enable"
            HotspotStartResult.PERMISSION_REQUIRED
        }
        assertEquals(HotspotStartResult.PERMISSION_REQUIRED, result)
        assertEquals(listOf("release", "enable"), calls)
    }

    @Test
    fun keepsStationModeOffOnceTheHotspotComesOn() {
        val calls = mutableListOf<String>()
        val result = enableHotspot(
            spendStation = true,
            releaseStationMode = { calls += "release"; true },
        ) {
            calls += "enable"
            HotspotStartResult.READY
        }
        assertEquals(HotspotStartResult.READY, result)
        assertEquals(listOf("release", "enable"), calls)
    }

    @Test
    fun nativeAcceptanceKeepsTheWholeBudgetAndDoesNotEscalate() {
        val plan = apWaitPlan(nativeAccepted = true, timeoutMillis = 15_000L)
        assertFalse(plan.escalateToRoot)
        assertEquals(15_000L, plan.waitMillis)
    }

    @Test
    fun aRefusalEscalatesAtOnceAndStillWaitsTheWholeBudget() {
        val plan = apWaitPlan(nativeAccepted = false, timeoutMillis = 15_000L)
        assertTrue(plan.escalateToRoot)
        assertEquals(15_000L, plan.waitMillis)
    }

    @Test
    fun stationRootConfirmationKeepsTheRemainderOfTheBudget() {
        assertEquals(800L to 1_200L, stationPollSplit(2_000L))
        assertTrue(stationPollSplit(2_000L).second > 0L)
    }

    @Test
    fun anAcceptedDisableThatIsNotConfirmedYetIsNotARefusal() {
        assertNull(sessionReleaseResult(callAccepted = true, confirmedOff = false, timeoutMillis = 2_000L))
        assertNull(sessionReleaseResult(callAccepted = true, confirmedOff = true, timeoutMillis = 2_000L))
    }

    @Test
    fun aRefusedDisableThatIsStillOnIsReported() {
        assertEquals(
            "the car did not report hotspot disabled within 2000ms",
            sessionReleaseResult(callAccepted = false, confirmedOff = false, timeoutMillis = 2_000L),
        )
    }

    @Test
    fun disablingAndEnablingAreNotFullyOff() {
        assertEquals(ApPhase.TRANSITION, classifyApPhase(WIFI_AP_STATE_DISABLING, null))
        assertEquals(ApPhase.TRANSITION, classifyApPhase(WIFI_AP_STATE_ENABLING, null))
        assertEquals(ApPhase.OFF, classifyApPhase(WIFI_AP_STATE_DISABLED, null))
        assertEquals(ApPhase.ON, classifyApPhase(WIFI_AP_STATE_ENABLED, null))
        assertEquals(ApPhase.OFF, classifyApPhase(null, false))
        assertEquals(ApPhase.UNKNOWN, classifyApPhase(null, null))
    }

    @Test
    fun stationDisablingAndEnablingAreNotFullyOff() {
        assertEquals(StationPhase.TRANSITION, classifyStationPhase(WIFI_STATE_DISABLING, null))
        assertEquals(StationPhase.TRANSITION, classifyStationPhase(WIFI_STATE_ENABLING, null))
        assertEquals(StationPhase.OFF, classifyStationPhase(WIFI_STATE_DISABLED, null))
        assertEquals(StationPhase.ON, classifyStationPhase(WIFI_STATE_ENABLED, null))
        assertEquals(StationPhase.OFF, classifyStationPhase(null, false))
        assertEquals(StationPhase.ON, classifyStationPhase(null, true))
        assertEquals(StationPhase.UNKNOWN, classifyStationPhase(null, null))
    }
}
