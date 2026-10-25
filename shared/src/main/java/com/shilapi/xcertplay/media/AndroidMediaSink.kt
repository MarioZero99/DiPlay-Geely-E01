package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import androidx.annotation.RequiresApi
import com.shilapi.xcertplay.DiagLog
import com.shilapi.xcertplay.PerfTrace
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.airplay.toHexString
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit

/**
 * Android rendering backend for the CarPlay media engine. Video frames are
 * decoded with MediaCodec onto a Surface; audio streams are decoded to PCM and
 * played through AudioTrack. Call [close] when the session tears down.
 */
class AndroidMediaSink(
    surface: Surface? = null,
    private val videoWidth: Int = 1280,
    private val videoHeight: Int = 720,
    private val preferSoftwareHevcDecoder: Boolean = false,
    private val advancedAudioChannelMapping: Boolean = false,
    private val navigationStreamType: Int = NavigationAudioStream.deviceDefault,
    onScreenStreamActiveChanged: ((Int, Boolean) -> Unit)? = null,
    private val mediaBufferMillis: Int = MediaAudioBuffer.DEFAULT_MILLIS,
    private val isWireless: Boolean = false,
    private val onAudioDiagnostic: (String) -> Unit = {},
    /** True while any music ("media") audio stream is running; called from media threads. */
    private val onMediaAudioChanged: (Boolean) -> Unit = {},
) : MediaSink {
    private val screenStateLock = Any()
    private val activeScreenTypes = mutableSetOf<Int>()
    private val defaultSurface = surface
    @Volatile private var screenStreamActiveChanged = onScreenStreamActiveChanged
    private val surfaces = ConcurrentHashMap<Int, Surface>()
    private val videoDecoders = ConcurrentHashMap<Int, VideoDecoder>()
    private val mediaAudioTypes = mutableSetOf<Int>()
    private val audioRenderers = ConcurrentHashMap<Int, AudioRenderer>()
    private val compositeGain = CompositeMediaGain()
    @Volatile private var loggedMediaGain = Float.NaN
    private val microphoneUplinks = ConcurrentHashMap<Int, MicrophoneUplink>()
    private val pendingVideoCodec = ConcurrentHashMap<Int, VideoCodec>()
    private val videoRecoveryHandlers = ConcurrentHashMap<Int, () -> Unit>()
    private val videoDiagnosticHandlers = ConcurrentHashMap<Int, (String) -> Unit>()
    private val recoveryPending = AtomicBoolean(false)
    private val recoveryExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "carplay-video-recovery").apply { isDaemon = true }
    }

    override fun setVideoRecoveryHandler(type: Int, handler: () -> Unit) {
        videoRecoveryHandlers[type] = handler
    }

    override fun setVideoDiagnosticHandler(type: Int, handler: (String) -> Unit) {
        videoDiagnosticHandlers[type] = handler
    }

    private fun requestVideoRecovery(type: Int) {
        if (!recoveryPending.compareAndSet(false, true)) return
        try {
            recoveryExecutor.execute {
                try { videoRecoveryHandlers[type]?.invoke() }
                catch (error: Exception) { DiagLog.w("xcertplay-usb", "Video keyframe request failed", error) }
                finally { recoveryPending.set(false) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { recoveryPending.set(false) }
    }

    fun setSurface(type: Int, surface: Surface) {
        surfaces[type] = surface
        videoDecoders[type]?.setSurface(surface)
    }

    fun clearSurface(type: Int, surface: Surface) {
        if (surfaces.remove(type, surface)) videoDecoders[type]?.setSurface(null)
    }

    /**
     * Applies a focus change reported by the media-button session.
     * Only music tracks move; phone and navigation audio stay at full gain.
     */
    fun onExternalAudioFocus(change: Int) {
        val gain = mediaTrackGainForFocus(change) ?: return
        compositeGain.setFocusGain(gain)
        publishMediaGain("focus")
    }

    fun setScreenStreamActiveChangedListener(listener: ((Int, Boolean) -> Unit)?) {
        synchronized(screenStateLock) {
            screenStreamActiveChanged = listener
            activeScreenTypes.forEach { listener?.invoke(it, true) }
        }
    }

    override fun onVideoCodec(type: Int, codec: VideoCodec) {
        pendingVideoCodec[type] = codec
    }

    override fun onVideoConfig(type: Int, codecData: ByteArray) {
        val codec = pendingVideoCodec[type] ?: VideoCodec.H264
        videoDecoder(type).configure(codec, codecData)
    }

    override fun onVideoFrame(type: Int, naluBytes: ByteArray) {
        videoDecoder(type).submit(naluBytes)
    }

    override fun onScreenStreamActive(type: Int, active: Boolean) {
        if (!active) {
            videoRecoveryHandlers.remove(type)
            videoDiagnosticHandlers.remove(type)
            videoDecoders.remove(type)?.close()
            pendingVideoCodec.remove(type)
        }
        synchronized(screenStateLock) {
            if (active) activeScreenTypes.add(type) else activeScreenTypes.remove(type)
            screenStreamActiveChanged?.invoke(type, active)
        }
    }

    override fun onAudioStarted(type: Int, format: AudioFormat, firstSample: Int) {
        val renderer = audioRenderer(type, format)
        val isNav = AudioChannelMapper.map(
            audioType = format.audioType,
            payloadType = format.payloadType,
            mode = if (advancedAudioChannelMapping) AudioChannelMappingMode.AUTOMOTIVE_BUS else AudioChannelMappingMode.MOBILE_COMPATIBLE,
            navigationStreamType = navigationStreamType,
        ).channel == AudioChannel.NAVIGATION
        compositeGain.onStreamStarted(type, isNav)
        publishMediaGain(format.audioType)
        renderer.start()
        if (format.audioType == "media") updateMediaAudio(type, true)
    }

    override fun onAudioRtp(type: Int, format: AudioFormat, rtp: ByteArray, sample: Int) {
        audioRenderer(type, format).submit(rtp, sample)
    }

    override fun onAudioStopped(type: Int) {
        val stopped = audioRenderers.remove(type)
        val audioType = stopped?.format?.audioType ?: "unknown"
        stopped?.close()
        updateMediaAudio(type, false)
        compositeGain.onStreamStopped(type)
        publishMediaGain(audioType)
    }

    private fun updateMediaAudio(type: Int, active: Boolean) {
        val (before, after) = synchronized(mediaAudioTypes) {
            val before = mediaAudioTypes.isNotEmpty()
            if (active) mediaAudioTypes.add(type) else mediaAudioTypes.remove(type)
            before to mediaAudioTypes.isNotEmpty()
        }
        if (before != after) onMediaAudioChanged(after)
    }

    override fun onMicrophoneStarted(type: Int, config: MicrophoneConfig) {
        val uplink = microphoneUplinks.computeIfAbsent(type) { MicrophoneUplink(config) }
        if (!uplink.start()) microphoneUplinks.remove(type, uplink)
    }

    override fun onMicrophoneStopped(type: Int) {
        microphoneUplinks.remove(type)?.close()
    }

    fun close() {
        synchronized(screenStateLock) {
            activeScreenTypes.forEach { screenStreamActiveChanged?.invoke(it, false) }
            activeScreenTypes.clear()
            screenStreamActiveChanged = null
        }
        videoDecoders.values.forEach(VideoDecoder::close)
        videoDecoders.clear()
        videoRecoveryHandlers.clear()
        videoDiagnosticHandlers.clear()
        recoveryExecutor.shutdownNow()
        audioRenderers.values.forEach(AudioRenderer::close)
        audioRenderers.clear()
        val hadMedia = synchronized(mediaAudioTypes) { mediaAudioTypes.isNotEmpty().also { mediaAudioTypes.clear() } }
        if (hadMedia) onMediaAudioChanged(false)
        microphoneUplinks.values.forEach(MicrophoneUplink::close)
        microphoneUplinks.clear()
    }

    private fun videoDecoder(type: Int): VideoDecoder =
        videoDecoders.computeIfAbsent(type) {
            VideoDecoder(
                type,
                surfaces[type] ?: defaultSurface,
                videoWidth,
                videoHeight,
                preferSoftwareHevcDecoder,
                requestKeyFrame = { requestVideoRecovery(type) },
                report = { videoDiagnosticHandlers[type]?.invoke(it) },
            )
        }

    @Synchronized
    private fun audioRenderer(type: Int, format: AudioFormat): AudioRenderer {
        val existing = audioRenderers[type]
        if (existing?.format == format) return existing
        existing?.close()
        return AudioRenderer(
            format = format,
            advancedAudioChannelMapping = advancedAudioChannelMapping,
            navigationStreamType = navigationStreamType,
            mediaBufferMillis = mediaBufferMillis,
            isWireless = isWireless,
            report = onAudioDiagnostic,
        ).also {
            audioRenderers[type] = it
            if (format.audioType == "media") it.applyMediaGain(compositeGain.effectiveGain())
        }
    }

    /** Pushes the current music gain to media tracks and logs when that gain changes. */
    private fun publishMediaGain(audioType: String) {
        val state = compositeGain.state()
        audioRenderers.values.filter { it.format.audioType == "media" }.forEach {
            it.applyMediaGain(state.effective)
        }
        if (state.effective == loggedMediaGain) return
        loggedMediaGain = state.effective
        DiagLog.i(
            "DiPlay-AudioFocus",
            "media effective=${state.effective} focus=${state.focus} nav=${state.navigationStreams} " +
                "audioType=$audioType",
        )
    }
}

/** Serial MediaCodec video decoder: one worker owns configure and frame feeding. */
private class VideoDecoder(
    streamType: Int,
    surface: Surface?,
    private val width: Int,
    private val height: Int,
    private val preferSoftwareHevcDecoder: Boolean,
    private val requestKeyFrame: () -> Unit,
    private val report: (String) -> Unit,
) : Closeable {
    private val queue = VideoDecodeQueue()
    @Volatile private var running = true
    @Volatile private var decoder: MediaCodec? = null
    private var outputSurface: Surface? = surface
    private var lastConfig: VideoJob.Config? = null
    private var renderedFrameLogged = false
    private var submittedFrameLogged = false
    private var firstFrameMarked = false
    private var firstInputMarked = false
    private var duplicateConfigLogged = false
    private val referenceChain = VideoReferenceChain()
    private var lastKeyFrameRequestNs = 0L
    // The main screen keeps the historical log format; other screens are labelled.
    private val stats = VideoStats(if (streamType == 110) "" else " stream=$streamType")
    private val decodeSeries = if (streamType == 110) "video/decode" else "video/decode[$streamType]"
    /** Kept from the constructor argument, which is not a property and cannot be read from a method. */
    private val streamLabel = if (streamType == 110) "" else "[$streamType]"
    private val videoBufferInfo = MediaCodec.BufferInfo()
    private val thread = Thread(::run, "carplay-video").apply { isDaemon = true; start() }

    fun configure(codec: VideoCodec, codecData: ByteArray) {
        queue.offer(VideoJob.Config(codec, codecData))
    }

    fun submit(nalus: ByteArray) {
        stats.onReceived(nalus.size)
        queue.offer(VideoJob.Frame(nalus))
    }

    fun setSurface(surface: Surface?) {
        queue.offer(VideoJob.SurfaceChanged(surface))
    }

    override fun close() {
        running = false
        thread.interrupt()
    }

    private fun run() {
        try {
            while (running) {
                val job = queue.poll(5)
                try {
                    when (job) {
                        is VideoJob.Config -> configureDecoder(job)
                        is VideoJob.Frame -> {
                            if (System.nanoTime() - job.receivedNs > MAX_FRAME_AGE_NS) {
                                queue.discardFrames()
                                recover("video backlog exceeded 250 ms")
                            } else feed(job.nalus)
                        }
                        is VideoJob.SurfaceChanged -> changeSurface(job.surface)
                        is VideoJob.Resync -> recover("video queue overflow")
                        null -> Unit
                    }
                    decoder?.let(::drainOutput)
                    stats.logIfDue()?.let(report)
                    if (referenceChain.needsKeyFrame && lastConfig != null && outputSurface != null) requestKeyFrameIfDue()
                } catch (error: Exception) {
                    if (running) DiagLog.e(TAG, "video decoder job failed: ${job?.javaClass?.simpleName}", error)
                    if (running) report("decoder error ${error.javaClass.simpleName}; waiting for keyframe")
                    releaseDecoder()
                    referenceChain.reset()
                    requestKeyFrameIfDue()
                }
            }
        } catch (_: InterruptedException) {
            // Worker shut down.
        } finally {
            releaseDecoder()
        }
    }

    private fun configureDecoder(config: VideoJob.Config) {
        val mime = if (config.codec == VideoCodec.H265) MediaFormat.MIMETYPE_VIDEO_HEVC
        else MediaFormat.MIMETYPE_VIDEO_AVC
        val previous = lastConfig
        if (
            decoder != null &&
            previous?.codec == config.codec &&
            previous.codecData.contentEquals(config.codecData)
        ) {
            if (!duplicateConfigLogged) {
                duplicateConfigLogged = true
                DiagLog.i(TAG, "video decoder config unchanged; keeping existing decoder")
            }
            // The decoder is up, which is exactly what this diagnostic reports. Saying nothing here
            // starved a caller that gates on it: it waited out its whole timeout for a decoder it
            // already had, and the only evidence was a log line it never reads.
            report("decoder=${decoder?.name} mime=$mime size=${width}x$height")
            return
        }
        lastConfig = config
        duplicateConfigLogged = false
        releaseDecoder()
        referenceChain.reset()
        val surface = outputSurface ?: run {
            report("decoder unavailable mime=$mime size=${width}x$height: no output surface")
            return
        }
        val codec = config.codec
        val codecData = config.codecData
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
        }
        if (codec == VideoCodec.H265) {
            val csd = MediaCodecSupport.hevcCodecSpecificData(codecData)
            if (csd.isNotEmpty()) format.setByteBuffer("csd-0", ByteBuffer.wrap(csd))
        } else {
            val (sps, pps) = MediaCodecSupport.avcParameterSets(codecData)
            if (sps.isNotEmpty()) format.setByteBuffer("csd-0", ByteBuffer.wrap(START_CODE + sps))
            if (pps.isNotEmpty()) format.setByteBuffer("csd-1", ByteBuffer.wrap(START_CODE + pps))
        }
        var candidate: MediaCodec? = null
        val next = try {
            createDecoder(mime).also {
                candidate = it
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    it.codecInfo.getCapabilitiesForType(mime).isFeatureSupported("low-latency")) {
                    format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                it.configure(format, surface, null, 0)
                it.start()
            }
        } catch (error: Exception) {
            runCatching { candidate?.release() }
            DiagLog.e(TAG, "video decoder configure failed mime=$mime size=${width}x$height", error)
            report("decoder configuration failed mime=$mime size=${width}x$height error=${error.javaClass.simpleName}")
            null
        }
        decoder = next
        renderedFrameLogged = false
        submittedFrameLogged = false
        if (next != null) {
            report("decoder=${next.name} mime=$mime size=${width}x$height")
            DiagLog.i(
                TAG,
                "video decoder configured name=${next.name} mime=$mime size=${width}x$height",
            )
        }
    }

    private fun createDecoder(mime: String): MediaCodec {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            mime == MediaFormat.MIMETYPE_VIDEO_HEVC &&
            preferSoftwareHevcDecoder
        ) {
            val software = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
                !it.isEncoder && it.isSoftwareOnly && mime in it.supportedTypes
            }
            if (software != null) {
                try {
                    return MediaCodec.createByCodecName(software.name)
                } catch (error: Exception) {
                    DiagLog.w(TAG, "software HEVC decoder unavailable name=${software.name}", error)
                }
            }
        }
        return MediaCodec.createDecoderByType(mime)
    }

    private fun changeSurface(surface: Surface?) {
        if (outputSurface === surface) return
        outputSurface = surface
        if (surface == null) {
            releaseDecoder()
            DiagLog.i(TAG, "video decoder detached from surface")
            return
        }
        val codec = decoder
        if (codec != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                codec.setOutputSurface(surface)
                DiagLog.i(TAG, "video decoder output surface updated")
                return
            } catch (error: Exception) {
                DiagLog.w(TAG, "video decoder output surface update failed; reconfiguring", error)
            }
        }
        releaseDecoder()
        lastConfig?.let(::configureDecoder)
    }

    private fun feed(nalus: ByteArray) {
        val config = lastConfig ?: return
        if (outputSurface == null) return
        if (nalus.isEmpty()) { recover("invalid video access unit"); return }
        if (!referenceChain.accepts(nalus, config.codec)) {
            requestKeyFrameIfDue()
            return
        }
        if (decoder == null) configureDecoder(config)
        val codec = decoder ?: return
        if (!submittedFrameLogged) {
            submittedFrameLogged = true
            // Once per session, not once per decoder: this is the "the phone's picture has reached
            // the decoder" point, and a recovery must not restamp it as if the run had just started.
            if (!firstInputMarked) {
                firstInputMarked = true
                PerfTrace.mark("video", videoMarkName("first-input"))
            }
            DiagLog.i(
                TAG,
                "video decoder first input avcc=${nalus.size} " +
                    "head=${nalus.take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }}",
            )
        }
        val index = VideoInputPump.acquire(
            running = { running }, drain = { drainOutput(codec) },
            dequeue = { codec.dequeueInputBuffer(INPUT_TIMEOUT_US) },
        )
        if (index < 0) { recover("video decoder input stalled"); return }
        @Suppress("DEPRECATION")
        val input = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) codec.getInputBuffer(index) else codec.inputBuffers[index]
        checkNotNull(input) { "Decoder input buffer unavailable" }
        input.clear()
        val written = MediaCodecSupport.writeAnnexB(nalus, input)
        if (written > 0) {
            codec.queueInputBuffer(index, 0, written, System.nanoTime() / 1000, 0)
            referenceChain.onQueued()
        } else if (written == 0) {
            recover("invalid video access unit")
            return
        } else {
            recover("video frame exceeded codec input capacity")
            return
        }
        drainOutput(codec)
    }

    private fun recover(reason: String) {
        DiagLog.w(TAG, "Video recovery: $reason; waiting for keyframe")
        stats.onRecovery()
        report("recovery: $reason; waiting for keyframe")
        // Recreate with codec-specific data: flush can discard CSD before the first output.
        releaseDecoder()
        referenceChain.reset()
        requestKeyFrameIfDue()
    }

    private fun requestKeyFrameIfDue() {
        val now = System.nanoTime()
        if (lastKeyFrameRequestNs != 0L && now - lastKeyFrameRequestNs < 1_000_000_000L) return
        lastKeyFrameRequestNs = now
        requestKeyFrame()
    }

    /**
     * Queue-to-picture latency, one sample per rendered frame.
     *
     * The input timestamp is the moment the frame was handed to the codec, so the difference is the
     * half of the delay the head unit owns — decoder queueing plus the release that hands the buffer to
     * the surface. The network half is already in [VideoStats] as `rx` and `maxGap`, and keeping them
     * apart is what says whether a late picture is the radio or the decoder.
     */
    private fun recordDecodeLatency(presentationTimeUs: Long, nowNs: Long) {
        if (presentationTimeUs <= 0L) return
        val latencyMs = (nowNs - presentationTimeUs * 1_000L) / 1_000_000L
        // Negative means the clocks disagree; minutes mean the timestamp is not a timestamp. Either
        // would poison the p95 the report is supposed to be trusted on.
        if (latencyMs in 0L..10_000L) PerfTrace.record(decodeSeries, latencyMs, warnAboveMs = 120)
    }

    private fun videoMarkName(suffix: String): String = "video/$suffix$streamLabel"

    private fun drainOutput(codec: MediaCodec) {
        val info = videoBufferInfo
        while (running) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> logOutputFormat(codec.outputFormat)
                index >= 0 -> {
                    val render = outputSurface != null
                    val nowNs = System.nanoTime()
                    codec.releaseOutputBuffer(index, render)
                    if (render) {
                        stats.onRendered(nowNs)
                        recordDecodeLatency(info.presentationTimeUs, nowNs)
                    }
                    if (render && !renderedFrameLogged) {
                        renderedFrameLogged = true
                        if (!firstFrameMarked) {
                            firstFrameMarked = true
                            PerfTrace.mark("video", videoMarkName("first-frame"))
                        }
                        report("first frame rendered")
                        DiagLog.i(TAG, "video decoder rendered first frame bytes=${info.size}")
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    private fun logOutputFormat(format: MediaFormat) {
        report("output format requested=${width}x${height} " +
            "coded=${format.intOrNull(MediaFormat.KEY_WIDTH)}x${format.intOrNull(MediaFormat.KEY_HEIGHT)} " +
            "crop=${format.intOrNull("crop-left")},${format.intOrNull("crop-top")}," +
            "${format.intOrNull("crop-right")},${format.intOrNull("crop-bottom")} " +
            "stride=${format.intOrNull(MediaFormat.KEY_STRIDE)} slice=${format.intOrNull(MediaFormat.KEY_SLICE_HEIGHT)} " +
            "color=${format.intOrNull(MediaFormat.KEY_COLOR_STANDARD)}/${format.intOrNull(MediaFormat.KEY_COLOR_RANGE)}/${format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)}")
        DiagLog.i(
            TAG,
            "video decoder output format " +
                "size=${format.intOrNull(MediaFormat.KEY_WIDTH)}x" +
                "${format.intOrNull(MediaFormat.KEY_HEIGHT)} " +
                "stride=${format.intOrNull(MediaFormat.KEY_STRIDE)} " +
                "slice=${format.intOrNull(MediaFormat.KEY_SLICE_HEIGHT)} " +
                "standard=${format.intOrNull(MediaFormat.KEY_COLOR_STANDARD)} " +
                "range=${format.intOrNull(MediaFormat.KEY_COLOR_RANGE)} " +
                "transfer=${format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)}",
        )
    }

    @Synchronized
    private fun releaseDecoder() {
        val codec = decoder
        decoder = null
        if (codec != null) {
            try {
                codec.stop()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                codec.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val MAX_INPUT_SIZE = 8 * 1024 * 1024
        const val INPUT_TIMEOUT_US = 10_000L
        const val MAX_FRAME_AGE_NS = 250_000_000L
        val START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)
    }
}

private fun MediaFormat.intOrNull(key: String): Int? =
    if (!containsKey(key)) {
        null
    } else {
        try {
            getInteger(key)
        } catch (_: Exception) {
            null
        }
    }

/** Decodes AAC-LC/Opus to PCM and plays it, or plays wired LPCM directly. */
private class AudioRenderer(
    val format: AudioFormat,
    private val advancedAudioChannelMapping: Boolean,
    private val navigationStreamType: Int,
    private val mediaBufferMillis: Int,
    private val isWireless: Boolean,
    private val report: (String) -> Unit,
) : Closeable {
    private data class AudioPacket(val rtp: ByteArray, val sample: Int)

    private val queue = LinkedBlockingQueue<AudioPacket>(MAX_QUEUED_PACKETS)
    @Volatile private var running = true
    @Volatile private var started = false
    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var pcm = ByteArray(64 * 1024)
    private var playbackStarted = false
    private var prebufferBytes = 0
    private var startThresholdBytes = 0
    private var fadeApplied = false
    private val isWirelessMedia = isWireless && format.audioType == "media"
    private val jitterBuffer = WirelessHoldJitterBuffer(isWireless, format.audioType, format.sampleRate)
    private var gateOpened = false
    private var snapshotLastRx = 0L
    private var snapshotBufferedMs = 0L
    private var snapshotTrackMs = 0L
    private var gateWaitMarkNs = 0L
    private val firstArrivalNs = AtomicLong()
    private val fadeByteCount = PcmSmoother.fadeByteCount(format.sampleRate, format.channels)
    private val stagingBuffer = ByteArray(fadeByteCount)
    private var stagedBytes = 0
    private var droppedPacketsLogged = false
    private var firstAacPayloadLogged = false
    private var firstOpusShortPacketLogged = false
    private var firstInputQueuedLogged = false
    private var inputQueued = 0
    private var inputDropped = 0
    private var outputBuffers = 0
    private var firstPcmLogged = false
    private val packetsReceived = AtomicInteger()
    private val packetsDropped = AtomicInteger()
    private val lastArrivalNs = AtomicLong()
    private val maxArrivalGapMs = AtomicLong()
    private var maxWriteMs = 0L
    private var statsWindowStartNs = 0L
    private var statsLastUnderruns = 0
    private var bytesPerSecond = 0
    private val bufferProgress = AudioBufferProgress(if (format.channels >= 2) 4 else 2)
    private var underrunsAtPlaybackStart = 0
    private var lastPcmWriteNs = 0L
    private var rebufferCount = 0
    private var drainCounted = false
    private val gainLock = Any()
    @Volatile private var mediaGain = 1f
    private val audioBufferInfo = MediaCodec.BufferInfo()
    private val thread = Thread(::run, "carplay-audio").apply { isDaemon = true }

    /** Music tracks only. The gain is kept if the [AudioTrack] does not exist yet. */
    fun applyMediaGain(gain: Float) {
        if (format.audioType != "media") return
        synchronized(gainLock) {
            mediaGain = gain
            track?.let { setTrackGain(it, gain) }
        }
    }

    fun start() {
        if (started) return
        started = true
        thread.start()
    }

    fun submit(rtp: ByteArray, sample: Int) {
        if (started) {
            packetsReceived.incrementAndGet()
            val now = System.nanoTime()
            firstArrivalNs.compareAndSet(0L, now)
            val previous = lastArrivalNs.getAndSet(now)
            if (previous != 0L) maxArrivalGapMs.accumulateAndGet((now - previous) / 1_000_000L, ::maxOf)
        }
        if (!started || !queue.offer(AudioPacket(rtp, sample))) {
            if (started) packetsDropped.incrementAndGet()
            if (started && !droppedPacketsLogged) {
                droppedPacketsLogged = true
                DiagLog.w(TAG, "audio queue full; dropping newest packets to bound latency")
                report("Audio: queue full audioType=${format.audioType}")
            }
        }
    }

    override fun close() {
        running = false
        thread.interrupt()
    }

    private fun run() {
        try {
            when (format.codec) {
                AudioCodecKind.AAC_LC -> configureCodec(MediaFormat.MIMETYPE_AUDIO_AAC)
                AudioCodecKind.OPUS -> configureCodec(MediaFormat.MIMETYPE_AUDIO_OPUS)
                AudioCodecKind.LPCM -> Unit
            }
            createTrack()
            while (running) {
                if (isWirelessMedia && !playbackStarted && !gateOpened) {
                    // A brake stores its own mark. Cold start waits until the first packet so the
                    // clock is not the moment the thread began.
                    if (gateWaitMarkNs == 0L) {
                        val first = firstArrivalNs.get()
                        if (first != 0L) gateWaitMarkNs = first
                    }
                    if (!jitterBuffer.isReadyToDrain(queue.size)) {
                        Thread.sleep(AUDIO_POLL_MILLIS)
                        maintainPlaybackBuffer()
                        logStatsIfDue()
                        continue
                    }
                    val mark = gateWaitMarkNs
                    val holdMs = if (mark == 0L) -1L else (System.nanoTime() - mark) / 1_000_000L
                    val queued = queue.size
                    val target = jitterBuffer.targetHoldPackets()
                    gateOpened = true
                    gateWaitMarkNs = 0L
                    DiagLog.i(
                        TAG,
                        "audio gate opened audioType=${format.audioType} rate=${format.sampleRate} " +
                            "target=$target queue=$queued holdMs=$holdMs",
                    )
                }
                queue.poll(AUDIO_POLL_MILLIS, TimeUnit.MILLISECONDS)?.let(::handle)
                // Output becomes ready asynchronously, including after the last packet of a burst.
                // Waiting for the next UDP packet can strand decoded sound for hundreds of ms.
                codec?.let(::drainCodec)
                maintainPlaybackBuffer()
                logStatsIfDue()
            }
        } catch (_: InterruptedException) {
            // Worker shut down.
        } catch (error: Exception) {
            if (running) {
                DiagLog.e(TAG, "audio renderer worker failed", error)
                report("Audio: renderer failed audioType=${format.audioType} error=${error.javaClass.simpleName}")
            }
        } finally {
            runCatching { logStatsIfDue(force = true) }
            release()
        }
    }

    private fun configureCodec(mime: String) {
        val mediaFormat = MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, mime)
            setInteger(MediaFormat.KEY_SAMPLE_RATE, format.sampleRate)
            setInteger(MediaFormat.KEY_CHANNEL_COUNT, format.channels)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                setInteger(MediaFormat.KEY_IS_ADTS, 1)
                setByteBuffer("csd-0", ByteBuffer.wrap(aacAudioSpecificConfig()))
            } else {
                setByteBuffer("csd-0", ByteBuffer.wrap(opusHead()))
                setByteBuffer("csd-1", ByteBuffer.wrap(opusCodecDelay()))
                setByteBuffer("csd-2", ByteBuffer.wrap(opusSeekPreRoll()))
            }
        }
        if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
            DiagLog.i(
                TAG,
                "audio AAC config rate=${format.sampleRate} channels=${format.channels} " +
                    "csd0=${aacAudioSpecificConfig().toHexString()}",
            )
        }
        codec = try {
            MediaCodec.createDecoderByType(mime).also {
                it.configure(mediaFormat, null, null, 0)
                it.start()
                DiagLog.i(TAG, "audio decoder configured mime=$mime name=${it.name}")
            }
        } catch (error: Exception) {
            DiagLog.e(TAG, "audio decoder configuration failed mime=$mime", error)
            null
        }
    }

    private fun createTrack() {
        val encoding = AndroidAudioFormat.ENCODING_PCM_16BIT
        val channelMask = if (format.channels >= 2) AndroidAudioFormat.CHANNEL_OUT_STEREO
        else AndroidAudioFormat.CHANNEL_OUT_MONO
        val minBuffer = AudioTrack.getMinBufferSize(format.sampleRate, channelMask, encoding)
        if (minBuffer <= 0) {
            DiagLog.e(TAG, "AudioTrack buffer size unavailable rate=${format.sampleRate} channels=${format.channels}")
            return
        }
        val plan = MediaAudioBuffer.plan(format.audioType, format.sampleRate, format.channels, minBuffer, mediaBufferMillis)
        val frameBytes = if (format.channels >= 2) 4 else 2
        bytesPerSecond = format.sampleRate * frameBytes
        val built: AudioTrack
        var routeLabel: String
        if (!AudioChannelMapper.usesNavigationStream(format.audioType, format.payloadType, advancedAudioChannelMapping)) {
            routeLabel = "usage"
            built = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioTrack.Builder()
                    .setAudioAttributes(audioAttributes())
                    .setAudioFormat(pcmFormat(encoding, channelMask))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(plan.trackBufferBytes)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(AudioManager.STREAM_MUSIC, format.sampleRate, channelMask, encoding,
                    plan.trackBufferBytes, AudioTrack.MODE_STREAM)
            }
        } else {
            val streamType = streamType()
            routeLabel = "streamType=$streamType"
            built = LegacyAudioFallback.build(
                createLegacy = {
                    AudioTrack(streamType, format.sampleRate, channelMask, encoding,
                        plan.trackBufferBytes, AudioTrack.MODE_STREAM)
                },
                isInitialized = { it.state == AudioTrack.STATE_INITIALIZED },
                release = { it.release() },
                createFallback = {
                    routeLabel = "streamType=$streamType(fallback=usage)"
                    DiagLog.w(TAG, "streamType=$streamType rejected by this ROM; falling back to usage-based track")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        AudioTrack.Builder()
                            .setAudioAttributes(audioAttributes())
                            .setAudioFormat(pcmFormat(encoding, channelMask))
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .setBufferSizeInBytes(plan.trackBufferBytes)
                            .build()
                    } else {
                        @Suppress("DEPRECATION")
                        AudioTrack(AudioManager.STREAM_MUSIC, format.sampleRate, channelMask, encoding,
                            plan.trackBufferBytes, AudioTrack.MODE_STREAM)
                    }
                },
            )
        }
        synchronized(gainLock) {
            track = built
            if (format.audioType == "media") setTrackGain(built, mediaGain)
        }
        val capacityBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            built.bufferSizeInFrames * frameBytes
        } else {
            plan.trackBufferBytes
        }
        startThresholdBytes = MediaAudioBuffer.startBytesFor(plan.startBytes, capacityBytes, PREBUFFER_WRITE_CHUNK_BYTES)
        report("Audio: ready audioType=${format.audioType} codec=${format.codec} " +
            "rate=${format.sampleRate} channels=${format.channels} " +
            "route=$routeLabel " +
            "bufferMs=${capacityBytes * 1000L / bytesPerSecond} startMs=${startThresholdBytes * 1000L / bytesPerSecond} " +
            "wireless=$isWireless target=${jitterBuffer.targetHoldPackets()}")
        DiagLog.i(
            TAG,
            "audio track prepared type=${format.payloadType} audioType=${format.audioType} " +
                "codec=${format.codec} " +
                "rate=${format.sampleRate} channels=${format.channels} " +
                "route=$routeLabel " +
                "buffer=${capacityBytes * 1000L / bytesPerSecond}ms start=${startThresholdBytes * 1000L / bytesPerSecond}ms " +
                "wireless=$isWireless target=${jitterBuffer.targetHoldPackets()}",
        )
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun pcmFormat(encoding: Int, channelMask: Int) = AndroidAudioFormat.Builder()
        .setSampleRate(format.sampleRate)
        .setChannelMask(channelMask)
        .setEncoding(encoding)
        .build()

    private fun streamType(): Int {
        val mode = if (advancedAudioChannelMapping) {
            AudioChannelMappingMode.AUTOMOTIVE_BUS
        } else {
            AudioChannelMappingMode.MOBILE_COMPATIBLE
        }
        return AudioChannelMapper.map(
            audioType = format.audioType,
            payloadType = format.payloadType,
            mode = mode,
            navigationStreamType = navigationStreamType,
        ).streamType
    }

    private fun aacAudioSpecificConfig(): ByteArray {
        val frequencyIndex = MediaCodecSupport.aacFrequencyIndex(format.sampleRate)
        val value = (AAC_OBJECT_TYPE_LC shl 11) or
            (frequencyIndex shl 7) or
            (format.channels.coerceIn(1, 7) shl 3)
        return byteArrayOf((value ushr 8).toByte(), value.toByte())
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun audioAttributes(): AudioAttributes {
        val mode = if (advancedAudioChannelMapping) {
            AudioChannelMappingMode.AUTOMOTIVE_BUS
        } else {
            AudioChannelMappingMode.MOBILE_COMPATIBLE
        }
        val selection = AudioChannelMapper.map(
            audioType = format.audioType,
            payloadType = format.payloadType,
            mode = mode,
        )
        val usage = usageFor(selection.channel)
        val contentType = contentTypeFor(selection.contentType)
        return AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(contentType)
            .build()
            .also {
                DiagLog.i(
                    TAG,
                    "audio route type=${format.payloadType} audioType=${format.audioType} " +
                        "mode=$mode channel=${selection.channel} " +
                        "usage=$usage contentType=$contentType",
                )
            }
    }

    private fun usageFor(channel: AudioChannel): Int = when (channel) {
        AudioChannel.MEDIA -> AudioAttributes.USAGE_MEDIA
        AudioChannel.PHONE -> AudioAttributes.USAGE_VOICE_COMMUNICATION
        AudioChannel.ASSISTANT -> AudioAttributes.USAGE_ASSISTANT
        AudioChannel.NAVIGATION -> AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
    }

    private fun contentTypeFor(contentType: AudioContentType): Int = when (contentType) {
        AudioContentType.MUSIC -> AudioAttributes.CONTENT_TYPE_MUSIC
        AudioContentType.SPEECH -> AudioAttributes.CONTENT_TYPE_SPEECH
    }

    /** Minimal OpusHead CSD for the mono 48 kHz stream CarPlay negotiates. */
    private fun opusHead(): ByteArray {
        val head = ByteArray(19)
        "OpusHead".toByteArray(Charsets.US_ASCII).copyInto(head, 0)
        head[8] = 1
        head[9] = format.channels.toByte()
        head[10] = 0x38
        head[11] = 0x01
        head[12] = format.sampleRate.toByte()
        head[13] = (format.sampleRate ushr 8).toByte()
        head[14] = (format.sampleRate ushr 16).toByte()
        head[15] = (format.sampleRate ushr 24).toByte()
        return head
    }

    private fun opusCodecDelay(): ByteArray =
        java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(OPUS_CODEC_DELAY_NANOS)
            .array()

    private fun opusSeekPreRoll(): ByteArray =
        java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(OPUS_SEEK_PRE_ROLL_NANOS)
            .array()

    private fun handle(packet: AudioPacket) {
        val rtp = packet.rtp
        val timestampUs = sampleTimestampUs(packet.sample)
        when (format.codec) {
            AudioCodecKind.LPCM -> writePcm(byteSwapS16(rtp.copyOfRange(12, rtp.size)))
            AudioCodecKind.AAC_LC -> {
                val accessUnit = rtp.copyOfRange(12, rtp.size)
                if (accessUnit.isNotEmpty()) {
                    if (!firstAacPayloadLogged) {
                        firstAacPayloadLogged = true
                        DiagLog.i(
                            TAG,
                            "audio AAC access unit bytes=${accessUnit.size} " +
                                "head=${accessUnit.copyOf(minOf(accessUnit.size, 16)).toHexString()}",
                        )
                    }
                    feedCodec(
                        MediaCodecSupport.adtsFrame(accessUnit, format.sampleRate, format.channels),
                        timestampUs,
                    )
                }
            }
            AudioCodecKind.OPUS -> {
                val accessUnit = rtp.copyOfRange(12, rtp.size)
                if (accessUnit.size < MIN_OPUS_PACKET_BYTES) {
                    if (!firstOpusShortPacketLogged) {
                        firstOpusShortPacketLogged = true
                        DiagLog.i(
                            TAG,
                            "audio Opus skipping short packet bytes=${accessUnit.size} " +
                                "head=${accessUnit.toHexString()}",
                        )
                    }
                    return
                }
                feedCodec(accessUnit, timestampUs)
            }
        }
    }

    private fun sampleTimestampUs(sample: Int): Long =
        (sample.toLong() and 0xffff_ffffL) * 1_000_000L / format.sampleRate

    private fun feedCodec(payload: ByteArray, presentationTimeUs: Long) {
        val codec = codec ?: return
        val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (index < 0) {
            inputDropped++
            if (inputDropped == 1) {
                DiagLog.w(
                    TAG,
                    "audio decoder input unavailable codec=${format.codec} " +
                        "queued=$inputQueued dropped=$inputDropped",
                )
            }
            return
        }
        @Suppress("DEPRECATION")
        val input = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            codec.getInputBuffer(index)
        } else {
            codec.inputBuffers[index]
        } ?: return
        input.clear()
        if (payload.size <= input.remaining()) {
            input.put(payload)
            codec.queueInputBuffer(index, 0, payload.size, presentationTimeUs, 0)
            inputQueued++
            if (!firstInputQueuedLogged) {
                firstInputQueuedLogged = true
                DiagLog.i(
                    TAG,
                    "audio decoder first input codec=${format.codec} bytes=${payload.size} " +
                        "head=${payload.copyOf(minOf(payload.size, 16)).toHexString()}",
                )
            }
        } else {
            codec.queueInputBuffer(index, 0, 0, 0, 0)
            inputDropped++
        }
        drainCodec(codec)
    }

    private fun drainCodec(codec: MediaCodec) {
        val info = audioBufferInfo
        while (running) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                index >= 0 -> {
                    val size = info.size
                    if (size > 0) {
                        outputBuffers++
                        if (outputBuffers == 1 || outputBuffers % DECODED_BUFFER_LOG_INTERVAL == 0) {
                            DiagLog.i(
                                TAG,
                                "audio decoder output codec=${format.codec} " +
                                    "buffers=$outputBuffers bytes=$size " +
                                    "queued=$inputQueued dropped=$inputDropped",
                            )
                        }
                    }
                    if (size > 0) {
                        @Suppress("DEPRECATION")
                        val output = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            codec.getOutputBuffer(index)
                        } else {
                            codec.outputBuffers[index]
                        }
                        if (output != null) {
                            if (size > pcm.size) pcm = ByteArray(size)
                            output.position(info.offset)
                            output.limit(info.offset + size)
                            output.get(pcm, 0, size)
                            writePcm(pcm, 0, size)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    private fun setTrackGain(track: AudioTrack, gain: Float) {
        @Suppress("DEPRECATION")
        runCatching { track.setStereoVolume(gain, gain) }
    }

    private fun writePcm(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        val track = track ?: return
        if (!firstPcmLogged && length > 0) {
            firstPcmLogged = true
            val end = minOf(data.size, offset + minOf(length, 16))
            DiagLog.i(
                TAG,
                "audio first PCM type=${format.payloadType} bytes=$length " +
                    "head=${data.copyOfRange(offset, end).toHexString()}",
            )
        }
        if (!isWirelessMedia) {
            if (!fadeApplied) {
                applyFadeIn(data, offset, length)
                fadeApplied = true
            }
            writeTrackWithProgress(track, data, offset, length)
            return
        }

        // Wireless media: smooth fade-in for cold start or underrun recovery
        if (!fadeApplied) {
            PcmSmoother.applyFadeIn(data, offset, minOf(length, fadeByteCount), format.sampleRate, format.channels)
            fadeApplied = true
        }

        var inOffset = offset
        var inRemaining = length

        while (inRemaining > 0 && running) {
            if (stagedBytes + inRemaining <= fadeByteCount) {
                System.arraycopy(data, inOffset, stagingBuffer, stagedBytes, inRemaining)
                stagedBytes += inRemaining
                inRemaining = 0
            } else {
                val availableFromStaging = stagedBytes
                val overflow = (stagedBytes + inRemaining) - fadeByteCount
                val writeFromStaging = minOf(availableFromStaging, overflow)
                if (writeFromStaging > 0) {
                    writeTrackWithProgress(track, stagingBuffer, 0, writeFromStaging)
                    if (writeFromStaging < stagedBytes) {
                        System.arraycopy(stagingBuffer, writeFromStaging, stagingBuffer, 0, stagedBytes - writeFromStaging)
                    }
                    stagedBytes -= writeFromStaging
                }
                val writeFromData = minOf(inRemaining - (fadeByteCount - stagedBytes), inRemaining)
                if (writeFromData > 0) {
                    writeTrackWithProgress(track, data, inOffset, writeFromData)
                    inOffset += writeFromData
                    inRemaining -= writeFromData
                }
            }
        }
    }

    private fun writeTrackWithProgress(track: AudioTrack, data: ByteArray, offset: Int, length: Int) {
        var written = 0
        while (written < length && running) {
            val writeLength = if (playbackStarted) {
                length - written
            } else {
                minOf(length - written, PREBUFFER_WRITE_CHUNK_BYTES)
            }
            val writeStarted = System.nanoTime()
            val count = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                track.write(data, offset + written, writeLength, AudioTrack.WRITE_BLOCKING)
            } else {
                track.write(data, offset + written, writeLength)
            }
            maxWriteMs = maxOf(maxWriteMs, (System.nanoTime() - writeStarted) / 1_000_000L)
            if (count <= 0) break
            written += count
            bufferProgress.written(count)
            lastPcmWriteNs = System.nanoTime()
            if (!playbackStarted) {
                prebufferBytes += count
                if (prebufferBytes >= startThresholdBytes) startPlayback(track, "threshold")
            }
        }
    }

    private fun writeCompletelyToTrack(data: ByteArray, offset: Int, length: Int) {
        val track = track ?: return
        var written = 0
        while (written < length && running) {
            val count = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                track.write(data, offset + written, length - written, AudioTrack.WRITE_BLOCKING)
            } else {
                track.write(data, offset + written, length - written)
            }
            if (count <= 0) break
            written += count
            bufferProgress.written(count)
        }
    }

    private fun startPlayback(track: AudioTrack, reason: String) {
        underrunsAtPlaybackStart = track.compatUnderruns()
        track.play()
        playbackStarted = true
        val prebufferMs = if (bytesPerSecond > 0) prebufferBytes * 1000L / bytesPerSecond else 0L
        val startMs = if (bytesPerSecond > 0) startThresholdBytes * 1000L / bytesPerSecond else 0L
        DiagLog.i(
            TAG,
            "audio playback started audioType=${format.audioType} reason=$reason " +
                "prebufferMs=$prebufferMs startMs=$startMs queue=${queue.size}",
        )
    }

    private fun maintainPlaybackBuffer() {
        val track = track ?: return
        val now = System.nanoTime()
        val lastRx = lastArrivalNs.get()

        val unplayedBytes = if (bytesPerSecond > 0) bufferProgress.queuedBytes(track.playbackHeadPosition) else 0
        if (lastRx != 0L && lastRx != snapshotLastRx) {
            snapshotLastRx = lastRx
            val queueMs = queue.size * 1024L * 1000L / format.sampleRate
            val trackMs = if (bytesPerSecond > 0) unplayedBytes * 1000L / bytesPerSecond else 0L
            snapshotTrackMs = trackMs
            snapshotBufferedMs = queueMs + trackMs
        }

        // Starvation brake for wireless media:
        if (playbackStarted && isWirelessMedia && lastRx > 0L &&
            (now - lastRx > (snapshotBufferedMs + 50L) * 1_000_000L)) {
            val sinceRxMs = (now - lastRx) / 1_000_000L
            val snapshotMs = snapshotBufferedMs
            val trackMs = snapshotTrackMs
            val queued = queue.size
            codec?.let(::drainCodec)
            if (stagedBytes > 0) {
                PcmSmoother.applyFadeOut(stagingBuffer, 0, stagedBytes, format.sampleRate, format.channels)
                writeCompletelyToTrack(stagingBuffer, 0, stagedBytes)
            }
            val silence = PcmSmoother.createSilence(format.sampleRate, format.channels, 40)
            writeCompletelyToTrack(silence, 0, silence.size)
            track.pause()
            playbackStarted = false
            gateOpened = false
            fadeApplied = false
            stagedBytes = 0
            prebufferBytes = 0
            jitterBuffer.onUnderrunDetected()
            gateWaitMarkNs = System.nanoTime()
            rebufferCount++
            val targetHold = jitterBuffer.targetHoldPackets()
            DiagLog.w(
                TAG,
                "wireless media starvation brake audioType=${format.audioType} " +
                    "sinceRxMs=$sinceRxMs snapshotMs=$snapshotMs trackMs=$trackMs " +
                    "queue=$queued targetHold=$targetHold",
            )
            report(
                "Audio: starvation brake triggered audioType=${format.audioType} " +
                    "sinceRxMs=$sinceRxMs snapshotMs=$snapshotMs trackMs=$trackMs " +
                    "queue=$queued targetHold=$targetHold",
            )
        }

        val platformReportsUnderruns = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
        val underrunDetected = if (platformReportsUnderruns) {
            track.compatUnderruns() > underrunsAtPlaybackStart
        } else {
            true
        }
        val drained = bufferProgress.shouldRebuffer(format.audioType, playbackStarted,
            underrunDetected, queue.isEmpty(), track.playbackHeadPosition)
        when (drainAction(drained, platformReportsUnderruns, drainCounted)) {
            DrainAction.NONE -> if (!drained) drainCounted = false
            DrainAction.COUNT -> {
                drainCounted = true
                rebufferCount++
            }
            DrainAction.PAUSE_AND_REBUFFER -> {
                // The hardware buffer has actually drained. Pause without flushing or discarding PCM,
                // then use the configured start threshold again when music resumes.
                track.pause()
                playbackStarted = false
                prebufferBytes = 0
                rebufferCount++
            }
        }
        // Tail buffer play-out:
        if (!isWirelessMedia) {
            if (!playbackStarted && prebufferBytes > 0 && queue.isEmpty() &&
                System.nanoTime() - lastPcmWriteNs >= BUFFER_TAIL_WAIT_NS) {
                startPlayback(track, "tail")
            }
        } else {
            if (!playbackStarted && gateOpened && queue.isEmpty() && prebufferBytes > 0) {
                startPlayback(track, "drain")
            }
        }
    }

    // Persist counters even during packet starvation, and flush before disconnect releases the track.
    private fun logStatsIfDue(force: Boolean = false) {
        val now = System.nanoTime()
        if (statsWindowStartNs == 0L) statsWindowStartNs = now
        if (!force && now - statsWindowStartNs < STATS_WINDOW_NS) return
        val underruns = track?.compatUnderruns() ?: 0
        val lastRx = lastArrivalNs.get()
        val line = "audio stats audioType=${format.audioType} codec=${format.codec} rx=${packetsReceived.getAndSet(0)} " +
            "dropped=${packetsDropped.getAndSet(0)} underruns=+${underruns - statsLastUnderruns} queue=${queue.size} " +
            "playing=$playbackStarted maxGapMs=${maxArrivalGapMs.getAndSet(0)} " +
            "sinceRxMs=${if (lastRx == 0L) -1 else (now - lastRx) / 1_000_000L} maxWriteMs=$maxWriteMs " +
            "decoderDroppedTotal=$inputDropped outputBuffersTotal=$outputBuffers rebuffers=$rebufferCount ended=$force"
        DiagLog.i(STATS_TAG, line)
        report(line)
        statsLastUnderruns = underruns
        maxWriteMs = 0L
        statsWindowStartNs = now
    }

    private fun applyFadeIn(data: ByteArray, offset: Int, length: Int) {
        val samples = (length - length % 2) / 2
        val fadeSamples = minOf(samples, maxOf(1, format.sampleRate / 100))
        for (index in 0 until fadeSamples) {
            val position = offset + index * 2
            val sample = (data[position].toInt() and 0xff) or (data[position + 1].toInt() shl 8)
            val scaled = (sample.toLong() * (index + 1) / fadeSamples).toInt()
            data[position] = scaled.toByte()
            data[position + 1] = (scaled shr 8).toByte()
        }
    }

    private fun byteSwapS16(source: ByteArray): ByteArray {
        for (index in 0 until source.size - 1 step 2) {
            val tmp = source[index]
            source[index] = source[index + 1]
            source[index + 1] = tmp
        }
        return source
    }

    @Synchronized
    private fun release() {
        jitterBuffer.onStreamDestroyed()
        val codec = codec
        this.codec = null
        if (codec != null) {
            try {
                codec.stop()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                codec.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
        val track = track
        this.track = null
        if (track != null) {
            try {
                track.pause()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                track.flush()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                track.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
    }

    private fun AudioTrack.compatUnderruns(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) underrunCount else 0

    private companion object {
        const val TAG = "xcertplay-usb"
        const val AAC_OBJECT_TYPE_LC = 2
        const val MIN_OPUS_PACKET_BYTES = 4
        const val OPUS_CODEC_DELAY_NANOS = 6_500_000L
        const val OPUS_SEEK_PRE_ROLL_NANOS = 80_000_000L
        const val INPUT_TIMEOUT_US = 10_000L
        const val AUDIO_POLL_MILLIS = 10L
        const val BUFFER_TAIL_WAIT_NS = 500_000_000L
        // Holds a burst after a Wi-Fi gap (~4 s of AAC) instead of dropping it.
        const val MAX_QUEUED_PACKETS = 192
        const val PREBUFFER_WRITE_CHUNK_BYTES = 2 * 1024
        const val STATS_TAG = "DiPlay-AudioStats"
        const val STATS_WINDOW_NS = 5_000_000_000L
        const val DECODED_BUFFER_LOG_INTERVAL = 50
    }
}
