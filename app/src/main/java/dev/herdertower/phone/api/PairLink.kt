package dev.herdertower.phone.api

import java.net.URLDecoder

/** What the pairing QR (`herdertower://pair?h=&p=&c=&fp=`) carries. */
data class PairLink(val host: String, val port: Int, val code: String, val fingerprint: String) {
    companion object {
        const val SCHEME = "herdertower"
        const val DEFAULT_PORT = 7843

        /** Parses a pairing link; throws [PairLinkException] saying what's wrong. */
        fun parse(text: String): PairLink {
            val link = text.trim()
            val prefix = "$SCHEME://pair"
            if (!link.lowercase().startsWith(prefix)) throw PairLinkException("That isn't a tower pairing code.")
            val rest = link.substring(prefix.length).removePrefix("/")
            if (!rest.startsWith("?")) throw PairLinkException("The pairing link has no details.")
            val params = rest.substring(1).substringBefore('#').split('&').filter { it.isNotEmpty() }.associate { kv ->
                val k = kv.substringBefore('=')
                val v = if ('=' in kv) kv.substringAfter('=') else ""
                decode(k) to decode(v)
            }
            val host = validHost(params["h"]) ?: throw PairLinkException("The pairing link has no tower address.")
            val port = params["p"]?.takeIf { it.isNotEmpty() }?.let { validPort(it) ?: throw PairLinkException("The port in the pairing link isn't valid.") }
                ?: DEFAULT_PORT
            val code = validCode(params["c"]) ?: throw PairLinkException("The pairing code should be 8 digits.")
            val fp = params["fp"]?.let { Fingerprint.normalize(it) } ?: throw PairLinkException("The certificate fingerprint in the pairing link isn't valid.")
            return PairLink(host, port, code, fp)
        }

        fun validHost(raw: String?): String? {
            val h = raw?.trim()?.removePrefix("[")?.removeSuffix("]") ?: return null
            if (h.isEmpty() || h.length > 253) return null
            if (h.any { it.isWhitespace() || it == '/' || it == '?' || it == '#' || it == '@' }) return null
            return h
        }

        fun validPort(raw: String): Int? = raw.trim().toIntOrNull()?.takeIf { it in 1..65535 }

        /** The 8-digit code; tolerates spaces or dashes the user typed. */
        fun validCode(raw: String?): String? = raw?.filter { it.isDigit() }?.takeIf { it.length == 8 && raw.all { c -> c.isDigit() || c == ' ' || c == '-' } }

        private fun decode(s: String): String = URLDecoder.decode(s, Charsets.UTF_8)
    }
}

class PairLinkException(message: String) : IllegalArgumentException(message)
