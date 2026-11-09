package dev.herdertower.phone.data

import dev.herdertower.phone.api.Question
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionNoticesTest {
    @Test fun thePermissionCountsOnlyFromAndroid13() {
        // Android 10 to 12: no POST_NOTIFICATIONS permission exists, and it reads as denied; it mustn't block.
        for (sdk in 29..32) {
            assertTrue("sdk $sdk", QuestionNotices.allowed(sdk, notificationsOn = true, permissionGranted = false))
        }
        // Android 13 and later: it's needed.
        for (sdk in 33..37) {
            assertFalse("sdk $sdk", QuestionNotices.allowed(sdk, notificationsOn = true, permissionGranted = false))
            assertTrue("sdk $sdk", QuestionNotices.allowed(sdk, notificationsOn = true, permissionGranted = true))
        }
        // Notifications turned off for the app: never, on any version.
        for (sdk in 29..37) assertFalse(QuestionNotices.allowed(sdk, notificationsOn = false, permissionGranted = true))
    }

    private class Recorded : QuestionNotices.Out {
        val posted = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        override fun post(q: Question) {
            posted += q.id
        }
        override fun cancel(id: String) {
            cancelled += id
        }
    }

    private fun asks(id: String, status: String = "open", decision: String = "escalate") = Question(id = id, session = "t3", status = status, decision = decision)

    @Test fun anEscalatedQuestionNotifiesOnceInTheBackground() {
        val out = Recorded()
        val notices = QuestionNotices({ true }, out)
        notices.follow(listOf(asks("q1"), asks("q2", decision = "auto")), background = true)
        notices.follow(listOf(asks("q1")), background = true)
        assertEquals("once, and only the one left to the user", listOf("q1"), out.posted)
        // Answered: its notification goes.
        notices.follow(emptyList(), background = true)
        assertEquals(listOf("q1"), out.cancelled)
    }

    @Test fun notWhileOnScreenNorWithoutPermission() {
        val out = Recorded()
        QuestionNotices({ true }, out).follow(listOf(asks("q1")), background = false)
        QuestionNotices({ false }, out).follow(listOf(asks("q2")), background = true)
        assertTrue(out.posted.isEmpty())
    }

    @Test fun forgettingLetsTheSameIdNotifyAgain() {
        val out = Recorded()
        val notices = QuestionNotices({ true }, out)
        notices.follow(listOf(asks("q1")), background = true)
        notices.forget() // paired with another tower, whose question may reuse the id
        notices.follow(listOf(asks("q1")), background = true)
        assertEquals(listOf("q1", "q1"), out.posted)
    }
}
