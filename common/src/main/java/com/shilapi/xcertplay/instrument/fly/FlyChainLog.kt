package com.shilapi.xcertplay.instrument.fly

/**
 * The two summaries that say how far the page change got.
 *
 * Each field is one hop on the wire. `true` / `false` are what that hop returned; `skip` means the
 * chain never reached it. `result=frame` says only that Android swapped the first buffer into the
 * cluster's virtual display — it is not the glass changing, which software on this side cannot see.
 */
object FlyChainLog {

    const val OK = "ok"
    const val FAIL = "fail"
    const val FRAME = "frame"

    /** The page broadcast went out but Extra's log could not prove it; the picture still attaches. */
    const val UNCONFIRMED = "unconfirmed"

    const val TRUE = "true"
    const val FALSE = "false"
    const val SKIP = "skip"
    const val NONE = "none"

    /** The three MCU writes. [at] is omitted only when the whole chain succeeded. */
    fun commands(
        result: String,
        at: String?,
        mcu1900: String,
        mcu1887: String,
        mcu1885: String,
    ): String =
        "${head("commands", result, at)} 1900=$mcu1900 1887=$mcu1887 1885=$mcu1885"

    /** The display, show, surface, detach, attach, mirror and first-frame hops. */
    fun picture(
        result: String,
        at: String?,
        display: String,
        show: String,
        surface: String,
        detach: String,
        attach: String,
        mirror: String,
        frame: String,
    ): String =
        "${head("picture", result, at)} display=$display show=$show surface=$surface " +
            "detach=$detach attach=$attach mirror=$mirror frame=$frame"

    private fun head(kind: String, result: String, at: String?): String =
        if (at == null) "fly.chain $kind result=$result"
        else "fly.chain $kind result=$result at=$at"
}

/**
 * One attach attempt's picture chain, written as each hop reports.
 *
 * All fields start `skip`; a failure line is printed at the failing hop and nowhere later, so a
 * single attempt produces at most one `fly.chain picture` line before the first frame.
 */
internal class FlyChain {
    var display: String = FlyChainLog.SKIP
    var show: String = FlyChainLog.SKIP
    var surface: String = FlyChainLog.SKIP
    var detach: String = FlyChainLog.SKIP
    var attach: String = FlyChainLog.SKIP
    var mirror: String = FlyChainLog.SKIP
    var frame: String = FlyChainLog.SKIP

    var reported: Boolean = false

    fun line(result: String, at: String?): String =
        FlyChainLog.picture(result, at, display, show, surface, detach, attach, mirror, frame)
}
