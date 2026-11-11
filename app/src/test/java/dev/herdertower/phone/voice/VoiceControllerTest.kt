package dev.herdertower.phone.voice

import dev.herdertower.phone.api.VoiceResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceControllerTest {
    private class Rig(scope: TestScope, val heard: String = "hello there") {
        var now = 10_000L // a real clock: 0 means "no press"
        val mics = mutableListOf<FakeMic>()
        val sent = mutableListOf<ByteArray>()
        val targets = mutableListOf<TalkTarget>()
        var hushes = 0
        var micFails = false
        val voice = VoiceController(
            scope.backgroundScope,
            hush = { hushes++ },
            send = { wav, to -> sent += wav; targets += to; Result.success(VoiceResult(heard, sent = true)) },
            linger = {},
            open = { if (micFails) throw IllegalStateException("The microphone isn't available.") else FakeMic().also { mics += it } },
            clock = { now },
        )
        val mic get() = mics.last()
    }

    @Test fun holdToTalkSendsOnRelease() = runTest(UnconfinedTestDispatcher()) {
        val r = Rig(this)
        r.voice.press()
        assertEquals(VoiceController.Phase.Recording(handsFree = false), r.voice.phase.value)
        assertTrue("a held take ignores pauses", r.mic.held)
        assertEquals(1, r.hushes) // barge in
        r.now += 2000
        r.voice.release()
        advanceUntilIdle()
        assertEquals(1, r.sent.size)
        assertEquals(VoiceController.Phase.Idle, r.voice.phase.value)
        assertEquals("hello there", r.voice.heard.value?.text)
    }

    @Test fun aTapListensUntilAPause() = runTest(UnconfinedTestDispatcher()) {
        val r = Rig(this)
        r.voice.press()
        r.now += 100
        r.voice.release()
        assertEquals(VoiceController.Phase.Recording(handsFree = true), r.voice.phase.value)
        assertFalse(r.mic.held)
        assertTrue(r.sent.isEmpty())
        r.mic.ended.complete(Take.Reason.Silence)
        advanceUntilIdle()
        assertEquals(1, r.sent.size)
        assertEquals(VoiceController.Phase.Idle, r.voice.phase.value)
    }

    @Test fun aSecondTapEndsAHandsFreeTake() = runTest(UnconfinedTestDispatcher()) {
        val r = Rig(this)
        r.voice.press()
        r.now += 100
        r.voice.release()
        r.now += 3000
        r.voice.press()
        r.voice.release()
        advanceUntilIdle()
        assertEquals(1, r.sent.size)
        assertEquals(1, r.mics.size)
    }

    @Test fun nothingSaidSendsNothing() = runTest(UnconfinedTestDispatcher()) {
        val r = Rig(this)
        r.voice.toggle()
        r.mic.speech = false
        r.mic.ended.complete(Take.Reason.NothingSaid)
        advanceUntilIdle()
        assertTrue(r.sent.isEmpty())
        assertEquals(VoiceController.Phase.Idle, r.voice.phase.value)
        assertEquals("Didn't hear anything.", r.voice.error.value)
        assertEquals(1, r.mic.stops)
    }

    @Test fun tooShortIsNotSent() = runTest(UnconfinedTestDispatcher()) {
        val r = Rig(this)
        r.voice.press()
        r.mic.ms = 100
        r.now += 400
        r.voice.release()
        advanceUntilIdle()
        assertTrue(r.sent.isEmpty())
        assertEquals(VoiceController.Phase.Idle, r.voice.phase.value)
        assertTrue(r.voice.error.value!!.startsWith("Too short"))
    }

    @Test fun aFailingMicrophoneDoesntLeaveItListening() = runTest(UnconfinedTestDispatcher()) {
        val r = Rig(this)
        r.voice.toggle()
        r.mic.speech = false
        r.mic.ended.complete(Take.Reason.MicFailed)
        advanceUntilIdle()
        assertEquals(VoiceController.Phase.Idle, r.voice.phase.value)
        assertEquals("The microphone stopped.", r.voice.error.value)
        assertTrue(r.sent.isEmpty())
        assertEquals(1, r.mic.stops)
    }

    @Test fun whatWasSaidBeforeTheMicrophoneFailedIsSent() = runTest(UnconfinedTestDispatcher()) {
        val r = Rig(this)
        r.voice.toggle()
        r.mic.ended.complete(Take.Reason.MicFailed)
        advanceUntilIdle()
        assertEquals(1, r.sent.size)
        assertEquals(VoiceController.Phase.Idle, r.voice.phase.value)
    }

    @Test fun noMicrophoneIsAnError() = runTest(UnconfinedTestDispatcher()) {
        val r = Rig(this)
        r.micFails = true
        r.voice.press()
        assertEquals(VoiceController.Phase.Idle, r.voice.phase.value)
        assertEquals("The microphone isn't available.", r.voice.error.value)
        r.voice.release()
        assertEquals(VoiceController.Phase.Idle, r.voice.phase.value)
    }

    @Test fun cancelStopsWithoutSending() = runTest(UnconfinedTestDispatcher()) {
        val r = Rig(this)
        r.voice.toggle()
        r.voice.cancel()
        advanceUntilIdle()
        assertEquals(VoiceController.Phase.Idle, r.voice.phase.value)
        assertTrue(r.sent.isEmpty())
        assertEquals(1, r.mic.stops)
        assertNull(r.voice.error.value)
    }

    @Test fun whispersMarkersAreNotWords() {
        assertEquals("", VoiceController.clean("[BLANK_AUDIO]"))
        assertEquals("hi", VoiceController.clean(" (silence) hi"))
    }

    @Test fun aTakeGoesWhereTheScreenSaidWhenItBegan() = runTest(UnconfinedTestDispatcher()) {
        val r = Rig(this)
        r.voice.target = TalkTarget.Space("sandbox")
        r.voice.press()
        // The user moves to another screen while talking: the take still goes to the space it was begun for.
        r.voice.target = TalkTarget.Main
        r.now += 2000
        r.voice.release()
        advanceUntilIdle()
        assertEquals(listOf<TalkTarget>(TalkTarget.Space("sandbox")), r.targets)
        assertEquals(TalkTarget.Space("sandbox"), r.voice.heard.value?.target)
        // The next one goes to the main tower.
        r.voice.press()
        r.now += 2000
        r.voice.release()
        advanceUntilIdle()
        assertEquals(TalkTarget.Main, r.targets.last())
    }

    @Test fun theWordsSoFarShowWhileTalking() = runTest(UnconfinedTestDispatcher()) {
        var asked = 0
        val mic = FakeMic(ms = 3000)
        val voice = VoiceController(
            backgroundScope, hush = {}, send = { _, _ -> Result.success(VoiceResult("open a session in M5 Pro", sent = true)) }, linger = {}, open = { mic },
            clock = { 10_000L }, words = { asked++; "open a session [BLANK_AUDIO]" },
        )
        assertEquals("", voice.hearing.value)
        voice.toggle()
        testScheduler.advanceTimeBy(1100)
        testScheduler.runCurrent()
        assertEquals("the Mac's words so far, without Whisper's markers", "open a session", voice.hearing.value)
        assertEquals(1, mic.snapshots)
        // The take hasn't grown: it isn't sent again.
        testScheduler.advanceTimeBy(1000)
        testScheduler.runCurrent()
        assertEquals(1, asked)
        voice.cancel()
        assertEquals("", voice.hearing.value)
        testScheduler.advanceTimeBy(3000)
        testScheduler.runCurrent()
        assertEquals("nothing more is written down after the take", 1, asked)
    }
}
