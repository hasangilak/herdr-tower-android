package dev.herdertower.phone.voice

import android.content.pm.ServiceInfo
import dev.herdertower.phone.voice.VoiceService.Started
import org.junit.Assert.assertEquals
import org.junit.Test

/** How the voice service goes to the foreground, including when Android refuses the microphone. */
class ForegroundStartTest {
    private val mic = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    private val media = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK

    @Test fun withTheMicrophoneWhenAllowed() {
        val asked = mutableListOf<Int>()
        assertEquals(Started.WithMic, VoiceService.startForegroundAs(true, 35) { asked += it })
        assertEquals(listOf(media or mic), asked)
    }

    @Test fun aBackgroundRestartFallsBackToMediaInsteadOfCrashing() {
        // Restarted by the system with the app in the background: Android refuses the microphone type.
        val asked = mutableListOf<Int>()
        val how = VoiceService.startForegroundAs(true, 35) { types ->
            asked += types
            if (types and mic != 0) throw SecurityException("needs the app in the foreground")
        }
        assertEquals(Started.MediaOnly, how)
        assertEquals(listOf(media or mic, media), asked)
    }

    @Test fun refusedAltogetherItSaysSo() {
        assertEquals(Started.No, VoiceService.startForegroundAs(true, 35) { throw IllegalStateException("not allowed") })
    }

    @Test fun withoutMicrophonePermissionOrBeforeAndroid11() {
        val asked = mutableListOf<Int>()
        assertEquals(Started.MediaOnly, VoiceService.startForegroundAs(false, 35) { asked += it })
        assertEquals(Started.MediaMayRecord, VoiceService.startForegroundAs(true, 29) { asked += it })
        assertEquals(listOf(media, media), asked)
    }
}
