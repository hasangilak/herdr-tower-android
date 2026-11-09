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
        // On Wi-Fi, yet the Mac's LAN address didn't answer: say what it did, since "join the Mac's Wi-Fi" would be wrong.
        onWifi == true && lan != null ->
            "On Wi-Fi, but the Mac at ${lan.host.host} ${lan.outcome.words}. Is this the Mac's network, with the tower's phone API on?" +
                (if (tailnet && vpnOn != true) " Away from it: turn Tailscale on." else "")
        !tailnet ->
            "Can't reach the Mac. Is the phone on the Mac's Wi-Fi, with “tower phone enable” on? " +
                "Away from home: start Tailscale on the Mac, then pair again so the phone learns its tailnet address."
        tailscaleInstalled == false ->
            "Can't reach the Mac on this network, and Tailscale isn't installed on this phone. " +
                "Join the Mac's Wi-Fi, or install Tailscale and sign in to the Mac's tailnet."
        vpnOn == false ->
            "Can't reach the Mac on this network, and Tailscale is off on this phone. Turn Tailscale on, or join the Mac's Wi-Fi."
        else ->
            "Can't reach the Mac on Wi-Fi or over Tailscale. Is the Mac awake, with Tailscale running there and the phone API on?"
    }
}
