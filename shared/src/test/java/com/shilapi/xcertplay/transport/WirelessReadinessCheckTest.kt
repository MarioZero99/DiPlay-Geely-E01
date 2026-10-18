package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.transport.adapter.WirelessAdapterStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The radio choice is this tree's, not the adapter's, so what is checked here is the readiness rule: which
 * gaps are reported, and that the adapter's own line is carried through to the page when there is one.
 */
class WirelessReadinessCheckTest {
    private val paired = WirelessAdapterStatus(
        plugged = true,
        named = true,
        flowEffective = true,
        sentence = "paired",
    )
    private val waiting = WirelessAdapterStatus(
        plugged = true,
        named = true,
        flowEffective = false,
        sentence = "waiting for the phone",
    )

    @Test fun aReadyCarPathHasNoGaps() {
        val report = WirelessReadinessCheck.assess(input(hop = WirelessBluetoothHop.CAR, phoneChosen = true))
        assertTrue(report.ready)
    }

    @Test fun missingHotspotDetailsAreListedBeforeTheRadio() {
        val report = WirelessReadinessCheck.assess(
            input(credentialsSaved = false, hotspotOn = false, hop = null),
        )
        assertEquals(
            listOf(WirelessGap.HOTSPOT_CREDENTIALS, WirelessGap.HOTSPOT_OFF, WirelessGap.RADIO_NOT_CHOSEN),
            report.gaps,
        )
    }

    @Test fun theCarRadioOffOrWithoutAPhoneIsItsOwnGap() {
        val off = WirelessReadinessCheck.assess(
            input(hop = WirelessBluetoothHop.CAR, carBluetoothOn = false, phoneChosen = false),
        )
        assertEquals(listOf(WirelessGap.CAR_BLUETOOTH_OFF, WirelessGap.PHONE_NOT_CHOSEN), off.gaps)
    }

    @Test fun anAdapterThatHasNotFinishedBlocksAndKeepsTheStuckStep() {
        val report = WirelessReadinessCheck.assess(
            input(hop = WirelessBluetoothHop.USB_ADAPTER, adapterPlugged = true, adapter = waiting),
        )
        assertEquals(listOf(WirelessGap.DONGLE_NOT_READY), report.gaps)
        assertEquals(waiting, report.adapter)
    }

    @Test fun anEffectiveAdapterStillAsksForThePhoneToBeChosen() {
        val report = WirelessReadinessCheck.assess(
            input(hop = WirelessBluetoothHop.USB_ADAPTER, phoneChosen = false, adapterPlugged = true, adapter = paired),
        )
        assertEquals(listOf(WirelessGap.PHONE_NOT_CHOSEN), report.gaps)
    }

    @Test fun aPluggedAdapterIsReportedEvenWhenTheCarRadioWasChosen() {
        val report = WirelessReadinessCheck.assess(
            input(hop = WirelessBluetoothHop.CAR, phoneChosen = true, adapterPlugged = true, adapter = waiting),
        )
        assertTrue(report.ready)
        assertEquals(waiting, report.adapter)
    }

    /**
     * One radio cannot be a station and an access point at once, so the two wireless modes want opposite
     * things of the car's own hotspot. Reading the hotspot the same way for both is what made the same
     * network mode unreachable while the car's hotspot was on.
     */
    @Test fun theSameNetworkModeIsBlockedWhileTheCarsOwnHotspotIsStillOn() {
        val on = WirelessReadinessCheck.assess(
            input(mode = WirelessHotspotMode.EXISTING_WIFI, hotspotOn = true, hop = WirelessBluetoothHop.CAR, phoneChosen = true),
        )
        val off = WirelessReadinessCheck.assess(
            input(mode = WirelessHotspotMode.EXISTING_WIFI, hotspotOn = false, hop = WirelessBluetoothHop.CAR, phoneChosen = true),
        )

        assertEquals(listOf(WirelessGap.HOTSPOT_ON), on.gaps)
        assertTrue(off.ready)
    }

    @Test fun theSameNetworkModeIsBlockedWhenCarWifiNotAssociated() {
        val disconnected = WirelessReadinessCheck.assess(
            input(
                mode = WirelessHotspotMode.EXISTING_WIFI,
                hotspotOn = false,
                hop = WirelessBluetoothHop.CAR,
                phoneChosen = true,
                carWifiAssociated = false,
            ),
        )
        assertEquals(listOf(WirelessGap.CAR_WIFI_DISCONNECTED), disconnected.gaps)
        assertFalse(disconnected.ready)
    }

    /** The other direction: a mode that is happy with the hotspot on must not be told to turn it off. */
    @Test fun onlyTheSameNetworkModeCaresAboutTheCarsOwnHotspot() {
        for (mode in listOf(
            WirelessHotspotMode.MANUAL,
            WirelessHotspotMode.LOCAL_ONLY_HOTSPOT,
        )) {
            val report = WirelessReadinessCheck.assess(
                input(mode = mode, hotspotOn = true, hop = WirelessBluetoothHop.CAR, phoneChosen = true),
            )

            assertFalse(mode.name, report.gaps.contains(WirelessGap.HOTSPOT_ON))
        }
    }

    private fun input(
        credentialsSaved: Boolean = true,
        hotspotOn: Boolean? = true,
        hop: WirelessBluetoothHop?,
        phoneChosen: Boolean = false,
        carBluetoothOn: Boolean = true,
        adapterPlugged: Boolean = false,
        adapter: WirelessAdapterStatus? = null,
        mode: WirelessHotspotMode = WirelessHotspotMode.MANUAL,
        carWifiAssociated: Boolean = true,
    ) = WirelessReadinessInput(credentialsSaved, hotspotOn, hop, phoneChosen, carBluetoothOn, adapterPlugged, adapter, mode, carWifiAssociated)
}
