package com.shilapi.xcertplay.media

import android.media.AudioTrack
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioTrack
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class AudioRendererSilenceLoopTest {

    @After
    fun tearDown() {
        ShadowAudioTrack.resetTest()
    }

    private fun lpcmFormat() = AudioFormat(
        codec = AudioCodecKind.LPCM,
        sampleRate = 48_000,
        channels = 2,
        payloadType = 96,
        audioType = "media",
    )

    private fun rtpPacket(pcm: ByteArray): ByteArray {
        val rtp = ByteArray(12 + pcm.size)
        rtp[0] = 0x80.toByte()
        rtp[1] = 96.toByte()
        System.arraycopy(pcm, 0, rtp, 12, pcm.size)
        return rtp
    }

    @Test
    fun packetStarvationMaintainsAudioTrackPlayingAndWritesSilence() {
        ShadowAudioTrack.resetTest()
        ShadowAudioTrack.setMinBufferSize(4096)

        val writtenBuffers = Collections.synchronizedList(mutableListOf<ByteArray>())
        var activeTrack: AudioTrack? = null

        ShadowAudioTrack.addAudioDataListener { track, bytes, _ ->
            activeTrack = track
            writtenBuffers.add(bytes.copyOf())
        }

        val sink = AndroidMediaSink(isWireless = true, mediaBufferMillis = 300)
        try {
            sink.onAudioStarted(96, lpcmFormat(), firstSample = 0)

            // 1. 注入 65 包真实非零音频数据 (65 >= 60 门限)，触发起播门限
            val loudPcm = ByteArray(1920) { index ->
                if ((index / 2) % 2 == 0) 0x7F.toByte() else 0x10.toByte()
            }
            repeat(65) { seq ->
                sink.onAudioRtp(96, lpcmFormat(), rtpPacket(loudPcm), sample = seq * 480)
            }

            // 等待工作线程处理并起播
            Thread.sleep(150)

            val track = activeTrack
            assertTrue("AudioTrack 必须已经创建并开始播放", track != null)
            assertEquals("起播后必须处于 PLAYSTATE_PLAYING", AudioTrack.PLAYSTATE_PLAYING, track!!.playState)

            // 2. 模拟断流：停止发送 RTP 包，等待音频线程消费完毕进入 Silence Loop
            Thread.sleep(200)

            // 断言：处于断包期间，AudioTrack 仍保持 PLAYING 状态，绝不调用 pause()！
            assertEquals("断流期间 AudioTrack 绝不能 pause，必须维持 PLAYING", AudioTrack.PLAYSTATE_PLAYING, track.playState)

            // 断言：抓取到的写入字节中必须有全 0 的静音填缝块 (Silence Chunk)
            val zeroWrites = synchronized(writtenBuffers) {
                writtenBuffers.filter { buf -> buf.isNotEmpty() && buf.all { it == 0.toByte() } }
            }
            assertTrue("断流期间末端必须持续写入全0静音帧填缝，防止硬件打结爆音", zeroWrites.isNotEmpty())

            // 3. 模拟新曲首批数据包到达（未满 60 包门限，例如 5 包），验证在静音掩护下平稳蓄水，绝不中断静音
            writtenBuffers.clear()
            repeat(5) { seq ->
                sink.onAudioRtp(96, lpcmFormat(), rtpPacket(loudPcm), sample = (65 + seq) * 480)
            }
            Thread.sleep(100)

            assertEquals("蓄水期间 AudioTrack 依然维持 PLAYING", AudioTrack.PLAYSTATE_PLAYING, track.playState)
            val bufferingZeroWrites = synchronized(writtenBuffers) {
                writtenBuffers.filter { buf -> buf.isNotEmpty() && buf.all { it == 0.toByte() } }
            }
            assertTrue("蓄水期间队列非空但门控未开，必须持续写入静音掩护声卡", bufferingZeroWrites.isNotEmpty())

            // 4. 继续注入剩余数据包直至蓄满 60 包 (5 + 60 = 65 >= 60 门限)，触发开闸起播
            writtenBuffers.clear()
            repeat(60) { seq ->
                sink.onAudioRtp(96, lpcmFormat(), rtpPacket(loudPcm), sample = (70 + seq) * 480)
            }
            Thread.sleep(150)

            assertEquals("恢复后 AudioTrack 依然保持 PLAYING", AudioTrack.PLAYSTATE_PLAYING, track.playState)
            val resumedWrites = synchronized(writtenBuffers) {
                writtenBuffers.filter { buf -> buf.any { it != 0.toByte() } }
            }
            assertTrue("恢复后必须能继续写入真实声音", resumedWrites.isNotEmpty())

        } finally {
            sink.onAudioStopped(96)
            sink.close()
        }
    }
}
