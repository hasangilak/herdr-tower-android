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
    /** Kept here, not sent by the tower: the network a LAN address was seen on (its range, `10.10.30`), and its name there ("Office"). */
    @SerialName("seen_range") val seenRange: String? = null,
    @SerialName("seen_name") val seenName: String? = null,
    /** Kept here too: when the tower last answered at this address (the phone's own record). */
    @SerialName("last_ok_ms") val lastOkMs: Long? = null,
) {
    val tailscale: Boolean get() = kind == TAILSCALE

    /** The network this address is on: the range it was seen on, else its first three numbers. */
    val range: String? get() = seenRange ?: if (tailscale) null else Hosts.rangeOf(host)

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

/**
 * The network the Mac is on, as the tower says it (hello, the `hosts` event,
 * `GET /v1/network`): its [range] (`10.10.30`), the name the user gave it
 * ("Office"), and its LAN address there.
 */
@Serializable
data class TowerNetwork(val range: String? = null, val name: String? = null, val lan: NetworkLan? = null) {
    /** "Office", or "the network 10.10.30.x". */
    val label: String? get() = name?.takeIf { it.isNotBlank() } ?: range?.takeIf { it.isNotBlank() }?.let { if (it.count { c -> c == '.' } == 2) "the network $it.x" else "the network $it" }
}

@Serializable
data class NetworkLan(val host: String = "", val port: Int = PairLink.DEFAULT_PORT)

/** The `hosts` event and `GET /v1/network`: the Mac's addresses now, the network it is on, its MagicDNS name. */
@Serializable
data class HostsUpdate(
    val hosts: List<TowerHost> = emptyList(),
    val network: TowerNetwork? = null,
    @SerialName("magic_dns") val magicDns: String? = null,
    val tailscale: TailscaleInfo? = null,
)

/** Choosing and keeping the tower's addresses. */
object Hosts {
    /** How many networks' LAN addresses are remembered (home, office, …), the latest first (docs/mobile-api.md: at most 8). */
    const val MAX_NETWORKS = 8

    /** The first three numbers of an IPv4 address (`10.10.30`), or null for a name or another form. */
    fun rangeOf(host: String): String? {
        val n = host.trim().split('.')
        if (n.size != 4 || n.any { p -> p.toIntOrNull()?.let { it in 0..255 } != true }) return null
        return n.take(3).joinToString(".")
    }
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
    fun candidates(hosts: List<TowerHost>, magicDns: String?, lastWorking: String?, phoneRanges: Set<String> = emptySet()): List<TowerHost> {
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
        // On a Wi-Fi whose range the phone knows: that network's address first; another network's after Tailscale
        // (the Mac is somewhere else, so its home address would only cost a timeout). Not known: every LAN address first.
        val here = if (phoneRanges.isEmpty()) lan else lan.filter { it.range in phoneRanges }
        val elsewhere = lan - here.toSet()
        val last = expanded.firstOrNull { it.id == lastWorking && it.tailscale }
        return (here + listOfNotNull(last) + expanded.filter { it.tailscale } + elsewhere).distinctBy { it.id }
    }

    /** "Wi-Fi 192.168.1.20 · Tailscale 100.68.110.33 (mac.tailnet.ts.net)": the addresses for people. */
    fun describe(hosts: List<TowerHost>, magicDns: String? = null): String = hosts.joinToString(" · ") { h ->
        val name = (h.dnsName ?: magicDns?.takeIf { h.tailscale })?.takeIf { it != h.host }
        val net = h.seenName?.takeIf { !h.tailscale && it.isNotBlank() }
        (if (h.tailscale) "Tailscale " else if (net != null) "$net " else "Wi-Fi ") + h.host + (if (h.port != PairLink.DEFAULT_PORT) ":${h.port}" else "") + (name?.let { " ($it)" } ?: "")
    }

    /**
     * The stored list after the tower sent its current one ([fresh], state
     * `lan_hosts`): the Mac moved to another LAN address or started
     * Tailscale. An empty list (the API off, an older tower) keeps what's
     * stored.
     */
    fun refresh(stored: List<TowerHost>, fresh: List<TowerHost>, network: TowerNetwork? = null): List<TowerHost> {
        val clean = clean(fresh)
        if (clean.isEmpty()) return stored
        // The fresh LAN addresses are on the network the Mac is on now: labelled with it, so the list says where each is.
        val now = clean.map { h ->
            if (h.tailscale) h else {
                val range = rangeOf(h.host) ?: network?.range
                val before = stored.firstOrNull { it.id == h.id }
                h.copy(seenRange = range, seenName = network?.name?.takeIf { network.range == null || network.range == range } ?: before?.seenName, lastOkMs = h.lastOkMs ?: before?.lastOkMs)
            }
        }
        val nowRanges = now.filter { !it.tailscale }.mapNotNull { it.range }.toSet()
        // Another network's LAN address is kept, after the fresh ones (the Mac goes home again): one per network, the
        // latest first, at most MAX_NETWORKS; one on a network the Mac lists now is replaced (it moved there).
        val otherLans = stored.filter { s -> !s.tailscale && now.none { it.id == s.id } && (s.range == null || s.range !in nowRanges) }
        // A kind the Mac doesn't list now is kept: its tailnet address while Tailscale is off there, and its LAN
        // address while it lists only the tailnet (Wi-Fi off, an exit node).
        val keepTail = stored.filter { s -> s.tailscale && now.none { it.tailscale } }
        val lans = (now.filter { !it.tailscale } + otherLans).distinctBy { it.range ?: it.id }.take(MAX_NETWORKS)
        val tails = now.filter { it.tailscale }.map { h -> h.copy(lastOkMs = h.lastOkMs ?: stored.firstOrNull { it.id == h.id }?.lastOkMs) }
        return (lans + tails + keepTail).distinctBy { it.id }
    }
}

/** The route the phone reaches the tower by now. */
data class ActiveRoute(val host: TowerHost) {
    val tailscale: Boolean get() = host.tailscale

    /** "Wi-Fi" or "Tailscale". */
    val label: String get() = if (tailscale) "Tailscale" else "Wi-Fi"
}
