# 性能埋点：报告里怎么看出"慢在哪"

诊断报告原来能回答"到哪一步了"（STEP 行）、"画面流不流畅"（5 秒 `video stats` 行），但回答不了
"这一步花了多久"。一条 `STEP usb/config` 停在原地九秒，可能是车机在枚举，也可能是 iPhone 在算证书，
日志里两者长得一模一样。这个埋点层就是补上这段时间轴，并把耗时分位数留在报告里。

实现：`shared/src/main/java/com/shilapi/xcertplay/PerfTrace.kt`。

## 记录什么

三类，都写进报告的 `--- Performance ---` 段：

1. **耗时分布** `PerfTrace.record(name, ms)` / `PerfTrace.span(name, warnAboveMs) { ... }`
   每个名字保留最近 512 个样本的定长环，报告给 `n / p50 / p95 / max / sum / over`。
   p95 是"难受的那 5%"，平均值会把一次 200ms 的卡顿摊掉，所以不打印平均值。
2. **时间点** `PerfTrace.mark(chain, name)`
   返回与**同一条链**上一个点的间隔（ms，首个点为 -1），报告按发生顺序列出。
   链是分开的：`startup` 不会去报"距上次连接步骤 183 秒"，第二次连接也不会继承第一次的时钟。
3. **超预算告警**
   样本超过 `warnAboveMs` 时写一行 `Perf <name> took 44ms (budget 8ms)` 进 logcat 和会话日志，
   同一个名字 5 秒最多一条——卡顿的一分钟不该变成制造卡顿的日志风暴。
4. **连接总结行**
   连接落地或失败时写一行
   `connect up in 18234ms; slowest=usb/config+9341ms`
   （失败则是 `connect failed in ...`）。报告需要导出，`diplay.log` 本来就在车机上，这一行让裸日志
   也能直接回答"这次为什么慢"。

开销是设计约束：一次时钟读、一次定长数组写、一次短锁；不格式化、不排序、不写盘；名字上限 32 个
（超出的计入 `timings dropped`，报告里会说明）。视频路径上是每帧一次，没有分配。

## 埋点清单

| 指标 | 位置 | 含义 | 预算 |
| --- | --- | --- | --- |
| `startup/process-to-frame` | `DiPlayActivity.reportFirstDraw` | 进程启动 → 首页第一帧（API 24+） | 2000ms |
| `startup/bootstrap` | `DiPlayActivity.onCreate` | 加载 CarPlay 认证身份（crypto + 文件 IO） | 200ms |
| `startup/create-done` → `startup/first-draw` | `DiPlayActivity` | 建树 → 真正可见（差值＝布局） | — |
| `startup/session-log` | `CarPlayHostActivity.onCreate` | 会话日志轮转（主线程磁盘 IO） | 100ms |
| `startup/host-create-done` | `CarPlayHostActivity.onCreate` | 投屏页建立完成 | — |
| `ui/render` | `DiPlayActivity.render` | 整页重建（丢掉整棵树再建） | 32ms |
| `ui/tick` | `DiPlayActivity` 每秒 tick | 状态刷新，含 Wi-Fi / AP / 电台查询 | 8ms |
| `connect/<step>` | `CarPlayController.onStatus` | 每个 STEP 距上一步的间隔 | — |
| `video/first-input` → `video/first-frame` | `AndroidMediaSink` | 首包进解码器 → 首帧上屏 | — |
| `video/decode` | `AndroidMediaSink.drainOutput` | 入队 → releaseOutputBuffer（解码+上屏那半段） | 120ms |

阈值是"该看一眼"的线，不是目标值。

## 报告里长什么样

```
--- Performance (ms; p50/p95 over the last 512 samples, worst p95 first) ---
  startup/bootstrap    n=1 p50=140 p95=140 max=140 sum=140
  ui/render            n=14 p50=96 p95=210 max=260 sum=1480 over=9/14
  ui/tick              n=180 p50=3 p95=11 max=44 sum=912 over=4/180
  video/decode         n=512 p50=8 p95=22 max=180 sum=5120 over=2/512
  --- marks: +ms since the previous mark on its chain; t=ms since the first mark ---
        --  t=     0ms  startup/create-done
    +812ms  t=   812ms  startup/first-draw
   +1204ms  t=  2016ms  connect/usb/discover
   +9341ms  t= 11357ms  connect/usb/config
    +118ms  t= 11475ms  video/first-input
    +204ms  t= 11679ms  video/first-frame
```

读法：先看最上面三行（p95 最大），再看 marks 里哪一跳特别长。`over=4/180` 是"180 次里有 4 次超预算"。
连接慢的时候，`connect/*` 的间隔直接指到等待发生在哪一级，不用再靠 STEP 行的绝对时间戳相减。

`+` 是**同一条链**上的间隔，`t=` 是**同一条时间轴**上的绝对位置。两个都印，是因为只看 `+` 容易把不同
链的间隔加起来：`+9341ms` 是连接步骤，`+204ms` 是视频解码，两者不能相加——但它们各自发生在 `t=11357`
和 `t=11679`，这个关系是真的。

## 不测什么

- 合成器 / GPU 掉帧。`Choreographer` 自循环采样在 Robolectric 下会让主 looper 空转，没上；
  真要测得上设备用 Perfetto。
- CPU 占用、温度降频、内存水位。这些要看 `dumpsys`，报告里不伪造。
- 音频稳态：`AndroidMediaSink` 已有 5 秒一次 `dropped= underruns= queue=` 行，不重复。

## 静态阅读找到的候选优化

按"预期收益 / 改动风险"排的，都还没有改，等埋点在真机上给出数字再动：

1. **`VideoDecodeQueue.offer` 每帧 O(n)** — `shared/.../media/VideoDecodeQueue.kt:35-36`
   每来一帧就 `filterIsInstance<Frame>()` 建一个临时 List，再 `sumOf` 扫一遍。60fps 下每秒 60 次分配、
   最多 60 个元素的扫描。维护两个计数器（在队帧数、在队字节）就能把这段变成 O(1)。
   验证：`video/decode` 的 p95 与 `sum`，以及 logcat 里的 GC 行。
2. **日志轮转在主线程** — `CarPlayHostActivity.onCreate` 调 `SessionLogFile.reset()`，
   `reset()` → `rotate()` 最多复制 7 个 512KB 文件，全在 UI 线程。
   验证：`startup/session-log` 一旦长期接近或超过 100ms 就是它。
3. **`render()` 整树重建** — `DiPlayActivity.kt:398`。页面切换这么做是对的，但配置变化、设置回写也会走。
   `ui/render` 的 `sum` 高、`n` 也跟着高，就把可变区域改成增量更新。
4. **tick 里的系统查询** — `DiPlayActivity.kt:2734` 起：每秒 `WifiManager.connectionInfo`（binder 调用）、
   AP 状态反射、`WirelessReadiness` 对象、`shown.map {}` 分配。`ui/tick` 的 p95 若贴着 8ms，
   就缓存 SSID/AP 状态或把 tick 降到 2 秒（页面上没有秒级变化的东西）。
5. **每次 drain 都 new `BufferInfo`** — `AndroidMediaSink.kt:491`（视频）、`:983`（音频）。
   每帧一次的短命对象，改成字段复用即可。
6. **`VideoStats` 每帧两把锁** — `submit`（网络线程）与 `onRendered`（视频线程）都 `@Synchronized`。
   单次开销很小，但两线程每帧争同一把锁；改成 `AtomicLong`/`LongAdder` 可以完全去锁。
7. **`HandshakeLog.displayLine` 每次 new Regex** — 每次握手行构造一个 `Regex("(?i)certificate")`。
   量小（握手期每步一行），但属于同一类浪费，提到 `val` 即可。

## 怎么用

1. 复现问题，设置页 → 保存诊断报告。
2. 报告头部 `Log losses:` 下面就是 `--- Performance ---`，不用翻日志段。
3. 对比两次运行只看 p95 和 over，不看平均值；改动前后用同一台车机、同一部 iPhone。
