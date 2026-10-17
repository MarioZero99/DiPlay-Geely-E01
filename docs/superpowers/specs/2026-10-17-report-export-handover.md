# 交接：E01 诊断报告导出（U 盘）— 未完成工作

- 日期：2026-10-17 21:0x · 状态：**设计已成文，实现零改动（已回滚），等复工信号**
- 上游设计稿：`docs/superpowers/specs/2026-10-17-report-destination-design.md`（v3，未入 git）
- 一句话：用户拍板"先做 U 盘那条，二维码以后再说"；开工后又被要求整体回滚（"不是让你做导出"）。回滚已完成并逐项验证，本功能对工作树的净改动为 0。

---

## 一、要解决的问题（三个独立故障，别混看）

E01（API 22 车机）上点"保存诊断报告"不能把文件存到 U 盘。拆开是三件事：

1. **选择器没人应答。** `ACTION_CREATE_DOCUMENT` 只由实现了 DocumentsProvider 的 Activity 应答；文件管理器是 `ACTION_VIEW` 的消费者，天生不在解析结果里。原生 5.1 自带 `com.android.documentsui`，E01 被 OEM 裁掉/禁用 ⇒ picker 永远打不开。代码：`DiPlayActivity.kt:205`（注册 `CreateDocument`）、`:2652-2658`（`chooseReportDestination`，launch 失败兜底直存）、卡片在 `:764-772`。当前分流是 Q+ 直存 Download、<Q 才走 picker——E01 是 API 22，走的正是坏的那条。
2. **就算选到 U 盘根也写不进。** targetSdk 37 + API 22：二级卷只对本包目录 `<vol>/Android/data/<pkg>/` 开放写入，根目录被 FUSE 拒（EACCES）。而 `DiagnosticExportStore.kt:72-77` 是硬编码猜挂载点（`/storage/usbotg`、`/mnt/usb_storage`、`/mnt/usb`），`:88-89` 探测失败即静默 `return`。
3. **失败被吞、谎报成功。** `canWrite()` 静默 return + 外层全包 `runCatching` + 弹窗不分支 ⇒ 真实落点其实是 `getExternalFilesDir()/diagnostic-reports`（一定成功），U 盘那份从没真正写进去，弹窗却说"已保存"。普通用户全程无感，这是最伤的一点。

## 二、用户已做的决策（按时间序；含被否方案及原因，防重复推翻）

| # | 决策 | 否掉了什么 |
|---|---|---|
| 1 | 不假设任何人会 adb / 看 logcat / 判断挂载；判定与解释全部由 App 在屏幕上完成 | "先 adb 实测再选方案"（v1 的前置） |
| 2 | 两套方案并行、都随包发出：方案一一键存 U 盘；方案二车机一次性 HTTP + 二维码、手机取 | 只做单一通道 |
| 3 | **不能猜 U 盘名字/挂载点**。挂载点三来源：`getExternalFilesDirs(null)` 第 2 项起（系统给的包目录，不需要名字）、`ACTION_MEDIA_MOUNTED` 广播的 `data`、厂商根只作兜底 | v2 把"猜根目录"放第一层 |
| 4 | 方案二**确实需要同网**（车机热点），用 `WIFI:T:WPA;S:..;P:..;;` 二维码把"连热点"压成扫一次；**captive portal 无 root 不可行**（DNS 在系统 dnsmasq 手里），已划掉 | "连上热点自动弹下载页" |
| 5 | v3 主次：方案二为主、方案一为顺手 | — |
| 6 | **最新拍板：先做方案一（U 盘），二维码/方案二以后再说** | 推翻 #5 的主次，以本条为准 |
| 7 | 实现开工一版后，用户要求**全部回滚**（"回滚你的改动 不是让你做导出"） | 本轮实现 |

**复工时以 #6 为准**：设计稿 §三/§五 写的"方案二先、方案一后"已过期，读稿时请直接按"方案一优先"执行（§1 的技术内容不受影响）。

## 三、方案一的技术要点（照设计稿 §1 执行）

- **卷枚举三来源**（任一成立即可写）：`getExternalFilesDirs(null)` 第 2 项起；插盘 `ACTION_MEDIA_MOUNTED` 的 `data` 路径；厂商挂载根兜底。**不需要知道卷名**，卷名只用于弹窗显示。
- **写入顺序 b→a→c**：① `<卷>/Android/data/<pkg>/files/DiPlay/`（契约保证可写，先写它）② `<卷>/DiPlay/`（用户好找，成功则优先报告）③ `Download/DiPlay/`（本机保底）。①或②成功才报"已存入 U 盘 + 真实路径"；只有③成功就明说没写进 U 盘的原因，**绝不假报成功**。
- **硬边界**：若 E01 把 U 盘挂成厂商私有挂载（不在 `getExternalFilesDirs`、不发 `MEDIA_MOUNTED`、FUSE 不给本包目录写），无 root 下方案一不可能成功。因此按钮按"探测到可写卷"才出现；探测不到就不出现，而不是让用户点了失败。
- **失败取证（无 adb）**：弹窗底部 12sp 小字透出探测结果，如 `发现 2 个卷 · usbotg-sda1 可写(包目录) · 根目录 EACCES`，用户截图即证据。
- 写盘后 `fd.sync()` + `MediaScannerConnection.scanFile`，否则车机文件管理器要拔插才看得见。

## 四、工作树现状（2026-10-17 21:0x，回滚后已验证）

本功能净改动 = 0，逐项核过：

- `DiagnosticExportStore.kt`：`git checkout` 还原，与 HEAD 一致；
- `DiagnosticDestinations.kt`（本轮新增的枚举文件）：已删除；
- `DiPlayActivity.kt`：导出段与 HEAD 一致（`:205` 注册、`:2652-2658` chooseReportDestination、`:2660+` exportDiagnostics、`:764-772` 卡片），无 probe/usbTarget/mediaMounted 等残留；
- `strings.xml` / `values-zh-rCN/strings.xml`：各只有 2 处新增，是**另一条线**的 `exit_confirm_body` / `cluster_display_not_detected`，不是本功能的。

**并发警告（重要）**：同一工作树有另一会话在改 UI 重构 + 埋点落地（`git status` 里的 `CarPlayHostActivity.kt`、`SlimDockView.kt`、`ConfirmSheetView.kt`(新)、`InteractionFlow.kt`(删)、`AttemptTracker/AttemptJournal/AttemptRecord`(新) 等都不是本功能的改动，**别回滚错文件**）。`DiPlayActivity.kt` 是双方共同文件：改它必须小步 + 每步 `./gradlew :common:compileDebugKotlin`；中途出现过一次因对方未落 `leaveToLauncher` 导致的编译失败，几十秒后自行恢复，不是本功能的错。

## 五、复工第一步

1. 读设计稿 §1（技术内容有效），忽略其 §三/§五 的顺序（以用户拍板"方案一先"为准）。
2. 做探测与回显：卷枚举（三来源）+ `mkdir` 试写探测 + errno 透出 + `uri == null` 分支。这一步无新功能，但让所有后续判断有依据。
3. 三层写入 b→a→c + 按钮按探测结果显示 + 失败弹窗带诊断行。
4. 收口：删 `choose_save_location` 第二个按钮（`:770`）与 `CreateDocument` 这条路（`:205`），一个出口一个按钮。
5. 方案二（二维码 + one-shot HTTP）等用户发话再做；届时先定 QR 自写还是引 zxing。

## 六、待拍板

1. 方案一按钮出现条件：只在探测到可写卷时显示（设计稿推荐）vs 始终显示、失败再转方案二？
2. 只写进 `Android/data/<pkg>` 成功时：如实报路径 vs 提示"用手机取"（涉及是否愿意让用户看到包目录路径）？
3. （方案二复工时再定）QR 自写（约 250 行、零依赖、推荐）还是引 zxing？老机型不认 Wi-Fi 码时，屏幕是否愿意大字显示热点密码？

## 七、文档索引

- 本交接：`docs/superpowers/specs/2026-10-17-report-export-handover.md`
- 导出设计稿 v3：`docs/superpowers/specs/2026-10-17-report-destination-design.md`
- 埋点设计稿 v2（另一条线，已部分落地）：`docs/superpowers/specs/2026-10-17-telemetry-design.md`
- UI 重构计划（另一条线，已提交）：`docs/superpowers/plans/2026-10-17-ui-redesign-plan.md`
- 当日全过程记忆：`/Users/g/Documents/project/xr/.workbuddy/memory/2026-10-17.md`
