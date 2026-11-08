package dev.herdertower.phone.compose

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Dictation into the draft with the phone's own speech recognizer: the
 * words appear as you say them, then stay in the input to edit, and nothing
 * is sent until you send it. (The tower's `POST /v1/voice` sends what it
 * hears at once, so it can't be used for this.)
 */
class Dictation(private val context: Context) {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var recognizer: SpeechRecognizer? = null

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    /** Starts listening; [onText] gets the whole dictation so far each time it grows. Call on the main thread. */
    fun start(onText: (String) -> Unit) {
        stop()
        _error.value = null
        if (!available) {
            _error.value = "This phone has no speech recognizer for dictation."
            return
        }
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        var done = "" // finished phrases, when the recognizer restarts after a pause
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _active.value = true
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {
                _level.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    if (done.isEmpty()) _error.value = "Didn't catch that."
                } else if (error != SpeechRecognizer.ERROR_CLIENT) {
                    _error.value = "Dictation stopped (${describe(error)})."
                }
                finish()
            }
            override fun onResults(results: Bundle?) {
                val t = best(results)
                if (t.isNotBlank()) {
                    done = join(done, t)
                    onText(done)
                }
                finish()
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val t = best(partialResults)
                if (t.isNotBlank()) onText(join(done, t))
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
        _active.value = true
        r.startListening(intent)
    }

    fun stop() {
        recognizer?.let {
            runCatching { it.stopListening() }
            runCatching { it.destroy() }
        }
        recognizer = null
        _active.value = false
        _level.value = 0f
    }

    private fun finish() {
        recognizer?.let { runCatching { it.destroy() } }
        recognizer = null
        _active.value = false
        _level.value = 0f
    }

    private fun best(b: Bundle?): String = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    private fun join(a: String, b: String) = if (a.isEmpty()) b else "$a $b"

    private fun describe(e: Int) = when (e) {
        SpeechRecognizer.ERROR_AUDIO -> "microphone"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "no microphone permission"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer busy"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "language not available"
        else -> "error $e"
    }
}
