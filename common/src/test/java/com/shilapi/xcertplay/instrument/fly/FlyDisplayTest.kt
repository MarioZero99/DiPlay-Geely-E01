package com.shilapi.xcertplay.instrument.fly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FlyDisplayTest {
    private val center = FlyDisplayInfo(0, "Built-in Screen", 1920, 720, defaultDisplay = true)
    private val cluster = FlyDisplayInfo(2, "HDMI Screen", 1920, 720, defaultDisplay = false)
    private val other = FlyDisplayInfo(5, "virt", 800, 480, defaultDisplay = false)

    @Test fun theCenterScreenIsNeverTheCluster() {
        assertNull(chooseClusterDisplay(listOf(center)))
    }

    @Test fun a1920x720DisplayThatIsNotTheCenterWins() {
        assertEquals(cluster, chooseClusterDisplay(listOf(center, other, cluster)))
    }

    @Test fun anyOtherDisplayIsUsedWhenNoneIs1920x720() {
        assertEquals(other, chooseClusterDisplay(listOf(center, other)))
    }

    @Test fun linesNameTheChoiceAndDoNotContainADottedAddress() {
        val lines = displayLines(listOf(center, cluster), cluster)
        assertEquals("fly.display list 0:Built-in Screen:1920x720:default 2:HDMI Screen:1920x720", lines[0])
        assertEquals("fly.display use id=2 name=HDMI Screen 1920x720", lines[1])
        assertEquals("fly.display none", displayLines(listOf(center), null).last())
    }
}
