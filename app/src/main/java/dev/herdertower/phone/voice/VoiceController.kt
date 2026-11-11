package dev.herdertower.phone.voice

import dev.herdertower.phone.api.VoiceResult
import dev.herdertower.phone.data.TowerSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where a take from the talk button goes. */
sealed interface TalkTarget {
    /** The main tower, as spoken. */
    data object Main : TalkTarget

    /** A space's own tower. */
    data class Space(val name: String) : TalkTarget

    /** Written down only, into a field on screen (a new space's goal). */
    class Into(val take: (String) -> Unit) : TalkTarget
}

/**
 * Talking into the phone. Hold the button to talk and let go to send, or
 * tap it once: then it listens until you pause (or you tap again). Starting
 * a take hushes the tower first (barge-in), here and on the Mac.
 */
class VoiceController(
    private val scope: CoroutineScope,
    private val hush: () -> Unit,
    private val send: suspend (ByteArray, TalkTarget) -> Result<VoiceResult>,
    private val linger: () -> Unit,
    private val open: () -> Mic,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Writes down the take so far (the Mac's Whisper, nothing sent), for the words shown while talking; null: none. */
    private val words: (suspend (ByteArray) -> String?)? = null,
) {
    constructor(session: TowerSession) : this(
        session.scope,
        session::hush,
        session::take,
        session::linger,
        { Take.start(session.scope, endOnSilence = true).also { it.what = "voice" } },
        words = { wav -> session.act { it.transcribe(wav) }.getOrNull()?.heard },
    )

    sealed interface Phase {
        data object Idle : Phase
        /** [handsFree]: a tap started it, and a pause ends it. */
        data class Recording(val handsFree: Boolean) : Phase
        data object Sending : Phase
    }

    /** What the Mac heard from the last take, and where it went. */
    data class Heard(val text: String, val sent: Boolean, val atMs: Long, val target: TalkTarget = TalkTarget.Main)

    /** Where the next take goes: set by the screen in front (the main tower unless it says otherwise). */
    @Volatile var target: TalkTarget = TalkTarget.Main
    /** Where the take in progress goes: as it was when it began, whatever screen comes after. */
    private var going: TalkTarget = TalkTarget.Main

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _heard = MutableStateFlow<Heard?>(null)
    val heard: StateFlow<Heard?> = _heard.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _hearing = MutableStateFlow("")
    /** The words of the take in progress, as the Mac has written them down so far. */
    val hearing: StateFlow<String> = _hearing.asStateFlow()

    private var take: Mic? = null
    private var watcher: Job? = null
    private var partials: Job? = null
    private var pressedAt = 0L

    /** Runs before a take starts (dictation into the draft stops: one microphone at a time). */
    var beforeTake: () -> Unit = {}

    private val _pause = MutableStateFlow(0f)
    /** 0..1 through the pause that sends a tap take: a countdown; talking again resets it. */
    val pause: StateFlow<Float> = _pause.asStateFlow()

    private val _waiting = MutableStateFlow(false)
    /** The user asked to keep listening: a pause doesn't send, a tap does. */
    val waiting: StateFlow<Boolean> = _waiting.asStateFlow()

    /** Keep listening through pauses; tap the button to send. */
    fun keepListening() {
        val t = take ?: return
        t.holdUntilStopped()
        _pause.value = 0f
        _waiting.value = true
    }

    private val _level = MutableStateFlow(0f)
    /** The microphone level of the take in progress, 0..1. */
    val level: StateFlow<Float> = _level.asStateFlow()
    private var levels: Job? = null

    /** The button went down: start a take (or, during a tap take, this press ends it on release). */
    fun press() {
        when (_phase.value) {
            Phase.Idle -> {
                pressedAt = clock()
                begin(handsFree = false)
            }
            is Phase.Recording, Phase.Sending -> pressedAt = 0
        }
    }

    /** The button came up. A short press means tap: keep listening until a pause. */
    fun release() {
        val p = _phase.value
        if (p !is Phase.Recording) return
        if (pressedAt == 0L) return finish() // second tap ends a hands-free take
        val held = clock() - pressedAt
        pressedAt = 0
        if (!p.handsFree && held < TAP_MS) {
            _phase.value = Phase.Recording(handsFree = true)
            take?.endOnSilenceFromNow()
        } else if (!p.handsFree) {
            finish()
        }
    }

    /** From the notification: one tap-to-talk take, or end the one running. */
    fun toggle() {
        when (_phase.value) {
            Phase.Idle -> begin(handsFree = true)
            is Phase.Recording -> finish()
            Phase.Sending -> {}
        }
    }

    fun cancel() {
        val t = take ?: return
        take = null
        watcher?.cancel()
        levels?.cancel()
        partials?.cancel()
        _hearing.value = ""
        _level.value = 0f
        _pause.value = 0f
        _waiting.value = false
        scope.launch { t.stop() }
        _phase.value = Phase.Idle
    }

    fun clearError() {
        _error.value = null
    }

    private fun begin(handsFree: Boolean) {
        _error.value = null
        beforeTake()
        // Barge in: the tower stops talking over you.
        hush()
        _waiting.value = false
        val t = try {
            open().also { if (!handsFree) it.holdUntilStopped() }
        } catch (e: Exception) {
            _error.value = e.message ?: "The microphone isn't available."
            return
        }
        take = t
        going = target
        _hearing.value = ""
        _phase.value = Phase.Recording(handsFree)
        // The words so far, while you talk: one request at a time, less often as the take grows (each sends all of it).
        partials?.cancel()
        partials = words?.let { write ->
            scope.launch {
                var sentMs = 0L
                while (true) {
                    kotlinx.coroutines.delay(WORDS_EVERY_MS)
                    val ms = t.durationMs
                    if (!t.heardSpeech || ms < 700 || ms - sentMs < maxOf(600L, sentMs / 10)) continue
                    sentMs = ms
                    val heard = write(t.snapshot())?.let(::clean)
                    if (take === t && !heard.isNullOrBlank()) _hearing.value = heard
                }
            }
        }
        levels?.cancel()
        levels = scope.launch {
            launch { t.pauseProgress.collect { _pause.value = it } }
            t.level.collect { _level.value = it }
        }
        watcher = scope.launch {
            when (t.ended.await()) {
                Take.Reason.NothingSaid -> {
                    cancel()
                    _error.value = "Didn't hear anything."
                }
                // The microphone failed: send what was said, if anything was.
                Take.Reason.MicFailed -> if (t.heardSpeech) finish() else {
                    cancel()
                    _error.value = "The microphone stopped."
                }
                else -> finish()
            }
        }
    }

    private fun finish() {
        val t = take ?: return
        take = null
        watcher?.cancel()
        levels?.cancel()
        partials?.cancel()
        _level.value = 0f
        _pause.value = 0f
        _waiting.value = false
        _phase.value = Phase.Sending
        scope.launch {
            val wav = t.stop()
            if (t.durationMs < MIN_MS) {
                _error.value = "Too short. Hold the button while you talk, or tap once."
                _phase.value = Phase.Idle
                return@launch
            }
            val to = going
            send(wav, to)
                .onSuccess { r ->
                    if (r.sent) linger()
                    _heard.value = Heard(clean(r.heard), r.sent, System.currentTimeMillis(), to)
                    if (!r.sent && clean(r.heard).isBlank()) _error.value = "The Mac heard nothing it could send."
                }
                .onFailure { _error.value = TowerSession.describe(it) }
            _phase.value = Phase.Idle
        }
    }

    companion object {
        /** Whisper's markers for no speech ("[BLANK_AUDIO]", "(silence)") aren't words. */
        fun clean(heard: String): String = heard.replace(Regex("""\[[A-Z_ ]+]|\((?:silence|music|noise)\)""", RegexOption.IGNORE_CASE), "").trim()
        private const val TAP_MS = 350L
        private const val WORDS_EVERY_MS = 1000L
        private const val MIN_MS = 300L
    }
}

