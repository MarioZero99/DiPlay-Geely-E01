# DiPlay 埋点方案 (Instrumentation Design)

- **版本**: 2.0.0 · 替换 1.0 的表/上传设计
- **日期**: 2026-10-17
- **范围**: 只定义**埋什么、怎么表达、在哪采、怎么不腐化**。传输不动——记录随现有诊断报告进现有桶，本文不设计上传。
- **前提**: 所有版本（official / local）同一套；不加依赖；不碰 30 帧路径。

---

## 〇、一句话

**这套代码里已经有埋点要的全部词汇，缺的只是把屏幕上已经算出来的东西在结束时存下来。**

`ConnectionStep`（七级台阶）、`ConnectionProgress`（走到哪一级）、`CarPlayFailureReason`（十二个失败原因）、`WirelessGap`（八个"还没开始就缺什么"）都是现成的、有穷尽 `when` 守着、每天在屏幕上画的东西。新增的只有一个记录器、一种落盘格式、一个失败码。

**不新造任何枚举**，是本方案与上一稿的根本区别。上一稿另立了 10 个 stage + 16 个 why，等于给同一个问题写了第二份答案——`WirelessStartupPolicy.kt:6-9` 里那句注释已经说过这件事："a second classifier beside it would be a second answer to the same question."

---

## 一、现有真值盘点（埋点的词表就是它们）

| 概念 | 现有类型 | 规模 | 位置 |
|---|---|---|---|
| 控制器状态 | `CarPlayStatus` | 21 个具体态 + `Failed` | `CarPlayController.kt:109` |
| 台阶 | `ConnectionStep` | 7 级（MFI/HOTSPOT/PAIRING/NETWORK/DEVICE/DATA_LINK/SESSION） | `ConnectionPlan.kt:17` |
| 台阶状态 | `ConnectionStepState` | PENDING/ACTIVE/DONE/FAILED | `ConnectionPlan.kt:40` |
| 走到哪 | `ConnectionProgress`（含 `rows`、`failedAt`） | — | `ConnectionPlan.kt:50` |
| 失败原因 | `CarPlayFailureReason` | 12 个 | `CarPlayController.kt:155` |
| 起不来的缺口 | `WirelessGap` | 8 个 | `WirelessReadiness.kt:41` |
| 传输 / 热点模式 / 蓝牙跳板 | `CarPlayTransport` / `WirelessHotspotMode` / `WirelessBluetoothHop` | 2/4/2 | 同上 |

关键性质：`connectionStepOf(status, transport)` 对 `CarPlayStatus` 是**穷尽 `when`**（`ConnectionPlan.kt:98-101` 的注释写明了用意）。控制器将来加一个状态、没给它安排台阶，这个文件就编不过——**埋点因此天然不会漏掉新状态**。这是本方案唯一需要的反腐化机制，且它已经存在。

---

## 二、记录：一次尝试一行

一条 JSONL，约 500 字节。字段全部来自第一节的现成类型。

```json
{"v":1,"id":"9f2c1a7e","gen":3,"retry":1,"t":1760706131480,"up":812345,
 "install":"3f9a1c02","ver":"0.4.2-geely","code":42,"model":"Geely E01","api":22,
 "transport":"WIRELESS","mode":"MANUAL","hop":"CAR",
 "gaps":[],"steps":[{"s":"MFI","ms":840},{"s":"HOTSPOT","ms":1520},{"s":"PAIRING","ms":0}],
 "at":"PAIRING","result":"fail","why":"BRING_UP_FAILED","detail":"rfcomm open failed: …",
 "ms":15320,"reset":false,"cfg":{"fps":30,"codec":"HEVC","buf":500,"res":100}}
```

| 字段 | 来源 | 规则 |
|---|---|---|
| `id` | 8 位十六进制，随机，**开始尝试时生成** | 不与 `DisplayDiagnosticSnapshot.attempt` 共用：协商可能在尝试开始之前就发生，且一次尝试可协商多次（1:N）。两处按时间对齐，不假装是同一个键 |
| `gen` / `retry` | `restartGeneration` / 重试梯子预算 | 同一次按键发起的多次尝试靠这两个串起来 |
| `t` | 墙钟毫秒 | 只给人看；车机时钟可能没对时 |
| `up` | `SystemClock.elapsedRealtime()` | 同一次开机内的真实先后，墙钟跳变不影响 |
| `install` | 首启随机 8 位十六进制 | 可重置；不是 MAC、不是 AirPlay `deviceId`（那个是给 iPhone 看的身份） |
| `gaps` | 只有 `blocked` 记录非空 | 能开起来的尝试按时定义就绪（页面挡在前面），所以写空数组；`blocked` 写那一个缺口，与 `why` 必然一致 |
| `steps` | `ConnectionStep` + 进入时刻 | 只记**到达过**的台阶，按到达顺序 |
| `at` | 失败时最后一级台阶（`failedAt`） | 就是屏幕上点亮的那一格；`blocked` 从未上梯，写 `"-"` |
| `result` | ok / fail / blocked / abandoned | blocked = 被 `refuseConnection` 挡住，从没开始 |
| `why` | ok ⇒ `"-"`；fail ⇒ `CarPlayFailureReason`；blocked ⇒ 该次拒绝的理由；abandoned ⇒ `"-"` | 两套词表不重名，`result` 决定读哪套。`abandoned` 是"起了步但没结局"，理由在 `detail` 里，不另造枚举 |
| `detail` | `CarPlayStatus.Failed.detail` | 唯一自由文本；截 120 字符，写前过 `DiagnosticRedactor` |
| `ms` | 开始到结局 | 用户可感知耗时，见下方"结局时刻" |
| `reset` | `Failed.wifiResetRequired` | 屏幕上要给恢复按钮的那种 |
| `cfg` | 三个数（`fps` / `buffer` / `res`）+ 编码（`HEVC` / `H264`） | 只有数字与短枚举 |

**结局时刻（决定 `ms` 与 `steps` 收在哪）**——四个结局各有一个精确的关闭点，不能笼统地"会话结束时写"：

| result | 关闭时刻 | 含义 |
|---|---|---|
| `ok` | 第一个 `connectionComplete(status)` 为真的状态（`ConnectionPlan.kt:86`） | `ms` = 连上用多久。**不取会话长度**，否则每个成功记录都是几小时 |
| `fail` | 第一个 `CarPlayStatus.Failed` | `why` / `detail` / `reset` 都在这一条上 |
| `blocked` | `refuseConnection` 当场 | 没有台阶、没有耗时 |
| `abandoned` | `shutdown()` / 页面销毁，且上面三者都没发生 | 含 `startCarPlay` 里被 `createRuntimeConfig()` 拒掉的那种（`CarPlayHostActivity.kt:2220-2228`），理由进 `detail` |

`ok` 之后会话再怎么结束，都不属于这次尝试；记录已经关了，不会再改。


**演进规则**：只增字段，不改语义；改语义必须抬 `v`；读端忽略不认识的键。

---

## 三、在哪采：四个钩子

| # | 钩子 | 位置 | 产出 |
|---|---|---|---|
| 1 | 拒绝 | `refuseConnection()` `DiPlayActivity.kt:2189` | `blocked` 记录（今天这里只改一个 UI 标志） |
| 2 | 开始 | `startCarPlay()`，**`createRuntimeConfig()` 成功之后**（`CarPlayHostActivity.kt:2229`） | 新尝试的头：`id/gen/t/up/install/…/gaps/cfg` |
| 3 | 状态 | `createStatusReporter` `CarPlayHostActivity.kt:2128` | `steps`、`at`、`why`、`result`，以及 `ok` 的关闭 |
| 4 | 收尾 | `shutdown()` / 页面销毁 | `abandoned`（只关还没关的那些） |

**钩子 1 收不下理由，必须改调用点。** `refuseConnection(reason: String?)` 收的是给人看的成品文案，5 个调用点里有 3 个传 `null`；而且 2081 / 2082 两处发生在就绪检查之后，那一刻 `readiness.gaps` 可能是空的。想靠"现场读 gaps"补 `why` 一定有洞，所以把缺口**显式**传进去：

| 调用点 | 传入 |
|---|---|
| 2059 `!readiness.ready` | `readiness.gaps.first()` |
| 2067 hotspot 未配置 | `HOTSPOT_CREDENTIALS` |
| 2074 名称/密码缺失 | `HOTSPOT_CREDENTIALS` |
| 2081 `carHotspotOff()` | `HOTSPOT_OFF` |
| 2082 未选手机 | `PHONE_NOT_CHOSEN` |

**钩子 2 不能放在函数第一行。** `startCarPlay` 开头有两个静默 `return`（2215 会话不归自己、2216 已在拆/已有控制器），放在最上面会开出永远关不掉的记录。放在配置建好、控制器构造之前，则三个提前返回各有归宿：前两个**不开记录**（本来就不是一次尝试），配置被拒的那一个开记录并以 `abandoned` 收尾。


钩子 3 之所以只要一处：全仓 `onStatus(...)` 有 38 个调用点，但它们都汇入 `createStatusReporter` 这一个回调（控制器构造时传入，`CarPlayHostActivity.kt:2280`）。**在汇入点拦一次，胜过在 38 个源头各埋一次**——少 37 个可能漏接的地方。

明确不碰：解码线程、音频线程、`VideoStats` 的周期行。音视频只在首帧后取一次快照放进 `cfg`，不进记录流。

---

## 四、一个 tracker，两个消费者

`lastStep` / `failedAt` 是 Activity 的字段（`CarPlayHostActivity.kt:162-163`），只在重绘时用一次就丢。`AttemptTracker` 接管它们的职责：

- 屏幕要 `ConnectionProgress` → 有 tracker 时问 tracker 要 `at`，没有时仍用那两个字段；
- 记录器要 `at` / `steps` → 问 tracker 要。

于是**点亮的台阶和写进文件的台阶是同一个值**，不是两次推导。两个字段没有删，因为它们还负责另一条路径：Activity 接管后台已在跑的会话时（`CarPlayHostActivity.kt:2204` 附近）并没有"一次尝试"，tracker 是 null，而梯子照样得画。这条路径不写记录，也不该为了统一而现造一条记录。

两处都在同一个函数里、由同一条状态驱动（`updateConnectionProgress`），所以"日志说死在 NETWORK、屏幕说死在 PAIRING"这种错要么不会发生，要么只需要看这一个函数。

`ConnectionProgress` 本身已经是纯函数计算结果（`connectionProgress(status, transport, failedAt)`），tracker 只是替它保管 `failedAt` 与时间戳，不改判定逻辑。

**线程**：四个钩子全在主线程——状态回调里直接 `setConnectionStage` 与 `updateConnectionProgress`（`CarPlayHostActivity.kt:2130-2133`），改 View 只可能在主线程。所以 tracker 不加锁，只声明"主线程独占"；将来谁在后台线程调它，是那个调用点的错，不是这里的错。


---

## 五、落盘

- 文件：`filesDir/logs/events.jsonl`，**一次尝试一行，追加写**，按大小轮转（256 KB × 2）。一次会话不到 2 KB。
- 不走 `SessionLogFile` 的逐行 redact：它见到 `ssid` 会把整行抹成 `[withheld]`（`DiagnosticRedactor.kt:14`）。记录里根本没有 ssid/mac/ip 字段，`detail` 单独过 redactor 后再拼进去。
- **报告那一遍也必须绕开通用 redact。** `buildDiagnosticSections` 对每个文件的每一行都过 `DiagnosticRedactor.redact`（`DiPlayActivity.kt:2680`），一条 JSON 只要 `detail` 里带了 `ssid` 就会被整行换成 `[withheld: …]`——记录既不是 JSON 也不再是证据。事件段要单独构造 `DiagnosticSection`，用它自己那句"写入时已脱敏"为前提，不再过第二遍。
- 写入时机：失败/结束时**同步追加**（一行几百字节）。理由是进程可能马上被杀，而现有 `sessionLogExecutor` 在 `onDestroy` 后不再接活；一次小写换一条证据，划算。
- 写失败计进 `DiagnosticCounters.noteLogWriteFailure()`（现成，`DiagnosticCounters.kt:16`），报告头会带出来——记录丢了自己会说。
- **模块方向决定代码放哪。** `common` 依赖 `shared`，反过来不行；而 `DiagnosticRedactor` / `DiagnosticCounters` 都在 `common`、且前者是 `internal`。所以：**类型与判定逻辑在 `shared`，脱敏、落盘、计数在 `common`**。tracker 因此只收"已经脱敏好的 detail"，时钟与 `install` 由调用方注入——这也是它能做成纯 Kotlin、跑 JVM 单测的原因（`SystemClock` / `SharedPreferences` 一个都进不来）。
- 报告里两处出现，同一份解析结果：头部渲染"最近 5 次尝试"表（人读），末尾附 `events.jsonl` 原文（`jq` 可读）。因为量小，**不需要分片、不需要 gzip、不需要新的上传路径**。


---

## 六、屏幕上的失败码

`W/PAIRING/BRING_UP_FAILED` = `transport` 首字母 + `at` + `why`。

- 三个来源都是现成枚举，由一个函数产出；
- **不落成字段**：记录里已经分别有 `transport` / `at` / `why`，再存一份就是第二个真相（而且 `code` 这个键在记录里已经被 `versionCode` 占了）。
- 车机没网时，车主照着这一行念/发微信即可，不必导出文件。

这条是本方案里唯一"新加给人看的东西"，也是没有网络时唯一能把数据带出车的手段。

---

## 七、怎么保证它不烂掉

1. **新状态不可能漏埋**：`CarPlayStatus` 加一个态 → `connectionStepOf` 的穷尽 `when` 编译失败（已有，`ConnectionPlanTest` 已在守）。
2. **词表改名会红**：一组测试把 `ConnectionStep` / `CarPlayFailureReason` / `WirelessGap` 的 `name` 列表钉死。改名是允许的，但必须同时想清楚历史数据怎么对齐——而不是悄悄把同一件事改成两个名字。
3. **一次尝试恰好一条记录**：`fail` 必带 `why`，`ok` 必为 `"-"`，`blocked` 必有 `gaps`。三条断言，一个测试。
4. **枚举入参**：记录构造器只收类型，不收字符串。`detail` 是唯一自由文本，截断 + redact。

---

## 八、不埋什么

- **不埋推断值**。"热点应该是开的""手机应该连上了"这类代码没读到的事实，一个字段都不留——UI 规范里"严禁虚构状态"同一条线，搬到数据上。
- **不埋逐帧/逐包**。已有 `VideoStats` 的周期行留在散文里。
- **不埋身份**。无 SSID、无 BSSID、无 MAC、无 IP、无设备名。`install` 是随机的、可重置的。
- **不埋产品漏斗**（首次启动、设置改动、留存）。要做是另一批记录，本期不做；真要做再加，不与本次混在一张表里。

---

## 九、与现有日志/报告的关系

| 现有件 | 处置 |
|---|---|
| `DiagLog` 那 209 个调用点 | 不动。从此只提供细节，不承担结论 |
| `DiagnosticDigest` 正则排名 | 本期不动（少改一处）；报告顺序改为：结论/失败码 → 最近尝试表 → 协商快照 → 散文 → `events.jsonl` 附录 |
| `DisplayDiagnosticSnapshot` | **保留**。它记的是一次协商的六段细节，一次会话可协商多次（1:N），塞进"一次尝试一行"会撑爆记录；改为与记录共用 `id`，两处能对上 |
| `SessionLogFile` 轮转 | 本期不动。事件是独立文件，不受 reset 影响 |

（上一稿说要把 `DisplayDiagnosticSnapshot` 并进来——读细之后收回：它是 1:N 的细节，属于散文层，不属于记录。）

---

## 十、落地顺序（每步一条会红的守卫）

1. **类型与 tracker**：`AttemptRecord`、`AttemptConfig`、`AttemptTracker`、`encodeAttempt`（手写 JSON 行，不引依赖）、词表锁定测试。落 `shared`，时钟与 `install` 注入，无 Android 依赖。
   守卫：`fail` 必带 `why`；`ok` 的 `why` 必为 `"-"`；`blocked` 必有 `why` 且无台阶；`detail` 带引号/换行时编码仍是合法单行 JSON；三个枚举的 `name` 列表被钉死。
2. **四个钩子 + 落盘**：拒绝也写一条，`blocked` 不是空白。
   守卫：一次尝试恰好一条记录；被拒绝的一次也有；`ok` 在 `connectionComplete` 那一刻就关闭（后续 `ControlEnded` 不改它）。
3. **报告接入**：表格 + 附录。
   守卫：没有任何会话时保存报告，仍能看到最近一次尝试的结论行（今天这条是空的）。
4. **失败码上屏**：与记录里的三个字段同源。
   守卫：码由同一函数产出，改一处两处一起变。

---

## 十一、已落地（2026-10-17）

| 件 | 位置 | 守卫 |
|---|---|---|
| 记录类型、四态、`code`、JSON 编码 | `shared/.../orchestration/AttemptRecord.kt` | golden line 钉死字段序；`detail` 带引号换行仍是一行 |
| tracker（`step/fail/block/abandon`，关一次） | `shared/.../orchestration/AttemptTracker.kt` | `fail` 必带 why、`blocked` 必带缺口且无台阶、`ok` 在 `connectionComplete` 关闭、第二次结局被忽略 |
| 词表钉死 | `AttemptTrackerTest` | 三个枚举的 `name` 列表 |
| `events.jsonl`（追加、256 KB 轮转、install id） | `common/.../AttemptJournal.kt` | 一次尝试一行、拒接也有、轮转后仍在、install 只抽一次 |
| 四个钩子 | `CarPlayHostActivity`（开始 / 状态 / shutdown）、`DiPlayActivity.refuseConnection` | — |
| 报告：最近 5 次表 + `events.jsonl` 附录 | `DiPlayActivity.recentAttempts / buildDiagnosticSections` | 事件段不过通用 redact |
| 失败码上屏 | `createStatusReporter` 的 `Failed` 分支，接在说明句下面 | 与记录同源 |

未做（下一步）：报告头的表还没有在真机上核对排版；`DisplayDiagnosticSnapshot` 的 id 未共用（见上，已决定不共用）。

## 十二、两个已定的边界

- **所有版本都埋**：不加 `BuildConfig` 开关，不做"实验性"分组。记录本身不含任何需要按版本区分的内容。
- **不上传就不改隐私文案**：记录留在 App 私有目录、随用户手动导出的报告一起走，与 `PRIVACY.md` 现在的说法一致，`strings.xml:288` 那句 "Nothing is sent automatically." 仍然成立。**代价是数据只在有人按键时才出车**；要把这一点改掉属于上传方案，与本方案正交。
