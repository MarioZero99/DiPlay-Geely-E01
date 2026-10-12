package com.shilapi.xcertplay.media

/**
 * Per-frame capture-silence tracking for issue #102.
 *
 * Some head units (GKUI 19 on Android 5.1 among them) draw no data at all from
 * `MediaRecorder.AudioSource.VOICE_COMMUNICATION`: every frame a telephony uplink captures is
 * all zeroes, so calls are silent no matter what the phone does. This tracker lets the uplink
 * tell "the recorder yields only zeroes" apart from "frames were captured but not sent", and
 * decides when that source has proved broken so later telephony uplinks use MIC instead.
 *
 * The class is deliberately free of Android types so the decision can be unit tested directly.
 */
internal class MicrophoneSilenceTracker {
    private var silent = 0L
    private var audioSeen = false

    /** Total all-zero frames seen so far. */
    val silentFrameCount: Long get() = silent

    /** Records one captured frame's silence. */
    fun frame(silent: Boolean) {
        if (silent) {
            this.silent++
        } else {
            audioSeen = true
        }
    }

    /** True while no non-silent frame has ever been captured. */
    fun neverReceivedAudio(): Boolean = !audioSeen

    /**
     * True once a telephony uplink that used VOICE_COMMUNICATION captured nothing but silence for
     * at least [BROKEN_SILENCE_FRAMES] frames: the source is broken on this device.
     *
     * @param audioType the uplink's CarPlay audio type (`telephony`, `speechrecognition`, ...).
     * @param usedVoiceCommunication whether the recorder used `VOICE_COMMUNICATION`.
     */
    fun isVoiceCommunicationBroken(audioType: String, usedVoiceCommunication: Boolean): Boolean =
        audioType == "telephony" &&
            usedVoiceCommunication &&
            neverReceivedAudio() &&
            silent >= BROKEN_SILENCE_FRAMES

    companion object {
        /** Minimum all-zero frames before VOICE_COMMUNICATION counts as broken (~4s at 20ms). */
        const val BROKEN_SILENCE_FRAMES = 200L
    }
}
