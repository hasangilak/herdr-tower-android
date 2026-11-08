package dev.herdertower.phone.api

/** One Server-Sent Event as it came off the wire. */
data class RawEvent(val id: String?, val event: String, val data: String)

/**
 * A line-by-line `text/event-stream` parser (the WHATWG rules the tower
 * needs): `field: value` lines, one optional space after the colon, `data`
 * lines joined with `\n`, an event dispatched on a blank line, comments
 * (`: ping`) reported separately. Lines are fed without their line ending
 * (a trailing `\r` is dropped).
 */
class SseParser(
    private val onEvent: (RawEvent) -> Unit,
    private val onComment: (String) -> Unit = {},
) {
    private var id: String? = null
    private var event: String? = null
    private val data = StringBuilder()
    private var hasData = false

    /** The last `id` seen on this stream. */
    var lastId: String? = null
        private set

    /** Milliseconds the server asked to wait before reconnecting, if it did. */
    var retryMs: Long? = null
        private set

    fun line(raw: String) {
        val line = raw.removeSuffix("\r")
        if (line.isEmpty()) {
            dispatch()
            return
        }
        if (line.startsWith(":")) {
            onComment(line.substring(1).removePrefix(" "))
            return
        }
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        val value = if (colon < 0) "" else line.substring(colon + 1).removePrefix(" ")
        when (field) {
            "event" -> event = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
            "id" -> if ('\u0000' !in value) id = value
            "retry" -> value.toLongOrNull()?.let { retryMs = it }
            else -> {} // ignored, as the spec says
        }
    }

    /** Feeds a chunk of text that may hold several lines (for tests and simple callers). */
    fun feed(text: String) {
        text.split("\n").let { parts ->
            // The text after the last newline is an incomplete line; callers feed whole lines.
            parts.dropLast(if (text.endsWith("\n")) 1 else 0).forEach(::line)
        }
    }

    private fun dispatch() {
        if (id != null) lastId = id
        if (hasData) {
            onEvent(RawEvent(id, event?.takeIf { it.isNotEmpty() } ?: "message", data.toString()))
        }
        id = null
        event = null
        data.setLength(0)
        hasData = false
    }
}
