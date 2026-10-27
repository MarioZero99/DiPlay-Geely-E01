package com.shilapi.xcertplay

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.RemoteControlClient
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.RequiresApi
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.instrument.XingruiInstrumentBridge
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * The wheel, with equivalent API 19 and API 21+ backends.
 *
 * What counts as a wheel key is the driver's table in [SteeringWheelKeyMappings] and nothing else --
 * this head unit's wheel also carries the car's own keys, and a press DiPlay has no answer for has to
 * reach the head unit.
 */
internal object CarPlayMediaKeys {
    private const val TAG = "DiPlay-MediaKeys"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val backend: Backend by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) Api21Backend() else LegacyBackend()
    }

    fun attach(context: Context, controller: CarPlayController) = backend.attach(context, controller)

    fun detach(expected: CarPlayController?) {
        backend.detach(expected)
        audioFocusTarget = null
    }

    fun onMediaAudioChanged(active: Boolean) = mainHandler.post { backend.update(active) }

    fun onClusterMetadataEnabledChanged(enabled: Boolean) = mainHandler.post {
        backend.onClusterMetadataEnabledChanged(enabled)
    }

    /** Receives the same focus callbacks as the media-button session. Cleared by [detach]. */
    fun bindAudioFocusTarget(target: ((Int) -> Unit)?) {
        audioFocusTarget = target
    }

    fun onIphonePlaying(playing: Boolean) {
        if (playing) mainHandler.post { backend.regainFocus() }
    }

    internal fun dispatch(event: KeyEvent): Boolean = backend.dispatch(event)

    internal fun metadataChanged(previous: CarPlayNowPlaying?, next: CarPlayNowPlaying?): Boolean {
        if (previous === next) return false
        if (previous == null) return next != null
        if (next == null) return true
        return previous.title != next.title ||
            previous.artist != next.artist ||
            previous.album != next.album ||
            previous.sourceApp != next.sourceApp ||
            previous.durationMillis != next.durationMillis ||
            previous.artworkTransferId != next.artworkTransferId
    }

    internal fun decodeArtwork(bytes: ByteArray, maxDim: Int = 384): Bitmap? {
        if (bytes.isEmpty()) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val width = bounds.outWidth
            val height = bounds.outHeight
            if (width <= 0 || height <= 0) return null
            var sampleSize = 1
            var w = width
            var h = height
            while (w > maxDim || h > maxDim) {
                w /= 2
                h /= 2
                sampleSize *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (t: Throwable) {
            DiagLog.w(TAG, "decode artwork failed: size=${bytes.size}", t)
            null
        }
    }

    internal fun buildMediaMetadata(nowPlaying: CarPlayNowPlaying, artwork: Bitmap?): MediaMetadata? {
        val hasContent = nowPlaying.title != null || nowPlaying.artist != null || nowPlaying.album != null || artwork != null
        if (!hasContent) return null
        val builder = MediaMetadata.Builder()
        nowPlaying.title?.let {
            builder.putString(MediaMetadata.METADATA_KEY_TITLE, it)
            builder.putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, it)
        }
        nowPlaying.artist?.let {
            builder.putString(MediaMetadata.METADATA_KEY_ARTIST, it)
            builder.putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, it)
        }
        nowPlaying.album?.let {
            builder.putString(MediaMetadata.METADATA_KEY_ALBUM, it)
            builder.putString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION, it)
        }
        nowPlaying.durationMillis?.let { duration ->
            if (duration > 0) {
                builder.putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
            }
        }
        if (artwork != null && !artwork.isRecycled) {
            builder.putBitmap(MediaMetadata.METADATA_KEY_ART, artwork)
            builder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork)
            builder.putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, artwork)
        }
        return builder.build()
    }

    internal data class FilteredTrack(
        val info: CarPlayNowPlaying,
        val isLyric: Boolean,
        val genuineTitle: String?,
        val genuineArtist: String?,
        val genuineAlbum: String?,
        val durationMillis: Long?,
    )

    internal fun filterLyricMetadata(
        info: CarPlayNowPlaying,
        lastGenuineTitle: String?,
        lastGenuineArtist: String?,
        lastGenuineAlbum: String?,
        lastDurationMillis: Long?,
    ): FilteredTrack {
        val rawTitle = info.title
        val rawArtist = info.artist
        val rawAlbum = info.album
        val rawDuration = info.durationMillis

        // 1. 还没有上一首真歌名：这包就是第一首，放行。
        if (lastGenuineTitle.isNullOrBlank()) {
            val genTitle = if (!rawTitle.isNullOrBlank()) rawTitle else null
            val genArtist = if (!rawArtist.isNullOrBlank()) rawArtist else null
            val genAlbum = if (!rawAlbum.isNullOrBlank()) rawAlbum else null
            val genDuration = if (rawDuration != null && rawDuration > 0) rawDuration else null
            return FilteredTrack(
                info = info,
                isLyric = false,
                genuineTitle = genTitle,
                genuineArtist = genArtist,
                genuineAlbum = genAlbum,
                durationMillis = genDuration,
            )
        }

        // 2. 命中下面三种之一：当成歌词。歌名、歌手、专辑、时长都保持上一首，不更新。
        //    进度是 0、进度为空、总时长变了，也不能放行。
        //    - 歌手等于上一首真歌名。
        //    - 歌名以 " - " 加上一首真歌名结尾。不要用 contains。
        //    - 歌名以这些前缀开头，中文冒号和英文冒号都算：词： 曲： 编曲： 制作： 制作人：
        val isLyricSwap = rawArtist == lastGenuineTitle
        val isLyricSuffix = !rawTitle.isNullOrBlank() && rawTitle.endsWith(" - $lastGenuineTitle")
        val isLyricCredit = !rawTitle.isNullOrBlank() &&
            (rawTitle.startsWith("词：") || rawTitle.startsWith("词:") ||
             rawTitle.startsWith("曲：") || rawTitle.startsWith("曲:") ||
             rawTitle.startsWith("编曲：") || rawTitle.startsWith("编曲:") ||
             rawTitle.startsWith("制作：") || rawTitle.startsWith("制作:") ||
             rawTitle.startsWith("制作人：") || rawTitle.startsWith("制作人:"))

        val isLyric = isLyricSwap || isLyricSuffix || isLyricCredit

        return if (isLyric) {
            val effTitle = lastGenuineTitle
            val effArtist = lastGenuineArtist ?: (if (rawArtist != lastGenuineTitle) rawArtist else null)
            val effAlbum = lastGenuineAlbum ?: rawAlbum
            FilteredTrack(
                info = info.copy(title = effTitle, artist = effArtist, album = effAlbum),
                isLyric = true,
                genuineTitle = lastGenuineTitle,
                genuineArtist = lastGenuineArtist,
                genuineAlbum = lastGenuineAlbum,
                durationMillis = lastDurationMillis,
            )
        } else {
            // 3. 其余都是新歌，放行。包括《七里香》后面的《七里香 (Live)》、时长相同的下一首、进度停在中途、进度为空。
            val genTitle = if (!rawTitle.isNullOrBlank()) rawTitle else lastGenuineTitle
            val genArtist = if (!rawArtist.isNullOrBlank()) rawArtist else lastGenuineArtist
            val genAlbum = if (!rawAlbum.isNullOrBlank()) rawAlbum else lastGenuineAlbum
            val genDuration = if (rawDuration != null && rawDuration > 0) rawDuration else lastDurationMillis
            FilteredTrack(
                info = info,
                isLyric = false,
                genuineTitle = genTitle,
                genuineArtist = genArtist,
                genuineAlbum = genAlbum,
                durationMillis = genDuration,
            )
        }
    }

    @Volatile private var lastKnownPosition: Long = 0L

    /**
     * 【架构与生态定位】
     * 本项目定位于“专注吉利星瑞原厂极致纯净，不考虑且不兼顾任何第三方桌面 Launcher（如嘟嘟桌面、Agama 等）或悬浮小部件”。
     * 吉利星瑞全液晶仪表盘直接由东软 IPKServer（XingruiInstrumentBridge）双向 IPC 直连驱动；
     * 彻底剔除 AOSP 标准广播（com.android.music.metachanged / playstatechanged），
     * 消除车机 system_server 广播总线的无谓轮询与频繁 IPC 唤醒开销。
     */
    @Deprecated("专注原厂极致纯净，彻底剔除第三方AOSP广播", level = DeprecationLevel.WARNING)
    internal fun broadcastMediaChange(
        context: Context,
        nowPlaying: CarPlayNowPlaying?,
        playing: Boolean,
        forcedPosition: Long? = null,
    ) {
        // No-op: 彻底剔除无用 AOSP 广播，全力保障原厂 IPKServer 通道纯净与高响应
    }

    internal fun resetBroadcastState() {
        lastKnownPosition = 0L
    }

    @Volatile private var audioFocusTarget: ((Int) -> Unit)? = null

    private interface Backend {
        fun attach(context: Context, controller: CarPlayController)
        fun detach(expected: CarPlayController?)
        fun update(active: Boolean)
        fun regainFocus()
        fun dispatch(event: KeyEvent): Boolean
        fun onClusterMetadataEnabledChanged(enabled: Boolean)
    }

    private abstract class FocusBackend : Backend {
        protected var context: Context? = null
        protected var controller: CarPlayController? = null
        private var focusHeld = false
        private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
            Log.i(TAG, "audio focus change=$change")
            if (change == AudioManager.AUDIOFOCUS_LOSS) focusHeld = false
            audioFocusTarget?.invoke(change)
        }

        override fun onClusterMetadataEnabledChanged(enabled: Boolean) {}

        override fun attach(context: Context, controller: CarPlayController) {
            if (this.controller !== controller) release()
            this.context = context.applicationContext
            this.controller = controller
            controller.playbackListener = ::onPlaybackChanged
        }

        override fun detach(expected: CarPlayController?) {
            if (expected == null || controller !== expected) return
            expected.playbackListener = null
            expected.nowPlayingListener = null
            expected.artworkListener = null
            release()
        }

        override fun regainFocus() {
            if (focusHeld) return
            val audio = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            @Suppress("DEPRECATION")
            val result = audio.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            )
            focusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }

        /**
         * The wheel decision, in the order the reference makes it.
         *
         * A code the driver has not bound is not answered for -- the press goes on to the head unit,
         * which is what keeps the car's own wheel keys working. Neither is a press on a head unit
         * with no session up: DiPlay being installed must not cost the driver their wheel.
         */
        override fun dispatch(event: KeyEvent): Boolean {
            val mappings = mappings()
            if (!CarPlayMediaKeyMapper.handles(event.keyCode, mappings)) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    DiagLog.i(TAG, "steering key unmapped ${describe(event)} -- left to the head unit")
                }
                return false
            }
            if (controller == null) {
                DiagLog.i(TAG, "steering key ${describe(event)} -- no session, left to the head unit")
                return false
            }
            CarPlayMediaKeyMapper.mediaIndex(event.keyCode, event.action, event.repeatCount, mappings)?.let(::send)
            return true
        }

        /** The driver's wheel table as stored. Read per press, as the reference does. */
        private fun mappings(): SteeringWheelKeyMappings = context
            ?.let { AirPlayPersistence.loadSteeringWheelKeyMappings(it) }
            ?: SteeringWheelKeyMappings.DEFAULT

        protected fun send(index: Int) {
            val sent = controller?.sendMediaButton(index) ?: false
            DiagLog.i(TAG, "media key -> CarPlay $index sent=$sent")
        }

        /** Enough to name the key in a report: the number alone says nothing about which one it was. */
        protected fun describe(event: KeyEvent) = "keyCode=${event.keyCode} name=${KeyEvent.keyCodeToString(event.keyCode)}"

        protected open fun release() {
            val audio = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            @Suppress("DEPRECATION")
            audio?.abandonAudioFocus(focusListener)
            focusHeld = false
            controller = null
            context = null
        }

        protected open fun onPlaybackChanged(playing: Boolean) {
            if (playing) mainHandler.post { regainFocus() }
        }
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private class Api21Backend : FocusBackend() {
        private var session: MediaSession? = null
        private var sessionToken: Any? = null
        private var lastPublishedNowPlaying: CarPlayNowPlaying? = null
        private var instrumentBridge: XingruiInstrumentBridge? = null

        override fun onPlaybackChanged(playing: Boolean) {
            super.onPlaybackChanged(playing)
            mainHandler.post {
                val currentContext = context ?: return@post
                if (!AirPlayPersistence.loadClusterMediaMetadataEnabled(currentContext)) return@post
                val currentSession = session ?: return@post
                val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS
                val pos = lastKnownPosition
                currentSession.setPlaybackState(
                    PlaybackState.Builder().setActions(actions).setState(
                        if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                        pos,
                        if (playing) 1f else 0f,
                    ).build(),
                )
                instrumentBridge?.updateMedia(
                    lastPublishedNowPlaying?.title,
                    lastPublishedNowPlaying?.artist,
                    lastPublishedNowPlaying?.album,
                    playing,
                )
            }
        }
        private var currentArtwork: Bitmap? = null
        private var lastGenuineTitle: String? = null
        private var lastGenuineArtist: String? = null
        private var lastGenuineAlbum: String? = null
        private var lastDurationMillis: Long? = null

        private val artworkWorker = Executors.newSingleThreadExecutor { r ->
            Thread(r, "DiPlay-ArtworkQueue").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
        }
        private val mainExecutor = Executor { command ->
            if (Looper.myLooper() == Looper.getMainLooper()) command.run() else mainHandler.post(command)
        }
        private val artworkQueue = NowPlayingArtworkQueue<Bitmap>(
            worker = artworkWorker,
            main = mainExecutor,
            decode = { bytes -> decodeArtwork(bytes) },
            publish = { owner, id, image -> onArtworkDecoded(owner, id, image) },
            discard = { image -> if (!image.isRecycled) image.recycle() },
        )

        override fun attach(context: Context, controller: CarPlayController) {
            super.attach(context, controller)
            sessionToken = artworkQueue.newSession()
            controller.nowPlayingListener = ::onNowPlaying
            controller.artworkListener = ::onArtworkReceived
            if (instrumentBridge == null) {
                instrumentBridge = XingruiInstrumentBridge(context)
            }
        }

        private fun ensureSession(currentContext: Context): MediaSession {
            session?.let { return it }
            val mediaButtonReceiver = PendingIntent.getBroadcast(
                currentContext,
                0,
                Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(
                    ComponentName(currentContext, CarPlayMediaButtonReceiver::class.java),
                ),
                PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return MediaSession(currentContext, "DiPlay CarPlay").apply {
                setFlags(
                    MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS,
                )
                setMediaButtonReceiver(mediaButtonReceiver)
                setCallback(CarPlayMediaCallback { index, _ -> send(index) }, mainHandler)
                isActive = true
                session = this
            }
        }

        override fun update(active: Boolean) {
            val currentContext = context ?: return
            if (controller == null) return
            val enabled = AirPlayPersistence.loadClusterMediaMetadataEnabled(currentContext)
            if (!enabled) {
                session?.let {
                    if (it.isActive) {
                        clearClusterMetadata()
                    }
                }
                return
            }
            val wasNull = session == null
            val currentSession = ensureSession(currentContext)
            currentSession.isActive = true
            if (active) regainFocus()
            val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS
            val pos = lastKnownPosition
            currentSession.setPlaybackState(
                PlaybackState.Builder().setActions(actions).setState(
                    if (active) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    pos,
                    if (active) 1f else 0f,
                ).build(),
            )
            if (wasNull) {
                lastPublishedNowPlaying?.let { publishMetadata(it, currentArtwork) }
            }
        }

        private fun onNowPlaying(info: CarPlayNowPlaying) {
            mainHandler.post {
                val currentContext = context ?: return@post
                if (!AirPlayPersistence.loadClusterMediaMetadataEnabled(currentContext)) {
                    if (lastPublishedNowPlaying != null || currentArtwork != null) {
                        clearClusterMetadata()
                    }
                    return@post
                }
                val filterResult = filterLyricMetadata(
                    info,
                    lastGenuineTitle,
                    lastGenuineArtist,
                    lastGenuineAlbum,
                    lastDurationMillis,
                )
                lastGenuineTitle = filterResult.genuineTitle
                lastGenuineArtist = filterResult.genuineArtist
                lastGenuineAlbum = filterResult.genuineAlbum
                lastDurationMillis = filterResult.durationMillis
                val filteredInfo = filterResult.info

                val previous = lastPublishedNowPlaying
                val isTrackChanged = previous != null && (previous.title != filteredInfo.title || previous.artist != filteredInfo.artist)
                if (isTrackChanged) {
                    // Reset position immediately when track changes, so late elapsed time won't bleed previous song's end position
                    lastKnownPosition = filteredInfo.elapsedMillis?.takeIf { it >= 0 } ?: 0L
                } else {
                    filteredInfo.elapsedMillis?.let { if (it >= 0) lastKnownPosition = it }
                }
                if (!metadataChanged(previous, filteredInfo)) {
                    // Filter out 2Hz elapsed time or status ticks to protect system_server heap
                    return@post
                }
                val previousArtworkId = previous?.artworkTransferId
                val newArtworkId = filteredInfo.artworkTransferId
                if (newArtworkId != previousArtworkId) {
                    updateArtwork(null)
                }
                lastPublishedNowPlaying = filteredInfo
                publishMetadata(filteredInfo, currentArtwork)
            }
        }

        private fun onArtworkReceived(transferId: Int, bytes: ByteArray) {
            val currentContext = context ?: return
            if (!AirPlayPersistence.loadClusterMediaMetadataEnabled(currentContext)) return
            val token = sessionToken ?: return
            artworkQueue.submit(token, transferId, bytes)
        }

        private fun onArtworkDecoded(owner: Any, id: Int, image: Bitmap?) {
            if (owner !== sessionToken) {
                image?.let { if (!it.isRecycled) it.recycle() }
                return
            }
            val current = lastPublishedNowPlaying
            if (current == null || current.artworkTransferId != id) {
                image?.let { if (!it.isRecycled) it.recycle() }
                return
            }
            updateArtwork(image)
            publishMetadata(current, currentArtwork)
        }

        private fun updateArtwork(bmp: Bitmap?) {
            currentArtwork = bmp
        }

        override fun onClusterMetadataEnabledChanged(enabled: Boolean) {
            if (!enabled) {
                clearClusterMetadata()
            } else {
                val currentContext = context ?: return
                if (!AirPlayPersistence.loadClusterMediaMetadataEnabled(currentContext)) return
                session?.isActive = true
                controller?.currentNowPlaying?.let { info ->
                    if (info.title != null || info.artist != null || info.album != null) {
                        lastPublishedNowPlaying = info
                        publishMetadata(info, currentArtwork)
                    }
                }
            }
        }

        private fun clearClusterMetadata() {
            artworkQueue.clear()
            updateArtwork(null)
            lastPublishedNowPlaying = null
            lastKnownPosition = 0L
            session?.let { sess ->
                runCatching {
                    sess.setMetadata(null)
                    val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS
                    sess.setPlaybackState(
                        PlaybackState.Builder().setActions(actions).setState(
                            PlaybackState.STATE_STOPPED,
                            0L,
                            0f,
                        ).build(),
                    )
                    sess.isActive = false
                }.onFailure { DiagLog.w(TAG, "failed to clear cluster metadata", it) }
            }
            resetBroadcastState()
            instrumentBridge?.clearMedia()
        }

        private fun publishMetadata(nowPlaying: CarPlayNowPlaying?, artwork: Bitmap?) {
            val currentContext = context ?: return
            if (!AirPlayPersistence.loadClusterMediaMetadataEnabled(currentContext)) {
                clearClusterMetadata()
                return
            }
            val currentSession = session ?: return
            runCatching {
                val metadata = nowPlaying?.let { buildMediaMetadata(it, artwork) }
                currentSession.setMetadata(metadata)

                val isPlaying = nowPlaying?.playing ?: false
                val position = nowPlaying?.elapsedMillis?.takeIf { it >= 0 } ?: lastKnownPosition
                if (position > 0) lastKnownPosition = position
                val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS
                currentSession.setPlaybackState(
                    PlaybackState.Builder().setActions(actions).setState(
                        if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                        position,
                        if (isPlaying) 1f else 0f,
                    ).build(),
                )

                instrumentBridge?.updateMedia(
                    nowPlaying?.title,
                    nowPlaying?.artist,
                    nowPlaying?.album,
                    isPlaying,
                )
                DiagLog.d(TAG, "cluster metadata published: title=${nowPlaying?.title} artist=${nowPlaying?.artist} hasArt=${artwork != null}")
            }.onFailure { e ->
                DiagLog.w(TAG, "failed to publish cluster metadata", e)
            }
        }


        override fun release() {
            sessionToken = null
            artworkQueue.clear()
            controller?.nowPlayingListener = null
            controller?.artworkListener = null
            lastPublishedNowPlaying = null
            lastGenuineTitle = null
            lastGenuineArtist = null
            lastGenuineAlbum = null
            lastDurationMillis = null
            updateArtwork(null)
            instrumentBridge?.release()
            instrumentBridge = null
            session?.apply {
                runCatching { setMetadata(null) }
                isActive = false
                release()
            }
            session = null
            super.release()
        }
    }

    @Suppress("DEPRECATION")
    private class LegacyBackend : FocusBackend() {
        private var remote: RemoteControlClient? = null
        private var receiver: ComponentName? = null

        override fun update(active: Boolean) {
            val currentContext = context ?: return
            val audio = currentContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (remote == null) {
                receiver = ComponentName(currentContext, CarPlayMediaButtonReceiver::class.java)
                val intent = Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(receiver)
                val pending = PendingIntent.getBroadcast(currentContext, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT)
                remote = RemoteControlClient(pending).apply {
                    setTransportControlFlags(
                        RemoteControlClient.FLAG_KEY_MEDIA_PLAY or RemoteControlClient.FLAG_KEY_MEDIA_PAUSE or
                            RemoteControlClient.FLAG_KEY_MEDIA_PLAY_PAUSE or RemoteControlClient.FLAG_KEY_MEDIA_NEXT or
                            RemoteControlClient.FLAG_KEY_MEDIA_PREVIOUS,
                    )
                }
                audio.registerMediaButtonEventReceiver(receiver)
                audio.registerRemoteControlClient(remote)
            }
            if (active) regainFocus()
            remote?.setPlaybackState(
                if (active) RemoteControlClient.PLAYSTATE_PLAYING else RemoteControlClient.PLAYSTATE_PAUSED,
            )
        }

        override fun release() {
            val audio = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audio != null) {
                remote?.let { audio.unregisterRemoteControlClient(it) }
                receiver?.let { audio.unregisterMediaButtonEventReceiver(it) }
            }
            remote = null
            receiver = null
            super.release()
        }
    }
}

@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
internal class CarPlayMediaCallback(
    private val send: (Int, Bundle?) -> Unit,
) : MediaSession.Callback() {
    override fun onPlay() = send(CarPlayMediaButton.PLAY, null)
    override fun onPause() = send(CarPlayMediaButton.PAUSE, null)
    override fun onSkipToNext() = send(CarPlayMediaButton.NEXT, null)
    override fun onSkipToPrevious() = send(CarPlayMediaButton.PREVIOUS, null)

    override fun onMediaButtonEvent(intent: Intent): Boolean {
        @Suppress("DEPRECATION")
        val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
        // The same table the broadcast path reads. A press the driver has not bound is declined here
        // rather than eaten, so the head unit's own handling of that key still happens.
        if (!CarPlayMediaKeyMapper.handles(event.keyCode)) return false
        CarPlayMediaKeyMapper.mediaIndex(event.keyCode, event.action, event.repeatCount)?.let { send(it, null) }
        return true
    }
}

class CarPlayMediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        @Suppress("DEPRECATION")
        val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
        if (CarPlayMediaKeys.dispatch(event)) abortBroadcast()
    }
}
