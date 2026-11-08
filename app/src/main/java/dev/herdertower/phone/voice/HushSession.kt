package dev.herdertower.phone.voice

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.media.VolumeProvider
import android.os.SystemClock
import android.view.KeyEvent

/**
 * The tower's media session while the phone is its voice, so the buttons
 * outside the app stop it: the headset's or Bluetooth's play/pause, and
 * volume down pressed twice (or held). While a reply plays the session takes
 * the volume keys (as a cast does) and passes every press on to the media
 * volume, so they still turn it down.
 */
class HushSession(context: Context, private val onHush: () -> Unit) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val keys = HushKeys()
    private val session = MediaSession(context, "tower").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(intent: Intent): Boolean {
                val ev = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java) ?: return false
                if (ev.action != KeyEvent.ACTION_DOWN || !speaking) return super.onMediaButtonEvent(intent)
                return when (ev.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_HEADSETHOOK -> {
                        onHush()
                        true
                    }
                    else -> super.onMediaButtonEvent(intent)
                }
            }
            override fun onPause() = onHush()
            override fun onStop() = onHush()
        })
    }

    private val volume = object : VolumeProvider(VOLUME_CONTROL_RELATIVE, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), audio.getStreamVolume(AudioManager.STREAM_MUSIC)) {
        override fun onAdjustVolume(direction: Int) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
            currentVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (direction < 0 && keys.down(SystemClock.uptimeMillis())) onHush()
        }
    }

    private var speaking = false

    /** Follows the player: while a clip plays, the session is active and has the buttons. */
    fun follow(playing: Boolean) {
        if (playing == speaking) return
        speaking = playing
        keys.reset()
        if (playing) {
            volume.currentVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            session.setPlaybackToRemote(volume)
        } else {
            session.setPlaybackToLocal(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build())
        }
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP or PlaybackState.ACTION_PLAY_PAUSE)
                .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, 0, 1f)
                .build(),
        )
        session.isActive = playing
    }

    fun release() {
        session.release()
    }
}
