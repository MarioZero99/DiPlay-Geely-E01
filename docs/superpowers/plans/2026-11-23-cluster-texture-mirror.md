# 星瑞 E01：仪表纹理镜像实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 三指左滑时，中控这路 `OMX.MTK.VIDEO.DECODER.AVC` 只解一次，输出到应用自己的 `SurfaceTexture`，GPU 把同一张纹理贴到中控和 `displayId 1` 的 `SurfaceView` 上。

**Architecture:** 1885、HDMI `displayId 1`、`Presentation` 保持不动。飞屏期间解码器放开中控 `SurfaceView`，改画到 `Surface(SurfaceTexture)`。一条 GL 线程 `updateTexImage` 后贴两块 `EGLSurface`：中控原来的 Surface，和仪表 `Presentation` 里的 Surface。飞屏结束或任一步失败，解码器交还中控 `SurfaceView`。`OMX.google.h264.decoder`、YUV 拷贝和 6 fps 软解从这条路径删除。

**Tech Stack:** Kotlin，Android 5.1 / API 22，EGL14，GLES2，`GL_TEXTURE_EXTERNAL_OES`，JUnit4。不引入 NDK 解码库。

**Spec:** 本文件即为规格。它替换 `docs/superpowers/plans/2026-11-21-cluster-buffer-paint.md` 的软解出画。1885、displayId 1、`Presentation`、禁止 1028、禁止流 111、禁止第二路 MTK 硬解，仍以 `docs/superpowers/plans/2026-11-20-cluster-presentation.md` 为准。车上证据是 `dist/supabase-logs/DiPlay-20261123-102430-546.txt` 和 2026-11-20 高德投屏开着时的 `dumpsys`。

## Global Constraints

- 最低 API 级别为 22。禁止 `PixelCopy`、`ImageReader`、`MediaCodec.getOutputImage`、`Display.getMode`、`MediaCodec.setOutputSurface`。换输出窗口走现有的 `release` 再 `configure`。
- 不修改 `/system`，不改 `Navi.apk`、`XCNaviService`、`Extraservice`。
- 不调用 `DimInteraction.create()`，不调用 `setPresentationToDimSwitch`，不发 `ecarx.intent.action.DIM_PRESENTATION_CTRL`。
- 不向手机请求 `SCREEN_TYPE_ALT`（流 111）。不恢复 `1028`、`10.255.255.101`、RTP 或第二遍编码。
- 不创建第二个 `OMX.MTK.VIDEO.DECODER.AVC`。飞屏不再创建 `OMX.google.h264.decoder`。
- `csd-0` / `csd-1` 仍由中控解码器带 `00 00 00 01`。飞屏不自己组 CSD。
- 日志里禁止出现 `name=`。
- 每个代码任务都先写会失败的测试，再写实现。
- 平时中控仍是 `SurfaceView` 直出（`Video output: SURFACE`）。只有飞屏这一段才把解码器改到纹理上。

## 已经确定的细节

这些不是上车才能知道的事。实现时按这里写，不要再留探针。

`MediaCodec.configure` 的窗口参数是 `android.view.Surface`。`SurfaceView` 的 Surface 和 `Surface(SurfaceTexture)` 在 native 层都是 `ANativeWindow`。`OMX.MTK.VIDEO.DECODER.AVC` 分不出生产者是谁。`VideoDecoder.configureDecoder` 已经对中控 Surface 调用 `configure(format, surface, null, 0)` 并 `start()`，2026-11-23 10:22:49 这路画出了第一帧。

同一份 `CarPlayHostActivity` 里，`TextureView` 路径就是 `Surface(texture)` 再 `attachSurface`。API 22 默认走 `SurfaceView`，原因写在 `CarPlayVideoSurface.kt`：`TextureView` 多一次合成，不是解码器拒绝纹理。这台车的诊断日志里没有 `Video output: TEXTURE`，因为默认是 `SURFACE`，不是因为纹理模式失败过。

API 22 没有 `setOutputSurface`。`VideoDecoder.changeSurface` 在 `SDK_INT < 23` 时已经是 `releaseDecoder()` 再 `configureDecoder(lastConfig)`。`configureDecoder` 在解码器为空时会 `referenceChain.reset()`，随后 `requestKeyFrameIfDue()` 向手机要关键帧。2026-11-23 10:23:00 的恢复就是这条路：拆掉解码器、要关键帧、`avcc=52252`、第一帧画出。飞屏换窗口用这一条，不新写一套解码器生命周期。

必须写对的是消费顺序，否则中控会停住。`SurfaceTexture` 的 `BufferQueue` 没有人 `updateTexImage`，硬解的输出队列会满，中控黑屏。顺序固定为四步，不能并成一步：

1. `replaceOutputSurface(MAIN, null)`，等回调。解码器放开中控 Surface。
2. 在这条 GL 线程上创建 `SurfaceTexture`，`updateTexImage` 的监听挂上，再对中控 Surface 和仪表 Surface 各建一个 `EGLSurface`。
3. `replaceOutputSurface(MAIN, Surface(surfaceTexture))`，等回调。回调 `false` 就是 `configure` 失败。
4. 第一张仪表 `eglSwapBuffers` 成功之后才进入 `PUSHING`，并取消 5 秒计时。解码器 `configure` 成功只表示窗口换完，不计时取消，也不进 `PUSHING`。

任一步失败，以及换窗口做到一半就收场：先把中控 Surface 交还解码器，再拆 GL。失败收层再发 `FLY_RIGHT`。用户右滑或断开已经在发 `FLY_RIGHT`，拆窗口时不再发第二次。中控不能停在第 1 步。`begin` 和 `end` 用代数作废过期回调。

高德已经证明像素通路。2026-11-20 10:49，`displayId 1` 上两块 `com.autonavi.amapauto` 的 `SurfaceView` 一有缓冲，`mHasContent` 变为 true，仪表上就是地图。我们的 `Presentation` 用的是同一块屏上的 `SurfaceView`。SurfaceFlinger 不区分缓冲来自 Canvas、硬解还是 `eglSwapBuffers`。

还要上车看的只有验收：玻璃上是否出现和中控同一张图。那不是设计里的未知分支。

## 性能

飞屏这一段，中控每一帧多一次 1920×720 的 OES 纹理贴图，贴两次（中控、仪表）。没有第二路解码，没有 YUV 拷贝，没有 150 ms `flush`。GL 线程名 `cluster-fly-mirror`，优先级 `THREAD_PRIORITY_DISPLAY`。这一帧就是驾驶员正在看的中控画面，不能放到 `THREAD_PRIORITY_BACKGROUND`。

单次贴图超过 32 ms 时记 `fly.display paint slow <ms>`，同一条 5 秒内只记一次。这行不拆屏，也不换回 `SurfaceView`。

换窗口的空窗就是一次关键帧。2026-11-23 从要关键帧到 IDR 约 2.1 秒。仪表 Surface 一到就取消原来的 3 秒「cluster silent」计时，另起 5 秒。5 秒内没有第一张仪表帧，按镜像失败收场，发 `FLY_RIGHT`。

## 车上怎么算成功

同一轮左滑里出现这三行，并且没有紧接着的 `fly.display mirror failed`，也没有飞屏自己发出的 `1885 right`：

- `fly.display use id=1 HDMI 屏幕 1920x720`
- `fly.display mirror`
- `fly.display frame`

中控在换窗口之后仍有 `Video: first frame rendered`。右滑或断开时有一次 `1885 right`，那是用户收层，不是失败。

上车前先关掉高德自己的仪表投屏。两家的 1920×720 层会叠在同一块 `displayId 1` 上。

---

### Task 1: 换窗口的顺序机器

**Files:**

- Create: `common/src/main/java/com/shilapi/xcertplay/instrument/fly/FlyOutputHandoff.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/instrument/fly/FlyOutputHandoffTest.kt`

**Interfaces:**

- Consumes: 无
- Produces: `class FlyOutputHandoff(replaceOutput: (Surface?, (Boolean) -> Unit) -> Unit, openMirror: (Surface, Surface, (Surface) -> Unit, () -> Unit) -> Unit, closeMirror: () -> Unit)`
- Produces: `fun begin(center: Surface, cluster: Surface, onReady: () -> Unit, onFailed: () -> Unit)`
- Produces: `fun end(onDone: () -> Unit)`
- `onReady` 只表示解码器已经换到纹理上。它不表示仪表上有帧，调用方不能凭它进入 `PUSHING`。
- `end` 在 `begin` 的任何阶段都把中控 Surface 交还解码器，然后 `closeMirror`，最后 `onDone`。进行中的 `begin` 回调因代数不符直接返回，不得再 `openMirror` 或 `onReady`。
- 第二次 `end` 若第一次还在交还，不再发一次 `replaceOutput`，等第一次的 `onDone` 之后再调用这次的 `onDone`。
- `replaceOutput` 的回调在调用者已经切回主线程之后进入。`openMirror` 的第四个参数是 GL 建立失败。第三个参数把 `Surface(SurfaceTexture)` 交回来。`closeMirror` 没打开过也要安全。

- [ ] **Step 1: Write the failing test**

```kotlin
package com.shilapi.xcertplay.instrument.fly

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [22])
class FlyOutputHandoffTest {
    private val calls = mutableListOf<String>()
    private var center: Surface? = null

    @Test fun beginDetachesBeforeTheMirrorExists() {
        val handoff = handoff(detachOk = true, attachOk = true)
        handoff.begin(newCenter(), surface("cluster"), onReady = { calls += "ready" }, onFailed = { calls += "failed" })
        assertEquals(listOf("detach", "open", "attach", "ready"), calls)
    }

    @Test fun aFailedAttachRestoresTheCenterAndDoesNotStayOpen() {
        val handoff = handoff(detachOk = true, attachOk = false)
        handoff.begin(newCenter(), surface("cluster"), onReady = { calls += "ready" }, onFailed = { calls += "failed" })
        assertEquals(listOf("detach", "open", "attach", "close", "restore", "failed"), calls)
    }

    @Test fun aBrokenMirrorRestoresTheCenter() {
        val handoff = handoff(detachOk = true, attachOk = true, mirrorBreaks = true)
        handoff.begin(newCenter(), surface("cluster"), onReady = { calls += "ready" }, onFailed = { calls += "failed" })
        assertEquals(listOf("detach", "open", "restore", "failed"), calls)
    }

    @Test fun endWhileShowingRestoresThenCloses() {
        val handoff = handoff(detachOk = true, attachOk = true)
        handoff.begin(newCenter(), surface("cluster"), onReady = {}, onFailed = {})
        calls.clear()
        handoff.end { calls += "done" }
        assertEquals(listOf("restore", "close", "done"), calls)
    }

    @Test fun endDuringDetachRestoresTheCenterAndDropsTheLateCallback() {
        var deliverDetach: ((Boolean) -> Unit)? = null
        val handoff = FlyOutputHandoff(
            replaceOutput = { surface, applied ->
                val kind = when {
                    surface == null -> "detach"
                    surface === center -> "restore"
                    else -> "attach"
                }
                calls += kind
                if (kind == "detach") deliverDetach = applied else applied(true)
            },
            openMirror = { _, _, _, _ -> calls += "open" },
            closeMirror = { calls += "close" },
        )
        handoff.begin(newCenter(), surface("cluster"), onReady = { calls += "ready" }, onFailed = { calls += "failed" })
        handoff.end { calls += "done" }
        deliverDetach?.invoke(true)
        assertEquals(listOf("detach", "restore", "close", "done"), calls)
    }

    private fun newCenter(): Surface = surface("center").also { center = it }

    private fun surface(name: String) = Surface(android.graphics.SurfaceTexture(name.hashCode()))

    private fun handoff(detachOk: Boolean, attachOk: Boolean, mirrorBreaks: Boolean = false) =
        FlyOutputHandoff(
            replaceOutput = { surface, applied ->
                val kind = when {
                    surface == null -> "detach"
                    surface === center -> "restore"
                    else -> "attach"
                }
                calls += kind
                applied(if (kind == "detach") detachOk else if (kind == "restore") true else attachOk)
            },
            openMirror = { _, _, onTexture, onBroken ->
                calls += "open"
                if (mirrorBreaks) onBroken() else onTexture(surface("texture"))
            },
            closeMirror = { calls += "close" },
        )
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :common:testDebugUnitTest --offline --tests com.shilapi.xcertplay.instrument.fly.FlyOutputHandoffTest`

Expected: FAIL，`FlyOutputHandoff` 不存在。

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.shilapi.xcertplay.instrument.fly

import android.view.Surface

/**
 * Moves the centre decoder onto a texture and back.
 *
 * The decoder must release the centre Surface before EGL creates a window on it, and must be
 * pointed back at that Surface before the mirror is destroyed. The callbacks arrive on the thread
 * the caller used to deliver `replaceOutput`'s result.
 */
internal class FlyOutputHandoff(
    private val replaceOutput: (Surface?, (Boolean) -> Unit) -> Unit,
    private val openMirror: (Surface, Surface, (Surface) -> Unit, () -> Unit) -> Unit,
    private val closeMirror: () -> Unit,
) {
    private enum class Phase { IDLE, DETACHING, OPENING, ATTACHING, SHOWING, RESTORING }

    private var center: Surface? = null
    private var generation = 0
    private var phase = Phase.IDLE
    private val afterRestore = mutableListOf<() -> Unit>()

    fun begin(center: Surface, cluster: Surface, onReady: () -> Unit, onFailed: () -> Unit) {
        val ticket = ++generation
        this.center = center
        phase = Phase.DETACHING
        replaceOutput(null) { detached ->
            if (ticket != generation) return@replaceOutput
            if (!detached) {
                fail(ticket, onFailed)
                return@replaceOutput
            }
            phase = Phase.OPENING
            openMirror(center, cluster, { texture ->
                if (ticket != generation) return@openMirror
                phase = Phase.ATTACHING
                replaceOutput(texture) { attached ->
                    if (ticket != generation) return@replaceOutput
                    if (!attached) {
                        closeMirror()
                        fail(ticket, onFailed)
                        return@replaceOutput
                    }
                    phase = Phase.SHOWING
                    onReady()
                }
            }, {
                if (ticket != generation) return@openMirror
                fail(ticket, onFailed)
            })
        }
    }

    fun end(onDone: () -> Unit) {
        if (phase == Phase.IDLE) {
            onDone()
            return
        }
        if (phase == Phase.RESTORING) {
            afterRestore += onDone
            return
        }
        val ticket = ++generation
        val back = center
        phase = Phase.RESTORING
        if (back == null) {
            closeMirror()
            finish(onDone)
            return
        }
        replaceOutput(back) {
            if (ticket != generation) return@replaceOutput
            closeMirror()
            finish(onDone)
        }
    }

    private fun fail(ticket: Int, onFailed: () -> Unit) {
        val back = center
        phase = Phase.RESTORING
        if (back == null) {
            finish(onFailed)
            return
        }
        replaceOutput(back) {
            if (ticket != generation) return@replaceOutput
            finish(onFailed)
        }
    }

    private fun finish(onDone: () -> Unit) {
        phase = Phase.IDLE
        center = null
        onDone()
        val pending = afterRestore.toList()
        afterRestore.clear()
        pending.forEach { it() }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :common:testDebugUnitTest --offline --tests com.shilapi.xcertplay.instrument.fly.FlyOutputHandoffTest`

Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/com/shilapi/xcertplay/instrument/fly/FlyOutputHandoff.kt \
  common/src/test/java/com/shilapi/xcertplay/instrument/fly/FlyOutputHandoffTest.kt
git commit -m "feat: hand the center decoder to a cluster mirror and back"
```

---

### Task 2: 解码器换窗口的完成回调

**Files:**

- Modify: `shared/src/main/java/com/shilapi/xcertplay/media/VideoDecodeQueue.kt`（`SurfaceChanged`）
- Modify: `shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt`（`setSurface`、`VideoDecoder.changeSurface`）
- Test: `shared/src/test/java/com/shilapi/xcertplay/media/VideoDecodeQueueTest.kt`

**Interfaces:**

- Consumes: 现有 `fun setSurface(type: Int, surface: Surface)` 保持原样，给中控平时的 `SurfaceView` 用。
- Produces: `fun replaceOutputSurface(type: Int, surface: Surface?, applied: (Boolean) -> Unit)`
- `applied` 在解码线程调用。`true` 表示窗口已经是这次传入的 Surface：`null` 时解码器已 `release`；非 null 时 `configure` 和 `start` 已成功。`false` 表示 `configure` 抛错，解码器不在这块 Surface 上。

- [ ] **Step 1: Write the failing test**

在 `VideoDecodeQueueTest` 增加：

```kotlin
@Test fun aSurfaceChangeKeepsItsCallback() {
    val queue = VideoDecodeQueue()
    var applied = false
    queue.offer(VideoJob.SurfaceChanged(null) { applied = true })
    val job = queue.poll(0) as VideoJob.SurfaceChanged
    job.applied(true)
    assertTrue(applied)
}
```

`SurfaceChanged` 现在是 `data class SurfaceChanged(val surface: Surface?)`。这一步先改签名再让测试编译。若测试在签名改完前就写，编译失败即红。

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :shared:testDebugUnitTest --offline --tests com.shilapi.xcertplay.media.VideoDecodeQueueTest`

Expected: FAIL，`SurfaceChanged` 没有 `applied`。

- [ ] **Step 3: Write minimal implementation**

`VideoJob.SurfaceChanged` 改为：

```kotlin
data class SurfaceChanged(
    val surface: Surface?,
    val applied: (Boolean) -> Unit = {},
) : VideoJob
```

`AndroidMediaSink` 增加，原 `setSurface(type, surface)` 不改：

```kotlin
fun replaceOutputSurface(type: Int, surface: Surface?, applied: (Boolean) -> Unit) {
    if (surface == null) surfaces.remove(type) else surfaces[type] = surface
    val decoder = videoDecoders[type]
    if (decoder == null) {
        applied(true)
        return
    }
    decoder.setSurface(surface, applied)
}
```

`VideoDecoder.setSurface` 改为把回调放进 `VideoJob.SurfaceChanged`。`changeSurface` 的三条出路都要调用一次回调，不能漏：

- `surface == null`：`releaseDecoder()` 之后 `applied(true)`。
- `SDK_INT >= 23` 且 `setOutputSurface` 成功：这块车是 API 22，走不到。回调仍 `applied(true)`，避免以后编译到更高 API 时卡住飞屏。
- `SDK_INT < 23`，或 `setOutputSurface` 抛错：`releaseDecoder()` 再 `configureDecoder`。`configureDecoder` 要能把成败返回出来。现在它失败时把 `decoder` 留成 null 并 `report(...)`。改成返回 `Boolean`：`decoder != null` 为 true。`changeSurface` 用这个返回值调用 `applied`。配置没变的早退路径（解码器还在、csd 相同）在换窗口时不会发生，因为 `releaseDecoder` 已经把 `decoder` 置空。不要为了换窗口去走那条早退。

`configureDecoder` 里 `outputSurface` 为 null 时现在直接 return。换窗口的 null 情况由 `changeSurface` 处理，不要进 `configureDecoder`。

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :shared:testDebugUnitTest --offline --tests com.shilapi.xcertplay.media.VideoDecodeQueueTest`

Expected: PASS。已有的 `SurfaceChanged(null)` 调用因为 `applied` 有默认值，仍然编译。

- [ ] **Step 5: Commit**

```bash
git add shared/src/main/java/com/shilapi/xcertplay/media/VideoDecodeQueue.kt \
  shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt \
  shared/src/test/java/com/shilapi/xcertplay/media/VideoDecodeQueueTest.kt
git commit -m "feat: report when the video decoder finishes changing windows"
```

---

### Task 3: 一块纹理贴中控和仪表

**Files:**

- Create: `common/src/main/java/com/shilapi/xcertplay/instrument/fly/FlyTextureMirror.kt`
- Create: `common/src/main/java/com/shilapi/xcertplay/instrument/fly/FlyMirrorLog.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/instrument/fly/FlyMirrorLogTest.kt`

**Interfaces:**

- Consumes: Task 1 的 `openMirror` / `closeMirror` 形状。
- Produces: `class FlyTextureMirror(private val log: (String) -> Unit)`
- Produces: `fun open(center: Surface, cluster: Surface, onTexture: (Surface) -> Unit, onFrame: () -> Unit, onBroken: () -> Unit)`
- Produces: `fun close()`
- Produces: `fun FlyMirrorLog.mirror(): String` = `fly.display mirror`
- Produces: `fun FlyMirrorLog.frame(): String` = `fly.display frame`
- Produces: `fun FlyMirrorLog.failed(reason: String): String` = `fly.display mirror failed <reason>`
- `onTexture` 在 GL 线程、两块 `EGLSurface` 和 `SurfaceTexture` 监听都就绪之后调用一次，然后马上回到帧循环。不得在 `onTexture` 里等待解码器。
- `onFrame` 在第一次仪表 `eglSwapBuffers` 返回 true 时调用一次，仍在 GL 线程。这是进入 `PUSHING` 的唯一信号。
- `onBroken` 在 EGL 初始化失败时调用一次，调用前已经释放这次 `open` 建过的 EGL。`close()` 在没打开、打开失败、或已经 `close` 过时都是空操作，并且可以从主线程调用。

- [ ] **Step 1: Write the failing test**

```kotlin
package com.shilapi.xcertplay.instrument.fly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FlyMirrorLogTest {
    @Test fun linesDoNotCarryANameField() {
        val lines = listOf(FlyMirrorLog.mirror(), FlyMirrorLog.frame(), FlyMirrorLog.failed("egl"))
        assertEquals("fly.display mirror", lines[0])
        assertEquals("fly.display frame", lines[1])
        assertEquals("fly.display mirror failed egl", lines[2])
        assertFalse(lines.any { it.contains("name=") })
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :common:testDebugUnitTest --offline --tests com.shilapi.xcertplay.instrument.fly.FlyMirrorLogTest`

Expected: FAIL，`FlyMirrorLog` 不存在。

- [ ] **Step 3: Write minimal implementation**

`FlyMirrorLog` 只含上面三个函数。

`FlyTextureMirror` 在自己的线程 `cluster-fly-mirror` 上做这些事，线程优先级 `THREAD_PRIORITY_DISPLAY`。线程一开始就 `Looper.prepare()`，再建 `Handler`。没有 `Looper`，`Handler` 会抛，`SurfaceTexture` 的监听挂不上去，硬解的输出队列会满。不要把 `updateTexImage` 放到主线程。帧循环用这个 `Handler` 投递，`close()` 用 `handler.post` 退出后 `Looper.myLooper().quit()`，再 `join`。

打开：

1. `eglGetDisplay` / `eglInitialize` / 选 `EGL_OPENGL_ES2_BIT` 的 RGB888 config / `eglCreateContext`。任一步失败：`log(FlyMirrorLog.failed("egl"))`，释放已经建成的部分，`onBroken()`，返回。
2. `glGenTextures` 一张纹理，绑到 `GL_TEXTURE_EXTERNAL_OES`，`SurfaceTexture(texId)`，`setDefaultBufferSize(1920, 720)`。
3. `SurfaceTexture.setOnFrameAvailableListener(listener, handler)`。回调里只置「有新帧」并让 `Handler` 再投递一帧，不在监听里 `updateTexImage`。
4. `eglCreateWindowSurface` 两块：center、cluster。失败：`failed("window")`，释放已经建成的 EGL，`onBroken()`。
5. `log(FlyMirrorLog.mirror())`，然后 `onTexture(Surface(surfaceTexture))`。这一步在两块 `EGLSurface` 都建成之后。`onTexture` 返回后线程继续帧循环，不等待 `replaceOutputSurface`。

每一帧：

1. `updateTexImage()`，`getTransformMatrix`。
2. `eglMakeCurrent(center)`，画 OES 四边形，`eglSwapBuffers`。
3. `eglMakeCurrent(cluster)`，同一张纹理再画一次，`eglSwapBuffers`。
4. 第一次 cluster `eglSwapBuffers` 返回 true：`log(FlyMirrorLog.frame())`，然后 `onFrame()`。只此一次。
5. 从 `updateTexImage` 到第二次 `swap` 的耗时超过 32 ms：`log("fly.display paint slow <ms>")`，5 秒内只记一次。

着色器是外部纹理的常规一对：顶点乘 `uTexMatrix`，片元 `samplerExternalOES`。纹理坐标是整张 0..1。不要裁剪，中控和仪表都是 1920×720。

`close()` 可从主线程调用。它让 GL 线程退出循环，然后 `eglDestroySurface` 两块、`eglDestroyContext`、`surfaceTexture.release()`、`Surface.release()`。`close` 返回前 GL 线程已经退出。飞屏线程不和这条线程共用一把锁去碰 EGL。

中控 Surface 或仪表 Surface 在 `open` 时 `isValid == false`：不建 EGL，`failed("surface")`，`onBroken()`。

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :common:testDebugUnitTest --offline --tests com.shilapi.xcertplay.instrument.fly.FlyMirrorLogTest`

Expected: PASS。EGL 本身没有 JVM 可跑的断言；顺序由 Task 1 的假 `openMirror` 锁住，车上由 `mirror` 然后 `frame` 两行验收。

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/com/shilapi/xcertplay/instrument/fly/FlyTextureMirror.kt \
  common/src/main/java/com/shilapi/xcertplay/instrument/fly/FlyMirrorLog.kt \
  common/src/test/java/com/shilapi/xcertplay/instrument/fly/FlyMirrorLogTest.kt
git commit -m "feat: draw the center picture onto the cluster display"
```

---

### Task 4: 飞屏改走镜像，失败时盖回仪表层

**Files:**

- Modify: `common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyController.kt`
- Modify: `common/src/test/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyControllerTest.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt`（`attachClusterPresentation`、飞屏收场）

**Interfaces:**

- Consumes: Task 1 `FlyOutputHandoff`，Task 2 `replaceOutputSurface`，Task 3 `FlyTextureMirror`。
- Produces: `fun onMirrorStarted()`：取消 `cluster silent` 那一记 3 秒计时，另起 5 秒。到期相位仍是 `WAITING_FOR_VIDEO` 就 `onMirrorFailed()`。
- Produces: `fun onMirrorReady()`：只由第一张仪表帧调用。代数加一，取消那 5 秒。相位是 `WAITING_FOR_VIDEO` 时进入 `PUSHING`。不再 `setMainVideoSubscribed(true)`，也不在这里要关键帧。
- Produces: `fun onMirrorFailed()`：相位是 `WAITING_FOR_VIDEO` 或 `PUSHING` 时走 `session.onRight`，发出 `FLY_RIGHT`，然后 `onGiveUp()`。代数加一，使 5 秒回调失效。

`onFlySurface` 不再创建 `FlyClusterDecoder`，`dismissClusterFly` 也不再调用它。仪表 Surface 到达时由 Activity 跑 `FlyOutputHandoff`。换窗口成功时 Activity 调用已有的 `requestKeyFrame("cluster fly")`，不改相位。中控视频观察者不再订阅。`onMainVideo` 可以留下空实现直到 Task 5 删掉调用。

- [ ] **Step 1: Write the failing test**

在 `ClusterFlyControllerTest` 增加三个测试。拨钟用文件里已有的方式：`onGesture(FLY_LEFT)` 之后只跑掉 400 ms 和 1400 ms 那两记，相位到 `WAITING_FOR_VIDEO`，不要把随后的 3000 ms 也跑掉。控制器已有 `phase`。订阅用构造时的 `setMainVideoSubscribed` 记下来，不要新增可变的状态机接口。

```kotlin
private fun waiting(): ClusterFlyController {
    val controller = controller()
    controller.onGesture(Decision.FLY_LEFT)
    now = 1_400L
    scheduled.removeAt(0).second()
    now = 2_400L
    scheduled.removeAt(0).second()
    return controller
}

@Test fun aMirrorFailureWhileWaitingCoversTheLayer() {
    val controller = waiting()
    controller.onMirrorStarted()
    controller.onMirrorFailed()
    assertTrue(sent.contains(FlyCommand.FLY_RIGHT))
}

@Test fun fiveSecondsWithoutAFrameCoversTheLayer() {
    val controller = waiting()
    controller.onMirrorStarted()
    now = 7_400L
    scheduled.last { it.first == 5_000L }.second()
    assertTrue(sent.contains(FlyCommand.FLY_RIGHT))
    assertEquals(FlyPhase.CLOSING, controller.phase)
}

@Test fun theFirstFrameEntersPushingAndTheWaitDoesNotCoverTheLayer() {
    val subscribed = mutableListOf<Boolean>()
    val controller = ClusterFlyController(
        commands = { sent.add(it) },
        showWarning = { warnings.add(it) },
        setMainVideoSubscribed = { subscribed.add(it) },
        log = { lines.add(it) },
        clock = { now },
        postDelayed = { delay, action -> scheduled.add(delay to action) },
    )
    controller.onGesture(Decision.FLY_LEFT)
    now = 1_400L
    scheduled.removeAt(0).second()
    now = 2_400L
    scheduled.removeAt(0).second()
    controller.onMirrorStarted()
    controller.onMirrorReady()
    assertEquals(FlyPhase.PUSHING, controller.phase)
    scheduled.filter { it.first == 5_000L }.forEach { it.second() }
    assertFalse(sent.contains(FlyCommand.FLY_RIGHT))
    assertFalse(subscribed.contains(true))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :common:testDebugUnitTest --offline --tests com.shilapi.xcertplay.instrument.fly.ClusterFlyControllerTest`

Expected: FAIL。`onMirrorFailed` 尚不存在，或者 `WAITING` 下没有 `FLY_RIGHT`。

- [ ] **Step 3: Write minimal implementation**

控制器：

- `onMirrorStarted`：`videoTimeoutGeneration++`，然后 `postDelayed(5_000L)`。代数仍等于这次的值、相位仍是 `WAITING_FOR_VIDEO` 时调用 `onMirrorFailed()`。
- `onMirrorReady`：代数再加一。相位是 `WAITING_FOR_VIDEO` 时执行现在 `onClusterDisplayReady` 里进入 `PUSHING` 的那一截。去掉 `setMainVideoSubscribed(true)` 和 `requestKeyFrame()`。关键帧在换窗口成功时由 Activity 要，不在第一张帧上才要。
- `onMirrorFailed`：代数加一。`WAITING_FOR_VIDEO` 和 `PUSHING` 都 `session.onRight`，`handle`，`onGiveUp()`。不要走 `abortSession`。`abortSession` 仍只给「仪表 Surface 一直没来」的 3 秒超时用，那种情况不发 `FLY_RIGHT`。
- 删掉 `tryStartDecoder` 和对 `FlyClusterDecoder` 的调用。`onMainVideo` 直接返回。`onFlySurface` 不再启动解码器。`dismissClusterFly` 里现有的 `clusterFly?.onFlySurface(null)` 删掉，避免拆窗口又走一遍失败收层。

Activity 增加字段 `flyHandoff`、`flyMirror`、`flyClosing`。Surface 回调：

```kotlin
val presentation = FlyClusterPresentation(this, display) { cluster ->
    if (cluster == null) {
        if (!flyClosing) clusterFly?.onMirrorFailed()
        return@FlyClusterPresentation
    }
    val center = currentSurface
    if (center == null || !center.isValid) {
        clusterFly?.onMirrorFailed()
        return@FlyClusterPresentation
    }
    clusterFly?.onMirrorStarted()
    val mirror = FlyTextureMirror { line -> DiagLog.i(ClusterFlyLog.TAG, line) }
    val handoff = FlyOutputHandoff(
        replaceOutput = { surface, applied ->
            val media = sink
            if (media == null) {
                applied(false)
                return@FlyOutputHandoff
            }
            media.replaceOutputSurface(SCREEN_TYPE_MAIN, surface) { ok ->
                runOnUiThread { applied(ok) }
            }
        },
        openMirror = { centerSurface, clusterSurface, onTexture, onBroken ->
            mirror.open(
                centerSurface,
                clusterSurface,
                onTexture = { texture -> runOnUiThread { onTexture(texture) } },
                onFrame = { runOnUiThread { clusterFly?.onMirrorReady() } },
                onBroken = { runOnUiThread { onBroken() } },
            )
        },
        closeMirror = { mirror.close() },
    )
    flyHandoff = handoff
    flyMirror = mirror
    handoff.begin(
        center,
        cluster,
        onReady = { requestKeyFrame("cluster fly") },
        onFailed = { clusterFly?.onMirrorFailed() },
    )
}
```

`onReady` 只向手机要关键帧。`onFrame` 才调用 `onMirrorReady()`。`currentSurface` 在飞屏期间仍是 `SurfaceView` 的 Surface，纹理 Surface 只交给 sink。

`dismissClusterFly` 先记下 `presentation`，把 `flyClusterPresentation` 置空（这样 `setOnDismissListener` 里的 `onClusterDisplayLost` 不会再收一次层），然后：

```kotlin
flyClosing = true
val handoff = flyHandoff
if (handoff == null) {
    presentation?.dismiss()
    flyClosing = false
    return
}
handoff.end {
    runOnUiThread {
        presentation?.dismiss()
        flyHandoff = null
        flyMirror = null
        flyClosing = false
    }
}
```

`presentation.dismiss()` 放在 `end` 的 `onDone` 里。`end` 完成前不销毁仪表 Surface。`flyClosing` 为真时，Surface 回调收到 null 直接返回，不调用 `onMirrorFailed`。`dismissClusterFly` 重入时若 `flyClosing` 已经为真，直接返回。

右滑、断开、`onCarPlayEnded`、`onMirrorFailed` 都经 `onGiveUp` 进 `dismissClusterFly`。失败路径里 `FlyOutputHandoff` 已经交还过中控 Surface，这里的 `end` 发现相位是 `IDLE` 就立刻 `onDone`。用户在换窗口中途右滑时，`end` 作废 `begin` 的回调并交还中控 Surface。

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :common:testDebugUnitTest --offline --tests com.shilapi.xcertplay.instrument.fly.ClusterFlyControllerTest`

Expected: PASS。原有「WAITING 超时不发 FLY_RIGHT」的测试仍然 PASS。

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyController.kt \
  common/src/test/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyControllerTest.kt \
  common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt
git commit -m "fix: fly the center picture onto the cluster with one decoder"
```

---

### Task 5: 删掉软解出画

**Files:**

- Delete: `common/src/main/java/com/shilapi/xcertplay/instrument/fly/FlyClusterDecoder.kt`
- Delete: `common/src/main/java/com/shilapi/xcertplay/instrument/fly/FlyPainter.kt`
- Delete: `common/src/main/java/com/shilapi/xcertplay/instrument/fly/FlyFrameSlot.kt`
- Delete: `common/src/main/java/com/shilapi/xcertplay/instrument/fly/FlyYuv.kt`
- Delete: `common/src/main/java/com/shilapi/xcertplay/instrument/fly/FlyAnnex.kt`
- Delete: `common/src/test/java/com/shilapi/xcertplay/instrument/fly/FlyAnnexTest.kt`
- Delete: `common/src/test/java/com/shilapi/xcertplay/instrument/fly/FlyYuvTest.kt`
- Delete: `common/src/test/java/com/shilapi/xcertplay/instrument/fly/FlyFrameSlotTest.kt`
- 以及只被这些类引用的测试。删之前用引用搜索确认没有飞屏以外的调用。`frameMeta`、`formatNotice` 若只被 `FlyYuv` 使用，一起删。

**Interfaces:**

- Consumes: Task 4 已经不再引用这些类型。
- Produces: 生产代码里不再出现 `OMX.google.h264.decoder`、`FlyClusterDecoder`、`fly.display buffer`、`fly.display slow`。

- [ ] **Step 1: 确认没有剩余引用**

Run: `rg -n "FlyClusterDecoder|FlyYuv|FlyAnnex|OMX.google.h264.decoder" common shared`

Expected: 只剩定义和测试。Activity 与控制器没有命中。

- [ ] **Step 2: 删除上面的文件，修编译错误**

控制器里若还留着 `frameExecutor` 且只为软解服务，一起删，包括 `close()` 里对它的 `shutdown`。`setMainVideoSubscribed` 构造参数若已无调用，从构造函数和所有测试构造点删掉。

- [ ] **Step 3: 跑测试**

Run: `./gradlew :common:testDebugUnitTest :shared:testDebugUnitTest --offline`

Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: Commit**

```bash
git add -A common/src/main/java/com/shilapi/xcertplay/instrument/fly \
  common/src/test/java/com/shilapi/xcertplay/instrument/fly \
  common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt \
  common/src/test/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyControllerTest.kt
git commit -m "refactor: remove the cluster software decoder"
```

---

## Self-review

- 四步换窗口：Task 1 锁顺序和代数，Task 2 提供「放开完成 / configure 成败」，Task 3 在放开之后才建 EGL。`onTexture` 不进入 `PUSHING`。
- 第一张仪表帧：Task 3 的 `onFrame` 打 `fly.display frame` 并通知 Activity。Task 4 只有 `onMirrorReady` 进入 `PUSHING` 并取消 5 秒。5 秒内没有这一帧就 `FLY_RIGHT`。
- 换窗口中途收场：Task 1 的 `endDuringDetach` 交还中控 Surface，并丢掉迟到的 detach 回调。`end` 进行中再次 `end` 不再发第二次 `replaceOutput`。
- 拆窗口：`dismiss` 在 `end` 的 `onDone` 里。`flyClosing` 期间 Surface 变 null 不调用 `onMirrorFailed`。`dismissClusterFly` 不再调用 `onFlySurface(null)`。
- 失败交还中控 Surface：Task 1 覆盖 attach 失败和 GL 失败。Task 4 在这两条以及 5 秒超时上发 `FLY_RIGHT`。
- 1885、displayId 1、`Presentation` 仍走现有 `attachClusterPresentation`。
- 不订阅第二路画面，不创建第二块硬解，不创建 `OMX.google.h264.decoder`。Task 5 删掉软解。
- 3 秒「cluster silent」仍不发 `FLY_RIGHT`。关键帧在换窗口成功时要，不等第一张帧。
- 日志没有 `name=`。成功三行是 `use id=1`、`mirror`、`frame`。
- `currentSurface` 在飞屏期间仍指向 `SurfaceView`。GL 线程先 `Looper.prepare()`。
- 验收时高德自己的仪表投屏是关掉的。
