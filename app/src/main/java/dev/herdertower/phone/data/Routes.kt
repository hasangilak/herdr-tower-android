package dev.herdertower.phone.data

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.ContextCompat
import dev.herdertower.phone.api.TowerHost
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * The phone's side of reaching the Mac over Tailscale: whether a VPN is up
 * (the Tailscale app runs as one) and whether the Tailscale app is there.
 * Null: the phone can't say.
 */
interface PhoneNet {
    fun vpnOn(): Boolean?
    fun tailscaleInstalled(): Boolean?
    /** On a Wi-Fi (or wired) network now, the kind the Mac's LAN is on. */
    fun onWifi(): Boolean? = null
    /** Android 17+ keeps this app off the local network until "local network" access is allowed. */
    fun localNetworkBlocked(): Boolean = false
    /** The ranges (`10.10.30`) of the Wi-Fi or wired networks the phone is on now: which of the Mac's networks it is on. */
    fun localRanges(): Set<String> = emptySet()
}

/** What one address did when the app tried it. */
enum class RouteOutcome(val words: String) {
    Answered("answered"),
    Refused("refused the connection (nothing listening there)"),
    TimedOut("didn't answer in time"),
    NoRoute("isn't reachable from this network"),
    Blocked("was blocked by Android"),
    NoName("couldn't be looked up"),
    OtherCertificate("answered with another certificate (not your Mac)"),
    Failed("failed");

    companion object {
        /** From the error a try ended with (the client's own wrapper's cause included). */
        fun of(e: Throwable): RouteOutcome {
            val root = generateSequence(e) { it.cause }.take(5).toList()
            val text = root.joinToString(" ") { it.message.orEmpty() }
            return when {
                text.contains("EPERM") || text.contains("not permitted", ignoreCase = true) -> Blocked
                root.any { it is SocketTimeoutException } || text.contains("didn't answer in time") -> TimedOut
                root.any { it is UnknownHostException } || text.contains("Unknown address") -> NoName
                root.any { it is NoRouteToHostException } || text.contains("ENETUNREACH") || text.contains("EHOSTUNREACH") || text.contains("unreachable", ignoreCase = true) -> NoRoute
                root.any { it is ConnectException } || text.contains("ECONNREFUSED") -> Refused
                root.any { it is SocketException } -> Failed
                else -> Failed
            }
        }
    }
}

/** One address tried: [got] is the certificate it showed when it wasn't the paired one. */
data class RouteTry(val host: TowerHost, val outcome: RouteOutcome, val atMs: Long, val got: String? = null) {
    /** "Wi-Fi 192.168.2.211: didn't answer in time". */
    val line: String get() = "${if (host.tailscale) "Tailscale" else "Wi-Fi"} ${host.host}: ${outcome.words}"
}

class SystemPhoneNet(private val context: Context) : PhoneNet {
    override fun vpnOn(): Boolean? = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }.getOrNull()

    @Suppress("DEPRECATION") // every network, not only the default one: with a VPN up, the default is the VPN
    override fun onWifi(): Boolean? = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        cm.allNetworks.any { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@any false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        }
    }.getOrNull()

    @Suppress("DEPRECATION")
    override fun localRanges(): Set<String> = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptySet()
        cm.allNetworks.filter { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@filter false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        }.flatMap { n -> cm.getLinkProperties(n)?.linkAddresses.orEmpty() }
            .mapNotNull { a -> (a.address as? java.net.Inet4Address)?.hostAddress?.let(dev.herdertower.phone.api.Hosts::rangeOf) }
            .toSet()
    }.getOrDefault(emptySet())

    override fun localNetworkBlocked(): Boolean = Build.VERSION.SDK_INT >= 37 &&
        ContextCompat.checkSelfPermission(context, "android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED

    override fun tailscaleInstalled(): Boolean? = runCatching {
        val pm = context.packageManager ?: return null
        // Declared in the manifest's <queries>: without it Android 11+ hides the package.
        pm.getLaunchIntentForPackage(TAILSCALE_APP) != null
    }.getOrNull()

    companion object {
        const val TAILSCALE_APP = "com.tailscale.ipn"
    }
}

/** What the app says about the way to the Mac. */
object RouteWords {
    /** No address of the tower answers here: the words, and the card's button opens the scanner. */
    const val OTHER_NETWORK = "Can't reach the tower on this network. Pair again here (scan the QR in Settings → Phone on the Mac), or turn on Tailscale on both."

    /**
     * No address answered. [tailnet]: the pairing has a Tailscale address
     * (or MagicDNS name) to try; [vpnOn] and [tailscaleInstalled] are the
     * phone's side, null when unknown.
     */
    fun unreachable(
        tailnet: Boolean,
        vpnOn: Boolean?,
        tailscaleInstalled: Boolean?,
        onWifi: Boolean? = null,
        localNetworkBlocked: Boolean = false,
        /** How the LAN address failed this time, if it was tried. */
        lan: RouteTry? = null,
    ): String = when {
        localNetworkBlocked ->
            "Android keeps this app off the local network, so the Mac can't be reached on Wi-Fi. Tap Allow for local network access" +
                (if (tailnet) ", or turn Tailscale on." else ".")
        lan?.outcome == RouteOutcome.Blocked ->
            "Android blocked the connection to the Mac at ${lan.host.host}. Check that local network access is allowed for this app" +
                (if (tailnet && vpnOn != true) ", or turn Tailscale on." else ".")
        // Nothing of the tower's answers here: pair again on this network, or reach it over Tailscale. What each
        // address did is in the menu (routeTries).
        // What the phone itself can say about Tailscale follows: the one thing to do on this side.
        tailscaleInstalled == false && tailnet -> "$OTHER_NETWORK Tailscale isn't installed on this phone."
        vpnOn == false && tailnet -> "$OTHER_NETWORK Tailscale is off on this phone."
        else -> OTHER_NETWORK
    }
}
