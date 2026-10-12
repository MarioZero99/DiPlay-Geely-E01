package com.shilapi.xcertplay.network

import java.net.InetAddress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Borrowing is the whole point of [MdnsResponder]: a second socket on the same port is refused for the
 * rest of the process, so a bring-up that opened its own would end discovery for every attempt after
 * it. Reverting this to one socket per attempt has to turn [theSameAddressIsOpenedOnce] red.
 */
class MdnsResponderTest {
    private val addressA = InetAddress.getByName("169.254.10.10")
    private val addressB = InetAddress.getByName("169.254.10.11")
    private val openedFor = mutableListOf<InetAddress>()
    private val opened = mutableListOf<StubResponderSocket>()
    private var realFactory: ((InetAddress, String) -> MdnsResponderSocket)? = null

    @Before fun countOpensInsteadOfPerformingThem() {
        realFactory = MdnsResponder.openSocket
        MdnsResponder.openSocket = { address, _ ->
            openedFor.add(address)
            StubResponderSocket().also { opened.add(it) }
        }
    }

    @After fun dropTheProcessSocket() {
        realFactory?.let { MdnsResponder.openSocket = it }
        realFactory = null
        MdnsResponder.close()
    }

    @Test fun theSameAddressIsOpenedOnce() {
        val first = MdnsResponder.acquire(addressA, "carplay-1")
        val second = MdnsResponder.acquire(addressA, "carplay-1")

        assertSame(first, second)
        assertEquals(listOf(addressA), openedFor)
    }

    @Test fun aChangedAddressGivesUpTheOldSocketBeforeOpeningTheNewOne() {
        MdnsResponder.acquire(addressA, "carplay-1")
        MdnsResponder.acquire(addressB, "carplay-1")

        assertEquals(listOf(addressA, addressB), openedFor)
        assertTrue(opened[0].closed)
        assertFalse(opened[1].closed)
    }

    @Test fun closeDropsTheSocketAndTheNextBorrowOpensAFreshOne() {
        MdnsResponder.acquire(addressA, "carplay-1")

        MdnsResponder.close()
        assertTrue(opened.single().closed)

        MdnsResponder.acquire(addressA, "carplay-1")
        assertEquals(2, opened.size)
        assertFalse(opened[1].closed)
    }

    /** The handle the app closes with reaches the same socket, so 5353 goes back when DiPlay does. */
    @Test fun theProcessHandleReleasesTheSocket() {
        MdnsResponder.acquire(addressA, "carplay-1")

        WirelessDiscovery.releaseResponder()

        assertTrue(opened.single().closed)
    }
}
