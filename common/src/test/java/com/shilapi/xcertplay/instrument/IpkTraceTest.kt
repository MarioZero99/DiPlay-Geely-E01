package com.shilapi.xcertplay.instrument

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IpkTraceTest {
    @Test fun commandFitsTheRootBridgeLimit() {
        // Same wrapper as RootShellBridge.wrap. The vendor binder rejects anything over 220 bytes.
        val wrapped = "{ ${IpkTrace.COMMAND}; } 2>&1; rc=\$?; echo __RC:\$rc"
        assertTrue(
            "wrapped command is ${wrapped.toByteArray(Charsets.UTF_8).size} bytes",
            wrapped.toByteArray(Charsets.UTF_8).size <= 220,
        )
    }

    @Test fun selectKeepsTheCanDecisionAndDropsTheMcuFlood() {
        val body = """
            11-03 17:38:21.100  1000  2000 D MCU_UART: byte flood
            11-03 17:38:21.110  10028 2100 D diminteraction: MediaService updateMedia : null
            11-03 17:38:21.120  1000  2200 I MediaBean: Media---->IPK  current sourceType:BTAudio
            11-03 17:38:21.130  1000  2200 I CAN_SetInfo: FE-6 发送歌曲名:下一站天后
            11-03 17:38:21.140  1000  2200 I CAN_SetInfo: FE-6  music result is true.
            11-03 17:38:21.150  1000  2200 D MCU_UART: more flood
        """.trimIndent()
        val kept = IpkTrace.select(body)
        assertEquals(
            listOf(
                "11-03 17:38:21.110  10028 2100 D diminteraction: MediaService updateMedia : null",
                "11-03 17:38:21.120  1000  2200 I MediaBean: Media---->IPK  current sourceType:BTAudio",
                "11-03 17:38:21.130  1000  2200 I CAN_SetInfo: FE-6 发送歌曲名:下一站天后",
                "11-03 17:38:21.140  1000  2200 I CAN_SetInfo: FE-6  music result is true.",
            ),
            kept,
        )
    }
}
