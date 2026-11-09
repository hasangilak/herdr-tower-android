package dev.herdertower.phone.compose

import dev.herdertower.phone.api.VoiceResult
import dev.herdertower.phone.voice.FakeMic
import dev.herdertower.phone.voice.Take
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TowerDictationTest {
    private class Rig(scope: TestScope, val mic: FakeMic = FakeMic(ms = 0)) {
        val sizes = mutableListOf<Int>()
        var reply: suspend (Int) -> String = { "hello" }
        val texts = mutableListOf<String>()
        val d = TowerDictation(scope.backgroundScope, { wav -> sizes += wav.size; Result.success(VoiceResult(reply(sizes.size))) }, { mic })
    }

    @Test fun wordsComeWhileTalkingAndTheWholeTakeAtTheEnd() = runTest(UnconfinedTestDispatcher()) {
        val r = Rig(this)
        r.d.start { r.texts += it }
        assertTrue(r.d.active.value)
        r.mic.ms = 1500
        advanceTimeBy(1001)
        assertEquals(listOf("hello"), r.texts)
        r.reply = { "hello world" }
        r.d.stop()
        advanceUntilIdle()
        assertEquals("hello world", r.texts.last())
        assertFalse(r.d.active.value)
        assertEquals(1, r.mic.stops)
    }

    @Test fun aLatePartialNeverLandsAfterTheFinalWords() = runTest(UnconfinedTestDispatcher()) {
        val r = Rig(this)
        val never = CompletableDeferred<String>()
        r.reply = { n -> if (n == 1) never.await() else "the final words" }
        r.d.start { r.texts += it }
        r.mic.ms = 1500
        advanceTimeBy(1001) // a partial is on its way
        r.d.stop()
        advanceUntilIdle()
        never.complete("an old partial")
        advanceUntilIdle()
        assertEquals(listOf("the final words"), r.texts)
    }

    @Test fun aPauseEndsItOnceEvenIfStopComesToo() = runTest(UnconfinedTestDispatcher()) {
        val r = Rig(this)
        val gate = CompletableDeferred<String>()
        r.reply = { gate.await() }
        r.d.start { r.texts += it }
        r.mic.ms = 500 // no partial yet
        r.mic.ended.complete(Take.Reason.Silence)
        runCurrent() // the final transcription is on its way
        r.d.stop()
        gate.complete("done")
        advanceUntilIdle()
        assertEquals(listOf("done"), r.texts)
        assertEquals(1, r.mic.stops)
        assertEquals(1, r.sizes.size)
        assertFalse(r.d.active.value)
    }

    @Test fun nothingSaidEmptiesWhatWasGuessed() = runTest(UnconfinedTestDispatcher()) {
        val r = Rig(this)
        r.d.start { r.texts += it }
        r.mic.speech = false
        r.mic.ended.complete(Take.Reason.NothingSaid)
        advanceUntilIdle()
        assertEquals(listOf(""), r.texts)
        assertEquals("Didn't hear anything.", r.d.error.value)
        assertFalse(r.d.active.value)
        r.d.clearError()
        assertEquals(null, r.d.error.value)
    }

    @Test fun aFailingMicrophoneEndsIt() = runTest(UnconfinedTestDispatcher()) {
        val r = Rig(this)
        r.d.start { r.texts += it }
        r.mic.speech = false
        r.mic.ended.complete(Take.Reason.MicFailed)
        advanceUntilIdle()
        assertFalse(r.d.active.value)
        assertEquals("The microphone stopped.", r.d.error.value)
        assertTrue(r.sizes.isEmpty())
    }

    @Test fun partialsGetRarerAsTheTakeGrows() = runTest(UnconfinedTestDispatcher()) {
        // Each partial sends the whole take: one a second would send it ~60 times over in two minutes.
        val r = Rig(this)
        r.d.start { r.texts += it }
        for (s in 1..115) {
            r.mic.ms = s * 1000L
            advanceTimeBy(1000)
        }
        val partials = r.sizes.size
        val sentMs = r.sizes.sumOf { (it - 44) / 32L }
        assertTrue("$partials partials", partials in 20..40)
        assertTrue("sent ${sentMs / 1000} s of audio", sentMs < 12 * 115_000)
        r.d.stop()
        advanceUntilIdle()
        assertEquals(partials + 1, r.sizes.size) // and the whole take once more at the end
    }
}
