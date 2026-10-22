package com.shilapi.governance.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class E01PrivilegedBridgeTest {

    @Test
    fun wrap_synchronousCommand_appendsExitCodeMarker() {
        val wrapped = E01PrivilegedBridge.wrap("setprop ctl.stop run_natp")
        assertTrue(wrapped.startsWith("{ setprop ctl.stop run_natp; } 2>&1; rc=\$?; echo "))
        assertTrue(wrapped.contains(E01PrivilegedBridge.RC_MARKER))
    }

    @Test
    fun wrap_backgroundCommand_preservesAmpersand() {
        val wrapped = E01PrivilegedBridge.wrap("some_command &")
        assertTrue(wrapped.startsWith("some_command & rc=\$?; echo "))
        assertTrue(wrapped.contains(E01PrivilegedBridge.RC_MARKER))
    }

    @Test
    fun classify_zeroExitCode_returnsSuccessWithTrimmedOutput() {
        val output = "Process output line\n${E01PrivilegedBridge.RC_MARKER}0\n"
        val result = E01PrivilegedBridge.classify(output)
        assertTrue(result is E01PrivilegedBridge.Result.Success)
        assertEquals("Process output line", (result as E01PrivilegedBridge.Result.Success).output)
    }

    @Test
    fun classify_nonZeroExitCode_returnsCommandFailed() {
        val output = "Permission denied\n${E01PrivilegedBridge.RC_MARKER}1\n"
        val result = E01PrivilegedBridge.classify(output)
        assertTrue(result is E01PrivilegedBridge.Result.CommandFailed)
        val failure = result as E01PrivilegedBridge.Result.CommandFailed
        assertEquals(1, failure.exitCode)
        assertEquals("Permission denied", failure.output)
    }

    @Test
    fun classify_missingMarker_returnsMalformedReply() {
        val output = "Unexpected raw output without marker"
        val result = E01PrivilegedBridge.classify(output)
        assertTrue(result is E01PrivilegedBridge.Result.MalformedReply)
    }

    @Test(expected = IllegalArgumentException::class)
    fun execute_multilineCommand_throwsIllegalArgumentException() {
        E01PrivilegedBridge.execute("line1\nline2", binder = null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun execute_oversizedCommand_throwsIllegalArgumentException() {
        val longCommand = "a".repeat(220)
        E01PrivilegedBridge.execute(longCommand, binder = null)
    }

    @Test
    fun isAvailable_returnsFalseWhenServiceNotFound() {
        // In local unit test JVM, ServiceManager is not present or returns null
        assertFalse(E01PrivilegedBridge.isAvailable())
    }
}
