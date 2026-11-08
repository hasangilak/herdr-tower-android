package dev.herdertower.phone.voice

/**
 * Volume down to stop the tower: two presses close together, or holding it.
 * Fed every volume-down press while the tower speaks; says when that's a stop.
 */
class HushKeys(private val windowMs: Long = 450) {
    private var lastDown = 0L

    /** A volume-down press at [atMs]; [repeat] is the key's repeat count (held: > 0). True: stop the tower. */
    fun down(atMs: Long, repeat: Int = 0): Boolean {
        if (repeat > 0) return repeat == 1 // held: once, as soon as it repeats
        val double = lastDown > 0 && atMs - lastDown <= windowMs
        lastDown = if (double) 0 else atMs
        return double
    }

    fun reset() {
        lastDown = 0
    }
}
