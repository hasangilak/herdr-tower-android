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
}
