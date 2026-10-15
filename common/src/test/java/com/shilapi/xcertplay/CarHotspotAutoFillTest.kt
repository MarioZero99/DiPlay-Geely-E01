package com.shilapi.xcertplay

import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import com.shilapi.xcertplay.network.CarHotspotStatus
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.transport.WirelessHotspotMode
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowWifiManager

/**
 * The clean-install path end to end: empty preferences, a head unit that answers with its own
 * hotspot, and the runtime config that has to accept the result.
 *
 * The name and the key come from the car, but the security mode is derived from a passphrase, and
 * the two readers of "the passphrase" disagree the moment the car is the one that supplies it. What
 * breaks is not only the connection: the settings page refuses to save a key it has been told is an
 * open network, and the hotspot manager rejects a live WPA2 access point announced as open.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [CarHotspotAutoFillTest.ApRadio::class])
class CarHotspotAutoFillTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun seedCarHotspot(ssid: String, key: String?) {
        (shadowOf(context.getSystemService(WifiManager::class.java)) as ApRadio).configured =
            WifiConfiguration().apply {
                SSID = "\"$ssid\""
                preSharedKey = key?.let { "\"$it\"" }
            }
    }

    /** What `CarPlayHostActivity.loadPersistedSettings` builds from a device that has saved nothing. */
    private fun cleanInstallConfig(): CarPlayRuntimeConfig {
        val storedSsid = AirPlayPersistence.loadManualHotspotSsid(context)
        val (ssid, passphrase) = manualHotspotCredentials(
            storedSsid = storedSsid,
            storedPassphrase = AirPlayPersistence.loadManualHotspotPassphrase(context),
            carHotspot = CarHotspotStatus.accessPoint(context),
        )
        return runtimeConfig(
            ssid = ssid,
            passphrase = passphrase,
            security = manualHotspotSecurityFor(
                storedSsid = storedSsid,
                storedSecurity = AirPlayPersistence.loadManualHotspotSecurity(context),
                usedPassphrase = passphrase,
            ),
        )
    }

    @Test fun aSecuredCarHotspotIsAnnouncedAsSecuredOnACleanInstall() {
        seedCarHotspot("GEELY-1234", "carkey123")

        val config = cleanInstallConfig()

        assertEquals("GEELY-1234", config.manualHotspotSsid)
        assertEquals("carkey123", config.manualHotspotPassphrase)
        assertEquals(ManualHotspotSecurity.WPA2, config.manualHotspotSecurity)
    }

    @Test fun anOpenCarHotspotIsAnnouncedAsOpenOnACleanInstall() {
        seedCarHotspot("GEELY-1234", null)

        val config = cleanInstallConfig()

        assertEquals("GEELY-1234", config.manualHotspotSsid)
        assertEquals("", config.manualHotspotPassphrase)
        assertEquals(ManualHotspotSecurity.OPEN, config.manualHotspotSecurity)
    }

    @Test fun aSavedPairKeepsTheSecurityTheDriverChose() {
        AirPlayPersistence.saveManualHotspotSsid(context, "DriverNet")
        AirPlayPersistence.saveManualHotspotPassphrase(context, "driverkey")
        AirPlayPersistence.saveManualHotspotSecurity(context, ManualHotspotSecurity.WPA3)
        seedCarHotspot("GEELY-1234", "carkey123")

        val config = cleanInstallConfig()

        assertEquals("DriverNet", config.manualHotspotSsid)
        assertEquals(ManualHotspotSecurity.WPA3, config.manualHotspotSecurity)
    }

    @Test fun aFirmwareThatHidesItsHotspotLeavesTheConfigRefusedRatherThanWrong() {
        // Nothing on either side. The config refuses, and the connection page reports the setting as
        // incomplete instead of taking the screen down — the half the activity's catch covers.
        assertThrows(IllegalArgumentException::class.java) { cleanInstallConfig() }
    }

    private fun runtimeConfig(
        ssid: String,
        passphrase: String,
        security: ManualHotspotSecurity,
    ): CarPlayRuntimeConfig = CarPlayRuntimeConfig(
        mfiTarget = MfiTarget.LOCAL,
        transport = CarPlayTransport.WIRELESS,
        wirelessHotspotMode = WirelessHotspotMode.MANUAL,
        manualHotspotSsid = ssid,
        manualHotspotPassphrase = passphrase,
        manualHotspotSecurity = security,
        identification = Iap2IdentificationConfig(
            name = "test",
            modelIdentifier = "test",
            manufacturer = "test",
            serialNumber = "test",
            firmwareVersion = "1",
            hardwareVersion = "1",
            carPlayUsbInterfaceNumber = 3,
        ),
    )

    @Implements(WifiManager::class)
    class ApRadio : ShadowWifiManager() {
        var configured: WifiConfiguration? = null
        @Implementation override fun getWifiApConfiguration(): WifiConfiguration? = configured
    }
}
