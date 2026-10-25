package com.shilapi.xcertplay.network

import android.content.Context
import android.net.wifi.WifiManager
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ManualHotspotManagerTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val diagnostics = mutableListOf<String>()

    private val mockInterface = ManualHotspotManager.LocalHotspotInterface(
        name = "ap0",
        hostAddress = InetAddress.getByName("192.168.43.1"),
        addresses = listOf(InetAddress.getByName("192.168.43.1")),
        hardwareAddress = "02:00:00:00:00:01",
        score = 100,
    )

    @Before
    fun setUp() {
        shadowOf(wifi).setAccessWifiStatePermission(true)
        shadowOf(wifi).setChangeWifiStatePermission(true)
    }

    @Test
    fun resolvesChannelFromDriverRadioWhenPublicApiExposesNone() {
        val manager = ManualHotspotManager(
            context = context,
            ssid = "Car_Hotspot_Test",
            passphrase = "password1234",
            band = ManualHotspotBand.GHZ_5,
            channel = 0,
            security = ManualHotspotSecurity.WPA2,
            onDiagnostic = { diagnostics += it },
            driverRadioReader = { iface, band ->
                assertEquals("ap0", iface)
                assertEquals("5 GHz", band)
                LegacyHotspotRadio.Reading(5180, null)
            },
            localInterfaceLocator = { mockInterface },
            settleThresholdMs = 50L,
        )

        val info = java.util.concurrent.Executors.newSingleThreadExecutor().submit<WirelessHotspotInfo> {
            manager.start(1000L)
        }.get()
        assertEquals(36, info.channel)
        assertEquals(5180, info.frequencyMHz)
        assertEquals("5 GHz", info.bandLabel)
    }

    @Test
    fun driverFrequencyMismatchWithExpectedBandThrows() {
        val manager = ManualHotspotManager(
            context = context,
            ssid = "Car_Hotspot_Test",
            passphrase = "password1234",
            band = ManualHotspotBand.GHZ_5,
            channel = 0,
            security = ManualHotspotSecurity.WPA2,
            onDiagnostic = { diagnostics += it },
            driverRadioReader = { _, _ ->
                LegacyHotspotRadio.Reading(2412, null)
            },
            localInterfaceLocator = { mockInterface },
            settleThresholdMs = 50L,
        )

        try {
            java.util.concurrent.Executors.newSingleThreadExecutor().submit {
                manager.start(300L)
            }.get()
            fail("Expected ExecutionException caused by IOException")
        } catch (e: Exception) {
            val cause = e.cause ?: e
            assertTrue(
                "Expected cause to be IOException but was ${cause.javaClass.name}",
                cause is IOException,
            )
            assertTrue(
                "Expected message to contain 'Manual hotspot is not running on 5 GHz', but was: ${cause.message}",
                cause.message?.contains("Manual hotspot is not running on 5 GHz") == true,
            )
        }
    }

    @Test
    fun transientFrequencyMismatchSettlesBeforeTimeout() {
        val readCount = AtomicInteger(0)
        val manager = ManualHotspotManager(
            context = context,
            ssid = "Car_Hotspot_Test",
            passphrase = "password1234",
            band = ManualHotspotBand.GHZ_5,
            channel = 0,
            security = ManualHotspotSecurity.WPA2,
            onDiagnostic = { diagnostics += it },
            driverRadioReader = { _, _ ->
                val count = readCount.incrementAndGet()
                if (count <= 2) {
                    // First two reads return stale 2.4 GHz STA PLL frequency
                    LegacyHotspotRadio.Reading(2412, null)
                } else {
                    // Subsequent reads return settled 5 GHz frequency
                    LegacyHotspotRadio.Reading(5180, null)
                }
            },
            localInterfaceLocator = { mockInterface },
            settleThresholdMs = 600L,
        )

        val info = java.util.concurrent.Executors.newSingleThreadExecutor().submit<WirelessHotspotInfo> {
            manager.start(2500L)
        }.get()
        assertEquals(36, info.channel)
        assertEquals(5180, info.frequencyMHz)
        assertEquals("5 GHz", info.bandLabel)
        assertTrue(readCount.get() >= 3)
    }

    @Test
    fun driverReadingNullFallsBackToChannelZero() {
        val manager = ManualHotspotManager(
            context = context,
            ssid = "Car_Hotspot_Test",
            passphrase = "password1234",
            band = ManualHotspotBand.GHZ_5,
            channel = 0,
            security = ManualHotspotSecurity.WPA2,
            onDiagnostic = { diagnostics += it },
            driverRadioReader = { _, _ ->
                LegacyHotspotRadio.Reading(null, "driver frequency query errno=95")
            },
            localInterfaceLocator = { mockInterface },
            settleThresholdMs = 50L,
        )

        val info = java.util.concurrent.Executors.newSingleThreadExecutor().submit<WirelessHotspotInfo> {
            manager.start(300L)
        }.get()
        assertEquals(0, info.channel)
        assertNull(info.frequencyMHz)
        assertEquals("5 GHz", info.bandLabel)
    }

    @Test
    fun incompatibleFrequencyFollowedByNullDoesNotSucceed() {
        val readCount = AtomicInteger(0)
        val manager = ManualHotspotManager(
            context = context,
            ssid = "Car_Hotspot_Test",
            passphrase = "password1234",
            band = ManualHotspotBand.GHZ_5,
            channel = 0,
            security = ManualHotspotSecurity.WPA2,
            onDiagnostic = { diagnostics += it },
            driverRadioReader = { _, _ ->
                val count = readCount.incrementAndGet()
                if (count == 1) {
                    // First read returns incompatible 2.4 GHz frequency
                    LegacyHotspotRadio.Reading(2412, null)
                } else {
                    // Subsequent reads return null (ioctl error)
                    LegacyHotspotRadio.Reading(null, "driver query errno=95")
                }
            },
            localInterfaceLocator = { mockInterface },
            settleThresholdMs = 300L,
        )

        try {
            java.util.concurrent.Executors.newSingleThreadExecutor().submit {
                manager.start(800L)
            }.get()
            fail("Expected ExecutionException caused by IOException, but start() succeeded")
        } catch (e: Exception) {
            val cause = e.cause ?: e
            assertTrue(
                "Expected cause to be IOException but was ${cause.javaClass.name}",
                cause is IOException,
            )
            assertTrue(
                "Expected message to contain 'Manual hotspot is not running on 5 GHz', but was: ${cause.message}",
                cause.message?.contains("Manual hotspot is not running on 5 GHz") == true,
            )
        }
    }

    @Test
    fun driverQueryFailureFallsBackToChannelZeroWithoutCrashing() {
        val channel = observedManualHotspotChannel(
            driverFrequencyMHz = null,
            connectionFrequencyMHz = null,
            scanFrequencyMHz = null,
        )
        assertEquals(0, channel)
    }
}
