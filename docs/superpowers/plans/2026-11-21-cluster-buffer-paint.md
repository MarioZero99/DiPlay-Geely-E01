# 星瑞 E01：仪表软解出画实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 飞屏链路保持不动，让 `OMX.google.h264.decoder` 把已经在中控上收到的 H.264 解成缓冲区，再以 6 fps 画到 displayId 1 的 Surface 上。中控硬解不增加耗时。

**Architecture:** 2026-11-20 16:53 的车上日志里，1885 left、HDMI `displayId=1`、`Presentation` 和软解码器的 `configure`/`start` 都成功了（相位到了 `PUSHING`）。第一张 IDR 送进 `OMX.google.h264.decoder` 时抛 `IllegalStateException`，应用自己发了 1885 right。这块 Android 5.1 上，软解码器不能把这块 HDMI Surface 当成自己的输出窗口。解码器改为 `configure(format, null, …)`，输出用 API 16 的 `getInputBuffers` / `getOutputBuffers`。画图放到另一条更低优先级的线程，6 fps，只保留最新一帧。解码线程每个访问单元都要送进解码器，画不上的帧只是不拷贝。

**Tech Stack:** Kotlin，Android 5.1 / API 22，JUnit4，现有 `FlyClusterDecoder`、`ClusterFlyController`、`DiagnosticRedactor`。

**Spec:** 本文件即为规格。它只替换 `docs/superpowers/plans/2026-11-20-cluster-presentation.md` 里「软解码器把 Surface 交给 OMX」和「禁止 `getOutputBuffer`」这两条。1885、displayId 1、`Presentation`、禁止 1028、禁止流 111、禁止第二路 MTK 硬解，仍以那份计划为准。车上证据是 `dist/supabase-logs/DiPlay-20261120-165424-319.txt`。

---

## Global Constraints

- 最低 API 级别为 22。禁止 `PixelCopy`、`ImageReader`、`MediaCodec.getOutputImage`、`Display.getMode`。显示器尺寸继续用 `Display.getRealSize`。
- 不修改 `/system`，不改 `Navi.apk`、`XCNaviService`、`Extraservice`。
- 不调用 `DimInteraction.create()`，不调用 `setPresentationToDimSwitch`，不发 `ecarx.intent.action.DIM_PRESENTATION_CTRL`。
- 不向手机请求 `SCREEN_TYPE_ALT`（流 111）。不恢复 `1028`、`10.255.255.101`、RTP 或第二遍编码。
- 飞屏解码器只创建 `OMX.google.h264.decoder`。失败时不退回 `OMX.MTK.VIDEO.DECODER.AVC`。
- `csd-0` / `csd-1` 继续带 `00 00 00 01`。`flush()` 然后 `start()` 仍然合法。慢路径之后继续把 `START_CODE + sps` 和 `START_CODE + pps` 重新送入。
- 一个访问单元超过 150 ms 时才 `flush`。这 150 ms 只计解码器的 `queue` 和 `drain`，不计 YUV 拷贝以外的颜色转换，也不计 `lockCanvas`。
- 媒体线程仍然只入队。队列容量保持 1。满了就丢，并等到下一个 IDR。画图线程忙、或者还没到 166 ms，都不是丢访问单元，禁止因此设置 `dropUntilIdr`。
- 颜色转换和 `lockCanvas` 只在 `cluster-fly-paint` 上做。这条线程优先级是 `THREAD_PRIORITY_LOWEST`。`cluster-fly-frames` 保持 `THREAD_PRIORITY_BACKGROUND`。不要把画图 `post` 回主线程。
- 出画失败不发 1885 right，不拆 `Presentation`。解码器抛 `RuntimeException` 时仍走现在的 `onPictureFailed()`。
- 日志里禁止出现 `name=`。`DiagnosticRedactor` 会把带 `name=` 的整行换成 `[withheld: credential or device name]`。
- 每个代码任务都先写会失败的测试，再写实现。

## 性能

这块车机是 MT6735，四核 Cortex-A53。中控已经占用 `OMX.MTK.VIDEO.DECODER.AVC`，大约 20 fps，H.264 profile 100，1920×720。昨天日志里的关键帧是 48267 和 49578 字节。飞屏再开一个硬解会抢中控。软解可以落在后面，中控不可以。

原厂高德的仪表地图是 6 fps。飞屏出画同样 6 fps，间隔 166 ms。1920×720 的 YUV420 是 2,073,600 字节，RGB565 是 2,764,800 字节。每个访问单元都做颜色转换，会把 `cluster-fly-frames` 拖过 150 ms，队列容量又只有 1，中间的 P 帧会被丢掉，仪表就只能等下一个 IDR。所以：

- 解码器每个访问单元都 `queueInputBuffer` 再 `releaseOutputBuffer(index, false)`。引用链留在解码器里面。
- 只有距上次成功拷贝已经 166 ms，并且画图线程没有占着缓冲，才把 YUV 拷进一块复用的数组。拷贝用 `ByteBuffer.duplicate().get`，不分配新的 `byte[]`。拷不上就立刻返回，不等待。
- 颜色转换在画图线程上做，并且在转换结束时放开 YUV 槽，再去 `lockCanvas`。`lockCanvas` 期间解码线程可以写入下一帧。
- 整次飞屏只分配这些块，并且在 `release` 时随对象一起丢掉：两块最多 3 MB 的 YUV 槽（`FlyFrameSlot` 按实际帧长分配一次，上限 3 MB），一块 1920×720×2 的 RGB565，一张同样尺寸的 `Bitmap.Config.RGB_565`。禁止在 `submit`、`offer`、`paintOne` 里 `ByteArray()` 或 `createBitmap`。
- 飞屏解码器的 `KEY_MAX_INPUT_SIZE` 是 1 MB，不是中控那路的 8 MB。软解会按这个值准备多块输入缓冲。昨天的关键帧不到 50 KB。超过 1 MB 的访问单元走现有的 `fly.display input short`，把槽还回去，等到下一个 IDR。中控解码器的 8 MB 不动。
- 输出缓冲在 `drain` 里当次就 `releaseOutputBuffer`。禁止把解码器的输出 `ByteBuffer` 交给画图线程慢慢读。
- 画图超过 166 ms 时记 `fly.display paint slow <ms>`，同一条 5 秒内只记一次。这行不触发 `flush`，也不拆屏。

## 车上怎么算成功

同一轮左滑里出现这三行，并且没有紧接着的 `fly.display decoder failed`：

- `fly.display use id=1 HDMI 屏幕 1920x720`（整行还在，不是 withheld）
- `fly.display buffer`（软解交出了第一块 YUV）
- `fly.display frame`（第一张 RGB565 已经 `unlockCanvasAndPost`）

中控继续有 `Video: first frame rendered` 和随后的 `shown=`。`fly.display paint slow` 可以出现，出现也不拆屏。

---

### Task 1: 让选中的显示器留在日志里

**Files:**

- Modify: `docs/superpowers/plans/2026-11-20-cluster-presentation.md`（标题下加作废说明）
- Modify: `common/src/main/java/com/shilapi/xcertplay/instrument/fly/FlyDisplay.kt:21-25`
- Modify: `common/src/test/java/com/shilapi/xcertplay/instrument/fly/FlyDisplayTest.kt:24-29`

**Interfaces:**

- Consumes: `DiagnosticRedactor.redact(line: String): String?`
- Produces: `displayLines` 的第二行改为 `fly.display use id=<id> <name> <width>x<height>`，中间没有 `name=`

- [ ] **Step 1: Write the failing test**

把 `FlyDisplayTest.linesNameTheChoiceAndDoNotContainADottedAddress` 的期望改成下面这个测试，并加上脱敏断言：

```kotlin
@Test fun linesNameTheChoiceAndDoNotContainADottedAddress() {
    val lines = displayLines(listOf(center, cluster), cluster)
    assertEquals("fly.display list 0:Built-in Screen:1920x720:default 2:HDMI Screen:1920x720", lines[0])
    assertEquals("fly.display use id=2 HDMI Screen 1920x720", lines[1])
    assertEquals("fly.display none", displayLines(listOf(center), null).last())
    assertFalse(lines[1].contains("name="))
    assertEquals(lines[0], DiagnosticRedactor.redact(lines[0]))
    assertEquals(lines[1], DiagnosticRedactor.redact(lines[1]))
}
```

文件头补上：

```kotlin
import com.shilapi.xcertplay.DiagnosticRedactor
import org.junit.Assert.assertFalse
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.instrument.fly.FlyDisplayTest.linesNameTheChoiceAndDoNotContainADottedAddress --offline`

Expected: FAIL，期望字符串里没有 `name=`，实现里还有。

- [ ] **Step 3: Write minimal implementation**

`FlyDisplay.kt` 里选中行改为：

```kotlin
val choice = if (chosen == null) {
    "fly.display none"
} else {
    "fly.display use id=${chosen.id} ${chosen.name} ${chosen.width}x${chosen.height}"
}
```

在 `2026-11-20-cluster-presentation.md` 的一级标题下面插入：

```markdown
> **2026-11-21 起，软解出画以 `2026-11-21-cluster-buffer-paint.md` 为准。** 「性能」里「输出就是 Presentation 的 Surface」和「禁止 getOutputBuffer」作废。1885、displayId 1、Presentation、禁止 1028 和流 111 仍然有效。
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.instrument.fly.FlyDisplayTest --offline`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/com/shilapi/xcertplay/instrument/fly/FlyDisplay.kt \
  common/src/test/java/com/shilapi/xcertplay/instrument/fly/FlyDisplayTest.kt \
  docs/superpowers/plans/2026-11-20-cluster-presentation.md \
  docs/superpowers/plans/2026-11-21-cluster-buffer-paint.md
git commit -m "fix: keep the chosen cluster display in the diagnostic log"
```

---

### Task 2: 把 YUV 转成复用的 RGB565

**Files:**

- Create: `common/src/main/java/com/shilapi/xcertplay/instrument/fly/FlyYuv.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/instrument/fly/FlyYuvTest.kt`

**Interfaces:**

- Consumes: 无
- Produces: `data class FlyFrameMeta(val width: Int, val height: Int, val stride: Int, val slice: Int, val color: Int)`
- Produces: `fun frameMeta(width: Int, height: Int, stride: Int, slice: Int, color: Int): FlyFrameMeta?`
- Produces: `object FlyYuv`，`PLANAR = 19`，`SEMIPLANAR = 21`，`MAX_BYTES = 3 * 1024 * 1024`，`fun supported(color: Int): Boolean`，`fun toRgb565(yuv: ByteArray, yuvLength: Int, meta: FlyFrameMeta, out: ByteArray): Boolean`

`frameMeta` 拒绝宽或高不在 2..1920 / 2..720、或不是偶数、或 `supported` 为 false 的输入。`stride < width` 时用 `width`，`slice < height` 时用 `height`。

`toRgb565` 不分配。字节按 `toInt() and 0xFF` 读。BT.601 定点，结果钳在 0..255，再装成小端 RGB565（ARM 上 `Bitmap.Config.RGB_565` 的字节序）。平面格式 19：Y，然后 U，然后 V。半平面格式 21：Y，然后 UV 交错。色度按 2×2 取样。布局放不下或 `out` 不够 `width * height * 2` 时返回 false。

- [ ] **Step 1: Write the failing test**

```kotlin
package com.shilapi.xcertplay.instrument.fly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlyYuvTest {
    @Test fun aNeutralPixelIsWhite() {
        val meta = frameMeta(2, 2, 2, 2, FlyYuv.PLANAR)!!
        val yuv = byteArrayOf(235.toByte(), 235.toByte(), 235.toByte(), 235.toByte(), 128.toByte(), 128.toByte())
        val out = ByteArray(8)
        assertTrue(FlyYuv.toRgb565(yuv, yuv.size, meta, out))
        assertEquals(0xFF, out[0].toInt() and 0xFF)
        assertEquals(0xFF, out[1].toInt() and 0xFF)
    }

    @Test fun planarAndSemiplanarPlaceRedThenGray() {
        val y = ByteArray(8) { 81.toByte() }
        val planar = y + byteArrayOf(90.toByte(), 128.toByte(), 240.toByte(), 128.toByte())
        val semi = y + byteArrayOf(90.toByte(), 240.toByte(), 128.toByte(), 128.toByte())
        val planarMeta = frameMeta(4, 2, 4, 2, FlyYuv.PLANAR)!!
        val semiMeta = frameMeta(4, 2, 4, 2, FlyYuv.SEMIPLANAR)!!
        val planarOut = ByteArray(16)
        val semiOut = ByteArray(16)
        assertTrue(FlyYuv.toRgb565(planar, planar.size, planarMeta, planarOut))
        assertTrue(FlyYuv.toRgb565(semi, semi.size, semiMeta, semiOut))
        assertEquals(0x00, planarOut[0].toInt() and 0xFF)
        assertEquals(0xF8, planarOut[1].toInt() and 0xFF)
        assertEquals(0x69, planarOut[4].toInt() and 0xFF)
        assertEquals(0x4A, planarOut[5].toInt() and 0xFF)
        assertTrue(planarOut.contentEquals(semiOut))
    }

    @Test fun aShortBufferOrOddSizeIsRejected() {
        val meta = frameMeta(2, 2, 2, 2, FlyYuv.PLANAR)!!
        assertFalse(FlyYuv.toRgb565(ByteArray(3), 3, meta, ByteArray(8)))
        assertNull(frameMeta(3, 2, 3, 2, FlyYuv.PLANAR))
        assertNull(frameMeta(1920, 720, 1920, 720, 0))
        assertFalse(FlyYuv.supported(0))
    }
}
```

像素是先算好的：`Y=81,U=90,V=240` 得到 `0xF800`；`Y=81,U=128,V=128` 得到 `0x4A69`。测试不要把换算公式再写一遍。

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.instrument.fly.FlyYuvTest --offline`

Expected: FAIL，`FlyYuv` 不存在。

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.shilapi.xcertplay.instrument.fly

internal data class FlyFrameMeta(
    val width: Int,
    val height: Int,
    val stride: Int,
    val slice: Int,
    val color: Int,
)

internal fun frameMeta(width: Int, height: Int, stride: Int, slice: Int, color: Int): FlyFrameMeta? {
    if (width !in 2..1920 || height !in 2..720) return null
    if (width % 2 != 0 || height % 2 != 0) return null
    if (!FlyYuv.supported(color)) return null
    val safeStride = if (stride >= width) stride else width
    val safeSlice = if (slice >= height) slice else height
    return FlyFrameMeta(width, height, safeStride, safeSlice, color)
}

internal object FlyYuv {
    const val PLANAR = 19
    const val SEMIPLANAR = 21
    const val MAX_BYTES = 3 * 1024 * 1024

    fun supported(color: Int): Boolean = color == PLANAR || color == SEMIPLANAR

    fun toRgb565(yuv: ByteArray, yuvLength: Int, meta: FlyFrameMeta, out: ByteArray): Boolean {
        val chromaRows = meta.slice / 2
        val ySize = meta.stride * meta.slice
        val needed = if (meta.color == PLANAR) {
            ySize + 2 * (meta.stride / 2) * chromaRows
        } else {
            ySize + meta.stride * chromaRows
        }
        if (yuvLength < needed || out.size < meta.width * meta.height * 2) return false
        var dst = 0
        var y = 0
        while (y < meta.height) {
            var x = 0
            while (x < meta.width) {
                val yValue = sample(yuv, y * meta.stride + x)
                val chroma = if (meta.color == PLANAR) {
                    val row = (y / 2) * (meta.stride / 2) + x / 2
                    sample(yuv, ySize + row) to sample(yuv, ySize + (meta.stride / 2) * chromaRows + row)
                } else {
                    val row = (y / 2) * meta.stride + (x / 2) * 2
                    sample(yuv, ySize + row) to sample(yuv, ySize + row + 1)
                }
                write(out, dst, rgb565(yValue, chroma.first, chroma.second))
                dst += 2
                x++
            }
            y++
        }
        return true
    }

    private fun sample(yuv: ByteArray, index: Int): Int = yuv[index].toInt() and 0xFF

    private fun rgb565(yValue: Int, uValue: Int, vValue: Int): Int {
        val c = yValue - 16
        val d = uValue - 128
        val e = vValue - 128
        val r = clamp((298 * c + 409 * e + 128) shr 8)
        val g = clamp((298 * c - 100 * d - 208 * e + 128) shr 8)
        val b = clamp((298 * c + 516 * d + 128) shr 8)
        return ((r shr 3) shl 11) or ((g shr 2) shl 5) or (b shr 3)
    }

    private fun clamp(value: Int): Int = when {
        value < 0 -> 0
        value > 255 -> 255
        else -> value
    }

    private fun write(out: ByteArray, at: Int, rgb565: Int) {
        out[at] = (rgb565 and 0xFF).toByte()
        out[at + 1] = ((rgb565 shr 8) and 0xFF).toByte()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.instrument.fly.FlyYuvTest --offline`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/com/shilapi/xcertplay/instrument/fly/FlyYuv.kt \
  common/src/test/java/com/shilapi/xcertplay/instrument/fly/FlyYuvTest.kt
git commit -m "feat: convert cluster YUV into a reused RGB565 buffer"
```

---

### Task 3: 166 ms 只保留最新一帧，解码线程不等待

**Files:**

- Create: `common/src/main/java/com/shilapi/xcertplay/instrument/fly/FlyFrameSlot.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/instrument/fly/FlyFrameSlotTest.kt`

**Interfaces:**

- Consumes: `FlyFrameMeta`，`FlyYuv.supported`，`FlyYuv.MAX_BYTES`
- Produces: `class FlyFrameSlot`，`fun offer(nowMs: Long, src: ByteBuffer, meta: FlyFrameMeta): Boolean`，`fun take(): Triple<ByteArray, Int, FlyFrameMeta>?`，`fun release()`

`offer` 在这些情况下立刻返回 false，不替换槽里的字节：`src.remaining()` 不在 `1..MAX_BYTES`，颜色不支持，画图线程还没 `release`，或者距上次成功 `offer` 不足 166 ms。第一次 `offer` 必须成功。成功时把字节拷进已有数组；数组不够长才重新分配一次。`take` 在没有占住的帧时返回 null。

- [ ] **Step 1: Write the failing test**

```kotlin
package com.shilapi.xcertplay.instrument.fly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class FlyFrameSlotTest {
    private val meta = frameMeta(2, 2, 2, 2, FlyYuv.PLANAR)!!
    private val slot = FlyFrameSlot()

    @Test fun thePainterHoldsTheOnlyCopy() {
        assertTrue(slot.offer(0, bytes(1, 2, 3), meta))
        assertFalse(slot.offer(1_000, bytes(9, 9, 9), meta))
        val taken = slot.take()!!
        assertEquals(1, taken.first[0].toInt())
        slot.release()
        assertTrue(slot.offer(166, bytes(4, 5, 6), meta))
        val next = slot.take()!!
        assertSame(taken.first, next.first)
        assertEquals(4, next.first[0].toInt())
    }

    @Test fun aFrameInsideTheIntervalIsLeftInTheCodec() {
        assertTrue(slot.offer(0, bytes(1), meta))
        slot.release()
        assertFalse(slot.offer(165, bytes(2), meta))
    }

    @Test fun anOversizedOrUnsupportedFrameIsRefused() {
        assertFalse(slot.offer(0, ByteBuffer.allocate(FlyYuv.MAX_BYTES + 1), meta))
        val odd = FlyFrameMeta(2, 2, 2, 2, 0)
        assertFalse(slot.offer(0, bytes(1, 2), odd))
    }

    private fun bytes(vararg values: Int): ByteBuffer = ByteBuffer.wrap(ByteArray(values.size) { values[it].toByte() })
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.instrument.fly.FlyFrameSlotTest --offline`

Expected: FAIL，`FlyFrameSlot` 不存在。

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.shilapi.xcertplay.instrument.fly

import java.nio.ByteBuffer

internal class FlyFrameSlot(
    private val intervalMs: Long = 166L,
) {
    private val lock = Any()
    private var pending: ByteArray? = null
    private var pendingLength = 0
    private var pendingMeta: FlyFrameMeta? = null
    private var held = false
    private var lastOfferMs = Long.MIN_VALUE

    fun offer(nowMs: Long, src: ByteBuffer, meta: FlyFrameMeta): Boolean {
        val length = src.remaining()
        if (length !in 1..FlyYuv.MAX_BYTES || !FlyYuv.supported(meta.color)) return false
        synchronized(lock) {
            if (held) return false
            if (lastOfferMs != Long.MIN_VALUE && nowMs - lastOfferMs < intervalMs) return false
            val dest = pending?.takeIf { it.size >= length } ?: ByteArray(length).also { pending = it }
            src.duplicate().get(dest, 0, length)
            pendingLength = length
            pendingMeta = meta
            lastOfferMs = nowMs
            held = true
        }
        return true
    }

    fun take(): Triple<ByteArray, Int, FlyFrameMeta>? {
        synchronized(lock) {
            if (!held) return null
            val buf = pending ?: return null
            val meta = pendingMeta ?: return null
            return Triple(buf, pendingLength, meta)
        }
    }

    fun release() {
        synchronized(lock) { held = false }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.instrument.fly.FlyFrameSlotTest --offline`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/com/shilapi/xcertplay/instrument/fly/FlyFrameSlot.kt \
  common/src/test/java/com/shilapi/xcertplay/instrument/fly/FlyFrameSlotTest.kt
git commit -m "feat: keep one cluster frame for the painter"
```

---

### Task 4: 软解输出到缓冲区，画图不占解码线程

**Files:**

- Create: `common/src/main/java/com/shilapi/xcertplay/instrument/fly/FlyPainter.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/instrument/fly/FlyPainterTest.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/instrument/fly/FlyClusterDecoder.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyController.kt:363-369`

**Interfaces:**

- Consumes: `FlyFrameSlot`，`FlyYuv.toRgb565`，`frameMeta`
- Produces: `class FlyPainter`，`fun paintOne(): Boolean`。画成功且是第一次时记 `fly.display frame`。`toRgb565` 返回 false 时记一次 `fly.display paint layout`，并仍然 `release` 槽。`measure` 的合计超过 166 时记 `fly.display paint slow <ms>`，5 秒内不重复。
- Produces: `internal fun flyDecoderFailure(error: Throwable): String`
- Produces: `FlyClusterDecoder` 用 `configure(format, null, null, 0)`，`KEY_MAX_INPUT_SIZE` 为 `1 * 1024 * 1024`，`start` 之后只调用 `inputBuffers` / `outputBuffers`。`releaseOutputBuffer` 的第二个参数是 `false`。`getInputBuffer` 和 `getOutputBuffer` 不再出现。

`FlyPainter` 的构造参数：

```kotlin
internal class FlyPainter(
    private val slot: FlyFrameSlot,
    private val rgb: ByteArray,
    private val draw: (ByteArray, Int, Int) -> Boolean,
    private val log: (String) -> Unit,
    private val nowMs: () -> Long,
    private val measure: (() -> Unit) -> Long,
)
```

生产代码的 `measure` 用 `System.nanoTime()` 包住被测块。转换的 `measure` 结束之后、`draw` 之前调用 `slot.release()`。画图失败不记 `fly.display frame`。

`FlyClusterDecoder` 另外持有一个只在生产路径使用的画图线程，名字 `cluster-fly-paint`，守护线程，优先级 `THREAD_PRIORITY_LOWEST`。线程体只调用 `FlyPainter.paintOne()`；返回 false 时 `Thread.sleep(16)`。`release()` 先把循环标志置 false，再 `stop` 和 `release` 解码器。不 `join`。

画到 Surface 的函数放在 `FlyClusterDecoder.kt` 文件里，签名：

```kotlin
internal fun drawClusterBitmap(surface: Surface, bitmap: Bitmap, rgb: ByteBuffer, width: Int, height: Int): Boolean
```

`surface.isValid` 为 false，或 `lockCanvas(null)` 返回 null，就返回 false。否则 `copyPixelsFromBuffer` 后 `drawBitmap(bitmap, 0f, 0f, null)`，`finally` 里 `unlockCanvasAndPost`。不 `drawColor`，不建 `Paint`。`Bitmap.createBitmap(1920, 720, Bitmap.Config.RGB_565)` 只在第一次画出 1920×720 时做一次。宽高不是这张图的尺寸就返回 false，不另建一张。

`drain` 里输出缓冲的顺序是：用 `BufferInfo.offset` 和 `size` 设好 position/limit，`slot.offer`，`finally` 里 `releaseOutputBuffer(index, false)`。第一次拿到 `index >= 0` 就记 `fly.display buffer`，即使这一帧没有拷进槽。`INFO_OUTPUT_FORMAT_CHANGED` 时读 `KEY_COLOR_FORMAT`、`KEY_STRIDE`、`KEY_SLICE_HEIGHT`，交给 `frameMeta`。颜色不支持时记一次 `fly.display color <n>`。`INFO_OUTPUT_BUFFERS_CHANGED` 时重新取 `outputBuffers`。

150 ms 的计时仍然包住 `queue` 和 `drain`，不包住 `paintOne`。慢路径的 `flush`、`start`、重新送 SPS/PPS 保持现在的顺序。

`ClusterFlyController.processFrame` 的 catch 改为：

```kotlin
} catch (error: RuntimeException) {
    log(flyDecoderFailure(error))
    decoder.release()
    flyDecoder = null
    onPictureFailed()
}
```

画图线程里的异常只记 `fly.display paint failed <类名>` 一次，然后让线程退出。不要从画图线程调用 `onPictureFailed`。

- [ ] **Step 1: Write the failing test**

```kotlin
package com.shilapi.xcertplay.instrument.fly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class FlyPainterTest {
    private val slot = FlyFrameSlot()
    private val logs = mutableListOf<String>()
    private var now = 0L
    private var measured = 10L

    private fun painter(draw: (ByteArray, Int, Int) -> Boolean = { _, _, _ -> true }) = FlyPainter(
        slot = slot,
        rgb = ByteArray(8),
        draw = draw,
        log = logs::add,
        nowMs = { now },
        measure = { block -> block(); measured },
    )

    @Test fun aPostedFrameIsLoggedOnceAndTheSlotIsFree() {
        val meta = frameMeta(2, 2, 2, 2, FlyYuv.PLANAR)!!
        val yuv = byteArrayOf(235.toByte(), 235.toByte(), 235.toByte(), 235.toByte(), 128.toByte(), 128.toByte())
        assertTrue(slot.offer(0, ByteBuffer.wrap(yuv), meta))
        assertTrue(painter().paintOne())
        assertEquals(listOf("fly.display frame"), logs)
        assertTrue(slot.offer(166, ByteBuffer.wrap(yuv), meta))
    }

    @Test fun aRejectedCanvasDoesNotCountAsAFrameAndStillReleases() {
        val meta = frameMeta(2, 2, 2, 2, FlyYuv.PLANAR)!!
        val yuv = byteArrayOf(235.toByte(), 235.toByte(), 235.toByte(), 235.toByte(), 128.toByte(), 128.toByte())
        assertTrue(slot.offer(0, ByteBuffer.wrap(yuv), meta))
        assertFalse(painter { _, _, _ -> false }.paintOne())
        assertFalse(logs.contains("fly.display frame"))
        assertTrue(slot.offer(166, ByteBuffer.wrap(yuv), meta))
    }

    @Test fun aSlowPaintIsLoggedAtMostEveryFiveSeconds() {
        measured = 200
        val meta = frameMeta(2, 2, 2, 2, FlyYuv.PLANAR)!!
        val yuv = byteArrayOf(235.toByte(), 235.toByte(), 235.toByte(), 235.toByte(), 128.toByte(), 128.toByte())
        val painter = painter()
        assertTrue(slot.offer(0, ByteBuffer.wrap(yuv), meta))
        painter.paintOne()
        slot.release()
        now = 1_000
        assertTrue(slot.offer(166, ByteBuffer.wrap(yuv), meta))
        painter.paintOne()
        assertEquals(1, logs.count { it.startsWith("fly.display paint slow ") })
        slot.release()
        now = 5_000
        assertTrue(slot.offer(332, ByteBuffer.wrap(yuv), meta))
        painter.paintOne()
        assertEquals(2, logs.count { it.startsWith("fly.display paint slow ") })
    }

    @Test fun decoderFailureNamesTheMethodWithoutADeviceName() {
        val error = IllegalStateException("codec is not in the Executing state")
        error.stackTrace = arrayOf(StackTraceElement("FlyClusterDecoder", "drain", "FlyClusterDecoder.kt", 140))
        val line = flyDecoderFailure(error)
        assertEquals(
            "fly.display decoder failed IllegalStateException codec is not in the Executing state at FlyClusterDecoder.kt:140",
            line,
        )
        assertFalse(line.contains("name="))
        assertEquals(line, com.shilapi.xcertplay.DiagnosticRedactor.redact(line))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.instrument.fly.FlyPainterTest --offline`

Expected: FAIL，`FlyPainter` 不存在。

- [ ] **Step 3: Write minimal implementation**

`FlyPainter.kt`：

```kotlin
package com.shilapi.xcertplay.instrument.fly

internal class FlyPainter(
    private val slot: FlyFrameSlot,
    private val rgb: ByteArray,
    private val draw: (ByteArray, Int, Int) -> Boolean,
    private val log: (String) -> Unit,
    private val nowMs: () -> Long,
    private val measure: (() -> Unit) -> Long,
) {
    private var frameLogged = false
    private var layoutLogged = false
    private var lastSlowLogMs = Long.MIN_VALUE

    fun paintOne(): Boolean {
        val taken = slot.take() ?: return false
        val (yuv, length, meta) = taken
        var wrote = false
        val spentConvert = try {
            measure { wrote = FlyYuv.toRgb565(yuv, length, meta, rgb) }
        } finally {
            slot.release()
        }
        if (!wrote) {
            if (!layoutLogged) {
                layoutLogged = true
                log("fly.display paint layout")
            }
            return false
        }
        var posted = false
        val spentDraw = measure { posted = draw(rgb, meta.width, meta.height) }
        if (!posted) return false
        if (!frameLogged) {
            frameLogged = true
            log("fly.display frame")
        }
        val spent = spentConvert + spentDraw
        val now = nowMs()
        if (spent > PAINT_BUDGET_MS && (lastSlowLogMs == Long.MIN_VALUE || now - lastSlowLogMs >= SLOW_LOG_GAP_MS)) {
            lastSlowLogMs = now
            log("fly.display paint slow $spent")
        }
        return true
    }

    private companion object {
        const val PAINT_BUDGET_MS = 166L
        const val SLOW_LOG_GAP_MS = 5_000L
    }
}

internal fun flyDecoderFailure(error: Throwable): String {
    val top = error.stackTrace.firstOrNull()
    val at = if (top == null) "none" else "${top.fileName}:${top.lineNumber}"
    val detail = error.message?.lineSequence()?.firstOrNull()?.take(80) ?: ""
    return "fly.display decoder failed ${error.javaClass.simpleName} $detail at $at"
}
```

`FlyClusterDecoder` 的输出路径改成下面这样。类里原有的 `awaitingIdr`、`ptsUs`、`inputWarned`、150 ms 慢路径和 SPS/PPS 重送保留。删掉 `getInputBuffer` 和 `releaseOutputBuffer(index, true)`。

```kotlin
internal fun drawClusterBitmap(
    surface: android.view.Surface,
    bitmap: android.graphics.Bitmap,
    rgb: java.nio.ByteBuffer,
    width: Int,
    height: Int,
): Boolean {
    if (!surface.isValid || bitmap.width != width || bitmap.height != height) return false
    rgb.rewind()
    rgb.limit(width * height * 2)
    bitmap.copyPixelsFromBuffer(rgb)
    val canvas = surface.lockCanvas(null) ?: return false
    return try {
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        true
    } finally {
        surface.unlockCanvasAndPost(canvas)
    }
}
```

解码器字段和创建：

```kotlin
private val slot = FlyFrameSlot()
private val rgb = ByteArray(1920 * 720 * 2)
private val rgbBuffer = java.nio.ByteBuffer.wrap(rgb)
private var bitmap: android.graphics.Bitmap? = null
@Volatile private var painting = false
private var paintThread: Thread? = null
private var meta: FlyFrameMeta? = null
private var colorLogged = false
private var bufferLogged = false
@Suppress("DEPRECATION")
private var inputBuffers: Array<java.nio.ByteBuffer> = emptyArray()
@Suppress("DEPRECATION")
private var outputBuffers: Array<java.nio.ByteBuffer> = emptyArray()

private fun startPainter(surface: android.view.Surface) {
    val painter = FlyPainter(
        slot = slot,
        rgb = rgb,
        draw = { _, width, height ->
            val current = bitmap ?: android.graphics.Bitmap.createBitmap(1920, 720, android.graphics.Bitmap.Config.RGB_565).also { bitmap = it }
            drawClusterBitmap(surface, current, rgbBuffer, width, height)
        },
        log = log,
        nowMs = { System.nanoTime() / 1_000_000L },
        measure = { block ->
            val started = System.nanoTime()
            block()
            (System.nanoTime() - started) / 1_000_000L
        },
    )
    painting = true
    paintThread = Thread({
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LOWEST)
        while (painting) {
            try {
                if (!painter.paintOne()) Thread.sleep(16)
            } catch (error: RuntimeException) {
                log("fly.display paint failed ${error.javaClass.simpleName}")
                painting = false
            }
        }
    }, "cluster-fly-paint").also {
        it.isDaemon = true
        it.start()
    }
}
```

`slot`、`rgb`、`rgbBuffer` 必须声明在 `codec = create(...)` 之前。`create` 里成功启动的那一段是：

```kotlin
format.setInteger(android.media.MediaFormat.KEY_MAX_INPUT_SIZE, 1 * 1024 * 1024)
format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(START_CODE + sps))
format.setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(START_CODE + pps))
codec.configure(format, null, null, 0)
codec.start()
@Suppress("DEPRECATION")
inputBuffers = codec.inputBuffers
@Suppress("DEPRECATION")
outputBuffers = codec.outputBuffers
startPainter(surface)
return codec
```

`release()` 第一行是 `painting = false`。不要 `join` 画图线程。

`queue` 用缓存的输入数组，不再调用 `getInputBuffer`：

```kotlin
private fun queue(codec: android.media.MediaCodec, nalu: ByteArray) {
    var index = codec.dequeueInputBuffer(0)
    if (index < 0) {
        drain(codec)
        index = codec.dequeueInputBuffer(0)
    }
    if (index < 0 || index >= inputBuffers.size) {
        awaitingIdr = true
        return
    }
    val buffer = inputBuffers[index]
    buffer.clear()
    if (buffer.remaining() < nalu.size) {
        codec.queueInputBuffer(index, 0, 0, ptsUs, 0)
        awaitingIdr = true
        if (!inputWarned) {
            inputWarned = true
            log("fly.display input short ${nalu.size}")
        }
        return
    }
    buffer.put(nalu)
    codec.queueInputBuffer(index, 0, nalu.size, ptsUs, 0)
    ptsUs += FRAME_US
}
```

`drain`：

```kotlin
private fun drain(codec: android.media.MediaCodec) {
    val info = android.media.MediaCodec.BufferInfo()
    while (true) {
        val index = codec.dequeueOutputBuffer(info, 0)
        when {
            index >= 0 -> {
                try {
                    if (!bufferLogged) {
                        bufferLogged = true
                        log("fly.display buffer")
                    }
                    val current = meta
                    if (current != null && info.size > 0 && index < outputBuffers.size) {
                        val buffer = outputBuffers[index]
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        slot.offer(System.nanoTime() / 1_000_000L, buffer, current)
                    }
                } finally {
                    codec.releaseOutputBuffer(index, false)
                }
            }
            index == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> readFormat(codec.outputFormat)
            index == android.media.MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                @Suppress("DEPRECATION")
                outputBuffers = codec.outputBuffers
            }
            else -> return
        }
    }
}

private fun readFormat(format: android.media.MediaFormat) {
    val width = if (format.containsKey(android.media.MediaFormat.KEY_WIDTH)) format.getInteger(android.media.MediaFormat.KEY_WIDTH) else 0
    val height = if (format.containsKey(android.media.MediaFormat.KEY_HEIGHT)) format.getInteger(android.media.MediaFormat.KEY_HEIGHT) else 0
    val stride = if (format.containsKey(android.media.MediaFormat.KEY_STRIDE)) format.getInteger(android.media.MediaFormat.KEY_STRIDE) else 0
    val slice = if (format.containsKey(android.media.MediaFormat.KEY_SLICE_HEIGHT)) format.getInteger(android.media.MediaFormat.KEY_SLICE_HEIGHT) else 0
    val color = if (format.containsKey(android.media.MediaFormat.KEY_COLOR_FORMAT)) format.getInteger(android.media.MediaFormat.KEY_COLOR_FORMAT) else 0
    val parsed = frameMeta(width, height, stride, slice, color)
    if (parsed == null && !FlyYuv.supported(color) && !colorLogged) {
        colorLogged = true
        log("fly.display color $color")
    }
    meta = parsed
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :common:compileDebugKotlin :common:testDebugUnitTest --tests com.shilapi.xcertplay.instrument.fly.FlyPainterTest --tests com.shilapi.xcertplay.instrument.fly.FlyYuvTest --tests com.shilapi.xcertplay.instrument.fly.FlyFrameSlotTest --tests com.shilapi.xcertplay.instrument.fly.FlyDisplayTest --offline`

Expected: PASS

然后再确认软解不再把 Surface 当输出：

```bash
rg "getInputBuffer|getOutputBuffer|releaseOutputBuffer\(index, true\)" common/src/main/java/com/shilapi/xcertplay/instrument/fly/FlyClusterDecoder.kt
```

Expected: 没有匹配。`configure(format, null, null, 0)` 还在文件里。

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/com/shilapi/xcertplay/instrument/fly/FlyPainter.kt \
  common/src/test/java/com/shilapi/xcertplay/instrument/fly/FlyPainterTest.kt \
  common/src/main/java/com/shilapi/xcertplay/instrument/fly/FlyClusterDecoder.kt \
  common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyController.kt
git commit -m "fix: paint the cluster from a software decoder buffer"
```

---

## 自检

- 链路：1885、选屏、`Presentation`、禁止 1028 和流 111 没有任务去改。Task 1 只改日志文字，并在旧计划标题下写明哪两句作废。
- 昨天的异常：Task 4 不再把 Surface 交给软解，输入输出都走 `getInputBuffers` / `getOutputBuffers`，异常行带上消息和 `文件:行号`。
- 性能：150 ms 不含画图；166 ms 才拷一帧；槽只有一块并且忙时立刻放弃；画图线程优先级更低；输入上限 1 MB；缓冲一次分配。对应 Task 3 和 Task 4。
- 出画失败不拆屏。解码失败仍然拆屏。
- 车上成功标准是 `use id=1`、`buffer`、`frame` 三行同时在，中控仍在出画。
