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
    /** True when [currentStep] is the step that failed. */
    val failed: Boolean = false,
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

    /** Hands the head unit's radio back once the session is over. */
    fun idleAfterSession(context: Context)
}
