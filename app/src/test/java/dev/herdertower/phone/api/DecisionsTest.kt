package dev.herdertower.phone.api

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Decisions as the tower sends them (herder-tower docs/quiet-tower.md, "Step 11"), and the same queue made from an older tower's questions and PRs. */
class DecisionsTest {
    private val body = """{"decisions":[
        {"id":"d1","key":"raised:sandbox:prod-read","kind":"permission","space":"sandbox",
         "sessions":[{"address":"s1","name":"eyzflow"},{"address":"s2","name":"mediaroom"},{"address":"s3","name":""}],"waiting":3,
         "title":"Allow one read of the production database","why":"Production data",
         "options":[{"label":"Allow once","why":"","pick":true},{"label":"No","why":"","pick":false}],
         "prs":[],"question_id":null,"first_ms":1000,"last_ms":2000,"count":2,"state":"open","new_field":{"x":1}},
        {"id":"d2","key":"merge:o/app","kind":"merge","space":"isa","sessions":[],"waiting":0,"title":"2 pull requests ready to merge","why":"Merge",
         "options":[{"label":"Merge both, dev first","pick":true}],
         "prs":[{"repo":"o/app","number":1273,"base":"dev","checks":"pass"},{"repo":"o/app","number":1277,"base":"main","checks":"fail"}],
         "question_id":null,"first_ms":500,"last_ms":500,"count":1,"state":"answering"}
    ]}"""

    /** As `GET /v1/decisions` answered in the walkthrough (docs/mobile-api-quiet-walkthrough.md, section 4). */
    private val walked = """{"space":null,"count":1,"decisions":[{"id":"d702e971f5cd1","key":"raise:fix-login:tabs or a sidebar for the settings","kind":"choice",
        "space":"fix-login","sessions":[{"address":"t3","name":"fix login eyzflow","waiting":true},{"address":"t4","name":"fix login mediaroom","waiting":false}],
        "waiting":1,"title":"Tabs or a sidebar for the settings?","why":"design",
        "options":[{"label":"Tabs","why":"matches the mock","pick":true},{"label":"A sidebar","why":"more room for later sections","pick":false}],
        "pick":"Tabs","prs":[],"question_id":null,"task":null,"first_ms":1794369735747,"last_ms":1794369737382,"count":2,"state":"open","muted":false}]}"""

    @Test fun readsTheWalkthroughsQueue() {
        val r = TowerJson.decodeFromString(DecisionsResponse.serializer(), walked)
        assertNull(r.space)
        assertEquals(1, r.count)
        val d = r.decisions.single()
        assertEquals("Tabs", d.pick)
        assertEquals(listOf(true, false), d.sessions.map { it.waiting })
        assertFalse(d.muted)
        assertTrue(d.open && d.live)
        // A merge's PR as the decision lists it: what the confirm and merge calls need, and its chip's gate.
        val pr = TowerJson.decodeFromString(DecisionPr.serializer(), """{"key":"o/app#12","repo":"o/app","number":12,"base":"dev","title":"Fix it",
            "url":"https://github.com/o/app/pull/12","checks":"fail","note":"1 check failing: lint","session":"t58",
            "merge":{"can":true,"blocked":null,"needs_override":"1 check failing: lint.","warnings":[]}}""")
        assertEquals("o/app#12", pr.key)
        assertEquals("1 check failing: lint", pr.note)
        assertEquals("1 check failing: lint.", pr.merge?.needsOverride)
        // With scores, each of a merge's PRs says whether it needs review.
        val scored = TowerJson.decodeFromString(DecisionPr.serializer(), """{"key":"o/app#12","repo":"o/app","number":12,"base":"dev","score":{"needs_review":false,"line":"Low risk: no migration"}}""")
        assertEquals("Low risk: no migration", scored.score?.line)
        assertTrue(DecisionsApi.failing(Decision(kind = "merge", prs = listOf(pr.copy(checks = "pass")))))
    }

    @Test fun aMutedSpacesDecisionIsLeftOutOfTheMainQueue() {
        val all = listOf(Decision(id = "a"), Decision(id = "b", muted = true), Decision(id = "c", state = "answering"))
        assertEquals(listOf("a", "c"), DecisionsApi.main(all).map { it.id })
        // Over already: the 409 says how, and the card goes without a "couldn't answer".
        val closed = TowerApiException(409, "closed", "d1 is answered already", extra = TowerJson.parseToJsonElement("""{"code":"closed","state":"dismissed"}""") as kotlinx.serialization.json.JsonObject)
        assertEquals("dismissed", DecisionsApi.closedBy(closed))
        assertEquals("answered", DecisionsApi.closedBy(TowerApiException(409, "closed", "over")))
        assertNull(DecisionsApi.closedBy(TowerApiException(409, "stopped", "the typing stopped")))
        assertEquals("It was dismissed already.", DecisionsApi.closedWords("dismissed"))
    }

    @Test fun readsTheQueueAsTheContractGivesIt() {
        val list = TowerJson.decodeFromString(DecisionsResponse.serializer(), body).decisions
        assertEquals(listOf("d1", "d2"), list.map { it.id })
        val d = list[0]
        assertEquals("permission", d.kind)
        assertEquals("sandbox", d.space)
        assertEquals(3, d.waiting)
        assertEquals(listOf("eyzflow", "mediaroom", "s3"), d.sessions.map { it.label })
        assertEquals(listOf(DecisionOption("Allow once", "", true), DecisionOption("No", "", false)), d.options)
        assertNull(d.questionId)
        assertTrue(d.open && d.live && !d.legacy)
        assertEquals(2, d.count)
        val m = list[1]
        assertFalse("being answered: not to tap again", m.open)
        assertTrue(m.live)
        assertEquals("https://github.com/o/app/pull/1277", m.prs[1].url)
        assertEquals(PrRef("o", "app", 1273), m.prs[0].ref)
        assertTrue("a failing check: merging takes a second yes", DecisionsApi.failing(m))
        assertFalse(DecisionsApi.failing(d))
    }

    @Test fun anEventCarriesTheListOrOnlySaysItChanged() {
        assertEquals(2, DecisionsApi.event(body)!!.decisions.size)
        assertEquals(listOf("d9"), DecisionsApi.event("""[{"id":"d9","title":"x"}]""")!!.decisions.map { it.id })
        assertEquals(emptyList<Decision>(), DecisionsApi.event("""{"decisions":[]}""")!!.decisions)
        // The event: every open one, a muted space's marked, and the badge.
        val e = DecisionsApi.event("""{"count":0,"decisions":[{"id":"d702e971f5cd1","state":"open","muted":true}]}""")!!
        assertEquals(0, e.count)
        assertTrue(e.decisions.single().muted)
        // Nothing to read from: the queue is asked for again.
        assertNull(DecisionsApi.event("{}"))
        assertNull(DecisionsApi.event("""{"changed":true}"""))
        assertNull(DecisionsApi.event("not json"))
    }

    @Test fun theAnswerBodies() {
        assertEquals("""{"choice":1}""", DecisionsApi.choiceBody(1))
        assertEquals("""{"other":"only the read, \"once\""}""", DecisionsApi.otherBody("only the read, \"once\""))
    }

    @Test fun whatWaitsAndForHowLong() {
        val now = 100 * 60_000L
        assertEquals("3 sessions wait", DecisionsApi.stake(Decision(waiting = 3)))
        assertEquals("1 session waits", DecisionsApi.stake(Decision(waiting = 1)))
        assertEquals("Nothing is blocked", DecisionsApi.stake(Decision(waiting = 0)))
        assertEquals("2 min", DecisionsApi.waited(Decision(firstMs = now - 2 * 60_000), now))
        assertEquals("just now", DecisionsApi.waited(Decision(firstMs = now - 20_000), now))
        assertEquals("1 h 5 min", DecisionsApi.waited(Decision(firstMs = now - 65 * 60_000), now))
        assertEquals("", DecisionsApi.waited(Decision(firstMs = 0), now))
    }

    @Test fun theQueuesOrderIsSessionsTimesMinutesThenTheOldest() {
        val now = 100 * 60_000L
        fun d(id: String, waiting: Int, mins: Long) = Decision(id = id, waiting = waiting, firstMs = now - mins * 60_000)
        // The design's own three: 3 x 2 = 6, 1 x 4 = 4, 0 x 31 = 0.
        assertEquals(listOf("db", "pick", "pr"), DecisionsApi.order(listOf(d("pr", 0, 31), d("pick", 1, 4), d("db", 3, 2)), now).map { it.id })
        // The same weight: the one that waited longer first.
        assertEquals(listOf("old", "new"), DecisionsApi.order(listOf(d("new", 0, 3), d("old", 0, 9)), now).map { it.id })
    }

    // A tower from before decisions

    private fun option(label: String) = QuestionOption(label)
    private fun rec(label: String, why: String, choice: Int) = Recommendation(label, why, JsonPrimitive(choice))

    private val permission = Question(
        id = "q2", session = "t5", name = "web", kind = "claude-permission", title = "Run a command", detail = "rm -rf build/",
        tabs = listOf(QuestionTab(question = "Do you want to proceed?", options = listOf(option("Yes"), option("Yes, and don't ask again"), option("No")))),
        recommendations = listOf(TabRecommendations(0, listOf(rec("No", "it deletes the build folder", 2), rec("Yes", "it's generated anyway", 0)))),
        decision = "escalate", reason = "deleting files", atMs = 5000,
    )
    private val three = Question(
        id = "q1", session = "t3", name = "api", kind = "claude-ask", title = "3 questions: Database, Features, Deploy",
        tabs = listOf(QuestionTab(question = "Which database?"), QuestionTab(question = "Which features?"), QuestionTab(question = "Deploy?")),
        decision = "escalate", atMs = 4000,
    )

    @Test fun aQuestionLeftToTheUserIsADecision() {
        val d = DecisionsApi.ofQuestion(permission, "Storefront")
        assertEquals("legacy-q:q2", d.id)
        assertTrue(d.legacy)
        assertEquals("permission", d.kind)
        assertEquals("Storefront", d.space)
        assertEquals("Run a command", d.title) // not "Do you want to proceed?"
        assertEquals("rm -rf build/", d.detail)
        assertEquals("Permission", d.why)
        assertEquals(1, d.waiting)
        assertEquals("q2", d.questionId)
        // The tower's recommendations are the answers, its first the pick.
        assertEquals(listOf(DecisionOption("No", "it deletes the build folder", true), DecisionOption("Yes", "it's generated anyway", false)), d.options)
        assertEquals("q2", DecisionsApi.legacyQuestionId(d.id))
        assertNull(DecisionsApi.legacyQuestionId("d-db"))
    }

    @Test fun aSetOfSeveralQuestionsHasNoAnswersToTap() {
        val d = DecisionsApi.ofQuestion(three, "Storefront")
        assertEquals(emptyList<DecisionOption>(), d.options)
        assertEquals("3 questions", d.why)
        assertEquals("3 questions: Database, Features, Deploy", d.title)
    }

    @Test fun beforeTheBrainRecommendsAShortChoiceOffersItsOwnOptions() {
        val d = DecisionsApi.ofQuestion(permission.copy(recommendations = null), "")
        assertEquals(listOf("Yes", "Yes, and don't ask again", "No"), d.options.map { it.label })
        assertTrue(d.options.none { it.pick })
        // A plain text question has nothing to tap until then.
        val text = Question(id = "q3", kind = "text", tabs = listOf(QuestionTab(question = "Open the PR now?", allowsOther = true)), decision = "escalate")
        assertEquals(emptyList<DecisionOption>(), DecisionsApi.ofQuestion(text, "").options)
        assertEquals("Open the PR now?", DecisionsApi.ofQuestion(text, "").title)
    }

    @Test fun anAnswerTappedMeansTheRecommendationOrTheOptionOfThatName() {
        assertEquals(Choice.Pick(2), DecisionsApi.legacyChoice(permission, "No"))
        assertEquals(Choice.Pick(0), DecisionsApi.legacyChoice(permission, "Yes"))
        // Not recommended, but an option of the dialog.
        assertEquals(Choice.Pick(1), DecisionsApi.legacyChoice(permission, "Yes, and don't ask again"))
        assertNull(DecisionsApi.legacyChoice(permission, "Maybe"))
        assertNull("several questions: the stepper answers them", DecisionsApi.legacyChoice(three, "Postgres"))
        val own = permission.copy(recommendations = listOf(TabRecommendations(0, listOf(Recommendation("Own schema", "", buildJsonObject { put("other", "Postgres, own schema") })))))
        assertEquals(Choice.Own("Postgres, own schema"), DecisionsApi.legacyChoice(own, "Own schema"))
    }

    @Test fun theStatesOfAQuestionCarryOver() {
        fun state(status: String) = DecisionsApi.ofQuestion(permission.copy(status = status, error = "the dialog moved on"), "").let { it.state to it.error }
        assertEquals("open" to null, state("open"))
        assertEquals("answering" to null, state("answering"))
        assertEquals("answering" to null, state("queued"))
        // The typing stopped half way: still open (the user answers again), and it says why.
        assertEquals("open" to "the dialog moved on", state("failed"))
    }

    private fun pr(n: Long, base: String, repo: String = "o/app", space: String? = "isa", waiting: Boolean = true, state: String = "open", checks: String = "passing", found: Long = 100) =
        Pr(key = "$repo#$n", repo = repo, number = n, title = "pr $n", base = base, state = state, checks = PrChecks(checks), session = "t9", name = "mediaroom", space = space, waiting = waiting, foundMs = found, checkedMs = found + 1)

    @Test fun theWaitingPrsOfOneRepoInOneSpaceAreOneMergeDevBeforeMain() {
        val d = DecisionsApi.ofPrs("isa", "o/app", listOf(pr(1277, "main", found = 300), pr(1273, "dev", found = 200, checks = "failing")))
        assertEquals("legacy-m:isa|o/app", d.id)
        assertEquals("merge", d.kind)
        assertEquals("2 pull requests ready to merge", d.title)
        assertEquals("Merge", d.why)
        assertEquals(0, d.waiting) // their sessions ended their turn: nothing is blocked
        assertEquals(listOf(Triple(1273L, "dev", "fail"), Triple(1277L, "main", "pass")), d.prs.map { Triple(it.number, it.base, it.checks) })
        // What the merge sheet needs comes along: the PR's key, title and gate.
        assertEquals(listOf("o/app#1273", "o/app#1277"), d.prs.map { it.key })
        assertEquals("pr 1273", d.prs[0].title)
        assertEquals(200L, d.firstMs)
        assertEquals(emptyList<DecisionOption>(), d.options) // each is merged from its own row, through the sheet that looks first
        assertEquals("Merge #7: pr 7", DecisionsApi.ofPrs("isa", "o/app", listOf(pr(7, "dev"))).title)
    }

    @Test fun theQueueOfAnOlderTower() {
        val auto = permission.copy(id = "q8", decision = "auto")
        val undecided = permission.copy(id = "q9", decision = null)
        val answered = permission.copy(id = "q7", status = "answered")
        val prs = listOf(pr(1273, "dev"), pr(1277, "main"), pr(5, "dev", repo = "o/web"), pr(6, "dev", waiting = false), pr(8, "dev", state = "merged"), pr(9, "dev", space = null))
        val now = 10 * 60_000L
        val q = DecisionsApi.fromLegacy(listOf(permission, three, auto, undecided, answered), prs, { if (it == "t9") "Infra" else "Storefront" }, now)
        // Only what the tower leaves to the user; the tower's own answers and what it hasn't decided on stay out.
        assertEquals(setOf("legacy-q:q2", "legacy-q:q1", "legacy-q:q7", "legacy-m:isa|o/app", "legacy-m:isa|o/web", "legacy-m:Infra|o/app"), q.map { it.id }.toSet())
        // One just answered stays until the tower closes it, as answered: it doesn't count as the user's any more.
        assertEquals("answered", q.first { it.id == "legacy-q:q7" }.state)
        assertEquals(5, q.count { it.open })
        val open = q.filter { it.open }
        assertEquals("the questions (a session waits on each) come before the merges", listOf("question", "permission"), open.take(2).map { it.kind })
        assertEquals(listOf("Storefront", "Storefront"), open.take(2).map { it.space })
        assertEquals(2, q.first { it.id == "legacy-m:isa|o/app" }.prs.size)
    }
}
