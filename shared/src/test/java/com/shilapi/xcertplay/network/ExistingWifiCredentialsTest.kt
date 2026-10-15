package com.shilapi.xcertplay.network

import android.net.wifi.WifiConfiguration
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the car tells the phone about the network it is already on.
 *
 * These two values go into 0x5703 and 0x4301, so a wrong one is a wrong network rather than a wrong
 * detail. The password is allowed to be missing — the platform hides it from apps, and the session
 * goes on without it — but it is never allowed to be wrong, which is why a configuration that cannot
 * be found is a refusal and not a guess.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ExistingWifiCredentialsTest {
    private val ssid = "Pocket Wi-Fi"

    @Test
    fun aSavedNetworkGivesItsPasswordAndItsSecuredType() {
        val credentials = existingWifiCredentials(
            listOf(saved(passphrase = "pocket-password")),
            ssid,
            networkId = LIVE_NETWORK_ID,
        )

        assertEquals("pocket-password", credentials?.passphrase)
        assertEquals(Iap2WirelessSecurity.WPA_WPA2, credentials?.security)
    }

    @Test
    fun aPasswordThePlatformHidesLeavesTheNetworkSecuredAndTheSessionWithoutOne() {
        val credentials = existingWifiCredentials(
            listOf(saved(passphrase = null)),
            ssid,
            networkId = LIVE_NETWORK_ID,
        )

        assertNull(credentials?.passphrase)
        assertEquals(Iap2WirelessSecurity.WPA_WPA2, credentials?.security)
    }

    @Test
    fun aPlatformThatMasksTheKeyInsteadOfDroppingItReadsTheSameWay() {
        val credentials = existingWifiCredentials(
            listOf(saved(passphrase = "*")),
            ssid,
            networkId = LIVE_NETWORK_ID,
        )

        assertNull(credentials?.passphrase)
        assertEquals(Iap2WirelessSecurity.WPA_WPA2, credentials?.security)
    }

    @Test
    fun anOpenNetworkSaysSoAndHasNoPassword() {
        val credentials = existingWifiCredentials(
            listOf(saved(passphrase = null, open = true)),
            ssid,
            networkId = LIVE_NETWORK_ID,
        )

        assertNull(credentials?.passphrase)
        assertEquals(Iap2WirelessSecurity.NONE, credentials?.security)
    }

    @Test
    fun theNetworkTheCarIsOnWinsOverAnotherOneWithTheSameName() {
        // Two networks can carry one name, and the saved details of the other one are not the details
        // of this connection — a password from the wrong configuration is a password that is wrong.
        val credentials = existingWifiCredentials(
            listOf(
                saved(passphrase = "stale-password", networkId = 1),
                saved(passphrase = "pocket-password", networkId = LIVE_NETWORK_ID),
            ),
            ssid,
            networkId = LIVE_NETWORK_ID,
        )

        assertEquals("pocket-password", credentials?.passphrase)
    }

    @Test
    fun aNetworkWithNoSavedConfigurationIsRefusedRatherThanGuessed() {
        assertNull(existingWifiCredentials(listOf(saved(passphrase = "other")), "Somewhere Else", -1))
        assertNull(existingWifiCredentials(emptyList(), ssid, -1))
        assertNull(existingWifiCredentials(null, ssid, -1))
    }

    @Test
    fun theQuotesThePlatformStoresAreNotPartOfTheValue() {
        val credentials = existingWifiCredentials(
            listOf(saved(ssid = "Pocket Wi-Fi", passphrase = "pocket-password")),
            "Pocket Wi-Fi",
            networkId = LIVE_NETWORK_ID,
        )

        assertEquals("pocket-password", credentials?.passphrase)
    }

    private fun saved(
        ssid: String = this.ssid,
        passphrase: String?,
        open: Boolean = false,
        networkId: Int = LIVE_NETWORK_ID,
    ): WifiConfiguration = WifiConfiguration().apply {
        this.SSID = "\"$ssid\""
        this.preSharedKey = passphrase?.let { "\"$it\"" }
        this.networkId = networkId
        allowedKeyManagement.clear()
        allowedKeyManagement.set(
            if (open) WifiConfiguration.KeyMgmt.NONE else WifiConfiguration.KeyMgmt.WPA_PSK,
        )
    }

    private companion object {
        const val LIVE_NETWORK_ID = 7
    }
}
