package dev.herdertower.phone.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** An item of a working session's task list: `pending`, `in_progress` or `completed`. */
@Serializable
data class ProgressItem(val id: String = "", val subject: String = "", val active: String = "", val status: String = "pending")

/**
 * How a working session's turn goes (a tower with `progress`, read from its
 * transcript every 15 s; absent when it isn't working): step N of M of its
 * task list (only when it keeps one), the call at work and since when, when
 * the turn started, its last write, its latest words, a job's own progress.
 * [line] is the tower's line as of its read; [ProgressApi.line] says it again
 * against the clock.
 */
@Serializable
data class SessionProgress(
    val line: String = "",
    val step: Int? = null,
    val steps: Int? = null,
    val done: Int = 0,
    val item: String? = null,
    val items: List<ProgressItem> = emptyList(),
    val tool: String? = null,
    @SerialName("tool_ms") val toolMs: Long? = null,
    @SerialName("turn_ms") val turnMs: Long? = null,
    @SerialName("last_ms") val lastMs: Long = 0,
    val said: String? = null,
    val job: String? = null,
)

/** `GET /v1/progress`: every working session's. */
@Serializable
data class WorkingSession(val session: String = "", val name: String = "", val where: String = "", val progress: SessionProgress? = null)

@Serializable
data class ProgressList(val working: List<WorkingSession> = emptyList())

/** `POST /v1/sessions/{t}/progress`'s body: the question, or none (what is done, what it does now, what is left). */
@Serializable
data class ProgressAsk(val question: String? = null)

/**
 * What a session said when asked aside: [via] `btw` (it answered as a side
 * question while its turn goes on) or `transcript` (read from its
 * transcript, [why] not asked aside).
 */
@Serializable
data class ProgressAnswer(
    val session: String = "",
    val name: String = "",
    val status: String = "",
    val asked: String = "",
    val via: String = "",
    val answer: String = "",
    val why: String? = null,
    val progress: SessionProgress? = null,
)

object ProgressApi {
    /** How long its last write may be old before the line turns amber. */
    const val QUIET_MS = 10 * 60_000L

    /** "45 s", "14 min", "2 h", "1 h 5 min" (the tower's span). */
    fun span(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "$s s"
            s < 3600 -> "${s / 60} min"
            s % 3600 < 60 -> "${s / 3600} h"
            else -> "${s / 3600} h ${(s % 3600) / 60} min"
        }
    }

    /** Cut to [n] characters, on a word where it can (the tower's clip). */
    fun clip(text: String, n: Int): String {
        val s = text.trim().split(Regex("\\s+")).joinToString(" ")
        if (s.length <= n) return s
        val cut = s.take(n)
        val at = cut.lastIndexOf(' ')
        val word = if (at > n / 2) cut.take(at) else cut
        return word.trimEnd(',', ';', ':', '.', ' ') + "…"
    }

    private fun lowerFirst(s: String) = s.replaceFirstChar { it.lowercase() }

    /**
     * The line, as the tower says it, against [now]: "step 3 of 7: running the
     * test suite · Bash: Run the tests for 4 min · 14 min in · last update 1
     * min ago", so "in" and "last update" stay true between the tower's reads.
     * Without anything to say it from: the tower's own line.
     */
    fun line(p: SessionProgress, now: Long): String {
        val parts = mutableListOf<String>()
        when {
            p.step != null && p.steps != null && p.item != null -> parts += "step ${p.step} of ${p.steps}: ${lowerFirst(clip(p.item, 70))}"
            p.step == null && p.steps != null && p.done == p.steps -> parts += "all ${p.steps} steps done"
        }
        if (p.tool != null && p.toolMs != null) {
            val ran = (now - p.toolMs).coerceAtLeast(0)
            if (parts.isEmpty()) parts += if (ran >= 60_000) "${p.tool} for ${span(ran)}" else p.tool
            else if (ran >= 60_000) parts += "${p.tool} for ${span(ran)}"
        }
        if (parts.isEmpty() && !p.said.isNullOrBlank()) parts += "\"${clip(p.said, 80)}\""
        p.job?.takeIf { it.isNotBlank() }?.let { parts += "job: ${clip(it, 60)}" }
        p.turnMs?.takeIf { it in 1 until now }?.let { parts += "${span(now - it)} in" }
        if (p.lastMs > 0) {
            val quiet = (now - p.lastMs).coerceAtLeast(0)
            parts += if (quiet < 60_000) "last update just now" else "last update ${span(quiet)} ago"
        }
        // Nothing to say it from at all: the tower's own line.
        return if (parts.isEmpty()) p.line.ifBlank { "working" } else parts.joinToString(" · ")
    }

    /** Its last write is 10 min old or more: the line turns amber. */
    fun quiet(p: SessionProgress, now: Long): Boolean = p.lastMs > 0 && now - p.lastMs >= QUIET_MS

    /** Under an answer read from the transcript: why it wasn't asked aside. */
    fun viaNote(a: ProgressAnswer): String? = when (a.via) {
        "transcript" -> "From its transcript" + (a.why?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
        else -> null
    }
}
