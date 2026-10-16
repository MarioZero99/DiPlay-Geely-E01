package com.shilapi.xcertplay.carhop

import android.content.Context

/** A phone the head unit's own Bluetooth service offers for a session. */
data class CarBluetoothPhone(val address: String, val name: String)

/**
 * What a hop offers to change about the head unit itself, for the system settings page.
 *
 * The hop says what it is, what is true now, and what it can do; the page owns the look, so a hop does
 * not ship a screen. Every [actions] entry carries the [Action.confirmation] the page must show before
 * doing it, because these change the head unit rather than the app and one of them can restart it.
 */
data class CarBluetoothMaintenance(
    /** One line naming what this control is for. */
    val title: String,
    /** What is true on the head unit right now, in the hop's own words. */
    val state: String,
    /** A second line of detail, or null when there is nothing more to say. */
    val detail: String? = null,
    /** What it can do, in the order they should be offered. */
    val actions: List<Action> = emptyList(),
    /**
     * The steps the current or last operation goes through, or empty when there is nothing to show.
     *
     * The stage names belong to the hop: the page draws them in order and marks the one being
     * attempted, so a step that fails is where the operation stopped rather than a separate message.
     */
    val steps: List<String> = emptyList(),
    /** Index into [steps] being attempted, or -1 when nothing is running. */
    val currentStep: Int = NO_STEP,
    /**
     * How that operation stands.
     *
     * Separate from [currentStep] because the step alone cannot say it: an operation that finished
     * successfully and one that is still on its last step are the same index, and an operation whose
     * script died without unwinding is the same index again. The page draws all three differently —
     * the first marks every step done, the second marks the one it is on, the third says nothing is
     * happening any more — and only the hop can tell them apart.
     */
    val status: CarBluetoothMaintenanceStatus = CarBluetoothMaintenanceStatus.NONE,
) {
    /** One thing the hop can do. [id] is the hop's own and comes back to it unchanged. */
    data class Action(
        val id: String,
        val label: String,
        /** Shown before doing it: what it changes, and how it comes back. */
        val confirmation: String,
        /** The one to offer most prominently. At most one action should carry it. */
        val primary: Boolean = false,
    )

    companion object {
        const val NO_STEP = -1
    }
}

/**
 * How the operation [CarBluetoothMaintenance.steps] describes stands.
 *
 * [INTERRUPTED] is the one that has to exist and could not be inferred: the hop's script writes its
 * own progress to a file, and a file left mid-operation by a script that was killed — or by a head
 * unit that restarted underneath it — says exactly what a running one says. Telling those apart is
 * about the reading rather than the text, so it is the hop's answer to give and not the page's to
 * guess at.
 */
enum class CarBluetoothMaintenanceStatus {
    /** Nothing has been attempted: [CarBluetoothMaintenance.steps] describes no operation. */
    NONE,

    /** Running right now. The page waits for it and offers nothing else. */
    RUNNING,

    /** Finished, with nothing unwound. */
    DONE,

    /** Stopped and unwound, or is unwinding: [CarBluetoothMaintenance.currentStep] is where. */
    FAILED,

    /** Started and never reported an outcome. Nothing is running; what it left behind is the state line's. */
    INTERRUPTED,
}

/**
 * The head unit's own Bluetooth, for head units whose stack an app cannot drive.
 *
 * Where the radios and the pairings belong to a vendor service there is nothing in `BluetoothAdapter`
 * to list and no bonded device to open a socket to, so the Bluetooth leg of a wireless session has to
 * come from somewhere else. A build that has that elsewhere supplies it here; every other build has
 * none of this and is not worse off for it — [CarBluetoothHops.active] is null, the connection page
 * lists the AOSP pairings, and the session runs on the AOSP stack.
 *
 * The interface is the whole of what the public side knows: which head units need it, what phones the
 * head unit offers, whether it could start now, the two lines the connection page shows, and whatever
 * it can do to the head unit itself.
 */
interface CarBluetoothHop {
    /** True when this head unit needs this hop rather than the AOSP stack. */
    fun applies(context: Context): Boolean

    /** Whether a session could start right now. May cost a round trip; callers cache the answer. */
    fun ready(context: Context): Boolean

    /**
     * Puts this hop in place at app start, for a hop that has to be there before a session rather than
     * at one.
     *
     * Best-effort, and only ever called off the main thread: it may need root and the head unit. A hop
     * that acts only when a session starts leaves the default, and a build with no hop never gets here.
     */
    fun onAppStart(context: Context) {
        // Nothing by default.
    }

    /**
     * Switches the head unit's own Bluetooth on when it is off, as the app opens.
     *
     * Best-effort and off the main thread, like [onAppStart]. Worth separating from it: this is not
     * about a session. The radio is what the driver's phone connects to at all, so it is put right
     * whether or not the hop is [ready], and a head unit whose Bluetooth has been switched off is one
     * where nothing else this app does would have worked.
     *
     * Where the radios belong to a vendor service, `BluetoothAdapter` does not reach them — only the
     * hop can — so a build with no hop has no one to ask and is not worse off for it.
     */
    fun ensureRadioOn(context: Context) {
        // Nothing by default.
    }

    /**
     * The name the head unit's own radio broadcasts, or null when this hop cannot read it.
     *
     * Apple's §18.4 requires the name in the iAP2 identification exchange to be the name in the
     * accessory's Extended Inquiry Response, so that an iPhone files the Bluetooth device it paired
     * with and the CarPlay accessory it is talking to under one entry rather than two. Where the radio
     * belongs to a vendor service, that name is the vendor's and can only be asked for — which is why
     * nothing on this side has one to offer: a name the phone has not heard in an inquiry is a second
     * accessory, and no name at all is not one of the answers.
     *
     * Blocking — callers stay off the main thread, and the answer is asked for at the moment the
     * exchange is about to happen rather than kept, because the driver can rename the radio.
     */
    fun localName(context: Context): String? = null

    /** The phones the head unit reports. Blocking — callers stay off the main thread. */
    fun connectedPhones(context: Context): List<CarBluetoothPhone>

    /** What turning this hop on does to the head unit, or null when there is nothing to say. */
    fun note(context: Context): String?

    /** Whether the hop can start, or null when there is nothing to say. */
    fun status(context: Context): String?

    /**
     * What this hop can do to the head unit itself, or null when it has nothing to offer — which is
     * also the answer on a head unit where [applies] is false.
     *
     * Costs a round trip through the head unit, so callers keep the answer and refresh it in the
     * background rather than asking while drawing.
     */
    fun maintenance(context: Context): CarBluetoothMaintenance?

    /**
     * Carries out one of [CarBluetoothMaintenance.actions] and says what happened.
     *
     * Blocking, and may end with the head unit restarting — this hop decides that and reports it as an
     * ordinary message rather than a failure. Callers stay off the main thread and redraw after.
     */
    fun performMaintenance(context: Context, id: String): String

    /**
     * True while the hop is changing the head unit, when a session must not start.
     *
     * Cheap and safe to ask on the main thread: it answers from state the hop already holds, and does
     * not go near the head unit. A session begun while the hop has the head unit's own process stopped
     * would be built on a socket that is about to move.
     */
    fun busy(context: Context): Boolean

    /**
     * Whether one of [CarBluetoothMaintenance.actions] is running right now.
     *
     * Cheap and safe to ask on the main thread, and answered from state the hop already holds rather
     * than from a round trip — the same progress file the card is built from. It exists because the
     * page has to decide whether to lock itself the instant a launch returns, and asking for the whole
     * card there would be a round trip standing in front of the screen it is trying to draw.
     *
     * A hop with nothing that runs for longer than a moment leaves this false.
     */
    fun operationRunning(context: Context): Boolean = false

    /** Hands the head unit's radio back once the session is over. */
    fun idleAfterSession(context: Context)
}
