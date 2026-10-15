package com.shilapi.xcertplay

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
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
import com.shilapi.xcertplay.orchestration.CarPlayController

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

    /** Receives the same focus callbacks as the media-button session. Cleared by [detach]. */
    fun bindAudioFocusTarget(target: ((Int) -> Unit)?) {
        audioFocusTarget = target
    }
    fun onIphonePlaying(playing: Boolean) {
        if (playing) mainHandler.post { backend.regainFocus() }
    }

    internal fun dispatch(event: KeyEvent): Boolean = backend.dispatch(event)

    @Volatile private var audioFocusTarget: ((Int) -> Unit)? = null

    private interface Backend {
        fun attach(context: Context, controller: CarPlayController)
        fun detach(expected: CarPlayController?)
        fun update(active: Boolean)
        fun regainFocus()
        fun dispatch(event: KeyEvent): Boolean
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

        override fun attach(context: Context, controller: CarPlayController) {
            if (this.controller !== controller) release()
            this.context = context.applicationContext
            this.controller = controller
            controller.playbackListener = ::onPlaybackChanged
        }

        override fun detach(expected: CarPlayController?) {
            if (expected == null || controller !== expected) return
            expected.playbackListener = null
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

        private fun onPlaybackChanged(playing: Boolean) {
            if (playing) mainHandler.post { regainFocus() }
        }
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private class Api21Backend : FocusBackend() {
        private var session: MediaSession? = null

        override fun update(active: Boolean) {
            val currentContext = context ?: return
            if (controller == null) return
            if (session == null) {
                // A car that hands the wheel over through its own input service sends the press on
                // as an ACTION_MEDIA_BUTTON aimed at whichever receiver the framework considers the
                // media button one. A session without these declares no interest and is passed over,
                // leaving the wheel on the head unit's own player.
                val mediaButtonReceiver = PendingIntent.getBroadcast(
                    currentContext,
                    0,
                    Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(
                        ComponentName(currentContext, CarPlayMediaButtonReceiver::class.java),
                    ),
                    PendingIntent.FLAG_UPDATE_CURRENT,
                )
                session = MediaSession(currentContext, "DiPlay CarPlay").apply {
                    setFlags(
                        MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                            MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS,
                    )
                    setMediaButtonReceiver(mediaButtonReceiver)
                    setCallback(CarPlayMediaCallback { index, _ -> send(index) }, mainHandler)
                    isActive = true
                }
            }
            if (active) regainFocus()
            val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS
            session?.setPlaybackState(
                PlaybackState.Builder().setActions(actions).setState(
                    if (active) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    1f,
                ).build(),
            )
        }

        override fun release() {
            session?.apply { isActive = false; release() }
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
