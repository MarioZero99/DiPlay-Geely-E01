package com.shilapi.xcertplay.instrument.fly

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The socket's lifecycle, which the layer needs twice per session: a restart of CarPlay closes the
 * link and a later swipe opens it again, on the same instance.
 */
class ClusterFlyLinkTest {

    @Test
    fun `a closed link can listen and send again`() {
        val listens = AtomicInteger()
        val link = ClusterFlyLink(
            port = 0,
            log = { line -> if (line == "fly.tcp listen") listens.incrementAndGet() },
        )
        try {
            link.start()
            assertTrue(await { listens.get() >= 1 })

            link.close()
            link.start()
            assertTrue(await { listens.get() >= 2 })

            assertTrue(link.send(byteArrayOf(0, 0, 0, 1, 0x67, 66, 0, 0)))
        } finally {
            link.close()
        }
    }

    private fun await(condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }
}
