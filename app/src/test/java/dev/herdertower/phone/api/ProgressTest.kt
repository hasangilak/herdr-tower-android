package dev.herdertower.phone.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A working session's progress line, said against the clock as the tower says it (src/progress.rs). */
class ProgressTest {
    private val t0 = 1_794_624_360_000L
    private val s = 1000L
    private val min = 60_000L

    @Test fun theLineIsSaidAgainstTheClock() {
        val p = SessionProgress(line = "stale", step = 3, steps = 7, done = 2, item = "Running the test suite", turnMs = t0, lastMs = t0 + 13 * min)
        assertEquals("step 3 of 7: running the test suite · 14 min in · last update 1 min ago", ProgressApi.line(p, t0 + 14 * min))
        // Two minutes on without a read: both times move.
        assertEquals("step 3 of 7: running the test suite · 16 min in · last update 3 min ago", ProgressApi.line(p, t0 + 16 * min))
        // As the tower's own tests: a step just begun; a long call; a call alone; its words.
        assertEquals("step 1 of 3: counting the Rust files · 30 s in · last update just now",
            ProgressApi.line(SessionProgress(step = 1, steps = 3, item = "Counting the Rust files", turnMs = t0, lastMs = t0 + 30 * s), t0 + 30 * s))
        assertEquals("step 2 of 3: running the test suite · Bash: Run the tests for 4 min · 4 min in · last update 4 min ago",
            ProgressApi.line(SessionProgress(step = 2, steps = 3, item = "Running the test suite", tool = "Bash: Run the tests", toolMs = t0, turnMs = t0, lastMs = t0), t0 + 4 * min))
        assertEquals("Edit: schema.rs · 10 s in · last update just now", ProgressApi.line(SessionProgress(tool = "Edit: schema.rs", toolMs = t0, turnMs = t0, lastMs = t0 + 10 * s), t0 + 10 * s))
        assertEquals("\"Fixing the parser now.\" · 2 min in · last update 1 min ago",
            ProgressApi.line(SessionProgress(said = "Fixing the parser now.", turnMs = t0, lastMs = t0 + min), t0 + 2 * min))
        assertEquals("all 3 steps done · job: train.py: epoch 3 of 10 · 1 h 5 min in · last update just now",
            ProgressApi.line(SessionProgress(steps = 3, done = 3, job = "train.py: epoch 3 of 10", turnMs = t0, lastMs = t0 + 65 * min), t0 + 65 * min))
        // Nothing to say it from: the tower's line.
        assertEquals("working on it", ProgressApi.line(SessionProgress(line = "working on it"), t0))
    }

    @Test fun quietAfterTenMinutesAndSpans() {
        val p = SessionProgress(lastMs = t0)
        assertFalse(ProgressApi.quiet(p, t0 + 9 * min))
        assertTrue(ProgressApi.quiet(p, t0 + 10 * min))
        assertEquals(listOf("45 s", "14 min", "2 h", "1 h 5 min"), listOf(45 * s, 14 * min, 120 * min, 65 * min).map(ProgressApi::span))
        assertEquals("a b c…", ProgressApi.clip("a b c d e f g", 6))
    }

    @Test fun anAnswerSaysWhereItCameFrom() {
        assertNull(ProgressApi.viaNote(ProgressAnswer(via = "btw", answer = "On step 3")))
        assertEquals("From its transcript: it isn't working (done): nothing to ask aside",
            ProgressApi.viaNote(ProgressAnswer(via = "transcript", why = "it isn't working (done): nothing to ask aside")))
        // On a mission's session and on the state's agents.
        val m = TowerJson.decodeFromString(MissionsResponse.serializer(), """{"spaces":[{"name":"a","sessions":[{"address":"t7","progress":{"line":"x","step":3,"steps":7,"done":2,"items":[{"id":"1","subject":"Count","active":"Counting","status":"completed"}],"tool":"Bash: t","tool_ms":1,"turn_ms":1,"last_ms":2,"said":"s","job":"j"}},{"address":"t8"}]}]}""")
        assertEquals(3, m.spaces[0].sessions[0].progress?.step)
        assertEquals("completed", m.spaces[0].sessions[0].progress?.items?.single()?.status)
        assertNull(m.spaces[0].sessions[1].progress)
        val st = TowerJson.decodeFromString(TowerState.serializer(), """{"agents":[{"address":"t7","progress":{"line":"Bash: t","last_ms":5}}]}""")
        assertEquals(5L, st.agents.single().progress?.lastMs)
    }
}
