package dev.herdertower.phone.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import dev.herdertower.phone.api.AudioClip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Plays the tower's clips in the order their `audio` events came as one
 * continuous stream through one [AudioTrack], on an audio-priority thread.
 * Each clip is fetched (with the token) as soon as it's announced, so the
 * next is ready while one plays. When the next sentence is late the stream
 * isn't stopped: silence is written until it comes (up to [LINGER_MS]), so
 * the track never restarts mid-reply (a restart ramps the volume up again
 * and can drop a few milliseconds). Each clip's edges get a 3 ms fade so
 * joins don't click.
 *
 * - [hush]/[stop]: stop now and drop everything queued.
 * - [pause]/[resume]: someone is talking at the Mac; hold playback.
 * - [stats]: what the diagnostics show (underruns, gaps, fetch times, audio mode, focus).
 */
class ClipPlayer(
    context: Context,
    private val scope: CoroutineScope,
    private val fetch: suspend (AudioClip) -> ByteArray,
) {
    private class Item(val clip: AudioClip, val bytes: Deferred<Pair<ByteArray, Long>>, val generation: Int, val announcedMs: Long)

    private val audio = context.getSystemService(AudioManager::class.java)
    /** Held while it talks: Wi-Fi out of power save (clips arrive on time) and the CPU awake (the screen may be off). */
    private val wifiLock = context.getSystemService(android.net.wifi.WifiManager::class.java)
        ?.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "tower:speaking")?.apply { setReferenceCounted(false) }
    private val wakeLock = context.getSystemService(android.os.PowerManager::class.java)
        ?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "tower:speaking")?.apply { setReferenceCounted(false) }
    private val queue = Channel<Item>(Channel.UNLIMITED)

    /** One thread, at audio priority, does all the writing. */
    private val audioThread = Executors.newSingleThreadExecutor { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            r.run()
        }, "tower-audio")
    }.asCoroutineDispatcher()

    /** Bumped by [hush]: anything from an older generation is dropped. */
    @Volatile private var generation = 0

    private val _now = MutableStateFlow<AudioClip?>(null)
    /** The clip playing now (its text is shown as the tower speaks); kept through short pauses between sentences. */
    val nowPlaying: StateFlow<AudioClip?> = _now.asStateFlow()

    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    /** What playback measured, for the diagnostics. */
    data class Stats(
        val clips: Int = 0,
        val underruns: Int = 0,
        /** Pauses between sentences filled with silence, and the longest. */
        val gaps: Int = 0,
        val maxGapMs: Long = 0,
        /** How long the last clip took to download. */
        val fetchMs: Long = 0,
        val rate: Int = 0,
        val bufferMs: Int = 0,
        val mode: String = "",
        val focus: String = "",
        /** The last clip's level (dBFS), to see if sentences differ in loudness. */
        val levelDb: Double = 0.0,
    ) {
        fun line(): String = if (clips == 0) "audio: nothing played yet" else "audio: $clips clips · $underruns underruns · $gaps gaps" +
            (if (gaps > 0) " (max $maxGapMs ms)" else "") +
            " · fetch $fetchMs ms · ${rate / 1000.0} kHz · buffer $bufferMs ms · mode $mode · focus $focus · level ${"%.0f".format(levelDb)} dB"
    }

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats.asStateFlow()

    private var track: AudioTrack? = null
    private var trackFormat = 0 to 0
    private var focus: AudioFocusRequest? = null
    private var focusWord = "none"
    /** The track is playing a stretch of speech (between clips it's fed silence). */
    private var streaming = false
    private var underrunsAtStart = 0
    private var stretchGeneration = 0

    // Lazy: made when the first clip plays (and not at all in the JVM tests, where Android's classes are stubs).
    private val attributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }

    init {
        scope.launch(audioThread) {
            var next: Item? = null
            while (true) {
                val item = next ?: queue.tryReceive().getOrNull()
                next = null
                if (streaming && (item == null || !item.bytes.isCompleted)) {
                    // Between sentences, or the next one still downloading: keep the stream going with silence
                    // (an empty buffer would underrun), then let it end.
                    next = item
                    if (!bridge()) finishStretch()
                    continue
                }
                val ready = item ?: queue.receiveCatching().getOrNull() ?: break
                runCatching { play(ready) }.onFailure { Log.w(TAG, "clip ${ready.clip.id} skipped: ${it.javaClass.simpleName}") }
            }
        }
    }

    /** After the user stopped a reply: its later sentences are dropped, until a new turn (its "Okay.") or [newTurn]. */
    @Volatile private var mutedUntil = 0L

    /** Whether the user stopped a reply that may still be arriving. */
    val muted: Boolean get() = System.currentTimeMillis() < mutedUntil

    /**
     * The user stopped the tower here: quiet now, and if a reply was playing,
     * the rest of that reply (its later sentences) is ignored too. Alerts and
     * cues still play.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // Channel.isEmpty
    fun stop() {
        if (_now.value != null || !queue.isEmpty) mutedUntil = System.currentTimeMillis() + MUTE_MS
        hush()
    }

    /** The user asked something new: its reply may play. */
    fun newTurn() {
        mutedUntil = 0
    }

    /** The tower is muted (`tower mute`): nothing plays until it is unmuted. */
    @Volatile var silenced = false

    fun enqueue(clip: AudioClip) {
        if (silenced) return
        if (clip.kind == "ack") mutedUntil = 0 // a new turn began
        if (clip.kind == "reply" && muted) return
        val g = generation
        val bytes = scope.async(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            fetch(clip) to System.currentTimeMillis() - t0
        }
        queue.trySend(Item(clip, bytes, g, System.currentTimeMillis()))
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

    private var silentMs = 0L
    private var clipsInStretch = 0

    private suspend fun play(item: Item) {
        if (item.generation != generation) return item.bytes.cancel()
        val (wav, fetched) = item.bytes.await()
        if (item.generation != generation) return
        val waited = System.currentTimeMillis() - item.announcedMs
        val info = Wav.parse(wav)
        val pcm = Wav.pcm16(wav, info)
        Wav.fadeEdges(pcm, info.sampleRate, info.channels)
        val t = trackFor(info.sampleRate, info.channels)
        requestFocus()
        if (silentMs > 0 && clipsInStretch > 0) {
            _stats.value = _stats.value.let { it.copy(gaps = it.gaps + 1, maxGapMs = maxOf(it.maxGapMs, silentMs)) }
        }
        silentMs = 0
        clipsInStretch++
        _now.value = item.clip
        // Hushed between the check above and now: don't leave this clip showing as playing.
        if (item.generation != generation) {
            _now.value = null
            return
        }
        if (!streaming || stretchGeneration != generation) {
            streaming = true
            stretchGeneration = generation
            clipsInStretch = 1
            runCatching { wifiLock?.acquire() }
            runCatching { wakeLock?.acquire(10 * 60_000L) }
            underrunsAtStart = t.underrunCount
        }
        val level = Wav.rmsDb(pcm)
        Log.i(TAG, "clip ${item.clip.id} ${item.clip.kind}: ${info.durationMs} ms at ${info.sampleRate} Hz, downloaded in $fetched ms, playing ${waited} ms after its event, level ${"%.1f".format(level)} dB")
        _stats.value = _stats.value.copy(
            clips = _stats.value.clips + 1, fetchMs = fetched, rate = info.sampleRate, levelDb = level,
            mode = modeWord(audio.mode), focus = focusWord,
        )
        val chunk = (info.sampleRate * info.channels * 2 / 50).coerceAtLeast(2) and 1.inv() // 20 ms, whole samples
        // A stopped track gets 200 ms written before it starts, so it never starts on an empty buffer.
        val prime = if (t.playState == AudioTrack.PLAYSTATE_PLAYING) 0 else minOf(pcm.size, chunk * 10)
        var off = 0
        while (off < pcm.size) {
            if (_paused.value) _paused.first { !it }
            if (item.generation != generation) return
            val n = t.write(pcm, off, minOf(chunk, pcm.size - off), AudioTrack.WRITE_BLOCKING)
            if (n <= 0) return
            off += n
            if (off >= prime && !_paused.value) synchronized(this) { if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play() }
        }
        noteUnderruns(t)
    }

    /** The queue is empty mid-stretch: 20 ms of silence. False once it has waited [LINGER_MS] (time to end the stretch). */
    private suspend fun bridge(): Boolean {
        val t = track ?: return false
        if (stretchGeneration != generation) return false // hushed: the stretch is over
        if (_paused.value) {
            _paused.first { !it }
            return true
        }
        if (silentMs >= LINGER_MS) return false
        val zeros = silence(trackFormat.first, trackFormat.second)
        val n = t.write(zeros, 0, zeros.size, AudioTrack.WRITE_BLOCKING)
        if (n <= 0) return false
        silentMs += 20
        return true
    }

    /** The stretch of speech is over: what's written plays out (it's silence), the track stops, focus goes back. */
    private fun finishStretch() {
        val t = track
        streaming = false
        clipsInStretch = 0
        silentMs = 0
        if (t != null) {
            noteUnderruns(t)
            synchronized(this) {
                runCatching {
                    t.stop()
                    t.flush()
                }
            }
        }
        _now.value = null
        abandonFocus()
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        _stats.value = _stats.value.copy(mode = modeWord(audio.mode), focus = focusWord)
    }

    private fun noteUnderruns(t: AudioTrack) {
        val u = (t.underrunCount - underrunsAtStart).coerceAtLeast(0)
        if (u > 0) {
            underrunsAtStart = t.underrunCount
            _stats.value = _stats.value.let { it.copy(underruns = it.underruns + u) }
            Log.w(TAG, "$u underruns")
        }
    }

    private var zeros = ByteArray(0)
    private fun silence(rate: Int, channels: Int): ByteArray {
        val n = (rate * channels * 2 / 50) and 1.inv()
        if (zeros.size != n) zeros = ByteArray(n)
        return zeros
    }

    private fun trackFor(rate: Int, channels: Int): AudioTrack = synchronized(this) {
        track?.takeIf { trackFormat == rate to channels }?.let { return it }
        track?.release()
        streaming = false
        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        // Room for a hiccup on the Wi-Fi or the CPU: at least 4 x the minimum and 400 ms.
        val size = maxOf(min * 4, rate * channels * 2 * 2 / 5)
        AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(size)
            .build()
            .also {
                track = it
                trackFormat = rate to channels
                val ms = it.bufferSizeInFrames * 1000 / rate
                _stats.value = _stats.value.copy(bufferMs = ms)
                Log.i(TAG, "track: $rate Hz x $channels, buffer $ms ms (min ${min / (2 * channels) * 1000 / rate} ms)")
            }
    }

    private fun requestFocus() {
        if (focus != null) return
        // Transient: other apps' sound is lowered while the tower talks. Our own volume is never touched.
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change ->
                focusWord = when (change) {
                    AudioManager.AUDIOFOCUS_GAIN -> "held"
                    AudioManager.AUDIOFOCUS_LOSS -> "lost"
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "lost for now"
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "ducked"
                    else -> "change $change"
                }
                Log.i(TAG, "focus: $focusWord")
                _stats.value = _stats.value.copy(focus = focusWord)
                // A call or another app took the audio for good: stop talking.
                if (change == AudioManager.AUDIOFOCUS_LOSS) hush()
            }
            .build()
        focusWord = if (audio.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) "held" else "refused"
        focus = req
    }

    private fun abandonFocus() {
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
        focusWord = "none"
    }

    companion object {
        private const val TAG = "ClipPlayer"
        /** How long a stopped turn's late clips are dropped at most. */
        private const val MUTE_MS = 120_000L
        /** How long silence bridges a late sentence before the stretch ends. */
        const val LINGER_MS = 2500L

        fun modeWord(mode: Int): String = when (mode) {
            AudioManager.MODE_NORMAL -> "normal"
            AudioManager.MODE_IN_COMMUNICATION -> "in communication"
            AudioManager.MODE_IN_CALL -> "in call"
            AudioManager.MODE_RINGTONE -> "ringtone"
            else -> "mode $mode"
        }
    }
}
