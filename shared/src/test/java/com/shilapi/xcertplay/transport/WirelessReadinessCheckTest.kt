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
        assertEquals(listOf(WirelessGap.HOTSPOT_ON), on.blocking)
        assertTrue(off.ready)
    }

    /**
     * And the other half of that answer: where the firmware lets DiPlay release the access point
     * itself, the hotspot being on is still worth *saying* — the driver's Wi-Fi is about to go down —
     * but it is not a reason to refuse. Blocking on it here would demand of the driver exactly what
     * the code that follows was about to do.
     */
    @Test fun theSameNetworkModeIsAllowedWhileTheHotspotCanBeReleased() {
        val report = WirelessReadinessCheck.assess(
            input(
                mode = WirelessHotspotMode.EXISTING_WIFI,
                hotspotOn = true,
                hotspotCanBeReleased = true,
                hop = WirelessBluetoothHop.CAR,
                phoneChosen = true,
            ),
        )

        assertEquals(listOf(WirelessGap.HOTSPOT_ON), report.gaps)
        assertTrue("the release is the connect path's job, not a gap", report.ready)
        assertTrue(report.blocking.isEmpty())
    }

    /**
     * The deadlock this route used to have on a single-radio head unit: the hotspot holds the radio,
     * so the car cannot be associated, so the association gap is present — and it is the one gap the
     * driver could not close without first doing what DiPlay was refusing to do. It stays on the page
     * as a fact and stops blocking the moment the hotspot is the thing holding the radio.
     */
    @Test fun theAssociationIsNotDemandedWhileTheHotspotHoldsTheRadio() {
        val report = WirelessReadinessCheck.assess(
            input(
                mode = WirelessHotspotMode.EXISTING_WIFI,
                hotspotOn = true,
                hotspotCanBeReleased = true,
                hop = WirelessBluetoothHop.CAR,
                phoneChosen = true,
                carWifiAssociated = false,
            ),
        )

        assertEquals(
            listOf(WirelessGap.HOTSPOT_ON, WirelessGap.CAR_WIFI_DISCONNECTED),
            report.gaps,
        )
        assertTrue("neither is the driver's to fix before connecting", report.ready)
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
        assertEquals(listOf(WirelessGap.CAR_WIFI_DISCONNECTED), disconnected.blocking)
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
        /**
         * Whether this build can switch the car's hotspot off by itself. False is the older answer —
         * the driver has to do it — and is the default so the cases below keep testing the gap.
         */
        hotspotCanBeReleased: Boolean = false,
        hop: WirelessBluetoothHop?,
        phoneChosen: Boolean = false,
        carBluetoothOn: Boolean = true,
        adapterPlugged: Boolean = false,
        adapter: WirelessAdapterStatus? = null,
        mode: WirelessHotspotMode = WirelessHotspotMode.MANUAL,
        carWifiAssociated: Boolean = true,
    ) = WirelessReadinessInput(
        credentialsSaved = credentialsSaved,
        hotspotOn = hotspotOn,
        hotspotCanBeReleased = hotspotCanBeReleased,
        hop = hop,
        phoneChosen = phoneChosen,
        carBluetoothOn = carBluetoothOn,
        adapterPlugged = adapterPlugged,
        adapter = adapter,
        mode = mode,
        carWifiAssociated = carWifiAssociated,
    )
}
