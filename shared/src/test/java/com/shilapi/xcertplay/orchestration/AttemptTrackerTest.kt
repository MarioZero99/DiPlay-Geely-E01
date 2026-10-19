package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.WirelessBluetoothHop
import com.shilapi.xcertplay.transport.WirelessGap
import com.shilapi.xcertplay.transport.WirelessHotspotMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One attempt, one record — and the record says which of the four ways it ended.
 *
 * The point of these tests is not the tracker's plumbing. It is that a report can be counted: "ten
 * attempts, six died on `PAIRING`" is a sentence somebody can only write if every attempt produced
 * exactly one line, with a rung and a reason, whether it failed, was refused, or never started at all.
 */
class AttemptTrackerTest {

    private class Clock(var uptime: Long = 1_000L, var wall: Long = 1_760_706_131_480L)

    private fun tracker(
        clock: Clock,
        transport: CarPlayTransport = CarPlayTransport.WIRELESS,
    ) = AttemptTracker(
        id = "9f2c1a7e",
        environment = AttemptEnvironment("3f9a1c02", "0.4.2-geely", 42, "Geely E01", 22),
        transport = transport,
        mode = WirelessHotspotMode.MANUAL,
        hop = WirelessBluetoothHop.CAR,
        config = AttemptConfig(fps = 30, hevc = true, scaleTenths = 100),
        generation = 3,
        retry = 1,
        uptime = { clock.uptime },
        wallClock = { clock.wall },
    )

    /** A failure names a rung and a reason, and the two are the ones the screen was showing. */
    @Test
    fun aFailureNamesTheRungItStoppedOnAndWhy() {
        val clock = Clock()
        val attempt = tracker(clock)
        attempt.step(CarPlayStatus.DiscoveringMfi)
        clock.uptime += 840
        attempt.step(CarPlayStatus.WaitingForPairedIphone)
        clock.uptime += 14_480
        attempt.step(CarPlayStatus.AttachingNetwork)
        clock.uptime += 20_000

        attempt.fail(CarPlayFailureReason.BRING_UP_FAILED, "rfcomm open failed", reset = false)

        val record = checkNotNull(attempt.record)
        assertEquals(AttemptResult.FAIL, record.result)
        assertEquals(ConnectionStep.NETWORK, record.at)
        assertEquals("BRING_UP_FAILED", record.why)
        assertEquals("W/NETWORK/BRING_UP_FAILED", record.code)
        assertEquals(listOf("MFI", "PAIRING", "NETWORK"), record.steps.map { it.step.name })
        assertEquals(listOf(0L, 840L, 15_320L), record.steps.map { it.millis })
        assertEquals(35_320L, record.durationMillis)
    }

    /**
     * A session that comes up closes the attempt there and then.
     *
     * The record used to be written when the session ended, which made every successful attempt as long
     * as the drive. The number a reader wants is how long the connect took.
     */
    @Test
    fun aSessionClosesTheAttemptAtTheMomentItComesUp() {
        val clock = Clock()
        val attempt = tracker(clock)
        attempt.step(CarPlayStatus.DiscoveringMfi)
        clock.uptime += 9_000
        attempt.step(CarPlayStatus.WirelessActive)

        val record = checkNotNull(attempt.record)
        assertEquals(AttemptResult.OK, record.result)
        assertEquals(ConnectionStep.SESSION, record.at)
        assertEquals("-", record.why)
        assertEquals("W/SESSION/-", record.code)
        assertEquals(9_000L, record.durationMillis)

        // The session ends an hour later. That is the session's business, not this attempt's.
        clock.uptime += 3_600_000
        attempt.step(CarPlayStatus.ControlEnded)
        assertEquals(9_000L, attempt.record?.durationMillis)
        assertEquals(AttemptResult.OK, attempt.record?.result)
    }

    /** A refusal that never reached the ladder still produces a line, and it carries the gap. */
    @Test
    fun aRefusalIsARecordWithTheGapThatStoppedIt() {
        val attempt = tracker(Clock())

        attempt.block(WirelessGap.HOTSPOT_OFF)

        val record = checkNotNull(attempt.record)
        assertEquals(AttemptResult.BLOCKED, record.result)
        assertEquals("HOTSPOT_OFF", record.why)
        assertEquals(listOf(WirelessGap.HOTSPOT_OFF), record.gaps)
        assertNull(record.at)
        assertTrue(record.steps.isEmpty())
        assertEquals("W/-/HOTSPOT_OFF", record.code)
    }

    /** The settings refusal: started, no controller, no rung — the sentence the driver saw is the evidence. */
    @Test
    fun anAttemptThatNeverGotAControllerIsAbandonedAndSaysWhy() {
        val attempt = tracker(Clock())
        attempt.abandon("fps=0 is not a frame rate")
        val record = checkNotNull(attempt.record)
        assertEquals(AttemptResult.ABANDONED, record.result)
        assertEquals("-", record.why)
        assertEquals("fps=0 is not a frame rate", record.detail)
    }

    /** Exactly one record per attempt, whichever hook gets there first. */
    @Test
    fun theSecondOutcomeIsIgnored() {
        val attempt = tracker(Clock())
        attempt.step(CarPlayStatus.DiscoveringMfi)
        attempt.fail(CarPlayFailureReason.MFI_USB_PERMISSION_DENIED, null)
        val first = attempt.record

        assertTrue(attempt.closed)
        attempt.abandon("late")
        attempt.fail(CarPlayFailureReason.BRING_UP_FAILED, null)
        assertEquals(first, attempt.record)
        assertEquals("MFI_USB_PERMISSION_DENIED", attempt.record?.why)
    }

    /** A status that names no rung on this transport changes nothing. */
    @Test
    fun aStatusOffThisLadderIsNotARung() {
        val attempt = tracker(Clock(), transport = CarPlayTransport.WIRED)
        attempt.step(CarPlayStatus.StartingHotspot)
        assertNull(attempt.at)
        assertNull(attempt.record)
        assertFalse(attempt.closed)
    }

    /** Detail is the one free-text field; 120 characters of it, no more. */
    @Test
    fun detailIsTruncated() {
        val attempt = tracker(Clock())
        attempt.abandon("x".repeat(500))
        assertEquals(120, attempt.record?.detail?.length)
    }

    /**
     * The line, exactly — field order included.
     *
     * Reading a file of these with `cut` or `jq` assumes the columns stand still, so this test is a
     * golden line rather than a field-by-field assertion: a reordered key is a change somebody's script
     * has to notice.
     */
    @Test
    fun aRecordIsOneLineWithTheFieldsInAFixedOrder() {
        val record = AttemptRecord(
            id = "9f2c1a7e",
            generation = 3,
            retry = 1,
            startedWallClockMillis = 1_760_706_131_480L,
            startedUptimeMillis = 812_345L,
            durationMillis = 15_320L,
            environment = AttemptEnvironment("3f9a1c02", "0.4.2-geely", 42, "Geely E01", 22),
            transport = CarPlayTransport.WIRELESS,
            mode = WirelessHotspotMode.MANUAL,
            hop = WirelessBluetoothHop.CAR,
            gaps = emptyList(),
            steps = listOf(
                AttemptStep(ConnectionStep.MFI, 840),
                AttemptStep(ConnectionStep.HOTSPOT, 1_520),
                AttemptStep(ConnectionStep.PAIRING, 0),
            ),
            at = ConnectionStep.PAIRING,
            result = AttemptResult.FAIL,
            why = "BRING_UP_FAILED",
            detail = "rfcomm open failed",
            reset = false,
            config = AttemptConfig(fps = 30, hevc = true, scaleTenths = 100),
        )

        assertEquals(
            "{\"v\":1,\"id\":\"9f2c1a7e\",\"gen\":3,\"retry\":1,\"t\":1760706131480,\"up\":812345," +
                "\"install\":\"3f9a1c02\",\"ver\":\"0.4.2-geely\",\"code\":42,\"model\":\"Geely E01\"," +
                "\"api\":22,\"transport\":\"WIRELESS\",\"mode\":\"MANUAL\",\"hop\":\"CAR\",\"gaps\":[]," +
                "\"steps\":[{\"s\":\"MFI\",\"ms\":840},{\"s\":\"HOTSPOT\",\"ms\":1520}," +
                "{\"s\":\"PAIRING\",\"ms\":0}],\"at\":\"PAIRING\",\"result\":\"FAIL\"," +
                "\"why\":\"BRING_UP_FAILED\",\"detail\":\"rfcomm open failed\",\"ms\":15320," +
                "\"reset\":false,\"cfg\":{\"fps\":30,\"codec\":\"HEVC\",\"res\":100}}",
            encodeAttempt(record),
        )
    }

    /**
     * A `detail` carrying a quote or a newline does not split the record.
     *
     * The controller's messages are not written for this file — an exception message with a line break
     * in it would end the JSON line early and turn one attempt into two unreadable ones.
     */
    @Test
    fun aDetailWithQuotesAndNewlinesStaysOneLine() {
        val record = AttemptRecord(
            id = "9f2c1a7e",
            generation = 0,
            retry = 0,
            startedWallClockMillis = 0,
            startedUptimeMillis = 0,
            durationMillis = 0,
            environment = AttemptEnvironment("3f9a1c02", "0.4.2", 1, "Head Unit", 19),
            transport = CarPlayTransport.WIRED,
            mode = null,
            hop = null,
            gaps = emptyList(),
            steps = emptyList(),
            at = null,
            result = AttemptResult.FAIL,
            why = "BRING_UP_FAILED",
            detail = "said \"no\"\nand stopped",
            reset = true,
            config = AttemptConfig(fps = 60, hevc = false, scaleTenths = 100),
        )

        val line = encodeAttempt(record)
        assertFalse(line.contains('\n'))
        assertTrue(line.contains("\\\"no\\\"\\nand stopped"))
        assertEquals("W/-/BRING_UP_FAILED", record.code)
    }

    /**
     * The three vocabularies the record is written in, pinned.
     *
     * A rename is allowed; a rename that nobody noticed is not, because the file already on somebody's
     * laptop counts attempts by these names. Changing one here is the moment to decide how the old
     * lines are read.
     */
    @Test
    fun theVocabulariesArePinned() {
        assertEquals(
            listOf("MFI", "DEVICE", "HOTSPOT", "PAIRING", "NETWORK", "DATA_LINK", "SESSION"),
            ConnectionStep.entries.map { it.name },
        )
        assertEquals(
            listOf(
                "MFI_USB_PERMISSION_DENIED", "MFI_USB_PERMISSION_TIMEOUT", "IPHONE_USB_PERMISSION_DENIED",
                "IPHONE_USB_PERMISSION_TIMEOUT", "CONTROL_CHANNEL_CLOSED", "WIRELESS_CONTROL_CHANNEL_CLOSED",
                "TRANSPORT_ATTACH_FAILED", "BRING_UP_FAILED", "HOST_ID_REJECTED", "CAR_HOTSPOT_STAYS_ON",
                "FIRST_TCP_TIMEOUT", "WIRELESS_PHONE_NOT_ON_NETWORK",
                "EXISTING_WIFI_CREDENTIALS_MISSING", "EXISTING_WIFI_PHONE_NOT_ON_NETWORK",
            ),
            CarPlayFailureReason.entries.map { it.name },
        )
        assertEquals(
            listOf(
                "HOTSPOT_CREDENTIALS", "HOTSPOT_OFF", "HOTSPOT_ON", "CAR_WIFI_DISCONNECTED",
                "RADIO_NOT_CHOSEN", "CAR_BLUETOOTH_OFF", "PHONE_NOT_CHOSEN", "DONGLE_NOT_READY",
            ),
            WirelessGap.entries.map { it.name },
        )
    }
}
