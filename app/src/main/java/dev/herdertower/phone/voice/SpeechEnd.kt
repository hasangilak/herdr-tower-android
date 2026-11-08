package dev.herdertower.phone.voice

/**
 * When someone has finished talking, from the loudness of 20 ms frames.
 *
 * The room's level (the floor) follows quiet frames quickly and loud ones
 * only slowly, so it settles on the background even when the first words
 * come at once (the old way took the loudest of the first 300 ms as the
 * room, so starting to talk straight away made later, softer words count
 * as silence). Speech must rise well above the floor to start, but only a
 * little to go on, so trailing soft words don't count as a pause. It ends
 * after [pauseMs] of real quiet that follows at least 100 ms of speech.
 */
class SpeechEnd(private val pauseMs: Int, private val minLevel: Double = 300.0) {
    private val frameMs = 20
    var floor = -1.0
        private set
    /** Speech was heard (at least 100 ms of it). */
    var spoke = false
        private set
    var voicedMs = 0
        private set
    /** The quiet since the last speech. */
    var silentMs = 0
        private set
    private var speaking = false
    private var frames = 0

    /** One frame's RMS; true when the pause after speech is long enough to stop. */
    fun feed(rms: Double): Boolean {
        frames++
        // The first 200 ms: the room is the quietest frame (speech dips between syllables, a fan doesn't).
        if (frames <= WARMUP) {
            floor = if (floor < 0) rms else minOf(floor, rms)
            if (frames < WARMUP) return false
        }
        val start = maxOf(minLevel, floor * 3.0)
        val goOn = maxOf(minLevel * 0.8, floor * 2.0)
        val loud = rms > if (speaking) goOn else start
        if (loud) {
            speaking = true
            voicedMs += frameMs
            if (voicedMs >= 100) spoke = true
            silentMs = 0
            // A steady new noise (a fan, the tower talking) slowly becomes the room, over ~10 s.
            floor += (rms - floor) * 0.002
        } else {
            speaking = false
            if (spoke) silentMs += frameMs
            floor += (rms - floor) * if (rms < floor) 0.3 else 0.03
        }
        return spoke && silentMs >= pauseMs
    }

    /** 0 while talking; rising to 1 through the pause that ends it (shown as a countdown after the first 400 ms). */
    val pauseProgress: Float get() = if (!spoke || silentMs < 400) 0f else (silentMs.toFloat() / pauseMs).coerceIn(0f, 1f)

    /** The level speech must reach to count (for the diagnostics). */
    val threshold: Double get() = maxOf(minLevel, floor * 3.0)

    private companion object {
        const val WARMUP = 10
    }
}

/** The latest take (voice or dictation), for the diagnostics. */
object Takes {
    val last = kotlinx.coroutines.flow.MutableStateFlow<TakeInfo?>(null)
}

/** One take, for the diagnostics. */
data class TakeInfo(
    val durationMs: Long,
    val endedBy: String,
    val rate: Int,
    val input: String,
    val floor: Double,
    val threshold: Double,
    val pauseMs: Int,
    val voicedMs: Int,
    val what: String = "",
) {
    fun line(): String = "last take${if (what.isNotEmpty()) " ($what)" else ""}: ${"%.1f".format(durationMs / 1000.0)} s · ended by $endedBy · speech ${"%.1f".format(voicedMs / 1000.0)} s · " +
        "${rate / 1000} kHz · $input · room ${floor.toInt()}, speech above ${threshold.toInt()} · pause ${"%.1f".format(pauseMs / 1000.0)} s"
}
