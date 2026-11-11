package dev.herdertower.phone.ui

import dev.herdertower.phone.api.AudioClip
import dev.herdertower.phone.data.Link
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkModelTest {
    @Test fun theOrbPlaysWhatThePhoneDoesOverTheTowersPhase() {
        assertEquals("listening", LinkModel.orbPhase(true, "idle", recording = true, sending = false, playing = null))
        assertEquals("transcribing", LinkModel.orbPhase(true, "idle", false, true, null))
        assertEquals("speaking", LinkModel.orbPhase(true, "thinking", false, false, AudioClip(kind = "reply")))
        assertEquals("announcing", LinkModel.orbPhase(true, "idle", false, false, AudioClip(kind = "alert")))
        assertEquals("off", LinkModel.orbPhase(false, "idle", false, false, null))
        assertEquals("blocked", LinkModel.orbPhase(true, "blocked", false, false, null))
    }

    @Test fun aRetryInFlightKeepsTheSameCard() {
        val reason = "Can't reach the tower."
        // Away: fail, retry, fail again. The card must not drop out while the retry is in flight.
        val seq = listOf<Link>(Link.Connecting(0), Link.Retrying(1, reason, 1), Link.Connecting(1, reason), Link.Retrying(2, reason, 2), Link.Connecting(2, reason))
        val shown = seq.map { LinkModel.connectionNotice(it) }
        assertNull(shown[0]) // a first try says nothing yet
        assertTrue(shown.drop(1).all { it?.action == NoticeAction.Retry && it.message.startsWith(reason) })
        assertEquals("$reason Trying again in 2 s.", shown[3]!!.message)
        assertEquals("$reason Trying again now.", shown[4]!!.message)
        assertNull(LinkModel.connectionNotice(Link.Live(false)))
        assertEquals(NoticeAction.Repair, LinkModel.connectionNotice(Link.Revoked("gone"))!!.action)
    }
}
