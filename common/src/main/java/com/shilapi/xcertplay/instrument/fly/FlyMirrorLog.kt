package com.shilapi.xcertplay.instrument.fly

/** The mirror's lines in the session log. Fixed words, no picture, no names. */
object FlyMirrorLog {
    fun mirror(): String = "fly.display mirror"

    fun frame(): String = "fly.display frame"

    fun failed(reason: String): String = "fly.display mirror failed $reason"
}
