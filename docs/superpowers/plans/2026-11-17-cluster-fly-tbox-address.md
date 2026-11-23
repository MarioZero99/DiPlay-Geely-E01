# Cluster Fly-Screen Address On tbox0 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On the Geely Xingrui E01, add `10.255.255.101/24` to `tbox0` for the fly session so the cluster's SYN to that address reaches the socket DiPlay already listens on.

**Architecture:** `ClusterFlyLink.start` already calls `FlyClusterAddress.prepare` before `ServerSocket` binds `0.0.0.0:1028`. `prepare` adds the address only when `FlyNet.aliasTarget` returns one interface. Today `tbox` is excluded, this car has no `eth0`, and the log is `fly.net skip none`. Remove that exclusion and choose one up interface in a fixed order: a single `eth*`, otherwise a single `tbox*`, otherwise the single remaining eligible NIC. The command sequence, the 1887 write, the three-finger broadcast, and the Baseline re-encoder stay as they are.

**Tech Stack:** Kotlin, JUnit 4, Android Gradle Plugin, `/system/bin/busybox ip` through `RootShellBridge` (220-byte limit). API 22.

**Spec:** Requirements in this file. Factory socket behavior is in `/Users/g/Documents/project/xr/飞屏.md`. This plan supersedes `docs/superpowers/plans/2026-11-12-cluster-fly-net-tbox-fix.md`. Do not execute that file.

## Global Constraints

- Head unit: 吉利星瑞 E01, alps, MT6735, Android 5.1 API 22, GKUI, 东软. Do not raise the API level.
- Do not modify `/system`, `Navi.apk`, `XCNaviService`, or `Extraservice`. Do not launch 高德. Do not send `ecarx.intent.action.MAP_PRIOR`.
- Do not change `ClusterFlySequencer` timings, `ClusterFlyMcu` bytes, `ClusterFlyLink` bind address, or the re-encoder. Opening stays `NAV_ON`, then `NAV_FRONT` at +400 ms (1887 `[67, 1]`), then `FLY_LEFT` at +1400 ms (1885 from `ThreeFingerReceiver`). Closing stays `FLY_RIGHT`, `NAV_OFF` at +400 ms, `NAV_BACK` at +1000 ms.
- Cluster dials `10.255.255.101` TCP `1028`. RTP goes to `10.255.255.106` UDP `9026`. Listen stays `0.0.0.0:1028`. The address is added before that listen and removed in `release`.
- Never put the address on `ap`, `wlan`, `p2p`, `rmnet`, `ccmni`, `lo`, tunnels, or dummy interfaces. A root command, after `RootShellBridge`'s wrapper, is at most 220 bytes. Do not write the address into `/system`.
- `1885` and `1887` stay at least 1000 ms apart. That gap is already `ClusterFlySequencer.MCU_GAP_MILLIS`.

## Requirements

1. The 2026-11-17 car log (`DiPlay-20261117-103309-593`) shows `fly.net skip none` with `tbox0` up and no `eth0`. `fly.tcp listen` follows, and there is no `fly.tcp accept`. The same `skip none` is in the 2026-11-11 log, where `ThreeFingerReceiver` did log `setInfo = [74, 1, ...]`.
2. A wildcard listen does not receive a SYN addressed to `10.255.255.101` until that address is local.
3. On this car the interface that faces the cluster is `tbox0`. When exactly one `eth*` is up, use that instead. When two `eth*` or two `tbox*` are up, add nothing.
4. Phone hotspot (`ap*`, `wlan*`), cellular (`rmnet*`, `ccmni*`), and tunnels (`sit*`, `tunl*`, `ip6tnl*`, `ifb*`, `dummy*`, `p2p*`) are never chosen.
5. Success on the car, later, is `fly.net add tbox0`, then `ThreeFingerReceiver setInfo = [74, 1, ...]`, then `fly.tcp accept`. This plan does not install the APK and does not claim that log.

## File structure

- Modify `common/src/main/java/com/shilapi/xcertplay/instrument/fly/FlyNet.kt`. Owns which NIC may carry the address.
- Modify `common/src/main/java/com/shilapi/xcertplay/instrument/fly/FlyClusterAddress.kt`. Comment only. The add and delete commands stay.
- Modify `common/src/test/java/com/shilapi/xcertplay/instrument/fly/FlyClusterSocketTest.kt`. Pins the choice.

No new files. No sequencer, MCU, or link edits.

---

### Task 1: Choose tbox0 when the car has no ethernet

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/instrument/fly/FlyNet.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/instrument/fly/FlyClusterAddress.kt` (class KDoc only)
- Test: `common/src/test/java/com/shilapi/xcertplay/instrument/fly/FlyClusterSocketTest.kt`

**Interfaces:**
- Consumes: `Nic(val name: String, val up: Boolean, val addresses: List<String>)`
- Produces: `FlyNet.aliasTarget(nics: List<Nic>): String?`. `FlyClusterAddress.prepare` already calls it and, on one name, runs `addCommand`. Later code must keep that signature.

- [ ] **Step 1: Replace the NIC-choice tests so the car topology fails**

In `FlyClusterSocketTest.kt`, replace the test named `the cluster address is added only on the one up interface that is not the phone or the tbox` with these four tests. Leave the other tests in the class unchanged.

```kotlin
    @Test
    fun `this car adds the address on tbox0`() {
        val car = listOf(
            Nic("ip6tnl0", true, emptyList()),
            Nic("sit0", true, emptyList()),
            Nic("ifb0", true, emptyList()),
            Nic("ifb1", true, emptyList()),
            Nic("ap0", true, listOf("192.168.43.1")),
            Nic("lo", true, listOf("127.0.0.1")),
            Nic("tbox0", true, listOf("192.168.225.1")),
            Nic("wlan0", false, emptyList()),
            Nic("tunl0", true, emptyList()),
            Nic("ccmni2", false, emptyList()),
            Nic("ccmni0", false, emptyList()),
            Nic("ccmni1", false, emptyList()),
        )
        assertEquals("tbox0", FlyNet.aliasTarget(car))
        assertEquals("fly.net skip none", FlyNet.skipLine(listOf(Nic("ap0", true, listOf("192.168.43.1")))))
    }

    @Test
    fun `one ethernet interface wins over tbox`() {
        val both = listOf(
            Nic("eth0", true, emptyList()),
            Nic("tbox0", true, listOf("192.168.225.1")),
            Nic("usb0", true, emptyList()),
        )
        assertEquals("eth0", FlyNet.aliasTarget(both))
    }

    @Test
    fun `two ethernet interfaces and two tbox interfaces are not guessed`() {
        assertNull(
            FlyNet.aliasTarget(
                listOf(Nic("eth0", true, emptyList()), Nic("eth1", true, emptyList())),
            ),
        )
        assertEquals(
            "fly.net skip eth0,eth1",
            FlyNet.skipLine(listOf(Nic("eth0", true, emptyList()), Nic("eth1", true, emptyList()))),
        )
        assertNull(
            FlyNet.aliasTarget(
                listOf(Nic("tbox0", true, emptyList()), Nic("tbox1", true, emptyList())),
            ),
        )
    }

    @Test
    fun `prepare on this car adds and removes the address on tbox0`() {
        val commands = mutableListOf<String>()
        val address = FlyClusterAddress(
            shell = { command ->
                commands += command
                0 to ""
            },
            interfaces = {
                listOf(
                    Nic("ap0", true, listOf("192.168.43.1")),
                    Nic("lo", true, listOf("127.0.0.1")),
                    Nic("tbox0", true, listOf("192.168.225.1")),
                    Nic("wlan0", false, emptyList()),
                )
            },
        )

        val lines = address.prepare()

        assertTrue(lines.contains("fly.net add tbox0"))
        assertEquals("/system/bin/busybox ip addr add 10.255.255.101/24 dev tbox0", commands.single())
        assertTrue(rootWrapped(commands.single()).toByteArray(Charsets.UTF_8).size <= 220)

        address.release()
        assertEquals("/system/bin/busybox ip addr del 10.255.255.101/24 dev tbox0", commands[1])
    }
```

`rootWrapped` is already a private function at the bottom of this test class. Do not add a second copy.

- [ ] **Step 2: Run the test and confirm the car case fails**

Run from `DiPlay-Legacy-Android-Geely`:

```bash
./gradlew :common:testDebugUnitTest --tests "com.shilapi.xcertplay.instrument.fly.FlyClusterSocketTest"
```

Expected: FAIL. `this car adds the address on tbox0` returns null because `tbox` is excluded. `one ethernet interface wins over tbox` returns null because `eth0` and `usb0` are both eligible. `prepare on this car adds and removes the address on tbox0` does not run the add command. The two-interface tests already return null and can pass before the code change.

- [ ] **Step 3: Choose the interface and correct the comments**

Replace `aliasTarget`, `excluded`'s list, and the file KDoc in `FlyNet.kt`. Keep `line`, `has`, `skipLine`, `local`, and `eligible` as they are. `skipLine` must keep listing every eligible NIC when `aliasTarget` returns null.

```kotlin
/**
 * Which interface may carry 10.255.255.101.
 *
 * The cluster dials that address. On this head unit it is reached through tbox0.
 * An ethernet NIC is used when exactly one is up. The phone hotspot, Wi-Fi,
 * cellular data, and tunnels are never chosen.
 */
object FlyNet {
```

```kotlin
    /**
     * The interface that receives 10.255.255.101, or null when the choice is ambiguous.
     * One up eth* wins. Otherwise one up tbox*. Otherwise the only remaining eligible NIC.
     */
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

```kotlin
    private val EXCLUDED = listOf(
        "ap", "wlan", "p2p", "dummy", "rmnet", "ccmni", "sit", "ifb", "tunl", "ip6tnl",
    )
```

`lo` stays excluded inside `excluded()`. Do not add `"tbox"` back to this list.

Replace the class KDoc in `FlyClusterAddress.kt` with:

```kotlin
/**
 * Makes 10.255.255.101 exist for as long as the fly link is open.
 *
 * The cluster dials that address. Listening on every interface still receives
 * nothing until the address is local. [FlyNet.aliasTarget] picks the interface.
 * The address is removed when the link closes. It is never written into /system.
 */
```

Do not change `addCommand`, `delCommand`, `prepare`, or `release`.

- [ ] **Step 4: Run the fly unit tests**

```bash
./gradlew :common:testDebugUnitTest --tests "com.shilapi.xcertplay.instrument.fly.*"
```

Expected: BUILD SUCCESSFUL. `FlyClusterSocketTest` includes the four tests from Step 1, plus the existing prepare, port-holder, and command-length tests.

- [ ] **Step 5: Commit**

Run these from `DiPlay-Legacy-Android-Geely`:

```bash
git add common/src/main/java/com/shilapi/xcertplay/instrument/fly/FlyNet.kt \
        common/src/main/java/com/shilapi/xcertplay/instrument/fly/FlyClusterAddress.kt \
        common/src/test/java/com/shilapi/xcertplay/instrument/fly/FlyClusterSocketTest.kt
git commit -m "$(cat <<'EOF'
fix(fly): add the cluster address on tbox0

This head unit has no eth0. The dial to 10.255.255.101 was dropped
because tbox was excluded and the address was never local.
EOF
)"
```

---

## On the car, after this commit is installed

This is not an agent task. Do not package or install from this plan.

1. Left swipe once from an idle fly phase.
2. The report must contain `fly.net add tbox0` and must not contain `fly.net skip none`.
3. About 1.4 s later, `logcat -d -s Extra:D` must contain `ThreeFingerReceiver` and `setInfo = [74, 1, ...]`.
4. Then `fly.tcp accept`, then `fly.ask`, then `fly.push`. The first video config on this phone is H.264 profile 100. The push that follows an accept must be Baseline (profile 66), which the existing re-encoder does.
5. A down swipe must log `fly.net` removal only as the delete command's success path already does: `release` runs `ip addr del` and does not need a new log line.
6. If `fly.net add tbox0` is present and `setInfo` is present and there is still no `fly.tcp accept`, stop. Do not add another address and do not change the sequencer. That result is a new fact about the cluster.
