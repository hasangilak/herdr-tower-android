package dev.herdertower.phone.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One address the tower listens on (docs/mobile-api.md, "Pairing flow"):
 * its LAN address, or its Tailscale one (an IPv4 in 100.64.0.0/10, with
 * the Mac's MagicDNS name when Tailscale reports one). Same port, same
 * certificate: the fingerprint pin holds for every address.
 */
@Serializable
data class TowerHost(
    /** `lan` or `tailscale`. */
    val kind: String = LAN,
    val host: String = "",
    val port: Int = PairLink.DEFAULT_PORT,
    @SerialName("dns_name") val dnsName: String? = null,
) {
    val tailscale: Boolean get() = kind == TAILSCALE

    /** `host:port`, how a route is remembered. */
    val id: String get() = "$host:$port"

    companion object {
        const val LAN = "lan"
        const val TAILSCALE = "tailscale"
    }
}

/** The Mac's Tailscale as the tower sees it (state `tailscale`). */
@Serializable
data class TailscaleInfo(
    val installed: Boolean = false,
    val running: Boolean = false,
    val state: String = "",
    val ip: String? = null,
    @SerialName("dns_name") val dnsName: String? = null,
)

/** Choosing and keeping the tower's addresses. */
object Hosts {
    /** Kinds this app knows; others (added to the tower later) are skipped. */
    private val KNOWN = setOf(TowerHost.LAN, TowerHost.TAILSCALE)

    /** Whether [host] is a Tailscale address: an IPv4 in 100.64.0.0/10, or a MagicDNS name (`*.ts.net`). */
    fun isTailnet(host: String): Boolean {
        val h = host.trim().lowercase().trimEnd('.')
        if (h.endsWith(".ts.net")) return true
        val parts = h.split('.')
        if (parts.size != 4) return false
        val n = parts.map { it.toIntOrNull() ?: return false }
        if (n.any { it !in 0..255 }) return false
        return n[0] == 100 && n[1] in 64..127
    }

    /** The kind an address alone suggests (a typed address, or an old single-host link). */
    fun kindOf(host: String): String = if (isTailnet(host)) TowerHost.TAILSCALE else TowerHost.LAN

    /**
     * The link's `hosts`: `lan:192.168.1.20:7843,tailscale:100.68.110.33:7843`,
     * in the order to try them; [dns] (the link's `dns`) joins the tailscale
     * entry. Malformed entries and unknown kinds are skipped.
     */
    fun parse(raw: String?, dns: String?): List<TowerHost> {
        if (raw.isNullOrBlank()) return emptyList()
        val name = dns?.trim()?.trimEnd('.')?.takeIf { PairLink.validHost(it) != null }
        return raw.split(',').mapNotNull { entry ->
            val parts = entry.trim().split(':')
            if (parts.size != 3) return@mapNotNull null
            val kind = parts[0].trim().lowercase()
            if (kind !in KNOWN) return@mapNotNull null
            val host = PairLink.validHost(parts[1]) ?: return@mapNotNull null
            val port = PairLink.validPort(parts[2]) ?: return@mapNotNull null
            TowerHost(kind, host, port, if (kind == TowerHost.TAILSCALE) name else null)
        }.distinctBy { it.id }
    }

    /** Back to the link's `hosts` form. */
    fun encode(hosts: List<TowerHost>): String = hosts.joinToString(",") { "${it.kind}:${it.host}:${it.port}" }

    /** The addresses a list from the tower (pairing JSON, state `lan_hosts`) keeps: known kinds, valid hosts. */
    fun clean(hosts: List<TowerHost>): List<TowerHost> = hosts.mapNotNull { h ->
        val host = PairLink.validHost(h.host) ?: return@mapNotNull null
        if (h.kind !in KNOWN || h.port !in 1..65535) return@mapNotNull null
        h.copy(host = host, dnsName = h.dnsName?.trim()?.trimEnd('.')?.takeIf { it.isNotEmpty() })
    }.distinctBy { it.id }

    /**
     * Where to try, in order: the LAN addresses first (the direct path at
     * home, tried with a short timeout), then the route that worked last,
     * then each Tailscale address and its MagicDNS name (which still finds
     * the Mac if its tailnet IP changed). [magicDns] stands in for a
     * tailscale entry without a name. Each address once.
     */
    fun candidates(hosts: List<TowerHost>, magicDns: String?, lastWorking: String?): List<TowerHost> {
        val expanded = buildList {
            hosts.forEach { h ->
                add(h)
                val name = h.dnsName ?: magicDns?.takeIf { h.tailscale }
                if (h.tailscale && name != null && name != h.host) add(TowerHost(TowerHost.TAILSCALE, name, h.port, name))
            }
            // Paired while the Mac had no tailnet address listed, but its name is known: still worth a try.
            if (magicDns != null && hosts.none { it.tailscale }) {
                add(TowerHost(TowerHost.TAILSCALE, magicDns, hosts.firstOrNull()?.port ?: PairLink.DEFAULT_PORT, magicDns))
            }
        }.distinctBy { it.id }
        val lan = expanded.filter { !it.tailscale }
        val last = expanded.firstOrNull { it.id == lastWorking && it.tailscale }
        return (lan + listOfNotNull(last) + expanded.filter { it.tailscale }).distinctBy { it.id }
    }

    /** "Wi-Fi 192.168.1.20 · Tailscale 100.68.110.33 (mac.tailnet.ts.net)": the addresses for people. */
    fun describe(hosts: List<TowerHost>, magicDns: String? = null): String = hosts.joinToString(" · ") { h ->
        val name = (h.dnsName ?: magicDns?.takeIf { h.tailscale })?.takeIf { it != h.host }
        (if (h.tailscale) "Tailscale " else "Wi-Fi ") + h.host + (if (h.port != PairLink.DEFAULT_PORT) ":${h.port}" else "") + (name?.let { " ($it)" } ?: "")
    }

    /**
     * The stored list after the tower sent its current one ([fresh], state
     * `lan_hosts`): the Mac moved to another LAN address or started
     * Tailscale. An empty list (the API off, an older tower) keeps what's
     * stored.
     */
    fun refresh(stored: List<TowerHost>, fresh: List<TowerHost>): List<TowerHost> {
        val clean = clean(fresh)
        if (clean.isEmpty()) return stored
        // A kind of address the Mac doesn't list now is kept, after the fresh ones: its tailnet address while Tailscale
        // is off there, and its LAN address while it lists only the tailnet (Wi-Fi off, an exit node). Dropping the
        // LAN one would leave the phone unable to find the Mac at home until it paired again.
        val keep = stored.filter { s -> clean.none { it.kind == s.kind } }
        return (clean + keep).distinctBy { it.id }
    }
}

/** The route the phone reaches the tower by now. */
data class ActiveRoute(val host: TowerHost) {
    val tailscale: Boolean get() = host.tailscale

    /** "Wi-Fi" or "Tailscale". */
    val label: String get() = if (tailscale) "Tailscale" else "Wi-Fi"
}
