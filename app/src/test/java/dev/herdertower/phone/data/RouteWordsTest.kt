package dev.herdertower.phone.data

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
}
