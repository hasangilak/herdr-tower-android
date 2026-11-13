package dev.herdertower.phone.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A session a decision is about; [waiting]: blocked on it now (false: it works on something else meanwhile). */
@Serializable
data class DecisionSession(
    val address: String = "",
    val name: String = "",
    val waiting: Boolean = true,
    /** A question's or permission's session: its own question set. */
    @SerialName("question_id") val questionId: String? = null,
) {
    val label: String get() = name.ifBlank { address }
}

/** One answer a decision offers; [pick] is the tower's own. */
@Serializable
data class DecisionOption(val label: String = "", val why: String = "", val pick: Boolean = false)

/**
 * A pull request a merge decision lists, in the order to merge them:
 * [checks] is `pass`, `pending`, `fail` or `none`, [note] the checks in
 * words, [merge] what its chip's Merge button would do.
 */
@Serializable
data class DecisionPr(
    val repo: String = "",
    val number: Long = 0,
    val base: String = "",
    val checks: String = "",
    @SerialName("key") val prKey: String = "",
    val title: String = "",
    @SerialName("url") val link: String = "",
    val note: String = "",
    val session: String? = null,
    val merge: PrGate? = null,
    /** Whether it needs a human code review (a tower with `scores`). */
    val score: PrScore? = null,
    /** Its plan's checks as they stand (a tower with `validation`). */
    val validation: PrValidation? = null,
    /** Who outside depends on what it changes (a tower with `impact`). */
    val impact: PrImpact? = null,
) {
    val ref: PrRef? get() = PrRef.of(repo, number)
    val key: String get() = prKey.ifBlank { "$repo#$number" }
    val url: String? get() = link.ifBlank { null } ?: ref?.let { "https://github.com/${it.owner}/${it.repo}/pull/${it.number}" }
}

/**
 * The one thing the tower puts in front of the user: a decision only they
 * can make (herder-tower docs/mobile-api.md, "The Quiet Tower"). [kind] is
 * `permission`, `question`, `merge`, `choice`, `failed` or `raised`; [why]
 * the reason or category; [state] `open`, `answering`, `answered`,
 * `stopped` or `dismissed`; [muted]: its space is muted (in the space's own
 * queue only, never notified).
 */
@Serializable
data class Decision(
    val id: String = "",
    val key: String = "",
    val kind: String = "",
    val space: String = "",
    val sessions: List<DecisionSession> = emptyList(),
    /** How many sessions are blocked on it now; 0: nothing is blocked. */
    val waiting: Int = 0,
    val title: String = "",
    val why: String = "",
    val options: List<DecisionOption> = emptyList(),
    /** The tower's pick: an option's label. */
    val pick: String? = null,
    val prs: List<DecisionPr> = emptyList(),
    /** The question set behind it (`/v1/questions/{id}`): its first session's. */
    @SerialName("question_id") val questionId: String? = null,
    /** A failed decision's agenda task. */
    val task: String? = null,
    @SerialName("first_ms") val firstMs: Long = 0,
    @SerialName("last_ms") val lastMs: Long = 0,
    val count: Int = 1,
    val state: String = "open",
    val muted: Boolean = false,
    /** What it is about in full (a command, a plan), when the title alone doesn't say (from a question set). */
    val detail: String = "",
    /** Why the last answer from this phone didn't go in (never from the tower). */
    @Transient val error: String? = null,
) {
    /** It can be answered now. */
    val open: Boolean get() = state == "open"

    /** It is still in the queue: open, or its answer going in. */
    val live: Boolean get() = state == "open" || state == "answering"

    /** Made here from a question or from waiting PRs, for a tower without the Quiet Tower. */
    val legacy: Boolean get() = id.startsWith(DecisionsApi.LEGACY)
}

/** `GET /v1/decisions` and the `decisions` event: the open ones in queue order; [count] is the badge (outside muted spaces). */
@Serializable
data class DecisionsResponse(
    val space: String? = null,
    val count: Int? = null,
    val decisions: List<Decision> = emptyList(),
    val closed: List<Decision> = emptyList(),
)

/** One session's answer as it went to it: [sent] false when it was busy and gets it when it stops. */
@Serializable
data class DecisionSent(val session: String = "", val name: String = "", val sent: Boolean = true)

/**
 * What an answer did: [state] `answered`, or `answering` while a question's
 * answer is typed in. A `failed` decision says whether it was [requeued] or
 * [abandoned].
 */
@Serializable
data class DecisionAnswered(
    val ok: Boolean = true,
    val id: String = "",
    val state: String = "answered",
    val answer: String? = null,
    val sent: List<DecisionSent> = emptyList(),
    val errors: List<kotlinx.serialization.json.JsonElement> = emptyList(),
    val requeued: kotlinx.serialization.json.JsonElement? = null,
    val abandoned: kotlinx.serialization.json.JsonElement? = null,
    /** Whether the answer can become a standing rule, and the words that would ask for it. */
    val rule: DecisionRule? = null,
)

object DecisionsApi {
    /** The start of an id made here (never one of the tower's). */
    const val LEGACY = "legacy-"
    const val LEGACY_QUESTION = "legacy-q:"
    const val LEGACY_MERGE = "legacy-m:"

    /** `{"choice": 1}`. */
    fun choiceBody(index: Int): String = buildJsonObject { put("choice", index) }.toString()

    /** `{"other": "…"}`. */
    fun otherBody(text: String): String = buildJsonObject { put("other", text) }.toString()

    /**
     * The `decisions` event: `{"count", "decisions":[…]}` (every open one,
     * muted ones marked), or a bare array. Null when it only says that
     * something changed: the list is read again.
     */
    fun event(data: String): DecisionsResponse? = runCatching {
        when (val e = TowerJson.parseToJsonElement(data)) {
            is JsonArray -> DecisionsResponse(decisions = TowerJson.decodeFromJsonElement(ListSerializer(Decision.serializer()), e))
            is JsonObject -> if (e["decisions"] is JsonArray) TowerJson.decodeFromJsonElement(DecisionsResponse.serializer(), e) else null
            else -> null
        }
    }.getOrNull()

    /** The kinds the app answers from a card (blank: made here, or an older shape). Any other is shown plainly and counted, never left out. */
    private val ANSWERED_HERE = setOf("", "permission", "question", "merge", "choice", "failed", "raised")

    /** Whether its answers can be sent from the app: a `blocked` one is answered in the session itself, a kind the app doesn't know isn't answered from here. */
    fun answerable(d: Decision): Boolean = d.kind in ANSWERED_HERE

    /** The main queue: what is shown and counted (a muted space's decisions are in its own view only). */
    fun main(list: List<Decision>): List<Decision> = list.filterNot { it.muted }

    /** "3 sessions wait", "1 session waits", "Nothing is blocked". */
    fun stake(d: Decision): String = when (d.waiting) {
        0 -> "Nothing is blocked"
        1 -> "1 session waits"
        else -> "${d.waiting} sessions wait"
    }

    /** How long it has waited: "2 min", "1 h 5 min"; empty when the tower didn't say since when. */
    fun waited(d: Decision, now: Long): String {
        if (d.firstMs <= 0) return ""
        val min = ((now - d.firstMs) / 60_000).coerceAtLeast(0)
        return when {
            min < 1 -> "just now"
            min < 60 -> "$min min"
            min < 24 * 60 -> "${min / 60} h ${min % 60} min"
            else -> "${min / (24 * 60)} d"
        }
    }

    /** `weight = sessions waiting x minutes waited`, heaviest first, then the oldest: the queue's order. */
    fun order(list: List<Decision>, now: Long): List<Decision> {
        fun minutes(d: Decision) = ((now - d.firstMs) / 60_000).coerceAtLeast(0)
        return list.sortedWith(compareByDescending<Decision> { it.waiting * minutes(it) }.thenBy { if (it.firstMs > 0) it.firstMs else Long.MAX_VALUE })
    }

    /** Whether one of its PRs fails its checks: merging then takes a second, explicit yes. */
    fun failing(d: Decision): Boolean = d.prs.any { it.checks == "fail" || it.merge?.needsOverride != null }

    /** Branches whose merge ships to production. */
    fun isProd(base: String): Boolean = base.lowercase() in setOf("main", "master", "prod", "production", "release", "live")

    // A tower from before decisions: the same queue from its questions and its waiting PRs.

    /**
     * The queue as a tower without `/v1/decisions` would have it: each
     * question it leaves to the user, and the waiting PRs of one repo in one
     * space as one merge. [spaceOf] names a session's space.
     */
    fun fromLegacy(questions: List<Question>, prs: Collection<Pr>, spaceOf: (String) -> String?, now: Long): List<Decision> {
        // One just answered stays (as answered, a quiet line) until the tower closes it.
        val asked = questions.filter { it.decision == "escalate" }.map { ofQuestion(it, spaceOf(it.session).orEmpty()) }
        val merges = prs.filter { it.waiting && it.open }
            .groupBy { (it.space ?: spaceOf(it.session)).orEmpty() to it.repo }
            .map { (at, list) -> ofPrs(at.first, at.second, list) }
        return order(asked + merges, now)
    }

    /** A question set left to the user, as a decision. */
    fun ofQuestion(q: Question, space: String): Decision {
        val tab = q.tabs.singleOrNull()
        val recs = q.recommendationsFor(0)
        val options = when {
            tab == null -> emptyList()
            recs.isNotEmpty() -> recs.mapIndexed { i, r -> DecisionOption(r.label, r.why, pick = i == 0) }
            // The brain hasn't said yet (or never will): a short single choice is answered with its own options.
            !tab.multi && tab.options.size in 1..4 -> tab.options.map { DecisionOption(it.label, it.description) }
            else -> emptyList()
        }
        val permission = q.kind == "claude-permission" || q.kind == "codex-menu"
        return Decision(
            id = LEGACY_QUESTION + q.id,
            key = "question:" + q.id,
            kind = if (permission) "permission" else "question",
            space = space,
            sessions = listOf(DecisionSession(q.session, q.name.orEmpty())),
            waiting = 1,
            // A permission's own words ("Do you want to proceed?") say nothing without what it wants to run.
            title = if (permission || tab == null) q.title.ifBlank { q.headline } else q.headline,
            why = when {
                q.kind == "claude-plan" -> "Plan"
                permission -> "Permission"
                q.tabs.size > 1 -> "${q.tabs.size} questions"
                else -> "Question"
            },
            options = options,
            questionId = q.id,
            firstMs = q.atMs,
            lastMs = q.atMs,
            state = when (q.status) {
                "answering", "queued" -> "answering"
                "answered" -> "answered"
                else -> "open"
            },
            detail = q.detail,
            error = q.error?.takeIf { q.status == "failed" },
        )
    }

    /** The answer option [label] of a legacy decision means for its question, or null when it has none (any more). */
    fun legacyChoice(q: Question, label: String): Choice? {
        val tab = q.tabs.singleOrNull() ?: return null
        q.recommendationsFor(0).firstOrNull { it.label == label }?.parsed?.let { return it }
        val i = tab.options.indexOfFirst { it.label == label }
        return if (i >= 0) Choice.Pick(i) else null
    }

    /** The waiting PRs of one repo in one space, as one merge decision: dev before main. */
    fun ofPrs(space: String, repo: String, prs: List<Pr>): Decision {
        val ordered = prs.sortedWith(compareBy<Pr>({ isProd(it.base) }, { it.number }))
        val sessions = ordered.filter { it.session.isNotBlank() }.distinctBy { it.session }.map { DecisionSession(it.session, it.name) }
        val first = ordered.first()
        return Decision(
            id = "$LEGACY_MERGE$space|$repo",
            key = "merge:$repo",
            kind = "merge",
            space = space,
            sessions = sessions,
            // Their sessions ended their turn: nothing is blocked, the merges wait.
            waiting = 0,
            title = if (ordered.size == 1) "Merge #${first.number}: ${first.title.ifBlank { first.id }}" else "${ordered.size} pull requests ready to merge",
            why = "Merge",
            prs = ordered.map { DecisionPr(it.repo, it.number, it.base, checks = checksWord(it.checks.state), prKey = it.id, title = it.title, link = it.url, session = it.session, merge = it.merge, score = it.score, validation = it.validation, impact = it.impact) },
            firstMs = ordered.minOf { if (it.foundMs > 0) it.foundMs else Long.MAX_VALUE }.takeIf { it != Long.MAX_VALUE } ?: 0,
            lastMs = ordered.maxOf { it.checkedMs },
            count = ordered.size,
        )
    }

    private fun checksWord(state: String): String = when (state) {
        "passing" -> "pass"
        "failing" -> "fail"
        "pending" -> "pending"
        "none" -> "none"
        else -> ""
    }

    /**
     * The 409 an answer or a dismissal gets when the decision is over already:
     * how it ended (`answered`, `dismissed`, `stopped`), or null for any other error.
     */
    fun closedBy(e: Throwable): String? = (e as? TowerApiException)?.takeIf { it.status == 409 && it.code == "closed" }?.let { it.word("state") ?: "answered" }

    /** "It was answered already, on another screen or in its session." */
    fun closedWords(state: String): String = when (state) {
        "dismissed" -> "It was dismissed already."
        "stopped" -> "It's over: its sessions moved on."
        else -> "It was answered already, on another screen or in its session."
    }

    /** The question's id behind a decision's id made here, or null. */
    fun legacyQuestionId(id: String): String? = id.removePrefix(LEGACY_QUESTION).takeIf { id.startsWith(LEGACY_QUESTION) && it.isNotEmpty() }
}
