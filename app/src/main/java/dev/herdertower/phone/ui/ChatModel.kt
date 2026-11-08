package dev.herdertower.phone.ui

import dev.herdertower.phone.api.HistoryEntry

/** What the chat shows, as the Mac panel's `chat_items` decides it. */
object ChatModel {
    /** Requests, answers, reports, alerts and summaries; finished or blocked jobs with words; never news held for an unfocused space. */
    fun items(entries: List<HistoryEntry>, max: Int = 300): List<HistoryEntry> = entries.filter { e ->
        !e.held && when (e.kind) {
            "request", "answer", "watch", "alert", "summary", "screenshot" -> true
            "job" -> e.status in setOf("done", "failed", "error", "exited", "blocked") && e.text.isNotEmpty()
            else -> false
        }
    }.takeLast(max)

    /** A session's report without the instructions the tower gave the brain with it (the panel's `report`). */
    fun report(text: String): String =
        listOf(" Read its screen and tell me what happened, briefly.", " Tell me briefly.", " Ask me before you answer it.")
            .fold(text) { t, cut -> t.replace(cut, "") }

    /** A screenshot the tower's own message points at: one by id, or a session's latest. */
    sealed interface ShotRef {
        data class One(val id: String) : ShotRef
        data class OfSession(val session: String) : ShotRef
    }

    private val SHOT = Regex("""\[shots?:([^\]\s]+)]""")

    /** The text without `[shot:<id>]` / `[shots:<session>]` markers (never shown or spoken), and what they point at. */
    fun shots(text: String): Pair<String, List<ShotRef>> {
        val refs = SHOT.findAll(text).map { m ->
            if (m.value.startsWith("[shots:")) ShotRef.OfSession(m.groupValues[1]) else ShotRef.One(m.groupValues[1])
        }.toList()
        if (refs.isEmpty()) return text to refs
        val clean = SHOT.replace(text, "").replace(Regex("""[ \t]{2,}"""), " ").replace(Regex(""" +\n"""), "\n").trim()
        return clean to refs
    }

    /** A reply as one plain line for a preview: no shot markers, no markdown marks. */
    fun preview(text: String): String = shots(text).first
        .replace(Regex("""(\*\*|__|`)"""), "")
        .replace(Regex("""(?m)^\s*([-*•+]|\d{1,3}[.)])\s+"""), "")
        .replace(Regex("""\[([^\]]+)]\(([^)]+)\)"""), "$1")
        .replace(Regex("""\s*\n\s*\n\s*"""), " · ")
        .replace(Regex("""\s*\n\s*"""), " ")
        .trim()

    /** `[Image #N]` markers in a sent message. */
    fun imageMarkers(text: String): List<Int> = Regex("""\[Image #(\d+)]""").findAll(text).map { it.groupValues[1].toInt() }.toList()

    /** What the working line says while the brain thinks: the panel's words, turning over every 3 s. */
    fun thinkingWord(elapsedMs: Long): String = listOf("Thinking", "Considering", "Working it out")[((elapsedMs / 3000) % 3).toInt()]

    /**
     * The working line's words for the brain's [work], as the panel's
     * `working_line`: the step ("Delegating", "to orb-rust"), or the turning
     * thinking words when it's plain thinking or a step finished over 3 s ago.
     */
    fun workLine(work: dev.herdertower.phone.api.Work, now: Long): Pair<String, String> {
        val elapsed = (now - work.sinceMs).coerceAtLeast(0)
        val thinking = (work.word == "Thinking" && work.detail.isEmpty()) || (work.doneMs > 0 && now - work.doneMs > 3000)
        return if (thinking || work.word.isBlank()) thinkingWord(elapsed) to "" else work.word to work.detail
    }

    /** Whether the working line shows: a turn under way, not answered yet, under half an hour old (as the panel). */
    fun showWork(work: dev.herdertower.phone.api.Work?, entries: List<HistoryEntry>, now: Long): Boolean =
        work != null && now - work.sinceMs < 30 * 60 * 1000 && entries.asReversed().take(20).none { it.kind == "answer" && it.at >= work.sinceMs }

    /** Pictures are shown; videos and other files by name. */
    fun isPicture(path: String) = path.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif")
}
