package dev.herdertower.phone.api

import java.security.MessageDigest
import java.security.cert.Certificate

/** SHA-256 certificate fingerprints, as the tower prints them: 64 lowercase hex characters. */
object Fingerprint {
    private val HEX = Regex("^[0-9a-f]{64}$")

    /** Lowercase hex without separators, or null when it isn't a SHA-256 fingerprint. Accepts `AB:CD…` and spaced groups. */
    fun normalize(raw: String): String? {
        val s = raw.filterNot { it == ':' || it == ' ' || it == '-' || it == '\n' || it == '\t' }.lowercase()
        return if (HEX.matches(s)) s else null
    }

    fun of(der: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(der).toHex()

    fun of(cert: Certificate): String = of(cert.encoded)

    /** Grouped by four for comparing by eye: `3fa9 c2e1 …`. */
    fun grouped(fp: String): String = fp.chunked(4).joinToString(" ")

    /** Constant-time comparison of two normalized fingerprints. */
    fun matches(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.lowercase().toByteArray(), b.lowercase().toByteArray())

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
