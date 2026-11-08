package dev.herdertower.phone.ui

import dev.herdertower.phone.api.Job
import dev.herdertower.phone.api.Space
import dev.herdertower.phone.api.SpaceSession
import dev.herdertower.phone.api.SpacesResponse
import dev.herdertower.phone.api.StateAgent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveModelTest {
    private val agents = listOf(
        StateAgent("t5", "web", "working", account = "main"),
        StateAgent("t3", "api", "blocked", account = "studio"),
        StateAgent("t7", "docs", "working"),
        StateAgent("t9", "infra-bot", "working"),
        StateAgent("t2", "deploy", "idle"),
        StateAgent("t11", "qfix-ask", "working"),
        StateAgent("t12", "tower.test-1", "blocked"),
    )
    private val jobs = listOf(
        Job("j1", "t5", "web", "Storefront", "main", "working", "Build the checkout", startedMs = 1_000),
        Job("j0", "t5", "web", "Storefront", "main", "done", "An older one", startedMs = 100),
        Job("j3", "t7", "docs", "Storefront", null, "working", "Update the guide", startedMs = 5_000),
        Job("j2", "t3", "api", "Storefront", "studio", "blocked", "Migrate the orders table", startedMs = 3_000),
    )
    private val spaces = SpacesResponse(
        focus = null,
        spaces = listOf(Space("Storefront", sessions = listOf(SpaceSession("t3"), SpaceSession("t5"), SpaceSession("t7"))), Space("Infra", sessions = listOf(SpaceSession("t9")))),
    )

    @Test fun workingAndBlockedOnlyNeverTests() {
        val a = ActiveModel.active(agents, jobs, spaces)
        assertEquals(listOf("t3", "t5", "t7", "t9"), a.map { it.address })
        assertTrue(ActiveModel.isTest("QFix-something"))
        assertTrue(ActiveModel.isTest("tower.test"))
    }

    @Test fun blockedFirstThenTheLongestRunning() {
        val a = ActiveModel.active(agents, jobs, spaces)
        assertTrue(a.first().blocked)
        assertEquals(1_000L, a[1].sinceMs) // web started first
        assertNull(a.last().sinceMs) // no job: after those with one
        assertEquals("Build the checkout", a[1].doing) // its open job, not the done one
        assertEquals("Infra", a.last().space)
    }

    @Test fun theFocusedSpaceFirstTheRestDimmed() {
        val a = ActiveModel.active(agents, jobs, spaces)
        val f = ActiveModel.focusFirst(a, "Infra")
        assertEquals("t9", f.first().first.address)
        assertEquals(false, f.first().second)
        assertTrue(f.drop(1).all { it.second })
        assertTrue(ActiveModel.focusFirst(a, null).none { it.second })
    }

    @Test fun saidAloud() {
        val a = ActiveModel.active(agents, jobs, spaces).first()
        assertEquals("api, needs you, in Storefront, for 2 s: Migrate the orders table", ActiveModel.describe(a, 5_000))
    }
}
