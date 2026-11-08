package dev.herdertower.phone.ui

import dev.herdertower.phone.api.Choice
import dev.herdertower.phone.api.FilePart
import dev.herdertower.phone.api.Question
import dev.herdertower.phone.api.QuestionTab
import dev.herdertower.phone.api.Recommendation
import dev.herdertower.phone.api.TabAnswer

/**
 * What the user has picked so far for one question: options (one, or
 * several on a multi-choice tab), their own words, files for those words.
 * [touched]: the user changed it (a recommendation that lands later
 * doesn't overwrite it).
 */
data class TabDraft(
    val picks: Set<Int> = emptySet(),
    val other: String = "",
    val files: List<FilePart> = emptyList(),
    val touched: Boolean = false,
)

object QuestionModel {
    /** The answer this draft makes for [tab], or null when there's none yet. */
    fun choice(tab: QuestionTab, d: TabDraft): Choice? {
        val words = d.other.trim()
        return when {
            tab.multi -> if (d.picks.isEmpty() && words.isEmpty()) null else Choice.Several(d.picks.sorted(), words.ifEmpty { null })
            d.picks.isNotEmpty() -> Choice.Pick(d.picks.first())
            words.isNotEmpty() -> Choice.Own(words)
            else -> null
        }
    }

    /** A recommendation (or what was heard) as a draft. */
    fun draftOf(c: Choice?, tab: QuestionTab, touched: Boolean, files: List<FilePart> = emptyList()): TabDraft = when (c) {
        is Choice.Pick -> TabDraft(setOf(c.index), "", emptyList(), touched)
        is Choice.Several -> if (tab.multi) TabDraft(c.options.toSet(), c.other.orEmpty(), files, touched)
        else TabDraft(c.options.take(1).toSet(), if (c.options.isEmpty()) c.other.orEmpty() else "", files, touched)
        is Choice.Own -> TabDraft(emptySet(), c.text, files, touched)
        is Choice.Spoken -> TabDraft(emptySet(), c.text, files, touched)
        null -> TabDraft(touched = touched)
    }

    /** Whether [d] is exactly what recommendation [r] picks (it's shown selected). */
    fun matches(tab: QuestionTab, d: TabDraft, r: Recommendation): Boolean {
        val want = draftOf(r.parsed, tab, false)
        return want.picks == d.picks && want.other.trim() == d.other.trim() && (want.picks.isNotEmpty() || want.other.isNotBlank())
    }

    /** Tapping option [i]: on a single-choice tab it's the answer; on a multi-choice tab it's ticked or unticked. */
    fun toggle(tab: QuestionTab, d: TabDraft, i: Int): TabDraft =
        if (tab.multi) d.copy(picks = if (i in d.picks) d.picks - i else d.picks + i, touched = true)
        else d.copy(picks = setOf(i), other = "", touched = true)

    /** Typing their own words: on a single-choice tab that replaces an option picked. */
    fun type(tab: QuestionTab, d: TabDraft, text: String): TabDraft =
        if (tab.multi) d.copy(other = text, touched = true)
        else d.copy(other = text, picks = if (text.isBlank()) d.picks else emptySet(), touched = true)

    /**
     * The drafts as they should be now: kept where the user touched them,
     * else recommendation 1 (the default pick) once the brain has given it.
     */
    fun withDefaults(q: Question, drafts: Map<Int, TabDraft>): Map<Int, TabDraft> = q.tabs.indices.associateWith { t ->
        val d = drafts[t]
        if (d != null && (d.touched || choice(q.tabs[t], d) != null)) d
        else q.recommendationsFor(t).firstOrNull()?.let { draftOf(it.parsed, q.tabs[t], touched = false) } ?: d ?: TabDraft()
    }

    /** The questions still without an answer. */
    fun missing(q: Question, drafts: Map<Int, TabDraft>): List<Int> = q.tabs.indices.filter { t -> choice(q.tabs[t], drafts[t] ?: TabDraft()) == null }

    /** What to send, or null while a question has no answer. */
    fun answers(q: Question, drafts: Map<Int, TabDraft>): List<TabAnswer>? {
        if (missing(q, drafts).isNotEmpty()) return null
        return q.tabs.indices.map { t ->
            val d = drafts[t] ?: TabDraft()
            TabAnswer(t, choice(q.tabs[t], d)!!, if (d.other.isNotBlank()) d.files else emptyList())
        }
    }

    /** The steps of the stepper: one per question, then the review (a one-question set is reviewed on its own page). */
    fun steps(q: Question): Int = if (q.tabs.size > 1) q.tabs.size + 1 else 1

    /** What kind of thing it is, in a word. */
    fun kindWord(q: Question): String = when (q.kind) {
        "claude-plan" -> "Plan"
        "claude-permission" -> "Permission"
        "codex-menu" -> "Approval"
        "text" -> "Question"
        else -> if (q.tabs.size > 1) "${q.tabs.size} questions" else "Question"
    }

    /** The status, in words, when it's not simply waiting for the user. */
    fun statusWords(q: Question): String? = when (q.status) {
        "answering" -> "Typing your answers into ${q.who}…"
        "queued" -> "Your answer goes in when ${q.who} stops working."
        "failed" -> "Couldn't answer: ${q.error ?: "it stopped"}. Try again, or answer in the terminal."
        "answered" -> "Answered."
        else -> null
    }

    /** The cards to show: the user's questions, oldest first, those for one [session] only when given. */
    fun forUser(all: List<Question>, session: String? = null): List<Question> =
        all.filter { it.forUser && (session == null || it.session == session) }.sortedBy { it.atMs }
}
