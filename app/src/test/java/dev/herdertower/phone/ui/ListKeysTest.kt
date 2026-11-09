package dev.herdertower.phone.ui

import dev.herdertower.phone.api.HistoryEntry
import dev.herdertower.phone.api.StateAgent
import dev.herdertower.phone.data.TowerSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** A lazy list crashes on a key used twice: the keys stay apart. */
class ListKeysTest {
    @Test fun theSameLineForTwoSessionsHasTwoKeys() {
        val a = HistoryEntry(at = 1_000, kind = "job", session = "t1", text = "Run the tests")
        val b = a.copy(session = "t2")
        // The history keeps both...
        assertEquals(2, TowerSession.merge(listOf(a), listOf(b)).size)
        // ...so their keys differ.
        assertNotEquals(historyKey(a), historyKey(b))
    }

    @Test fun aRepeatedKeyGetsASuffix() {
        val k = LazyKeys()
        assertEquals("a", k.unique("a"))
        assertEquals("a#2", k.unique("a"))
        assertEquals("a#3", k.unique("a"))
        assertEquals("b", k.unique("b"))
        assertEquals("a#2x", k.unique("a#2x"))
    }

    @Test fun eachSessionIsActiveOnce() {
        val agents = listOf(
            StateAgent("t5", "web", "working"),
            StateAgent("t5", "web", "working"),
            StateAgent("", "nameless", "blocked"),
        )
        assertEquals(listOf("t5"), ActiveModel.active(agents, emptyList(), null).map { it.address })
    }
}
