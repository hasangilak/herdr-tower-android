package dev.herdertower.phone.ui

import dev.herdertower.phone.api.Agent
import dev.herdertower.phone.api.AudioClip
import dev.herdertower.phone.api.Job
import dev.herdertower.phone.api.Space
import dev.herdertower.phone.api.SpaceSession
import dev.herdertower.phone.api.SpacesResponse
import dev.herdertower.phone.api.StateAgent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeModelTest {
    private val agents = listOf(
        StateAgent("t3", "api", "blocked", "claude-code", "eyz"),
        StateAgent("t5", "web", "working", "claude-code", "main"),
    )
    private val spaces = SpacesResponse(
        null,
        listOf(
            Space("Infra", unread = 2, sessions = listOf(SpaceSession("vps/t2", "deploy", "idle")), lastActivityMs = 5),
            Space("B2C support", runningJobs = 2, sessions = listOf(SpaceSession("t3", "api", "blocked"), SpaceSession("t5", "web", "working")), lastActivityMs = 9),
        ),
    )
    private val jobs = listOf(
        Job("j2", "t3", "api", "B2C support", status = "blocked", asked = "Fix the failing migration"),
        Job("j9", "t7", "docs", "Docs", status = "blocked", asked = "Publish?"),
    )

    @Test fun blockedSessionsComeFirstThenRealAsks() {
        val s = HomeModel.suggestions(agents, spaces, jobs, listOf(Agent(address = "t3", doing = "Waiting for approval")))
        assertEquals(Suggestion.Answer("t3", "api", "Waiting for approval"), s[0])
        assertEquals(Suggestion.Answer("t7", "docs", "Publish?"), s[1])
        assertEquals("Status of all sessions", (s[2] as Suggestion.Ask).title)
        assertEquals("Catch me up", (s[3] as Suggestion.Ask).title)
        assertEquals(Suggestion.Ask("News in Infra", "What's new in Infra?", "2 new"), s[4])
        // No focus yet: suggest the space that needs attention most.
        assertEquals("B2C support", (s.last() as Suggestion.Focus).space)
        assertEquals(s.size, s.map { it.key }.toSet().size)
    }

    @Test fun aFocusedSpaceOffersToClearIt() {
        val s = HomeModel.suggestions(emptyList(), spaces.copy(focus = "Infra"), emptyList())
        assertEquals(Suggestion.ClearFocus("Infra"), s.last())
    }

    @Test fun greetingAndNeedsLine() {
        assertEquals("Good morning", HomeModel.timeGreeting(8))
        assertEquals("Good evening", HomeModel.timeGreeting(20))
        assertEquals("Hello", HomeModel.timeGreeting(2))
        assertEquals("2 sessions need you", HomeModel.needsLine(2, 1, 5))
        assertEquals("1 session needs you", HomeModel.needsLine(1, 0, 5))
        assertEquals("3 at work", HomeModel.needsLine(0, 3, 5))
        assertEquals("all quiet", HomeModel.needsLine(0, 0, 5))
    }

    @Test fun theOrbPlaysWhatThePhoneDoesOverTheTowersPhase() {
        assertEquals("listening", HomeModel.orbPhase(true, "idle", recording = true, sending = false, playing = null))
        assertEquals("transcribing", HomeModel.orbPhase(true, "idle", false, true, null))
        assertEquals("speaking", HomeModel.orbPhase(true, "thinking", false, false, AudioClip(kind = "reply")))
        assertEquals("announcing", HomeModel.orbPhase(true, "idle", false, false, AudioClip(kind = "alert")))
        assertEquals("off", HomeModel.orbPhase(false, "idle", false, false, null))
        assertEquals("blocked", HomeModel.orbPhase(true, "blocked", false, false, null))
        assertTrue(HomeModel.needsYou(true, "idle", 1))
        assertTrue(!HomeModel.needsYou(false, "blocked", 1))
    }
}
