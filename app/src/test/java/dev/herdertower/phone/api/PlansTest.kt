package dev.herdertower.phone.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlansTest {
    private fun state(plan: String, extra: String = "") = TowerJson.decodeFromString(TowerState.serializer(), """{"version": 3, "plan": $plan $extra}""")

    @Test fun theOpenPlanAndItsPredictedPrs() {
        val s = state(
            """{"id": "p3", "form": "page", "status": "ready", "ticket": "SHOP-212 Live editor",
               "draft": {"space": "live-editor", "goal": "Edit the storefront live", "done_when": "editors publish from the preview",
                         "sessions": [{"repo": "eyzflow", "why": "the drafts table"}]},
               "revision": 2, "address": "t40", "planner": "idle", "blocked": false, "answering": false,
               "dir": "/tmp/p3", "plan_md": "/tmp/p3/plan.md", "url": "https://claude.ai/artifact/abc", "why": "",
               "thread": [{"from": "user", "text": "two PRs please", "at": 5}], "created_ms": 1, "ready_ms": 2, "updated_ms": 3, "closed_ms": null,
               "launched": null,
               "prs": [{"n": 1, "repo": "eyzflow", "base": "dev", "title": "Drafts table", "changes": "Adds the table.", "files": ["db/migrations/0042.sql"],
                        "migration": true, "breaking": "", "escalate": [], "after": [],
                        "score": {"needs_review": true, "line": "Needs review: a database migration (db/migrations/0042.sql)", "reasons": ["a database migration (db/migrations/0042.sql)"], "flags": ["migration"], "planned": "p3#1"}},
                       {"n": 2, "repo": "admin", "base": "dev", "title": "Editor", "changes": "", "files": [], "migration": false, "breaking": "", "escalate": [],
                        "after": [1], "score": {"needs_review": false, "line": "Low risk: no migration, breaking change, secrets, infrastructure or auth"}, "pr": "o/admin#88"}]}""",
            """, "auto_merge": true""",
        )
        val p = PlansApi.parse(s.plan)!!
        assertEquals("p3", p.id)
        assertEquals("ready", p.status)
        assertEquals("live-editor", p.draft?.space)
        assertEquals("editors publish from the preview", p.draft?.doneWhen)
        assertEquals(listOf(PlanSession("eyzflow", "the drafts table")), p.draft?.sessions)
        assertEquals("https://claude.ai/artifact/abc", p.url)
        assertEquals(2, p.revision)
        assertEquals(listOf(PlanMsg("user", "two PRs please", 5)), p.thread)
        assertEquals(2L, p.readyMs)
        assertNull(p.closedMs)
        assertEquals(2, p.prs.size)
        assertTrue(p.prs[0].migration)
        assertTrue(p.prs[0].score!!.needsReview)
        assertEquals(listOf("migration"), p.prs[0].score!!.flags)
        assertNull(p.prs[0].pr)
        assertEquals(listOf(1), p.prs[1].after)
        assertEquals("o/admin#88", p.prs[1].pr)
        // Who outside depends on a predicted PR (a tower with impact).
        val deps = PlansApi.parse(TowerJson.parseToJsonElement(
            """{"id": "p4", "prs": [{"n": 1, "dependents": [{"what": "the field total", "used_by": "shop-web src/cart.ts render", "breaks": true},
               {"what": "formatTotal", "used_by": "shop-app lib/a.dart build"}], "outside": ""},
               {"n": 2, "dependents": [], "outside": "rg over ~/Work for publishDraft: nothing"}]}""",
        ))!!
        assertEquals(listOf(PlanDependent("the field total", "shop-web src/cart.ts render", true), PlanDependent("formatTotal", "shop-app lib/a.dart build", false)), deps.prs[0].dependents)
        assertEquals("rg over ~/Work for publishDraft: nothing", deps.prs[1].outside)
        assertEquals(true, s.autoMerge)
    }

    @Test fun noPlanAPlanFromBeforeAndOneTheAppCantRead() {
        // No plan open: null, and the state reads as ever.
        assertNull(PlansApi.parse(state("null").plan))
        assertNull(PlansApi.parse(TowerJson.decodeFromString(TowerState.serializer(), """{"version": 3}""").plan))
        assertNull(TowerJson.decodeFromString(TowerState.serializer(), """{"version": 3}""").autoMerge)
        // A plan from before scores: no prs; a writing one has no url yet.
        val before = PlansApi.parse(state("""{"id": "p1", "status": "writing", "planner": "starting", "url": null, "draft": {}}""").plan)!!
        assertEquals(emptyList<PredictedPr>(), before.prs)
        assertNull(before.url)
        assertNull(before.draft?.space)
        assertFalse(before.blocked)
        // A plan in a shape the app can't read is no plan here; the rest of the state still reads.
        val odd = state("""{"id": "p2", "draft": "not an object", "prs": 7}""", """, "auto_merge": false""")
        assertNull(PlansApi.parse(odd.plan))
        assertEquals(3L, odd.version)
        assertEquals(false, odd.autoMerge)
        assertNull(PlansApi.parse(state("\"p9\"").plan))
    }
}
