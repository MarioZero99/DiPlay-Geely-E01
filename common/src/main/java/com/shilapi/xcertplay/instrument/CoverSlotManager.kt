package com.shilapi.xcertplay.instrument

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.shilapi.xcertplay.DiagLog
import com.shilapi.xcertplay.host.R
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger

internal class CoverSlotManager(
    private val baseDir: File = File("/sdcard/Music/mediacenter/images")
) {
    companion object {
        private const val TAG = "CoverSlotManager"
        const val MAX_CLUSTER_DIM = 214
        const val SDCARD_URI_PREFIX = "file:///sdcard/Music/mediacenter/images"
        const val DEFAULT_COVER_FILENAME = "default_cover.jpg"
    }

    private val slotIndex = AtomicInteger(0)
    @Volatile
    private var isDefaultCoverReady: Boolean = false
    private val defaultCoverLock = Any()

    fun saveCover(bitmap: Bitmap?): String? {
        if (bitmap == null || bitmap.isRecycled) return null
        val nextSlot = Math.floorMod(slotIndex.getAndIncrement(), 2)
        val targetFile = File(baseDir, "cover_$nextSlot.jpg")
        return try {
            if (!baseDir.exists()) {
                baseDir.mkdirs()
            }
            val scaledBitmap = scaleToClusterBounds(bitmap, MAX_CLUSTER_DIM)
            FileOutputStream(targetFile).use { out ->
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            if (scaledBitmap != bitmap) {
                scaledBitmap.recycle()
            }
            targetFile.setReadable(true, false)
            "$SDCARD_URI_PREFIX/cover_$nextSlot.jpg"
        } catch (t: Throwable) {
            DiagLog.w(TAG, "Failed to save cover image to slot $nextSlot", t)
            null
        }
    }

    /**
     * Saves or returns the default fallback cover for timeouts or radio/podcast streams without artwork.
     * Prevents empty artwork triggers on instrument cluster while providing a clean, recognizable icon.
     * Uses double-checked locking and in-memory flag to ensure zero disk I/O once created.
     */
    fun saveDefaultCover(context: Context?): String? {
        if (isDefaultCoverReady) {
            return "$SDCARD_URI_PREFIX/$DEFAULT_COVER_FILENAME"
        }
        val targetFile = File(baseDir, DEFAULT_COVER_FILENAME)
        return try {
            synchronized(defaultCoverLock) {
                if (isDefaultCoverReady) {
                    return "$SDCARD_URI_PREFIX/$DEFAULT_COVER_FILENAME"
                }
                if (!baseDir.exists()) {
                    baseDir.mkdirs()
                }
                if (!targetFile.exists() || targetFile.length() == 0L) {
                    val bitmap = if (context != null) {
                        runCatching {
                            BitmapFactory.decodeResource(context.resources, R.drawable.ic_carplay)
                        }.getOrNull()
                    } else null

                    val finalBmp = if (bitmap != null) {
                        val scaled = scaleToClusterBounds(bitmap, MAX_CLUSTER_DIM)
                        // Compose onto solid background to prevent black box artifacts when compressing transparent PNG to JPEG
                        val solidBmp = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(solidBmp)
                        canvas.drawColor(Color.rgb(0x1F, 0x24, 0x30))
                        canvas.drawBitmap(scaled, 0f, 0f, null)
                        if (scaled !== bitmap) {
                            scaled.recycle()
                        }
                        solidBmp
                    } else {
                        createFallbackMusicBitmap()
                    }

                    FileOutputStream(targetFile).use { out ->
                        finalBmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    }
                    if (finalBmp !== bitmap) {
                        finalBmp.recycle()
                    }
                    targetFile.setReadable(true, false)
                }
                isDefaultCoverReady = true
            }
            "$SDCARD_URI_PREFIX/$DEFAULT_COVER_FILENAME"
        } catch (t: Throwable) {
            DiagLog.w(TAG, "Failed to save default cover image", t)
            null
        }
    }

    private fun createFallbackMusicBitmap(): Bitmap {
        val size = 192
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val bgPaint = Paint().apply {
            color = Color.rgb(0x1F, 0x24, 0x30)
            isAntiAlias = true
        }
        canvas.drawColor(bgPaint.color)
        val iconPaint = Paint().apply {
            color = Color.WHITE
            isAntiAlias = true
            textSize = 72f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("♫", size / 2f, size / 2f + 24f, iconPaint)
        return bmp
    }

    private fun scaleToClusterBounds(src: Bitmap, maxDim: Int): Bitmap {
        val width = src.width
        val height = src.height
        if (width <= maxDim && height <= maxDim) return src
        val ratio = maxDim.toFloat() / maxOf(width, height)
        val dstW = (width * ratio).toInt().coerceAtLeast(1)
        val dstH = (height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, dstW, dstH, true)
    }

    fun clear(deleteDefaultCover: Boolean = false) {
        runCatching {
            File(baseDir, "cover_0.jpg").delete()
            File(baseDir, "cover_1.jpg").delete()
            if (deleteDefaultCover) {
                File(baseDir, DEFAULT_COVER_FILENAME).delete()
                isDefaultCoverReady = false
            }
        }.onFailure { t ->
            DiagLog.w(TAG, "Failed to clear cover slots", t)
        }
    }
}
