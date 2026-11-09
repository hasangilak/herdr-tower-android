package dev.herdertower.phone.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone's own dictation engine, with a scripted recognizer in place of the phone's. */
class PhoneDictationTest {
    private class Scripted : Recognizer {
        var events: RecognizerEvents? = null
        var stopped = false
        var destroyed = false
        override fun start(events: RecognizerEvents) {
            this.events = events
        }
        override fun stop() {
            stopped = true
        }
        override fun destroy() {
            destroyed = true
        }
    }

    private val delayed = mutableListOf<() -> Unit>()
    /** A new recognizer for each dictation, as the phone gives; [r] is the latest. */
    private val made = mutableListOf<Scripted>()
    private val r get() = made.last()
    private val dictation = Dictation({ true }, { Scripted().also { made += it } }, { _, block -> delayed += block })
    private val box = mutableListOf<String>()

    @Test fun theFinalWordsLandAfterStop() {
        dictation.start { box += it }
        r.events!!.partial("check the web")
        // Stop while the last words are still being recognized.
        dictation.stop()
        assertTrue(r.stopped)
        assertFalse("not destroyed at Stop: that would lose the final result", r.destroyed)
        assertTrue("still active until the final words come", dictation.active.value)
        r.events!!.result("check the web session and tell me when it's done")
        assertEquals("check the web session and tell me when it's done", box.last())
        assertTrue(r.destroyed)
        assertFalse(dictation.active.value)
    }

    @Test fun withoutAFinalResultItGivesUpAfterTheWait() {
        dictation.start { box += it }
        r.events!!.partial("check the")
        dictation.stop()
        assertEquals(1, delayed.size)
        delayed.single()() // the wait runs out
        assertTrue(r.destroyed)
        assertFalse(dictation.active.value)
        assertEquals(listOf("check the"), box)
    }

    @Test fun nothingHeardAfterStopIsntAnError() {
        dictation.start { box += it }
        dictation.stop()
        r.events!!.error(android.speech.SpeechRecognizer.ERROR_NO_MATCH)
        assertNull("you stopped it: no 'Didn't catch that'", dictation.error.value)
        assertFalse(dictation.active.value)
    }

    @Test fun nothingHeardWithoutStopSaysSo() {
        dictation.start { box += it }
        r.events!!.error(android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        assertEquals("Didn't catch that.", dictation.error.value)
    }

    @Test fun aLateResultFromAnEarlierDictationIsDropped() {
        dictation.start { box += it }
        val first = r.events!!
        dictation.start { box += it } // a new one before the first finished
        first.result("old words")
        assertTrue(box.isEmpty())
    }
}
