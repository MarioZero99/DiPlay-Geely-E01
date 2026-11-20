package com.shilapi.xcertplay.instrument.fly

/**
 * The few H.264 facts the presentation decoder needs from the CarPlay stream.
 *
 * The sink hands over an avcC record (length-prefixed SPS/PPS) and Annex-B access units. The
 * decoder wants the parameter sets without start codes, and has to know when a unit is an IDR so it
 * can start — or restart — on a complete picture.
 */
internal object FlyAnnex {
    data class ParameterSets(val sps: ByteArray, val pps: ByteArray)

    fun isConfig(bytes: ByteArray): Boolean = profile(bytes) != null

    fun profile(bytes: ByteArray): Int? {
        if (bytes.size < 7 || bytes[0].toInt() != 1) return null
        if ((bytes[4].toInt() and 0xFC) != 0xFC) return null
        if ((bytes[5].toInt() and 0xE0) != 0xE0) return null
        return bytes[1].toInt() and 0xFF
    }

    fun parameterSets(config: ByteArray): ParameterSets? {
        if (profile(config) == null) return null
        var at = 5
        val spsCount = config[at].toInt() and 0x1F
        at++
        val sps = readSet(config, at, spsCount) ?: return null
        at = sps.second
        if (at >= config.size) return null
        val ppsCount = config[at].toInt() and 0xFF
        at++
        val pps = readSet(config, at, ppsCount) ?: return null
        return ParameterSets(sps.first, pps.first)
    }

    fun isIdr(nalu: ByteArray): Boolean = hasType(nalu, 5)
    fun isParameterSet(nalu: ByteArray): Boolean = hasType(nalu, 7) || hasType(nalu, 8)

    private fun readSet(config: ByteArray, start: Int, count: Int): Pair<ByteArray, Int>? {
        var at = start
        var first: ByteArray? = null
        repeat(count) {
            if (at + 2 > config.size) return null
            val length = ((config[at].toInt() and 0xFF) shl 8) or (config[at + 1].toInt() and 0xFF)
            val from = at + 2
            if (length <= 0 || from + length > config.size) return null
            if (first == null) first = config.copyOfRange(from, from + length)
            at = from + length
        }
        return first?.let { it to at }
    }

    private fun hasType(nalu: ByteArray, type: Int): Boolean {
        var i = 0
        while (i + 3 < nalu.size) {
            val header = when {
                nalu[i].toInt() == 0 && nalu[i + 1].toInt() == 0 && nalu[i + 2].toInt() == 1 -> i + 3
                i + 4 < nalu.size && nalu[i].toInt() == 0 && nalu[i + 1].toInt() == 0 &&
                    nalu[i + 2].toInt() == 0 && nalu[i + 3].toInt() == 1 -> i + 4
                else -> -1
            }
            if (header >= 0 && header < nalu.size && (nalu[header].toInt() and 0x1F) == type) return true
            i++
        }
        return false
    }
}
