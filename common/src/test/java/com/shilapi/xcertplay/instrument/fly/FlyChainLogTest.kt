package com.shilapi.xcertplay.instrument.fly

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two summary lines, as strings.
 *
 * `picture result=frame` says only that Android swapped one buffer into displayId 1. It is not the
 * glass, and nothing here may be read as "the cluster page is open".
 */
class FlyChainLogTest {

    @Test
    fun `a complete command chain omits the failing hop`() {
        assertEquals(
            "fly.chain commands result=ok 1900=true 1887=true 1885=true",
            FlyChainLog.commands("ok", null, "true", "true", "true"),
        )
    }

    @Test
    fun `a command chain that stopped at 1900 names it and skips the rest`() {
        assertEquals(
            "fly.chain commands result=fail at=1900 1900=false 1887=skip 1885=skip",
            FlyChainLog.commands("fail", "1900", "false", "skip", "skip"),
        )
    }

    @Test
    fun `a picture chain that reached the first buffer`() {
        assertEquals(
            "fly.chain picture result=frame display=1 show=true surface=true detach=true attach=true mirror=true frame=true",
            FlyChainLog.picture(
                "frame", null, "1",
                "true", "true", "true", "true", "true", "true",
            ),
        )
    }

    @Test
    fun `a picture chain that failed at detach skips everything after it`() {
        assertEquals(
            "fly.chain picture result=fail at=detach display=1 show=true surface=true detach=false attach=skip mirror=skip frame=skip",
            FlyChainLog.picture(
                "fail", "detach", "1",
                "true", "true", "false", "skip", "skip", "skip",
            ),
        )
    }
}
