package com.shilapi.xcertplay.instrument.fly

import com.shilapi.xcertplay.DiagnosticRedactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlyProbeTest {

    @Test fun commandsFitTheRootBridge() {
        for (command in FlyProbe.commands + FlyTrace.COMMAND) {
            val wrapped = "{ $command; } 2>&1; rc=\$?; echo __RC:\$rc"
            assertTrue(
                "$command wraps to ${wrapped.toByteArray(Charsets.UTF_8).size} bytes",
                wrapped.toByteArray(Charsets.UTF_8).size <= 220,
            )
        }
    }

    @Test fun decodeLittleEndianProcAddress() {
        assertEquals("10-255-255-101", FlyProbe.decodeIpv4Hex("65FFFF0A"))
        assertEquals("172-30-100-10", FlyProbe.decodeIpv4Hex("0A641EAC"))
        assertEquals("0-0-0-0", FlyProbe.decodeIpv4Hex("00000000"))
    }

    @Test fun yesterdayShapeKeepsTheAddressesAndSaysEth0IsAbsent() {
        val lines = FlyProbe.lines(
            sysfs = ShellText(0, "lo\nap0\ntbox0\nwlan0\nccmni0\n"),
            addr = ShellText(0, ADDR_NO_ETH),
            neigh = ShellText(0, NEIGH),
            route = ShellText(0, ROUTE),
            tcp = ShellText(0, TCP),
            extra = EXTRA,
        )
        assertEquals(
            "fly.probe summary eth0=absent tbox0=present cluster-host=tbox0 ipk-host=none ipk-peer=seen rtp-peer=seen",
            lines.first(),
        )
        assertTrue(lines.any { it == "fly.probe net lo ap0 tbox0 wlan0 ccmni0" })
        assertTrue(lines.any { it.contains("fly.probe addr tbox0 inet 10-255-255-101/24") })
        assertTrue(lines.any { it.contains("fly.probe neigh 172-30-100-11 dev eth0 STALE") })
        assertFalse(lines.any { it.contains("aa:bb") })
        assertTrue(lines.any { it == "fly.probe tcp port=1028 st=0A host=10-255-255-101 uid=1021" })
        assertTrue(lines.any { it == "fly.probe tcp port=28887 absent" })
        assertTrue(lines.any { it.contains("setMMI_NavigationMod_indicator:[1, 1, 0]") })
        assertSurvivesRedaction(lines)
    }

    @Test fun ipkHostOnEth0AndNoClusterAddress() {
        val lines = FlyProbe.lines(
            sysfs = ShellText(0, "lo eth0 tbox0"),
            addr = ShellText(
                0,
                """
                2: eth0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 state UP
                    inet 172.30.100.10/24 brd 172.30.100.255 scope global eth0
                3: tbox0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 state UP
                    inet 192.168.225.190/24 scope global tbox0
                """.trimIndent(),
            ),
            neigh = ShellText(0, "172.30.100.11 dev eth0 lladdr 11:22:33:44:55:66 REACHABLE"),
            route = ShellText(0, "172.30.100.0/24 dev eth0 proto kernel scope link src 172.30.100.10"),
            tcp = ShellText(1, ""),
            extra = "",
        )
        assertEquals(
            "fly.probe summary eth0=present tbox0=present cluster-host=none ipk-host=eth0 ipk-peer=seen rtp-peer=none",
            lines.first(),
        )
        assertTrue(lines.any { it.contains("172-30-100-10/24") })
        assertTrue(lines.contains("fly.probe tcp port=1028 absent"))
        assertTrue(lines.contains("fly.probe tcp port=28887 absent"))
        assertTrue(lines.contains("fly.probe extra empty"))
        assertSurvivesRedaction(lines)
    }

    @Test fun arpFallbackDropsTheMac() {
        val lines = FlyProbe.lines(
            sysfs = ShellText(0, "tbox0"),
            addr = ShellText(null, ""),
            neigh = ShellText(
                0,
                """
                IP address HW type Flags HW address Mask Device
                192.168.225.1 0x1 0x2 aa:bb:cc:dd:ee:ff * tbox0
                """.trimIndent(),
            ),
            route = ShellText(null, ""),
            tcp = ShellText(null, "grep: not found"),
            extra = "",
        )
        assertTrue(lines.first().contains("cluster-host=unknown"))
        assertTrue(lines.any { it == "fly.probe neigh 192-168-225-1 flags=0x2 dev tbox0" })
        assertTrue(lines.contains("fly.probe tcp failed rc=null"))
        assertSurvivesRedaction(lines)
    }

    private fun assertSurvivesRedaction(lines: List<String>) {
        for (line in lines) {
            val redacted = DiagnosticRedactor.redact(line)
            assertEquals(line, redacted)
            assertFalse(line, line.contains("[ip]"))
            assertFalse(line, dotted.containsMatchIn(line))
        }
    }

    private companion object {
        val dotted = Regex("(?<![0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9])")

        val ADDR_NO_ETH = """
            1: lo: <LOOPBACK,UP,LOWER_UP> mtu 65536 state UNKNOWN
                link/loopback 00:00:00:00:00:00 brd 00:00:00:00:00:00
                inet 127.0.0.1/8 scope host lo
            5: tbox0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 state UP
                link/ether 02:00:00:00:00:01 brd ff:ff:ff:ff:ff:ff
                inet 192.168.225.190/24 brd 192.168.225.255 scope global tbox0
                inet 10.255.255.101/24 scope global tbox0
            6: ap0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 state UP
                inet 192.168.43.1/24 scope global ap0
        """.trimIndent()

        const val NEIGH = """
            192.168.225.1 dev tbox0 lladdr aa:bb:cc:dd:ee:ff REACHABLE
            172.30.100.11 dev eth0 lladdr 11:22:33:44:55:66 STALE
            10.255.255.106 dev tbox0 FAILED
        """

        const val ROUTE = """
            default via 192.168.225.1 dev tbox0
            10.255.255.0/24 dev tbox0 proto kernel scope link src 10.255.255.101
        """

        const val TCP =
            "/proc/net/tcp:  0: 65FFFF0A:0404 00000000:0000 0A 00000000:00000000 00:00000000 00000000 1021 0 495 1 00000000 100 0 0 10 0"

        const val EXTRA = """
            11-18 17:37:53.200  1000  1234 D Extra:  @neunavi@  导航状态
            11-18 17:37:53.201  1000  1234 D Extra:  @neunavi@  setMMI_NavigationMod_indicator:[1, 1, 0]
            11-18 17:37:53.202  1000  1234 D Extra:  @neunavi@  road name chatter
        """
    }
}
