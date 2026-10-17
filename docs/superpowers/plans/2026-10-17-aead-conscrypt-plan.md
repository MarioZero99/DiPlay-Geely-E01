# ChaCha20-Poly1305：改用 Conscrypt，保留调用层复用

## 一、为什么放弃自研 native

自研的那份 C（`chacha20_poly1305.c` + `chacha20_jni.c`，约 300 行）能跑，能与 BouncyCastle 逐字节对齐，
但它有三件事无法自证：

1. **常量时间属性不可由源码保证。** 源码层看着没有密钥相关分支，但编译器的自动向量化和条件移动
   会破坏它。验证需要 dudect / ctgrind，没做。
2. **未定义行为只在特定工具链下暴露。** 本地是 NDK 25 Clang；换编译器、换优化等级，严格别名和
   有符号溢出的显现方式不同。BoringSSL 把 UBSan / ASan 常驻 CI，这份没有。
3. **组合边界覆盖不了。** 真实输入是「任意长度 × 任意截断 × 重放 × 畸形头部」，差分测试只覆盖
   了长度 × AAD × offset 的有限网格，而那份差分测试已经按用户要求删除，连回归基线也没有了。

收益侧：解密占单帧预算 2–3%（1080p30 约 6 Mbps，单帧 25 KB，ARMv7 上 BouncyCastle 约 0.7–1.0 ms）。
为这点收益把未审计的密码实现放进生产解密路径，风险收益比不成立。

**结论：生产路径交给 BoringSSL。** 它不是「Google 的另一个实现」，是 Android 平台自家 TLS 用的
同一份库，`EVP_AEAD_chacha20_poly1305` 在 arm64 / armv7 上有手写 NEON 汇编。

## 二、保留什么

调用层的活儿与用哪个内核无关，全部保留：

| 保留项 | 作用 |
|---|---|
| `Cipher` 实例复用 | 不重建 provider 对象 |
| `AirPlayAead.process` 写调用方数组 | 接收缓冲原地解密，无中间数组 |
| 调用方复用 nonce 数组（`nonce64Into`） | 每帧不再分配 12 字节数组 |
| `SecretKeySpec` 缓存（按 key 引用相等） | 每帧不再 clone 32 字节 key |
| 首帧 `aead=` 日志 | 报真正跑的实现 |

**一处纠正：`IvParameterSpec` 不能复用。** 它的构造函数会 clone 传入的 nonce，
`getIV()` 返回的也是 clone。复用一个实例会让第二帧及之后都用第一帧的 nonce。
每帧新建 12 字节的 spec 是 JCE 无法绕开的固定开销，保留即可。

## 三、步骤

- [x] **1. 删除自研实现**
  - `shared/src/main/jni/chacha20_poly1305.c` / `.h` / `chacha20_jni.c`
  - `shared/src/main/jni/Android.mk` 里的 `xcertplay_chacha20` 模块
  - `shared/.../airplay/NativeAead.kt`
  - 旧计划 `2026-10-17-native-chacha-plan.md`

- [x] **2. `AirPlayAead` 收敛为两条路径**
  顺序：JCE（API 28+ 平台 / 19–27 用 `conscrypt-android`）→ BouncyCastle。
  `AEADBadTagException` 直接上抛，不换实现重试（换实现只会得到同样的拒绝）。
  非验签类的 `GeneralSecurityException` 才熔断该 provider 并回落。

- [x] **3. JceAead 调用层复用**
  - 缓存 `SecretKeySpec`，判据是 key 数组引用相同；引用不同则重建。
  - `IvParameterSpec` 每帧新建，并在 KDoc 写明原因。
  - 保持 `doFinal(input, off, len, output, outOff)` 的写调用方数组形式。

- [x] **4. 确认构建**
  - `conscrypt-android:2.5.2`。2.6.x 的 manifest 声明 minSdk 21，而本模块 `minSdk = 19`，
    清单合并会直接失败，所以停在 2.5.2（它的 so 标记是 arm64=API 21、armv7=API 16，E01 的
    API 22 能加载）。
  - `abiFilters` 只留 `arm64-v8a` + `armeabi-v7a`

- [x] **5. 验证**
  - `./gradlew :shared:testDebugUnitTest`（不构建 APK）
  - `AirPlayCryptoReuseTest` 三例必须全绿

- [x] **6. 文档口径**
  - `有线与性能.md` 里「native 优先」改为「JCE 优先，BoringSSL 内核」

## 四、已知取舍

- **体积**：2 ABI 合计约 +1.39 MB（压缩后），APK 从 10.5 MB 到约 11.9 MB，+13%。
  换来的是一个平台上被千万台设备跑过的密码实现。
- **不可用的机型**：没有 NEON 或 so 加载失败时回落 BouncyCastle，功能不受影响，只是慢回改动前。
- **x86 模拟器**：`abiFilters` 已裁掉 x86，模拟器上没有 so，走 BouncyCastle 兜底。

## 五、审查：E01 会不会回退到 BouncyCastle

**结论：静态证据支持「不会」，但不能替代真机验证。**

对 `conscrypt-android:2.5.2` 的 AAR 直接反编译与解析的结果：

| 检查项 | 结果 |
|---|---|
| provider 是否注册了这个算法 | `OpenSSLProvider` 里 `put("ChaCha20/Poly1305/NoPadding", "OpenSSLAeadCipherChaCha20")`，另有别名 `Alg.Alias.Cipher.ChaCha20-Poly1305`；与 `TRANSFORMATIONS` 的两个名字完全对上 |
| so 的运行时依赖 | armv7 / arm64 均只有 `liblog.so`、`libm.so`、`libdl.so`、`libc.so`，没有 `libc++_shared.so` |
| so 的最低 API 标记 | armv7 = 16、arm64 = 21，E01 是 22 |
| AAR manifest | minSdk 19 |
| 混淆 | AAR 自带 `-keep class org.conscrypt.** { *; }`（当前 release 未开 minify） |

唯一不能静态证明的是真机上 `dlopen` 是否成功。出现回退时首帧日志会打 `aead=bouncycastle`，
这是唯一的现场判据。

## 六、审查发现并已修的问题

1. **熔断判据过宽。** 原来任何 `GeneralSecurityException` 都永久 `jceFailed = true`，包括
   `ShortBufferException`、`IllegalBlockSizeException` 这种「这一帧形状不对」的数据问题。一个畸形帧
   就会让这条流剩下的所有帧都退到 BouncyCastle。现在只对四个配置类异常熔断：
   `NoSuchAlgorithmException`、`NoSuchPaddingException`、`InvalidKeyException`、
   `InvalidAlgorithmParameterException`；其余上抛，交给流原有的失败处理。
2. **熔断不可见。** `path` 只在首帧打印。加了 `onFallback` 回调，`ScreenStream` / `AudioStream`
   接到 `onDiagnostic`，回退时打一条 `video/audio aead fell back to BouncyCastle`。

## 七、发现但未改（记录）

- **首次加载成本**：`Conscrypt.newProvider()` 第一次调用要解压并重定位约 2 MB 的 so，估计 30–80 ms，
  发生在 `CarPlayMediaEngine.onScreen` 的 RTSP 线程，不影响主线程，但会推迟首帧。
- **宿主测试证明不了设备行为**：`:shared` 单测加载的是 `conscrypt-openjdk-uber 2.6.2`（Gradle 传递
  依赖），不是 2.5.2 的 Android so。它能证明 JCE 调用形状正确，不能证明设备上 so 能加载。
- **体积口径**：+1.39 MB 的前提是 APK 内 so 用 Deflate 存储（已核对 `dist/` 里现有 so 均为 `Defl:N`）。
  如果改用 `useLegacyPackaging=false`（STORED），增量会变成 armv7 1.22 + arm64 2.00 = 3.22 MB。
- **`path` 只是首帧快照**，熔断后的值不会自动重打（现在靠新增的回退诊断行）。

## 八、兼容性核对

| 调用点 | 保护 | 结论 |
|---|---|---|
| `ScreenStream` 视频帧 | `bodyLength >= TAG_SIZE(16)` 才解密，否则原样拷贝 | 不会给 Conscrypt 送短输入 |
| `AudioStream` 音频包 | `length >= RTP_HEADER_LEN + TAIL_LEN(24)`，`sealedLength = length - 8 - RTP_HEADER_LEN >= 16` | 同上 |
| 输出缓冲 | 视频 `ByteArray(bodyLength - 16)`、音频 `DATAGRAM_BYTES` | 不会 `ShortBufferException` |
| `key` 生命周期 | `dataStreamKey` 每次 `hkdfSha512` 返回新数组，每个流新建 `AirPlayAead` | `SecretKeySpec` 缓存不会跨流复用旧 key |
| 异常类型 | BC 抛 `InvalidCipherTextException`，JCE 抛 `AEADBadTagException`；两处调用方都 catch `Exception` | 行为一致，无按类型分支 |
| nonce 长度 | 两处都是 12 字节 | Conscrypt 的 AEAD 要求 12 |
