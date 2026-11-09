package dev.herdertower.phone.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechEndTest {
    /** Feeds [ms] at [level]; returns the ms into it when it ended, or -1. */
    private fun SpeechEnd.run(level: Double, ms: Int): Int {
        for (t in 0 until ms / 20) if (feed(level)) return (t + 1) * 20
        return -1
    }

    /** Speech as it is: loud, with short dips between syllables. */
    private fun SpeechEnd.talk(ms: Int, level: Double = 3000.0): Int {
        for (t in 0 until ms / 20) if (feed(if (t % 8 == 7) 150.0 else level)) return t * 20
        return -1
    }

    @Test fun endsAfterRealSilenceNotBefore() {
        val v = SpeechEnd(pauseMs = 1800)
        assertEquals(-1, v.run(100.0, 500)) // the room
        assertEquals(-1, v.talk(2000))
        assertTrue(v.spoke)
        assertEquals(1800, v.run(100.0, 3000)) // 1.8 s of quiet, exactly
    }

    @Test fun talkingStraightAwayStillWorks() {
        // The old detector took the loudest of the first 300 ms as the room: speech at once broke it.
        val v = SpeechEnd(pauseMs = 1800)
        assertEquals(-1, v.talk(3000))
        assertTrue(v.spoke)
        assertTrue(v.floor < 500)
        assertEquals(1800, v.run(120.0, 3000))
    }

    @Test fun aThoughtMidSentenceIsNotTheEnd() {
        val v = SpeechEnd(pauseMs = 1800)
        v.run(100.0, 400)
        v.talk(1500)
        assertEquals(-1, v.run(100.0, 1200)) // a breath, a thought: 1.2 s (the old limit) no longer sends
        assertEquals(0f, SpeechEnd(1800).pauseProgress)
        v.talk(780) // ends on speech, not on a dip
        assertEquals(1800, v.run(100.0, 2500))
    }

    @Test fun softTrailingWordsAreNotSilence() {
        val v = SpeechEnd(pauseMs = 1800)
        v.run(100.0, 400)
        v.talk(1000)
        assertEquals(-1, v.run(700.0, 2500)) // quieter words go on
        assertEquals(1800, v.run(100.0, 2500))
    }

    @Test fun aSteadyNoiseIsTheRoomNotSpeech() {
        val v = SpeechEnd(pauseMs = 1800)
        assertEquals(-1, v.run(650.0, 5000)) // a fan from the start
        assertFalse(v.spoke)
        v.talk(1000, level = 4000.0)
        assertTrue(v.spoke)
        assertEquals(1800, v.run(650.0, 2500))
    }

    @Test fun theCountdownRunsThroughThePause() {
        val v = SpeechEnd(pauseMs = 2000)
        v.run(100.0, 400)
        v.talk(1000)
        assertEquals(0f, v.pauseProgress)
        v.run(100.0, 300)
        assertEquals(0f, v.pauseProgress) // not for a short gap
        v.run(100.0, 700)
        assertEquals(0.5f, v.pauseProgress, 0.02f)
        v.talk(200)
        assertEquals(0f, v.pauseProgress) // talking again resets it
    }

    @Test fun clicksAreNotSpeech() {
        val v = SpeechEnd(pauseMs = 1800)
        v.run(100.0, 400)
        v.run(5000.0, 60)
        assertFalse(v.spoke)
    }

    @Test fun longTalkWithShallowDipsIsNotAPause() {
        // Fluent speech doesn't always dip to the room between words: soft frames used to lift the floor until talking counted as quiet.
        val v = SpeechEnd(pauseMs = 2000)
        v.run(100.0, 400)
        val speech = doubleArrayOf(3000.0, 4000.0, 2000.0, 3500.0, 1800.0, 4200.0, 2500.0, 1000.0)
        for (t in 0 until 30 * 50) assertFalse("ended ${t * 20} ms into the talk", v.feed(speech[t % speech.size]))
        assertEquals(2000, v.run(100.0, 3000)) // then real quiet still ends it
    }

    @Test fun scatteredClicksDontAddUpToSpeech() {
        val v = SpeechEnd(pauseMs = 2000)
        v.run(100.0, 400)
        repeat(10) {
            v.run(5000.0, 20) // a click, a key, a knock
            v.run(100.0, 400)
        }
        assertFalse(v.spoke)
        assertEquals(200, v.voicedMs) // still counted for the diagnostics
    }
}
