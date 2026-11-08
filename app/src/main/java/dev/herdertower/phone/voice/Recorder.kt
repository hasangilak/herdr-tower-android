package dev.herdertower.phone.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

/**
 * One take from the microphone: 16 kHz mono 16-bit PCM, as the tower prefers.
 * With [endOnSilence] (tap to talk) the take ends by itself after a pause
 * that follows speech; otherwise it runs until [stop] (hold to talk).
 */
class Take private constructor(private val record: AudioRecord, @Volatile private var endOnSilence: Boolean, private val pauseFrames: Int) {
    private val pcm = ByteArrayOutputStream()
    private val _level = MutableStateFlow(0f)
    /** 0..1, for the button's ring. */
    val level: StateFlow<Float> = _level

    /** Whether speech has been heard yet (dictation asks for words only then: Whisper guesses at silence). */
    @Volatile var heardSpeech = false
        private set

    /** Completes when the take ended by itself (a pause after speech, nothing said, or too long). */
    val ended = CompletableDeferred<Reason>()

    enum class Reason { Silence, NothingSaid, TooLong }

    private var job: Job? = null

    private fun start(scope: CoroutineScope) {
        record.startRecording()
        job = scope.launch(Dispatchers.IO) {
            val frame = ShortArray(RATE / 50) // 20 ms
            val bytes = ByteArray(frame.size * 2)
            var noise = 0.0
            var frames = 0
            var spoke = false
            var quiet = 0
            while (isActive) {
                val n = record.read(frame, 0, frame.size)
                if (n <= 0) break
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
                // The room's noise from the first 300 ms, then speech is clearly above it.
                if (frames <= 15) noise = maxOf(noise, rms)
                val threshold = maxOf(500.0, noise * 2.5)
                if (rms > threshold) {
                    spoke = true
                    heardSpeech = true
                    quiet = 0
                } else {
                    quiet++
                }
                if (frames >= MAX_FRAMES) {
                    ended.complete(Reason.TooLong)
                    break
                }
                if (endOnSilence) {
                    if (spoke && quiet >= pauseFrames) {
                        ended.complete(Reason.Silence)
                        break
                    }
                    if (!spoke && frames >= NOTHING_FRAMES) {
                        ended.complete(Reason.NothingSaid)
                        break
                    }
                }
            }
        }
    }

    /** Hold to talk: only [stop] ends it (still capped at the tower's limit). */
    fun holdUntilStopped() {
        endOnSilence = false
    }

    /** A tap after all: from now on a pause after speech ends it. */
    fun endOnSilenceFromNow() {
        endOnSilence = true
    }

    /** The take so far as a WAV, while it goes on (dictation shows its words as they come). */
    fun snapshot(): ByteArray = Wav.encode(synchronized(pcm) { pcm.toByteArray() }, RATE, 1)

    val durationMs: Long get() = synchronized(pcm) { pcm.size().toLong() * 1000 / (RATE * 2) }

    /** Stops the microphone and returns the take as a WAV (16 kHz mono 16-bit). */
    suspend fun stop(): ByteArray {
        job?.let {
            if (it.isActive) {
                // Let the read loop finish its frame, then end it.
                runCatching { record.stop() }
                it.cancel()
            }
            it.join()
        }
        runCatching { record.stop() }
        record.release()
        _level.value = 0f
        return Wav.encode(synchronized(pcm) { pcm.toByteArray() }, RATE, 1)
    }

    companion object {
        const val RATE = 16_000
        private const val MAX_FRAMES = 115 * 50 // the tower takes up to 120 s
        private const val END_PAUSE_FRAMES = 60 // 1.2 s of quiet after speech
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
            return Take(rec, endOnSilence, pauseMs / 20).also { it.start(scope) }
        }
    }
}
