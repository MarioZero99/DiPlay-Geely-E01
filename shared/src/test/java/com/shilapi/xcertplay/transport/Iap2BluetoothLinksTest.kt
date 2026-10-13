package com.shilapi.xcertplay.transport

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The radio choice, on the side of it that stays in this tree.
 *
 * The other half of `Iap2BluetoothLinkTest` — that a chosen adapter is kept, that the phone the adapter
 * paired is the one opened — needs the adapter's HCI stack and is tested with it, in the adapter's own
 * repository. What is left here is the promise that matters to a build without one: a choice of a radio that
 * is not there fails, and never quietly reaches for the car's instead.
 */
class Iap2BluetoothLinksTest {
    /** Stands in for the car's own Bluetooth hop, which is what a run falls back to. */
    private class CarLink : Iap2BluetoothLink {
        override val localAddress: String = "F0:00:00:00:00:01"

        override fun target(): PairedTarget = PairedTarget("iPhone", "AA:00:00:00:00:01")

        override fun open(): BlockingDuplexByteStream =
            throw IOException("the car link is not opened by these tests")
    }

    @Test fun choosingTheCarWithNoAdapterLeavesTheCarInCharge() {
        var carCalls = 0

        val link = Iap2BluetoothLinks.chooseForWireless(WirelessBluetoothHop.CAR, null, null, {}) {
            carCalls += 1
            CarLink()
        }

        assertTrue(link is CarLink)
        assertEquals(1, carCalls)
    }

    @Test fun choosingTheAdapterWhenItIsNotRunningDoesNotFallBackToTheCar() {
        var carCalls = 0

        val failure = runCatching {
            Iap2BluetoothLinks.chooseForWireless(
                WirelessBluetoothHop.USB_ADAPTER, null, null, {}, externalRouteEnabled = true,
            ) {
                carCalls += 1
                CarLink()
            }
        }.exceptionOrNull()

        assertEquals(0, carCalls)
        assertTrue(failure is IOException)
    }

    /** What a shipping build does: the radio is gone, so a stale choice of it can only fail. */
    @Test fun choosingTheAdapterWithTheRouteOffFailsAndNeverReachesTheCar() {
        var carCalls = 0

        val failure = runCatching {
            Iap2BluetoothLinks.chooseForWireless(
                WirelessBluetoothHop.USB_ADAPTER, null, null, {}, externalRouteEnabled = false,
            ) {
                carCalls += 1
                CarLink()
            }
        }.exceptionOrNull()

        assertEquals(0, carCalls)
        assertEquals("External Bluetooth route is off", failure?.message)
    }
}
