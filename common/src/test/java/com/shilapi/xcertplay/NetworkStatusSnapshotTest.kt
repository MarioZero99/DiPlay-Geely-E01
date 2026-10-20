package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The home page's two-second radio cache. A name-only refresh used to move the clock and leave
 * hotspot and Bluetooth unread, so the connect button trusted that empty picture until the window
 * expired.
 */
class NetworkStatusSnapshotTest {
    @Test fun aNameReadFillsHotspotAndBluetoothInTheSameSnapshot() {
        var hotspotOn: Boolean? = false
        var bluetoothOn = true
        var reads = 0
        var now = 5_000L
        val snapshot = NetworkStatusSnapshot(ttlMillis = 2_000, now = { now }) {
            reads++
            NetworkStatus("car", hotspotOn, hotspotCanBeReleased = true, carBluetoothOn = bluetoothOn)
        }

        val first = snapshot.get()

        assertEquals("car", first.joinedWifiName)
        assertEquals(false, first.hotspotOn)
        assertTrue(first.carBluetoothOn)
        assertEquals(1, reads)

        hotspotOn = true
        bluetoothOn = false
        val second = snapshot.get()

        assertEquals(false, second.hotspotOn)
        assertEquals(true, second.carBluetoothOn)
        assertEquals(1, reads)
    }

    @Test fun theWindowExpiresAndInvalidateBothReadAgain() {
        var reads = 0
        var now = 5_000L
        val snapshot = NetworkStatusSnapshot(ttlMillis = 2_000, now = { now }) {
            reads++
            reads
        }

        assertEquals(1, snapshot.get())
        now += 1_999
        assertEquals(1, snapshot.get())
        now += 1
        assertEquals(2, snapshot.get())
        snapshot.invalidate()
        assertEquals(3, snapshot.get())
    }
}
