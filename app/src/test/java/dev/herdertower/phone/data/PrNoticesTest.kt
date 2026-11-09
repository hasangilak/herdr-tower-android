package dev.herdertower.phone.data

import dev.herdertower.phone.api.Pr
import dev.herdertower.phone.api.PrChecks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrNoticesTest {
    private class Recorded : PrNotices.Out {
        val posted = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        override fun post(pr: Pr) {
            posted += pr.id
        }
        override fun cancel(key: String) {
            cancelled += key
        }
    }

    private fun pr(n: Long, waiting: Boolean = true, state: String = "open") =
        Pr(key = "o/app#$n", repo = "o/app", number = n, title = "fix $n", base = "main", state = state, waiting = waiting)

    @Test fun aWaitingPrNotifiesOnceAndGoesWhenMerged() {
        val out = Recorded()
        val n = PrNotices({ true }, out)
        n.follow(listOf(pr(1), pr(2, waiting = false)), background = true)
        n.follow(listOf(pr(1), pr(2, waiting = false)), background = true)
        assertEquals("once, and only the waiting one", listOf("o/app#1"), out.posted)
        n.follow(listOf(pr(1, state = "merged")), background = true)
        assertEquals(listOf("o/app#1"), out.cancelled)
    }

    @Test fun notOnScreenNorWithoutPermission() {
        val out = Recorded()
        PrNotices({ true }, out).follow(listOf(pr(1)), background = false)
        PrNotices({ false }, out).follow(listOf(pr(2)), background = true)
        assertTrue(out.posted.isEmpty())
    }

    @Test fun waitingAgainLaterNotifiesAgain() {
        val out = Recorded()
        val n = PrNotices({ true }, out)
        n.follow(listOf(pr(1)), background = true)
        n.follow(listOf(pr(1, waiting = false)), background = true)
        n.follow(listOf(pr(1)), background = true)
        assertEquals(listOf("o/app#1", "o/app#1"), out.posted)
    }

    @Test fun itsWords() {
        val p = pr(1250).copy(repo = "eyzhub/mediaroom", name = "b2c tickets", checks = PrChecks("failing", failing = listOf("CI / lint")))
        assertEquals("PR #1250 waits for you · eyzhub/mediaroom", PrNotices.title(p))
        assertEquals("fix 1250\nInto main · from b2c tickets\nFailing: CI / lint", PrNotices.text(p))
    }
}
