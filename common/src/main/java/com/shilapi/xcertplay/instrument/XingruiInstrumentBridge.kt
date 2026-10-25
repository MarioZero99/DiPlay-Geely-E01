package com.shilapi.xcertplay.instrument

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.ecarx.xui.adaptapi.diminteraction.IDimTransListener
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal class XingruiInstrumentBridge(context: Context) {
    private val appContext = context.applicationContext
    private val coverManager = CoverSlotManager(appContext)
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "XingruiInstrument-Worker").apply { isDaemon = true }
    }

    private val targetIntent = Intent().apply {
        component = ComponentName(
            "com.neusoft.shockwave.instrumentserver",
            "com.ecarx.xui.adaptapi.diminteraction.MediaService"
        )
    }

    val isSupported: Boolean = runCatching {
        appContext.packageManager.resolveService(targetIntent, 0) != null
    }.getOrDefault(false)

    @Volatile
    private var dimService: IDimTransListener? = null
    @Volatile
    private var boundBinder: IBinder? = null
    private val isBound = AtomicBoolean(false)

    // Bitmap 引用去重缓存，防止暂停/播放重复做写盘 I/O
    private var lastArtworkRef: WeakReference<Bitmap>? = null
    private var lastCoverUri: String? = null

    private val deathRecipient = object : IBinder.DeathRecipient {
        override fun binderDied() {
            Log.w(TAG, "IPKServer MediaService died, clearing local stub")
            boundBinder?.unlinkToDeath(this, 0)
            boundBinder = null
            dimService = null
            isBound.set(false)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "Connected to Xingrui IPK MediaService: $name")
            boundBinder = service
            try {
                service?.linkToDeath(deathRecipient, 0)
            } catch (e: RemoteException) {
                Log.w(TAG, "linkToDeath failed", e)
            }
            dimService = IDimTransListener.Stub.asInterface(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Disconnected from Xingrui IPK MediaService: $name")
            boundBinder?.unlinkToDeath(deathRecipient, 0)
            boundBinder = null
            dimService = null
            isBound.set(false)
        }
    }

    init {
        if (isSupported) {
            safeBind()
        }
    }

    private fun safeBind() {
        if (!isSupported || isBound.get()) return
        try {
            val success = appContext.bindService(targetIntent, serviceConnection, Context.BIND_AUTO_CREATE)
            isBound.set(success)
            Log.i(TAG, "bindService to MediaService result=$success")
        } catch (t: Throwable) {
            Log.w(TAG, "safeBind encountered exception", t)
        }
    }

    /**
     * Non-blocking update method. Completely safe to call from main thread.
     */
    fun updateMedia(
        title: String?,
        artist: String?,
        album: String?,
        artwork: Bitmap?,
        isPlaying: Boolean
    ) {
        if (!isSupported || executor.isShutdown) return
        executor.execute {
            try {
                if (dimService == null && !isBound.get()) {
                    safeBind()
                }

                val service = dimService
                if (service == null) {
                    Log.d(TAG, "MediaService not ready yet, skip update")
                    return@execute
                }

                val safeTitle = if (!title.isNullOrBlank()) title else " "
                val safeArtist = if (!artist.isNullOrBlank()) artist else " "
                val safeAlbum = if (!album.isNullOrBlank()) album else " "

                // 封面去重与空白图保障：绝不传空字符串防止触发原厂 IPKServer -102 清屏
                val coverPath = if (artwork != null && !artwork.isRecycled) {
                    val prevBmp = lastArtworkRef?.get()
                    if (prevBmp === artwork && lastCoverUri != null) {
                        lastCoverUri!!
                    } else {
                        val uri = coverManager.saveCover(artwork)
                        lastArtworkRef = WeakReference(artwork)
                        lastCoverUri = uri
                        uri
                    }
                } else {
                    lastArtworkRef = null
                    lastCoverUri = null
                    coverManager.getBlankCoverUri()
                }

                try {
                    // Source 6 = Online music (enables album art display in AlbumService)
                    service.onSourceChanged(SOURCE_ONLINE_MUSIC)
                    service.updateMedia(0, safeTitle, safeAlbum, safeArtist, coverPath, SOURCE_ONLINE_MUSIC)
                    service.onAction(if (isPlaying) 1 else 2, SOURCE_ONLINE_MUSIC)
                    Log.d(TAG, "Sent to Xingrui IPK: $safeTitle - $safeArtist (cover=$coverPath, playing=$isPlaying)")
                } catch (e: RemoteException) {
                    Log.w(TAG, "RemoteException calling MediaService, will reconnect", e)
                    dimService = null
                    isBound.set(false)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Safe catch-all to prevent any crash in instrument bridge", t)
            }
        }
    }

    fun clearMedia() {
        if (!isSupported || executor.isShutdown) return
        executor.execute {
            try {
                val service = dimService ?: return@execute
                try {
                    service.updateMedia(0, " ", " ", " ", "", SOURCE_ONLINE_MUSIC)
                    service.onAction(2, SOURCE_ONLINE_MUSIC)
                } catch (e: RemoteException) {
                    Log.w(TAG, "RemoteException while clearing media", e)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error in clearMedia", t)
            }
        }
    }

    fun release() {
        try {
            // 同步清屏：在当前线程直接调用已有 service 引用，确保解绑前指令送达
            dimService?.let { service ->
                runCatching {
                    service.updateMedia(0, " ", " ", " ", "", SOURCE_ONLINE_MUSIC)
                    service.onAction(2, SOURCE_ONLINE_MUSIC)
                }
            }
            boundBinder?.unlinkToDeath(deathRecipient, 0)
            boundBinder = null
            dimService = null
            if (isBound.compareAndSet(true, false)) {
                appContext.unbindService(serviceConnection)
            }
            executor.shutdown()
        } catch (t: Throwable) {
            Log.w(TAG, "Error during release", t)
        }
    }

    companion object {
        private const val TAG = "XingruiInstrument"
        const val SOURCE_ONLINE_MUSIC = 6
    }
}
