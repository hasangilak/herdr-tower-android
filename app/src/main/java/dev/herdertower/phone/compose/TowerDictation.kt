package dev.herdertower.phone.compose

import dev.herdertower.phone.data.TowerSession
import dev.herdertower.phone.voice.Take
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Something that turns speech into text for the draft. */
interface DictationEngine {
    val active: StateFlow<Boolean>
    val level: StateFlow<Float>
    val error: StateFlow<String?>
    /** Starts listening; [onText] gets the whole dictation so far each time it changes. */
    fun start(onText: (String) -> Unit)
    fun stop()
}

/**
 * Dictation with the tower's Whisper (the same model and vocabulary as the
 * Mac's): the take so far goes to `POST /v1/transcribe` about every second,
 * one request at a time, and what comes back fills the draft; when you stop
 * (or pause for a while) the whole take is transcribed once more for the
 * final words. Nothing reaches the tower's brain.
 */
class TowerDictation(private val session: TowerSession) : DictationEngine {
    private val _active = MutableStateFlow(false)
    override val active: StateFlow<Boolean> = _active.asStateFlow()
    private val _level = MutableStateFlow(0f)
    override val level: StateFlow<Float> = _level.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    override val error: StateFlow<String?> = _error.asStateFlow()

    private var take: Take? = null
    private var job: Job? = null
    private var onText: (String) -> Unit = {}

    override fun start(onText: (String) -> Unit) {
        stop()
        _error.value = null
        this.onText = onText
        val t = try {
            Take.start(session.scope, endOnSilence = true, pauseMs = PAUSE_MS).also { it.what = "dictation" }
        } catch (e: Exception) {
            _error.value = e.message ?: "The microphone isn't available."
            return
        }
        take = t
        _active.value = true
        job = session.scope.launch {
            launch { t.level.collect { _level.value = it } }
            // The words so far, while you talk: one request at a time.
            val partials = launch {
                var sentMs = 0L
                while (true) {
                    delay(INTERVAL_MS)
                    val ms = t.durationMs
                    if (!t.heardSpeech || ms < 700 || ms - sentMs < 600) continue
                    sentMs = ms
                    session.act { it.transcribe(t.snapshot()) }.onSuccess { r -> if (r.heard.isNotBlank()) onText(r.heard) }
                }
            }
            val reason = t.ended.await()
            partials.cancel()
            finish(t, nothingSaid = reason == Take.Reason.NothingSaid)
        }
    }

    /** Stop listening: the whole take is transcribed once more for the final words. */
    override fun stop() {
        val t = take ?: return
        val j = job
        session.scope.launch {
            j?.children?.forEach { it.cancel() }
            j?.cancel()
            finish(t, nothingSaid = false)
        }
    }

    private suspend fun finish(t: Take, nothingSaid: Boolean) {
        if (take !== t) return
        take = null
        val wav = try { t.stop() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        _level.value = 0f
        when {
            nothingSaid || !t.heardSpeech -> {
                onText("") // whatever was guessed from the silence goes
                _error.value = "Didn't hear anything."
            }
            wav != null && t.durationMs >= 300 -> session.act { it.transcribe(wav) }
                .onSuccess { r -> if (r.heard.isNotBlank()) onText(r.heard) else _error.value = "Didn't catch that." }
                .onFailure { _error.value = "Couldn't transcribe: ${TowerSession.describe(it)}" }
        }
        _active.value = false
    }

    private companion object {
        const val INTERVAL_MS = 1000L
        /** A pause this long after speech ends the dictation. */
        const val PAUSE_MS = 2500
    }
}
