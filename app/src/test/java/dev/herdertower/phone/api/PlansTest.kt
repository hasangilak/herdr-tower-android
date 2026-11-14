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

    /** Planning v2: stages, questions, assumptions, and a live plan's revisions, in the Mac's words. */
    @Test fun aPlanSaysItsStagesQuestionsAndRevisions() {
        val json = TowerJson.parseToJsonElement(
            """{"id":"p4","status":"ready","version":1,"open_questions":2,"progress":{"stages":4,"done":1,"at":2},""" +
                """"stages":[{"id":"s1","title":"Spike","kind":"spike","done_when":"it saves","post":[{"id":"c1","what":"w","how":"h","evidence":"e","status":"passed","proof":"200 OK"}],"state":"done"},""" +
                """{"id":"s2","title":"Build","kind":"build","done_when":"editors save","after":["s1"],"prs":[1,2],"pre":[{"id":"c1","what":"w","how":"h","evidence":"the config line","status":"planned"}],""" +
                """"post":[{"id":"c2","what":"w","how":"h","evidence":"e","status":"failed","proof":"still sent a PATCH"},{"id":"c3","what":"w","how":"h","evidence":"e","status":"not_run"}],"state":"failing"}],""" +
                """"questions":[{"id":"q1","text":"Who?","theirs":true,"status":"answered","answer":"Only leads","by":"you"},{"id":"q2","text":"Flag?","status":"answered","answer":"EDITOR_SAVE","by":"tower"},""" +
                """{"id":"q3","text":"When?","status":"asked","card":"d1"},{"id":"q4","text":"History?","status":"with-tower"}],""" +
                """"assumptions":[{"id":"a1","text":"It takes writes","check":"a test write","by":"s1","status":"holds"},{"id":"a2","text":"No migration","check":"read the schema","status":"open"}],""" +
                """"findings":[{"id":"f1","text":"split","revision":2},{"id":"f3","text":"can't run"}],""" +
                """"revisions":[{"n":2,"why":"PR 2 split in two.","findings":["f1"],"changes":["a"],"size":"small","status":"applied"},{"n":3,"why":"signed payloads","findings":["f2"],"size":"big","status":"proposed"}]}""",
        )
        val p = PlansApi.parse(json)!!
        assertEquals("Stage 2 of 4 · 1 done", PlansApi.stagesHead(p))
        assertEquals("All 4 done", PlansApi.stagesHead(p.copy(progress = PlanProgress(4, 4, null))))
        assertEquals("4 stages", PlansApi.stagesHead(p.copy(progress = PlanProgress(4, 0, 1))))
        assertEquals("Done" to false, PlansApi.stageWord("done"))
        assertEquals("Failing" to true, PlansApi.stageWord(p.stages[1].state))
        assertEquals("Pre-checks first" to false, PlansApi.stageWord("ready"))
        assertEquals("Delivers PR 1, PR 2 · after s1", PlansApi.stageLine(p.stages[1]))
        assertEquals(listOf("○", "✗", "–"), (p.stages[1].pre + p.stages[1].post).map { PlansApi.checkMark(it).first })
        assertEquals("Evidence: the config line", PlansApi.checkResult(p.stages[1].pre[0]))
        assertEquals("passed: 200 OK", PlansApi.checkResult(p.stages[0].post[0]))
        assertEquals(
            listOf(
                "Answered by you: Only leads", "Answered by the tower: EDITOR_SAVE",
                "In your queue: answer it there, on the Mac or the phone.", "With the tower: it answers what is routine, and asks you the rest.",
            ),
            p.questions.map { PlansApi.questionLine(it) },
        )
        assertEquals("2 open of 4", PlansApi.questionsHead(p))
        assertEquals("2 questions of the plan are open: Launch waits for their answers, so no plan goes out with questions on its page.", PlansApi.launchWaits(p))
        assertEquals(null, PlansApi.launchWaits(p.copy(openQuestions = 0)))
        assertEquals("Checked by s1: a test write", PlansApi.assumptionLine(p.assumptions[0]))
        assertEquals("Checked: read the schema", PlansApi.assumptionLine(p.assumptions[1]))
        assertEquals("Plan · version 1 · Stage 2 of 4 · 1 done", PlansApi.liveHead(p))
        assertEquals("Done when: editors save · 0 of 3 checks passed", PlansApi.liveStageLine(p.stages[1]))
        assertEquals("1 finding waits for the plan's revision.", PlansApi.findingsWait(p))
        assertEquals(
            listOf("Revision 3 · waiting for your word: signed payloads (from f2)", "Revision 2 · small, applied by itself: PR 2 split in two (from f1)"),
            PlansApi.lastRevisions(p).map { PlansApi.revisionLine(it) },
        )
        // A space's live plan, by its name in any case.
        val live = PlansApi.live(TowerJson.parseToJsonElement("""[{"id":"p3","status":"live","launched":"ISA"},{"bad":[}]""".replace("[}]", "1}]")))
        assertEquals("p3", PlansApi.liveOf(live, "isa")?.id)
        // A big revision's card shows its diff whole; a plan question's doesn't need to.
        assertTrue(PlansApi.isRevisionCard(Decision(why = "plan", key = "plan:p3:rev:3")))
        assertFalse(PlansApi.isRevisionCard(Decision(why = "plan", key = "plan:p3:q3")))
    }
}
