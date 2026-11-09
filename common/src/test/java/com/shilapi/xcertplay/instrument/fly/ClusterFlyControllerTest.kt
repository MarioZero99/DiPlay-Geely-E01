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

    /** Left at 1000, the fly step at 1400, the cluster's ask at 1500: a live PUSHING session. */
    private fun pushing(frameExecutor: java.util.concurrent.ExecutorService? = null): ClusterFlyController {
        val controller = controller(frameExecutor)
        controller.onGesture(Decision.FLY_LEFT)
        now = 1_400L
        runScheduled()
        now = 1_500L
        controller.onPacket(openAsk())
        runScheduled()
        return controller
    }

    /**
     * The CarPlay media thread must not pay for the cluster: a frame is only queued there. The
     * policy, the encoder boundary and the socket send all run on the fly thread.
     */
    @Test
    fun `a frame is packetized on the fly thread, not the caller's`() {
        val executor = ManualExecutor()
        val controller = pushing(executor)

        controller.onMainVideo(110, byteArrayOf(0, 0, 0, 1, 0x67, 66, 0, 0))

        assertFalse(lines.contains("fly.push start forward"))

        executor.runAll()
        assertTrue(lines.contains("fly.push start forward"))
    }

    @Test
    fun `left sends navigation before the fly`() {
        controller().onGesture(Decision.FLY_LEFT)

        assertEquals(listOf(FlyCommand.NAV_ON), sent)
        assertEquals(400L, scheduled.single().first)

        scheduled.single().second()
        assertEquals(listOf(FlyCommand.NAV_ON, FlyCommand.FLY_LEFT), sent)
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

    @Test
    fun `open is answered ready then start`() {
        val controller = controller()
        controller.onGesture(Decision.FLY_LEFT)
        now = 1_400L
        runScheduled()
        now = 1_500L

        val replies = controller.onPacket(openAsk())

        assertEquals(2, replies.size)
        assertEquals(2, replies[0].last().toInt())
        assertEquals(0, replies[1].last().toInt())
    }

    @Test
    fun `the picture being forwarded is logged`() {
        val executor = ManualExecutor()
        val controller = pushing(executor)

        controller.onMainVideo(110, byteArrayOf(0, 0, 0, 1, 0x67, 66, 0, 0))
        executor.runAll()

        assertTrue(lines.contains("fly.push start forward"))
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
        now = 2_600L

        controller.onCarPlayEnded()

        assertEquals(
            listOf(FlyCommand.NAV_ON, FlyCommand.FLY_LEFT, FlyCommand.FLY_RIGHT),
            sent,
        )
        runScheduled()
        assertEquals(FlyCommand.NAV_OFF, sent.last())
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
