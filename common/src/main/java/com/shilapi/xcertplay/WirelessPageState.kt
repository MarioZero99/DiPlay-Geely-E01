package com.shilapi.xcertplay

import com.shilapi.xcertplay.transport.WirelessReadiness

/**
 * Where the wireless session is.
 *
 * Three states rather than two booleans, because [Connecting] and [Live] answer "may this setting be
 * changed" differently: a picture on the screen is something to lose and worth asking about, and an
 * attempt that has not produced one yet is not — see 49 §一.
 */
enum class SessionPhase { Idle, Connecting, Live }

internal fun sessionPhase(hasSession: Boolean, active: Boolean): SessionPhase = when {
    !hasSession -> SessionPhase.Idle
    active -> SessionPhase.Live
    else -> SessionPhase.Connecting
}

/**
 * What the page reads once per redraw: whether a session is running, and what a connection would
 * still be missing.
 *
 * Both halves are here for the same reason. A card that asks the session for itself, and a card that
 * asks the head unit for itself, drift apart from the step beside them — and a page whose controls
 * disagree about whether a connection is running is a page that offers to start a second one.
 */
data class WirelessPageState(val session: SessionPhase, val readiness: WirelessReadiness)

/**
 * Whether changing a setting the connection is built from has to be confirmed first.
 *
 * Only where there is a picture to lose. An attempt that has not produced one yet is replaced without
 * asking — there is nothing on the screen to take away, and a question there would be a tap for
 * nothing. See 49 §一.
 */
internal fun connectionSettingNeedsConfirm(session: SessionPhase): Boolean = session == SessionPhase.Live
