package com.shilapi.xcertplay.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootShellBridgeTest {

    @Test
    fun wrapsCommandWithExitCodeMarker() {
        val wrapped = RootShellBridge.wrap("/system/bin/svc wifi enable")
        assertTrue(wrapped.contains("/system/bin/svc wifi enable"))
        assertTrue(wrapped.contains("__RC:"))
        assertTrue(wrapped.contains("rc=\$?"))
    }

    @Test
    fun rejectsUnsafeMultilineCommands() {
        assertFalse(RootShellBridge.execute("line1\nline2"))
        assertFalse(RootShellBridge.execute("line1\rline2"))
    }

    @Test
    fun classifiesOutputCorrectly() {
        assertTrue(RootShellBridge.classifyOutput("some output\n__RC:0\n"))
        assertFalse(RootShellBridge.classifyOutput("some output\n__RC:1\n"))
        assertFalse(RootShellBridge.classifyOutput("some output\n__RC:127\n"))
        assertFalse(RootShellBridge.classifyOutput("malformed without marker"))
    }

    @Test
    fun returnsFalseWhenServiceUnavailable() {
        // In unit test environment, ExtraUtilsService is not present
        assertFalse(RootShellBridge.execute("/system/bin/id"))
    }

    @Test
    fun hotspotCommandFitsTheBinderBudget() {
        val paths = listOf(
            "/data/app/com.shihab.diplay-1/base.apk",
            "/data/app/com.shihab.diplay.hudtest-1/base.apk",
        )
        paths.forEach { path ->
            assertTrue(RootShellBridge.shellSafeApkPath(path))
            val wrapped = RootShellBridge.wrap(RootShellBridge.hotspotCommand(path, "enable"))
            assertTrue(wrapped.toByteArray(Charsets.UTF_8).size <= 220)
        }
    }

    @Test
    fun aPathWithSpacesIsNotPassedToTheShell() {
        assertFalse(RootShellBridge.shellSafeApkPath("/data/app/my app/base.apk"))
    }
}
