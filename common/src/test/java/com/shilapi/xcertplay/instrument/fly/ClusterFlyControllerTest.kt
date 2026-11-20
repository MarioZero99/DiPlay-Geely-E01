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

    /** Gesture at 1000, front at 1400, left at 2400, the display ready at 2500. */
    private fun pushing(): ClusterFlyController {
        val controller = controller()
        controller.onGesture(Decision.FLY_LEFT)
        now = 2_400L
        runScheduled()
        now = 2_500L
        controller.onClusterDisplayReady()
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
     * The cluster's display came up at the same millisecond the left step was due, so the session
     * is already pushing by the time the failed 1885 is known. There is nothing to take back: the
     * picture is live and the next swipe must not find a half-torn-down layer.
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
        controller.onClusterDisplayReady()
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
    fun `no display within 3000ms aborts once`() {
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
    fun `a surface change is queued even while a frame is waiting`() {
        val tasks = mutableListOf<Runnable>()
        val controller = controller(RecordingExecutor(tasks))

        controller.onMainVideo(110, byteArrayOf(0, 0, 0, 1, 0x65, 0))
        controller.onMainVideo(110, byteArrayOf(0, 0, 0, 1, 0x41, 0))
        controller.onFlySurface(null)

        assertEquals(2, tasks.size)
    }

    @Test
    fun `car play ending puts the cluster window away`() {
        val giveUps = mutableListOf<Int>()
        val controller = ClusterFlyController(
            commands = { sent.add(it) },
            showWarning = { warnings.add(it) },
            setMainVideoSubscribed = {},
            log = { lines.add(it) },
            clock = { now },
            postDelayed = { delay, action -> scheduled.add(delay to action) },
            onGiveUp = { giveUps.add(1) },
        )
        controller.onGesture(Decision.FLY_LEFT)
        now = 2_400L
        runScheduled()
        now = 2_500L
        controller.onClusterDisplayReady()

        controller.onCarPlayEnded()

        assertEquals(1, giveUps.size)
    }

    @Test
    fun `a started picture asks for one keyframe`() {
        val requests = mutableListOf<Int>()
        val controller = ClusterFlyController(
            commands = { sent.add(it) },
            showWarning = { warnings.add(it) },
            setMainVideoSubscribed = {},
            log = { lines.add(it) },
            clock = { now },
            postDelayed = { delay, action -> scheduled.add(delay to action) },
            requestKeyFrame = { requests.add(1) },
        )
        controller.onGesture(Decision.FLY_LEFT)
        now = 2_400L
        runScheduled()
        now = 2_500L
        controller.onClusterDisplayReady()
        controller.onClusterDisplayReady()

        assertEquals(1, requests.size)
    }

    /** Runs nothing: the test only needs to see what the controller queued. */
    private class RecordingExecutor(private val tasks: MutableList<Runnable>) :
        java.util.concurrent.AbstractExecutorService() {
        override fun execute(command: Runnable) {
            tasks.add(command)
        }

        override fun shutdown() = Unit

        override fun shutdownNow(): MutableList<Runnable> =
            tasks.toMutableList().also { tasks.clear() }

        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: java.util.concurrent.TimeUnit) = true
    }

    @Test
    fun `a cluster display cancels the silent timeout`() {
        val controller = controller()
        openToLeft(controller)
        now = 2_500L
        controller.onClusterDisplayReady()
        runScheduled()

        assertFalse(sent.contains(FlyCommand.NAV_OFF))
        assertFalse(sent.contains(FlyCommand.NAV_BACK))
        assertFalse(warnings.contains(ClusterFlyWarning.pageUnchanged()))
        assertTrue(lines.contains("fly.phase PUSHING"))
    }

}
