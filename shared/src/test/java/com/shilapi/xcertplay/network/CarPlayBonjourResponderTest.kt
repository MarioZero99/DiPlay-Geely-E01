package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import java.net.InetAddress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A bring-up has to borrow the process's responder rather than open its own.
 *
 * Two runs in a row is the ordinary case — a retry, or the display changing — and a second socket for
 * 5353 is refused on this platform, so the second run's own socket would end discovery for the rest of
 * the process. Putting the create back inside the run has to turn
 * [aSecondBringUpOnTheSameAddressBorrowsTheSameSocket] red.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CarPlayBonjourResponderTest {
    private val address = InetAddress.getByName("169.254.10.10")
    private val opened = mutableListOf<StubResponderSocket>()
    private var realFactory: ((InetAddress, String) -> MdnsResponderSocket)? = null

    @Before fun countOpensInsteadOfPerformingThem() {
        realFactory = MdnsResponder.openSocket
        MdnsResponder.openSocket = { _, _ ->
            StubResponderSocket().also { opened.add(it) }
        }
    }

    @After fun dropTheProcessSocket() {
        realFactory?.let { MdnsResponder.openSocket = it }
        realFactory = null
        MdnsResponder.close()
    }

    @Test fun aSecondBringUpOnTheSameAddressBorrowsTheSameSocket() {
        val first = bonjour()
        val second = bonjour()

        try {
            first.start()
            first.close()
            second.start()

            assertEquals(1, opened.size)
            assertFalse(opened.single().closed)
            // Both runs published; the second took the record over from the first.
            assertEquals(2, opened.single().published.size)
        } finally {
            runCatching { first.close() }
            runCatching { second.close() }
        }
    }

    @Test fun closingARunUnpublishesItsRecordAndListenerWithoutClosingTheSocket() {
        val bonjour = bonjour()

        try {
            bonjour.start()
            val socket = opened.single()
            assertEquals(1, socket.published.size)

            bonjour.close()

            assertEquals(1, socket.retracted.size)
            assertEquals(0, socket.listeners)
            assertFalse(socket.closed)
        } finally {
            runCatching { bonjour.close() }
        }
    }

    /**
     * jmDNS retracts a record by service name and type, which every run of the same accessory shares,
     * so a superseded run letting go would otherwise take the newer run's record down with it.
     */
    @Test fun aSupersededRunDoesNotTakeTheNextRunsRecordAway() {
        val first = bonjour()
        val second = bonjour()

        try {
            first.start()
            second.start()
            val socket = opened.single()

            first.close()

            // Only the latest record may be retracted, so the older run's let-go never reached the socket.
            assertEquals(2, socket.published.size)
            assertEquals(0, socket.retracted.size)
        } finally {
            runCatching { first.close() }
            runCatching { second.close() }
        }
    }

    private fun bonjour() = CarPlayBonjour(
        context = RuntimeEnvironment.getApplication(),
        config = CONFIG,
        identity = IDENTITY,
        advertisedHost = address.hostAddress,
        useInterfaceMdns = true,
    )

    private companion object {
        val CONFIG = AirPlayConfig(
            deviceName = "xcertplay",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:02",
            sourceVersion = "366.0",
            main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
            model = "LIVI",
        )
        val IDENTITY = AirPlayIdentity(
            privateKey = ByteArray(32),
            publicKey = byteArrayOf(0x01, 0x23, 0xab.toByte()),
            pairingId = "pairing-1",
        )
    }
}
