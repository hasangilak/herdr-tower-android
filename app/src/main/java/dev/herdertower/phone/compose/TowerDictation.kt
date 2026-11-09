package dev.herdertower.phone.compose

import dev.herdertower.phone.api.VoiceResult
import dev.herdertower.phone.data.TowerSession
import dev.herdertower.phone.voice.Mic
import dev.herdertower.phone.voice.Take
import dev.herdertower.phone.voice.VoiceController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
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
    /** The error was shown: don't show it again. */
    fun clearError()
}

/**
 * Dictation with the tower's Whisper (the same model and vocabulary as the
 * Mac's): the take so far goes to `POST /v1/transcribe` while you talk, one
 * request at a time, and what comes back fills the draft; when you stop
 * (or pause for a while) the whole take is transcribed once more for the
 * final words. Nothing reaches the tower's brain.
 */
class TowerDictation(
    private val scope: CoroutineScope,
    private val transcribe: suspend (ByteArray) -> Result<VoiceResult>,
    private val open: () -> Mic,
) : DictationEngine {
    constructor(session: TowerSession) : this(
        session.scope,
        { wav -> session.act { it.transcribe(wav) } },
        { Take.start(session.scope, endOnSilence = true, pauseMs = PAUSE_MS).also { it.what = "dictation" } },
    )

    private val _active = MutableStateFlow(false)
    override val active: StateFlow<Boolean> = _active.asStateFlow()
    private val _level = MutableStateFlow(0f)
    override val level: StateFlow<Float> = _level.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    override val error: StateFlow<String?> = _error.asStateFlow()

    private var take: Mic? = null
    private var job: Job? = null
    /** Where the words of the take running now go. */
    private var onText: (String) -> Unit = {}

    override fun clearError() {
        _error.value = null
    }

    override fun start(onText: (String) -> Unit) {
        stop()
        _error.value = null
        val t = try {
            open()
        } catch (e: Exception) {
            _error.value = e.message ?: "The microphone isn't available."
            return
        }
        take = t
        this.onText = onText
        _active.value = true
        job = scope.launch {
            launch { t.level.collect { _level.value = it } }
            // The words so far, while you talk: one request at a time, less often as the take grows (each sends all of it).
            val partials = launch {
                var sentMs = 0L
                while (true) {
                    delay(INTERVAL_MS)
                    val ms = t.durationMs
                    if (!t.heardSpeech || ms < 700 || ms - sentMs < maxOf(600L, sentMs / 10)) continue
                    sentMs = ms
                    transcribe(t.snapshot()).onSuccess { r -> VoiceController.clean(r.heard).takeIf { it.isNotBlank() }?.let(onText) }
                }
            }
            val reason = t.ended.await()
            partials.cancelAndJoin()
            finish(t, reason, onText)
        }
    }

    /** Stop listening: the whole take is transcribed once more for the final words. */
    override fun stop() {
        val t = take ?: return
        val j = job
        val say = onText
        scope.launch {
            j?.cancelAndJoin()
            finish(t, null, say)
        }
    }

    private suspend fun finish(t: Mic, reason: Take.Reason?, onText: (String) -> Unit) {
        if (take !== t) return
        take = null
        val wav = try { t.stop() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        _level.value = 0f
        when {
            reason == Take.Reason.MicFailed && !t.heardSpeech -> {
                onText("")
                _error.value = "The microphone stopped."
            }
            reason == Take.Reason.NothingSaid || !t.heardSpeech -> {
                onText("") // whatever was guessed from the silence goes
                _error.value = "Didn't hear anything."
            }
            wav != null && t.durationMs >= 300 -> transcribe(wav)
                .onSuccess { r -> VoiceController.clean(r.heard).let { if (it.isNotBlank()) onText(it) else _error.value = "Didn't catch that." } }
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
