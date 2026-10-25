package com.shilapi.performance.sampler

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import com.shilapi.performance.model.LinkMetrics
import com.shilapi.performance.model.LinkType

object LinkSampler {

    fun formatWifiDetail(
        ssid: String?,
        frequencyMhz: Int,
        linkSpeedMbps: Int,
        rssiDbm: Int,
    ): String {
        val band = when {
            frequencyMhz >= 4900 -> "5 GHz"
            frequencyMhz in 2400..2500 -> "2.4 GHz"
            else -> ""
        }
        val speed = if (linkSpeedMbps > 0) "$linkSpeedMbps Mbps" else ""
        val rssi = if (rssiDbm != 0 && rssiDbm > -127) "($rssiDbm dBm)" else ""

        val parts = mutableListOf<String>()
        val cleanSsid = ssid?.trim('\"')?.trim()
        if (!cleanSsid.isNullOrEmpty() && cleanSsid != "<unknown ssid>") {
            parts.add(cleanSsid)
        }
        if (band.isNotEmpty()) parts.add(band)
        if (speed.isNotEmpty()) parts.add(speed)
        val stats = parts.joinToString(" · ")
        val withRssi = if (rssi.isNotEmpty()) "$stats $rssi".trim() else stats
        return withRssi.ifEmpty { "已连接" }
    }

    fun sample(context: Context?, isWired: Boolean): LinkMetrics {
        if (isWired) {
            return LinkMetrics(LinkType.USB_WIRED, "NCM / AOA")
        }
        if (context == null) {
            return LinkMetrics(LinkType.UNKNOWN, "未知")
        }

        return runCatching {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return LinkMetrics(LinkType.UNKNOWN, "Wi-Fi不可用")

            // 1. 探测车机是否处于热点 (SoftAP) 模式
            val isHotspotEnabled = runCatching {
                WifiManager::class.java.getMethod("getWifiApState").invoke(wifi) as Int == 13
            }.recoverCatching {
                WifiManager::class.java.getMethod("isWifiApEnabled").invoke(wifi) as Boolean
            }.getOrDefault(false)

            if (isHotspotEnabled) {
                val apSsid = runCatching {
                    val config = WifiManager::class.java.getMethod("getWifiApConfiguration").invoke(wifi)
                    config?.javaClass?.getField("SSID")?.get(config) as? String
                }.getOrNull()?.trim('\"')?.trim()?.takeIf { it.isNotEmpty() }

                val detail = if (apSsid != null) "$apSsid · 热点已开启" else "热点已开启"
                return LinkMetrics(LinkType.CAR_HOTSPOT, detail)
            }

            // 2. 探测 Wi-Fi 客户端连接状态
            val info = wifi.connectionInfo
            if (info != null && info.networkId != -1) {
                val freq = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    runCatching { info.frequency }.getOrDefault(-1)
                } else -1

                val cleanSsid = info.ssid?.trim('\"') ?: ""
                val detail = formatWifiDetail(cleanSsid, freq, info.linkSpeed, info.rssi)
                LinkMetrics(LinkType.WIFI_CLIENT, detail)
            } else {
                LinkMetrics(LinkType.UNKNOWN, "待连接")
            }
        }.getOrDefault(LinkMetrics(LinkType.UNKNOWN, "未知"))
    }
}
