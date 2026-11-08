package dev.herdertower.phone.voice

import java.io.ByteArrayOutputStream

/**
 * Cuts a continuous 16 kHz recording into bursts of speech, by loudness
 * against a level that follows the room (and the phone's own voice in it).
 * Only short bursts come out, a word or two: what someone says to stop it.
 * Longer ones (the tower's own sentences heard back) are dropped here.
 */
class Bursts(
    private val rate: Int = 16_000,
    private val minMs: Int = 200,
    private val maxMs: Int = 1500,
    private val endPauseMs: Int = 300,
    private val prerollMs: Int = 200,
) {
    private val frameMs = 20
    private var floor = -1.0
    private val preroll = ArrayDeque<ByteArray>()
    private var burst: ByteArrayOutputStream? = null
    private var voicedMs = 0
    private var quietMs = 0
    private var tooLong = false

    /** One 20 ms frame (16-bit PCM) and its RMS. Returns a finished short burst (PCM), or null. */
    fun feed(frame: ByteArray, rms: Double): ByteArray? {
        if (floor < 0) floor = rms
        val loud = rms > maxOf(MIN_LEVEL, floor * 2.5)
        // The floor follows the level slowly (about a second), faster downwards; not while a burst may
        // still be a word (so a long sentence stays one long burst), again once it's too long for one.
        if (burst == null || tooLong) floor += (rms - floor) * if (rms < floor) 0.2 else 0.02
        val b = burst
        if (b == null) {
            preroll.addLast(frame)
            while (preroll.size > prerollMs / frameMs) preroll.removeFirst()
            if (!loud) return null
            burst = ByteArrayOutputStream().also { out -> preroll.forEach { out.write(it) } }
            preroll.clear()
            voicedMs = frameMs
            quietMs = 0
            tooLong = false
            return null
        }
        if (!tooLong) b.write(frame)
        if (loud) {
            voicedMs += frameMs + quietMs
            quietMs = 0
            if (voicedMs > maxMs) tooLong = true
            return null
        }
        quietMs += frameMs
        if (quietMs < endPauseMs) return null
        burst = null
        return if (!tooLong && voicedMs >= minMs) b.toByteArray() else null
    }

    companion object {
        /** Below this nothing counts as speech (16-bit RMS). */
        const val MIN_LEVEL = 400.0
    }
}
