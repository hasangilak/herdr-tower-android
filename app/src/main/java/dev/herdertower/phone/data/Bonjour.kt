package dev.herdertower.phone.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import dev.herdertower.phone.api.Fingerprint
import dev.herdertower.phone.api.Hosts
import dev.herdertower.phone.api.TowerHost
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/** What a `_herdr-tower._tcp` advertisement's TXT must say for this phone to take it (docs/mobile-api.md, "Finding the Mac by Bonjour"). */
object BonjourTxt {
    /** `v=1` and `fp` equal to the pinned fingerprint (lowercased, `:` stripped). The name is never matched: it can be renamed on a clash. */
    fun accepts(txt: Map<String, ByteArray?>, pinned: String): Boolean {
        if (txt["v"]?.toString(Charsets.UTF_8)?.trim() != "1") return false
        val fp = txt["fp"]?.toString(Charsets.UTF_8)?.let(Fingerprint::normalize) ?: return false
        val want = Fingerprint.normalize(pinned) ?: return false
        return Fingerprint.matches(fp, want)
    }
}

/** A tower found on this network: its LAN address, and the Mac's name as it advertises it. */
data class FoundTower(val host: TowerHost, val name: String?)

/** What a search did: the tower found (or none), and in words what it saw and passed over, for Settings and the card. */
data class FindResult(val found: List<FoundTower> = emptyList(), val note: String = "") {
    companion object {
        fun none(note: String) = FindResult(emptyList(), note)
    }
}

/**
 * Finds the paired tower on the network the phone is on now, by Bonjour: the
 * Mac's phone API advertises `_herdr-tower._tcp` with its certificate's
 * fingerprint in TXT `fp`. Only a service whose `fp` is the pinned one
 * counts; the connection is pinned as everywhere, so a look-alike gets
 * nothing.
 */
interface TowerFinder {
    suspend fun find(fingerprint: String, timeoutMs: Long): FindResult
}

/** Android's own DNS-SD (NsdManager): no multicast lock or extra permission needed for discovery. */
class NsdTowerFinder(private val context: Context) : TowerFinder {
    override suspend fun find(fingerprint: String, timeoutMs: Long): FindResult {
        val nsd = runCatching { context.getSystemService(NsdManager::class.java) }.getOrNull() ?: return FindResult.none("Bonjour isn't available on this phone")
        val want = Fingerprint.normalize(fingerprint) ?: return FindResult.none("the pairing has no fingerprint to match")
        val seen = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        var failed: Int? = null
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                seen.trySend(info)
            }
            override fun onStartDiscoveryFailed(type: String, code: Int) {
                Log.i(TAG, "discovery failed: $code")
                failed = code
                seen.close()
            }
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
        }
        runCatching { nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { return FindResult.none("Bonjour couldn't start: ${it.javaClass.simpleName}") }
        val found = mutableListOf<FoundTower>()
        val skipped = mutableListOf<String>()
        var services = 0
        try {
            withTimeoutOrNull(timeoutMs) {
                for (s in seen) {
                    services++
                    val r = resolve(nsd, s)
                    if (r == null) {
                        skipped += "${s.serviceName}: no answer to resolving it"
                        continue
                    }
                    // v=1 with this tower's fingerprint only: another Mac's tower on this network is not ours.
                    if (!BonjourTxt.accepts(r.attributes, want)) {
                        skipped += "${s.serviceName}: another tower (its fingerprint isn't this pairing's)"
                        continue
                    }
                    val ip = addresses(r).firstOrNull()
                    if (ip == null) {
                        skipped += "${s.serviceName}: no IPv4 address (only ${allAddresses(r).ifEmpty { listOf("none") }.joinToString()})"
                        continue
                    }
                    found += FoundTower(
                        TowerHost(TowerHost.LAN, ip, r.port, seenRange = Hosts.rangeOf(ip)),
                        r.attributes["name"]?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() },
                    )
                    break
                }
            }
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
        val note = when {
            found.isNotEmpty() -> "found it at ${found[0].host.host}:${found[0].host.port}"
            failed != null -> "Bonjour couldn't start (code $failed)"
            services == 0 -> "no tower advertised on this Wi-Fi"
            else -> "skipped " + skipped.joinToString("; ")
        }
        Log.i(TAG, "search: $note")
        return FindResult(found, note)
    }

    private fun addresses(info: NsdServiceInfo): List<String> =
        if (Build.VERSION.SDK_INT >= 34) info.hostAddresses.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
        else @Suppress("DEPRECATION") listOfNotNull((info.host as? Inet4Address)?.hostAddress)

    private fun allAddresses(info: NsdServiceInfo): List<String> =
        if (Build.VERSION.SDK_INT >= 34) info.hostAddresses.mapNotNull { it.hostAddress }
        else @Suppress("DEPRECATION") listOfNotNull(info.host?.hostAddress)

    private suspend fun resolve(nsd: NsdManager, info: NsdServiceInfo): NsdServiceInfo? = withTimeoutOrNull(RESOLVE_MS) {
        if (Build.VERSION.SDK_INT >= 34) resolveNew(nsd, info) else resolveOld(nsd, info)
    }

    /**
     * Android 14+: the service's updates until one carries an IPv4 address
     * (the Mac's host resolves to IPv6 too, and that can come first; the API
     * listens on IPv4 only). Out of time: the last update, so its TXT and
     * addresses can still say why it was passed over.
     */
    @androidx.annotation.RequiresApi(34)
    private suspend fun resolveNew(nsd: NsdManager, info: NsdServiceInfo): NsdServiceInfo? {
        var last: NsdServiceInfo? = null
        return withTimeoutOrNull(RESOLVE_MS - 200) {
            suspendCancellableCoroutine { cont ->
                val executor = Executors.newSingleThreadExecutor()
                val cb = object : NsdManager.ServiceInfoCallback {
                    override fun onServiceInfoCallbackRegistrationFailed(code: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                    override fun onServiceUpdated(updated: NsdServiceInfo) {
                        last = updated
                        if (updated.hostAddresses.none { it is Inet4Address }) return // wait for the IPv4 one
                        if (cont.isActive) cont.resume(updated)
                        runCatching { nsd.unregisterServiceInfoCallback(this) }
                    }
                    override fun onServiceLost() {}
                    override fun onServiceInfoCallbackUnregistered() {
                        executor.shutdown()
                    }
                }
                runCatching { nsd.registerServiceInfoCallback(info, executor, cb) }.onFailure { if (cont.isActive) cont.resume(null) }
                cont.invokeOnCancellation { runCatching { nsd.unregisterServiceInfoCallback(cb) } }
            }
        } ?: last
    }

    @Suppress("DEPRECATION")
    private suspend fun resolveOld(nsd: NsdManager, info: NsdServiceInfo): NsdServiceInfo? = suspendCancellableCoroutine { cont ->
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(s: NsdServiceInfo, code: Int) {
                if (cont.isActive) cont.resume(null)
            }
            override fun onServiceResolved(s: NsdServiceInfo) {
                if (cont.isActive) cont.resume(s)
            }
        }
        runCatching { nsd.resolveService(info, listener) }.onFailure { if (cont.isActive) cont.resume(null) }
    }

    companion object {
        private const val TAG = "TowerFinder"
        const val SERVICE = "_herdr-tower._tcp"
        private const val RESOLVE_MS = 3_000L
    }
}
