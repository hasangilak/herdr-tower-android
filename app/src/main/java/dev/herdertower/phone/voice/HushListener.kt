package dev.herdertower.phone.voice

import dev.herdertower.phone.api.AudioClip
import dev.herdertower.phone.data.TowerSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Say "stop" to hush the tower while it talks on the phone. Only while a
 * reply plays, the microphone listens through the echo canceller (the call
 * source), and each short burst of speech goes to the tower's own
 * transcriber (`POST /v1/transcribe`, the same Whisper as the Mac's, English
 * and German); a hush word stops it. Words the tower itself just said don't
 * count, so its own voice coming back through the microphone can't stop it.
 */
class HushListener(private val session: TowerSession, private val onHush: () -> Unit) {
    private var job: Job? = null
    /** What it said lately (this sentence and the one before). */
    private val recent = ArrayDeque<String>()

    /** Follows the player: listen while [playing] is a clip and [enabled], stop otherwise. Call on the main thread. */
    fun follow(playing: AudioClip?, enabled: Boolean) {
        playing?.text?.let {
            synchronized(recent) {
                if (recent.lastOrNull() != it) recent.addLast(it)
                while (recent.size > 2) recent.removeFirst()
            }
        }
        if (playing != null && enabled) start() else stop()
    }

    private fun start() {
        if (job?.isActive == true) return
        android.util.Log.i(TAG, "listening while it talks")
        job = session.scope.launch {
            while (isActive) {
                val take = try {
                    Take.start(session.scope, endOnSilence = true, pauseMs = 400, cancelEcho = true)
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "no microphone: ${e.message}")
                    return@launch // no microphone now: the Stop button still works
                }
                try {
                    // A burst of speech ends at a short pause; never longer than a few seconds.
                    withTimeoutOrNull(BURST_MS) { take.ended.await() }
                    val heard = take.heardSpeech
                    val wav = take.stop()
                    android.util.Log.i(TAG, "burst ${take.durationMs} ms, speech=$heard")
                    if (heard && take.durationMs >= 300) check(wav)
                } catch (e: CancellationException) {
                    runCatching { take.stop() }
                    throw e
                }
            }
        }
    }

    private suspend fun check(wav: ByteArray) {
        val said = synchronized(recent) { recent.joinToString(" ") }
        session.act { it.transcribe(wav) }.onSuccess { r ->
            val hush = isHush(r.heard, said)
            android.util.Log.i(TAG, if (hush) "heard a hush word" else "heard ${words(r.heard).size} words, no hush")
            if (hush) {
                onHush()
                stop()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        private const val TAG = "HushListener"
        private const val BURST_MS = 2000L

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
