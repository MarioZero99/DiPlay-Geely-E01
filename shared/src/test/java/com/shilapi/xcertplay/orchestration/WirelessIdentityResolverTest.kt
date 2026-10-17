package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import com.shilapi.xcertplay.transport.Iap2BluetoothLink
import com.shilapi.xcertplay.transport.PairedTarget
import com.shilapi.xcertplay.transport.WirelessBluetoothHop
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WirelessIdentityResolverTest {

    private class FakeLink(
        override val localName: String? = null,
        override val localAddress: String? = "00:11:22:33:44:55",
    ) : Iap2BluetoothLink {
        override fun target(): PairedTarget? = null
        override fun open(): BlockingDuplexByteStream = throw UnsupportedOperationException()
    }

    @Test
    fun returnsLinkLocalNameWhenProvided() {
        val link = FakeLink(localName = "GEELY-Dongle")
        val resolved = WirelessIdentityResolver.resolveRadioName(
            link = link,
            hopType = WirelessBluetoothHop.USB_ADAPTER,
        )
        assertEquals("GEELY-Dongle", resolved)
    }

    @Test
    fun returnsFallbackCarHopNameWhenLinkLocalNameIsNull() {
        val link = FakeLink(localName = null)
        val resolved = WirelessIdentityResolver.resolveRadioName(
            link = link,
            hopType = WirelessBluetoothHop.CAR,
            fallbackCarHopName = "GEELY-CarRadio",
        )
        assertEquals("GEELY-CarRadio", resolved)
    }

    @Test
    fun prefersLinkLocalNameOverFallbackCarHopName() {
        val link = FakeLink(localName = "GEELY-Dongle")
        val resolved = WirelessIdentityResolver.resolveRadioName(
            link = link,
            hopType = WirelessBluetoothHop.USB_ADAPTER,
            fallbackCarHopName = "GEELY-CarRadio",
        )
        assertEquals("GEELY-Dongle", resolved)
    }

    @Test
    fun throwsUsbAdapterSpecificExceptionWhenUsbAdapterHasNoName() {
        val link = FakeLink(localName = null)
        try {
            WirelessIdentityResolver.resolveRadioName(
                link = link,
                hopType = WirelessBluetoothHop.USB_ADAPTER,
                fallbackCarHopName = null,
            )
            fail("Expected IOException")
        } catch (e: IOException) {
            assertTrue(
                "Error message should mention USB Bluetooth adapter: ${e.message}",
                e.message?.contains("USB Bluetooth adapter did not report its broadcast name") == true,
            )
        }
    }

    @Test
    fun throwsCarSpecificExceptionWhenCarHopHasNoName() {
        val link = FakeLink(localName = null)
        try {
            WirelessIdentityResolver.resolveRadioName(
                link = link,
                hopType = WirelessBluetoothHop.CAR,
                fallbackCarHopName = null,
            )
            fail("Expected IOException")
        } catch (e: IOException) {
            assertTrue(
                "Error message should mention head unit: ${e.message}",
                e.message?.contains("The head unit would not say what its Bluetooth is called") == true,
            )
        }
    }

    @Test
    fun treatsBlankNamesAsNull() {
        val link = FakeLink(localName = "   ")
        try {
            WirelessIdentityResolver.resolveRadioName(
                link = link,
                hopType = WirelessBluetoothHop.USB_ADAPTER,
                fallbackCarHopName = "  ",
            )
            fail("Expected IOException for blank names")
        } catch (e: IOException) {
            assertTrue(e.message?.contains("USB Bluetooth adapter") == true)
        }
    }
}
