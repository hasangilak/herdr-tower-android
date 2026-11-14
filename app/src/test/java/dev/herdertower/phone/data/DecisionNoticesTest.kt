package dev.herdertower.phone.data

import dev.herdertower.phone.api.Decision
import dev.herdertower.phone.api.DecisionOption
import dev.herdertower.phone.api.DecisionPr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one notification: a decision the phone hasn't seen, while the app isn't on screen. */
class DecisionNoticesTest {
    @Test fun thePermissionCountsOnlyFromAndroid13() {
        // Android 10 to 12: no POST_NOTIFICATIONS permission exists, and it reads as denied; it mustn't block.
        for (sdk in 29..32) {
            assertTrue("sdk $sdk", DecisionNotices.allowed(sdk, notificationsOn = true, permissionGranted = false))
        }
        // Android 13 and later: it's needed.
        for (sdk in 33..37) {
            assertFalse("sdk $sdk", DecisionNotices.allowed(sdk, notificationsOn = true, permissionGranted = false))
            assertTrue("sdk $sdk", DecisionNotices.allowed(sdk, notificationsOn = true, permissionGranted = true))
        }
        // Notifications turned off for the app: never, on any version.
        for (sdk in 29..37) assertFalse(DecisionNotices.allowed(sdk, notificationsOn = false, permissionGranted = true))
    }

    private class Recorded : DecisionNotices.Out {
        val posted = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        val failed = mutableListOf<String>()
        /** The notifications showing, as the system would list them. */
        val showing = linkedSetOf<String>()
        override fun post(d: Decision) {
            posted += d.id
            showing += d.id
        }
        override fun cancel(id: String) {
            cancelled += id
            showing -= id
        }
        override fun active(): Set<String> = showing.toSet()
        override fun failed(d: Decision, why: String) {
            failed += "${d.id}: $why"
            showing += d.id
        }
    }

    private class Kept(var ids: List<String> = emptyList()) : DecisionNotices.Seen {
        override fun load() = ids
        override fun save(ids: List<String>) {
            this.ids = ids.toList()
        }
    }

    private fun d(id: String, state: String = "open", muted: Boolean = false, count: Int = 1) =
        Decision(id = id, title = "Allow $id?", state = state, muted = muted, count = count, options = listOf(DecisionOption("Allow once", pick = true), DecisionOption("No")))

    @Test fun aNewDecisionNotifiesOnceWhileTheAppIsAway() {
        val out = Recorded()
        val n = DecisionNotices({ true }, out, Kept())
        n.follow(listOf(d("d1")), background = true)
        // The same thing asked again: the same id with a higher count. A change, never a second alert.
        n.follow(listOf(d("d1", count = 3)), background = true)
        n.follow(listOf(d("d1", count = 3), d("d2")), background = true)
        assertEquals(listOf("d1", "d2"), out.posted)
    }

    @Test fun nothingNotifiesOnScreenOrWithoutPermission() {
        val out = Recorded()
        val n = DecisionNotices({ true }, out, Kept())
        n.follow(listOf(d("d1")), background = false)
        // Seen in the app: going to the background later doesn't bring it up.
        n.follow(listOf(d("d1")), background = true)
        DecisionNotices({ false }, out, Kept()).follow(listOf(d("d2")), background = true)
        assertTrue(out.posted.isEmpty())
    }

    @Test fun aMutedSpacesDecisionIsSeenAndNeverNotified() {
        val out = Recorded()
        val kept = Kept()
        val n = DecisionNotices({ true }, out, kept)
        n.follow(listOf(d("d1", muted = true)), background = true)
        assertTrue(out.posted.isEmpty())
        assertEquals(listOf("d1"), kept.ids)
        // Its space unmuted: the same id, not new. Not then either, as on the Mac.
        n.follow(listOf(d("d1")), background = true)
        assertTrue(out.posted.isEmpty())
        // A decision being answered when first seen is remembered too.
        n.follow(listOf(d("d1"), d("d2", "answering")), background = true)
        n.follow(listOf(d("d1"), d("d2")), background = true)
        assertTrue(out.posted.isEmpty())
    }

    @Test fun anAnsweredOrGoneDecisionTakesItsNotificationAway() {
        val out = Recorded()
        val n = DecisionNotices({ true }, out, Kept())
        n.follow(listOf(d("d1"), d("d2"), d("d3")), background = true)
        // Answered on the Mac: its answer going in, nothing left to tap.
        n.follow(listOf(d("d1", "answering"), d("d2"), d("d3")), background = true)
        assertEquals(listOf("d1"), out.cancelled)
        // Its space muted: off the queue, and off the screen.
        n.follow(listOf(d("d2"), d("d3", muted = true)), background = true)
        assertEquals(setOf("d2"), out.showing)
        n.follow(emptyList(), background = true)
        assertTrue(out.showing.isEmpty())
        // An id is never new twice: the same need again later is another decision, with another id.
        n.follow(listOf(d("d2")), background = true)
        assertEquals(listOf("d1", "d2", "d3"), out.posted)
    }

    @Test fun whatWasSeenIsKeptAcrossRestarts() {
        val kept = Kept()
        val first = Recorded()
        DecisionNotices({ true }, first, kept).follow(listOf(d("d1")), background = true)
        assertEquals(listOf("d1"), kept.ids)
        // The process was stopped and started again in the background: no second alert for the same decision.
        val second = Recorded().apply { showing += "d1" }
        val n = DecisionNotices({ true }, second, kept)
        n.follow(listOf(d("d1"), d("d2")), background = true)
        assertEquals(listOf("d2"), second.posted)
        // Its notification from before the restart still goes when it's over.
        n.follow(listOf(d("d2")), background = true)
        assertEquals(listOf("d1"), second.cancelled)
        assertEquals(listOf("d1", "d2"), kept.ids)
    }

    @Test fun comingOnScreenClearsAndAnotherTowerStartsOver() {
        val out = Recorded()
        val kept = Kept()
        val n = DecisionNotices({ true }, out, kept)
        n.follow(listOf(d("d1")), background = true)
        n.clearAll()
        assertEquals(listOf("d1"), out.cancelled)
        n.forget()
        assertTrue(kept.ids.isEmpty())
        n.follow(listOf(d("d1")), background = true)
        assertEquals(listOf("d1", "d1"), out.posted)
    }

    @Test fun aFailedAnswerSaysSoOnTheNotification() {
        val out = Recorded()
        val n = DecisionNotices({ true }, out, Kept())
        n.failed(d("d1"), "the Mac didn't answer in time")
        assertEquals(listOf("d1: the Mac didn't answer in time"), out.failed)
        n.answered("d1")
        assertEquals(listOf("d1"), out.cancelled)
    }

    @Test fun theFirstTwoAnswersAreTheActions() {
        val three = Decision(id = "d", options = listOf(DecisionOption("B, magnified view", pick = true), DecisionOption("A"), DecisionOption("C")))
        assertEquals(listOf(0 to "B, magnified view", 1 to "A"), DecisionNotices.actions(three))
        assertEquals(emptyList<Pair<Int, String>>(), DecisionNotices.actions(Decision(id = "d")))
        // A merge shows what merging would do first: only in the app.
        val merge = Decision(id = "m", kind = "merge", options = listOf(DecisionOption("#12 into dev")), prs = listOf(DecisionPr("o/app", 12, "dev", "pass")))
        assertEquals(emptyList<Pair<Int, String>>(), DecisionNotices.actions(merge))
        assertEquals("Production data · 3 sessions wait", DecisionNotices.line(Decision(why = "Production data", waiting = 3)))
        assertEquals("Nothing is blocked", DecisionNotices.line(Decision()))
    }

    /** Rule 5: a decision back from hold notifies once, as a new one, with why; one on hold never does. */
    @Test fun aDecisionBackFromHoldNotifiesOnceAndOneOnHoldNever() {
        val out = Recorded()
        val kept = Kept()
        val n = DecisionNotices({ true }, out, kept)
        n.follow(listOf(d("d1")), background = true)
        // Held: it leaves the list; never notified while held.
        n.follow(emptyList(), background = true)
        n.follow(listOf(d("d2").copy(hold = dev.herdertower.phone.api.DecisionHold("later"))), background = true)
        assertEquals(listOf("d1"), out.posted)
        // Back, saying why: once. Seen again with the same back: nothing.
        val back = d("d1").copy(back = dev.herdertower.phone.api.DecisionBack("PR #829 merged, which it waited for", 5000))
        n.follow(listOf(back), background = true)
        n.follow(listOf(back), background = true)
        assertEquals(listOf("d1", "d1"), out.posted)
        assertEquals("Back from hold: PR #829 merged, which it waited for", DecisionNotices.line(back))
        // After a restart the mark is still there; back once more (later) notifies again.
        val again = DecisionNotices({ true }, out, kept)
        again.follow(listOf(back), background = true)
        again.follow(listOf(d("d1").copy(back = dev.herdertower.phone.api.DecisionBack("PR #830 has new commits", 9000))), background = true)
        assertEquals(listOf("d1", "d1", "d1"), out.posted)
    }

    /** Let go from hold on this phone: no notification for it here. */
    @Test fun oneLetGoHereDoesNotNotify() {
        val out = Recorded()
        val n = DecisionNotices({ true }, out, Kept())
        val back = d("d1").copy(back = dev.herdertower.phone.api.DecisionBack("you let it go from hold", 7000))
        n.seenBack(back)
        n.follow(listOf(back), background = true)
        assertEquals(emptyList<String>(), out.posted)
    }
}
