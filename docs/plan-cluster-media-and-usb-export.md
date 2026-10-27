# 星瑞 E01 仪表媒体（歌词/节流补发/防回跳）与 U 盘原生卷方案重构计划

## 核心目标
1. **仪表 2 秒节流不丢状态**：节流期内的播放/暂停暂存，窗口结束补发 `onAction(1/2)`。
2. **歌词规则收窄**：进度归零或时长变化直通放行真切歌，精准拦截 3 种伪装歌词包，删掉过宽规则（解决《七里香 (Live)》被误杀）。
3. **断开防上一首回跳**：清屏与发歌统一放入单后台队列，引入 `generation` 代数废弃滞后任务，清屏排在队列末尾，执行完再解绑。
4. **源类型坚守 0**：固定使用东软不死通道 `0`（HDD-Audio），不改成 1 或 2。彻底剔除封面写盘逻辑。
5. **U 盘按 CX 三步对齐**：使用 `StorageManager.getVolumeList()` 抓取系统已挂载可移动卷，标题用系统的「USB 存储」，仅允许 `/storage/$name` 同名有效替换，彻底剔除 `/proc/mounts` 猜测、`ls -1` 与 `/storage/usbotg` 误映射。

---

## 一、 仪表模块改动（`CarPlayMediaKeys.kt`）

### 1. 歌词过滤与真切歌通行判定 (`filterLyricMetadata`)
* **真切歌直通判定**：
  * 条件 1：`info.elapsedMillis == 0L` 或 `(info.elapsedMillis ?: -1L) < 1000L`（切歌进度归零）；
  * 条件 2：`info.durationMillis != null && lastDurationMillis != null && info.durationMillis != lastDurationMillis`（曲目总时长变更）；
  * 命中任一条件：**判定为真切歌**，无条件放行，更新 `lastGenuineTitle`、`lastGenuineArtist`、`lastDurationMillis`。
* **仅拦截 3 种确定性歌词包**：
  1. 歌手等于当前歌名（酷狗字段对调）：`!lastGenuineTitle.isNullOrBlank() && info.artist == lastGenuineTitle`
  2. 歌名以当前歌名结尾：`!rawTitle.isNullOrBlank() && !lastGenuineTitle.isNullOrBlank() && rawTitle.endsWith(" - $lastGenuineTitle")`
  3. 歌名以制作人员标识开头：`rawTitle.startsWith("词：")`、`"曲："`、`"编曲："`、`"制作人："`（兼容中英文冒号）。
* **彻底删除的错误规则**：
  * ❌ 删除 `rawTitle.contains(lastGenuineTitle)`（之前导致《七里香》切《七里香 (Live)》被锁死）。
  * ❌ 删除“同长度且播放超 3 秒漂移锁死”。

---

## 二、 仪表桥接与线程时序（`XingruiInstrumentBridge.kt`）

### 1. 2 秒节流与状态补发
* 保留 `MIN_CAN_INTERVAL_MS = 2000L`，防 CAN 总线打满锁死。
* **状态暂存补发机制**：
  * 当歌名未变但播放状态变更（如切歌 2 秒内手机点了暂停）：
    * 若处于 2 秒节流窗口内，**不丢弃**，暂存 `pendingAction = (if (isPlaying) 1 else 2)`；
    * 启动定时调度，在 2 秒窗口结束后，补发一次 `service.onAction(pendingAction, sourceType)` 并清空暂存。

### 2. 同代数（Generation）与同一后台队列时序
* 维护原子代数 `currentGeneration = AtomicInteger(0)` 与 `isReleased = AtomicBoolean(false)`。
* 发歌任务携带提交时的代数 `taskGen`。后台线程执行前检查 `currentGeneration.get() == taskGen && !isReleased.get()`，代数不对直接抛弃。
* **清屏（`clearMedia` / `release`）重构**：
  * 调用时递增代数：`currentGeneration.incrementAndGet()`（使队列中排队的旧发歌任务全部失效）；
  * 若是 `release()`，立即置 `isReleased.set(true)` 拒收后续一切新歌；
  * **清屏指令排进同一个后台队列末尾**：
    `service.updateMedia(0, " ", " ", " ", "", 0)` + `service.onAction(2, 0)`；
  * 在该清屏任务执行完毕后，再执行 `safeUnbind()` 断开 AIDL。
* **仪表索要广播（`AudioRequireAction`）**：
  * 仅在 `!isReleased.get() && lastCachedMedia != null` 时响应重发；已断开时不应答。

### 3. 源类型与封面极简
* 保持 `sourceType = 0`。
* 彻底移除 `CoverSlotManager`、Bitmap 解码与写盘逻辑。播放传 `"carplay"`，清屏传 `""`。

---

## 三、 U 盘存储对齐 CX（`DiagnosticVolumeProbe.kt` 与 `DiagnosticExportStore.kt`）

### 1. 第一屏卷列表（对齐 CX 核心 3 步）
* 仅调用 `StorageManager.getVolumeList()`，筛选：
  1. `vol.isRemovable() == true`
  2. `sm.getVolumeState(path) == "mounted"`
  3. `!isPrimaryStorage(path)`
* **标题与路径**：
  * 标题直接取系统：`vol.getDescription(context)`（系统默认输出「USB 存储」）；
  * 路径取 `vol.getPath()`。
* **唯一允许的路径微调**：
  * 若 `getPath()` 为 `/mnt/media_rw/$name`，且同名的 `/storage/$name` 存在且 `listFiles() != null`，则采用 `/storage/$name`；
  * **严禁改写/回退为 `/storage/usbotg`**。
* **彻底清除以下杂质**：
  * ❌ 移除 `/proc/mounts` 探测与筛选；
  * ❌ 移除 `ls -1 /mnt/media_rw`；
  * ❌ 移除厂商目录猜测兜底；
  * ❌ 移除虚拟 SD 卡（`sdcard1/2`）占位符。
* 列表末尾保留机身内部存储（`Download`）。

### 2. 点击卷浏览与报告保存
* 点击后直接对卷路径执行普通 `listFiles()`；列不出直接标明「读不了」，不再用 root 伪造展开。
* 诊断日志统一保存到该卷根目录的 `DiPlay/` 文件夹（即 `<volumeRoot>/DiPlay/<fileName>`）。
