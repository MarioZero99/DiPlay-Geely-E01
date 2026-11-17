package com.shilapi.xcertplay.instrument.fly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.BindException

/**
 * The car log has to say why 1028 did not open: which address is on which NIC, and who already
 * holds the port. These tests pin that text. A later change that hides the holder, or that adds
 * the cluster address onto Wi-Fi, fails here.
 */
class FlyClusterSocketTest {

    @Test
    fun `the nic line names every interface and a missing address is a dash`() {
        val line = FlyNet.line(
            listOf(
                Nic("lo", true, listOf("127.0.0.1")),
                Nic("eth0", true, emptyList()),
                Nic("ap0", true, listOf("192.168.43.1")),
            ),
        )
        assertEquals("fly.net lo=127.0.0.1 eth0=- ap0=192.168.43.1", line)
    }

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
        assertEquals(
            "tbox0",
            FlyNet.aliasTarget(listOf(Nic("tbox0", true, emptyList()), Nic("usb0", true, emptyList()))),
        )
        assertNull(FlyNet.aliasTarget(listOf(Nic("tunl0", true, emptyList()))))
        assertNull(FlyNet.aliasTarget(listOf(Nic("ip6tnl0", true, emptyList()))))
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

    @Test
    fun `a down interface is not given the cluster address`() {
        val nics = listOf(Nic("eth0", false, emptyList()))
        assertNull(FlyNet.aliasTarget(nics))
        assertEquals("fly.net skip none", FlyNet.skipLine(nics))
    }

    @Test
    fun `prepare adds the address once and release removes it`() {
        val commands = mutableListOf<String>()
        val address = FlyClusterAddress(
            shell = { command ->
                commands += command
                0 to ""
            },
            interfaces = {
                listOf(
                    Nic("lo", true, listOf("127.0.0.1")),
                    Nic("eth0", true, emptyList()),
                )
            },
        )

        val lines = address.prepare()

        assertTrue(lines.contains("fly.net lo=127.0.0.1 eth0=-"))
        assertTrue(lines.contains("fly.net add eth0"))
        assertEquals("/system/bin/busybox ip addr add 10.255.255.101/24 dev eth0", commands.single())

        address.release()
        assertEquals("/system/bin/busybox ip addr del 10.255.255.101/24 dev eth0", commands[1])
    }

    @Test
    fun `prepare does not add an address that is already on a nic`() {
        val commands = mutableListOf<String>()
        val address = FlyClusterAddress(
            shell = { command ->
                commands += command
                0 to ""
            },
            interfaces = { listOf(Nic("eth0", true, listOf("10.255.255.101"))) },
        )

        val lines = address.prepare()

        assertTrue(commands.isEmpty())
        assertTrue(lines.none { it.contains(" add ") })
    }

    @Test
    fun `the root commands fit the extraservice limit`() {
        val add = FlyClusterAddress.addCommand("eth0")
        val inode = FlyPortHolder.inodeCommand(1_234_567_890_123L)
        assertTrue(rootWrapped(add).toByteArray(Charsets.UTF_8).size <= 220)
        assertTrue(rootWrapped(inode).toByteArray(Charsets.UTF_8).size <= 220)
    }

    @Test
    fun `netstat names the process listening on 1028 and ignores other ports`() {
        val text = """
            tcp  0  0 0.0.0.0:5555  0.0.0.0:*  LISTEN  200/adbd
            tcp  0  0 0.0.0.0:1028  0.0.0.0:*  LISTEN  2165/com.autonavi.amapauto
            tcp  0  0 10.255.255.101:10280  0.0.0.0:*  LISTEN  9/other
        """.trimIndent()

        val holders = FlyPortHolder.parseNetstat(text, 1028)

        assertEquals(1, holders.size)
        assertEquals(2165, holders[0].pid)
        assertEquals("com.autonavi.amapauto", holders[0].comm)
        assertEquals("0.0.0.0:1028", holders[0].local)
    }

    @Test
    fun `proc net tcp finds a listen inode and decodes the little endian address`() {
        val text = """
              sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
               0: 65FFFF0A:0404 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 54321 1 00000000 100 0 0 10 0
               1: 00000000:15B3 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 99 1 00000000 100 0 0 10 0
        """.trimIndent()

        val listens = FlyPortHolder.parseProc(text, 1028)

        assertEquals(1, listens.size)
        assertEquals("10.255.255.101:1028", listens[0].local)
        assertEquals(54321L, listens[0].inode)
    }

    @Test
    fun `an address already in use is reported from netstat with the pid`() {
        val netstat = "tcp  0  0 0.0.0.0:1028  0.0.0.0:*  LISTEN  2165/com.autonavi.amapauto\n"
        val lines = FlyPortHolder.report(
            BindException("Address already in use"),
            port = 1028,
            selfPid = 100,
            shell = { command ->
                if (command.contains("netstat")) 0 to netstat else 0 to ""
            },
        )
        assertEquals(
            "fly.tcp holder pid=2165 comm=com.autonavi.amapauto local=0.0.0.0:1028",
            lines.single(),
        )
    }

    @Test
    fun `our own pid is marked self and a missing address is not a port conflict`() {
        val netstat = "tcp  0  0 127.0.0.1:1028  0.0.0.0:*  LISTEN  100/com.shihab.diplay\n"
        val self = FlyPortHolder.report(
            BindException("bind failed: EADDRINUSE (Address already in use)"),
            port = 1028,
            selfPid = 100,
            shell = { 0 to netstat },
        )
        assertEquals(
            "fly.tcp holder self pid=100 comm=com.shihab.diplay local=127.0.0.1:1028",
            self.single(),
        )

        val other = FlyPortHolder.report(
            BindException("Cannot assign requested address"),
            port = 1028,
            selfPid = 100,
            shell = { _ -> error("should not scan") },
        )
        assertTrue(other.isEmpty())
    }

    @Test
    fun `a bind failure line carries the exception and then the holder`() {
        val lines = bindFailureLines(
            BindException("Address already in use"),
            port = 1028,
        ) { _, _ -> listOf("fly.tcp holder pid=2165 comm=com.autonavi.amapauto local=0.0.0.0:1028") }

        assertEquals("fly.tcp busy BindException Address already in use", lines[0])
        assertEquals("fly.tcp holder pid=2165 comm=com.autonavi.amapauto local=0.0.0.0:1028", lines[1])
    }

    /** Same wrapper RootShellBridge puts around a command before the 220-byte check. */
    private fun rootWrapped(command: String) = "{ $command; } 2>&1; rc=\$?; echo __RC:\$rc"
}
