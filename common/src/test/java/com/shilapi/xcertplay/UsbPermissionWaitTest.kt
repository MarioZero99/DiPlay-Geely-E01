package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbPermissionWaitTest {
    @Test fun grantWithoutABroadcastFinishesOnTheNextPoll() {
        var allowed = false
        val outcomes = mutableListOf<Boolean>()
        val wait = UsbPermissionWait(
            hasPermission = { allowed },
            onResult = { outcomes += it },
            waitMillis = 1_000L,
            stepMillis = 250L,
        )
        val tasks = ArrayDeque<() -> Unit>()
        wait.start { _, block -> tasks.add(block) }
        tasks.removeFirst().invoke()
        assertTrue(outcomes.isEmpty())
        allowed = true
        tasks.removeFirst().invoke()
        assertEquals(listOf(true), outcomes)
        assertTrue(tasks.isEmpty())
    }

    @Test fun timeoutDeliversFalseOnce() {
        val outcomes = mutableListOf<Boolean>()
        val wait = UsbPermissionWait(
            hasPermission = { false },
            onResult = { outcomes += it },
            waitMillis = 500L,
            stepMillis = 250L,
        )
        val tasks = ArrayDeque<() -> Unit>()
        wait.start { _, block -> tasks.add(block) }
        tasks.removeFirst().invoke()
        assertTrue(outcomes.isEmpty())
        tasks.removeFirst().invoke()
        assertEquals(listOf(false), outcomes)
        assertTrue(tasks.isEmpty())
    }

    @Test fun broadcastWithANullDeviceNameCounts() {
        val outcomes = mutableListOf<Boolean>()
        val wait = UsbPermissionWait(
            hasPermission = { false },
            onResult = { outcomes += it },
            waitMillis = 1_000L,
            stepMillis = 250L,
        )
        wait.onBroadcast(granted = true, reportedDeviceName = null, targetDeviceName = "/dev/bus/usb/001/005")
        assertEquals(listOf(true), outcomes)
    }

    @Test fun aMismatchedDeviceNameIsIgnored() {
        val outcomes = mutableListOf<Boolean>()
        val wait = UsbPermissionWait(
            hasPermission = { false },
            onResult = { outcomes += it },
            waitMillis = 1_000L,
            stepMillis = 250L,
        )
        wait.onBroadcast(granted = true, reportedDeviceName = "/dev/bus/usb/001/004", targetDeviceName = "/dev/bus/usb/001/005")
        assertTrue(outcomes.isEmpty())
    }

    @Test fun aGrantAfterTimeoutStillReportsTrue() {
        val outcomes = mutableListOf<Boolean>()
        val wait = UsbPermissionWait(
            hasPermission = { false },
            onResult = { outcomes += it },
            waitMillis = 250L,
            stepMillis = 250L,
        )
        val tasks = ArrayDeque<() -> Unit>()
        wait.start { _, block -> tasks.add(block) }
        tasks.removeFirst().invoke()
        assertEquals(listOf(false), outcomes)
        wait.onBroadcast(granted = true, reportedDeviceName = null, targetDeviceName = "usb")
        assertEquals(listOf(false, true), outcomes)
    }

    @Test fun acceptsPermissionBroadcastTreatsAMissingDeviceAsThisRequest() {
        assertTrue(acceptsPermissionBroadcast("usb", null))
        assertTrue(acceptsPermissionBroadcast("usb", "usb"))
        assertFalse(acceptsPermissionBroadcast("usb", "other"))
    }
}
