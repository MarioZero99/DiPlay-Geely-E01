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

    /**
     * 同一份 action 在车机上有两个进程：
     * instrumentserver（uid 10028）和 extraservice（uid 1000）。
     * 东软 MediaBean 用 resolveService 注册回调，只注册在其中一份上。
     * 另一份的 listener 是空的，updateMedia 直接返回，调用方仍看到成功。
     * 两份都发：空 listener 什么都不做，有回调的那份才会进 CAN。
     */
    private val mediaEndpoints: List<BoundMediaService> = listOf(
        ComponentName(PKG_INSTRUMENT_SERVER, CLS_MEDIA_SERVICE),
        ComponentName(PKG_EXTRA_SERVICE, CLS_MEDIA_SERVICE),
    ).map { component ->
        val endpoint = BoundMediaService(component)
        endpoint.death = IBinder.DeathRecipient {
            DiagLog.w(TAG, "MediaService died: $component")
            endpoint.binder?.let { binder -> runCatching { binder.unlinkToDeath(endpoint.death, 0) } }
            endpoint.binder = null
            endpoint.listener = null
            endpoint.bound = false
        }
        endpoint.connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                DiagLog.i(TAG, "Connected to Xingrui IPK MediaService: $name")
                endpoint.binder = service
                runCatching { service?.linkToDeath(endpoint.death, 0) }
                endpoint.listener = service?.let { IDimTransListener.Stub.asInterface(it) }
                if (released.get()) return
                val cached = lastCachedMedia ?: return
                val gen = currentGeneration.get()
                DiagLog.i(TAG, "Replaying cached media on service connected: ${cached.title} - ${cached.artist} (gen=$gen)")
                executor.execute { replayCachedMedia(gen, cached) }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                DiagLog.w(TAG, "Disconnected from Xingrui IPK MediaService: $name")
                endpoint.binder?.let { binder -> runCatching { binder.unlinkToDeath(endpoint.death, 0) } }
                endpoint.binder = null
                endpoint.listener = null
                endpoint.bound = false
            }
        }
        endpoint
    }

    internal var debounceDelayMs: Long = 400L
    internal var minCanIntervalMs: Long = MIN_CAN_INTERVAL_MS
    internal var enforceThrottleForTest: Boolean = false

    internal var isSupportedOverrideForTest: Boolean? = null
        set(value) {
            field = value
            if (value == true) {
                debounceDelayMs = 0L
            }
        }
    val isSupported: Boolean
        get() = isSupportedOverrideForTest ?: mediaEndpoints.any { endpoint ->
            runCatching {
                appContext.packageManager.resolveService(intentFor(endpoint.component), 0) != null
            }.getOrDefault(false)
        }

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

    private fun intentFor(component: ComponentName): Intent {
        return Intent(ACTION_MEDIA_SERVICE).setComponent(component)
    }

    private fun liveTargets(): List<MediaTarget> {
        val injected = ArrayList<MediaTarget>()
        dimServiceForTest?.let { injected.add(MediaTarget("test", it)) }
        extraDimServicesForTest.forEachIndexed { index, service ->
            injected.add(MediaTarget("test-$index", service))
        }
        if (injected.isNotEmpty()) return injected
        return mediaEndpoints.mapNotNull { endpoint ->
            endpoint.listener?.let { MediaTarget(endpoint.component.packageName, it) }
        }
    }

    private fun liveListeners(): List<IDimTransListener> = liveTargets().map { it.service }

    private fun usingInjectedService(): Boolean {
        return dimServiceForTest != null || extraDimServicesForTest.isNotEmpty()
    }

    private fun endpointRoster(): String {
        return mediaEndpoints.joinToString(" ") { endpoint ->
            "${endpoint.component.packageName}:bound=${endpoint.bound}:listener=${endpoint.listener != null}"
        }
    }

    private fun dropListener(service: IDimTransListener) {
        if (service === dimServiceForTest || service in extraDimServicesForTest) return
        mediaEndpoints.filter { it.listener === service }.forEach { endpoint ->
            endpoint.binder?.let { binder -> runCatching { binder.unlinkToDeath(endpoint.death, 0) } }
            endpoint.binder = null
            endpoint.listener = null
        }
    }

    /**
     * 对每一份已连上的 MediaService 调一次。
     * 返回 true 表示至少一份调用没有抛异常。
     */
    private fun eachMediaService(
        onMissing: () -> Unit = { safeBind() },
        block: (label: String, service: IDimTransListener) -> Unit,
    ): Boolean {
        val targets = liveTargets()
        if (targets.isEmpty()) {
            if (!usingInjectedService()) {
                DiagLog.i(TAG, "MediaService not connected: ${endpointRoster()}")
            }
            onMissing()
            return false
        }
        var delivered = false
        for (target in targets) {
            try {
                block(target.label, target.service)
                delivered = true
            } catch (t: Throwable) {
                DiagLog.w(TAG, "Exception calling MediaService ${target.label}", t)
                dropListener(target.service)
                safeBind()
            }
        }
        return delivered
    }

    private fun safeBind() {
        if (!isSupported) return
        for (endpoint in mediaEndpoints) {
            if (endpoint.listener != null) continue
            val intent = intentFor(endpoint.component)
            val resolvable = runCatching {
                appContext.packageManager.resolveService(intent, 0) != null
            }.getOrDefault(false)
            if (!resolvable) {
                DiagLog.i(TAG, "bindService ${endpoint.component.packageName} skipped, service not installed")
                continue
            }
            try {
                val success = appContext.bindService(intent, endpoint.connection, Context.BIND_AUTO_CREATE)
                endpoint.bound = success
                DiagLog.i(TAG, "bindService to MediaService ${endpoint.component.packageName} result=$success")
            } catch (t: Throwable) {
                DiagLog.w(TAG, "safeBind encountered exception", t)
            }
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
        // 空歌名会占住东软的发送锁，真歌名要等仪表应答后才能再发。会话刚起来时的空包不发。
        if (title.isNullOrBlank() && artist.isNullOrBlank()) return
        // 缓存里的 playing 在入队时就改成新状态，不要等补发成功才改
        lastCachedMedia = CachedMedia(title, artist, album, isPlaying, artworkBitmap)
        val gen = currentGeneration.get()
        executor.execute {
            try {
                if (released.get() || currentGeneration.get() != gen) return@execute
                processMediaUpdate(gen, title, artist, album, isPlaying)
            } catch (t: Throwable) {
                DiagLog.e(TAG, "Error in processMediaUpdate worker", t)
            }
        }
    }

    private fun processMediaUpdate(
        gen: Int,
        title: String?,
        artist: String?,
        album: String?,
        isPlaying: Boolean,
    ) {
        val safeTitle = XingruiClusterMediaFormatter.formatText(title)
        val safeArtist = XingruiClusterMediaFormatter.formatText(artist)
        val isMetadataSame = safeTitle == lastSentTitle && safeArtist == lastSentArtist
        val isPlayingSame = isPlaying == lastSentPlaying

        // 封面不进仪表。同一首歌的封面到达不再占用东软发送锁。
        if (isMetadataSame && isPlayingSame) {
            cancelAllPending()
            return
        }

        // 歌名没变，只是播放或暂停。窗口内先记下，到点只补一笔 onAction。
        if (isMetadataSame) {
            cancelPendingDebounce()
            enqueueThrottledSend(gen, isTrackChange = false) {
                doSendActionOnly(gen, isPlaying)
            }
            return
        }

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
        doSendMediaUpdate(
            gen,
            safeTitle,
            safeArtist,
            safeAlbum,
            cached.isPlaying,
            XingruiClusterMediaFormatter.PLAYING_MEDIA_PATH,
            null,
        )
    }

    private fun replayCachedMedia(gen: Int, cached: CachedMedia) {
        if (released.get() || currentGeneration.get() != gen) return
        cancelAllPending()
        dispatchFullUpdate(gen, cached)
    }

    private fun doSendActionOnly(gen: Int, isPlaying: Boolean) {
        if (released.get() || currentGeneration.get() != gen) return
        if (liveListeners().isEmpty()) {
            safeBind()
            return
        }
        val sourceType = clusterSource()
        BTAudioGuard.ensureConnected(appContext)
        val delivered = eachMediaService { label, service ->
            service.onAction(if (isPlaying) 1 else 2, sourceType)
            DiagLog.i(TAG, "Binder returned onAction from $label source=$sourceType playing=$isPlaying")
        }
        if (!delivered) return
        if (recordActionIfCurrent(gen, isPlaying)) {
            DiagLog.i(TAG, "Sent pending onAction to Xingrui IPK (source=$sourceType, gen=$gen): isPlaying=$isPlaying")
        } else {
            DiagLog.d(TAG, "Discarded pending onAction bookkeeping because generation moved during IPC (gen=$gen)")
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
        if (liveListeners().isEmpty()) {
            safeBind()
            DiagLog.d(TAG, "MediaService not ready yet, update cached for replay")
            return
        }

        val sourceType = clusterSource()
        if (!usingInjectedService()) {
            DiagLog.i(TAG, "MediaService roster ${endpointRoster()}")
        }
        // 音源 2 在 BTAudio=Disconnected 时被整包丢掉。A2DP 一断东软就会写成 Disconnected，发出去之前改回来。
        BTAudioGuard.ensureConnected(appContext)
        val mediaPath = coverUri?.takeIf { it.startsWith("http") }
            ?: XingruiClusterMediaFormatter.PLAYING_MEDIA_PATH
        // 每次真正调用 updateMedia 之前先 onSourceChanged。东软初始状态是 -101，没调用过就整包丢弃。
        val delivered = eachMediaService { label, service ->
            DiagLog.i(TAG, "Sending to $label (source=$sourceType, gen=$gen): $safeTitle - $safeArtist (playing=$isPlaying, cover=$coverUri)")
            service.onSourceChanged(sourceType)
            service.updateMedia(0, safeTitle, safeAlbum, safeArtist, mediaPath, sourceType)
            service.onAction(if (isPlaying) 1 else 2, sourceType)
            DiagLog.i(TAG, "Binder returned from $label source=$sourceType. CAN result is the following IPK trace.")
        }
        if (!delivered) return
        if (!usingInjectedService()) {
            IpkTrace.capture()
        }
        // 调用都成功之后才记账。记账前再看代数，并且和清屏加代数放在同一把锁里。
        val token = recordFullSendIfCurrent(gen, safeTitle, safeArtist, isPlaying, coverUri, coverFingerprint)
        if (token != null) {
            DiagLog.i(TAG, "Sent to Xingrui IPK (source=$sourceType, gen=$gen, seq=$token): $safeTitle - $safeArtist (playing=$isPlaying)")
        } else {
            DiagLog.d(TAG, "Discarded cluster send bookkeeping because generation moved during IPC (gen=$gen)")
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
                if (liveListeners().isEmpty()) return@execute
                val sourceType = clusterSource()
                BTAudioGuard.ensureConnected(appContext)
                eachMediaService(onMissing = {}) { label, service ->
                    service.onSourceChanged(sourceType)
                    service.updateMedia(0, " ", " ", " ", "", sourceType)
                    service.onAction(2, sourceType)
                    DiagLog.i(TAG, "Cleared Xingrui IPK media from $label (source=$sourceType, gen=$gen)")
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
                        if (liveListeners().isNotEmpty()) {
                            val sourceType = clusterSource()
                            BTAudioGuard.ensureConnected(appContext)
                            eachMediaService(onMissing = {}) { label, service ->
                                service.onSourceChanged(sourceType)
                                service.updateMedia(0, " ", " ", " ", "", sourceType)
                                service.onAction(2, sourceType)
                                DiagLog.i(TAG, "Cleared Xingrui IPK media on release from $label (source=$sourceType, gen=$gen)")
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
        for (endpoint in mediaEndpoints) {
            endpoint.binder?.let { binder -> runCatching { binder.unlinkToDeath(endpoint.death, 0) } }
            endpoint.binder = null
            endpoint.listener = null
            if (endpoint.bound) {
                endpoint.bound = false
                runCatching { appContext.unbindService(endpoint.connection) }
            }
        }
        executor.shutdown()
        DiagLog.i(TAG, "Released XingruiInstrumentBridge successfully")
    }

    internal var dimServiceForTest: IDimTransListener? = null
    internal var extraDimServicesForTest: List<IDimTransListener> = emptyList()
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

        private const val MIN_CAN_INTERVAL_MS = 2000L
        private const val MAX_CAN_TEXT_LENGTH = 31

        private const val PKG_INSTRUMENT_SERVER = "com.neusoft.shockwave.instrumentserver"
        private const val PKG_EXTRA_SERVICE = "com.neusoft.extraservice"
        private const val CLS_MEDIA_SERVICE = "com.ecarx.xui.adaptapi.diminteraction.MediaService"
        private const val ACTION_MEDIA_SERVICE = "com.ecarx.xui.adaptapi.diminteraction.MediaService"

        private const val ACTION_AUDIO_REQUIRE = "com.neusoft.optimus.mmi.AudioRequireAction"
        private const val EXTRA_BROADCAST_ACTION = "broadcastAction"
        private const val ACTION_GET_PLAY_INFO = 3
    }

    /** 仪表媒体卡认 CAN 值 5，也就是蓝牙音源 2。存过的 0 和 6 会在读取时迁回 2。 */
    private fun clusterSource(): Int = AirPlayPersistence.loadClusterMediaSource(appContext)

    private data class MediaTarget(val label: String, val service: IDimTransListener)

    private class BoundMediaService(val component: ComponentName) {
        var binder: IBinder? = null
        var listener: IDimTransListener? = null
        var bound: Boolean = false
        lateinit var connection: ServiceConnection
        lateinit var death: IBinder.DeathRecipient
    }
}
