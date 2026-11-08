package dev.herdertower.phone.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** One option of a question, as the session's dialog shows it. */
@Serializable
data class QuestionOption(val label: String = "", val description: String = "", val key: String = "")

/** One question of a set (a tab of Claude's dialog, or the one question of a menu). */
@Serializable
data class QuestionTab(
    val title: String = "",
    val question: String = "",
    val options: List<QuestionOption> = emptyList(),
    val multi: Boolean = false,
    @SerialName("allows_other") val allowsOther: Boolean = false,
    val answered: Boolean = false,
)

/** One of the brain's three recommended answers: the first is the default pick. */
@Serializable
data class Recommendation(val label: String = "", val why: String = "", val choice: JsonElement? = null) {
    val parsed: Choice? get() = choice?.let(Choice::parse)
}

@Serializable
data class TabRecommendations(val tab: Int = 0, val options: List<Recommendation> = emptyList())

/**
 * What a blocked session asks, as a question set (docs/questions.md):
 * [kind] `claude-ask`, `claude-plan`, `claude-permission`, `codex-menu` or
 * `text`; [recommendations] null while the brain thinks; [decision] `auto`
 * (the tower answers it) or `escalate` (it's the user's).
 */
@Serializable
data class Question(
    val id: String = "",
    val session: String = "",
    val name: String? = null,
    val kind: String = "",
    val title: String = "",
    val detail: String = "",
    val context: String = "",
    val tabs: List<QuestionTab> = emptyList(),
    @SerialName("current_tab") val currentTab: Int = 0,
    @SerialName("submit_state") val submitState: String = "single",
    val recommendations: List<TabRecommendations>? = null,
    val decision: String? = null,
    val reason: String? = null,
    val status: String = "open",
    val error: String? = null,
    @SerialName("answered_with") val answeredWith: JsonElement? = null,
    @SerialName("at_ms") val atMs: Long = 0,
) {
    /** Who asks: the session's name, else its address. */
    val who: String get() = name?.takeIf { it.isNotBlank() } ?: session

    /** The recommendations for [tab] (empty while the brain thinks). */
    fun recommendationsFor(tab: Int): List<Recommendation> = recommendations?.firstOrNull { it.tab == tab }?.options.orEmpty()

    /** Whether it's the user's to answer: not one the tower answers itself, and not finished. */
    val forUser: Boolean get() = decision != "auto" && status != "answered"

    /** The one line that says what it asks (the first question, or the title). */
    val headline: String get() = tabs.firstOrNull()?.question?.takeIf { it.isNotBlank() } ?: title
}

@Serializable
data class QuestionsResponse(val questions: List<Question> = emptyList())

@Serializable
data class QuestionPolicy(val policy: String = "normal")

/** `{"tab","heard","choice"}` from interpret / voice. */
@Serializable
data class Interpreted(val tab: Int = 0, val heard: String = "", val choice: JsonElement? = null) {
    val parsed: Choice? get() = choice?.let(Choice::parse)
}

/** The `question` event: a set appeared, changed or closed. */
@Serializable
data class QuestionEvent(val op: String = "", val id: String = "", val question: Question? = null)

/**
 * An answer to one question, as the tower takes it: an option ([Pick]),
 * several options and maybe typed words ([Several], multi-choice tabs), only
 * typed words ([Own]), or something said for the tower to interpret ([Spoken]).
 */
sealed interface Choice {
    data class Pick(val index: Int) : Choice
    data class Several(val options: List<Int>, val other: String? = null) : Choice
    data class Own(val text: String) : Choice
    data class Spoken(val text: String) : Choice

    fun toJson(): JsonElement = when (this) {
        is Pick -> JsonPrimitive(index)
        is Several -> buildJsonObject {
            put("options", buildJsonArray { options.sorted().forEach { add(JsonPrimitive(it)) } })
            other?.takeIf { it.isNotBlank() }?.let { put("other", it) }
        }
        is Own -> buildJsonObject { put("other", text) }
        is Spoken -> buildJsonObject { put("spoken", text) }
    }

    /** In words, with the tab's options: "Postgres", "Postgres, Redis + 'exports'", "'my words'". */
    fun words(tab: QuestionTab?): String {
        fun label(i: Int) = tab?.options?.getOrNull(i)?.label ?: "option ${i + 1}"
        return when (this) {
            is Pick -> label(index)
            is Several -> (options.sorted().map(::label) + listOfNotNull(other?.takeIf { it.isNotBlank() }?.let { "“$it”" })).joinToString(", ").ifEmpty { "nothing" }
            is Own -> "“$text”"
            is Spoken -> "“$text”"
        }
    }

    companion object {
        /** Reads a choice as the tower writes it; null for anything else. */
        fun parse(e: JsonElement): Choice? = when (e) {
            is JsonPrimitive -> e.intOrNull?.let(::Pick)
            is JsonObject -> {
                val opts = (e["options"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.intOrNull }
                val other = (e["other"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
                val spoken = (e["spoken"] as? JsonPrimitive)?.contentOrNull
                when {
                    opts != null -> Several(opts, other?.takeIf { it.isNotBlank() })
                    other != null -> Own(other)
                    spoken != null -> Spoken(spoken)
                    else -> null
                }
            }
            else -> null
        }
    }
}

/** One tab's answer to send, with files for its typed words (sent as parts `tab<N>`). */
data class TabAnswer(val tab: Int, val choice: Choice, val files: List<FilePart> = emptyList())

object QuestionsApi {
    /** The JSON body of an answer: `{"answers":[{"tab","choice"}],"submit":true}`. */
    fun body(answers: List<TabAnswer>, submit: Boolean = true): String = buildJsonObject {
        put("answers", buildJsonArray {
            answers.sortedBy { it.tab }.forEach { a ->
                add(buildJsonObject {
                    put("tab", a.tab)
                    put("choice", a.choice.toJson())
                })
            }
        })
        put("submit", submit)
    }.toString()

    /** A question from a `question` event's data: the set itself, or null for `closed`. */
    fun event(data: String): QuestionEvent = TowerJson.decodeFromString(QuestionEvent.serializer(), data)

    /** Applies an event to the open sets. */
    fun apply(open: List<Question>, ev: QuestionEvent): List<Question> = when (ev.op) {
        "closed" -> open.filterNot { it.id == ev.id }
        else -> ev.question?.let { q -> open.filterNot { it.id == q.id } + q }?.sortedBy { it.atMs } ?: open
    }
}
