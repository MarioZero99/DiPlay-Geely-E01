package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.transport.adapter.WirelessAdapterStatus
import org.junit.Assert.assertEquals
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

    private fun input(
        credentialsSaved: Boolean = true,
        hotspotOn: Boolean = true,
        hop: WirelessBluetoothHop?,
        phoneChosen: Boolean = false,
        carBluetoothOn: Boolean = true,
        adapterPlugged: Boolean = false,
        adapter: WirelessAdapterStatus? = null,
    ) = WirelessReadinessInput(credentialsSaved, hotspotOn, hop, phoneChosen, carBluetoothOn, adapterPlugged, adapter)
}
