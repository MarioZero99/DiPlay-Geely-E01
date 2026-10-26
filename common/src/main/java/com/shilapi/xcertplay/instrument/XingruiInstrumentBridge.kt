package com.shilapi.xcertplay.instrument

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.IBinder
import android.os.RemoteException
import com.ecarx.xui.adaptapi.diminteraction.IDimTransListener
import com.shilapi.xcertplay.DiagLog
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
        action = ACTION_MEDIA_SERVICE
        component = ComponentName(
            PKG_INSTRUMENT_SERVER,
            CLS_MEDIA_SERVICE
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
    private var receiverRegistered = false

    // Cache of the latest media metadata to replay on connection or steering wheel request
    private data class CachedMedia(
        val title: String?,
        val artist: String?,
        val album: String?,
        val artwork: Bitmap?,
        val isPlaying: Boolean
    )
    @Volatile
    private var lastCachedMedia: CachedMedia? = null

    // Bitmap reference deduplication to prevent repetitive disk I/O on play/pause
    private var lastArtworkRef: WeakReference<Bitmap>? = null
    private var lastCoverUri: String? = null

    private val deathRecipient = object : IBinder.DeathRecipient {
        override fun binderDied() {
            DiagLog.w(TAG, "IPKServer MediaService died, clearing local stub")
            boundBinder?.unlinkToDeath(this, 0)
            boundBinder = null
            dimService = null
            isBound.set(false)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            DiagLog.i(TAG, "Connected to Xingrui IPK MediaService: $name")
            boundBinder = service
            try {
                service?.linkToDeath(deathRecipient, 0)
            } catch (e: RemoteException) {
                DiagLog.w(TAG, "linkToDeath failed", e)
            }
            dimService = IDimTransListener.Stub.asInterface(service)

            // Immediately replay latest cached media upon connection
            val cached = lastCachedMedia
            if (cached != null) {
                DiagLog.i(TAG, "Replaying cached media on service connected: ${cached.title} - ${cached.artist}")
                executor.execute {
                    sendMediaUpdate(cached.title, cached.artist, cached.album, cached.artwork, cached.isPlaying)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            DiagLog.w(TAG, "Disconnected from Xingrui IPK MediaService: $name")
            boundBinder?.unlinkToDeath(deathRecipient, 0)
            boundBinder = null
            dimService = null
            isBound.set(false)
        }
    }

    private val audioRequireReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val actionType = intent.getIntExtra(EXTRA_BROADCAST_ACTION, -1)
            DiagLog.d(TAG, "Received AudioRequireAction broadcast: broadcastAction=$actionType")
            if (actionType == ACTION_GET_PLAY_INFO) {
                val cached = lastCachedMedia
                if (cached != null) {
                    DiagLog.i(TAG, "Cluster media tab requested info, replaying: ${cached.title} - ${cached.artist}")
                    executor.execute {
                        sendMediaUpdate(cached.title, cached.artist, cached.album, cached.artwork, cached.isPlaying)
                    }
                }
            }
        }
    }

    init {
        if (isSupported) {
            safeBind()
            registerAudioRequireReceiver()
        } else {
            DiagLog.w(TAG, "Xingrui IPK MediaService is not supported on this device")
        }
    }

    private fun registerAudioRequireReceiver() {
        try {
            val filter = IntentFilter(ACTION_AUDIO_REQUIRE)
            appContext.registerReceiver(audioRequireReceiver, filter)
            receiverRegistered = true
            DiagLog.i(TAG, "Registered AudioRequireAction receiver")
        } catch (t: Throwable) {
            DiagLog.w(TAG, "Failed to register AudioRequireAction receiver", t)
        }
    }

    private fun safeBind() {
        if (!isSupported || isBound.get()) return
        try {
            val success = appContext.bindService(targetIntent, serviceConnection, Context.BIND_AUTO_CREATE)
            isBound.set(success)
            DiagLog.i(TAG, "bindService to MediaService result=$success")
        } catch (t: Throwable) {
            DiagLog.w(TAG, "safeBind encountered exception", t)
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
        lastCachedMedia = CachedMedia(title, artist, album, artwork, isPlaying)
        executor.execute {
            sendMediaUpdate(title, artist, album, artwork, isPlaying)
        }
    }

    private fun sendMediaUpdate(
        title: String?,
        artist: String?,
        album: String?,
        artwork: Bitmap?,
        isPlaying: Boolean
    ) {
        try {
            if (dimService == null && !isBound.get()) {
                safeBind()
            }

            val service = dimService
            if (service == null) {
                DiagLog.d(TAG, "MediaService not ready yet, update cached for replay")
                return
            }

            val safeTitle = if (!title.isNullOrBlank()) title else " "
            val safeArtist = if (!artist.isNullOrBlank()) artist else " "
            val safeAlbum = if (!album.isNullOrBlank()) album else " "

            // Guarantee cover URI is never empty to prevent triggering IPKServer -102 clear-screen
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
                DiagLog.i(TAG, "Sent to Xingrui IPK: $safeTitle - $safeArtist (cover=$coverPath, playing=$isPlaying)")
            } catch (e: RemoteException) {
                DiagLog.w(TAG, "RemoteException calling MediaService, will reconnect", e)
                dimService = null
                isBound.set(false)
            }
        } catch (t: Throwable) {
            DiagLog.e(TAG, "Safe catch-all to prevent any crash in instrument bridge", t)
        }
    }

    fun clearMedia() {
        if (!isSupported || executor.isShutdown) return
        lastCachedMedia = null
        executor.execute {
            try {
                val service = dimService ?: return@execute
                try {
                    service.updateMedia(0, " ", " ", " ", "", SOURCE_ONLINE_MUSIC)
                    service.onAction(2, SOURCE_ONLINE_MUSIC)
                    DiagLog.i(TAG, "Cleared Xingrui IPK media")
                } catch (e: RemoteException) {
                    DiagLog.w(TAG, "RemoteException while clearing media", e)
                }
            } catch (t: Throwable) {
                DiagLog.e(TAG, "Error in clearMedia", t)
            }
        }
    }

    fun release() {
        try {
            if (receiverRegistered) {
                runCatching { appContext.unregisterReceiver(audioRequireReceiver) }
                receiverRegistered = false
            }
            dimService?.let { service ->
                runCatching {
                    service.updateMedia(0, " ", " ", " ", "", SOURCE_ONLINE_MUSIC)
                    service.onAction(2, SOURCE_ONLINE_MUSIC)
                }
            }
            boundBinder?.unlinkToDeath(deathRecipient, 0)
            boundBinder = null
            dimService = null
            lastCachedMedia = null
            if (isBound.compareAndSet(true, false)) {
                appContext.unbindService(serviceConnection)
            }
            executor.shutdown()
            DiagLog.i(TAG, "Released XingruiInstrumentBridge")
        } catch (t: Throwable) {
            DiagLog.w(TAG, "Error during release", t)
        }
    }

    companion object {
        private const val TAG = "XingruiInstrument"
        const val SOURCE_ONLINE_MUSIC = 6

        private const val PKG_INSTRUMENT_SERVER = "com.neusoft.shockwave.instrumentserver"
        private const val CLS_MEDIA_SERVICE = "com.ecarx.xui.adaptapi.diminteraction.MediaService"
        private const val ACTION_MEDIA_SERVICE = "com.ecarx.xui.adaptapi.diminteraction.MediaService"

        private const val ACTION_AUDIO_REQUIRE = "com.neusoft.optimus.mmi.AudioRequireAction"
        private const val EXTRA_BROADCAST_ACTION = "broadcastAction"
        private const val ACTION_GET_PLAY_INFO = 3
    }
}
