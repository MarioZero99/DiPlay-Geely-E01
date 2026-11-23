# Fix Cluster Fly-Screen Virtual IP Binding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable Geely Xingrui E01 instrument cluster fly-screen TCP connection by allowing `tbox0` as an eligible vehicle network interface in `FlyNet.kt`, ensuring `10.255.255.101/24` is bound to `tbox0` when `eth0` is absent.

**Architecture:** 
1. In `FlyNet.kt`, remove `"tbox"` from `EXCLUDED` list while preserving cellular (`ccmni`, `rmnet`), Wi-Fi (`wlan`, `ap`, `p2p`), loopback (`lo`), and tunnel/dummy filters.
2. In `FlyNet.aliasTarget()`, establish deterministic interface selection: prefer `eth*` if present, fall back to `tbox*` if present, or single remaining eligible interface.
3. Update unit tests in `FlyClusterSocketTest.kt` to mirror the actual vehicle topology (`ap0`, `lo`, `tbox0`, `wlan0`, `ccmni*`, `ip6tnl0`, `sit0`, `ifb*`, `tunl0`).
4. Rebuild local release APK and verify diagnostics output.

**Tech Stack:** Kotlin, Android Gradle Plugin, JUnit 4, Linux `/system/bin/busybox ip`.

**Spec:**
- `/Users/g/Documents/project/xr/飞屏.md` (Section II.3 & Section III)
- Car log evidence: `DiPlay-20261111-180654-808.txt` (`fly.net skip none` caused by `tbox` exclusion)

## Global Constraints
- Target platform: Alps E01 (MT6735, Android 5.1 Lollipop API 22, GKUI Neusoft).
- Shell commands executed through `RootShellBridge` must remain under 220 bytes.
- Do not add virtual IPs to cellular (`ccmni`, `rmnet`) or phone Wi-Fi (`wlan`, `ap`).
- Preserve all existing comments, docstrings, and diagnostic log formats.

---

### Task 1: Update `FlyNet.kt` and Unit Tests for `tbox` Interface Support

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/instrument/fly/FlyNet.kt:15-69`
- Modify: `common/src/test/java/com/shilapi/xcertplay/instrument/fly/FlyClusterSocketTest.kt:28-53`

**Interfaces:**
- Consumes: `Nic(val name: String, val up: Boolean, val addresses: List<String>)`
- Produces: `FlyNet.aliasTarget(nics: List<Nic>): String?`, `FlyNet.skipLine(nics: List<Nic>): String`

- [ ] **Step 1: Write failing unit test in `FlyClusterSocketTest.kt` matching the car's interface inventory**

Add test case in `FlyClusterSocketTest.kt`:
```kotlin
    @Test
    fun `tbox0 is chosen when eth0 is absent but tbox0 is up`() {
        val carNics = listOf(
            Nic("lo", true, listOf("127.0.0.1")),
            Nic("ap0", true, listOf("192.168.43.1")),
            Nic("wlan0", false, emptyList()),
            Nic("ccmni0", false, emptyList()),
            Nic("sit0", false, emptyList()),
            Nic("ip6tnl0", false, emptyList()),
            Nic("ifb0", false, emptyList()),
            Nic("tbox0", true, listOf("192.168.225.1")),
        )
        assertEquals("tbox0", FlyNet.aliasTarget(carNics))
    }

    @Test
    fun `eth0 takes precedence over tbox0 if both are present and up`() {
        val both = listOf(
            Nic("lo", true, listOf("127.0.0.1")),
            Nic("eth0", true, emptyList()),
            Nic("tbox0", true, listOf("192.168.225.1")),
        )
        assertEquals("eth0", FlyNet.aliasTarget(both))
    }
```

- [ ] **Step 2: Run the test to confirm it fails**

Run:
```bash
./gradlew :common:testDebugUnitTest --tests "com.shilapi.xcertplay.instrument.fly.FlyClusterSocketTest"
```
Expected: Fails because `"tbox"` is currently in `EXCLUDED`, returning `null` instead of `"tbox0"`.

- [ ] **Step 3: Modify `FlyNet.kt` to allow `tbox` and prioritize `eth*` over `tbox*`**

In `common/src/main/java/com/shilapi/xcertplay/instrument/fly/FlyNet.kt`:
1. Update `aliasTarget`:
```kotlin
    fun aliasTarget(nics: List<Nic>): String? {
        val up = eligible(nics)
        val eth = up.filter { it.name.startsWith("eth", ignoreCase = true) }
        if (eth.size == 1) return eth[0].name
        if (eth.size > 1) return null

        val tbox = up.filter { it.name.startsWith("tbox", ignoreCase = true) }
        if (tbox.size == 1) return tbox[0].name
        if (tbox.size > 1) return null

        return up.singleOrNull()?.name
    }
```
2. Remove `"tbox"` from `EXCLUDED`, and include tunnel types `tunl` and `ip6tnl`:
```kotlin
    private val EXCLUDED = listOf("ap", "wlan", "p2p", "dummy", "rmnet", "ccmni", "sit", "ifb", "tunl", "ip6tnl")
```

- [ ] **Step 4: Run unit tests to verify they pass**

Run:
```bash
./gradlew :common:testDebugUnitTest --tests "com.shilapi.xcertplay.instrument.fly.FlyClusterSocketTest"
```
Expected: All tests pass.

- [ ] **Step 5: Commit the change**

```bash
git add common/src/main/java/com/shilapi/xcertplay/instrument/fly/FlyNet.kt common/src/test/java/com/shilapi/xcertplay/instrument/fly/FlyClusterSocketTest.kt
git commit -m "fix(fly): allow tbox0 vehicle interface for cluster virtual IP"
```

---

### Task 2: Build Verification and Artifact Packaging

**Files:**
- Output: `dist/DiPlay-Legacy-Geely-V050-local.apk`

- [ ] **Step 1: Run complete unit test suite for instrument fly module**

```bash
./gradlew :common:testDebugUnitTest --tests "com.shilapi.xcertplay.instrument.fly.*"
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Package release APK**

```bash
./scripts/package-geely.sh
```
or
```bash
./gradlew assembleRelease
```
Expected: New APK generated in `dist/` or `automotive/build/outputs/apk/release/`.

- [ ] **Step 3: Verify APK size and commit info**

Check generated APK timestamp and verify it is ready for deployment.
