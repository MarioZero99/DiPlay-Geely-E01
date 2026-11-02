# 蓝牙断开重连后无线连接修复计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 一次改完「蓝牙断开后再连上，无线 CarPlay 起不来」。依据已经在手的 `gocsdk` 二进制，而不是再等一轮上车日志才选分支。

**推断（2026-11-02，来自 `diplay-vendor-hop/payload/gocsdk-spp-uuid128-v2` 的字符串，以及 geekplay 反编译）：**

车机上有三份东西，不要混：

| 东西 | 能不能反编译出重连逻辑 |
|---|---|
| `XCBTService.apk`（`ecarx.bluetooth.service`） | 不能。包里没有 `classes.dex`，jadx 只得到 `R.java`。`getConnectedDevices` 在车机旁路的 odex 里。 |
| `Bluetooth-GocBtAPI.apk`（`com.nforetek.bt`） | 能，它有 dex。样本不在 `extracted_apks`，在固件 `system.new.dat`（约 2.7GB）里。它只把 `CONNECT_HFP` / `CONNECT_A2DP` / `CONNECT_SPP` 交给 daemon，不跑 iAP2。 |
| `gocsdk-spp-uuid128-v2` | 这就是 GOC。stripped ARM PIE，jadx 用不上。字符串已经把重连顺序写明。 |

Daemon 自己的顺序：

- 先连 HFP。iPhone 的 A2DP 在 HFP 之后 **2 秒**再连（`hf connected delay 2s to connect iphone a2dp`）。其它手机是 3 秒。DiPlay 等手机的窗口是 15 秒，盖得住这 2 秒。
- 正常重连不会停在「只有 HFP」。A2DP 失败才会拖住（`a2dp connect fialed delay to connect again`），或被主动拆掉（`Current Must Disconnect A2dp`）。这不是每次重连的默认路径。
- HFP 60 秒没完成时 daemon 自己重启（`hfp connect,wait 60s no response,restart`）。那一分钟里两个 profile 都会空。这是延迟，不是门禁写错。
- SPP 是独立 profile，不挂在 HFP∩A2DP 上。套接字名 `goc_spp`。接上之后仍会失败：`spp_socket connect not inited, wait`、`spp_socket connect %s error,not inited`、`spp error addr:%s`、`spp_socket error:all clients are connected`。
- 车上跑通过的 geekplay 用的是同一句 `CollectionsKt.intersect(hfp, a2dp)`。交集不是这次要拆的门。

DiPlay 现在只在 `LocalSocket` 还没在听时重试：`ENOENT` / `ECONNREFUSED`，3 次，间隔 200ms（`GocSppTransport.connect`）。Daemon 那些 “not inited / all clients” 发生在 accept 之后，应用看到的是握手后立刻 EOF，不会重试。

**Architecture:**

1. 交集保持不动。纯 A2DP 仍然不是候选。
2. 交集为空时打出两个 profile 各自的地址，上车用来核对上面的推断，不作为是否改代码的开关。
3. `goc_spp` 握手后若立刻 EOF，或套接字层被拒，按 daemon 的 “not inited, wait” 再连。等待要盖住 iPhone A2DP 那 2 秒，而不是 200ms。旧的客户端必须先关掉，避免打中 `all clients are connected`。

**Tech Stack:** Kotlin, Android API 22, JUnit 4。测试：`DiPlay-Legacy-Android-Geely` 里 `:shared:testDebugUnitTest -PvendorHopDir=/Users/g/Documents/project/diplay-vendor-hop`。

## Global Constraints

- 不改 HFP∩A2DP。不把纯 A2DP 放进候选。
- 单手机地址不一致已经由 `resolveTarget` 用车机报的那一台，不要再加一层地址匹配。
- 不听 AOSP 的 `ACTION_ACL_CONNECTED`。geekplay 的 dex 里也没有蓝牙已连接广播，只有 `ecarx.intent.action.ECARX_KEY_RVOICEASSIST_EVENT`。
- 不去抢 TCP 7000。这台车 7000 是 uid 1021 占着，DiPlay 听 7001。
- `diplay-vendor-hop` 没有 Gradle 工程。测试和提交都按下面写的路径。

---

### Task 1: 交集为空时写明两个 profile

**Files:**

- Modify: `/Users/g/Documents/project/diplay-vendor-hop/src/com/shilapi/xcertplay/network/EcarxConnectedIphoneLookup.kt`
- Test: `/Users/g/Documents/project/diplay-vendor-hop/test/com/shilapi/xcertplay/network/EcarxConnectedIphoneLookupTest.kt`

返回值仍是交集。交集为空时只多这一句，无设备写 `none`，有设备写 `地址(名字)`，多个用逗号加空格：

`ecarx no phone on both profiles: hfp=[CC:60:23:D7:2F:5B(iPhone)] a2dp=[none]`

两边都空：`ecarx no phone on both profiles: hfp=[none] a2dp=[none]`

- [x] **Step 1: 先写失败测试**

  用例名 `emptyIntersectionNamesEachProfile`，三组断言 `said` 含整行：

  - HFP `CC:60:23:D7:2F:5B` / `iPhone`，A2DP 空 → `hfp=[CC:60:23:D7:2F:5B(iPhone)] a2dp=[none]`，返回空列表
  - 两边都空 → `hfp=[none] a2dp=[none]`
  - 只有 A2DP 上有同一台 → `hfp=[none] a2dp=[CC:60:23:D7:2F:5B(iPhone)]`，返回空列表

- [x] **Step 2: 跑红**

  ```bash
  /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely/gradlew -p /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely :shared:testDebugUnitTest -PvendorHopDir=/Users/g/Documents/project/diplay-vendor-hop --tests "com.shilapi.xcertplay.network.EcarxConnectedIphoneLookupTest.emptyIntersectionNamesEachProfile"
  ```

- [x] **Step 3: 实现**

  在 `connectedDevicesFromProfileApis` 里，`intersect` 为空时 `onDiagnostic` 打上面那句。`joinToString` 的默认分隔就是逗号加空格。删掉 `connectedDevices()` 里那句 `the car reports no phone connected on both profiles`。

  `did not answer`、`threw or answered nothing`、`reported 00:00:00:00:00:00`、`had no address` 保持原样。Binder 没应答时走不到这句新日志。

- [x] **Step 4: 跑绿**

  同一条 Gradle 命令去掉方法名，跑整个 `EcarxConnectedIphoneLookupTest`。原有 6 个用例加上这一个都过。

- [x] **Step 5: 提交**

  ```bash
  git -C /Users/g/Documents/project/diplay-vendor-hop commit -am "feat(vendor-hop): name each profile when the connected-phone intersection is empty"
  ```

---

### Task 2: `goc_spp` 在 SPP 尚未就绪时重试

**Files:**

- Modify: `/Users/g/Documents/project/diplay-vendor-hop/src/com/shilapi/xcertplay/transport/GocSppTransport.kt`
- Test: `/Users/g/Documents/project/diplay-vendor-hop/test/com/shilapi/xcertplay/transport/GocSppTransportTest.kt`

Daemon 在套接字 accept 之后才判断 SPP 是否 init。现在的 3×200ms 只覆盖 “socket 文件还不在”。握手写完 12 位地址后，对端立刻关掉，应用必须再连，并且先关掉上一只 socket。

- [x] **Step 1: 先写失败测试**

  - 第一次 `connectReserved` 成功，`writeHandshake` 之后第一次读立刻 EOF。断言会再 `connectReserved` 一次，第二次读到字节后才返回。
  - 第一次 `connectReserved` 抛 `IOException("ENOENT")` 时，现有 200ms 重试仍然在，不要拉长到整段 SPP 等待。
  - 重试前上一只 socket 的 `close()` 被调用过。

- [x] **Step 2: 跑红**

  ```bash
  /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely/gradlew -p /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely :shared:testDebugUnitTest -PvendorHopDir=/Users/g/Documents/project/diplay-vendor-hop --tests "com.shilapi.xcertplay.transport.GocSppTransportTest"
  ```

- [x] **Step 3: 实现**

  `connect()` 在写出地址之后，用一次短读确认这条流还活着。立刻 EOF 或写地址时被断开：关掉这只 socket，再连。

  这一段的预算是 8 秒、间隔 500ms。8 秒盖住 daemon 那句 “not inited, wait” 和 iPhone A2DP 的 2 秒，又短于现有 15 秒等手机的窗口。`ENOENT` / `ECONNREFUSED` 仍走原来的 3×200ms，不要让“套接字根本没在听”也空等 8 秒。

  每次失败都 `close()` 当前 socket，再创建下一只。不要把上一只留在 `all clients are connected` 那个名额上。

- [x] **Step 4: 跑绿**

  再跑 `GocSppTransportTest` 和 `GocIap2BluetoothLinkTest`。

- [x] **Step 5: 提交**

  ```bash
  git -C /Users/g/Documents/project/diplay-vendor-hop commit -am "fix(vendor-hop): retry goc_spp when the daemon accepts then drops the new client"
  ```

---

### Task 3: 上车核对，不另开分支

装进 Task 1 和 Task 2 的包。手机连上、无线起来、关掉手机蓝牙、再打开并连上车机。

日志对得上推断时，重连会在 SPP 重试窗口里出现 `wireless RFCOMM connected`。

对不上时只看这三句，不要回头改交集：

- 整段 15 秒都是 `hfp=[地址(名字)] a2dp=[none]`：A2DP 没有按 2 秒起来。这是 daemon 的 `a2dp connect fialed` 或 `Current Must Disconnect A2dp`，不是 Kotlin 门禁。
- `hfp=[none] a2dp=[none]` 持续超过 15 秒：daemon 在 `hfp connect,wait 60s no response,restart`，或 ECARX 的列表是空的。XCBTService 的 odex 才能看 `getConnectedDevices`，这份 APK 看不了。
- 已经有 `wireless selected Bluetooth target`，随后是 `Could not connect to reserved LocalSocket goc_spp` 或 `wireless RFCOMM EOF`：SPP 重试没盖住。把 EOF 前 daemon 能打出的那句（not inited / all clients / bad addr）对上再加长预算。GOC 这条路没有 `wireless RFCOMM connect timed out`，那句在 `AndroidIap2BluetoothLink`。
