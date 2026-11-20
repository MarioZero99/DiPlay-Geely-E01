package com.shilapi.xcertplay.instrument.fly

import com.shilapi.xcertplay.ui.ThreeFingerSwipeDetector.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The controller's contract with the rest of the app: what it sends, and when.
 *
 * The broadcasts are recorded here instead of posted, and time is a number the test hands over, so
 * the whole decision path runs on a computer. The Activity only carries these out.
 */
class ClusterFlyControllerTest {

    private var now = 1_000L
    private val sent = mutableListOf<FlyCommand>()
    private val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
    private val warnings = mutableListOf<String>()
    private val lines = mutableListOf<String>()

    private fun controller(frameExecutor: java.util.concurrent.ExecutorService? = null) = ClusterFlyController(
        commands = { sent.add(it) },
        showWarning = { warnings.add(it) },
        setMainVideoSubscribed = {},
        log = { lines.add(it) },
        clock = { now },
        postDelayed = { delay, action -> scheduled.add(delay to action) },
        frameExecutor = frameExecutor,
    )

    /** Runs what the controller posted, in the order it posted it. */
    private fun runScheduled() {
        val pending = scheduled.toList()
        scheduled.clear()
        pending.forEach { it.second() }
    }

    /** A 24-byte ask of function 301 with the switch off. */
    private fun openAsk(): ByteArray {
        val packet = ByteArray(24)
        packet[4] = (301 shr 8).toByte()
        packet[5] = 301.toByte()
        return packet
    }

    private fun ask(function: Int, order: Int): ByteArray {
        val packet = ByteArray(24)
        packet[4] = (function shr 8).toByte()
        packet[5] = function.toByte()
        packet[23] = order.toByte()
        return packet
    }

    /** Gesture at 1000, front at 1400, left at 2400, the cluster's ask at 2500. */
    private fun pushing(frameExecutor: java.util.concurrent.ExecutorService? = null): ClusterFlyController {
        val controller = controller(frameExecutor)
        controller.onGesture(Decision.FLY_LEFT)
        now = 2_400L
        runScheduled()
        now = 2_500L
        controller.onPacket(openAsk())
        runScheduled()
        return controller
    }

    @Test
    fun `left sends navigation, then front, then the fly`() {
        controller().onGesture(Decision.FLY_LEFT)

        assertEquals(listOf(FlyCommand.NAV_ON), sent)
        assertEquals(listOf(400L, 1_400L), scheduled.map { it.first })
        assertTrue(lines.contains("fly.command NAV_ON"))
        assertFalse(lines.contains("fly.command NAV_FRONT"))
        assertFalse(lines.contains("fly.command FLY_LEFT"))

        now = 1_400L
        scheduled[0].second()
        assertEquals(listOf(FlyCommand.NAV_ON, FlyCommand.NAV_FRONT), sent)
        assertFalse(lines.contains("fly.command FLY_LEFT"))

        now = 2_400L
        scheduled[1].second()
        assertEquals(listOf(FlyCommand.NAV_ON, FlyCommand.NAV_FRONT, FlyCommand.FLY_LEFT), sent)
    }

    @Test fun theLeftStepOpensTheLayerAndStillWaitsForTheSurface() {
        val order = mutableListOf<String>()
        val controller = ClusterFlyController(
            commands = { sent.add(it) },
            showWarning = { warnings.add(it) },
            setMainVideoSubscribed = {},
            log = { lines.add(it) },
            clock = { now },
            postDelayed = { delay, action ->
                order.add("schedule $delay")
                scheduled.add(delay to action)
            },
            onLayerOpened = { order.add("opened") },
        )
        controller.onGesture(Decision.FLY_LEFT)
        now = 2_400L
        runScheduled()
        assertEquals(FlyPhase.WAITING_FOR_VIDEO, controller.phase)
        assertTrue(order.indexOf("opened") < order.indexOf("schedule 3000"))
        controller.onClusterDisplayReady()
        assertEquals(FlyPhase.PUSHING, controller.phase)
        scheduled.filter { it.first == 3_000L }.forEach { it.second() }
        assertFalse(lines.any { it.contains("fly.abort") })
    }

    @Test
    fun `right with nothing open sends nothing`() {
        controller().onGesture(Decision.FLY_RIGHT)

        assertEquals(emptyList<FlyCommand>(), sent)
        assertEquals(emptyList<Pair<Long, () -> Unit>>(), scheduled)
        assertEquals(emptyList<String>(), warnings)
    }

    @Test
    fun `the quick bar gesture is not the controller's`() {
        controller().onGesture(Decision.TRIGGER)
        controller().onGesture(Decision.PASS)

        assertEquals(emptyList<FlyCommand>(), sent)
        assertEquals(emptyList<String>(), warnings)
    }

    /**
     * The cluster can ask in the 400 ms before the left step. That ask is not refused: the first
     * answer would be the one it keeps. Ready and start are logged when the left step goes out.
     */
    @Test
    fun `an open before the fly step is answered when the step goes out`() {
        val controller = controller()
        controller.onGesture(Decision.FLY_LEFT)
        now = 1_200L

        val replies = controller.onPacket(openAsk())

        assertEquals(emptyList<ByteArray>(), replies)
        assertFalse(lines.contains("fly.reply refused"))

        now = 2_400L
        runScheduled()

        assertTrue(lines.contains("fly.reply ready"))
        assertTrue(lines.contains("fly.reply start"))
        assertFalse(lines.contains("fly.reply refused"))
    }

    @Test
    fun `open is answered ready then start`() {
        val controller = controller()
        controller.onGesture(Decision.FLY_LEFT)
        now = 2_400L
        runScheduled()
        now = 2_500L

        val replies = controller.onPacket(openAsk())

        assertEquals(2, replies.size)
        assertEquals(2, replies[0].last().toInt())
        assertEquals(0, replies[1].last().toInt())
    }

    /**
     * The factory answers a close with the map acknowledgement first and the stop second, and it
     * does not care whether it believed it was streaming.
     */
    @Test
    fun `a close is answered success then stop`() {
        val controller = pushing(ManualExecutor())
        now = 5_000L

        val replies = controller.onPacket(ask(301, 1))

        assertEquals(2, replies.size)
        assertEquals(2, replies[0].last().toInt())
        assertEquals(1, replies[1].last().toInt())
    }

    /** Function 303 is the cluster's own heartbeat; the factory reads it and writes nothing back. */
    @Test
    fun `the cluster heartbeat gets no reply`() {
        assertEquals(emptyList<ByteArray>(), controller().onPacket(ask(303, 0)))
    }

    /** The junction map is not ours to show, so it is declined rather than acknowledged. */
    @Test
    fun `the junction map is declined`() {
        val replies = controller().onPacket(ask(302, 0))

        assertEquals(1, replies.size)
        assertEquals(0x01, replies[0][4].toInt())
        assertEquals(0x2E, replies[0][5].toInt())
        assertEquals(0, replies[0].last().toInt())
    }

    /**
     * CarPlay ending is the layer's last chance to take itself down. It goes out as a right swipe,
     * in the factory's order, even though the session is already losing its navigation picture.
     */
    @Test
    fun `car play ending closes the layer it opened`() {
        val controller = pushing()
        now = 3_400L

        controller.onCarPlayEnded()

        assertEquals(
            listOf(FlyCommand.NAV_ON, FlyCommand.NAV_FRONT, FlyCommand.FLY_LEFT, FlyCommand.FLY_RIGHT),
            sent,
        )
        runScheduled()
        assertEquals(FlyCommand.NAV_BACK, sent.last())
    }

    private fun failedLeft(): ClusterFlyController = ClusterFlyController(
        commands = { command ->
            sent.add(command)
            command != FlyCommand.FLY_LEFT
        },
        showWarning = { warnings.add(it) },
        setMainVideoSubscribed = {},
        log = { lines.add(it) },
        clock = { now },
        postDelayed = { delay, action -> scheduled.add(delay to action) },
    )

    private fun openToLeft(controller: ClusterFlyController) {
        controller.onGesture(Decision.FLY_LEFT)
        now = 1_400L
        scheduled.removeAt(0).second()
        now = 2_400L
        scheduled.removeAt(0).second()
    }

    @Test
    fun `failed 1885 aborts without waiting or fly right`() {
        val controller = failedLeft()
        openToLeft(controller)

        assertEquals(
            listOf(FlyCommand.NAV_ON, FlyCommand.NAV_FRONT, FlyCommand.FLY_LEFT, FlyCommand.NAV_OFF),
            sent,
        )
        assertEquals(listOf(400L), scheduled.map { it.first })
        assertFalse(lines.contains("fly.phase WAITING_FOR_VIDEO"))
        assertTrue(lines.contains("fly.abort 1885 left false"))
        assertEquals(listOf(ClusterFlyWarning.pageUnchanged()), warnings)

        now = 2_400L
        controller.onGesture(Decision.FLY_LEFT)
        assertEquals(listOf("刚才那次还没完成，请稍等"), warnings.drop(1))
        assertFalse(sent.drop(4).contains(FlyCommand.NAV_ON))

        // The abort's own front message goes out at 2800. It is a withdrawal, not a new command,
        // so the one-second interval still counts from the failed 1885 at 2400.
        now = 2_800L
        scheduled.removeAt(0).second()
        assertEquals(FlyCommand.NAV_BACK, sent.last())

        now = 3_400L
        controller.onGesture(Decision.FLY_LEFT)
        assertEquals(FlyCommand.NAV_ON, sent.last())
    }

    /**
     * The cluster asked at the same millisecond the left step was due, so the session is already
     * pushing by the time the failed 1885 is known. There is nothing to take back: the picture is
     * live and the next swipe must not find a half-torn-down layer.
     */
    @Test
    fun `a failed 1885 after the cluster already asked keeps the picture`() {
        val subscriptions = mutableListOf<Boolean>()
        val controller = ClusterFlyController(
            commands = { command ->
                sent.add(command)
                command != FlyCommand.FLY_LEFT
            },
            showWarning = { warnings.add(it) },
            setMainVideoSubscribed = { subscriptions.add(it) },
            log = { lines.add(it) },
            clock = { now },
            postDelayed = { delay, action -> scheduled.add(delay to action) },
        )
        controller.onGesture(Decision.FLY_LEFT)
        now = 1_400L
        scheduled.removeAt(0).second()

        now = 2_400L
        controller.onPacket(openAsk())
        runScheduled()

        assertEquals(listOf(FlyCommand.NAV_ON, FlyCommand.NAV_FRONT, FlyCommand.FLY_LEFT), sent)
        assertEquals(listOf(true), subscriptions)
        assertFalse(warnings.contains(ClusterFlyWarning.pageUnchanged()))
    }

    /** Closing the Activity takes the pending three-second abort with it. */
    @Test
    fun `closing the controller cancels the silent timeout`() {
        val controller = controller()
        openToLeft(controller)

        controller.close()
        now = 5_400L
        runScheduled()

        assertFalse(sent.contains(FlyCommand.NAV_OFF))
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `no video ask within 3000ms aborts once`() {
        val controller = controller()
        openToLeft(controller)
        assertTrue(lines.contains("fly.phase WAITING_FOR_VIDEO"))
        assertEquals(listOf(3_000L), scheduled.map { it.first })

        now = 5_400L
        scheduled.removeAt(0).second()

        assertEquals(
            listOf(FlyCommand.NAV_ON, FlyCommand.NAV_FRONT, FlyCommand.FLY_LEFT, FlyCommand.NAV_OFF),
            sent,
        )
        assertFalse(sent.contains(FlyCommand.FLY_RIGHT))
        assertEquals(listOf(ClusterFlyWarning.pageUnchanged()), warnings)
        assertTrue(lines.contains("fly.abort cluster silent"))
    }

    @Test
    fun `an open ask cancels the silent timeout`() {
        val controller = controller()
        openToLeft(controller)
        now = 2_500L
        controller.onPacket(openAsk())
        runScheduled()

        assertFalse(sent.contains(FlyCommand.NAV_OFF))
        assertFalse(sent.contains(FlyCommand.NAV_BACK))
        assertFalse(warnings.contains(ClusterFlyWarning.pageUnchanged()))
        assertTrue(lines.contains("fly.push start forward") || lines.contains("fly.phase PUSHING"))
    }

    /** Runs the fly-thread tasks by hand, so the test can see what was deferred. */
    private class ManualExecutor : java.util.concurrent.AbstractExecutorService() {
        private val tasks = mutableListOf<Runnable>()
        private var stopped = false

        override fun execute(command: Runnable) {
            tasks.add(command)
        }

        override fun shutdown() {
            stopped = true
        }

        override fun shutdownNow(): MutableList<Runnable> {
            stopped = true
            return tasks.toMutableList().also { tasks.clear() }
        }

        override fun isShutdown() = stopped

        override fun isTerminated() = stopped

        override fun awaitTermination(timeout: Long, unit: java.util.concurrent.TimeUnit) = true

        fun runAll() {
            val pending = tasks.toList()
            tasks.clear()
            pending.forEach { it.run() }
        }
    }
}
