package dev.herdertower.phone.compose

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What dictation needs from a speech recognizer: the phone's ([SystemRecognizer]), or a test's. */
internal interface Recognizer {
    fun start(events: RecognizerEvents)
    /** Stop listening: the final words still come, through [RecognizerEvents.result]. */
    fun stop()
    fun destroy()
}

/** What a recognizer reports. */
internal interface RecognizerEvents {
    fun ready()
    fun level(rmsdB: Float)
    fun partial(text: String)
    fun result(text: String)
    fun error(code: Int)
}

/**
 * Dictation with the phone's own speech recognizer, for when the tower
 * isn't reachable (otherwise [TowerDictation] uses the Mac's Whisper): the
 * words appear as you say them, then stay in the input to edit.
 */
class Dictation internal constructor(
    private val isAvailable: () -> Boolean,
    private val newRecognizer: () -> Recognizer,
    /** Runs a block after a delay, on the main thread. */
    private val later: (Long, () -> Unit) -> Unit,
) : DictationEngine {
    constructor(context: Context) : this(
        { SpeechRecognizer.isRecognitionAvailable(context) },
        { SystemRecognizer(context) },
        { ms, block -> Handler(Looper.getMainLooper()).postDelayed(block, ms) },
    )

    private val _active = MutableStateFlow(false)
    override val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _level = MutableStateFlow(0f)
    override val level: StateFlow<Float> = _level.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    override val error: StateFlow<String?> = _error.asStateFlow()

    private var recognizer: Recognizer? = null
    /** Stop was asked for: the final words are on their way. */
    private var stopping = false

    val available: Boolean get() = isAvailable()

    override fun clearError() {
        _error.value = null
    }

    /** Starts listening; [onText] gets the whole dictation so far each time it grows. Call on the main thread. */
    override fun start(onText: (String) -> Unit) {
        finish()
        _error.value = null
        if (!available) {
            _error.value = "This phone has no speech recognizer for dictation."
            return
        }
        val r = newRecognizer()
        recognizer = r
        _active.value = true
        r.start(object : RecognizerEvents {
            override fun ready() {
                if (recognizer === r) _active.value = true
            }
            override fun level(rmsdB: Float) {
                if (recognizer === r) _level.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            }
            override fun partial(text: String) {
                if (recognizer === r && text.isNotBlank()) onText(text)
            }
            override fun result(text: String) {
                if (recognizer !== r) return
                if (text.isNotBlank()) onText(text)
                finish()
            }
            override fun error(code: Int) {
                if (recognizer !== r) return
                if (code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    if (!stopping) _error.value = "Didn't catch that."
                } else if (code != SpeechRecognizer.ERROR_CLIENT) {
                    _error.value = "Dictation stopped (${describe(code)})."
                }
                finish()
            }
        })
    }

    /** Stops listening; the final words still come (the recognizer ends itself), or it gives up after a few seconds. */
    override fun stop() {
        val r = recognizer ?: return
        if (stopping) return
        stopping = true
        runCatching { r.stop() }
        later(FINAL_WAIT_MS) { if (recognizer === r) finish() }
    }

    private fun finish() {
        recognizer?.let { runCatching { it.destroy() } }
        recognizer = null
        stopping = false
        _active.value = false
        _level.value = 0f
    }

    private fun describe(e: Int) = when (e) {
        SpeechRecognizer.ERROR_AUDIO -> "microphone"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "no microphone permission"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer busy"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "language not available"
        else -> "error $e"
    }

    internal companion object {
        /** How long the final words may take after Stop. */
        const val FINAL_WAIT_MS = 4000L
    }
}

/** The phone's speech recognizer, with partial results and a 2.5 s pause to end. */
private class SystemRecognizer(private val context: Context) : Recognizer {
    private var r: SpeechRecognizer? = null

    override fun start(events: RecognizerEvents) {
        val sr = SpeechRecognizer.createSpeechRecognizer(context)
        r = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = events.ready()
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) = events.level(rmsdB)
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) = events.error(error)
            override fun onResults(results: Bundle?) = events.result(best(results))
            override fun onPartialResults(partialResults: Bundle?) = events.partial(best(partialResults))
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
        sr.startListening(intent)
    }

    override fun stop() {
        r?.stopListening()
    }

    override fun destroy() {
        r?.destroy()
        r = null
    }

    private fun best(b: Bundle?): String = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
}
