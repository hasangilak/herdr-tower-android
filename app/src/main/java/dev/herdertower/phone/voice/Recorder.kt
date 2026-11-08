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

    /** 0..1 through the pause that will end a tap take (a countdown to sending); 0 while talking. */
    private val _pause = MutableStateFlow(0f)
    val pauseProgress: StateFlow<Float> = _pause

    @Volatile private var detector: SpeechEnd? = null
    @Volatile var endedBy: String = ""
        private set
    /** What the take is for, in the diagnostics: "voice", "dictation", "answer". */
    var what: String = ""

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
                    if (endedBy.isEmpty()) endedBy = "the microphone stopped ($n)"
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
    fun holdUntilStopped() {
        endOnSilence = false
        _pause.value = 0f
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
        if (endedBy.isEmpty()) endedBy = "stopped (sent or released)"
        job?.let {
            if (it.isActive) {
                // Let the read loop finish its frame, then end it.
                runCatching { record.stop() }
                it.cancel()
            }
            it.join()
        }
        Takes.last.value = info()
        android.util.Log.i("Take", info().line())
        runCatching { record.stop() }
        record.release()
        _level.value = 0f
        return Wav.encode(synchronized(pcm) { pcm.toByteArray() }, RATE, 1)
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
            return Take(rec, endOnSilence, pauseMs / 20).also { it.start(scope) }
        }
    }
}
