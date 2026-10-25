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
    fun formatsWifiDetailWithSsidWhenProvided() {
        val detail = LinkSampler.formatWifiDetail(
            ssid = "Geely-E01",
            frequencyMhz = 5180,
            linkSpeedMbps = 433,
            rssiDbm = -55,
        )
        assertEquals("Geely-E01 · 5 GHz · 433 Mbps (-55 dBm)", detail)
    }

    @Test
    fun formats24GhzWifiDetailWithoutSsidWhenEmpty() {
        val detail = LinkSampler.formatWifiDetail(
            ssid = "",
            frequencyMhz = 2437,
            linkSpeedMbps = 72,
            rssiDbm = -68,
        )
        assertEquals("2.4 GHz · 72 Mbps (-68 dBm)", detail)
    }

    @Test
    fun formatsWifiDetailOmittingUnknownSsidPlaceholder() {
        val detail = LinkSampler.formatWifiDetail(
            ssid = "<unknown ssid>",
            frequencyMhz = 5180,
            linkSpeedMbps = 433,
            rssiDbm = -55,
        )
        assertEquals("5 GHz · 433 Mbps (-55 dBm)", detail)
    }
}
