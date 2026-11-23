package com.shilapi.xcertplay.instrument.fly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FlyMirrorLogTest {
    @Test fun linesDoNotCarryANameField() {
        val lines = listOf(FlyMirrorLog.mirror(), FlyMirrorLog.frame(), FlyMirrorLog.failed("egl"))
        assertEquals("fly.display mirror", lines[0])
        assertEquals("fly.display frame", lines[1])
        assertEquals("fly.display mirror failed egl", lines[2])
        assertFalse(lines.any { it.contains("name=") })
    }
}
