package dev.herdertower.phone.ui

import dev.herdertower.phone.api.MergeConfirm
import dev.herdertower.phone.api.MergeResult
import dev.herdertower.phone.api.Pr
import dev.herdertower.phone.api.PrChecks
import dev.herdertower.phone.api.PrGate
import dev.herdertower.phone.api.TowerApiException
import dev.herdertower.phone.api.UnreachableException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrModelTest {
    private val open = Pr(
        key = "o/app#12", repo = "o/app", number = 12, title = "release: tickets", base = "main", head = "dev", state = "open",
        mergeable = "mergeable", checks = PrChecks("passing", passed = 5, total = 5), name = "mediaroom", merge = PrGate(can = true),
    )

    @Test fun whereItStands() {
        assertEquals(PrModel.Phase.Open, PrModel.phase(open))
        assertEquals(PrModel.Phase.Waiting, PrModel.phase(open.copy(waiting = true)))
        assertEquals(PrModel.Phase.Draft, PrModel.phase(open.copy(draft = true, waiting = true)))
        // Waiting means nothing once it's merged or closed.
        assertEquals(PrModel.Phase.Merged, PrModel.phase(open.copy(state = "merged", waiting = true)))
        assertEquals(PrModel.Phase.Closed, PrModel.phase(open.copy(state = "closed")))
        assertEquals(PrModel.Phase.Unknown, PrModel.phase(Pr(key = "o/app#12")))
    }

    @Test fun checksInWords() {
        assertEquals("Checks passing (5)" to PrModel.Tone.Good, PrModel.checks(open))
        assertEquals("Failing: CI / lint" to PrModel.Tone.Bad, PrModel.checks(open.copy(checks = PrChecks("failing", failing = listOf("CI / lint"), passed = 4, total = 5))))
        assertEquals(
            "Failing: a, b and 2 more" to PrModel.Tone.Bad,
            PrModel.checks(open.copy(checks = PrChecks("failing", failing = listOf("a", "b", "c", "d"), total = 6))),
        )
        assertEquals("Checks running (3 of 5 done)" to PrModel.Tone.Pending, PrModel.checks(open.copy(checks = PrChecks("pending", pending = listOf("x", "y"), passed = 3, total = 5))))
        assertEquals("No checks" to PrModel.Tone.Quiet, PrModel.checks(open.copy(checks = PrChecks("none"))))
    }

    @Test fun conflicts() {
        assertEquals("No conflicts" to PrModel.Tone.Good, PrModel.mergeable(open))
        assertEquals("Conflicts with main" to PrModel.Tone.Bad, PrModel.mergeable(open.copy(mergeable = "conflicting")))
        assertEquals("Checking for conflicts" to PrModel.Tone.Pending, PrModel.mergeable(open.copy(mergeable = "unknown")))
        assertNull(PrModel.mergeable(open.copy(state = "merged")))
    }

    @Test fun theMergeButton() {
        assertEquals(PrModel.MergeButton(true, null), PrModel.mergeButton(open))
        // Failing checks: still enabled (the sheet asks the second yes).
        assertEquals(true, PrModel.mergeButton(open.copy(merge = PrGate(can = true, needsOverride = "1 check failing")))?.enabled)
        val conflict = PrModel.mergeButton(open.copy(merge = PrGate(can = false, blocked = "It has conflicts with main: they need resolving first.")))
        assertEquals(PrModel.MergeButton(false, "It has conflicts with main: they need resolving first."), conflict)
        // Gone once merged or closed.
        assertNull(PrModel.mergeButton(open.copy(state = "merged")))
        assertNull(PrModel.mergeButton(open.copy(state = "closed")))
    }

    @Test fun mergedAndClosedLines() {
        assertEquals("Merged into main from the Mac", PrModel.closedLine(open.copy(state = "merged", mergedBy = "mac")))
        assertEquals("Merged into main from a phone", PrModel.closedLine(open.copy(state = "merged", mergedBy = "phone:d_1")))
        assertEquals("Merged into main on GitHub", PrModel.closedLine(open.copy(state = "merged", mergedBy = "github")))
        assertEquals("Closed without merging", PrModel.closedLine(open.copy(state = "closed")))
        assertNull(PrModel.closedLine(open))
    }

    @Test fun aScreenReaderHearsTheWholeChip() {
        assertEquals(
            "PR #12 · o/app: release: tickets, dev into main, waiting for you, Failing: lint, No conflicts",
            PrModel.describe(open.copy(waiting = true, checks = PrChecks("failing", failing = listOf("lint")))),
        )
        assertEquals("PR #12 · o/app: release: tickets, dev into main, Merged into main from the Mac", PrModel.describe(open.copy(state = "merged", mergedBy = "mac")))
    }

    @Test fun mainIsAlwaysWarnedAbout() {
        val given = listOf("It merges into main: that ships to production.", "1 check is still running.")
        assertEquals(given, PrModel.warnings("main", given))
        // An older tower without it: added.
        assertEquals(listOf("It merges into main: that ships to production."), PrModel.warnings("main", emptyList()))
        assertEquals(emptyList<String>(), PrModel.warnings("dev", emptyList()))
    }

    // The merge flow

    private fun confirm(needsOverride: String? = null, can: Boolean = true, blocked: String? = null) =
        MergeConfirm(token = "tok", method = "squash", can = can, blocked = blocked, needsOverride = needsOverride, pr = open)

    @Test fun passingChecksMergeOnOneYes() {
        val ready = MergeStep.looked(confirm(), now = 1_000) as MergeStep.Ready
        assertEquals(MergeStep.Merging(ready.confirm, override = false), MergeStep.mergeTapped(ready, now = 5_000))
    }

    @Test fun failingChecksNeedTheSecondYesAndOnlyThenOverride() {
        val ready = MergeStep.looked(confirm(needsOverride = "1 check failing: lint."), now = 1_000) as MergeStep.Ready
        val second = MergeStep.mergeTapped(ready, now = 2_000)
        assertTrue(second is MergeStep.Override)
        assertEquals(MergeStep.Merging(ready.confirm, override = true), MergeStep.overrideTapped(second as MergeStep.Override, now = 3_000))
    }

    @Test fun aBlockedPrDoesntMerge() {
        val ready = MergeStep.looked(confirm(can = false, blocked = "It's a draft: mark it ready for review first."), now = 1_000) as MergeStep.Ready
        assertEquals("It's a draft: mark it ready for review first.", ready.blocked)
        assertEquals(ready, MergeStep.mergeTapped(ready, now = 2_000))
        // `can` false without a reason is still a no.
        val noWhy = MergeStep.looked(confirm(can = false), now = 1_000) as MergeStep.Ready
        assertEquals(noWhy, MergeStep.mergeTapped(noWhy, now = 2_000))
    }

    @Test fun anOldLookIsRenewedBeforeMerging() {
        val ready = MergeStep.looked(confirm(), now = 0) as MergeStep.Ready
        val again = MergeStep.mergeTapped(ready, now = MergeStep.TOKEN_MS + 1)
        assertTrue(again is MergeStep.Looking)
        assertEquals(ready.confirm, (again as MergeStep.Looking).last)
        // Also between the two yeses.
        val o = MergeStep.Override(confirm(needsOverride = "x"), at = 0)
        assertTrue(MergeStep.overrideTapped(o, now = MergeStep.TOKEN_MS + 1) is MergeStep.Looking)
    }

    @Test fun everyFailureSaysWhyAndKeepsWhatWasShown() {
        val merging = MergeStep.Merging(confirm(), override = false)
        fun failed(e: Throwable) = MergeStep.mergeFailed(e, merging) as MergeStep.Failed
        assertEquals("The go-ahead ran out or was used already. Look again, then merge.", failed(TowerApiException(403, "bad_token", "confirm first")).message)
        assertEquals("New commits came since you looked: look again before merging.", failed(TowerApiException(409, "changed", "x")).message)
        assertEquals("GitHub didn't merge it: a review is required", failed(TowerApiException(409, "merge_failed", "a review is required")).message)
        assertEquals("It has conflicts.", failed(TowerApiException(409, "blocked", "It has conflicts.")).message)
        assertTrue(failed(UnreachableException("The tower didn't answer in time.", null)).message.endsWith("Look again to see whether it merged."))
        assertEquals(merging.confirm, failed(TowerApiException(409, "changed", "x")).last)

        val look = MergeStep.lookFailed(TowerApiException(503, "github", "gh: not logged in"), null) as MergeStep.Failed
        assertEquals("The Mac couldn't ask GitHub: gh: not logged in", look.message)
        assertEquals(MergeStep.Merged(open), MergeStep.merged(MergeResult(true, open)))
    }

    @Test fun looksAndBranches() {
        assertEquals("PR #12 · o/app", PrModel.heading(open))
        assertEquals("dev → main", PrModel.branches(open))
        assertEquals("into main", PrModel.branches(open.copy(head = "")))
        assertEquals("Squash and merge", PrModel.method("squash"))
        assertTrue(PrModel.isProd("Main"))
        assertFalse(PrModel.isProd("dev"))
    }
}
