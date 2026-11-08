package dev.herdertower.phone.ui

/**
 * A colour per Claude account, picked from its name exactly as the orb
 * panel does (FNV-1a over the lowercased name, into eight accents that are
 * never status colours: mint, orchid, peach, lime, lilac, coral, sand, aqua).
 * An account keeps its colour on the Mac and the phone; the default account
 * is drawn outlined instead.
 */
object AccountHue {
    val HUES: LongArray = longArrayOf(0x6eeaa8, 0xf0a6ff, 0xffb98a, 0xc8f06e, 0xc9b3ff, 0xff9fb2, 0xe8d9a0, 0x7fe8e0)

    fun fnv1a(name: String): UInt =
        name.lowercase().toByteArray(Charsets.UTF_8).fold(0x811c9dc5u) { h, b -> (h xor (b.toUInt() and 0xffu)) * 0x01000193u }

    /** `0xRRGGBB`. */
    fun of(name: String): Long = HUES[(fnv1a(name) % HUES.size.toUInt()).toInt()]
}
