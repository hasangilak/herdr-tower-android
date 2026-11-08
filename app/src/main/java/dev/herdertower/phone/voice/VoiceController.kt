package dev.herdertower.phone.voice

import dev.herdertower.phone.data.TowerSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Talking into the phone. Hold the button to talk and let go to send, or
 * tap it once: then it listens until you pause (or you tap again). Starting
 * a take hushes the tower first (barge-in), here and on the Mac.
 */
class VoiceController(private val session: TowerSession) {
    sealed interface Phase {
        data object Idle : Phase
        /** [handsFree]: a tap started it, and a pause ends it. */
        data class Recording(val handsFree: Boolean) : Phase
        data object Sending : Phase
    }

    /** What the Mac heard from the last take. */
    data class Heard(val text: String, val sent: Boolean, val atMs: Long)

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _heard = MutableStateFlow<Heard?>(null)
    val heard: StateFlow<Heard?> = _heard.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var take: Take? = null
    private var watcher: Job? = null
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
                pressedAt = System.currentTimeMillis()
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
        val held = System.currentTimeMillis() - pressedAt
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
        _level.value = 0f
        _pause.value = 0f
        _waiting.value = false
        session.scope.launch { t.stop() }
        _phase.value = Phase.Idle
    }

    fun clearError() {
        _error.value = null
    }

    private fun begin(handsFree: Boolean) {
        _error.value = null
        beforeTake()
        // Barge in: the tower stops talking over you.
        session.hush()
        _waiting.value = false
        val t = try {
            Take.start(session.scope, endOnSilence = true).also { it.what = "voice"; if (!handsFree) it.holdUntilStopped() }
        } catch (e: Exception) {
            _error.value = e.message ?: "The microphone isn't available."
            return
        }
        take = t
        _phase.value = Phase.Recording(handsFree)
        levels?.cancel()
        levels = session.scope.launch {
            launch { t.pauseProgress.collect { _pause.value = it } }
            t.level.collect { _level.value = it }
        }
        watcher = session.scope.launch {
            when (t.ended.await()) {
                Take.Reason.NothingSaid -> {
                    cancel()
                    _error.value = "Didn't hear anything."
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
        _level.value = 0f
        _pause.value = 0f
        _waiting.value = false
        _phase.value = Phase.Sending
        session.scope.launch {
            val wav = t.stop()
            if (t.durationMs < MIN_MS) {
                _error.value = "Too short. Hold the button while you talk, or tap once."
                _phase.value = Phase.Idle
                return@launch
            }
            session.act { it.voice(wav) }
                .onSuccess { r ->
                    if (r.sent) session.linger()
                    _heard.value = Heard(r.heard, r.sent, System.currentTimeMillis())
                    if (!r.sent && r.heard.isBlank()) _error.value = "The Mac heard nothing it could send."
                }
                .onFailure { _error.value = TowerSession.describe(it) }
            _phase.value = Phase.Idle
        }
    }

    private companion object {
        const val TAP_MS = 350L
        const val MIN_MS = 300L
    }
}

