package com.shilapi.xcertplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarHotspotControllerTest {
    @Test
    fun aSwitchNobodyAskedForNeverSpendsTheDriversWifi() {
        // Opening the app is not the driver asking for a session, so an attempt that cannot turn the
        // hotspot on must leave their Wi-Fi -- and on this head unit their ADB over it -- where it was.
        assertEquals(
            listOf(false),
            hotspotEnableAttempts(
                mayReleaseStationMode(spendDriverWifi = false, stationEnabled = true),
            ),
        )
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
    fun leavesStationModeAloneWhenItIsAlreadyOff() {
        assertEquals(listOf(false), hotspotEnableAttempts(stationEnabled = false))
    }

    @Test
    fun triesAgainWithStationModeReleasedWhenItIsOn() {
        // Turning the driver's Wi-Fi off is a visible side effect, so it is the second attempt only.
        assertEquals(listOf(false, true), hotspotEnableAttempts(stationEnabled = true))
    }

    @Test
    fun doesNotSpendTheDriversWifiOnARefusalReleasingItCannotChange() {
        // The firmware gated the permission; station mode had nothing to do with it. Turning the
        // driver's Wi-Fi off here buys nothing and takes their connection with it.
        var attempts = 0
        var releases = 0
        var restores = 0
        val result = enableHotspot(
            stationEnabled = true,
            releaseStationMode = { releases += 1 },
            restoreStationMode = { restores += 1 },
        ) {
            attempts += 1
            HotspotStartResult.PERMISSION_REQUIRED
        }

        assertEquals(HotspotStartResult.PERMISSION_REQUIRED, result)
        assertEquals("a refusal the firmware owns must be reported after one attempt", 1, attempts)
        assertEquals("the driver's Wi-Fi must not be switched off for it", 0, releases)
        assertEquals(0, restores)
    }

    @Test
    fun releasesStationModeForOneRetryAndPutsItBackWhenThatFailsToo() {
        val calls = mutableListOf<String>()
        val result = enableHotspot(
            stationEnabled = true,
            releaseStationMode = { calls += "release" },
            restoreStationMode = { calls += "restore" },
        ) {
            calls += "attempt"
            HotspotStartResult.FAILED
        }

        assertEquals(HotspotStartResult.FAILED, result)
        assertEquals(
            "a failed switch must not leave the driver without their Wi-Fi",
            listOf("attempt", "release", "attempt", "restore"),
            calls,
        )
    }

    @Test
    fun keepsStationModeOffOnceItIsWhatMadeTheHotspotComeOn() {
        var attempts = 0
        val calls = mutableListOf<String>()
        val result = enableHotspot(
            stationEnabled = true,
            releaseStationMode = { calls += "release" },
            restoreStationMode = { calls += "restore" },
        ) {
            attempts += 1
            calls += "attempt"
            if (attempts == 2) HotspotStartResult.READY else HotspotStartResult.FAILED
        }

        assertEquals(HotspotStartResult.READY, result)
        // The hotspot holds the radio now, so station mode stays down — only a failure puts it back.
        assertEquals(listOf("attempt", "release", "attempt"), calls)
    }

    @Test
    fun makesASingleAttemptAndTouchesNoWifiWhenStationModeIsAlreadyOff() {
        var attempts = 0
        val result = enableHotspot(
            stationEnabled = false,
            releaseStationMode = { throw AssertionError("station mode is already off") },
            restoreStationMode = { throw AssertionError("nothing was released") },
        ) {
            attempts += 1
            HotspotStartResult.READY
        }

        assertEquals(HotspotStartResult.READY, result)
        assertEquals(1, attempts)
    }
}
