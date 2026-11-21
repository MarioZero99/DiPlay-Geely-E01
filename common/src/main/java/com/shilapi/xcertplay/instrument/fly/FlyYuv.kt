package com.shilapi.xcertplay.instrument.fly

internal data class FlyFrameMeta(
    val width: Int,
    val height: Int,
    val stride: Int,
    val slice: Int,
    val color: Int,
)

internal fun frameMeta(width: Int, height: Int, stride: Int, slice: Int, color: Int): FlyFrameMeta? {
    if (width !in 2..1920 || height !in 2..720) return null
    if (width % 2 != 0 || height % 2 != 0) return null
    if (!FlyYuv.supported(color)) return null
    val safeStride = if (stride >= width) stride else width
    val safeSlice = if (slice >= height) slice else height
    return FlyFrameMeta(width, height, safeStride, safeSlice, color)
}

internal object FlyYuv {
    const val PLANAR = 19
    const val SEMIPLANAR = 21
    const val MAX_BYTES = 3 * 1024 * 1024

    fun supported(color: Int): Boolean = color == PLANAR || color == SEMIPLANAR

    fun toRgb565(yuv: ByteArray, yuvLength: Int, meta: FlyFrameMeta, out: ByteArray): Boolean {
        val chromaRows = meta.slice / 2
        val ySize = meta.stride * meta.slice
        val needed = if (meta.color == PLANAR) {
            ySize + 2 * (meta.stride / 2) * chromaRows
        } else {
            ySize + meta.stride * chromaRows
        }
        if (yuvLength < needed || out.size < meta.width * meta.height * 2) return false
        var dst = 0
        var y = 0
        while (y < meta.height) {
            var x = 0
            while (x < meta.width) {
                val yValue = sample(yuv, y * meta.stride + x)
                val chroma = if (meta.color == PLANAR) {
                    val row = (y / 2) * (meta.stride / 2) + x / 2
                    sample(yuv, ySize + row) to sample(yuv, ySize + (meta.stride / 2) * chromaRows + row)
                } else {
                    val row = (y / 2) * meta.stride + (x / 2) * 2
                    sample(yuv, ySize + row) to sample(yuv, ySize + row + 1)
                }
                write(out, dst, rgb565(yValue, chroma.first, chroma.second))
                dst += 2
                x++
            }
            y++
        }
        return true
    }

    private fun sample(yuv: ByteArray, index: Int): Int = yuv[index].toInt() and 0xFF

    private fun rgb565(yValue: Int, uValue: Int, vValue: Int): Int {
        val c = yValue - 16
        val d = uValue - 128
        val e = vValue - 128
        val r = clamp((298 * c + 409 * e + 128) shr 8)
        val g = clamp((298 * c - 100 * d - 208 * e + 128) shr 8)
        val b = clamp((298 * c + 516 * d + 128) shr 8)
        return ((r shr 3) shl 11) or ((g shr 2) shl 5) or (b shr 3)
    }

    private fun clamp(value: Int): Int = when {
        value < 0 -> 0
        value > 255 -> 255
        else -> value
    }

    private fun write(out: ByteArray, at: Int, rgb565: Int) {
        out[at] = (rgb565 and 0xFF).toByte()
        out[at + 1] = ((rgb565 shr 8) and 0xFF).toByte()
    }
}
