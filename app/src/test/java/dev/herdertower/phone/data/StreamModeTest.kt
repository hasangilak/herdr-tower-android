package dev.herdertower.phone.data

import dev.herdertower.phone.data.TowerSession.StreamMode
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamModeTest {
    @Test fun theVoiceStreamDoesNotFollowTheScreen() {
        // The phone is the voice: on screen or not, lingering or not, it's the same stream (no reconnect).
        for (fg in listOf(true, false)) for (linger in listOf(true, false)) {
            assertEquals(StreamMode.Voice, TowerSession.streamMode(voice = true, foreground = fg, lingering = linger))
        }
    }

    @Test fun thePanelFeedOnlyWhileOnScreenOrWaitingForAReply() {
        assertEquals(StreamMode.Panel, TowerSession.streamMode(voice = false, foreground = true, lingering = false))
        assertEquals(StreamMode.Panel, TowerSession.streamMode(voice = false, foreground = false, lingering = true))
        assertEquals(StreamMode.Idle, TowerSession.streamMode(voice = false, foreground = false, lingering = false))
    }

    /** The Mac off: the phone stops trying after the window, never before its tries, and a brief drop never gets there. */
    @Test fun thePhoneGivesUpOnlyAfterTheWindowAndItsTries() {
        val t0 = 1_000_000L
        // A brief drop: a few seconds of failures, nowhere near the window.
        assertEquals(false, TowerSession.givesUp(t0, attempts = 3, now = t0 + 8_000))
        // Two minutes of failures with its tries: it stops.
        assertEquals(true, TowerSession.givesUp(t0, attempts = 14, now = t0 + TowerSession.GIVE_UP_MS))
        assertEquals(false, TowerSession.givesUp(t0, attempts = 14, now = t0 + TowerSession.GIVE_UP_MS - 1))
        // A phone asleep through the window: not before its tries.
        assertEquals(false, TowerSession.givesUp(t0, attempts = 2, now = t0 + 10 * TowerSession.GIVE_UP_MS))
        assertEquals(true, TowerSession.givesUp(t0, attempts = TowerSession.GIVE_UP_TRIES, now = t0 + 10 * TowerSession.GIVE_UP_MS))
        // The backoff up to the window: 1, 2, 5, then every 10 s; about a dozen tries in two minutes.
        var t = 0L
        var n = 0
        while (!TowerSession.givesUp(0, n, t)) {
            t += 1000L * TowerSession.BACKOFF[minOf(n, TowerSession.BACKOFF.lastIndex)]
            n++
        }
        assertEquals(true, n in 12..15)
    }

    /** The calm card: no countdown, Connect, and since when (a Mac off for days says its day). */
    @Test fun offlineIsACalmCardWithConnect() {
        val n = dev.herdertower.phone.ui.LinkModel.connectionNotice(Link.Offline("Can't reach the Mac.", 5))!!
        assertEquals(dev.herdertower.phone.ui.NoticeAction.Connect, n.action)
        assertEquals(
            "The Mac is offline. The phone stopped trying (no answer since Sat 18:05), so it doesn't wake or drain the battery; it tries again when you open the app, or tap Connect.",
            dev.herdertower.phone.ui.LinkModel.offlineWords(Link.Offline("x", 5)) { "Sat 18:05" },
        )
    }
}
