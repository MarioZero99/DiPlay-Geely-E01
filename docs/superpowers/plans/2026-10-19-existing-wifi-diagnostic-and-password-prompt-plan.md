# 同网 Wi-Fi 密码缺失与错误诊断提示实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 解决同网模式（`EXISTING_WIFI`）下车机 Wi-Fi 密码缺失或填错导致 CarPlay 无线连接超时且无明确提示的问题：
1. 加密网络未配密码时 0 秒即时拦截，不再白等 30 秒超时；
2. 修复底层 ARP 网关误判 Bug，排除网关 IP 并输出排查日志，精准断定 iPhone 未能入网；
3. UI 界面展示清晰的静态错误文本，并停止盲目死循环重试；
4. 密码输入防呆增加 `.trim()` 和 WPA2 最低 8 位长度有效性校验。

**Architecture:** 
1. **网络层与数据载体**：
   - 在 `WirelessHotspotInfo` 中透传 `defaultGateway`。
   - 在 `ExistingWifiManager` 中判定：若当前网络不是 OPEN 网络且无可用密码，0 秒抛出 `ExistingWifiCredentialsMissingException`，直接阻断。
2. **连接控制器层**：
   - 修复 `CarPlayController.ipv4PeersOnAccessPoint()`，将 `wirelessHotspotGateway` 传入 `arpPeersOn`，并在日志中输出排除网关后的 peer 数量与排除的网关 IP。
   - 若排除后 peer 为空，抛出 `ExistingWifiPhoneNotOnNetworkException`。
   - 映射新增两个独立的 `CarPlayFailureReason`：`EXISTING_WIFI_CREDENTIALS_MISSING` 和 `EXISTING_WIFI_PHONE_NOT_ON_NETWORK`。
3. **宿主生命周期与提示层**：
   - 在 `CarPlayHostActivity` 中将上述两类失败归为非网络重试型错误（停止自动盲目重试，记录日志等待用户手动处理）。
   - 在中英文资源中增加明确的静态指导文案。
4. **存储与输入防呆**：
   - 在 `AirPlayPersistence.saveExistingWifiNetwork` 中自动对司机输入的密码执行 `.trim()`，并增加 8 ~ 63 位长度检查（若不满足则抛出 `IllegalArgumentException`）。
   - 在 `DiPlayActivity` 弹窗输入处增加最少 8 位实时防呆提示。

**Tech Stack:** Android SDK (API 21+), Kotlin, JUnit 4, Robolectric.

---

### Task 1: 在 `WirelessHotspotInfo` 透传 `defaultGateway` 与同网未配密码 0 秒拦截

**Files:**
- Modify: `shared/src/main/java/com/shilapi/xcertplay/network/WirelessHotspotManager.kt:14-75`
- Modify: `shared/src/main/java/com/shilapi/xcertplay/network/ExistingWifiManager.kt:240-300`
- Test: `shared/src/test/java/com/shilapi/xcertplay/network/ExistingWifiManagerTest.kt`

**Interfaces:**
- Produces: `WirelessHotspotInfo.defaultGateway: String?`
- Produces: `class ExistingWifiCredentialsMissingException(val ssid: String, message: String) : IOException(message)`

- [ ] **Step 1: Write the failing tests**

在 `ExistingWifiManagerTest.kt` 中添加：
1. 验证 LinkProperties 解析到的默认网关正确透传至 `WirelessHotspotInfo.defaultGateway`。
2. 验证当 Wi-Fi 为加密网络（WPA/WPA2）但无任何可用密码时，直接抛出 `ExistingWifiCredentialsMissingException`，实现 0 秒即时拦截。

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :shared:testDebugUnitTest --tests com.shilapi.xcertplay.network.ExistingWifiManagerTest`
Expected: FAIL.

- [ ] **Step 3: Implement `defaultGateway` and credentials check in `ExistingWifiManager`**

1. 在 `WirelessHotspotInfo` 中增加 `defaultGateway: String? = null` 属性。
2. 定义 `ExistingWifiCredentialsMissingException`。
3. 在 `ExistingWifiManager.attach()` 中：
   - 赋值 `defaultGateway = defaultGateway(attached.properties)`。
   - 若 `credentials.security != Iap2WirelessSecurity.NONE && password.value.isNullOrEmpty()`，抛出 `ExistingWifiCredentialsMissingException`。

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :shared:testDebugUnitTest --tests com.shilapi.xcertplay.network.ExistingWifiManagerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add shared/src/main/java/com/shilapi/xcertplay/network/WirelessHotspotManager.kt \
        shared/src/main/java/com/shilapi/xcertplay/network/ExistingWifiManager.kt \
        shared/src/test/java/com/shilapi/xcertplay/network/ExistingWifiManagerTest.kt
git commit -m "feat(wireless): pass defaultGateway and fast-fail when existing wifi credentials missing"
```

---

### Task 2: 修复 `CarPlayController` 排除网关误判并新增故障类型与排查日志

**Files:**
- Modify: `shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt`
- Test: `shared/src/test/java/com/shilapi/xcertplay/orchestration/CarPlayFailureReasonTest.kt`

**Interfaces:**
- Produces: `CarPlayFailureReason.EXISTING_WIFI_CREDENTIALS_MISSING`
- Produces: `CarPlayFailureReason.EXISTING_WIFI_PHONE_NOT_ON_NETWORK`
- Produces: `class ExistingWifiPhoneNotOnNetworkException(val ssid: String?, message: String) : IOException(message)`

- [ ] **Step 1: Write the failing tests**

在 `CarPlayFailureReasonTest.kt` 中增加针对新异常类型映射的测试：
- `ExistingWifiCredentialsMissingException` -> `CarPlayFailureReason.EXISTING_WIFI_CREDENTIALS_MISSING`
- `ExistingWifiPhoneNotOnNetworkException` -> `CarPlayFailureReason.EXISTING_WIFI_PHONE_NOT_ON_NETWORK`

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :shared:testDebugUnitTest --tests com.shilapi.xcertplay.orchestration.CarPlayFailureReasonTest`
Expected: FAIL.

- [ ] **Step 3: Implement exception mapping, gateway exclusion & logging in `CarPlayController.kt`**

1. 在 `CarPlayFailureReason` 中添加 `EXISTING_WIFI_CREDENTIALS_MISSING` 和 `EXISTING_WIFI_PHONE_NOT_ON_NETWORK`。
2. 定义 `ExistingWifiPhoneNotOnNetworkException`。
3. 在 `failureReasonFor` 中添加对应异常映射分支。
4. 在 `CarPlayController` 中缓存 `wirelessHotspotGateway` 与 `wirelessHotspotBackend`。
5. 修复 `ipv4PeersOnAccessPoint()`：传入 `wirelessHotspotGateway`，并在日志中打印 `gatewayExcluded=$wirelessHotspotGateway`。
6. 在 `onFirstTcpTimedOut` 触发时，若在同网模式下发现排除网关后 0 peers，抛出 `ExistingWifiPhoneNotOnNetworkException`。

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :shared:testDebugUnitTest --tests com.shilapi.xcertplay.orchestration.CarPlayFailureReasonTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt \
        shared/src/test/java/com/shilapi/xcertplay/orchestration/CarPlayFailureReasonTest.kt
git commit -m "fix(wireless): filter gateway in arp check and report distinct existing wifi failures"
```

---

### Task 3: 静态错误文本与停止自动重试策略

**Files:**
- Modify: `common/src/main/res/values/strings.xml`
- Modify: `common/src/main/res/values-zh-rCN/strings.xml`
- Modify: `common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/CarPlayFailureReasonMessageTest.kt`

**Interfaces:**
- Produces: `R.string.failure_existing_wifi_credentials_missing`
- Produces: `R.string.failure_existing_wifi_phone_not_on_network`

- [ ] **Step 1: Write the failing tests**

在 `CarPlayFailureReasonMessageTest.kt` 中断言：
- `failureText(EXISTING_WIFI_CREDENTIALS_MISSING)` 包含密码配置提示。
- `failureText(EXISTING_WIFI_PHONE_NOT_ON_NETWORK)` 包含密码检查与手机入网提示。

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.CarPlayFailureReasonMessageTest`
Expected: FAIL.

- [ ] **Step 3: Add strings and update `CarPlayHostActivity.kt`**

1. 在 `strings.xml` 和 `values-zh-rCN/strings.xml` 中添加 `failure_existing_wifi_credentials_missing` 与 `failure_existing_wifi_phone_not_on_network`。
2. 在 `CarPlayHostActivity.failureText` 中添加映射。
3. 在 `CarPlayHostActivity` 失败重试判定中，对 `EXISTING_WIFI_CREDENTIALS_MISSING` 和 `EXISTING_WIFI_PHONE_NOT_ON_NETWORK` 停止自动重试，仅记录日志并保持当前静态错误文本展示。

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.CarPlayFailureReasonMessageTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add common/src/main/res/values/strings.xml \
        common/src/main/res/values-zh-rCN/strings.xml \
        common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt \
        common/src/test/java/com/shilapi/xcertplay/CarPlayFailureReasonMessageTest.kt
git commit -m "feat(ui): display clear static error text and stop retry on existing wifi errors"
```

---

### Task 4: 密码输入防呆优化（`.trim()` + WPA2 最低 8 位校验）

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/AirPlayPersistence.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/AirPlayPersistenceTest.kt`

**Interfaces:**
- Consumes: `AirPlayPersistence.saveExistingWifiNetwork(context, ssid, passphrase)`

- [ ] **Step 1: Write the failing tests**

在 `AirPlayPersistenceTest.kt` 中测试：
1. `saveExistingWifiNetwork` 自动去除首尾空格。
2. 当输入的密码非空但少于 8 位时，抛出 `IllegalArgumentException`（或拒绝保存非空短密码）。

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.AirPlayPersistenceTest`
Expected: FAIL.

- [ ] **Step 3: Implement validation in `AirPlayPersistence.kt` and `DiPlayActivity.kt`**

1. 在 `AirPlayPersistence.saveExistingWifiNetwork` 中对密码执行 `.trim()`，并校验若 `cleanPassphrase.isNotEmpty() && cleanPassphrase.length < 8` 则拒绝保存并抛出异常。
2. 在 `DiPlayActivity` 弹窗输入处，当用户输入的密码长度不足 8 位时给出提示并不允许保存。

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.AirPlayPersistenceTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/com/shilapi/xcertplay/AirPlayPersistence.kt \
        common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt \
        common/src/test/java/com/shilapi/xcertplay/AirPlayPersistenceTest.kt
git commit -m "fix(persistence): trim wifi passphrase and enforce minimum 8 chars for wpa2"
```

---

### Task 5: 全量回归构建与验证

- [ ] **Step 1: Run all unit tests**
Run: `./gradlew testDebugUnitTest`
Expected: 全模块所有单元测试 PASS。

- [ ] **Step 2: Run assembleDebug build**
Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.
