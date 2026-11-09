package dev.herdertower.phone.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the app says when no address of the Mac answers. */
class RouteWordsTest {
    @Test fun tailscaleOffOnThePhone() {
        val m = RouteWords.unreachable(tailnet = true, vpnOn = false, tailscaleInstalled = true)
        assertTrue(m, "Tailscale is off on this phone" in m && "Turn Tailscale on" in m)
    }

    @Test fun tailscaleNotInstalled() {
        val m = RouteWords.unreachable(tailnet = true, vpnOn = false, tailscaleInstalled = false)
        assertTrue(m, "isn't installed on this phone" in m)
    }

    @Test fun tailscaleOnButTheMacDoesntAnswer() {
        val m = RouteWords.unreachable(tailnet = true, vpnOn = true, tailscaleInstalled = true)
        assertTrue(m, "on Wi-Fi or over Tailscale" in m && "Mac awake" in m)
        // The phone can't say: the same, not a guess that Tailscale is off.
        assertTrue(RouteWords.unreachable(tailnet = true, vpnOn = null, tailscaleInstalled = null).startsWith("Can't reach the Mac on Wi-Fi or over Tailscale"))
    }

    @Test fun pairedBeforeTheMacHadTailscale() {
        val m = RouteWords.unreachable(tailnet = false, vpnOn = false, tailscaleInstalled = false)
        assertTrue(m, "start Tailscale on the Mac, then pair again" in m)
    }

    private val lanHost = dev.herdertower.phone.api.TowerHost("lan", "192.168.2.211", 7843)

    @Test fun onTheMacsWifiTheMessageSaysWhatTheLanAddressDid() {
        // The case reported: Tailscale off on both, the phone back on the Mac's Wi-Fi, and still no connection.
        val m = RouteWords.unreachable(
            tailnet = true, vpnOn = false, tailscaleInstalled = true, onWifi = true,
            lan = RouteTry(lanHost, RouteOutcome.TimedOut, 0),
        )
        assertTrue(m, m.startsWith("On Wi-Fi, but the Mac at 192.168.2.211 didn't answer in time"))
        assertTrue(m, "join the Mac's Wi-Fi" !in m)
    }

    @Test fun androidKeepingTheAppOffTheLocalNetworkIsSaid() {
        val m = RouteWords.unreachable(tailnet = true, vpnOn = false, tailscaleInstalled = true, onWifi = true, localNetworkBlocked = true)
        assertTrue(m, m.startsWith("Android keeps this app off the local network") && "Tap Allow" in m)
        val blocked = RouteWords.unreachable(tailnet = true, vpnOn = false, tailscaleInstalled = true, onWifi = true, lan = RouteTry(lanHost, RouteOutcome.Blocked, 0))
        assertTrue(blocked, blocked.startsWith("Android blocked the connection to the Mac at 192.168.2.211"))
    }

    @Test fun eachFailureIsNamed() {
        fun of(e: Throwable) = RouteOutcome.of(dev.herdertower.phone.api.UnreachableException("wrapped", e))
        assertEquals(RouteOutcome.TimedOut, of(java.net.SocketTimeoutException("connect timed out")))
        assertEquals(RouteOutcome.Refused, of(java.net.ConnectException("failed to connect to /192.168.2.211 (port 7843): connect failed: ECONNREFUSED")))
        assertEquals(RouteOutcome.NoRoute, of(java.net.ConnectException("connect failed: EHOSTUNREACH (No route to host)")))
        assertEquals(RouteOutcome.NoRoute, of(java.net.NoRouteToHostException("No route to host")))
        assertEquals(RouteOutcome.Blocked, of(java.net.SocketException("connect failed: EPERM (Operation not permitted)")))
        assertEquals(RouteOutcome.NoName, of(java.net.UnknownHostException("mac.tailnet.ts.net")))
        assertEquals("Wi-Fi 192.168.2.211: refused the connection (nothing listening there)", RouteTry(lanHost, RouteOutcome.Refused, 0).line)
    }
}
