package com.shilapi.xcertplay.rig

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.media.AndroidMediaSink
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The desk rig's display head: the emulator half of `ForwardingMediaSink`.
 *
 * The other half of the rig runs on the host and is a JVM, so it can decrypt the AirPlay screen stream
 * but cannot render it -- a Surface needs Android. This activity is Android: it takes the bytes that
 * half sends and hands them to the app's own [AndroidMediaSink], which is the same decoder the car
 * renders CarPlay with. Nothing here is a stand-in for the app's decode path; the bytes and the sink
 * are both the real ones, and only the transport between them is this rig's own.
 *
 * It is a debug-only entry, and deliberately not reachable from the app's own UI: it renders a stream
 * that is handed to it, with no iAP2, no Bluetooth and no pairing behind it, so nothing about it
 * belongs in a build that goes to a car.
 *
 * The wire format is `ForwardingMediaSink`'s, and the two have to be changed together. That half is not in
 * this tree — it is in the adapter stack that `shared/build.gradle` mounts by `DIPLAY_ADAPTER_DIR` — so it
 * will not be found by searching here. With nothing mounted there is no desk half to change with, and this
 * activity is then a head that nothing dials rather than a broken one: it needs no adapter to run, and
 * carries none of the adapter's code.
 */
class AirPlayDisplayHeadActivity : Activity() {
    private lateinit var sink: AndroidMediaSink
    private lateinit var status: TextView
    private val frames = AtomicInteger(0)
    private val bytes = AtomicInteger(0)

    @Volatile private var stopped = false

    /**
     * Released when the decoder says it is up.
     *
     * The software decoder on this emulator takes about a second to start, and it starts on a thread of
     * its own, so the frames that arrive during that second are already older than the sink's freshness
     * check by the time it looks at them. The sink then drops the whole backlog and waits for a keyframe,
     * which releases the decoder and rebuilds it -- another second, during which the same thing happens
     * again. Measured, that loop held the screen black while the phone re-sent its configuration every
     * hundred and fifty milliseconds and nothing was ever rendered.
     *
     * Waiting here is what breaks it. The read loop stops pulling frames while the decoder starts, so
     * they wait in the socket rather than in a queue that ages, and the sink's clock starts when it is
     * handed a frame rather than when the phone sent it.
     */
    private val decoderConfigured = Semaphore(0)

    /** The far end of the connection in flight, for the one message that travels the other way. */
    @Volatile private var output: DataOutputStream? = null

    /**
     * Says the one thing this end ever says back.
     *
     * Written from the decoder's own recovery thread while the read loop owns the other direction, so
     * the write is serialised against itself and nothing else touches the stream.
     */
    private fun askForKeyFrame() {
        val stream = output ?: return
        say("asking the desk for a keyframe")
        synchronized(stream) {
            runCatching {
                stream.writeByte(TAG_NEED_KEYFRAME)
                stream.writeByte(SCREEN_STREAM_TYPE)
                stream.flush()
            }.onFailure { say("could not ask for a keyframe: ${it.javaClass.simpleName}: ${it.message}") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(160, 0, 0, 0))
            textSize = 14f
            text = "AirPlay display head: waiting on port $PORT"
        }
        val surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                // Stored on the sink rather than handed to a decoder: the decoder is created on the first
                // frame, and it picks the surface up from here when it is. That also means a surface that
                // arrives before the first frame is not a race.
                sink.setSurface(SCREEN_STREAM_TYPE, holder.surface)
                say("surface ready ${holder.surfaceFrame.width()}x${holder.surfaceFrame.height()}")
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                sink.setSurface(SCREEN_STREAM_TYPE, holder.surface)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                say("surface gone")
            }
        })

        setContentView(
            FrameLayout(this).apply {
                addView(
                    surfaceView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
                addView(
                    status,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP or Gravity.START,
                    ),
                )
            },
        )

        sink = AndroidMediaSink(surface = null, videoWidth = 1920, videoHeight = 720)
        // The decoder asks for a keyframe when it has fallen behind or lost its reference chain. On the
        // car that call reaches the media engine in the same process, which turns it into `forceKeyFrame`
        // for the phone. Here the engine is on the other machine, so the request goes back down the
        // socket instead. Without it the head sits waiting for a keyframe nothing has asked for --
        // measured, thirty-five seconds of black screen after the decoder dropped a backlog of frames it
        // had no chance to catch up with.
        sink.setVideoRecoveryHandler(SCREEN_STREAM_TYPE) { askForKeyFrame() }
        sink.setVideoDiagnosticHandler(SCREEN_STREAM_TYPE) { message ->
            if (message.startsWith("decoder=")) decoderConfigured.release()
        }
        Thread(::acceptLoop, "display-head").apply { isDaemon = true; start() }
        say("listening on port $PORT")
    }

    override fun onDestroy() {
        stopped = true
        sink.close()
        super.onDestroy()
    }

    /**
     * One connection at a time, and a reconnect is accepted after one ends: the desk reconnects on its
     * next attempt, and a head that only ever took the first connection would show the first attempt and
     * nothing after.
     */
    private fun acceptLoop() {
        ServerSocket().use { server ->
            server.reuseAddress = true
            server.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), PORT))
            while (!stopped) {
                val connection = try {
                    server.accept()
                } catch (error: Exception) {
                    if (!stopped) say("accept failed: ${error.javaClass.simpleName}: ${error.message}")
                    return
                }
                say("desk connected from ${connection.remoteSocketAddress}")
                try {
                    connection.tcpNoDelay = true
                    output = DataOutputStream(connection.getOutputStream().buffered())
                    DataInputStream(connection.getInputStream().buffered()).use(::readStream)
                } catch (error: Exception) {
                    say("stream ended: ${error.javaClass.simpleName}: ${error.message}")
                } finally {
                    output = null
                    runCatching { connection.close() }
                    say("desk disconnected; ${frames.get()} frame(s), ${bytes.get()} byte(s)")
                }
            }
        }
    }

    private fun readStream(input: DataInputStream) {
        while (!stopped) {
            val tag = input.readUnsignedByte()
            when (tag) {
                TAG_CODEC -> {
                    val type = input.readUnsignedByte()
                    val name = ByteArray(input.readUnsignedShort())
                    input.readFully(name)
                    val codec = runCatching { VideoCodec.valueOf(String(name, Charsets.UTF_8)) }
                        .getOrElse { VideoCodec.H264 }
                    sink.onVideoCodec(type, codec)
                    say("codec type=$type $codec")
                }

                TAG_CONFIG -> {
                    val type = input.readUnsignedByte()
                    val data = ByteArray(input.readInt())
                    input.readFully(data)
                    // Any permit left over belongs to the previous decoder; this one waits for its own.
                    decoderConfigured.drainPermits()
                    sink.onVideoConfig(type, data)
                    val started = decoderConfigured.tryAcquire(DECODER_START_MILLIS, TimeUnit.MILLISECONDS)
                    say("config type=$type ${data.size} byte(s); decoder up=$started")
                }

                TAG_FRAME -> {
                    val type = input.readUnsignedByte()
                    val data = ByteArray(input.readInt())
                    input.readFully(data)
                    sink.onVideoFrame(type, data)
                    val count = frames.incrementAndGet()
                    bytes.addAndGet(data.size)
                    if (count % 30 == 0) say("$count frame(s), ${bytes.get()} byte(s)")
                }

                TAG_ACTIVE -> {
                    val type = input.readUnsignedByte()
                    val active = input.readUnsignedByte() != 0
                    sink.onScreenStreamActive(type, active)
                    say("screen stream type=$type active=$active")
                }

                else -> throw EOFException("unknown tag $tag")
            }
        }
    }

    /**
     * Said three times on purpose: to the log, which is what a run is read from afterwards; to the screen,
     * which is what `screencap` shows -- a black frame has to be readable as "nothing arrived" rather than
     * as "the decoder is broken"; and back down the socket, because the desk is the half that writes the
     * run's log and can see neither of the other two. A run in which the desk pushed frames into a head
     * that answered only "I need a keyframe" is exactly the run that cannot be read.
     */
    private fun say(message: String) {
        Log.i(TAG, message)
        sendStatus(message)
        runOnUiThread {
            status.text = "AirPlay display head\n$message\n${frames.get()} frame(s), ${bytes.get()} byte(s)"
        }
    }

    /**
     * This end's other direction, beside the keyframe request and serialised against it, so a status and a
     * request never interleave mid-message.
     */
    private fun sendStatus(message: String) {
        val stream = output ?: return
        val data = message.toByteArray(Charsets.UTF_8)
        synchronized(stream) {
            runCatching {
                stream.writeByte(TAG_STATUS)
                stream.writeShort(data.size)
                stream.write(data)
                stream.flush()
            }
        }
    }

    private companion object {
        const val TAG = "DiPlay-DisplayHead"

        /** The port `ForwardingMediaSink` dials through `adb forward`. */
        const val PORT = 7999

        /** The AirPlay screen stream type; 110 is the main screen, which is the only one sent here. */
        const val SCREEN_STREAM_TYPE = 110

        const val TAG_CODEC = 1
        const val TAG_CONFIG = 2
        const val TAG_FRAME = 3
        const val TAG_ACTIVE = 4

        /** This end's direction, and its whole vocabulary: it needs a keyframe, and it reports. */
        const val TAG_NEED_KEYFRAME = 0x10
        const val TAG_STATUS = 0x11

        /** How long the read loop holds off while the decoder starts. Bounded, so a dead one still reads. */
        const val DECODER_START_MILLIS = 3_000L
    }
}
