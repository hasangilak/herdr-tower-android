package dev.herdertower.phone.api

import dev.herdertower.phone.ui.QuestionModel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the tower may send that isn't quite as expected: read without throwing. */
class RobustJsonTest {
    @Test fun aSetWithoutQuestionsHasNothingToSend() {
        val q = TowerJson.decodeFromString(Question.serializer(), """{"id":"q1","session":"t1","kind":"text","title":"Continue?"}""")
        assertEquals(0, q.tabs.size)
        assertEquals("Continue?", q.headline)
        assertEquals(1, QuestionModel.steps(q))
        assertEquals(0, QuestionModel.clampStep(q, 3))
        assertEquals(emptyList<Int>(), QuestionModel.missing(q, emptyMap()))
        assertNull(QuestionModel.answers(q, QuestionModel.withDefaults(q, emptyMap())))
    }

    @Test fun theStepStaysInTheSetWhenItShrinks() {
        val three = Question(tabs = List(3) { QuestionTab(question = "q$it") })
        assertEquals(3, QuestionModel.clampStep(three, 3)) // the review
        assertEquals(3, QuestionModel.clampStep(three, 9))
        val one = three.copy(tabs = three.tabs.take(1))
        assertEquals(0, QuestionModel.clampStep(one, 3))
        assertEquals(0, QuestionModel.clampStep(one, -1))
    }

    @Test fun oddOptionsInAChoiceAreSkipped() {
        val e = buildJsonObject {
            put("options", JsonArray(listOf(JsonPrimitive(1), buildJsonObject { put("i", 0) }, JsonArray(listOf(JsonPrimitive(2))), JsonNull, JsonPrimitive("x"))))
        }
        assertEquals(Choice.Several(listOf(1)), Choice.parse(e))
        val r = TowerJson.decodeFromString(Recommendation.serializer(), """{"label":"x","choice":{"options":[{"index":0}]}}""")
        assertEquals(Choice.Several(emptyList()), r.parsed)
        assertNull(Choice.parse(JsonNull))
    }

    @Test fun aNullPathIsNoPathAndKeepsItsPlace() {
        val m = TowerJson.decodeFromString(
            ChatMessage.serializer(),
            """{"id":"h1","attachments":[{"n":1,"path":null,"url":"/v1/attachments/a/b.png"},null,{"n":3,"path":"/x/c.png"},{"n":4,"path":null,"url":null}]}""",
        )
        assertEquals(listOf("/v1/attachments/a/b.png", "", "/x/c.png", ""), m.attachmentPaths())
    }

    @Test fun aNullScreenshotIsLeftOut() {
        val m = TowerJson.decodeFromString(ChatMessage.serializer(), """{"id":"h1","at":5,"screenshots":["s1",null,""]}""")
        assertEquals(listOf("s1"), m.shots().map { it.id })
    }
}
