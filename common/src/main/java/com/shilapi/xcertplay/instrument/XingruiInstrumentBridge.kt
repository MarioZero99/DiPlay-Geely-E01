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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal class XingruiInstrumentBridge(context: Context) {
    private val appContext = context.applicationContext
    private val coverManager = CoverSlotManager(appContext)
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "XingruiInstrument-Worker").apply { isDaemon = true }
    }

    @Volatile
    private var dimService: IDimTransListener? = null
    private val isBound = AtomicBoolean(false)

    private val deathRecipient = IBinder.DeathRecipient {
        Log.w(TAG, "IPKServer MediaService died, will rebind on next update or bind call")
        dimService = null
        isBound.set(false)
        if (!executor.isShutdown) {
            executor.execute {
                safeBind()
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "Connected to Xingrui IPK MediaService: $name")
            try {
                service?.linkToDeath(deathRecipient, 0)
            } catch (e: RemoteException) {
                Log.w(TAG, "linkToDeath failed", e)
            }
            dimService = IDimTransListener.Stub.asInterface(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Disconnected from Xingrui IPK MediaService: $name")
            dimService = null
            isBound.set(false)
        }
    }

    init {
        safeBind()
    }

    private fun safeBind() {
        if (isBound.get()) return
        try {
            val intent = Intent().apply {
                component = ComponentName(
                    "com.neusoft.shockwave.instrumentserver",
                    "com.ecarx.xui.adaptapi.diminteraction.MediaService"
                )
            }
            val success = appContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
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
        if (executor.isShutdown) return
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

                val coverPath = coverManager.saveCover(artwork)

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
        if (executor.isShutdown) return
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
            clearMedia()
            if (isBound.compareAndSet(true, false)) {
                appContext.unbindService(serviceConnection)
            }
            dimService = null
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
