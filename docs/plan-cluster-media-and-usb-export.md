# 星瑞 E01 仪表媒体与 U 盘系统卷

改 `CarPlayMediaKeys.kt`、`XingruiInstrumentBridge.kt`、`DiagnosticVolumeProbe.kt`、`DiagnosticExportStore.kt`，以及对应的单测。不改源类型，不写封面文件，不把 CX 的整套卷分类搬进来。

源类型继续用 `AirPlayPersistence.loadClusterMediaSource()`。读到旧值 2 时仍迁成 0。0 在东软里叫 `HDD-Audio`，蓝牙断开时不会被丢掉。不要改成 1 或 2。

## 歌词

`filterLyricMetadata` 按这个顺序判断，先命中的为准。

1. 还没有上一首真歌名：这包就是第一首，放行。
2. 命中下面三种之一：当成歌词。歌名、歌手、专辑、时长都保持上一首，不更新。进度是 0、进度为空、总时长变了，也不能放行。
3. 其余都是新歌，放行。包括《七里香》后面的《七里香 (Live)》、时长相同的下一首、进度停在中途、进度为空。

三种歌词包：

- 歌手等于上一首真歌名。
- 歌名以 `" - "` 加上上一首真歌名结尾。不要用 `contains`。
- 歌名以这些前缀开头，中文冒号和英文冒号都算：`词：` `曲：` `编曲：` `制作：` `制作人：`。

删掉这两条：

- 歌名里只要包含上一首真歌名。
- 时长相同、进度超过 3 秒、标题变了，就锁死第一帧。

不要加「进度小于 1 秒就放行」。进度为空不算切歌信号，它走第 3 条，只要没撞上三种歌词包就放行。

## 仪表队列

桥接层继续用现有的单线程 `executor`。新增 `currentGeneration: AtomicInteger` 和 `released: AtomicBoolean`。发歌、补发、清屏都在这条队列里做。任务开始时代数对不上，或者已经 `released`，就返回。

这些行为留下：

- 每次真正调用 `updateMedia` 之前先 `onSourceChanged(0)`。封面删了，这句不能删。东软初始状态是 `-101`，没调用过就整包丢弃。
- `truncateCanString` 仍先 trim，再截到 31 个字符，空文本用一个空格。
- `MIN_CAN_INTERVAL_MS` 仍是 2000。这是防仪表锁死，不是为了封面。

封面文件删掉。`updateMedia` 不再接收 `Bitmap`，桥接层不再使用 `CoverSlotManager`。播放中的封面参数固定为非空字符串 `"carplay"`，用来避开东软对 `""` 发 `-102` 关屏。清屏才传 `""`。`decodeArtwork` 留给 MediaSession，本计划不删会话侧解码，也不为仪表写 jpg。

### 两秒内的暂停

歌名和歌手没变、播放状态变了、距离上次成功发送不足 2 秒：不要丢。记下最新的播放或暂停，以及当时的代数。窗口结束时把一笔 `onAction(1)` 或 `onAction(2)` 排进同一条队列。

这包补发开始时代数不对，或已经断开，就什么都不发。在它发出之前如果已经成功发送过一首新歌，或者已经清屏，取消这包暂存。补发只发 `onAction`，不再发一遍歌名。缓存里的 `playing` 在入队时就改成新状态，不要等补发成功才改。

窗口已经结束，或者歌名变了：按现有路径马上发送，并取消还没发出的暂存。

### 仪表来要歌名

`AudioRequireAction` 里动作是 3 时：已经断开，或者缓存是空的，不应答。否则把缓存排进队列，`force = true`。强制发送不受 2 秒限制，仍要检查代数和 `released`。

### 清屏和断开

`clearMedia` 用于关掉「仪表媒体信息」。它加一次代数，取消暂存，清空缓存和上次发送记录，然后只排一笔清屏。清屏任务先 `onSourceChanged(0)`，再 `updateMedia(0, " ", " ", " ", "", 0)`，再 `onAction(2, 0)`。不解绑，不把 `released` 设为 true。服务还在，之后的新歌用新代数继续发。

`release` 用于断开。先置 `released`，让新的 `updateMedia` 直接返回。再加一次代数，取消暂存，清空缓存，只排一笔清屏。不要调用 `clearMedia`，否则代数加两次，清屏任务会被第二次加代数判成过期。这笔记清屏写完后再解绑、再 `shutdown` 这个 executor。调用 `release` 的线程不要在 worker 里等待这条队列，避免自己等自己。

已经在 binder 调用里的那一笔会先写完。清屏排在它后面，所以最后一笔是关屏。

## U 盘

第一屏只来自 `StorageManager.getVolumeList()`。某一卷反射失败就跳过这一卷，不要让整份列表变成空的。

留下同时满足这些条件的卷：

- `isRemovable()` 为 true
- 该卷自己的 `getState()` 等于 `mounted`
- 不是主存储。有 `isPrimary()` 就用它，否则再用现有的路径比较

标题用 `getDescription(context)`。路径用 `getPath()`。卡片上仍显示这条路径，方便和 CX 地址栏对照。

路径只允许一种替换：`getPath()` 是 `/mnt/media_rw/某个名字`，名字里面没有斜线，而且 `/storage/同一个名字` 的 `listFiles()` 至少返回一项。这时用 `/storage` 那条。空目录不算。否则留 `getPath()`。禁止改写成 `/storage/usbotg`。

第一屏删掉这些来源：

- `/proc/mounts`
- `ls -1 /mnt/media_rw`
- 厂商目录兜底
- 为了虚拟 `sdcard1`、`sdcard2` 去扫那些目录

系统卷没列出的空壳不会再出现。`getVolumeList()` 为空，或点进去是空目录，就显示系统给的路径和现有文案「读不了这个目录」。不要再发明别的目录。

点进子文件夹只为了看内容。`openDirectory` 记住的卷根不变。保存位置仍是 `<卷根>/DiPlay/<文件名>`。

列目录只用 `listFiles()`。`children()` 里的 `listFilesViaRoot` 删掉。列不出就用 `export_unreadable`，列表为空。列不出仍然允许保存。

写入先走现有的直接写。失败后再走现有的 `exportViaRoot`：`cp` 然后 `sync`，超时仍是 5 秒。不要因为浏览不再用 root，就把这条写入删掉。

没插 U 盘时，最后一项仍是机身下载目录。

## 测试

`CarPlayMediaKeysBroadcastTest` 补上这些断言：

- 《七里香》接到《七里香 (Live)》，进度为 0，不是歌词，歌名更新。
- 进度为 0 的 `词：`，是歌词，歌名保持上一首。进度 500 毫秒的 `词：` 同样拦住。
- 歌手被换成上一首歌名，即使进度为 0，也是歌词。
- 歌名以 `" - "` 加上一首歌名结尾，是歌词。
- 只是包含上一首歌名、又对不上上面三种，不是歌词。

卷路径补上：

- `/mnt/media_rw/usbotg-sda1` 在 `/storage/usbotg-sda1` 里有文件时，用后者。
- `/storage/usbotg-sda1` 是空目录时，留在 `media_rw` 这条。
- 任何名字都不会变成 `/storage/usbotg`。

队列逻辑若抽成可以单测的判断，补两条：代数变了之后，暂存的暂停不再发送；`release` 只加一次代数，清屏任务仍会执行。不要求在单测里绑上东软服务。
