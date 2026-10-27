package com.shilapi.xcertplay.instrument

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
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

    private val targetIntent = Intent().apply {
        action = ACTION_MEDIA_SERVICE
        component = ComponentName(
            PKG_INSTRUMENT_SERVER,
            CLS_MEDIA_SERVICE,
        )
    }

    internal var isSupportedOverrideForTest: Boolean? = null
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
    private var lastSentTimeMs: Long = 0L
    @Volatile
    private var sendToken: Long = 0L

    // 成功记账和清屏改代数共用这把锁。东软调用返回的瞬间可能正好在清屏，
    // 「看代数」和「写下上次已发送」不能被清屏插开。
    private val sentStateLock = Any()

    private var pendingRunnable: Runnable? = null
    private var pendingGen: Int = -1
    private var pendingPlaying: Boolean? = null
    private var pendingToken: Long = -1L

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
            executor.execute {
                if (released.get() || currentGeneration.get() != gen) return@execute
                cancelPendingAction()
                val safeTitle = truncateCanString(if (!cached.title.isNullOrBlank()) cached.title else " ")
                val safeArtist = truncateCanString(if (!cached.artist.isNullOrBlank()) cached.artist else " ")
                val safeAlbum = truncateCanString(if (!cached.album.isNullOrBlank()) cached.album else " ")
                doSendMediaUpdate(gen, safeTitle, safeArtist, safeAlbum, cached.isPlaying)
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
                if (released.get()) return
                val cached = lastCachedMedia ?: return
                val gen = currentGeneration.get()
                DiagLog.i(TAG, "Cluster media tab requested info, replaying: ${cached.title} - ${cached.artist} (gen=$gen)")
                executor.execute {
                    if (released.get() || currentGeneration.get() != gen) return@execute
                    cancelPendingAction()
                    val safeTitle = truncateCanString(if (!cached.title.isNullOrBlank()) cached.title else " ")
                    val safeArtist = truncateCanString(if (!cached.artist.isNullOrBlank()) cached.artist else " ")
                    val safeAlbum = truncateCanString(if (!cached.album.isNullOrBlank()) cached.album else " ")
                    doSendMediaUpdate(gen, safeTitle, safeArtist, safeAlbum, cached.isPlaying)
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
        isPlaying: Boolean,
    ) {
        if (released.get() || !isSupported || executor.isShutdown) return
        // 缓存里的 playing 在入队时就改成新状态，不要等补发成功才改
        lastCachedMedia = CachedMedia(title, artist, album, isPlaying)
        val gen = currentGeneration.get()
        executor.execute {
            if (released.get() || currentGeneration.get() != gen) return@execute
            processMediaUpdate(gen, title, artist, album, isPlaying)
        }
    }

    private fun truncateCanString(str: String): String {
        val trimmed = str.trim()
        return if (trimmed.length > MAX_CAN_TEXT_LENGTH) trimmed.substring(0, MAX_CAN_TEXT_LENGTH) else trimmed
    }

    private fun processMediaUpdate(
        gen: Int,
        title: String?,
        artist: String?,
        album: String?,
        isPlaying: Boolean,
    ) {
        val safeTitle = truncateCanString(if (!title.isNullOrBlank()) title else " ")
        val safeArtist = truncateCanString(if (!artist.isNullOrBlank()) artist else " ")
        val safeAlbum = truncateCanString(if (!album.isNullOrBlank()) album else " ")

        val now = SystemClock.uptimeMillis()
        val isMetadataSame = safeTitle == lastSentTitle && safeArtist == lastSentArtist
        val isPlayingSame = isPlaying == lastSentPlaying

        if (isMetadataSame && isPlayingSame) {
            // 播放状态已与上次发送一致（如暂停后又恢复播放），立即取消暂存的暂停
            cancelPendingAction()
            return
        }

        val elapsed = now - lastSentTimeMs
        if (isMetadataSame && !isPlayingSame && elapsed < MIN_CAN_INTERVAL_MS) {
            // 歌名和歌手没变、播放状态变了、距离上次成功发送不足 2 秒：不要丢。
            // 记下最新的播放或暂停，以及当时的代数与完整发送记号。窗口结束时把一笔 onAction(1) 或 onAction(2) 排进同一条队列。
            schedulePendingAction(gen, isPlaying, sendToken, delayMs = MIN_CAN_INTERVAL_MS - elapsed)
            return
        }

        // 窗口已经结束，或者歌名变了：按现有路径马上发送，并取消还没发出的暂存。
        cancelPendingAction()
        doSendMediaUpdate(gen, safeTitle, safeArtist, safeAlbum, isPlaying)
    }

    @Synchronized
    private fun cancelPendingAction() {
        pendingRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingRunnable = null
        pendingGen = -1
        pendingPlaying = null
        pendingToken = -1L
    }

    @Synchronized
    private fun schedulePendingAction(gen: Int, isPlaying: Boolean, token: Long, delayMs: Long) {
        cancelPendingAction()
        pendingGen = gen
        pendingPlaying = isPlaying
        pendingToken = token
        val r = Runnable {
            synchronized(this@XingruiInstrumentBridge) {
                if (pendingGen != gen || pendingToken != token || released.get()) return@Runnable
                val actionPlaying = pendingPlaying ?: return@Runnable
                pendingRunnable = null
                pendingGen = -1
                pendingPlaying = null
                pendingToken = -1L
                executor.execute {
                    if (released.get() || currentGeneration.get() != gen) return@execute
                    // 补发开始时：如果已经发出过新歌（sendToken 变了），丢弃
                    if (sendToken != token) {
                        DiagLog.d(TAG, "Dropped pending onAction: a newer track or full send occurred (token $token != $sendToken)")
                        return@execute
                    }
                    // 如果新的播放状态已经和上次发送的一致，丢弃
                    if (lastSentPlaying == actionPlaying) {
                        DiagLog.d(TAG, "Dropped pending onAction: playing state already matches last sent ($actionPlaying)")
                        return@execute
                    }
                    // 如果当前缓存的最新状态已经不再是这笔动作，丢弃
                    if (lastCachedMedia?.isPlaying != actionPlaying) {
                        DiagLog.d(TAG, "Dropped pending onAction: cached playing state changed (${lastCachedMedia?.isPlaying} != $actionPlaying)")
                        return@execute
                    }
                    doSendActionOnly(gen, actionPlaying)
                }
            }
        }
        pendingRunnable = r
        mainHandler.postDelayed(r, delayMs.coerceAtLeast(10L))
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
        val sourceType = AirPlayPersistence.loadClusterMediaSource(appContext)
        try {
            service.onAction(if (isPlaying) 1 else 2, sourceType)
            if (recordActionIfCurrent(gen, isPlaying)) {
                DiagLog.i(TAG, "Sent pending onAction to Xingrui IPK (source=$sourceType, gen=$gen): isPlaying=$isPlaying")
            } else {
                DiagLog.d(TAG, "Discarded pending onAction bookkeeping because generation moved during IPC (gen=$gen)")
            }
        } catch (e: RemoteException) {
            DiagLog.w(TAG, "RemoteException calling onAction, will reconnect", e)
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

        val sourceType = AirPlayPersistence.loadClusterMediaSource(appContext)

        try {
            // 每次真正调用 updateMedia 之前先 onSourceChanged(0)。
            // 封面删了，这句不能删。东软初始状态是 -101，没调用过就整包丢弃。
            service.onSourceChanged(sourceType)
            // 播放中的封面参数固定为非空字符串 "carplay"，用来避开东软对 "" 发 -102 关屏。清屏才传 ""。
            service.updateMedia(0, safeTitle, safeAlbum, safeArtist, "carplay", sourceType)
            service.onAction(if (isPlaying) 1 else 2, sourceType)

            // 三次调用都成功之后才记账。记账前再看代数，并且和清屏加代数放在同一把锁里。
            // 对不上就不要记：失败、或者清屏已经发生，都保持清屏留下的空记录，同一首歌还会再发。
            val token = recordFullSendIfCurrent(gen, safeTitle, safeArtist, isPlaying)
            if (token != null) {
                DiagLog.i(TAG, "Sent to Xingrui IPK (source=$sourceType, gen=$gen, token=$token): $safeTitle - $safeArtist (playing=$isPlaying)")
            } else {
                DiagLog.d(TAG, "Discarded cluster send bookkeeping because generation moved during IPC (gen=$gen)")
            }
        } catch (e: RemoteException) {
            DiagLog.w(TAG, "RemoteException calling MediaService, will reconnect", e)
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
        cancelPendingAction()
        lastCachedMedia = null

        executor.execute {
            if (!claimClearSentState(gen)) return@execute
            try {
                val service = dimServiceForTest ?: dimService ?: return@execute
                val sourceType = AirPlayPersistence.loadClusterMediaSource(appContext)
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
                cancelPendingAction()
                lastCachedMedia = null

                executor.execute {
                    if (!claimClearSentState(gen)) {
                        finishRelease()
                        return@execute
                    }
                    try {
                        val service = dimServiceForTest ?: dimService
                        if (service != null) {
                            val sourceType = AirPlayPersistence.loadClusterMediaSource(appContext)
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
        lastSentTimeMs = 0L
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
    ): Long? = synchronized(sentStateLock) {
        if (released.get() || currentGeneration.get() != gen) {
            null
        } else {
            val token = ++sendToken
            lastSentTitle = title
            lastSentArtist = artist
            lastSentPlaying = isPlaying
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
    internal fun hasPendingActionForTest(): Boolean = synchronized(this) { pendingRunnable != null }
    internal fun triggerSchedulePendingActionForTest(gen: Int, isPlaying: Boolean, delayMs: Long) {
        schedulePendingAction(gen, isPlaying, sendToken, delayMs)
    }
    internal fun getSendTokenForTest(): Long = sendToken
    internal fun getLastSentPlayingForTest(): Boolean? = lastSentPlaying
    internal fun getLastSentTitleForTest(): String? = lastSentTitle
    internal fun getLastSentArtistForTest(): String? = lastSentArtist
    internal fun drainQueueForTest() {
        runCatching {
            executor.submit {}.get(2, java.util.concurrent.TimeUnit.SECONDS)
        }
    }
    internal fun simulateFullSendForTest(title: String, artist: String, isPlaying: Boolean) {
        cancelPendingAction()
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

        private const val MIN_CAN_INTERVAL_MS = 2000L
        private const val MAX_CAN_TEXT_LENGTH = 31

        private const val PKG_INSTRUMENT_SERVER = "com.neusoft.shockwave.instrumentserver"
        private const val CLS_MEDIA_SERVICE = "com.ecarx.xui.adaptapi.diminteraction.MediaService"
        private const val ACTION_MEDIA_SERVICE = "com.ecarx.xui.adaptapi.diminteraction.MediaService"

        private const val ACTION_AUDIO_REQUIRE = "com.neusoft.optimus.mmi.AudioRequireAction"
        private const val EXTRA_BROADCAST_ACTION = "broadcastAction"
        private const val ACTION_GET_PLAY_INFO = 3
    }
}
