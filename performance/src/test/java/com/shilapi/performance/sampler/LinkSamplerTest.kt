package com.shilapi.performance.sampler

import com.shilapi.performance.model.LinkType
import org.junit.Assert.assertEquals
import org.junit.Test

class LinkSamplerTest {
    @Test
    fun returnsUsbWiredImmediatelyWhenWired() {
        val link = LinkSampler.sample(context = null, isWired = true)
        assertEquals(LinkType.USB_WIRED, link.type)
        assertEquals("NCM / AOA", link.detail)
    }

    @Test
    fun formatsWifiDetailCorrectly() {
        val detail = LinkSampler.formatWifiDetail(
            ssid = "CarConnect",
            frequencyMhz = 5180,
            linkSpeedMbps = 433,
            rssiDbm = -55,
        )
        assertEquals("5 GHz · 433 Mbps (-55 dBm)", detail)
    }

    @Test
    fun formats24GhzWifiDetailCorrectly() {
        val detail = LinkSampler.formatWifiDetail(
            ssid = "CarConnect",
            frequencyMhz = 2437,
            linkSpeedMbps = 72,
            rssiDbm = -68,
        )
        assertEquals("2.4 GHz · 72 Mbps (-68 dBm)", detail)
    }
}
