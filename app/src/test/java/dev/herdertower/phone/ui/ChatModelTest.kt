package dev.herdertower.phone.ui

import dev.herdertower.phone.api.HistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatModelTest {
    @Test fun theChatShowsWhatThePanelShows() {
        val e = listOf(
            HistoryEntry(1, "request", text = "hi"),
            HistoryEntry(2, "answer", text = "hello"),
            HistoryEntry(3, "job", status = "working", text = "build it"),
            HistoryEntry(4, "job", status = "done", text = ""),
            HistoryEntry(5, "job", status = "done", text = "built"),
            HistoryEntry(6, "watch", text = "t3 is done", held = true),
            HistoryEntry(7, "summary", text = "api: migrated"),
            HistoryEntry(8, "note", text = "x"),
            HistoryEntry(9, "screenshot", id = "a.png", caption = "Login"),
        )
        assertEquals(listOf(1L, 2, 5, 7, 9), ChatModel.items(e).map { it.at })
    }

    @Test fun reportsLoseTheBrainsInstructions() {
        assertEquals("t1 is now done. You had asked it: tests.", ChatModel.report("t1 is now done. You had asked it: tests. Read its screen and tell me what happened, briefly."))
    }

    @Test fun shotMarkersBecomeImagesAndLeaveTheText() {
        val (text, refs) = ChatModel.shots("Here is the login page [shot:1794160000000-login.png] and the rest:\n[shots:t22]")
        assertEquals("Here is the login page and the rest:", text)
        assertEquals(listOf(ChatModel.ShotRef.One("1794160000000-login.png"), ChatModel.ShotRef.OfSession("t22")), refs)
        assertEquals("plain" to emptyList<ChatModel.ShotRef>(), ChatModel.shots("plain"))
    }

    @Test fun theWorkingWordsTurnOver() {
        assertEquals("Thinking", ChatModel.thinkingWord(0))
        assertEquals("Considering", ChatModel.thinkingWord(3500))
        assertEquals("Working it out", ChatModel.thinkingWord(6100))
    }
}
