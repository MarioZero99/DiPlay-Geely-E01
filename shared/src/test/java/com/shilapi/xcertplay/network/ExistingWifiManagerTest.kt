package com.shilapi.xcertplay.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowWifiInfo

/**
 * Attaching to a network that already exists, against the platform's own contracts.
 *
 * What is simulated here is the platform's answers, not the radio. Whether a real head unit hands
 * over an address on the network it has just joined is the one thing only the car can say; what is
 * pinned here is what DiPlay does with each answer — which network it takes, what it refuses to act
 * on, and what it publishes when the platform will not show a network's password.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ExistingWifiManagerTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val iface = NetworkInterface.getNetworkInterfaces().toList().first { !it.isLoopback }
    private val diagnostics = mutableListOf<String>()
    private var changes = 0
    private val network: Network = ShadowNetwork.newInstance(1)
    private lateinit var properties: LinkProperties
    private var savedNetworkId = -1

    @Before
    fun setUp() {
        shadowOf(wifi).setAccessWifiStatePermission(true)
        shadowOf(wifi).setChangeWifiStatePermission(true)
        savedNetworkId = addSavedNetwork()
        properties = link("192.0.2.10/24")
        shadowOf(connectivity).addNetwork(network, null)
        shadowOf(connectivity).setNetworkCapabilities(network, wifiCapabilities())
        shadowOf(connectivity).setLinkProperties(network, properties)
        shadowOf(wifi).setConnectionInfo(stationInfo("Pocket Wi-Fi", savedNetworkId))
    }

    @Test
    fun publishesTheAddressOnTheNetworkItJoinedAndTheNameThePlatformReports() {
        manager().use { manager ->
            val info = start(manager)

            assertEquals(WirelessHotspotBackend.EXISTING_WIFI, info.backend)
            assertEquals("Pocket Wi-Fi", info.ssid)
            assertEquals("192.0.2.10", info.hostAddress!!.hostAddress)
            assertEquals(iface.name, info.interfaceName)
            assertEquals(Iap2WirelessSecurity.WPA_WPA2, info.security)
            assertEquals("pocket-password", info.passphrase)
            // The router's BSSID is not this receiver's AirPlay device identity.
            assertNull(info.bssid)
        }
    }

    /**
     * The access point's own address goes to the phone as a 0x5703 hint, and only when it is one.
     *
     * The phone acts on whatever it is given, so the addresses that name no access point — an all-zero
     * one, the `02:00:00:00:00:00` placeholder, a group address, a truncated one — have to be dropped
     * here rather than sent as a hint pointing at hardware that is not there.
     */
    @Test
    fun theAccessPointAddressIsAHintAndAnUnusableOneIsNotSent() {
        shadowOf(wifi).setConnectionInfo(withBssid("a4:2b:8c:11:22:33"))
        manager().use { manager ->
            assertEquals("a42b8c112233", start(manager).accessPointBssid?.joinToString("") { "%02x".format(it) })
        }

        for (unusable in listOf(
            "00:00:00:00:00:00",
            "02:00:00:00:00:00",
            "01:00:5e:00:00:fb",
            "a4:2b:8c:11:22",
            "not-an-address",
        )) {
            shadowOf(wifi).setConnectionInfo(withBssid(unusable))
            manager().use { manager ->
                assertNull("$unusable names no access point", start(manager).accessPointBssid)
            }
        }
    }

    @Test
    fun aPasswordThePlatformHidesIsPublishedAsNoPasswordRatherThanAnEmptyOne() {
        val hidden = addSavedNetwork(passphrase = null)
        shadowOf(wifi).setConnectionInfo(stationInfo("Pocket Wi-Fi", hidden))

        manager().use { manager ->
            val info = start(manager)

            assertNull(info.passphrase)
            assertEquals(Iap2WirelessSecurity.WPA_WPA2, info.security)
            assertTrue(diagnostics.any { it.contains("credentials=absent") })
        }
    }

    @Test
    fun thePasswordNeverReachesTheDiagnostics() {
        manager().use { start(it) }

        assertTrue(diagnostics.none { it.contains("pocket-password") })
        assertTrue(diagnostics.any { it.contains("Existing Wi-Fi attached") })
        assertTrue(diagnostics.any { it.contains("credentials=present") })
    }

    /**
     * The network's name and the verdict on its key are on separate lines, and the verdict's line has
     * neither `ssid=` nor the word the report redactor keys on.
     *
     * Not tidiness. The redactor drops a line carrying a credential keyword — the whole line, not the
     * field — and `ssid` and `pass…` are both on its list. While the name and the verdict shared a
     * line, the one thing this whole path is gambled on arrived in the report as `[withheld]`, and the
     * driver had no way to learn whether this head unit hands over a saved key at all.
     */
    @Test
    fun theNetworkNameIsKeptOffTheLineThatCarriesTheVerdict() {
        manager().use { start(it) }

        val verdict = diagnostics.first { it.contains("Existing Wi-Fi details") }
        assertTrue("the verdict is on it", verdict.contains("credentials="))
        assertFalse("the name is not", verdict.contains("ssid="))
        assertFalse(
            "and neither is the word the redactor drops lines for",
            verdict.contains("assword", ignoreCase = true),
        )
    }

    @Test
    fun keepsTheLinkLocalAddressAsASecondChoiceBehindTheOneTheRouterHandedOut() {
        properties.setLinkAddresses(listOf(linkAddress("192.0.2.10/24"), linkAddress("fe80::1234/64")))

        manager().use { assertEquals("192.0.2.10", start(it).hostAddress!!.hostAddress) }
    }

    @Test
    fun aSecondWifiNetworkIsRefusedRatherThanChosenBetween() {
        val other = ShadowNetwork.newInstance(2)
        shadowOf(connectivity).addNetwork(other, null)
        shadowOf(connectivity).setNetworkCapabilities(other, wifiCapabilities())

        manager().use { manager ->
            val failure = assertThrows(IOException::class.java) { start(manager) }
            assertTrue(failure.message!!, failure.message!!.contains("2 Wi-Fi networks"))
        }
    }

    @Test
    fun transientMultipleWifiNetworksAreToleratedIfSettledWithinGracePeriod() {
        val transientNetwork = ShadowNetwork.newInstance(2)
        shadowOf(connectivity).addNetwork(transientNetwork, null)
        shadowOf(connectivity).setNetworkCapabilities(transientNetwork, wifiCapabilities())

        val executor = Executors.newSingleThreadScheduledExecutor()
        try {
            // After 250ms, remove the transient network to simulate AP teardown settling
            executor.schedule({
                shadowOf(connectivity).removeNetwork(transientNetwork)
            }, 250, TimeUnit.MILLISECONDS)

            manager().use { manager ->
                val result = start(manager, timeout = 2_000)
                assertEquals("192.0.2.10", result.hostAddress!!.hostAddress)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun anotherTransportIsNotAWifiNetwork() {
        val cellular = ShadowNetwork.newInstance(3)
        shadowOf(connectivity).addNetwork(cellular, null)
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        shadowOf(connectivity).setNetworkCapabilities(cellular, capabilities)

        manager().use { assertEquals("192.0.2.10", start(it).hostAddress!!.hostAddress) }
    }

    @Test
    fun aNetworkWithNoSavedConfigurationIsRefusedInsteadOfAnnouncedWrong() {
        shadowOf(wifi).setConnectionInfo(stationInfo("Somewhere Else", networkId = -1))

        manager().use { manager ->
            val failure = assertThrows(IOException::class.java) { start(manager) }
            assertTrue(failure.message!!, failure.message!!.contains("Somewhere Else"))
        }
    }

    @Test
    fun aNetworkWithNoAddressYetIsWaitedForAndThenNamedInTheTimeout() {
        val withoutAnAddress = LinkProperties().apply { interfaceName = iface.name }
        shadowOf(connectivity).setLinkProperties(network, withoutAnAddress)

        manager().use { manager ->
            val failure = assertThrows(IOException::class.java) { start(manager, timeout = 300) }
            assertTrue(failure.message!!, failure.message!!.contains("an address on the Wi-Fi network"))
        }
    }

    @Test
    fun losingTheNetworkReportsTheChangeOnceAndNotAgainAfterClose() {
        val manager = manager()
        start(manager)
        val callbacks = shadowOf(connectivity).networkCallbacks.toList()
        assertTrue(callbacks.isNotEmpty())

        callbacks.forEach { it.onLost(network) }
        callbacks.forEach { it.onLost(network) }
        assertEquals(1, changes)
        assertTrue(diagnostics.any { it.contains("network lost") })

        manager.close()
        callbacks.forEach { it.onLost(network) }
        assertEquals(1, changes)
    }

    @Test
    fun anAddressChangeRestartsTheSessionButADnsChangeDoesNot() {
        manager().use { manager ->
            start(manager)
            val callbacks = shadowOf(connectivity).networkCallbacks.toList()

            properties.setDnsServers(listOf(InetAddress.getByName("192.0.2.1")))
            callbacks.forEach { it.onLinkPropertiesChanged(network, properties) }
            assertEquals(0, changes)

            callbacks.forEach { it.onLinkPropertiesChanged(network, link("192.0.2.11/24")) }
            assertEquals(1, changes)
        }
    }

    @Test
    fun closingUnregistersTheCallbackItRegistered() {
        val manager = manager()
        start(manager)
        assertEquals(1, shadowOf(connectivity).networkCallbacks.size)

        manager.close()

        assertEquals(0, shadowOf(connectivity).networkCallbacks.size)
    }

    @Test
    fun anIpv6OnlyNetworkIsPublishedScopedToTheInterface() {
        val only = LinkProperties().apply {
            interfaceName = iface.name
            setLinkAddresses(listOf(linkAddress("fe80::1234/64")))
        }
        shadowOf(connectivity).setLinkProperties(network, only)

        manager().use { manager ->
            val host = start(manager).hostAddress as Inet6Address
            assertTrue(host.isLinkLocalAddress)
            assertEquals(iface.index, host.scopeId)
        }
    }

    private fun manager() = ExistingWifiManager(
        context = context,
        onDiagnostic = diagnostics::add,
        onNetworkChanged = { changes++ },
    )

    /** A saved network: where the security type and the password both come from. */
    private fun addSavedNetwork(
        ssid: String = "Pocket Wi-Fi",
        passphrase: String? = "pocket-password",
        open: Boolean = false,
    ): Int = wifi.addNetwork(
        WifiConfiguration().apply {
            this.SSID = "\"$ssid\""
            this.preSharedKey = passphrase?.let { "\"$it\"" }
            allowedKeyManagement.clear()
            allowedKeyManagement.set(
                if (open) WifiConfiguration.KeyMgmt.NONE else WifiConfiguration.KeyMgmt.WPA_PSK,
            )
        },
    )

    /** The shadow quotes what it is given the way the platform stores it; unquoted here. */
    private fun stationInfo(ssid: String, networkId: Int): WifiInfo =
        ShadowWifiInfo.newInstance().also {
            shadowOf(it).setSSID(ssid)
            shadowOf(it).setNetworkId(networkId)
            shadowOf(it).setFrequency(5180)
        }

    private fun withBssid(bssid: String): WifiInfo =
        stationInfo("Pocket Wi-Fi", savedNetworkId).also { shadowOf(it).setBSSID(bssid) }

    private fun wifiCapabilities(): NetworkCapabilities =
        ShadowNetworkCapabilities.newInstance().also {
            shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        }

    private fun link(address: String): LinkProperties = LinkProperties().apply {
        interfaceName = iface.name
        setLinkAddresses(listOf(linkAddress(address)))
    }

    /**
     * LinkAddress has no usable constructor in the SDK stubs, which is what this test compiles
     * against; the platform's own is the same one-address-and-prefix-length pair.
     */
    private fun linkAddress(cidr: String): LinkAddress {
        val constructor = LinkAddress::class.java.getDeclaredConstructor(
            InetAddress::class.java,
            Int::class.javaPrimitiveType,
        )
        constructor.isAccessible = true
        return constructor.newInstance(
            InetAddress.getByName(cidr.substringBefore('/')),
            cidr.substringAfter('/').toInt(),
        )
    }

    private fun start(manager: ExistingWifiManager, timeout: Long = 1_000): WirelessHotspotInfo {
        val executor = Executors.newSingleThreadExecutor()
        try {
            return executor.submit<WirelessHotspotInfo> { manager.start(timeout) }.get(5, TimeUnit.SECONDS)
        } catch (error: ExecutionException) {
            throw error.cause!!
        } finally {
            executor.shutdownNow()
        }
    }
}
