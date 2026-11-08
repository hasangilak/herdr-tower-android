package dev.herdertower.phone.api

import dev.herdertower.phone.ui.ChatModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatApiTest {
    @Test fun readsAPageInAnyWrapping() {
        val one = """{"id":"m2","at":2,"role":"tower","text":"hi"}"""
        val two = """{"id":"m1","at":1,"role":"user","text":"hello","source":"phone"}"""
        for (body in listOf("""{"messages":[$one,$two],"more":true}""", """{"entries":[$one,$two]}""", "[$one,$two]")) {
            val p = ChatApi.page(body)
            assertEquals(listOf("m1", "m2"), p.messages.map { it.id }) // oldest first
        }
        assertEquals(true, ChatApi.page("""{"messages":[],"more":true}""").more)
    }

    @Test fun anEventIsAMessageOrWrapsOne() {
        assertEquals("m3", ChatApi.message("""{"id":"m3","at":3,"role":"tower","text":"x"}""").id)
        assertEquals("m4", ChatApi.message("""{"message":{"id":"m4","at":4,"role":"tower","text":"x"}}""").id)
    }

    @Test fun aReplyBeingWrittenUpdatesInPlace() {
        val t = ChatApi.merge(emptyList(), listOf(ChatMessage("r1", 5, "tower", "Look"), ChatMessage("u1", 4, "user", "q")))
        val t2 = ChatApi.merge(t, listOf(ChatMessage("r1", 5, "tower", "Looking at web now.")))
        assertEquals(listOf("u1", "r1"), t2.map { it.id })
        assertEquals("Looking at web now.", t2.last().text)
    }

    @Test fun screenshotsByIdOrWhole() {
        val m = TowerJson.decodeFromString(ChatMessage.serializer(), """{"id":"r","at":1,"role":"tower","text":"done",
            "screenshots":["a.png",{"id":"b.png","caption":"Login","session":"t5"}]}""")
        val shots = m.shots()
        assertEquals(listOf("a.png", "b.png"), shots.map { it.id })
        assertEquals("/v1/screenshots/b.png", shots[1].url)
        assertEquals("Login", shots[1].caption)
    }

    @Test fun aSentMessageShowsOnceWhenItComesBack() {
        val p = ChatModel.Pending("[Image #1] what's wrong here?", atMs = 10_000, files = 1)
        val thread = listOf(ChatMessage("u9", 10_500, "user", "[voice] [Image #1] what's wrong here?", source = "phone"))
        assertEquals(emptyList<ChatModel.Pending>(), ChatModel.unmatched(listOf(p), thread, now = 11_000))
        assertEquals(listOf(p), ChatModel.unmatched(listOf(p), emptyList(), now = 11_000))
        assertEquals(emptyList<ChatModel.Pending>(), ChatModel.unmatched(listOf(p), emptyList(), now = 200_000)) // gave up waiting
    }

    @Test fun threadMessagesBecomeTheChatsRows() {
        assertEquals("request", ChatModel.entry(ChatMessage("u", 1, "user", "q")).kind)
        assertEquals("answer", ChatModel.entry(ChatMessage("r", 2, "tower", "a")).kind)
        val report = ChatModel.entry(ChatMessage("w", 3, "report", "t1 is done", kind = "watch", session = "t1"))
        assertEquals("watch", report.kind)
        assertEquals("t1", report.session)
        assertTrue(ChatMessage("x", 1, "you", "").mine)
    }

    /** The message exactly as docs/mobile-api.md shows it. */
    @Test fun readsTheTowersMessage() {
        val page = ChatApi.page(
            """{"messages":[{"id":"h812","at":1794158000000,"updated_at":1794158000000,"role":"user","kind":"request",
            "text":"[Image #1] [File #2: notes.pdf] compare these","source":"phone:d_9fce5c6c",
            "attachments":[{"n":1,"kind":"image","name":"1-login.png","path":"/x/attachments/1794-phone/1-login.png","url":"/v1/attachments/1794-phone/1-login.png"},
            {"n":2,"kind":"file","name":"2-notes.pdf","path":"/x/attachments/1794-phone/2-notes.pdf","url":null}],"screenshots":[]},
            {"id":"h813","at":1794158000000,"updated_at":1794158060000,"role":"screenshots","kind":"screenshot","text":"","session":"t5","name":"web",
            "screenshots":[{"id":"s1.png","caption":"Login","session":"t5","name":"web","account":"main","at":1,"url":"/v1/screenshots/s1.png"}]}],"more":true}""",
        )
        assertEquals(true, page.more)
        val (ask, card) = page.messages
        assertTrue(ask.mine)
        assertEquals(listOf("/x/attachments/1794-phone/1-login.png", "/x/attachments/1794-phone/2-notes.pdf"), ask.attachmentPaths())
        assertEquals(ChatModel.entry(ask).attachments, ask.attachmentPaths())
        assertEquals("screenshot", ChatModel.entry(card).kind)
        assertEquals("Login", card.shots().single().caption)
        assertEquals(1794158060000, card.updatedAt)
    }

    @Test fun theThreadIsInTheTowersOrder() {
        val a = ChatMessage("h9", 500, "user", "a")
        val b = ChatMessage("h10", 400, "tower", "b") // later line, earlier clock: still after
        val merged = ChatApi.merge(listOf(b), listOf(a))
        assertEquals(listOf("h9", "h10"), merged.map { it.id })
        val grown = ChatApi.merge(merged, listOf(ChatApi.message("""{"op":"update","message":{"id":"h9","at":500,"role":"user","text":"a2"}}""")))
        assertEquals(listOf("a2", "b"), grown.map { it.text })
    }
}
