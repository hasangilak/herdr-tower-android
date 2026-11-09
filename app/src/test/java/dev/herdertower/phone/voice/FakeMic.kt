package dev.herdertower.phone.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

/** A take without a microphone: the test says how long it is, whether it heard speech and how it ends. */
class FakeMic(var ms: Long = 2000, var speech: Boolean = true) : Mic {
    override val level = MutableStateFlow(0f)
    override val pauseProgress = MutableStateFlow(0f)
    override val ended = CompletableDeferred<Take.Reason>()
    override val heardSpeech: Boolean get() = speech
    override val durationMs: Long get() = ms
    var held = false
    var stops = 0
    var snapshots = 0

    override fun holdUntilStopped() {
        held = true
    }

    override fun endOnSilenceFromNow() {
        held = false
    }

    override suspend fun snapshot(): ByteArray {
        snapshots++
        return Wav.encode(ByteArray((ms * 32).toInt()))
    }

    override suspend fun stop(): ByteArray {
        stops++
        ended.complete(Take.Reason.Stopped)
        return Wav.encode(ByteArray((ms * 32).toInt()))
    }
}
