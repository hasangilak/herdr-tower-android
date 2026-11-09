package dev.herdertower.phone.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import dev.herdertower.phone.api.AudioClip
import dev.herdertower.phone.data.TowerSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Say "stop" to hush the tower while it talks on the phone.
 *
 * While it talks, one plain recording runs (the recognition source: no call
 * mode, no echo-cancelling path that would change how the phone plays its
 * own voice), the same one from the first sentence to the last. [Bursts]
 * cuts short bursts of speech out of it, a word or two; those go to the
 * tower's transcriber (`POST /v1/transcribe`, Whisper, English and German),
 * one at a time, and a hush word stops it. The tower's own sentences heard
 * back are too long to be sent, and words it just said don't count.
 */
class HushListener(private val session: TowerSession, private val onHush: () -> Unit) {
    private var job: Job? = null
    private var stopping: Job? = null
    /** What it said lately (this sentence and the one before). */
    private val recent = ArrayDeque<String>()

    /** Follows the player: listen while [playing] is a clip and [enabled]; a moment after it ends, stop. Main thread. */
    fun follow(playing: AudioClip?, enabled: Boolean) {
        playing?.text?.let {
            synchronized(recent) {
                if (recent.lastOrNull() != it) recent.addLast(it)
                while (recent.size > 2) recent.removeFirst()
            }
        }
        if (!enabled) return stop()
        if (playing != null) {
            stopping?.cancel()
            stopping = null
            start()
        } else if (job != null && stopping == null) {
            stopping = session.scope.launch {
                delay(2000)
                stop()
            }
        }
    }

    private fun start() {
        if (job?.isActive == true) return
        val bursts = Channel<ByteArray>(Channel.CONFLATED)
        job = session.scope.launch {
            val checker = launch { for (pcm in bursts) check(Wav.encode(pcm, RATE, 1)) }
            // The recording is opened and released in the same place: stopped before it began, it never opens.
            launch(Dispatchers.IO) { record(bursts) }.join()
            checker.join()
        }
    }

    /** Records until cancelled, cutting bursts of speech out of it. */
    @SuppressLint("MissingPermission")
    private fun CoroutineScope.record(bursts: Channel<ByteArray>) {
        try {
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = runCatching {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE))
            }.getOrNull()
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                rec?.release()
                Log.w(TAG, "no microphone")
                return // the Stop button still works
            }
            try {
                rec.startRecording()
                Log.i(TAG, "listening while it talks")
                val cut = Bursts(RATE)
                val frame = ShortArray(RATE / 50)
                while (isActive) {
                    val n = rec.read(frame, 0, frame.size)
                    if (n <= 0) break
                    val bytes = ByteArray(n * 2)
                    var sum = 0.0
                    for (i in 0 until n) {
                        val v = frame[i].toInt()
                        bytes[2 * i] = (v and 0xff).toByte()
                        bytes[2 * i + 1] = (v shr 8).toByte()
                        sum += v.toDouble() * v
                    }
                    cut.feed(bytes, sqrt(sum / n))?.let {
                        Log.i(TAG, "burst ${it.size / 32} ms")
                        bursts.trySend(it)
                    }
                }
            } catch (e: IllegalStateException) {
                Log.w(TAG, "the microphone stopped")
            } finally {
                runCatching { rec.stop() }
                rec.release()
            }
        } finally {
            bursts.close()
        }
    }

    private suspend fun check(wav: ByteArray) {
        val said = synchronized(recent) { recent.joinToString(" ") }
        session.act { it.transcribe(wav) }.onSuccess { r ->
            val hush = isHush(r.heard, said)
            Log.i(TAG, if (hush) "heard a hush word" else "heard ${words(r.heard).size} words, no hush")
            if (hush) {
                onHush()
                stop()
            }
        }
    }

    fun stop() {
        stopping?.cancel()
        stopping = null
        if (job != null) Log.i(TAG, "stopped listening")
        job?.cancel()
        job = null
    }

    companion object {
        private const val TAG = "HushListener"
        private const val RATE = 16_000

        /** English and German words for "be quiet". */
        val WORDS = setOf("hush", "stop", "quiet", "shush", "silence", "enough", "ruhe", "stopp", "halt", "still", "schweig", "aufhören")

        private fun words(s: String) = s.lowercase().split(Regex("""[^\p{L}]+""")).filter { it.isNotEmpty() }

        /** Whether [heard] says a hush word that isn't just the tower's own sentence ([speaking]) heard back. */
        fun isHush(heard: String, speaking: String): Boolean {
            val said = words(speaking).toSet()
            return words(heard).any { it in WORDS && it !in said }
        }
    }
}
