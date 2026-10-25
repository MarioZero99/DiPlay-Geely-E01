package com.shilapi.xcertplay.instrument

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream

internal class CoverSlotManager(private val context: Context) {
    private val coversDir: File by lazy {
        val baseDir = context.externalCacheDir 
            ?: File(Environment.getExternalStorageDirectory(), "Android/data/${context.packageName}/cache")
        File(baseDir, "covers").apply {
            if (!exists()) {
                runCatching { mkdirs() }
            }
        }
    }

    private val slotFiles by lazy {
        arrayOf(
            File(coversDir, "cover_0.jpg"),
            File(coversDir, "cover_1.jpg")
        )
    }

    private val blankFile: File by lazy {
        File(coversDir, "cover_blank.jpg").apply {
            if (!exists() || length() == 0L) {
                runCatching {
                    val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.RGB_565).apply {
                        eraseColor(Color.BLACK)
                    }
                    FileOutputStream(this@apply).use { out ->
                        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
                        out.flush()
                    }
                }.onFailure { Log.w(TAG, "Failed to create blank cover fallback", it) }
            }
        }
    }

    @Volatile
    private var currentSlot = 0

    fun getBlankCoverUri(): String = toSdcardUri(blankFile)

    /**
     * Saves bitmap to the alternate slot and returns the formatted URI for IPKServer.
     * Returns blank cover URI if save fails or bitmap is null/recycled.
     */
    fun saveCover(bitmap: Bitmap?): String {
        if (bitmap == null || bitmap.isRecycled) return getBlankCoverUri()
        return try {
            val nextSlot = 1 - currentSlot
            val targetFile = slotFiles[nextSlot]

            val parent = targetFile.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }

            FileOutputStream(targetFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
                out.flush()
            }
            currentSlot = nextSlot
            toSdcardUri(targetFile)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to save cover art safely, falling back to blank cover", t)
            getBlankCoverUri()
        }
    }

    private fun toSdcardUri(file: File): String {
        return "file:///sdcard/Android/data/${context.packageName}/cache/covers/${file.name}"
    }

    companion object {
        private const val TAG = "CoverSlotManager"
    }
}
