package com.shilapi.xcertplay.instrument

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.os.SystemClock
import com.ecarx.xui.adaptapi.diminteraction.IDimTransListener
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.DiagLog
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal class XingruiInstrumentBridge(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "XingruiInstrument-Worker").apply { isDaemon = true }
    }

    internal var coverSlotManager: CoverSlotManager = CoverSlotManager()

    private val targetIntent = Intent().apply {
        action = ACTION_MEDIA_SERVICE
        component = ComponentName(
            PKG_INSTRUMENT_SERVER,
            CLS_MEDIA_SERVICE,
        )
    }

    internal var debounceDelayMs: Long = 400L
    internal var minCanIntervalMs: Long = 1500L
    internal var enforceThrottleForTest: Boolean = false

    internal var isSupportedOverrideForTest: Boolean? = null
        set(value) {
            field = value
            if (value == true) {
                debounceDelayMs = 0L
            }
        }
    val isSupported: Boolean
        get() = isSupportedOverrideForTest ?: runCatching {
            appContext.packageManager.resolveService(targetIntent, 0) != null
        }.getOrDefault(false)

    @Volatile
    private var dimService: IDimTransListener? = null
    @Volatile
    private var boundBinder: IBinder? = null
    private val isBound = AtomicBoolean(false)
    private var receiverRegistered = false

    private val currentGeneration = AtomicInteger(0)
    private val released = AtomicBoolean(false)

    // Cache of the latest media metadata to replay on connection or steering wheel request
    private data class CachedMedia(
        val title: String?,
        val artist: String?,
        val album: String?,
        val isPlaying: Boolean,
        val artworkBitmap: Bitmap? = null,
    )
    @Volatile
    private var lastCachedMedia: CachedMedia? = null

    // Two-second throttle state management
    @Volatile
    private var lastSentTitle: String? = null
    @Volatile
    private var lastSentArtist: String? = null
    @Volatile
    private var lastSentPlaying: Boolean? = null
    @Volatile
    private var lastSentCoverUri: String? = null
    @Volatile
    private var lastSentCoverFingerprint: Long? = null
    @Volatile
    private var lastSentTimeMs: Long = -MIN_CAN_INTERVAL_MS
    @Volatile
    private var sendToken: Long = 0L

    // 成功记账和清屏改代数共用这把锁。东软调用返回的瞬间可能正好在清屏，
    // 「看代数」和「写下上次已发送」不能被清屏插开。
    private val sentStateLock = Any()

    private var pendingDebounceRunnable: Runnable? = null
    private var pendingDebounceGen: Int = -1

    private var pendingThrottleRunnable: Runnable? = null
    private var pendingThrottleGen: Int = -1

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

            if (released.get()) return
            val cached = lastCachedMedia ?: return
            val gen = currentGeneration.get()
            DiagLog.i(TAG, "Replaying cached media on service connected: ${cached.title} - ${cached.artist} (gen=$gen)")
            executor.execute { replayCachedMedia(gen, cached) }
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
                if (released.get()) return
                val cached = lastCachedMedia ?: return
                val gen = currentGeneration.get()
                DiagLog.i(TAG, "Cluster media tab requested info, replaying: ${cached.title} - ${cached.artist} (gen=$gen)")
                executor.execute { replayCachedMedia(gen, cached) }
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
        isPlaying: Boolean,
        artworkBitmap: Bitmap? = null,
    ) {
        if (released.get() || !isSupported || executor.isShutdown) return
        // 缓存里的 playing 在入队时就改成新状态，不要等补发成功才改
        lastCachedMedia = CachedMedia(title, artist, album, isPlaying, artworkBitmap)
        val gen = currentGeneration.get()
        executor.execute {
            try {
                if (released.get() || currentGeneration.get() != gen) return@execute
                processMediaUpdate(gen, title, artist, album, isPlaying, artworkBitmap)
            } catch (t: Throwable) {
                DiagLog.e(TAG, "Error in processMediaUpdate worker", t)
            }
        }
    }

    private fun getArtworkFingerprint(bitmap: Bitmap?): Long? {
        if (bitmap == null || bitmap.isRecycled) return null
        var h = bitmap.generationId.toLong()
        h = 31 * h + bitmap.width
        h = 31 * h + bitmap.height
        h = 31 * h + System.identityHashCode(bitmap)
        return h
    }

    private fun processMediaUpdate(
        gen: Int,
        title: String?,
        artist: String?,
        album: String?,
        isPlaying: Boolean,
        artworkBitmap: Bitmap?,
    ) {
        val safeTitle = XingruiClusterMediaFormatter.formatText(title)
        val safeArtist = XingruiClusterMediaFormatter.formatText(artist)
        val safeAlbum = XingruiClusterMediaFormatter.formatText(album)

        val currentFingerprint = getArtworkFingerprint(artworkBitmap)
        val isMetadataSame = safeTitle == lastSentTitle && safeArtist == lastSentArtist
        val isArtworkSame = if (artworkBitmap != null) {
            currentFingerprint != null && currentFingerprint == lastSentCoverFingerprint
        } else {
            lastSentCoverFingerprint == null || lastSentCoverFingerprint == DEFAULT_COVER_FINGERPRINT
        }
        val isPlayingSame = isPlaying == lastSentPlaying

        // 1. 完全一致（元数据、封面、播放状态），无需重复发送，取消所有未决暂存
        if (isMetadataSame && isPlayingSame && isArtworkSame) {
            cancelAllPending()
            return
        }

        // 2. 仅播放/暂停切换（歌曲与封面一致），无需等待 400ms 封面聚合，直接进入 CAN 节流队列
        if (isMetadataSame && isArtworkSame) {
            cancelPendingDebounce()
            enqueueThrottledSend(gen, isTrackChange = false) {
                doSendActionOnly(gen, isPlaying)
            }
            return
        }

        // 3. 歌曲元数据切换或无封面到达：若此时封面未到且开启了防抖，等待 400ms 聚合封面
        if (artworkBitmap == null && debounceDelayMs > 0L) {
            scheduleDebounce(gen)
            return
        }

        // 4. 封面已到或防抖结束：取消防抖，通过 CAN 严格节流队列保证单笔发送
        cancelPendingDebounce()
        enqueueThrottledSend(gen, isTrackChange = true) {
            val cached = lastCachedMedia ?: return@enqueueThrottledSend
            dispatchFullUpdate(gen, cached)
        }
    }

    @Synchronized
    private fun scheduleDebounce(gen: Int) {
        cancelAllPending()
        pendingDebounceGen = gen
        val r = Runnable {
            synchronized(this@XingruiInstrumentBridge) {
                if (pendingDebounceGen != gen || released.get()) return@Runnable
                pendingDebounceRunnable = null
                pendingDebounceGen = -1
                executor.execute {
                    if (released.get() || currentGeneration.get() != gen) return@execute
                    val cached = lastCachedMedia ?: return@execute
                    enqueueThrottledSend(gen, isTrackChange = true) {
                        dispatchFullUpdate(gen, cached)
                    }
                }
            }
        }
        pendingDebounceRunnable = r
        mainHandler.postDelayed(r, debounceDelayMs)
    }

    @Synchronized
    private fun cancelPendingDebounce() {
        pendingDebounceRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingDebounceRunnable = null
        pendingDebounceGen = -1
    }

    @Synchronized
    private fun enqueueThrottledSend(gen: Int, isTrackChange: Boolean = false, sendBlock: () -> Unit) {
        cancelPendingThrottle()
        val now = SystemClock.uptimeMillis()
        val elapsed = now - lastSentTimeMs
        val interval = if (isTrackChange && isSupportedOverrideForTest == true && !enforceThrottleForTest) 0L else minCanIntervalMs
        if (elapsed >= interval) {
            sendBlock()
        } else {
            val delayMs = interval - elapsed
            pendingThrottleGen = gen
            val r = Runnable {
                synchronized(this@XingruiInstrumentBridge) {
                    if (pendingThrottleGen != gen || released.get()) return@Runnable
                    pendingThrottleRunnable = null
                    pendingThrottleGen = -1
                    executor.execute {
                        if (released.get() || currentGeneration.get() != gen) return@execute
                        sendBlock()
                    }
                }
            }
            pendingThrottleRunnable = r
            mainHandler.postDelayed(r, delayMs.coerceAtLeast(10L))
        }
    }

    @Synchronized
    private fun cancelPendingThrottle() {
        pendingThrottleRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingThrottleRunnable = null
        pendingThrottleGen = -1
    }

    @Synchronized
    private fun cancelAllPending() {
        cancelPendingDebounce()
        cancelPendingThrottle()
    }

    private fun dispatchFullUpdate(gen: Int, cached: CachedMedia) {
        val safeTitle = XingruiClusterMediaFormatter.formatText(cached.title)
        val safeArtist = XingruiClusterMediaFormatter.formatText(cached.artist)
        val safeAlbum = XingruiClusterMediaFormatter.formatText(cached.album)
        val currentFingerprint = getArtworkFingerprint(cached.artworkBitmap)
        val isArtworkSame = if (cached.artworkBitmap != null) {
            currentFingerprint != null && currentFingerprint == lastSentCoverFingerprint
        } else {
            lastSentCoverFingerprint == null || lastSentCoverFingerprint == DEFAULT_COVER_FINGERPRINT
        }
        val coverUri = if (isArtworkSame && lastSentCoverUri != null) {
            lastSentCoverUri
        } else if (cached.artworkBitmap != null) {
            coverSlotManager.saveCover(cached.artworkBitmap)
        } else {
            coverSlotManager.saveDefaultCover(appContext)
        }
        val recordedFingerprint = if (cached.artworkBitmap != null) {
            currentFingerprint
        } else {
            DEFAULT_COVER_FINGERPRINT
        }
        doSendMediaUpdate(gen, safeTitle, safeArtist, safeAlbum, cached.isPlaying, coverUri, recordedFingerprint)
    }

    private fun replayCachedMedia(gen: Int, cached: CachedMedia) {
        if (released.get() || currentGeneration.get() != gen) return
        cancelAllPending()
        dispatchFullUpdate(gen, cached)
    }

    private fun doSendActionOnly(gen: Int, isPlaying: Boolean) {
        if (released.get() || currentGeneration.get() != gen) return
        val service = dimServiceForTest ?: dimService
        if (service == null) {
            if (!isBound.get()) {
                safeBind()
            }
            return
        }
        val sourceType = SOURCE_ONLINE_MUSIC
        try {
            service.onAction(if (isPlaying) 1 else 2, sourceType)
            if (recordActionIfCurrent(gen, isPlaying)) {
                DiagLog.i(TAG, "Sent pending onAction to Xingrui IPK (source=$sourceType, gen=$gen): isPlaying=$isPlaying")
            } else {
                DiagLog.d(TAG, "Discarded pending onAction bookkeeping because generation moved during IPC (gen=$gen)")
            }
        } catch (t: Throwable) {
            DiagLog.w(TAG, "Exception calling onAction, will reconnect", t)
            dimService = null
            isBound.set(false)
            safeBind()
        }
    }

    private fun doSendMediaUpdate(
        gen: Int,
        safeTitle: String,
        safeArtist: String,
        safeAlbum: String,
        isPlaying: Boolean,
        coverUri: String?,
        coverFingerprint: Long? = null,
    ) {
        if (released.get() || currentGeneration.get() != gen) return

        val service = dimServiceForTest ?: dimService
        if (service == null) {
            if (!isBound.get()) {
                safeBind()
            }
            DiagLog.d(TAG, "MediaService not ready yet, update cached for replay")
            return
        }

        val sourceType = SOURCE_ONLINE_MUSIC

        try {
            DiagLog.i(TAG, "Sending to Xingrui IPK (source=$sourceType, gen=$gen): $safeTitle - $safeArtist (playing=$isPlaying, cover=$coverUri)")
            // 每次真正调用 updateMedia 之前先 onSourceChanged。东软初始状态是 -101，没调用过就整包丢弃。
            service.onSourceChanged(sourceType)
            val mediaPath = XingruiClusterMediaFormatter.buildMediaPath(coverUri)
            service.updateMedia(0, safeTitle, safeAlbum, safeArtist, mediaPath, sourceType)
            service.onAction(if (isPlaying) 1 else 2, sourceType)

            // 调用都成功之后才记账。记账前再看代数，并且和清屏加代数放在同一把锁里。
            val token = recordFullSendIfCurrent(gen, safeTitle, safeArtist, isPlaying, coverUri, coverFingerprint)
            if (token != null) {
                DiagLog.i(TAG, "Sent to Xingrui IPK (source=$sourceType, gen=$gen, seq=$token): $safeTitle - $safeArtist (playing=$isPlaying)")
            } else {
                DiagLog.d(TAG, "Discarded cluster send bookkeeping because generation moved during IPC (gen=$gen)")
            }
        } catch (t: Throwable) {
            DiagLog.w(TAG, "Exception calling MediaService, will reconnect", t)
            dimService = null
            isBound.set(false)
            safeBind()
        }
    }

    /**
     * 关掉「仪表媒体信息」。加一次代数，取消暂存，清空缓存和上次发送记录，然后只排一笔清屏。
     * 清屏任务真正执行时再清一次上次发送记录。正在进行的东软调用返回后还会写回歌名，这笔清屏排在它后面。
     * 服务不在也要清记录。不解绑，不把 released 设为 true。之后的新歌用新代数继续发。
     */
    fun clearMedia() {
        if (!isSupported || executor.isShutdown) return
        val gen = bumpGenerationAndClearSentState()
        cancelAllPending()
        lastCachedMedia = null

        executor.execute {
            coverSlotManager.clear()
            if (!claimClearSentState(gen)) return@execute
            try {
                val service = dimServiceForTest ?: dimService ?: return@execute
                val sourceType = SOURCE_ONLINE_MUSIC
                try {
                    service.onSourceChanged(sourceType)
                    service.updateMedia(0, " ", " ", " ", "", sourceType)
                    service.onAction(2, sourceType)
                    DiagLog.i(TAG, "Cleared Xingrui IPK media (source=$sourceType, gen=$gen)")
                } catch (e: RemoteException) {
                    DiagLog.w(TAG, "RemoteException while clearing media", e)
                }
            } catch (t: Throwable) {
                DiagLog.e(TAG, "Error in clearMedia", t)
            }
        }
    }

    /**
     * 用于断开。先置 released，让新的 updateMedia 直接返回。再加一次代数，取消暂存，清空缓存，只排一笔清屏。
     * 不要调用 clearMedia，否则代数加两次，清屏任务会被第二次加代数判成过期。
     * 这笔清屏写完后再解绑、再 shutdown 这个 executor。调用 release 的线程不要在 worker 里等待这条队列，避免自己等自己。
     */
    fun release() {
        if (!isSupported) return
        try {
            val gen = synchronized(sentStateLock) {
                if (!released.compareAndSet(false, true)) {
                    -1
                } else {
                    val next = currentGeneration.incrementAndGet()
                    clearSentStateLocked()
                    next
                }
            }
            if (gen < 0) return
            try {
                if (receiverRegistered) {
                    runCatching { appContext.unregisterReceiver(audioRequireReceiver) }
                    receiverRegistered = false
                }
                cancelAllPending()
                lastCachedMedia = null

                executor.execute {
                    coverSlotManager.clear()
                    if (!claimClearSentState(gen)) {
                        finishRelease()
                        return@execute
                    }
                    try {
                        val service = dimServiceForTest ?: dimService
                        if (service != null) {
                            val sourceType = SOURCE_ONLINE_MUSIC
                            runCatching {
                                service.onSourceChanged(sourceType)
                                service.updateMedia(0, " ", " ", " ", "", sourceType)
                                service.onAction(2, sourceType)
                                DiagLog.i(TAG, "Cleared Xingrui IPK media on release (source=$sourceType, gen=$gen)")
                            }
                        }
                    } catch (t: Throwable) {
                        DiagLog.w(TAG, "Error during clear in release", t)
                    } finally {
                        finishRelease()
                    }
                }
            } catch (t: Throwable) {
                DiagLog.w(TAG, "Error during release", t)
            }
        } catch (t: Throwable) {
            DiagLog.w(TAG, "Error during release", t)
        }
    }

    private fun clearSentStateLocked() {
        lastSentTitle = null
        lastSentArtist = null
        lastSentPlaying = null
        lastSentCoverUri = null
        lastSentCoverFingerprint = null
        lastSentTimeMs = -MIN_CAN_INTERVAL_MS
    }

    private fun bumpGenerationAndClearSentState(): Int = synchronized(sentStateLock) {
        val gen = currentGeneration.incrementAndGet()
        clearSentStateLocked()
        gen
    }

    /** 清屏任务开始时再清一次。代数已经变了就整笔清屏都不做。 */
    private fun claimClearSentState(gen: Int): Boolean = synchronized(sentStateLock) {
        if (currentGeneration.get() != gen) {
            false
        } else {
            clearSentStateLocked()
            true
        }
    }

    private fun recordFullSendIfCurrent(
        gen: Int,
        title: String,
        artist: String,
        isPlaying: Boolean,
        coverUri: String?,
        coverFingerprint: Long? = null,
    ): Long? = synchronized(sentStateLock) {
        if (released.get() || currentGeneration.get() != gen) {
            null
        } else {
            val token = ++sendToken
            lastSentTitle = title
            lastSentArtist = artist
            lastSentPlaying = isPlaying
            lastSentCoverUri = coverUri
            lastSentCoverFingerprint = coverFingerprint
            lastSentTimeMs = SystemClock.uptimeMillis()
            token
        }
    }

    private fun recordActionIfCurrent(gen: Int, isPlaying: Boolean): Boolean = synchronized(sentStateLock) {
        if (released.get() || currentGeneration.get() != gen) {
            false
        } else {
            lastSentPlaying = isPlaying
            lastSentTimeMs = SystemClock.uptimeMillis()
            true
        }
    }

    private fun finishRelease() {
        try {
            boundBinder?.unlinkToDeath(deathRecipient, 0)
        } catch (t: Throwable) {
            // ignore
        }
        boundBinder = null
        dimService = null
        if (isBound.compareAndSet(true, false)) {
            runCatching { appContext.unbindService(serviceConnection) }
        }
        executor.shutdown()
        DiagLog.i(TAG, "Released XingruiInstrumentBridge successfully")
    }

    internal var dimServiceForTest: IDimTransListener? = null
    internal fun getCurrentGenerationForTest(): Int = currentGeneration.get()
    internal fun isReleasedForTest(): Boolean = released.get()
    internal fun hasPendingActionForTest(): Boolean = synchronized(this) { pendingThrottleRunnable != null || pendingDebounceRunnable != null }
    internal fun hasPendingDebounceForTest(): Boolean = synchronized(this) { pendingDebounceRunnable != null }
    internal fun hasPendingThrottleForTest(): Boolean = synchronized(this) { pendingThrottleRunnable != null }
    internal fun triggerSchedulePendingActionForTest(gen: Int, isPlaying: Boolean, delayMs: Long) {
        pendingThrottleGen = gen
        val r = Runnable {
            synchronized(this@XingruiInstrumentBridge) {
                if (pendingThrottleGen != gen || released.get()) return@Runnable
                pendingThrottleRunnable = null
                pendingThrottleGen = -1
                executor.execute {
                    if (released.get() || currentGeneration.get() != gen) return@execute
                    doSendActionOnly(gen, isPlaying)
                }
            }
        }
        pendingThrottleRunnable = r
        mainHandler.postDelayed(r, delayMs)
    }
    internal fun getSendTokenForTest(): Long = sendToken
    internal fun getLastSentPlayingForTest(): Boolean? = lastSentPlaying
    internal fun getLastSentTitleForTest(): String? = lastSentTitle
    internal fun getLastSentArtistForTest(): String? = lastSentArtist
    internal fun replayCachedForTest() {
        val cached = lastCachedMedia ?: return
        val gen = currentGeneration.get()
        executor.execute { replayCachedMedia(gen, cached) }
    }

    internal fun drainQueueForTest() {
        runCatching {
            executor.submit {}.get(2, java.util.concurrent.TimeUnit.SECONDS)
        }
    }
    internal fun simulateFullSendForTest(title: String, artist: String, isPlaying: Boolean) {
        cancelAllPending()
        synchronized(sentStateLock) {
            sendToken++
            lastSentTitle = title
            lastSentArtist = artist
            lastSentPlaying = isPlaying
            lastSentTimeMs = SystemClock.uptimeMillis()
        }
    }

    companion object {
        private const val TAG = "XingruiInstrument"
        const val SOURCE_USB = 0
        const val SOURCE_BLUETOOTH = 2
        const val SOURCE_ONLINE_MUSIC = 6
        const val DEFAULT_COVER_FINGERPRINT = -9999L

        private const val MIN_CAN_INTERVAL_MS = 1500L
        private const val MAX_CAN_TEXT_LENGTH = 31

        private const val PKG_INSTRUMENT_SERVER = "com.neusoft.shockwave.instrumentserver"
        private const val CLS_MEDIA_SERVICE = "com.ecarx.xui.adaptapi.diminteraction.MediaService"
        private const val ACTION_MEDIA_SERVICE = "com.ecarx.xui.adaptapi.diminteraction.MediaService"

        private const val ACTION_AUDIO_REQUIRE = "com.neusoft.optimus.mmi.AudioRequireAction"
        private const val EXTRA_BROADCAST_ACTION = "broadcastAction"
        private const val ACTION_GET_PLAY_INFO = 3
    }
}
