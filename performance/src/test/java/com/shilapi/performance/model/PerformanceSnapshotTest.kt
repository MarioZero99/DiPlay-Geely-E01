package com.shilapi.performance.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PerformanceSnapshotTest {
    @Test
    fun formatsSummaryLineCorrectlyForWifi() {
        val snapshot = PerformanceSnapshot(
            cpu = CpuMetrics(usagePercent = 25, coreCount = 4, frequencyMhz = 1500),
            memory = MemoryMetrics(usedBytes = 1024L * 1024 * 1200, totalBytes = 1024L * 1024 * 2048, isLowMemory = false),
            storage = StorageMetrics(usedBytes = 1024L * 1024 * 1024 * 18, totalBytes = 1024L * 1024 * 1024 * 32),
            link = LinkMetrics(type = LinkType.WIFI_CLIENT, detail = "5 GHz · 433 Mbps (-48 dBm)"),
            media = MediaMetrics(rxFps = 30.0f, shownFps = 30.0f, touchLatencyMs = 82, droppedFrames = 0),
        )
        assertEquals("CPU: 25% (4核 @ 1.5GHz)", snapshot.cpuText)
        assertEquals("RAM: 1.2 GB / 2.0 GB (58%)", snapshot.memoryText)
        assertEquals("机身存储: 18.0 GB / 32.0 GB (56%)", snapshot.storageText)
        assertEquals("链路: Wi-Fi · 5 GHz · 433 Mbps (-48 dBm)", snapshot.linkText)
        assertEquals("FPS: 30.0 · 延迟: 82ms", snapshot.mediaText)
    }

    @Test
    fun formatsSummaryLineCorrectlyForUsb() {
        val snapshot = PerformanceSnapshot(
            cpu = CpuMetrics(usagePercent = -1, coreCount = 4, frequencyMhz = 0),
            memory = MemoryMetrics(usedBytes = 0, totalBytes = 0, isLowMemory = true),
            storage = StorageMetrics(usedBytes = 0, totalBytes = 0),
            link = LinkMetrics(type = LinkType.USB_WIRED, detail = "NCM / AOA"),
            media = null,
        )
        assertEquals("CPU: ... (4核 @ N/A)", snapshot.cpuText)
        assertEquals("RAM: N/A", snapshot.memoryText)
        assertEquals("机身存储: N/A", snapshot.storageText)
        assertEquals("链路: 有线连接 (NCM / AOA)", snapshot.linkText)
        assertEquals("投屏: 待机", snapshot.mediaText)
    }

    @Test
    fun formatsSummaryLineCorrectlyForCarHotspot() {
        val snapshot = PerformanceSnapshot(
            cpu = CpuMetrics(usagePercent = 50, coreCount = 8, frequencyMhz = 800),
            memory = MemoryMetrics(usedBytes = 1024L * 1024 * 1800, totalBytes = 1024L * 1024 * 2048, isLowMemory = true),
            storage = StorageMetrics(usedBytes = 1024L * 1024 * 1024 * 10, totalBytes = 1024L * 1024 * 1024 * 32),
            link = LinkMetrics(type = LinkType.CAR_HOTSPOT, detail = "Geely-Car · 5 GHz"),
            media = MediaMetrics(rxFps = 60.0f, shownFps = 59.8f, touchLatencyMs = -1, droppedFrames = 2),
        )
        assertEquals("CPU: 50% (8核 @ 800MHz)", snapshot.cpuText)
        assertEquals("RAM: 1.8 GB / 2.0 GB (87%) [低内存警告]", snapshot.memoryText)
        assertEquals("链路: 车机热点 · Geely-Car · 5 GHz", snapshot.linkText)
        assertEquals("FPS: 59.8", snapshot.mediaText)
    }
}
