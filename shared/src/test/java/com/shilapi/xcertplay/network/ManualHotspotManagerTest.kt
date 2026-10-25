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
    fun driverFrequencyMismatchWithExpectedBandFallsBackToChannelZeroWithoutKillingConnection() {
        val manager = ManualHotspotManager(
            context = context,
            ssid = "Car_Hotspot_Test",
            passphrase = "password1234",
            band = ManualHotspotBand.GHZ_5,
            channel = 0,
            security = ManualHotspotSecurity.WPA2,
            onDiagnostic = { diagnostics += it },
            driverRadioReader = { _, _ ->
                // Driver reports 2.4 GHz (e.g. single-radio residue on E01) when 5 GHz was expected
                LegacyHotspotRadio.Reading(2412, null)
            },
            localInterfaceLocator = { mockInterface },
        )

        // Must NOT abort connection with IOException; must safely report channel 0 so iPhone scans reliably
        val info = java.util.concurrent.Executors.newSingleThreadExecutor().submit<WirelessHotspotInfo> {
            manager.start(500L)
        }.get()
        assertEquals(0, info.channel)
        assertNull(info.frequencyMHz)
        assertEquals("5 GHz", info.bandLabel)
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
        )

        val info = java.util.concurrent.Executors.newSingleThreadExecutor().submit<WirelessHotspotInfo> {
            manager.start(300L)
        }.get()
        assertEquals(0, info.channel)
        assertNull(info.frequencyMHz)
        assertEquals("5 GHz", info.bandLabel)
    }

    @Test
    fun scanFrequencyProvidesAuthoritativeChannel() {
        val manager = ManualHotspotManager(
            context = context,
            ssid = "Car_Hotspot_Test",
            passphrase = "password1234",
            band = ManualHotspotBand.GHZ_5,
            channel = 0,
            security = ManualHotspotSecurity.WPA2,
            onDiagnostic = { diagnostics += it },
            driverRadioReader = { _, _ ->
                // Driver query is unavailable or suspicious
                LegacyHotspotRadio.Reading(null, "driver query errno=95")
            },
            localInterfaceLocator = { mockInterface },
        )

        val scanResult = android.net.wifi.ScanResult().apply {
            SSID = "Car_Hotspot_Test"
            BSSID = "02:00:00:00:00:01"
            frequency = 5180
        }
        shadowOf(wifi).setScanResults(listOf(scanResult))

        val info = java.util.concurrent.Executors.newSingleThreadExecutor().submit<WirelessHotspotInfo> {
            manager.start(500L)
        }.get()
        assertEquals(36, info.channel)
        assertEquals(5180, info.frequencyMHz)
        assertEquals("5 GHz", info.bandLabel)
    }

    @Test
    fun carHotspotBandMismatchWithPreferenceDoesNotAbort() {
        val manager = ManualHotspotManager(
            context = context,
            ssid = "Car_Hotspot_Test",
            passphrase = "password1234",
            band = ManualHotspotBand.GHZ_5,
            channel = 0,
            security = ManualHotspotSecurity.WPA2,
            onDiagnostic = { diagnostics += it },
            driverRadioReader = { _, _ ->
                // Driver reports car hotspot running on 2.4 GHz
                LegacyHotspotRadio.Reading(2412, null)
            },
            localInterfaceLocator = { mockInterface },
        )

        // The app must not abort when car hotspot runs on 2.4 GHz while user preference had 5 GHz;
        // it must safely report channel 0 so the iPhone can scan and connect on 2.4 GHz
        val info = java.util.concurrent.Executors.newSingleThreadExecutor().submit<WirelessHotspotInfo> {
            manager.start(500L)
        }.get()
        assertEquals(0, info.channel)
        assertEquals("5 GHz", info.bandLabel)
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
