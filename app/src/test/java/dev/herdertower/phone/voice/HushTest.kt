package dev.herdertower.phone.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HushTest {
    @Test fun aHushWordStops() {
        assertTrue(HushListener.isHush("stop", "Web finished the checkout page."))
        assertTrue(HushListener.isHush("Hush!", ""))
        assertTrue(HushListener.isHush("okay quiet please", "Api is waiting."))
        assertTrue(HushListener.isHush("Ruhe", ""))
        assertTrue(HushListener.isHush("stopp", ""))
        assertTrue(HushListener.isHush("halt", ""))
    }

    @Test fun otherWordsDont() {
        assertFalse(HushListener.isHush("what's everyone doing", ""))
        assertFalse(HushListener.isHush("stopwatch", ""))
        assertFalse(HushListener.isHush("", ""))
    }

    @Test fun itsOwnVoiceHeardBackDoesntStopIt() {
        // The tower says "stop" itself; the microphone hears it back.
        assertFalse(HushListener.isHush("the tests stop at the login page", "The tests stop at the login page."))
        // ...but the user saying another hush word over it does.
        assertTrue(HushListener.isHush("the tests quiet", "The tests stop at the login page."))
    }

    @Test fun volumeDownTwiceOrHeld() {
        val k = HushKeys(windowMs = 450)
        assertFalse(k.down(1000))
        assertTrue(k.down(1300)) // twice, close together
        assertFalse(k.down(1500)) // a fresh start after a stop
        assertFalse(k.down(2500)) // too far apart
        assertTrue(k.down(2800))
        val held = HushKeys()
        assertFalse(held.down(0))
        assertTrue(held.down(500, repeat = 1)) // held: as soon as it repeats
        assertFalse(held.down(550, repeat = 2)) // once
    }
}
