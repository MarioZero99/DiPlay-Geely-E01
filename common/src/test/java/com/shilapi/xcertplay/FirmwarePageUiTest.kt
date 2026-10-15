package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.shilapi.xcertplay.carhop.CarBluetoothHop
import com.shilapi.xcertplay.carhop.CarBluetoothHops
import com.shilapi.xcertplay.carhop.CarBluetoothMaintenance
import com.shilapi.xcertplay.carhop.CarBluetoothMaintenanceStatus
import com.shilapi.xcertplay.carhop.CarBluetoothPhone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * A write to the head unit's `/system` takes the whole screen until it is over.
 *
 * Three things are locked in here, and they are the three the page exists for. It opens onto a write
 * already under way — the script belongs to the head unit, so it goes on running whether or not this
 * app is alive, and opening the app onto one is an ordinary way to arrive. It has no way off it while
 * the write lasts, because the header's two controls would both take the driver somewhere that cannot
 * show them what is happening. And it lets them go once the operation has ended, including when it
 * ended without reporting anything — which is the one ending that needs words, because nothing is
 * running and nothing said how it stopped.
 *
 * The steps are drawn in three states rather than two, and that is the other half of what this pins:
 * an install that had completed used to look exactly like one that had not started.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en")
class FirmwarePageUiTest {
    private val hop = FirmwareHop()

    @Before fun thisBuildHasThisHop() = CarBluetoothHops.override(hop)

    @After fun giveTheBuildItsOwnAnswerBack() = CarBluetoothHops.resetOverride()

    private val heading = "Changing the head unit's firmware"

    private val interrupted = "This write never reported an outcome — the script was probably interrupted."

    private fun open(page: String): DiPlayActivity {
        val intent = Intent(RuntimeEnvironment.getApplication(), DiPlayActivity::class.java)
            .putExtra("page", page)
        return Robolectric.buildActivity(DiPlayActivity::class.java, intent).setup().get()
    }

    private fun allViews(activity: DiPlayActivity): List<View> {
        val found = mutableListOf<View>()
        fun walk(view: View) {
            found += view
            if (view is ViewGroup) for (index in 0 until view.childCount) walk(view.getChildAt(index))
        }
        walk(activity.findViewById(android.R.id.content))
        return found
    }

    private inline fun <reified T : View> views(activity: DiPlayActivity): List<T> =
        allViews(activity).filterIsInstance<T>()

    private fun texts(activity: DiPlayActivity) =
        views<TextView>(activity).mapNotNull { it.text?.toString() }.filter { it.isNotBlank() }

    private fun buttonLabels(activity: DiPlayActivity) =
        views<Button>(activity).mapNotNull { it.text?.toString() }

    /**
     * Lets the page's own round trip finish, and the clock the watch runs on with it.
     *
     * The card is asked for off the main thread, so nothing is on the screen until that answer comes
     * back; and the watch that keeps it current is a delayed post, which a paused looper only runs
     * once its clock is moved. Both have to happen before anything here can be read.
     */
    private fun settle(activity: DiPlayActivity, until: () -> Boolean): List<String> {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
            if (until()) break
            Thread.sleep(20)
        }
        return texts(activity)
    }

    private fun settled(activity: DiPlayActivity) = settle(activity) { heading in texts(activity) }

    /**
     * Opening onto a write already under way lands on the page that waits for it.
     *
     * Without this the driver arrives on the home page with nothing to say a script is writing to
     * `/system`, and the only thing that would tell them is a card three taps away.
     */
    @Test fun openingOntoAWriteUnderWayLandsOnThePageThatWaitsForIt() {
        hop.running = true
        hop.status = CarBluetoothMaintenanceStatus.RUNNING
        hop.currentStep = 5
        assertTrue("the waiting page is what opens", heading in texts(open("home")))
    }

    /**
     * And it has no way off it.
     *
     * Both header controls would take the driver somewhere that cannot show them what is happening,
     * and the second of them out of the app — which would not even stop the write, because the script
     * is the head unit's and not this process's child. So the page draws neither, and there is nothing
     * else on it to press: no cancel, because the moment the script may be inside is one where the
     * only dangerous thing left is being interrupted.
     */
    @Test fun theWaitingPageHasNoWayOffIt() {
        hop.running = true
        hop.status = CarBluetoothMaintenanceStatus.RUNNING
        hop.currentStep = 5
        val activity = open("home")
        settled(activity)
        assertTrue("nothing at all to press", buttonLabels(activity).isEmpty())
    }

    /** The back key is not a way off it either, while the write is running. */
    @Test fun backDoesNotLeaveTheWaitingPageWhileItRuns() {
        hop.running = true
        hop.status = CarBluetoothMaintenanceStatus.RUNNING
        hop.currentStep = 5
        val activity = open("home")
        activity.onBackPressedDispatcher.onBackPressed()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("still on the waiting page", heading in texts(activity))
        assertFalse("and the app is not on its way out", activity.isFinishing)
    }

    /**
     * A second tap on the app icon does not take the driver off the page either.
     *
     * The activity is `singleTop` with a launcher filter, so that second tap arrives as an intent
     * that names no page — and the default is the home page, with the back key unswallowed along with
     * it. The watch would put them back within a beat, which is the page flickering away and back
     * under their hands; refusing the intent is what the lock is for.
     */
    @Test fun aSecondTapOnTheIconDoesNotLeaveTheWaitingPage() {
        hop.running = true
        hop.status = CarBluetoothMaintenanceStatus.RUNNING
        hop.currentStep = 5
        val controller = Robolectric.buildActivity(
            DiPlayActivity::class.java,
            Intent(RuntimeEnvironment.getApplication(), DiPlayActivity::class.java)
                .putExtra("page", "firmware"),
        ).setup()
        val activity = controller.get()
        settled(activity)

        // Read before the looper is idled, and that is the whole point of the test: the watch puts the
        // page back within a beat either way, so an assertion made after it has run cannot tell the
        // two apart. What is being pinned is that the driver never sees the other page at all.
        controller.newIntent(Intent(RuntimeEnvironment.getApplication(), DiPlayActivity::class.java))

        assertTrue("still on the waiting page", heading in texts(activity))
    }

    /**
     * Once it is over the page says so and lets the driver go.
     *
     * The operation ends on the head unit, not here, so this is the transition that matters: the page
     * has to follow it from the hop's own state rather than from having been the one that started it.
     */
    @Test fun aFinishedWriteSaysSoAndOffersTheWayOut() {
        hop.running = true
        hop.status = CarBluetoothMaintenanceStatus.RUNNING
        hop.currentStep = 5
        val activity = open("home")

        hop.running = false
        hop.status = CarBluetoothMaintenanceStatus.DONE
        hop.currentStep = STEPS.lastIndex

        val shown = settle(activity) { "Done" in texts(activity) }
        assertTrue("the ending is said", "Done" in shown)
        assertTrue("and there is a way out", "Back" in buttonLabels(activity))
    }

    /**
     * An operation that never reported is the ending that needs words.
     *
     * Nothing is running and nothing said how it ended: the script was killed, or the head unit
     * restarted underneath it. The page says so, says what the only certain way to stop it is, and
     * lets the driver go rather than holding the screen on a wait that is never coming back.
     */
    @Test fun anInterruptedWriteSaysSoAndOffersTheWayOut() {
        hop.running = false
        hop.status = CarBluetoothMaintenanceStatus.INTERRUPTED
        hop.currentStep = 5
        // The route here is the page itself: this is where the driver was standing when the script
        // stopped reporting, and where the watchdog hands the screen back to them.
        val activity = open("firmware")
        val shown = settle(activity) { interrupted in texts(activity) }
        assertTrue("what happened is said", interrupted in shown)
        assertTrue(
            "and what it would take to be sure",
            shown.any { it.startsWith("Restarting the head unit is the only way") },
        )
        assertTrue("and there is a way out", "Back" in buttonLabels(activity))
    }

    /**
     * Every step of a finished operation is marked done, not just the last one.
     *
     * This is the shape the page could not draw before: `COMPLETED` was reported at the last step, and
     * a step the operation had finished was drawn the same as one it had not reached. An install that
     * had completed came out as one line lit and every line before it grey — indistinguishable from an
     * install that never started.
     */
    @Test fun aFinishedWriteTicksEveryStep() {
        hop.running = false
        hop.status = CarBluetoothMaintenanceStatus.DONE
        hop.currentStep = STEPS.lastIndex
        val activity = open("firmware")
        val shown = settle(activity) { texts(activity).count { it.startsWith("✓") } == STEPS.size }
        assertEquals("every step is marked done", STEPS.size, shown.count { it.startsWith("✓") })
    }

    /**
     * A step the operation did not get past is where it stopped, and everything before it is done.
     *
     * The failure lands on the step that caused it rather than on a line of its own, which is what the
     * hop's `currentStep` means — so the tick has to stop there too, or a failure would be drawn as
     * though the operation had finished.
     */
    @Test fun aFailedWriteTicksWhatItGotThroughAndStopsThere() {
        hop.running = false
        hop.status = CarBluetoothMaintenanceStatus.FAILED
        hop.currentStep = 2
        hop.detail = "remount-rw-failed"
        val activity = open("firmware")
        val shown = settle(activity) { "remount-rw-failed" in texts(activity) }
        assertEquals("the two it finished are ticked", 2, shown.count { it.startsWith("✓") })
        assertTrue("and the step it stopped at is named as it is", STEPS[2] in shown)
        assertFalse("with nothing ticked past it", "✓  ${STEPS[2]}" in shown)
        assertTrue("and the reason is the script's own word", "remount-rw-failed" in shown)
    }

    /** A hop whose one operation is the one under test, answering from fields the test sets. */
    private class FirmwareHop : CarBluetoothHop {
        @Volatile var running = false
        @Volatile var status = CarBluetoothMaintenanceStatus.NONE
        @Volatile var currentStep = CarBluetoothMaintenance.NO_STEP
        @Volatile var detail: String? = null

        override fun applies(context: Context): Boolean = true

        override fun ready(context: Context): Boolean = true

        override fun connectedPhones(context: Context): List<CarBluetoothPhone> = emptyList()

        override fun note(context: Context): String? = null

        override fun status(context: Context): String? = null

        override fun busy(context: Context): Boolean = running

        override fun operationRunning(context: Context): Boolean = running

        override fun idleAfterSession(context: Context) = Unit

        override fun performMaintenance(context: Context, id: String): String = ""

        override fun maintenance(context: Context): CarBluetoothMaintenance = CarBluetoothMaintenance(
            title = "Head unit firmware",
            state = "This app's patched firmware is in place · SPP listening",
            detail = detail,
            steps = STEPS,
            currentStep = currentStep,
            status = status,
        )
    }

    private companion object {
        /**
         * The hop's own nine, as a build with it mounted draws them.
         *
         * Literal rather than taken from the hop, which this module cannot see: the point of them here
         * is to be lines the page can be asked about, and a test that read them off the thing under
         * test would agree with whatever it drew.
         */
        val STEPS = listOf(
            "Checking the installed firmware",
            "Preparing a rollback copy",
            "Mounting /system read-write",
            "Backing up the head unit's own firmware",
            "Stopping the gocsdk service",
            "Writing the patched firmware",
            "Checking what was written",
            "Mounting /system read-only again",
            "Starting the gocsdk service",
        )
    }
}
