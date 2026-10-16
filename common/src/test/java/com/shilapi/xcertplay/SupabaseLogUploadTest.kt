package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupabaseLogUploadTest {
    @Test fun aReportTooBigForOneObjectIsSplitWhole() {
        val text = (1..500).joinToString("\n") { "line $it" } + "\n"
        val parts = SupabaseLogUpload.parts(text, maxBytes = 200)
        assertTrue("a report over the cap has to become more than one object", parts.size > 1)
        parts.forEach { assertTrue("every object has to fit the bucket", it.size <= 200) }
        assertEquals(text, parts.joinToString("") { it.toString(Charsets.UTF_8) })
    }

    @Test fun aLineLongerThanTheCapIsCutAtACharacterBoundary() {
        val text = "中".repeat(300) + "\n"
        val parts = SupabaseLogUpload.parts(text, maxBytes = 100)
        parts.forEach { assertTrue(it.size <= 100) }
        assertEquals(text, parts.joinToString("") { it.toString(Charsets.UTF_8) })
    }

    @Test fun theObjectNameSaysWhereThePartGoes() {
        assertEquals("DiPlay.txt", SupabaseLogUpload.partName("DiPlay.txt", 0, 1))
        assertEquals("DiPlay-p1of3.txt", SupabaseLogUpload.partName("DiPlay.txt", 0, 3))
        assertEquals("DiPlay-p3of3.txt", SupabaseLogUpload.partName("DiPlay.txt", 2, 3))
    }

    @Test fun blankOrNonHttpsConfigStaysOff() {
        assertFalse(SupabaseLogUpload.enabled("", "key"))
        assertFalse(SupabaseLogUpload.enabled("https://example.supabase.co", ""))
        assertFalse(SupabaseLogUpload.enabled("http://example.supabase.co", "key"))
        assertTrue(SupabaseLogUpload.enabled("https://example.supabase.co", "sb_publishable_x"))
    }
}
