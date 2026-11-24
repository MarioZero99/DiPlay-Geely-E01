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

    private fun controller() = ClusterFlyController(
        commands = { sent.add(it) },
        showWarning = { warnings.add(it) },
        log = { lines.add(it) },
        clock = { now },
        postDelayed = { delay, action -> scheduled.add(delay to action) },
    )

    /** Runs what the controller posted, in the order it posted it. */
    private fun runScheduled() {
        val pending = scheduled.toList()
        scheduled.clear()
        pending.forEach { it.second() }
    }

    /** Gesture at 1000, front at 2000, left at 3000, the display ready at 3100. */
    private fun pushing(): ClusterFlyController {
        val controller = controller()
        controller.onGesture(Decision.FLY_LEFT)
        now = 3_000L
        runScheduled()
        now = 3_100L
        controller.onMirrorReady()
        return controller
    }

    /** Gesture at 1000, front at 2000, left at 3000: the session is waiting for a picture. */
    private fun waiting(): ClusterFlyController {
        val controller = controller()
        controller.onGesture(Decision.FLY_LEFT)
        now = 2_000L
        scheduled.removeAt(0).second()
        now = 3_000L
        scheduled.removeAt(0).second()
        return controller
    }

    @Test fun aMirrorFailureWhileWaitingCoversTheLayer() {
        val controller = waiting()
        controller.onMirrorStarted()
        controller.onMirrorFailed()
        // The close is one second after FLY_LEFT; the factory would refuse it sooner.
        now = 4_000L
        scheduled.last { it.first == 1_000L }.second()
        assertTrue(sent.contains(FlyCommand.FLY_RIGHT))
    }

    @Test fun fiveSecondsWithoutAFrameCoversTheLayer() {
        val controller = waiting()
        controller.onMirrorStarted()
        now = 8_000L
        scheduled.last { it.first == 5_000L }.second()
        assertTrue(sent.contains(FlyCommand.FLY_RIGHT))
        assertEquals(FlyPhase.CLOSING, controller.phase)
    }

    @Test fun theFirstFrameEntersPushingAndTheWaitDoesNotCoverTheLayer() {
        val controller = ClusterFlyController(
            commands = { sent.add(it) },
            showWarning = { warnings.add(it) },
            log = { lines.add(it) },
            clock = { now },
            postDelayed = { delay, action -> scheduled.add(delay to action) },
        )
        controller.onGesture(Decision.FLY_LEFT)
        now = 2_000L
        scheduled.removeAt(0).second()
        now = 3_000L
        scheduled.removeAt(0).second()
        controller.onMirrorStarted()
        controller.onMirrorReady()
        assertEquals(FlyPhase.PUSHING, controller.phase)
        scheduled.filter { it.first == 5_000L }.forEach { it.second() }
        assertFalse(sent.contains(FlyCommand.FLY_RIGHT))
    }

    @Test
    fun `left sends navigation, then front, then the fly`() {
        controller().onGesture(Decision.FLY_LEFT)

        assertEquals(listOf(FlyCommand.NAV_ON, FlyCommand.NAV_MODE_ON), sent)
        assertEquals(listOf(1_000L, 2_000L), scheduled.map { it.first })
        assertTrue(lines.contains("fly.command NAV_ON"))
        assertFalse(lines.contains("fly.command NAV_FRONT"))
        assertFalse(lines.contains("fly.command FLY_LEFT"))

        now = 2_000L
        scheduled[0].second()
        assertEquals(listOf(FlyCommand.NAV_ON, FlyCommand.NAV_MODE_ON, FlyCommand.NAV_FRONT), sent)
        assertFalse(lines.contains("fly.command FLY_LEFT"))

        now = 3_000L
        scheduled[1].second()
        assertEquals(
            listOf(FlyCommand.NAV_ON, FlyCommand.NAV_MODE_ON, FlyCommand.NAV_FRONT, FlyCommand.FLY_LEFT),
            sent,
        )
    }

    @Test fun theLeftStepOpensTheLayerAndStillWaitsForTheSurface() {
        val order = mutableListOf<String>()
        val controller = ClusterFlyController(
            commands = { sent.add(it) },
            showWarning = { warnings.add(it) },
                log = { lines.add(it) },
            clock = { now },
            postDelayed = { delay, action ->
                order.add("schedule $delay")
                scheduled.add(delay to action)
            },
            onLayerOpened = { order.add("opened") },
        )
        controller.onGesture(Decision.FLY_LEFT)
        now = 3_000L
        runScheduled()
        assertEquals(FlyPhase.WAITING_FOR_VIDEO, controller.phase)
        assertTrue(order.indexOf("opened") < order.indexOf("schedule 3000"))
        controller.onMirrorReady()
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
        now = 4_100L

        controller.onCarPlayEnded()

        assertEquals(
            listOf(
                FlyCommand.NAV_ON,
                FlyCommand.NAV_MODE_ON,
                FlyCommand.NAV_FRONT,
                FlyCommand.FLY_LEFT,
                FlyCommand.FLY_RIGHT,
            ),
            sent,
        )
        runScheduled()
        assertEquals(
            listOf(
                FlyCommand.NAV_ON,
                FlyCommand.NAV_MODE_ON,
                FlyCommand.NAV_FRONT,
                FlyCommand.FLY_LEFT,
                FlyCommand.FLY_RIGHT,
                FlyCommand.NAV_MODE_OFF,
                FlyCommand.NAV_OFF,
                FlyCommand.NAV_BACK,
            ),
            sent,
        )
    }

    private fun failedLeft(): ClusterFlyController = ClusterFlyController(
        commands = { command ->
            sent.add(command)
            command != FlyCommand.FLY_LEFT
        },
        showWarning = { warnings.add(it) },
        log = { lines.add(it) },
        clock = { now },
        postDelayed = { delay, action -> scheduled.add(delay to action) },
    )

    private fun openToLeft(controller: ClusterFlyController) {
        controller.onGesture(Decision.FLY_LEFT)
        now = 2_000L
        scheduled.removeAt(0).second()
        now = 3_000L
        scheduled.removeAt(0).second()
    }

    @Test
    fun `failed 1885 aborts without waiting or fly right`() {
        val controller = failedLeft()
        openToLeft(controller)

        assertEquals(
            listOf(
                FlyCommand.NAV_ON,
                FlyCommand.NAV_MODE_ON,
                FlyCommand.NAV_FRONT,
                FlyCommand.FLY_LEFT,
                FlyCommand.NAV_MODE_OFF,
                FlyCommand.NAV_OFF,
            ),
            sent,
        )
        assertEquals(listOf(1_000L), scheduled.map { it.first })
        assertFalse(lines.contains("fly.phase WAITING_FOR_VIDEO"))
        assertTrue(lines.contains("fly.abort 1885 left false"))
        assertEquals(listOf(ClusterFlyWarning.pageUnchanged()), warnings)

        now = 3_000L
        controller.onGesture(Decision.FLY_LEFT)
        assertEquals(listOf("刚才那次还没完成，请稍等"), warnings.drop(1))
        assertFalse(sent.drop(6).contains(FlyCommand.NAV_ON))

        // The abort's own front message goes out at 4000. It is a withdrawal, not a new command,
        // so the one-second interval still counts from the failed 1885 at 3000.
        now = 4_000L
        scheduled.removeAt(0).second()
        assertEquals(FlyCommand.NAV_BACK, sent.last())

        now = 4_000L
        controller.onGesture(Decision.FLY_LEFT)
        assertEquals(
            listOf(FlyCommand.NAV_ON, FlyCommand.NAV_MODE_ON),
            sent.takeLast(2),
        )
    }

    @Test
    fun `a failed 1900 aborts and drops the front and the fly`() {
        val controller = ClusterFlyController(
            commands = { command ->
                sent.add(command)
                command != FlyCommand.NAV_MODE_ON
            },
            showWarning = { warnings.add(it) },
            log = { lines.add(it) },
            clock = { now },
            postDelayed = { delay, action -> scheduled.add(delay to action) },
        )

        controller.onGesture(Decision.FLY_LEFT)

        // The 1900 write never reached RpcService: the mode, the flag and the front are withdrawn.
        assertEquals(
            listOf(FlyCommand.NAV_ON, FlyCommand.NAV_MODE_ON, FlyCommand.NAV_MODE_OFF, FlyCommand.NAV_OFF),
            sent,
        )
        assertTrue(lines.contains("fly.abort 1900 on false"))

        // The queued 1887 and 1885 belong to the old generation and must not go out.
        now = 3_000L
        runScheduled()

        assertFalse(sent.contains(FlyCommand.NAV_FRONT))
        assertFalse(sent.contains(FlyCommand.FLY_LEFT))
        assertTrue(sent.contains(FlyCommand.NAV_BACK))
    }

    @Test
    fun `a failed 1887 still sends the fly`() {
        val controller = ClusterFlyController(
            commands = { command ->
                sent.add(command)
                command != FlyCommand.NAV_FRONT
            },
            showWarning = { warnings.add(it) },
            log = { lines.add(it) },
            clock = { now },
            postDelayed = { delay, action -> scheduled.add(delay to action) },
        )
        controller.onGesture(Decision.FLY_LEFT)

        now = 2_000L
        scheduled[0].second()
        now = 3_000L
        scheduled[1].second()

        assertTrue(sent.contains(FlyCommand.FLY_LEFT))
        assertFalse(sent.contains(FlyCommand.NAV_MODE_OFF))
    }

    @Test
    fun `a completed open prints the command chain`() {
        val controller = controller()
        openToLeft(controller)

        assertTrue(
            lines.contains("fly.chain commands result=ok 1900=true 1887=true 1885=true"),
        )
    }

    @Test
    fun `a failed 1900 prints the command chain it stopped at`() {
        val controller = ClusterFlyController(
            commands = { command ->
                sent.add(command)
                command != FlyCommand.NAV_MODE_ON
            },
            showWarning = { warnings.add(it) },
            log = { lines.add(it) },
            clock = { now },
            postDelayed = { delay, action -> scheduled.add(delay to action) },
        )
        controller.onGesture(Decision.FLY_LEFT)

        assertTrue(
            lines.contains("fly.chain commands result=fail at=1900 1900=false 1887=skip 1885=skip"),
        )
    }

    @Test
    fun `a cluster that never brings a surface reports the chain at surface`() {
        val hops = mutableListOf<String>()
        val controller = ClusterFlyController(
            commands = { sent.add(it) },
            showWarning = { warnings.add(it) },
            log = { lines.add(it) },
            clock = { now },
            postDelayed = { delay, action -> scheduled.add(delay to action) },
            onPictureFailed = { hops.add(it) },
        )
        openToLeft(controller)

        now = 6_000L
        scheduled.removeAt(0).second()

        assertEquals(listOf("surface"), hops)
    }

    @Test
    fun `a mirror with no frame reports the chain at frame`() {
        val hops = mutableListOf<String>()
        val controller = ClusterFlyController(
            commands = { sent.add(it) },
            showWarning = { warnings.add(it) },
            log = { lines.add(it) },
            clock = { now },
            postDelayed = { delay, action -> scheduled.add(delay to action) },
            onPictureFailed = { hops.add(it) },
        )
        openToLeft(controller)
        controller.onMirrorStarted()

        now = 8_000L
        scheduled.last { it.first == 5_000L }.second()

        assertEquals(listOf("frame"), hops)
    }

    @Test
    fun `the first mcu has no gap and the next one is one second`() {
        val controller = controller()
        controller.onGesture(Decision.FLY_LEFT)
        assertTrue(lines.contains("fly.mcu gap none"))

        now = 2_000L
        scheduled[0].second()
        assertTrue(lines.contains("fly.mcu gap 1000"))
    }

    /**
     * The cluster's display came up at the same millisecond the left step was due, so the session
     * is already pushing by the time the failed 1885 is known. There is nothing to take back: the
     * picture is live and the next swipe must not find a half-torn-down layer.
     */
    @Test
    fun `a failed 1885 after the cluster already asked keeps the picture`() {
        val controller = ClusterFlyController(
            commands = { command ->
                sent.add(command)
                command != FlyCommand.FLY_LEFT
            },
            showWarning = { warnings.add(it) },
            log = { lines.add(it) },
            clock = { now },
            postDelayed = { delay, action -> scheduled.add(delay to action) },
        )
        controller.onGesture(Decision.FLY_LEFT)
        now = 2_000L
        scheduled.removeAt(0).second()

        now = 3_000L
        controller.onMirrorReady()
        runScheduled()

        assertEquals(
            listOf(FlyCommand.NAV_ON, FlyCommand.NAV_MODE_ON, FlyCommand.NAV_FRONT, FlyCommand.FLY_LEFT),
            sent,
        )
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

        now = 6_000L
        scheduled.removeAt(0).second()

        assertEquals(
            listOf(
                FlyCommand.NAV_ON,
                FlyCommand.NAV_MODE_ON,
                FlyCommand.NAV_FRONT,
                FlyCommand.FLY_LEFT,
                FlyCommand.NAV_MODE_OFF,
                FlyCommand.NAV_OFF,
            ),
            sent,
        )
        assertFalse(sent.contains(FlyCommand.FLY_RIGHT))
        assertEquals(listOf(ClusterFlyWarning.pageUnchanged()), warnings)
        assertTrue(lines.contains("fly.abort cluster silent"))

        // The withdrawal's gap is between writes including withdrawals: 1000 ms from the
        // retraction mode-off that went out at 6000, not 4000 from the failed FLY_LEFT.
        now = 7_000L
        runScheduled()
        assertEquals(FlyCommand.NAV_BACK, sent.last())
        assertEquals("fly.mcu gap 1000", lines.last { it.startsWith("fly.mcu gap") })
    }

    @Test
    fun `car play ending puts the cluster window away`() {
        val giveUps = mutableListOf<Int>()
        val controller = ClusterFlyController(
            commands = { sent.add(it) },
            showWarning = { warnings.add(it) },
                log = { lines.add(it) },
            clock = { now },
            postDelayed = { delay, action -> scheduled.add(delay to action) },
            onGiveUp = { giveUps.add(1) },
        )
        controller.onGesture(Decision.FLY_LEFT)
        now = 3_000L
        runScheduled()
        now = 3_100L
        controller.onMirrorReady()

        controller.onCarPlayEnded()

        assertEquals(1, giveUps.size)
    }

    @Test
    fun `a lost cluster display ends the picture`() {
        val giveUps = mutableListOf<Int>()
        val controller = ClusterFlyController(
            commands = { sent.add(it) },
            showWarning = { warnings.add(it) },
                log = { lines.add(it) },
            clock = { now },
            postDelayed = { delay, action -> scheduled.add(delay to action) },
            onGiveUp = { giveUps.add(1) },
        )
        controller.onGesture(Decision.FLY_LEFT)
        now = 3_000L
        runScheduled()
        now = 3_100L
        controller.onMirrorReady()

        controller.onClusterDisplayLost()

        assertEquals(1, giveUps.size)
    }

    @Test
    fun `a lost display while waiting aborts with its own reason`() {
        val controller = controller()
        openToLeft(controller)

        controller.onClusterDisplayLost()

        assertTrue(lines.contains("fly.abort display gone"))
    }

    @Test
    fun `a cluster display cancels the silent timeout`() {
        val controller = controller()
        openToLeft(controller)
        now = 3_100L
        controller.onMirrorReady()
        runScheduled()

        assertFalse(sent.contains(FlyCommand.NAV_OFF))
        assertFalse(sent.contains(FlyCommand.NAV_BACK))
        assertFalse(warnings.contains(ClusterFlyWarning.pageUnchanged()))
        assertTrue(lines.contains("fly.phase PUSHING"))
    }

}
