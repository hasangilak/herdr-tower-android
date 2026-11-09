package dev.herdertower.phone.api

import java.net.URLDecoder

/**
 * What the pairing QR carries: `herdertower://pair?h=&p=&c=&fp=`, and from
 * a tower with Tailscale `&hosts=lan:<ip>:<port>,tailscale:<ip>:<port>&dns=<MagicDNS name>`.
 * [host]/[port] are the first address (an old link's only one); [hosts] is
 * every address in the order to try them, never empty.
 */
data class PairLink(
    val host: String,
    val port: Int,
    val code: String,
    val fingerprint: String,
    val hosts: List<TowerHost> = listOf(TowerHost(Hosts.kindOf(host), host, port)),
    val magicDns: String? = null,
) {
    /** Back to a `herdertower://pair` link that [parse] reads (to keep it across a recreate). */
    fun toLink(): String {
        fun enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8")
        val more = if (hosts.size > 1 || hosts.any { it.tailscale }) "&hosts=${enc(Hosts.encode(hosts))}" else ""
        val dns = magicDns?.let { "&dns=${enc(it)}" }.orEmpty()
        return "$SCHEME://pair?h=${enc(host)}&p=$port&c=$code&fp=$fingerprint$more$dns"
    }

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
            val magicDns = params["dns"]?.trim()?.trimEnd('.')?.let { validHost(it) }
            // Every address, in order; an older tower's link (or a hosts list this app can't read) is its one address.
            val listed = Hosts.parse(params["hosts"], magicDns)
            val hosts = listed.ifEmpty { listOf(TowerHost(Hosts.kindOf(host), host, port, magicDns?.takeIf { Hosts.isTailnet(host) })) }
            return PairLink(host, port, code, fp, hosts, magicDns)
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

        // The String overload: the Charset one needs Android 13 (API 33).
        private fun decode(s: String): String = URLDecoder.decode(s, "UTF-8")
    }
}

class PairLinkException(message: String) : IllegalArgumentException(message)
