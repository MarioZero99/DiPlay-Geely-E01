package com.shilapi.xcertplay

import com.shilapi.xcertplay.orchestration.AttemptConfig
import com.shilapi.xcertplay.orchestration.AttemptEnvironment
import com.shilapi.xcertplay.orchestration.AttemptRecord
import com.shilapi.xcertplay.orchestration.AttemptResult
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.transport.WirelessGap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The file a report is read from.
 *
 * The point of every one of these is that an attempt leaves a line: a refusal that never reached the
 * ladder, a failure, and a file that has been running long enough to rotate. "No line" and "a quiet
 * day" have to be different things, or the report cannot be counted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class AttemptJournalTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val directory get() = File(context.filesDir, "logs")

    private fun record(result: AttemptResult, why: String) = AttemptRecord(
        id = "9f2c1a7e",
        generation = 1,
        retry = 0,
        startedWallClockMillis = 1_760_706_131_480L,
        startedUptimeMillis = 812_345L,
        durationMillis = 15_320L,
        environment = AttemptEnvironment("3f9a1c02", "0.4.2-geely", 42, "Geely E01", 22),
        transport = CarPlayTransport.WIRELESS,
        mode = null,
        hop = null,
        gaps = emptyList(),
        steps = emptyList(),
        at = null,
        result = result,
        why = why,
        detail = null,
        reset = false,
        config = AttemptConfig(fps = 30, hevc = true, scaleTenths = 100),
    )

    @Test
    fun anAttemptLeavesOneLineInItsOwnFile() {
        AttemptJournal.write(context, record(AttemptResult.FAIL, "BRING_UP_FAILED"))
        assertEquals(1, AttemptJournal.lines(context).size)
        assertTrue(AttemptJournal.lines(context).single().contains("\"why\":\"BRING_UP_FAILED\""))
        assertTrue(File(directory, AttemptJournal.EVENTS).isFile)
    }

    /** A connect the page refused is still an attempt: without it, ten refusals look like no attempts. */
    @Test
    fun aRefusalIsRecordedEvenThoughNothingStarted() {
        AttemptJournal.writeRefusal(context, WirelessGap.HOTSPOT_OFF)
        val line = AttemptJournal.lines(context).single()
        assertTrue(line.contains("\"result\":\"BLOCKED\""))
        assertTrue(line.contains("\"why\":\"HOTSPOT_OFF\""))
        assertTrue(line.contains("\"at\":\"-\""))
    }

    /** Past the cap the file rotates rather than growing until the report has to drop the newest line. */
    @Test
    fun theFileRotatesInsteadOfGrowingWithoutBound() {
        directory.mkdirs()
        File(directory, AttemptJournal.EVENTS).writeText("x".repeat(300 * 1024) + "\n")
        AttemptJournal.write(context, record(AttemptResult.OK, "-"))
        assertTrue(File(directory, "events.1.jsonl").isFile)
        assertTrue(AttemptJournal.lines(context).last().contains("\"result\":\"OK\""))
    }

    /** The installation id is drawn once and then reused, so two lines can be seen to be one car. */
    @Test
    fun theInstallationIdIsDrawnOnce() {
        val first = AttemptJournal.installId(context)
        assertEquals(first, AttemptJournal.installId(context))
        assertEquals(8, first.length)
    }
}
