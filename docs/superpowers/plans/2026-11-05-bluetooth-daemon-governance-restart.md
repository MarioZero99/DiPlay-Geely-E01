# 原车蓝牙服务治理与一键重启实施方案（精简版）

**Goal:** 在【车机治理】（Vehicle Governance）板块中增加「原车蓝牙服务治理」卡片与「重启底层蓝牙服务」功能，针对底层 gocsdk 守护进程或 SPP 套接字池卡死导致的“蓝牙断开再也连不上”提供 3~5 秒免重启整车机的一键自愈逃生通道。

## 一、方案设计（极简直接实现）

1. **入口归属**：位于【设置】->【车机治理】（`vehicleGovernanceCards`），紧随 CarLife 治理与语音治理之后。
2. **底层执行通道**：
   - 优先通过 `vendorHop(context)?.performMaintenance(this, "payload")`，直接复用底层已有的 `E01GocHijack.restart(context)`（安全停止 gocsdk、释放套接字、杀死旧进程、重拉候选守护进程）；
   - 回退通道：若无 vendorHop 则通过 `RootShellBridge.execute("/system/bin/stop gocsdk; sleep 1; /system/bin/start gocsdk")`。
3. **安全与互锁**：
   - 会话进行中置灰：当 `CarPlayBackgroundSession.hasSession() == true` 时，按钮禁用并显示警告文字；
   - 弹窗二次确认：防止误触断连；
   - 异步处理与反馈：执行期间显示“正在重启蓝牙服务…”，完成后弹出 Toast 提示。

## 二、修改文件

- [`DiPlayActivity.kt`](file:///Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely/common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt)：增加 `bluetoothGovernanceCard`、`confirmBluetoothRestart`、`runBluetoothRestart`，接入 `vehicleGovernanceCards`。
- [`strings.xml`](file:///Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely/common/src/main/res/values/strings.xml) / [`values-zh-rCN/strings.xml`](file:///Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely/common/src/main/res/values-zh-rCN/strings.xml)：补充卡片与按钮文案。
- [`BluetoothGovernanceUiTest.kt`](file:///Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely/common/src/test/java/com/shilapi/xcertplay/BluetoothGovernanceUiTest.kt)：自动化验证按钮渲染、会话互锁与弹窗行为。
