package dev.herdertower.phone.api

import org.junit.Assert.assertEquals
import org.junit.Test

class SseParserTest {
    private fun parse(text: String): Pair<List<RawEvent>, List<String>> {
        val events = mutableListOf<RawEvent>()
        val comments = mutableListOf<String>()
        val p = SseParser({ events += it }, { comments += it })
        p.feed(text)
        return events to comments
    }

    @Test fun parsesTheTowersFraming() {
        // As the tower writes it: `sse_event(3, "ping", {})` == "id: 3\nevent: ping\ndata: {}\n\n"
        val (events, _) = parse("id: 1\nevent: hello\ndata: {\"api\":1,\"device\":\"d_1\",\"voice\":true}\n\nid: 2\nevent: state\ndata: {}\n\n")
        assertEquals(listOf(RawEvent("1", "hello", "{\"api\":1,\"device\":\"d_1\",\"voice\":true}"), RawEvent("2", "state", "{}")), events)
    }

    @Test fun commentsArePingsNotEvents() {
        val (events, comments) = parse(": ping\n\nid: 5\nevent: hush\ndata: {}\n\n: ping\n")
        assertEquals(listOf("ping", "ping"), comments)
        assertEquals(listOf(RawEvent("5", "hush", "{}")), events)
    }

    @Test fun joinsDataLinesAndHandlesCrlfAndNoSpace() {
        val (events, _) = parse("event:x\r\ndata:a\r\ndata: b\r\n\r\n")
        assertEquals(listOf(RawEvent(null, "x", "a\nb")), events)
    }

    @Test fun anEventWithoutDataIsNotDispatchedAndDefaultsToMessage() {
        val (events, _) = parse("event: nothing\n\ndata: plain\n\n")
        assertEquals(listOf(RawEvent(null, "message", "plain")), events)
    }

    @Test fun unknownFieldsAreIgnoredAndRetryIsKept() {
        val events = mutableListOf<RawEvent>()
        val p = SseParser({ events += it })
        p.feed("foo: bar\nretry: 3000\nid: 9\nevent: e\ndata: d\n\n")
        assertEquals(listOf(RawEvent("9", "e", "d")), events)
        assertEquals(3000L, p.retryMs)
        assertEquals("9", p.lastId)
    }

    @Test fun anIncompleteEventAtTheEndIsNotDispatched() {
        val (events, _) = parse("event: state\ndata: {}\n")
        assertEquals(emptyList<RawEvent>(), events)
    }
}
