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
            listOf(
                saved(
                    passphrase = null,
                    keyManagement = intArrayOf(WifiConfiguration.KeyMgmt.NONE),
                ),
            ),
            ssid,
            networkId = LIVE_NETWORK_ID,
        )

        assertNull(credentials?.passphrase)
        assertEquals(Iap2WirelessSecurity.NONE, credentials?.security)
    }

    /**
     * The WPA3 spellings, which every secured network used to be announced as WPA2 for.
     *
     * A phone told WPA2 for a WPA3-only network picks the wrong association and the join fails, so each
     * of the three masks below has to reach the phone as the one it actually is. The transitions come
     * in two spellings — SAE beside WPA2_PSK and SAE beside WPA_PSK — because both are written by real
     * platforms for the same network, and both mean the same thing to the phone.
     */
    @Test
    fun aWpa3OnlyNetworkIsAdvertisedAsWpa3Only() {
        // The hidden key is the ordinary case, not a refusal: the platform strips preSharedKey out of
        // what it hands an app, and a WPA3 network whose password this side cannot read is still a
        // network the phone can be told to join.
        val credentials = existingWifiCredentials(
            listOf(saved(passphrase = null, keyManagement = intArrayOf(SAE))),
            ssid,
            networkId = LIVE_NETWORK_ID,
        )

        assertNull(credentials?.passphrase)
        assertEquals(Iap2WirelessSecurity.WPA3_ONLY, credentials?.security)
    }

    @Test
    fun aWpa3TransitionNetworkIsNotAdvertisedAsWpa2() {
        val credentials = existingWifiCredentials(
            listOf(
                saved(
                    passphrase = "pocket-password",
                    keyManagement = intArrayOf(SAE, WifiConfiguration.KeyMgmt.WPA2_PSK),
                ),
            ),
            ssid,
            networkId = LIVE_NETWORK_ID,
        )

        assertEquals(Iap2WirelessSecurity.WPA3_TRANSITION, credentials?.security)
    }

    @Test
    fun theWpaPskSpellingOfATransitionNetworkReadsTheSameWay() {
        val credentials = existingWifiCredentials(
            listOf(
                saved(
                    passphrase = "pocket-password",
                    keyManagement = intArrayOf(SAE, WifiConfiguration.KeyMgmt.WPA_PSK),
                ),
            ),
            ssid,
            networkId = LIVE_NETWORK_ID,
        )

        assertEquals(Iap2WirelessSecurity.WPA3_TRANSITION, credentials?.security)
    }

    @Test
    fun anEnhancedOpenNetworkIsAdvertisedAsOneWithNoPassword() {
        val credentials = existingWifiCredentials(
            listOf(saved(passphrase = null, keyManagement = intArrayOf(OWE))),
            ssid,
            networkId = LIVE_NETWORK_ID,
        )

        assertNull(credentials?.passphrase)
        assertEquals(Iap2WirelessSecurity.NONE, credentials?.security)
    }

    @Test
    fun anEmptyKeyManagementMaskIsStillASecuredNetwork() {
        // A mask with nothing in it is a platform that would not say, and not saying is not the same as
        // saying the network is open: that reading would send the phone in with no password typed in.
        val credentials = existingWifiCredentials(
            listOf(saved(passphrase = "pocket-password", keyManagement = intArrayOf())),
            ssid,
            networkId = LIVE_NETWORK_ID,
        )

        assertEquals(Iap2WirelessSecurity.WPA_WPA2, credentials?.security)
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

    /**
     * The head unit that will not hand its copy over, which is the case the driver's copy exists for.
     *
     * Without the fallback the phone is told to join a secured network with no password, and the
     * bring-up then waits for a phone that has nothing to join with — which is what the afternoon
     * report shows: iAP2 authenticated, 0x5703 and 0x4301 sent, and no TCP connection ever arriving.
     */
    @Test
    fun aHiddenPasswordIsReplacedByTheOneTheDriverTypedForThatNetwork() {
        val password = existingWifiPassword(
            fromCar = null,
            driver = listOf(ExistingWifiDriverCredentials(ssid, "typed-by-driver")),
            ssid = ssid,
        )

        assertEquals("typed-by-driver", password.value)
        assertEquals("driver", password.source)
    }

    @Test
    fun theCarsOwnCopyIsWhatIsUsedWhenItHasOne() {
        val password = existingWifiPassword(
            fromCar = "car-password",
            driver = listOf(ExistingWifiDriverCredentials(ssid, "typed-by-driver")),
            ssid = ssid,
        )

        assertEquals("car-password", password.value)
        assertEquals("car", password.source)
    }

    @Test
    fun aPasswordTypedForAnotherNetworkIsNeverSentAsThisOnes() {
        val other = existingWifiPassword(
            fromCar = null,
            driver = listOf(ExistingWifiDriverCredentials("Someone else's Wi-Fi", "typed-by-driver")),
            ssid = ssid,
        )
        val blank = existingWifiPassword(
            fromCar = null,
            driver = listOf(ExistingWifiDriverCredentials(ssid, "")),
            ssid = ssid,
        )

        assertNull(other.value)
        assertEquals("nowhere", other.source)
        assertNull(blank.value)
        assertEquals("nowhere", blank.source)
    }

    /**
     * The saved name and the name the head unit reports need not be spelled the same way.
     *
     * The name is read off a head unit's screen and typed back by the driver, and a car that reports
     * `mycarwifi` where the driver wrote `MyCarWiFi` is the same network with a different
     * capitalisation. Discarding the password over that hands the phone a secured network with nothing
     * to join it with — the afternoon report's shape exactly.
     */
    @Test
    fun aSavedNameThatDiffersOnlyInCaseIsTheSameNetwork() {
        val password = existingWifiPassword(
            fromCar = null,
            driver = listOf(ExistingWifiDriverCredentials("MyCarWiFi", "typed-by-driver")),
            ssid = "mycarwifi",
        )

        assertEquals("typed-by-driver", password.value)
        assertEquals("driver", password.source)
    }

    @Test
    fun aSavedNameWithSurroundingSpacesIsTheSameNetwork() {
        // What a copy-and-paste off the car's own screen looks like, and what a field that was typed
        // into without looking leaves behind. Neither is a different network.
        val typed = existingWifiPassword(
            fromCar = null,
            driver = listOf(ExistingWifiDriverCredentials("  MyCarWiFi ", "typed-by-driver")),
            ssid = "MyCarWiFi",
        )
        val reported = existingWifiPassword(
            fromCar = null,
            driver = listOf(ExistingWifiDriverCredentials("MyCarWiFi", "typed-by-driver")),
            ssid = " MyCarWiFi ",
        )

        assertEquals("typed-by-driver", typed.value)
        assertEquals("typed-by-driver", reported.value)
    }

    @Test
    fun anExactNameWinsOverADifferentlyCasedOne() {
        // Two entries that both answer to the name the car reports, and one of them is the network
        // itself character for character. That one is the answer, and the tolerant comparison is not
        // asked at all — it would find two.
        val password = existingWifiPassword(
            fromCar = null,
            driver = listOf(
                ExistingWifiDriverCredentials("mycarwifi", "case-only-password"),
                ExistingWifiDriverCredentials("MyCarWiFi", "exact-password"),
            ),
            ssid = "MyCarWiFi",
        )

        assertEquals("exact-password", password.value)
        assertEquals("driver", password.source)
    }

    /**
     * Two names that differ only in case cannot be told apart, so neither password is used.
     *
     * The head unit reports one spelling of one network, and nothing here can say which entry it is.
     * Guessing is a wrong password rather than a missing one, and a wrong password is the failure the
     * driver cannot diagnose; the session goes on without a driver's copy instead.
     */
    @Test
    fun twoEntriesThatMatchOnlyInCaseYieldNoPasswordRatherThanOne() {
        val password = existingWifiPassword(
            fromCar = null,
            driver = listOf(
                ExistingWifiDriverCredentials("MyCarWiFi", "first-password"),
                ExistingWifiDriverCredentials("mycarwifi", "second-password"),
            ),
            ssid = "MYCARWIFI",
        )

        assertNull(password.value)
        assertEquals("nowhere", password.source)
    }

    /** A list of saved networks is not a licence to use one of them for a network it is not for. */
    @Test
    fun aPasswordSavedForAnUnrelatedNetworkIsStillNeverSent() {
        val password = existingWifiPassword(
            fromCar = null,
            driver = listOf(
                ExistingWifiDriverCredentials("Home Wi-Fi", "home-password"),
                ExistingWifiDriverCredentials("Work Wi-Fi", "work-password"),
            ),
            ssid = ssid,
        )

        assertNull(password.value)
        assertEquals("nowhere", password.source)
    }

    @Test
    fun wepNetworksAreRejectedAndNotTreatedAsOpen() {
        val wep = WifiConfiguration().apply {
            SSID = "\"$ssid\""
            networkId = LIVE_NETWORK_ID
            wepKeys[0] = "\"12345\""
            wepTxKeyIndex = 0
            allowedKeyManagement.clear()
            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
        }
        val credentials = existingWifiCredentials(listOf(wep), ssid, LIVE_NETWORK_ID)
        assertNull(credentials)
    }

    private fun saved(
        ssid: String = this.ssid,
        passphrase: String?,
        keyManagement: IntArray = intArrayOf(WifiConfiguration.KeyMgmt.WPA_PSK),
        networkId: Int = LIVE_NETWORK_ID,
    ): WifiConfiguration = WifiConfiguration().apply {
        this.SSID = "\"$ssid\""
        this.preSharedKey = passphrase?.let { "\"$it\"" }
        this.networkId = networkId
        allowedKeyManagement.clear()
        keyManagement.forEach { allowedKeyManagement.set(it) }
    }

    private companion object {
        const val LIVE_NETWORK_ID = 7

        // The API 29 values of WifiConfiguration.KeyMgmt.SAE and .OWE, written out for the reason the
        // production side writes them out: the symbols are newer than this module's floor.
        const val SAE = 8
        const val OWE = 9
    }
}
