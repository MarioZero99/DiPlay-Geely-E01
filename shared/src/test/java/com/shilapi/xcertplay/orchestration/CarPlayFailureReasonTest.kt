package com.shilapi.xcertplay.orchestration

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A hotspot the car will not give up cannot be retried into working: the head unit is the one holding
 * it, and DiPlay has no permission to switch it off. Left filed as a general bring-up failure it went
 * round the retry ladder instead — one attempt every thirty seconds, the same sentence each time.
 * Which failures are not worth retrying is decided here, in one place the screen can read.
 */
class CarPlayFailureReasonTest {
    @Test
    fun aHotspotTheCarWillNotReleaseIsNotARetryableBringUpFailure() {
        assertEquals(
            CarPlayFailureReason.CAR_HOTSPOT_STAYS_ON,
            failureReasonFor(
                CarHotspotStaysOnException("the firmware does not give DiPlay permission to switch it off"),
            ),
        )
    }

    /** Every other fault on this route still gets its retry — that is what the ladder is for. */
    @Test
    fun everyOtherBringUpFailureKeepsRetrying() {
        assertEquals(
            CarPlayFailureReason.BRING_UP_FAILED,
            failureReasonFor(java.io.IOException("something else went wrong")),
        )
    }

    /**
     * A phone that never joined gets the sentence that says so, not the one about the other address.
     *
     * From the listening socket the two are the same event, and the screen used to report both as
     * "retrying with the other network address" — a claim that is false here, because this failure is
     * the one case that deliberately does not move the address family.
     */
    @Test
    fun aPhoneThatNeverJoinedIsNotReportedAsAnAddressFamilyProblem() {
        assertEquals(
            CarPlayFailureReason.WIRELESS_PHONE_NOT_ON_NETWORK,
            failureReasonFor(WirelessPhoneNotOnNetworkException("no device joined the access point")),
        )
        assertEquals(
            CarPlayFailureReason.FIRST_TCP_TIMEOUT,
            failureReasonFor(FirstTcpTimeoutException("no AirPlay TCP")),
        )
    }
}
