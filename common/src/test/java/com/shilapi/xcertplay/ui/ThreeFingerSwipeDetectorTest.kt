package com.shilapi.xcertplay.ui

import com.shilapi.xcertplay.ui.ThreeFingerSwipeDetector.Decision
import com.shilapi.xcertplay.ui.ThreeFingerSwipeDetector.Phase
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The three-finger swipe, driven by numbers.
 *
 * Every case here is one the old inline detector in the Activity got wrong or could not be asked
 * about: a third finger that lands after the others have been resting, a flick whose last event is
 * the lift rather than a move, the events left over after the settings page has been asked for, and
 * a sequence that ends in a cancel instead of an up.
 */
class ThreeFingerSwipeDetectorTest {

    private val detector = ThreeFingerSwipeDetector(
        distance = 72f,
        directionRatio = 1.15f,
        armWindowMillis = 350L,
    )

    private fun threeFingers() {
        assertEquals(Decision.PASS, detector.onTouch(Phase.DOWN, 1, 0L, 900f, 400f))
        assertEquals(Decision.PASS, detector.onTouch(Phase.POINTER_DOWN, 2, 20L, 900f, 400f))
        assertEquals(Decision.ARM, detector.onTouch(Phase.POINTER_DOWN, 3, 40L, 900f, 400f))
    }

    @Test
    fun `three fingers landing together arm the gesture`() = threeFingers()

    /** Two fingers are CarPlay's own scroll, and stay CarPlay's. */
    @Test
    fun `two fingers never arm`() {
        assertEquals(Decision.PASS, detector.onTouch(Phase.DOWN, 1, 0L, 900f, 400f))
        assertEquals(Decision.PASS, detector.onTouch(Phase.POINTER_DOWN, 2, 20L, 900f, 400f))
    }

    /**
     * Three presses in a row are a hand coming to rest, not a swipe. The window is what tells them
     * apart, and it is counted from the first finger rather than from the one before.
     */
    @Test
    fun `a third finger that lands late does not arm`() {
        assertEquals(Decision.PASS, detector.onTouch(Phase.DOWN, 1, 0L, 900f, 400f))
        assertEquals(Decision.PASS, detector.onTouch(Phase.POINTER_DOWN, 2, 400L, 900f, 400f))
        assertEquals(Decision.PASS, detector.onTouch(Phase.POINTER_DOWN, 3, 600L, 900f, 400f))
    }

    @Test
    fun `a downward swipe past the distance triggers`() {
        threeFingers()
        assertEquals(Decision.CONSUME, detector.onTouch(Phase.MOVE, 3, 80L, 900f, 450f))
        assertEquals(Decision.TRIGGER, detector.onTouch(Phase.MOVE, 3, 120L, 900f, 480f))
    }

    @Test
    fun `a sideways drag is not a settings gesture`() {
        threeFingers()
        // 80 down, but 200 across: far enough vertically, nowhere near vertical enough.
        assertEquals(Decision.CONSUME, detector.onTouch(Phase.MOVE, 3, 120L, 1100f, 480f))
    }

    /** A flick's last move can be short and the lift carries the rest of the distance. */
    @Test
    fun `the lift is checked when the last move was short`() {
        threeFingers()
        assertEquals(Decision.CONSUME, detector.onTouch(Phase.MOVE, 3, 80L, 900f, 440f))
        assertEquals(Decision.TRIGGER, detector.onTouch(Phase.POINTER_UP, 3, 140L, 900f, 500f))
    }

    /**
     * After it fires, the rest of the touch stream belongs to the gesture. Forwarding a `MOVE` with
     * no `DOWN` in front of it is how the phone ended up with a contact it never saw arrive.
     */
    @Test
    fun `everything after a trigger is swallowed until the fingers are up`() {
        threeFingers()
        assertEquals(Decision.TRIGGER, detector.onTouch(Phase.MOVE, 3, 120L, 900f, 480f))
        assertEquals(Decision.CONSUME, detector.onTouch(Phase.MOVE, 3, 160L, 900f, 500f))
        assertEquals(Decision.CONSUME, detector.onTouch(Phase.POINTER_UP, 3, 200L, 900f, 520f))
        assertEquals(Decision.CONSUME, detector.onTouch(Phase.POINTER_UP, 2, 220L, 900f, 520f))
        assertEquals(Decision.CONSUME, detector.onTouch(Phase.UP, 1, 240L, 900f, 520f))
        // And the next touch is CarPlay's again.
        assertEquals(Decision.PASS, detector.onTouch(Phase.DOWN, 1, 400L, 300f, 300f))
    }

    /** A cancel — the window losing focus — must not leave the machine stuck mid-gesture. */
    @Test
    fun `a cancel resets the sequence`() {
        threeFingers()
        assertEquals(Decision.CONSUME, detector.onTouch(Phase.MOVE, 3, 80L, 900f, 450f))
        assertEquals(Decision.CONSUME, detector.onTouch(Phase.CANCEL, 3, 90L, 900f, 450f))
        assertEquals(Decision.PASS, detector.onTouch(Phase.DOWN, 1, 200L, 300f, 300f))
        assertEquals(Decision.ARM, detector.onTouch(Phase.POINTER_DOWN, 3, 220L, 300f, 300f))
    }

    @Test
    fun `a fourth finger does not rearm a running gesture`() {
        threeFingers()
        assertEquals(Decision.CONSUME, detector.onTouch(Phase.POINTER_DOWN, 4, 60L, 900f, 400f))
    }

    /** The distance is measured from where the three fingers were, not from where the first one was. */
    @Test
    fun `the origin is the three finger centroid`() {
        assertEquals(Decision.PASS, detector.onTouch(Phase.DOWN, 1, 0L, 900f, 300f))
        assertEquals(Decision.PASS, detector.onTouch(Phase.POINTER_DOWN, 2, 20L, 900f, 340f))
        assertEquals(Decision.ARM, detector.onTouch(Phase.POINTER_DOWN, 3, 40L, 900f, 380f))
        // 60 past the first finger's height but only 40 past the centroid's: not yet.
        assertEquals(Decision.CONSUME, detector.onTouch(Phase.MOVE, 3, 80L, 900f, 420f))
        assertEquals(Decision.TRIGGER, detector.onTouch(Phase.MOVE, 3, 120L, 900f, 460f))
    }
}
