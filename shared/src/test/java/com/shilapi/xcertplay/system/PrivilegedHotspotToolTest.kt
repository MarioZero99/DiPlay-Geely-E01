package com.shilapi.xcertplay.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegedHotspotToolTest {
    @Test
    fun enableAndDisableAreTheOnlyActions() {
        assertEquals(true, hotspotAction(arrayOf("enable")))
        assertEquals(false, hotspotAction(arrayOf("disable")))
    }

    @Test
    fun aMissingOrUnknownArgumentDoesNotBecomeDisable() {
        assertNull(hotspotAction(emptyArray()))
        assertNull(hotspotAction(arrayOf("Enable")))
        assertNull(hotspotAction(arrayOf("")))
    }

    @Test
    fun aVoidResultOnAndroid51IsAccepted() {
        assertTrue(hotspotCallAccepted(null))
        assertTrue(hotspotCallAccepted(true))
        assertFalse(hotspotCallAccepted(false))
    }
}
