package com.shilapi.xcertplay.network

import org.junit.Assert.assertEquals
import org.junit.Test

class WirelessStartupPolicyTest {

    @Test
    fun carHotspotAlwaysUsesThirtySeconds() {
        assertEquals(
            WirelessStartupPolicy.FIRST_TCP_MILLIS,
            WirelessStartupPolicy.firstTcpTimeoutMillis(
                isExistingWifi = false,
                reconnectAttempts = 0,
                generation = 1,
                wirelessAddressFamily = null,
            ),
        )
        assertEquals(
            WirelessStartupPolicy.FIRST_TCP_MILLIS,
            WirelessStartupPolicy.firstTcpTimeoutMillis(
                isExistingWifi = false,
                reconnectAttempts = 2,
                generation = 2,
                wirelessAddressFamily = "IPv4",
            ),
        )
    }

    @Test
    fun existingWifiColdStartUsesTenSeconds() {
        assertEquals(
            10_000L,
            WirelessStartupPolicy.firstTcpTimeoutMillis(
                isExistingWifi = true,
                reconnectAttempts = 0,
                generation = 1,
                wirelessAddressFamily = null,
            ),
        )
    }

    @Test
    fun existingWifiSubsequentAttemptsUseFiveSeconds() {
        // Retry attempt within the reconnection loop
        assertEquals(
            5_000L,
            WirelessStartupPolicy.firstTcpTimeoutMillis(
                isExistingWifi = true,
                reconnectAttempts = 1,
                generation = 1,
                wirelessAddressFamily = null,
            ),
        )

        // Attempt after address family has already been determined or flipped
        assertEquals(
            5_000L,
            WirelessStartupPolicy.firstTcpTimeoutMillis(
                isExistingWifi = true,
                reconnectAttempts = 0,
                generation = 1,
                wirelessAddressFamily = "IPv4",
            ),
        )

        // Internal wireless generation restart
        assertEquals(
            5_000L,
            WirelessStartupPolicy.firstTcpTimeoutMillis(
                isExistingWifi = true,
                reconnectAttempts = 0,
                generation = 2,
                wirelessAddressFamily = null,
            ),
        )
    }
}
