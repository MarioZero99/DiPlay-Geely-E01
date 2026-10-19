# 停用「语音」会不会让 E01 死机？

**版本 2（2026-10-19）** — 上一版把「语音」当成一个可 `pm disable` 的 App 来推演，**前提就错了**。平台事实笔记里早有实测：这台车的语音内核是 init 拉起的原生进程 `natp`，不是 APK。本版按真实架构重写。

---

## 结论

在这台车上，「停用语音」是**三件完全不同的事**，风险不在一档：

| 要停的东西 | 是什么 | 能不能停 | 死机风险 |
| --- | --- | --- | --- |
| **`com.ecarx.xiaoka`**（晓咖，62 MB） | `/system/app/AIXiaokaGUI`，提供 `XKVoiceService`（`ecarx_vr_service`），也是三个系统无障碍服务之一 | 能，`pm disable-user` 即可 | **低**。后果是语音车控/唤醒失效、客户端 bind 失败，不是死机 |
| **`com.ecarx.xcttsengine` / `com.ecarx.xcttsservice`** | TTS 引擎与 TTS 服务（后者 APK 105 MB） | 能 | **低**。导航播报变哑，`STREAM_NAVI_TTS`（E01 上是 10）那条链路断 |
| **`natp`**（263 MB） | `/system/bin/natp`，Nuance Dragon Drive（ASR+声纹+对话），`uid=1000(system)`、`PPID=1`，开机 29.7 秒出现 | **不能**。`pm disable` 对它无效——它不是 APK | **这才是真风险，但性质是变砖不是死机**（见下） |

**所以「停用语音会不会导致 E01 死机」的答案分两句：**

1. 停那两个 APK（晓咖 / TTS）：**不会死机**。已经有过一次同方向的实证——2026-10-06 停用的 5 个包里就有 `com.baidu.iov.dueros.activate`（DuerOS 语音激活器），没记录到任何异常。
2. 停 `natp`：**不是「会不会死机」，是「回不回得来」**。要 root、要改 `/system`、它由 init 监护（`PPID=1`，杀了会被拉回），而这台车**没有 TWRP**、boot/recovery 有 LK 层签名校验、`mtk-su` 连跑 6 次车机直接重启。这条路一旦走坏，没有 recovery 兜底。

**而且这件事已经拍过板了**：`有线与性能.md:33` 的硬约束写着「**`natp` 及晓咖 / TTS 不动**（占一颗核 40%、约 254 MB）」。在做性能决策时，它已被当作不可动的地基，不是待优化项。

> 如果问的是「`natp` 本身能不能改写」，见另文 [NATP-REWRITE-FEASIBILITY.md](NATP-REWRITE-FEASIBILITY.md)：结论是配置层可改、`setprop ctl.stop run_natp` 可逆、换二进制不建议。

---

## 一、真实架构（来自 `平台事实.md`）

```
MCU ──XSFInputService──XCInputService──injectInputEvent──► 标准 MEDIA_BUTTON 广播
                                                              └─► DiPlay（只认 87/88 等）
语音键 ──► 前台窗口（DiPlay 自 commit 76519ea6 起不再处理，见下）

natp（原生，263MB，PPID=1）── 持续抓麦克风 ──► /sdcard/upload/*.pcm（写失败重试）
      ▲
      └── 晓咖 XKVoiceService（com.ecarx.xiaoka，62MB）── 客户端：com.ecarx.sdk.openapi
TTS：com.ecarx.xcttsengine / com.ecarx.xcttsservice（105MB）
```

关键事实（`平台事实.md`）：

- `:33` `natp` **不是 APK，`pm disable` 无效**；`PPID=1`，init 监护。
- `:34` `natp` 持续抓麦克风往 `/sdcard/upload/` 写 `.pcm`，写失败重试；`GCF_DDFW_FATAL.log` 里已有 `nuance.audio.PalBufferAudioInputAdapter invalid state STATE_STARTED`。
- `:35` 依赖链：晓咖提供 `XKVoiceService`，`com.ecarx.xcttsengine` 提供 `TtsService`。
- `:30` PSS 排行：`natp 263 MB` > 高德 124 MB > mediaserver 116 MB > hvac 88 MB > 晓咖 62 MB。**语音是整个车机第一大内存户**。
- `:31` 2026-10-06 已停 5 包：`ecarx.ads`、`ecarx.violationinquiry`、`com.baidu.iov.dueros.activate`、`ecarx.membercenter`、`com.qihoo360.vehiclesafe`。
- `:36` S 级勿动清单已含 `com.ecarx.sdk.openapi`（晓咖的客户端 SDK 宿主）。

---

## 二、上一版哪里错了（保留记录，避免重复犯）

| 上一版说 | 实际情况 |
| --- | --- |
| 「先拿包名，再 `pm disable-user`」 | 对晓咖/TTS 成立；对真正占 263 MB 的 `natp` **完全不成立**，它没有包名可停 |
| 「停用后立刻测 CarPlay 麦克风会不会 EBUSY」 | 方向反了：`natp` **一直在抢麦克风**，问题不是停用才出现，而是它本来就在；要测的是「在 natp 活着的前提下 DiPlay 拿不拿得到麦」 |
| 引 `CarPlayHostActivity.kt:929` 说语音键由车机层直接拉起 | 结论对（这键不归 DiPlay），但机制写法不准：实际链路是 `XCInputService` 注入标准键码，语音键则走前台窗口；且 `平台事实.md:55`「由 `dispatchKeyEvent` 处理并唤醒 Siri」已被 commit `76519ea6`（2026-10-15）推翻，**那句是过期结论** |

---

## 三、如果只是想要内存，能走的路

按收益/风险排序：

1. **什么都不动**。`natp` 263 MB 是最大头，但它是语音内核，动了没退路；晓咖/TTS 已由性能决策冻结。
2. **维持已停的 5 包**（`:31`）。`com.qihoo360.vehiclesafe` 那个还挂 AppInstallGuard/AppUninstallGuard，停了才装得上 APK。
3. **如果设置里有「语音唤醒 / 免唤醒」开关**，关它是唯一零组件风险的动作。注意：它改的是 `natp` 的行为，不是 `natp` 的存在，内存回收有限（热词引擎可能仍常驻）。
4. **不要**为了 263 MB 去 root 停 `natp`：没有 recovery、boot 有签名校验、`mtk-su` 连跑 6 次就重启——这三条任意一条都足以让「省 263 MB」变成「跑一趟 4S」。

---

## 四、真要停那两个 APK，按这个来（风险是功能，不是死机）

```bash
# 先看清楚它现在活着干什么
adb shell ps | grep -E "ecarx.xiaoka|xctts"
adb shell dumpsys meminfo com.ecarx.xiaoka | head -15

# 停（只停用，不卸载；5.1 没有 pm install-existing 这条回头路）
adb shell pm disable-user --user 0 com.ecarx.xiaoka
adb shell pm list packages -d

# 回滚
adb shell pm enable com.ecarx.xiaoka
```

停晓咖后必须同时检查一件事：它原本挂在 `enabled_accessibility_services` 里（`:62` 提到原有 3 个系统服务 naviservice / controlboard / xiaoka）。停用后这条 secure 设置里会留一个无效条目，客户端可能反复尝试绑定——要么同步把条目删掉，要么别停。

验收（每项都开 logcat 看 `FATAL EXCEPTION|ANR in`）：

| 检查项 | 期望 | 出事的样子 |
| --- | --- | --- |
| 桌面、设置、滑动 | 正常 | 黑屏/闪退 → 立刻 `pm enable` |
| 语音键 | 按了没反应（正常） | 反复弹「已停止运行」→ 原厂调用方无保护，别停 |
| 语音车控（说「开空调」） | 失效（可接受） | — |
| 导航播报 | 有声（没停 TTS 的话） | 变哑 → 说明默认 TTS 是它 |
| CarPlay 打电话 / Siri | 麦克风有上行 | 无声 → 先确认这是不是**本来就这样**（`natp` 一直占麦） |

---

## 五、还没被回答的问题（只有车上能测）

- [ ] 在 `natp` 活着、持续写 `.pcm` 的前提下，DiPlay 的 `AudioRecord` 到底能不能稳定拿到麦？（这是 CarPlay 通话/Siri 的真问题，和「停不停语音」无关）
- [ ] `natp` 写 `/sdcard/upload/` 失败重试时，磁盘/IO 抖动对 30 帧有没有可测量的影响？
- [ ] 设置里到底有没有「语音唤醒」开关，关掉后 `natp` 的 PSS/CPU 掉多少？
- [ ] 停晓咖后，`enabled_accessibility_services` 那条要不要一起清、清了会不会影响 controlboard/naviservice。
