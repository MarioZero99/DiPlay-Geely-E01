package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.CarPlayMediaButton

/**
 * One wheel key event, read against the driver's bindings.
 *
 * Both key paths -- the media button the head unit broadcasts and the key event it delivers to the
 * focused window -- ask this, so there is one answer to "is this press ours": the table, and nothing
 * else. An event this answers null for is left alone, which is what keeps the car's own keys working.
 */
object CarPlayMediaKeyMapper {
    fun handles(
        keyCode: Int,
        mappings: SteeringWheelKeyMappings = SteeringWheelKeyMappings.DEFAULT,
    ): Boolean = mappings.handles(keyCode)

    fun command(
        keyCode: Int,
        action: Int,
        repeatCount: Int,
        mappings: SteeringWheelKeyMappings = SteeringWheelKeyMappings.DEFAULT,
    ): SteeringWheelCommand? = mappings.commandFor(keyCode, action, repeatCount)

    /** The CarPlay media press a command stands for: an index into `AirPlayHid`'s media report. */
    fun mediaIndex(command: SteeringWheelCommand): Int = when (command) {
        SteeringWheelCommand.PREVIOUS_TRACK -> CarPlayMediaButton.PREVIOUS
        SteeringWheelCommand.NEXT_TRACK -> CarPlayMediaButton.NEXT
    }

    /** The press to send, or null when this event carries none. */
    fun mediaIndex(
        keyCode: Int,
        action: Int,
        repeatCount: Int,
        mappings: SteeringWheelKeyMappings = SteeringWheelKeyMappings.DEFAULT,
    ): Int? = command(keyCode, action, repeatCount, mappings)?.let(::mediaIndex)
}
