package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Test

class CompositeMediaGainTest {

    @Test
    fun `default gain is unity when no navigation and full focus`() {
        val composite = CompositeMediaGain(duckFactor = 0.2f)
        assertEquals(1.0f, composite.effectiveGain(), 0.001f)
    }

    @Test
    fun `navigation active ducks media gain to duckFactor`() {
        val composite = CompositeMediaGain(duckFactor = 0.2f)
        val ducked = composite.onStreamStarted(type = 101, isNavigation = true)
        assertEquals(0.2f, ducked, 0.001f)
        assertEquals(0.2f, composite.effectiveGain(), 0.001f)

        val restored = composite.onStreamStopped(type = 101)
        assertEquals(1.0f, restored, 0.001f)
        assertEquals(1.0f, composite.effectiveGain(), 0.001f)
    }

    @Test
    fun `overlapping navigation streams keep ducking until all finish`() {
        val composite = CompositeMediaGain(duckFactor = 0.2f)

        // Stream 1 starts
        composite.onStreamStarted(type = 101, isNavigation = true)
        assertEquals(0.2f, composite.effectiveGain(), 0.001f)

        // Stream 2 starts concurrently
        composite.onStreamStarted(type = 103, isNavigation = true)
        assertEquals(0.2f, composite.effectiveGain(), 0.001f)

        // Stream 1 ends, but Stream 2 is still active
        composite.onStreamStopped(type = 101)
        assertEquals("Must remain ducked while stream 103 is active", 0.2f, composite.effectiveGain(), 0.001f)

        // Stream 2 ends -> volume restores
        composite.onStreamStopped(type = 103)
        assertEquals(1.0f, composite.effectiveGain(), 0.001f)
    }

    @Test
    fun `non navigation stream events do not trigger ducking`() {
        val composite = CompositeMediaGain(duckFactor = 0.2f)

        composite.onStreamStarted(type = 102, isNavigation = false)
        assertEquals(1.0f, composite.effectiveGain(), 0.001f)

        composite.onStreamStopped(type = 102)
        assertEquals(1.0f, composite.effectiveGain(), 0.001f)
    }

    @Test
    fun `external focus gain of zero remains zero regardless of navigation`() {
        val composite = CompositeMediaGain(duckFactor = 0.2f)
        composite.setFocusGain(0.0f)
        assertEquals(0.0f, composite.effectiveGain(), 0.001f)

        composite.onStreamStarted(type = 101, isNavigation = true)
        assertEquals(0.0f, composite.effectiveGain(), 0.001f)

        composite.onStreamStopped(type = 101)
        assertEquals(0.0f, composite.effectiveGain(), 0.001f)
    }

    @Test
    fun `external focus 0_2 and navigation 0_2 take minOf to produce 0_2 instead of 0_04`() {
        val composite = CompositeMediaGain(duckFactor = 0.2f)
        composite.setFocusGain(0.2f)
        assertEquals(0.2f, composite.effectiveGain(), 0.001f)

        composite.onStreamStarted(type = 101, isNavigation = true)
        // Must NOT multiply to 0.04
        assertEquals(0.2f, composite.effectiveGain(), 0.001f)

        composite.onStreamStopped(type = 101)
        assertEquals(0.2f, composite.effectiveGain(), 0.001f)

        composite.setFocusGain(1.0f)
        assertEquals(1.0f, composite.effectiveGain(), 0.001f)
    }
}
