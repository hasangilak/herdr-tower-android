package dev.herdertower.phone.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * The phone's side of reaching the Mac over Tailscale: whether a VPN is up
 * (the Tailscale app runs as one) and whether the Tailscale app is there.
 * Null: the phone can't say.
 */
interface PhoneNet {
    fun vpnOn(): Boolean?
    fun tailscaleInstalled(): Boolean?
}

class SystemPhoneNet(private val context: Context) : PhoneNet {
    override fun vpnOn(): Boolean? = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }.getOrNull()

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
    fun unreachable(tailnet: Boolean, vpnOn: Boolean?, tailscaleInstalled: Boolean?): String = when {
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
