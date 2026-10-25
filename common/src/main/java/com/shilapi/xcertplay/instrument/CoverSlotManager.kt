package com.shilapi.xcertplay.instrument

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.io.FileOutputStream

internal class CoverSlotManager(private val context: Context) {
    private val coversDir: File by lazy {
        val baseDir = context.externalCacheDir 
            ?: File("/sdcard/Android/data/${context.packageName}/cache")
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

    @Volatile
    private var currentSlot = 0

    /**
     * Saves bitmap to the alternate slot and returns the formatted URI for IPKServer.
     * Returns empty string if save fails or bitmap is null/recycled.
     */
    fun saveCover(bitmap: Bitmap?): String {
        if (bitmap == null || bitmap.isRecycled) return ""
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

            val absPath = targetFile.absolutePath
            val sdcardIndex = absPath.indexOf("/sdcard/")
            if (sdcardIndex >= 0) {
                "file:///sdcard/" + absPath.substring(sdcardIndex + 8)
            } else {
                val emulatedIndex = absPath.indexOf("/0/")
                if (emulatedIndex >= 0) {
                    "file:///sdcard/" + absPath.substring(emulatedIndex + 3)
                } else {
                    "file:///sdcard/Android/data/${context.packageName}/cache/covers/${targetFile.name}"
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to save cover art safely", t)
            ""
        }
    }

    companion object {
        private const val TAG = "CoverSlotManager"
    }
}
