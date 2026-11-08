package dev.herdertower.phone.api

import dev.herdertower.phone.ui.QuestionModel
import dev.herdertower.phone.ui.TabDraft
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionsTest {
    /** The set as docs/questions.md shows it, with recommendations. */
    private val doc = """
        {"id":"q2bd2c2bc9b6a","session":"t22","name":"Claude in eyzflow","kind":"claude-ask",
         "title":"3 questions: Database, Features, Deploy","detail":"","context":"Set up the new app",
         "tabs":[
          {"title":"Database","question":"Which database should the app use?","options":[{"label":"Postgres","description":"…","key":"1"},{"label":"SQLite","description":"one file","key":"2"}],"multi":false,"allows_other":true,"answered":false},
          {"title":"Features","question":"Which features?","options":[{"label":"Auth","key":"1"},{"label":"Search","key":"2"},{"label":"Billing","key":"3"}],"multi":true,"allows_other":true,"answered":false},
          {"title":"Deploy","question":"Where to deploy?","options":[{"label":"Fly","key":"1"},{"label":"Render","key":"2"}],"multi":false,"allows_other":true,"answered":false}],
         "current_tab":0,"submit_state":"answering",
         "recommendations":[{"tab":0,"options":[{"label":"SQLite","why":"one file, enough for now","choice":1},{"label":"Postgres","why":"grows","choice":0},{"label":"Other","why":"x","choice":{"other":"DuckDB"}}]},
                            {"tab":1,"options":[{"label":"Auth and search","why":"core","choice":{"options":[0,1]}}]}],
         "decision":"escalate","reason":"a product decision","status":"open","error":null,"exact":true,"at_ms":1794171202515}
    """.trimIndent()

    private val q = TowerJson.decodeFromString(Question.serializer(), doc)

    @Test fun readsTheSet() {
        assertEquals("Claude in eyzflow", q.who)
        assertEquals(3, q.tabs.size)
        assertTrue(q.tabs[1].multi)
        assertEquals(Choice.Pick(1), q.recommendationsFor(0)[0].parsed)
        assertEquals(Choice.Several(listOf(0, 1)), q.recommendationsFor(1)[0].parsed)
        assertEquals(Choice.Own("DuckDB"), q.recommendationsFor(0)[2].parsed)
        assertTrue(q.recommendationsFor(2).isEmpty())
        assertTrue(q.forUser)
        assertFalse(q.copy(decision = "auto").forUser)
        assertEquals(4, QuestionModel.steps(q))
    }

    @Test fun recommendationsStillComing() {
        val thinking = TowerJson.decodeFromString(Question.serializer(), """{"id":"q1","tabs":[{"question":"Proceed?","options":[{"label":"Yes"},{"label":"No"}]}],"recommendations":null}""")
        assertNull(thinking.recommendations)
        assertTrue(thinking.forUser) // shown at once, never blocked on the brain
        assertEquals(1, QuestionModel.steps(thinking))
    }

    @Test fun choicesAsTheTowerTakesThem() {
        val body = QuestionsApi.body(
            listOf(
                TabAnswer(1, Choice.Several(listOf(2, 0), "Exports, as CSV")),
                TabAnswer(0, Choice.Pick(1)),
                TabAnswer(2, Choice.Own("Railway, with a staging app\nand preview deploys")),
            ),
        )
        assertEquals(
            """{"answers":[{"tab":0,"choice":1},{"tab":1,"choice":{"options":[0,2],"other":"Exports, as CSV"}},{"tab":2,"choice":{"other":"Railway, with a staging app\nand preview deploys"}}],"submit":true}""",
            body,
        )
        assertEquals("""{"spoken":"the second one"}""", Choice.Spoken("the second one").toJson().toString())
    }

    @Test fun recommendationOneIsTheDefaultPickUntilTheUserChanges() {
        var d = QuestionModel.withDefaults(q, emptyMap())
        assertEquals(setOf(1), d[0]!!.picks) // SQLite
        assertEquals(setOf(0, 1), d[1]!!.picks)
        assertEquals(TabDraft(), d[2]) // no recommendation yet
        assertEquals(listOf(2), QuestionModel.missing(q, d))
        assertNull(QuestionModel.answers(q, d))
        // The user picks Postgres; a later update keeps it.
        d = d + (0 to QuestionModel.toggle(q.tabs[0], d[0]!!, 0))
        d = d + (2 to QuestionModel.type(q.tabs[2], d[2]!!, "Fly, two regions"))
        d = QuestionModel.withDefaults(q, d)
        assertEquals(setOf(0), d[0]!!.picks)
        val a = QuestionModel.answers(q, d)!!
        assertEquals(listOf(Choice.Pick(0), Choice.Several(listOf(0, 1)), Choice.Own("Fly, two regions")), a.map { it.choice })
    }

    @Test fun singleAndMultiChoiceTaps() {
        val single = q.tabs[0]
        var d = QuestionModel.toggle(single, TabDraft(other = "mine"), 1)
        assertEquals(setOf(1), d.picks)
        assertEquals("", d.other)
        d = QuestionModel.type(single, d, "DuckDB")
        assertTrue(d.picks.isEmpty())
        assertEquals(Choice.Own("DuckDB"), QuestionModel.choice(single, d))

        val multi = q.tabs[1]
        var m = QuestionModel.toggle(multi, TabDraft(), 0)
        m = QuestionModel.toggle(multi, m, 2)
        m = QuestionModel.toggle(multi, m, 0)
        m = QuestionModel.type(multi, m, "exports")
        assertEquals(Choice.Several(listOf(2), "exports"), QuestionModel.choice(multi, m))
        assertTrue(QuestionModel.matches(multi, TabDraft(setOf(0, 1)), q.recommendationsFor(1)[0]))
    }

    @Test fun filesGoWithTypedWordsOnly() {
        val f = FilePart("sketch.png", "image/png", byteArrayOf(1))
        val d = mapOf(0 to TabDraft(setOf(1), files = listOf(f)), 1 to TabDraft(setOf(0)), 2 to TabDraft(other = "see sketch", files = listOf(f)))
        val a = QuestionModel.answers(q, d)!!
        assertTrue(a[0].files.isEmpty())
        assertEquals(1, a[2].files.size)
    }

    @Test fun eventsOpenUpdateAndClose() {
        var open = QuestionsApi.apply(emptyList(), QuestionsApi.event("""{"op":"open","id":"q2bd2c2bc9b6a","question":$doc}"""))
        assertEquals(1, open.size)
        open = QuestionsApi.apply(open, QuestionEvent("update", q.id, q.copy(status = "answering")))
        assertEquals("answering", open.single().status)
        open = QuestionsApi.apply(open, QuestionsApi.event("""{"op":"closed","id":"q2bd2c2bc9b6a"}"""))
        assertTrue(open.isEmpty())
        val ev = TowerEvent.decode(RawEvent(null, "question", """{"op":"closed","id":"x"}"""))
        assertTrue(ev is TowerEvent.QuestionChanged)
    }

    @Test fun inWords() {
        assertEquals("SQLite", Choice.Pick(1).words(q.tabs[0]))
        assertEquals("Auth, Billing, “exports”", Choice.Several(listOf(2, 0), "exports").words(q.tabs[1]))
        assertEquals(JsonPrimitive(3), Choice.Pick(3).toJson())
    }
}
