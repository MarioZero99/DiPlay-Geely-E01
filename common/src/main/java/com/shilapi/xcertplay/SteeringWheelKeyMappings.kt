package com.shilapi.xcertplay

import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject

/** What a wheel key that DiPlay owns does. Two commands -- the wheel is a track wheel on this car. */
enum class SteeringWheelCommand { PREVIOUS_TRACK, NEXT_TRACK }

/**
 * Which wheel key codes are DiPlay's to take, and what each of them does.
 *
 * The table *is* the capture rule: [handles] is the only question the key paths ask, so a code the
 * driver has not bound is answered for by the head unit, exactly as if DiPlay were not installed.
 * That matters on a Geely head unit, where the wheel also carries the car's own keys -- pressing one
 * of those must not be swallowed by an app that never meant to answer it.
 *
 * Only Android's own key codes are bindable at all -- see [isValidKeyCode]. The vendor family this
 * head unit uses for its own keys is outside the range, so it cannot reach the table by default or
 * through a hand edit, and the default itself covers no more than the two track codes.
 *
 * Taken from the official GeekPlay build for this head unit, whose wheel handling is this shape and
 * which was measured working where DiPlay's own overshot: it asked the head unit's input service to
 * hand over the play/pause and voice codes as well, which is how a wheel key DiPlay does not use can
 * end up with no effect at all.
 */
class SteeringWheelKeyMappings private constructor(val bindings: Map<Int, SteeringWheelCommand>) {
    /** Whether this key is one the driver gave to DiPlay. False leaves the press to the head unit. */
    fun handles(keyCode: Int): Boolean = bindings.containsKey(keyCode)

    /**
     * The command this event carries, or null when it carries none: a release, or the auto-repeat of a
     * key still held down. Null here does not mean the key is not ours -- see [handles].
     */
    fun commandFor(keyCode: Int, action: Int, repeatCount: Int): SteeringWheelCommand? =
        if (action != KeyEvent.ACTION_DOWN || repeatCount != 0) null else bindings[keyCode]

    fun keyCodesFor(command: SteeringWheelCommand): List<Int> =
        bindings.filterValues { it == command }.keys.sorted()

    /** The same table with [command] moved onto [keyCodes], replacing whatever it was on before. */
    fun withKeyCodes(command: SteeringWheelCommand, keyCodes: Collection<Int>): SteeringWheelKeyMappings =
        from(
            bindings.filterValues { it != command } +
                keyCodes.distinct().filter(::isValidKeyCode).associateWith { command },
        )

    override fun equals(other: Any?): Boolean = other is SteeringWheelKeyMappings && bindings == other.bindings

    override fun hashCode(): Int = bindings.hashCode()

    override fun toString(): String = "SteeringWheelKeyMappings($bindings)"

    companion object {
        const val MIN_KEY_CODE = 1
        const val MAX_KEY_CODE = 1000

        /**
         * Where the wheel starts. 87/88 are Android's own next and previous; 89 is rewind, which on a
         * wheel is the same request. Nothing else is bound.
         */
        val DEFAULT: SteeringWheelKeyMappings = from(
            mapOf(
                KeyEvent.KEYCODE_MEDIA_NEXT to SteeringWheelCommand.NEXT_TRACK,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS to SteeringWheelCommand.PREVIOUS_TRACK,
                KeyEvent.KEYCODE_MEDIA_REWIND to SteeringWheelCommand.PREVIOUS_TRACK,
            ),
        )

        /**
         * Android's own key code range. The vendor family a Geely head unit uses for its own keys is
         * outside it by construction, so no stored table can hand DiPlay a key it must not answer.
         */
        fun isValidKeyCode(keyCode: Int): Boolean = keyCode in MIN_KEY_CODE..MAX_KEY_CODE

        /** A table from whatever survived storage or a hand edit: unknown codes are dropped. */
        fun from(bindings: Map<Int, SteeringWheelCommand>): SteeringWheelKeyMappings =
            SteeringWheelKeyMappings(bindings.filterKeys(::isValidKeyCode).toSortedMap())
    }
}

/**
 * The mappings as one stored string.
 *
 * A blob written by another version decodes to null rather than to a half-read table: the caller then
 * falls back to [SteeringWheelKeyMappings.DEFAULT], which is a wheel that works, where a partly-read
 * one is a wheel with keys that do nothing.
 */
object SteeringWheelKeyMappingsCodec {
    private const val KEY_VERSION = "version"
    private const val KEY_BINDINGS = "bindings"
    private const val KEY_CODE = "keyCode"
    private const val KEY_COMMAND = "command"
    private const val VERSION = 1

    fun encode(mappings: SteeringWheelKeyMappings): String {
        val bindings = JSONArray()
        mappings.bindings.forEach { (keyCode, command) ->
            bindings.put(JSONObject().put(KEY_CODE, keyCode).put(KEY_COMMAND, command.name))
        }
        return JSONObject().put(KEY_VERSION, VERSION).put(KEY_BINDINGS, bindings).toString()
    }

    fun decode(value: String): SteeringWheelKeyMappings? = runCatching {
        val root = JSONObject(value)
        require(root.getInt(KEY_VERSION) == VERSION) { "unknown mappings version" }
        val encoded = root.getJSONArray(KEY_BINDINGS)
        val bindings = LinkedHashMap<Int, SteeringWheelCommand>()
        for (index in 0 until encoded.length()) {
            val entry = encoded.getJSONObject(index)
            val command = runCatching { SteeringWheelCommand.valueOf(entry.getString(KEY_COMMAND)) }.getOrNull()
            val keyCode = entry.getInt(KEY_CODE)
            if (command != null && SteeringWheelKeyMappings.isValidKeyCode(keyCode)) bindings[keyCode] = command
        }
        SteeringWheelKeyMappings.from(bindings)
    }.getOrNull()
}
