package dev.herdertower.phone.data

import dev.herdertower.phone.api.HistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryMergeTest {
    private fun e(at: Long, text: String = "t$at") = HistoryEntry(at = at, kind = "answer", text = text)

    @Test fun aReloadAndTheStreamDontDuplicate() {
        val live = listOf(e(5), e(7))
        val reloaded = listOf(e(3), e(5), e(6))
        assertEquals(listOf(3L, 5, 6, 7), TowerSession.merge(live, reloaded).map { it.at })
    }

    @Test fun olderPagesGoInFront() {
        assertEquals(listOf(1L, 2, 9), TowerSession.merge(listOf(e(9)), listOf(e(1), e(2))).map { it.at })
    }

    @Test fun backoffIsTheContracts() {
        assertEquals(listOf(1, 2, 5, 10), TowerSession.BACKOFF.toList())
    }
}
