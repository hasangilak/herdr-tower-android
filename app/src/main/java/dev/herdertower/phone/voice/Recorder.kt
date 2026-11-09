package dev.herdertower.phone.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/** A take as the voice button and dictation use it (a fake one in the tests). */
interface Mic {
    /** 0..1, for the button's ring. */
    val level: StateFlow<Float>
    /** 0..1 through the pause that will end a tap take; 0 while talking. */
    val pauseProgress: StateFlow<Float>
    /** Completes when the take ended: by itself, or by [stop]. */
    val ended: Deferred<Take.Reason>
    /** Whether speech has been heard yet. */
    val heardSpeech: Boolean
    val durationMs: Long
    /** Only [stop] ends it (still capped at the tower's limit). */
    fun holdUntilStopped()
    /** From now on a pause after speech ends it. */
    fun endOnSilenceFromNow()
    /** The take so far as a WAV, while it goes on. */
    suspend fun snapshot(): ByteArray
    /** Stops the microphone and returns the whole take as a WAV. Safe to call more than once. */
    suspend fun stop(): ByteArray
}

/**
 * One take from the microphone: 16 kHz mono 16-bit PCM, as the tower prefers.
 * With [endOnSilence] (tap to talk) the take ends by itself after a pause
 * that follows speech; otherwise it runs until [stop] (hold to talk).
 */
class Take private constructor(private val record: AudioRecord, @Volatile private var endOnSilence: Boolean, private val pauseFrames: Int) : Mic {
    private val pcm = ByteArrayOutputStream()
    private val _level = MutableStateFlow(0f)
    override val level: StateFlow<Float> = _level

    /** Whether speech has been heard yet (dictation asks for words only then: Whisper guesses at silence). */
    @Volatile override var heardSpeech = false
        private set

    /** Completes when the take ended: a pause after speech, nothing said, too long, the microphone failing, or [stop]. */
    override val ended = CompletableDeferred<Reason>()

    enum class Reason { Silence, NothingSaid, TooLong, MicFailed, Stopped }

    private var job: Job? = null

    /** 0..1 through the pause that will end a tap take (a countdown to sending); 0 while talking. */
    private val _pause = MutableStateFlow(0f)
    override val pauseProgress: StateFlow<Float> = _pause

    @Volatile private var detector: SpeechEnd? = null
    @Volatile var endedBy: String = ""
        private set
    /** What the take is for, in the diagnostics: "voice", "dictation", "answer". */
    var what: String = ""

    private val stopping = AtomicBoolean(false)
    private val stopped = CompletableDeferred<ByteArray>()

    /** What this take did, for the diagnostics: length, why it ended, the input, the room. */
    fun info(): TakeInfo {
        val d = detector
        val route = runCatching { record.routedDevice?.let { inputName(it.type) + (it.productName?.toString()?.takeIf { n -> n.isNotBlank() }?.let { n -> " ($n)" } ?: "") } }.getOrNull() ?: "?"
        return TakeInfo(durationMs, endedBy.ifEmpty { "still listening" }, RATE, route, d?.floor ?: 0.0, d?.threshold ?: 0.0, pauseFrames * 20, d?.voicedMs ?: 0, what)
    }

    private fun start(scope: CoroutineScope) {
        record.startRecording()
        val vad = SpeechEnd(pauseFrames * 20).also { detector = it }
        job = scope.launch(Dispatchers.IO) {
            val frame = ShortArray(RATE / 50) // 20 ms
            val bytes = ByteArray(frame.size * 2)
            var frames = 0
            while (isActive) {
                val n = record.read(frame, 0, frame.size)
                if (n <= 0) {
                    // Stopped from outside, or the microphone failed: the take is over either way.
                    if (endedBy.isEmpty()) endedBy = "the microphone stopped ($n)"
                    if (!stopping.get()) ended.complete(Reason.MicFailed)
                    break
                }
                for (i in 0 until n) {
                    bytes[2 * i] = (frame[i].toInt() and 0xff).toByte()
                    bytes[2 * i + 1] = (frame[i].toInt() shr 8).toByte()
                }
                synchronized(pcm) { pcm.write(bytes, 0, n * 2) }
                var sum = 0.0
                for (i in 0 until n) sum += frame[i].toDouble() * frame[i]
                val rms = sqrt(sum / n)
                _level.value = (rms / 6000.0).toFloat().coerceIn(0f, 1f)
                frames++
                val paused = vad.feed(rms)
                if (vad.spoke) heardSpeech = true
                _pause.value = if (endOnSilence) vad.pauseProgress else 0f
                if (frames >= MAX_FRAMES) {
                    endedBy = "too long (${MAX_FRAMES / 50} s)"
                    ended.complete(Reason.TooLong)
                    break
                }
                if (endOnSilence) {
                    if (paused) {
                        endedBy = "a pause of ${vad.silentMs / 1000.0} s"
                        ended.complete(Reason.Silence)
                        break
                    }
                    if (!vad.spoke && frames >= NOTHING_FRAMES) {
                        endedBy = "nothing said"
                        ended.complete(Reason.NothingSaid)
                        break
                    }
                }
            }
            _pause.value = 0f
        }
    }

    /** Hold to talk, or "keep listening": only [stop] ends it (still capped at the tower's limit). */
    override fun holdUntilStopped() {
        endOnSilence = false
        _pause.value = 0f
    }

    /** A tap after all: from now on a pause after speech ends it. */
    override fun endOnSilenceFromNow() {
        endOnSilence = true
    }

    /** The take so far as a WAV, while it goes on (dictation shows its words as they come). Built off the main thread. */
    override suspend fun snapshot(): ByteArray = withContext(Dispatchers.Default) { Wav.encode(synchronized(pcm) { pcm.toByteArray() }, RATE, 1) }

    override val durationMs: Long get() = synchronized(pcm) { pcm.size().toLong() * 1000 / (RATE * 2) }

    /** Stops the microphone and returns the take as a WAV (16 kHz mono 16-bit). A second call gets the same WAV. */
    override suspend fun stop(): ByteArray {
        if (!stopping.compareAndSet(false, true)) return stopped.await()
        if (endedBy.isEmpty()) endedBy = "stopped (sent or released)"
        job?.let {
            if (it.isActive) {
                // Let the read loop finish its frame, then end it.
                runCatching { record.stop() }
                it.cancel()
            }
            it.join()
        }
        ended.complete(Reason.Stopped)
        Takes.last.value = info()
        android.util.Log.i("Take", info().line())
        runCatching { record.stop() }
        record.release()
        _level.value = 0f
        val wav = withContext(Dispatchers.Default) { Wav.encode(synchronized(pcm) { pcm.toByteArray() }, RATE, 1) }
        stopped.complete(wav)
        return wav
    }

    companion object {
        const val RATE = 16_000

        fun inputName(type: Int): String = when (type) {
            android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC -> "built-in mic"
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth (SCO)"
            android.media.AudioDeviceInfo.TYPE_USB_HEADSET, android.media.AudioDeviceInfo.TYPE_USB_DEVICE -> "USB"
            android.media.AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE"
            android.media.AudioDeviceInfo.TYPE_TELEPHONY -> "telephony"
            else -> "input $type"
        }
        private const val MAX_FRAMES = 115 * 50 // the tower takes up to 120 s
        /** Quiet after speech that ends a tap take: long enough for a breath or a thought mid-sentence. */
        private const val END_PAUSE_FRAMES = 100 // 2 s
        private const val NOTHING_FRAMES = 8 * 50

        /** Starts recording; the caller must hold RECORD_AUDIO. Throws if the microphone can't be opened. */
        @SuppressLint("MissingPermission")
        fun start(scope: CoroutineScope, endOnSilence: Boolean, pauseMs: Int = END_PAUSE_FRAMES * 20): Take {
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE))
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                throw IllegalStateException("The microphone isn't available.")
            }
            val take = Take(rec, endOnSilence, pauseMs / 20)
            try {
                take.start(scope)
            } catch (e: Exception) {
                // It didn't start: give the microphone back.
                runCatching { rec.stop() }
                rec.release()
                throw IllegalStateException("The microphone isn't available.", e)
            }
            return take
        }
    }
}
