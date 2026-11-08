package dev.herdertower.phone.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import dev.herdertower.phone.api.AudioClip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Plays the tower's clips in the order their `audio` events came, gapless,
 * through one streaming [AudioTrack]. Each clip is fetched (with the token)
 * as soon as it's announced, so the next is ready while one plays.
 *
 * - [hush]: stop now and drop everything queued.
 * - [pause]/[resume]: someone is talking at the Mac; hold playback.
 */
class ClipPlayer(
    context: Context,
    private val scope: CoroutineScope,
    private val fetch: suspend (AudioClip) -> ByteArray,
) {
    private class Item(val clip: AudioClip, val bytes: Deferred<ByteArray>, val generation: Int)

    private val audio = context.getSystemService(AudioManager::class.java)
    private val queue = Channel<Item>(Channel.UNLIMITED)

    /** Bumped by [hush]: anything from an older generation is dropped. */
    @Volatile private var generation = 0

    private val _now = MutableStateFlow<AudioClip?>(null)
    /** The clip playing now (its text is shown as the tower speaks). */
    val nowPlaying: StateFlow<AudioClip?> = _now.asStateFlow()

    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    private var track: AudioTrack? = null
    private var trackFormat = 0 to 0
    private var framesWritten = 0L
    private var focus: AudioFocusRequest? = null

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    init {
        scope.launch(Dispatchers.IO) {
            for (item in queue) {
                runCatching { play(item) }.onFailure { Log.w(TAG, "clip ${item.clip.id} skipped: ${it.javaClass.simpleName}") }
                if (queue.isEmpty) idle()
            }
        }
    }

    fun enqueue(clip: AudioClip) {
        val g = generation
        val bytes = scope.async(Dispatchers.IO) { fetch(clip) }
        queue.trySend(Item(clip, bytes, g))
    }

    fun hush() {
        generation++
        while (true) {
            val dropped = queue.tryReceive().getOrNull() ?: break
            dropped.bytes.cancel()
        }
        _paused.value = false
        synchronized(this) {
            track?.let {
                runCatching {
                    it.pause()
                    it.flush()
                }
            }
            framesWritten = 0
        }
        _now.value = null
    }

    fun pause() {
        _paused.value = true
        synchronized(this) { track?.let { runCatching { it.pause() } } }
    }

    fun resume() {
        _paused.value = false
        synchronized(this) { if (_now.value != null) track?.let { runCatching { it.play() } } }
    }

    fun release() {
        hush()
        queue.close()
        synchronized(this) {
            track?.release()
            track = null
        }
        abandonFocus()
    }

    private suspend fun play(item: Item) {
        if (item.generation != generation) return item.bytes.cancel()
        val wav = item.bytes.await()
        if (item.generation != generation) return
        val info = Wav.parse(wav)
        val pcm = Wav.pcm16(wav, info)
        val t = trackFor(info.sampleRate, info.channels)
        requestFocus()
        _now.value = item.clip
        if (!_paused.value) synchronized(this) { if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play() }
        val chunk = (info.sampleRate * info.channels * 2 / 50).coerceAtLeast(2) and 1.inv() // 20 ms, whole samples
        var off = 0
        while (off < pcm.size) {
            if (_paused.value) _paused.first { !it }
            if (item.generation != generation) return
            val n = t.write(pcm, off, minOf(chunk, pcm.size - off), AudioTrack.WRITE_BLOCKING)
            if (n <= 0) return
            off += n
            synchronized(this) { framesWritten += n / (2 * info.channels) }
        }
    }

    /** The queue ran dry: let what's written play out, then stop the track and give up audio focus. */
    private suspend fun idle() {
        val g = generation
        val t = track ?: return
        var waited = 0
        while (g == generation && queue.isEmpty && waited < 15_000) {
            val head = t.playbackHeadPosition.toLong() and 0xffffffffL
            if (head >= framesWritten || t.playState != AudioTrack.PLAYSTATE_PLAYING && !_paused.value) break
            delay(20)
            if (!_paused.value) waited += 20
        }
        if (g != generation || !queue.isEmpty) return
        synchronized(this) {
            runCatching {
                t.stop()
                t.flush()
            }
            framesWritten = 0
        }
        _now.value = null
        abandonFocus()
    }

    private fun trackFor(rate: Int, channels: Int): AudioTrack = synchronized(this) {
        track?.takeIf { trackFormat == rate to channels }?.let { return it }
        track?.release()
        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(min, rate * channels * 2 / 5)) // ~200 ms
            .build()
            .also {
                track = it
                trackFormat = rate to channels
                framesWritten = 0
            }
    }

    private fun requestFocus() {
        if (focus != null) return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change ->
                // A call or another app took the audio for good: stop talking.
                if (change == AudioManager.AUDIOFOCUS_LOSS) hush()
            }
            .build()
        audio.requestAudioFocus(req)
        focus = req
    }

    private fun abandonFocus() {
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }

    private companion object {
        const val TAG = "ClipPlayer"
    }
}
