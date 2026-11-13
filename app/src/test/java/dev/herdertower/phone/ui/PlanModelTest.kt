package dev.herdertower.phone.ui

import dev.herdertower.phone.api.Plan
import dev.herdertower.phone.api.PlanDraft
import dev.herdertower.phone.api.PrScore
import dev.herdertower.phone.api.PredictedPr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanModelTest {
    private val migration = PredictedPr(
        n = 1, repo = "eyzflow", base = "dev", title = "Drafts table", migration = true,
        files = listOf("db/migrations/0042.sql", "src/a.ts", "src/b.ts", "src/c.ts", "src/d.ts"),
        score = PrScore(needsReview = true, line = "Needs review: a database migration (db/migrations/0042.sql)"),
    )
    private val ui = PredictedPr(
        n = 2, repo = "admin", base = "dev", title = "Editor", files = listOf("src/Editor.tsx"), after = listOf(1),
        score = PrScore(needsReview = false, line = "Low risk: no migration, breaking change, secrets, infrastructure or auth"),
    )
    private val ready = Plan(id = "p3", status = "ready", planner = "idle", url = "https://claude.ai/artifact/abc", draft = PlanDraft(space = "live-editor"), prs = listOf(migration, ui))

    @Test fun howThePlanStands() {
        assertEquals("live-editor", PlanModel.name(ready))
        assertEquals("New plan", PlanModel.name(ready.copy(draft = null)))
        assertEquals("page", PlanModel.form(ready))
        assertEquals("whiteboard", PlanModel.form(ready.copy(form = "whiteboard")))

        assertEquals(PlanModel.Status("Ready: a page on your claude.ai account (private).", PlanModel.Tone.Ready, "ready"), PlanModel.status(ready))
        val writing = Plan(status = "writing", planner = "working")
        assertEquals(PlanModel.Tone.Busy, PlanModel.status(writing).tone)
        assertEquals("The planner is writing the plan. It reads the repos first: about five minutes.", PlanModel.status(writing).line)
        assertEquals("The tower's brain is starting the planner…", PlanModel.status(writing.copy(planner = "starting")).line)
        val blocked = PlanModel.status(writing.copy(planner = "blocked", blocked = true))
        assertEquals(PlanModel.Tone.Waits, blocked.tone)
        assertTrue(blocked.line.startsWith("The planner waits on a question in its terminal on the Mac"))
        // Failed: why, said once with one full stop.
        val failed = Plan(status = "failed", planner = "gone", why = "the planner ended before it published the plan.")
        assertEquals("The plan didn't come: the planner ended before it published the plan.", PlanModel.status(failed).line)
        assertEquals(PlanModel.Tone.Failed, PlanModel.status(failed).tone)
        assertEquals("The plan didn't come.", PlanModel.status(failed.copy(why = "")).line)
        assertEquals(emptyList<String>(), PlanModel.notes(failed))
        // Ready, with a question waiting, the planner gone, and revised.
        assertEquals(
            listOf("The planner waits on a question in its terminal on the Mac. Answer it there.", "The planner ended.", "The planner changed the draft 2 times."),
            PlanModel.notes(ready.copy(blocked = true, why = "The planner ended.", revision = 2)),
        )
        assertEquals(listOf("The planner changed the draft once."), PlanModel.notes(ready.copy(revision = 1)))

        // The spaces list's line: with its PRs only when the tower scores them.
        assertEquals("Ready to read · 2 pull requests, 1 needs review", PlanModel.rowLine(ready, scores = true))
        assertEquals("Ready to read", PlanModel.rowLine(ready, scores = false))
        assertEquals("The planner is writing it", PlanModel.rowLine(writing, scores = true))
    }

    @Test fun thePredictedPrs() {
        assertEquals("2 pull requests, 1 needs review", PlanModel.prsHead(listOf(migration, ui)))
        assertEquals("1 pull request, all low risk", PlanModel.prsHead(listOf(ui)))
        assertEquals("1 pull request, needs review", PlanModel.prsHead(listOf(migration)))
        assertEquals("2 pull requests, all need review", PlanModel.prsHead(listOf(migration, migration.copy(n = 2))))
        assertEquals("3 pull requests, 2 need review", PlanModel.prsHead(listOf(migration, migration.copy(n = 2), ui)))

        assertEquals("PR 1 · eyzflow → dev", PlanModel.head(migration))
        assertEquals("db/migrations/0042.sql, src/a.ts, src/b.ts and 2 more", PlanModel.files(migration))
        assertEquals("src/Editor.tsx", PlanModel.files(ui))
        assertNull(PlanModel.files(ui.copy(files = emptyList())))

        assertEquals("Needs review" to true, PlanModel.badge(migration.score))
        assertEquals("Low risk" to false, PlanModel.badge(ui.score))
        assertNull(PlanModel.badge(null))
        assertEquals("a database migration (db/migrations/0042.sql)", PlanModel.reason(migration.score))
        assertEquals("no migration, breaking change, secrets, infrastructure or auth", PlanModel.reason(ui.score))
        assertEquals("odd line", PlanModel.reason(PrScore(line = "odd line")))
        assertNull(PlanModel.reason(PrScore(line = "")))

        assertEquals(emptyList<String>(), PlanModel.prNotes(migration))
        assertEquals(listOf("After PR 1"), PlanModel.prNotes(ui))
        assertEquals(listOf("After PR 1, PR 3", "Opened: o/admin#88"), PlanModel.prNotes(ui.copy(after = listOf(1, 3), pr = "o/admin#88")))
    }

    @Test fun whoMergesWhich() {
        val on = "The tower merges the low-risk ones into dev itself, once their checks pass and their reviewer finds nothing. Those that need review, and any merge into main or prod, come to you."
        val off = "Each one comes to you to merge: auto-merge of low-risk PRs into dev is off (Settings on the Mac)."
        val yours = "Each one comes to you to merge."
        assertEquals(on, PlanModel.mergeNote(listOf(migration, ui), autoMerge = true))
        assertEquals(off, PlanModel.mergeNote(listOf(migration, ui), autoMerge = false))
        // Only a migration, or low-risk PRs into main: each is the user's, auto-merge or not.
        assertEquals(yours, PlanModel.mergeNote(listOf(migration), autoMerge = true))
        assertEquals(yours, PlanModel.mergeNote(listOf(ui.copy(base = "main")), autoMerge = true))
        assertEquals(on, PlanModel.mergeNote(listOf(ui.copy(base = "Develop")), autoMerge = true))
        assertTrue(PlanModel.isDev(" development "))
    }

    @Test fun whoOutsideDependsOnAPredictedPr() {
        val breaks = dev.herdertower.phone.api.PlanDependent("the response field page.body", "storefront-web src/pages/render.ts renderPage", breaks = true)
        val fine = dev.herdertower.phone.api.PlanDependent("the LivePanel component", "eyzflow-admin-dashboard src/pages/Settings.tsx SettingsPage")
        assertEquals("the response field page.body, used by storefront-web src/pages/render.ts renderPage", PlanModel.dependent(breaks))
        assertEquals("Depends on it, still works: the LivePanel component, used by eyzflow-admin-dashboard src/pages/Settings.tsx SettingsPage", PlanModel.stillWorks(fine))
        assertEquals("Nothing outside depends on it: rg over ~/Work for publishDraft: no use outside eyzflow",
            PlanModel.nothingOutside(ui.copy(outside = " rg over ~/Work for publishDraft: no use outside eyzflow ")))
        // With dependents, or with no word from the planner, no such line.
        assertNull(PlanModel.nothingOutside(ui.copy(outside = "rg over ~/Work for LivePanel", dependents = listOf(fine))))
        assertNull(PlanModel.nothingOutside(ui))
    }
}
