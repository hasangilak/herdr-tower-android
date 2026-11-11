package dev.herdertower.phone.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the app says when no address of the Mac answers. */
class RouteWordsTest {
    private val lanHost = dev.herdertower.phone.api.TowerHost("lan", "192.168.2.211", 7843)

    @Test fun nothingAnswersHereSaysPairAgainHereOrTailscale() {
        // Paired at home, now at the office: none of the stored addresses answers.
        val m = RouteWords.unreachable(tailnet = true, vpnOn = true, tailscaleInstalled = true, onWifi = true, lan = RouteTry(lanHost, RouteOutcome.TimedOut, 0))
        assertEquals("Can't reach the tower on this network. Pair again here (scan the QR in Settings → Phone on the Mac), or turn on Tailscale on both.", m)
        assertEquals(RouteWords.OTHER_NETWORK, RouteWords.unreachable(tailnet = false, vpnOn = false, tailscaleInstalled = false))
        assertEquals(RouteWords.OTHER_NETWORK, RouteWords.unreachable(tailnet = true, vpnOn = null, tailscaleInstalled = null))
    }

    @Test fun whatThePhoneKnowsOfTailscaleFollows() {
        val off = RouteWords.unreachable(tailnet = true, vpnOn = false, tailscaleInstalled = true)
        assertTrue(off, off.startsWith(RouteWords.OTHER_NETWORK) && off.endsWith("Tailscale is off on this phone."))
        val missing = RouteWords.unreachable(tailnet = true, vpnOn = false, tailscaleInstalled = false)
        assertTrue(missing, missing.endsWith("Tailscale isn't installed on this phone."))
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
