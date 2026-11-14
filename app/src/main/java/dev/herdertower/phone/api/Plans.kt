package dev.herdertower.phone.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** A session the draft would open: its repo, and why it is in. */
@Serializable
data class PlanSession(val repo: String = "", val why: String? = null)

/** The draft a plan is written from (`tower launch --draft --json`): the space it would open and its goal. */
@Serializable
data class PlanDraft(
    val space: String? = null,
    val goal: String? = null,
    @SerialName("done_when") val doneWhen: String? = null,
    val sessions: List<PlanSession> = emptyList(),
)

/** Something outside that uses what a predicted PR changes: [what] ("the response field page.body"), [usedBy] (repo, file, function), and whether it [breaks]. */
@Serializable
data class PlanDependent(val what: String = "", @SerialName("used_by") val usedBy: String = "", val breaks: Boolean = false)

/** One line of the chat about a plan: `user` or `planner`. */
@Serializable
data class PlanMsg(val from: String = "", val text: String = "", val at: Long = 0)

/** A pull request a plan says will be opened, with the tower's score of it (`tower plan prs`). */
@Serializable
data class PredictedPr(
    /** Its number in the plan, from 1. */
    val n: Int = 0,
    val repo: String = "",
    /** The branch it merges into: `dev` unless the plan says otherwise. */
    val base: String = "dev",
    val title: String = "",
    val changes: String = "",
    /** Roughly which files (paths in the repo, or globs). */
    val files: List<String> = emptyList(),
    val migration: Boolean = false,
    /** What it breaks, in the planner's words; empty: nothing. */
    val breaking: String = "",
    val escalate: List<String> = emptyList(),
    /** The plan's PRs (by [n]) it waits for. */
    val after: List<Int> = emptyList(),
    /** How it is validated (a tower with `validation`): each check planned. */
    val checks: List<PrCheck> = emptyList(),
    @SerialName("edge_cases") val edgeCases: List<EdgeCase> = emptyList(),
    /** Who outside uses what it changes, and whether the change breaks it (a tower with `impact`). */
    val dependents: List<PlanDependent> = emptyList(),
    /** How the planner checked who depends on it; when nothing does, that it doesn't. */
    val outside: String = "",
    val score: PrScore? = null,
    /** The real PR's key once it opened (`owner/name#12`). */
    val pr: String? = null,
)

/**
 * The plan that is open (`plan` in the state, docs/mobile-api.md): what the
 * tower's planner writes for a ticket before a space is launched. [status]
 * `writing`, `ready`, `failed` or `closed`; [planner] `starting`, `working`,
 * `idle`, `blocked` or `gone`; [url] the published plan, null until then.
 */
@Serializable
data class Plan(
    val id: String = "",
    /** `page` or `whiteboard`. */
    val form: String = "page",
    val status: String = "writing",
    val ticket: String = "",
    val draft: PlanDraft? = null,
    val revision: Int = 0,
    val planner: String = "",
    /** A dialog in the planner's terminal waits for an answer there. */
    val blocked: Boolean = false,
    /** The user said something the planner hasn't answered yet. */
    val answering: Boolean = false,
    val url: String? = null,
    /** Why it failed, or that the planner ended. */
    val why: String = "",
    val thread: List<PlanMsg> = emptyList(),
    @SerialName("created_ms") val createdMs: Long = 0,
    @SerialName("ready_ms") val readyMs: Long? = null,
    @SerialName("updated_ms") val updatedMs: Long = 0,
    @SerialName("closed_ms") val closedMs: Long? = null,
    /** The space launched with it. */
    val launched: String? = null,
    /** The pull requests it predicts (a tower with `scores`; a plan from before has none). */
    val prs: List<PredictedPr> = emptyList(),
    /** Planning v2: its stages, each with what counts as done and how it is validated. */
    val stages: List<PlanStage> = emptyList(),
    /** How far the work is: how many stages, how many done, the one at work (1-based) or null. */
    val progress: PlanProgress? = null,
    /** What the plan asks, each answered or where it waits. */
    val questions: List<PlanQuestion> = emptyList(),
    @SerialName("open_questions") val openQuestions: Int = 0,
    /** What it takes for granted, each with how it is checked. */
    val assumptions: List<PlanAssumption> = emptyList(),
    /** What the work found that the plan didn't know. */
    val findings: List<PlanFinding> = emptyList(),
    /** How the plan changed since it launched. */
    val revisions: List<PlanRevision> = emptyList(),
    val version: Int = 0,
    /** A critic reads a draft before its questions go out to the user. */
    val critic: PlanCritic? = null,
    /** Spikes done whose findings the plan hasn't taken in yet (stage ids): the stages after them wait. */
    @SerialName("spikes_pending") val spikesPending: List<String> = emptyList(),
    /** The planner's Claude account. */
    val account: String? = null,
)

/** The critic's read of a draft: [status] "" (not yet), `reading`, `done` or `failed`; the gaps it sent the planner. */
@Serializable
data class PlanCritic(val status: String = "", @SerialName("at_ms") val atMs: Long = 0, val gaps: List<String> = emptyList(), val why: String = "")

/** An agenda task sent for a stage: its id, title, state (queued, running, blocked, done-claimed, verified, failed, …) and session. */
@Serializable
data class StageTask(val id: String = "", val title: String = "", val state: String = "", val session: String = "", val name: String = "")

/** A stage of a plan: [kind] `spike`, `build` or `release`; [state] `waiting`, `ready`, `active`, `failing` or `done`. */
@Serializable
data class PlanStage(
    val id: String = "",
    val title: String = "",
    val kind: String = "",
    val goal: String = "",
    @SerialName("done_when") val doneWhen: String = "",
    val after: List<String> = emptyList(),
    /** The plan's PRs (their numbers) it delivers. */
    val prs: List<Int> = emptyList(),
    /** Checked before its work starts. */
    val pre: List<PrCheck> = emptyList(),
    /** Done when these pass. */
    val post: List<PrCheck> = emptyList(),
    val state: String = "",
    /** The agenda tasks sent for it. */
    val tasks: List<StageTask> = emptyList(),
)

@Serializable
data class PlanProgress(val stages: Int = 0, val done: Int = 0, val at: Int? = null)

@Serializable
data class PlanOption(val label: String = "", val why: String = "")

/** A question of the plan: [status] `open`, `with-tower`, `asked` (in the user's queue, [card]) or `answered` (by `you` or `tower`). */
@Serializable
data class PlanQuestion(
    val id: String = "",
    val text: String = "",
    val why: String = "",
    val options: List<PlanOption> = emptyList(),
    val recommended: Int? = null,
    val theirs: Boolean = false,
    val stage: String = "",
    val status: String = "open",
    val answer: String = "",
    val by: String = "",
    val card: String? = null,
    @SerialName("at_ms") val atMs: Long? = null,
) {
    val open: Boolean get() = status != "answered"
}

/** An assumption: how it is checked, by which stage; [status] `open`, `holds` or `broken`. */
@Serializable
data class PlanAssumption(val id: String = "", val text: String = "", val check: String = "", val by: String = "", val status: String = "open", val proof: String = "")

/** Something the work found: [revision] the plan's revision that took it in, null while it waits for one. */
@Serializable
data class PlanFinding(
    val id: String = "",
    @SerialName("at_ms") val atMs: Long = 0,
    val kind: String = "",
    val text: String = "",
    val stage: String = "",
    val assumption: String = "",
    /** The session whose Handoff block it came from. */
    val session: String = "",
    val revision: Int? = null,
)

/** A revision of a live plan: [size] `small`, `big` or `none`; [status] `applied`, `proposed` (waits for the user's word), `rejected` or `kept` (a finding changes nothing). */
@Serializable
data class PlanRevision(
    val n: Int = 0,
    @SerialName("at_ms") val atMs: Long = 0,
    val by: String = "",
    val why: String = "",
    val findings: List<String> = emptyList(),
    val changes: List<String> = emptyList(),
    val size: String = "small",
    val status: String = "applied",
    val card: String? = null,
)

object PlansApi {
    /** The state's `plans_live`: the launched plans, each read on its own (one the app can't read is left out). */
    fun live(e: JsonElement?): List<Plan> =
        (e as? kotlinx.serialization.json.JsonArray)?.mapNotNull { runCatching { TowerJson.decodeFromJsonElement(Plan.serializer(), it) }.getOrNull() }.orEmpty()

    /** A space's live plan: the one launched as it (by name, any case). */
    fun liveOf(plans: List<Plan>, space: String): Plan? = plans.lastOrNull { it.launched?.equals(space, ignoreCase = true) == true }

    /** "Stage 2 of 4 · 1 done", "All 4 done", or "3 stages" before any is under way (the Mac's stages_head). */
    fun stagesHead(p: Plan): String {
        val n = maxOf(p.progress?.stages ?: 0, p.stages.size)
        val done = p.progress?.done ?: 0
        val at = p.progress?.at
        return when {
            done == n && n > 0 -> "All $n done"
            at != null && done > 0 -> "Stage $at of $n · $done done"
            else -> "$n stage${if (n == 1) "" else "s"}"
        }
    }

    /** A stage's chip, and whether it is trouble: Done, Failing, Under way, Pre-checks first, Waiting, Planned. */
    fun stageWord(state: String): Pair<String, Boolean> = when (state) {
        "done" -> "Done" to false
        "failing" -> "Failing" to true
        "active" -> "Under way" to false
        "ready" -> "Pre-checks first" to false
        "waiting" -> "Waiting" to false
        else -> "Planned" to false
    }

    /** A check's mark, and whether it is bad news: ✓ passed, ✗ failed, – not run, ○ not reported yet. */
    fun checkMark(c: PrCheck): Pair<String, Boolean> = when (c.status) {
        "passed" -> "✓" to false
        "failed" -> "✗" to true
        "not_run" -> "–" to true
        else -> "○" to false
    }

    /** A check's result line: "passed: <proof>" once reported, else "Evidence: <what it gives>". */
    fun checkResult(c: PrCheck): String {
        val proof = c.proof?.takeIf { it.isNotBlank() } ?: return "Evidence: ${c.evidence}"
        val word = when (c.status) {
            "passed" -> "passed"
            "failed" -> "failed"
            "not_run" -> "not run"
            else -> "not reported yet"
        }
        return "$word: $proof"
    }

    /** "Delivers PR 1, PR 2 · after s1", or "" when it says neither. */
    fun stageLine(s: PlanStage): String = listOfNotNull(
        s.prs.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "Delivers ") { "PR $it" },
        s.after.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "after "),
    ).joinToString(" · ")

    /** Where a question is: "Answered by you: …", "In your queue: …", "With the tower: …", "Being routed." */
    fun questionLine(q: PlanQuestion): String = when (q.status) {
        "answered" -> "Answered by ${if (q.by == "tower") "the tower" else "you"}: ${q.answer.trim()}"
        "asked" -> "In your queue: answer it there, on the Mac or the phone."
        "with-tower" -> "With the tower: it answers what is routine, and asks you the rest."
        else -> "Being routed."
    }

    /** The critic's read in a line (the Mac's critic_line); null before it reads. */
    fun criticLine(c: PlanCritic?): String? = when (c?.status) {
        "reading" -> "A critic is reading the plan before its questions go out to you."
        "done" -> if (c.gaps.isEmpty()) "A critic read the plan and found no gaps."
        else "A critic read the plan first: ${c.gaps.size} gap${if (c.gaps.size == 1) "" else "s"} sent to the planner. Its questions came to you after."
        "failed" -> "The critic couldn't read the plan; its questions went out as they were."
        else -> null
    }

    /** "a9.1 running (t5) · a9.2 verified" (the Mac's tasks_line). */
    fun tasksLine(tasks: List<StageTask>): String = tasks.joinToString(" · ") { if (it.session.isBlank()) "${it.id} ${it.state}" else "${it.id} ${it.state} (${it.session})" }

    /** On a spike whose findings wait: the plan screen's words and the live card's. */
    const val SPIKE_WAITS = "Done: what it found waits for the plan's revision; the stages after it wait too."
    const val SPIKE_WAITS_LIVE = "Its findings wait for the plan's revision"

    /** "2 open of 4", "All 4 answered". */
    fun questionsHead(p: Plan): String =
        if (p.openQuestions == 0) "All ${p.questions.size} answered" else "${p.openQuestions} open of ${p.questions.size}"

    /** Why Launch waits, while the plan has open questions. */
    fun launchWaits(p: Plan): String? = when (p.openQuestions) {
        0 -> null
        1 -> "1 question of the plan is open: Launch waits for its answer, so no plan goes out with questions on its page."
        else -> "${p.openQuestions} questions of the plan are open: Launch waits for their answers, so no plan goes out with questions on its page."
    }

    /** An assumption's check line: "Checked by s1: …", or "Checked: …". */
    fun assumptionLine(a: PlanAssumption): String = if (a.by.isBlank()) "Checked: ${a.check}" else "Checked by ${a.by}: ${a.check}"

    /** ✓ holds, ✗ broken, ○ not checked yet. */
    fun assumptionMark(a: PlanAssumption): String = when (a.status) {
        "holds" -> "✓"
        "broken" -> "✗"
        else -> "○"
    }

    /** "Plan · version 3 · Stage 2 of 4 · 1 done". */
    fun liveHead(p: Plan): String = "Plan · version ${maxOf(p.version, 1)} · ${stagesHead(p)}"

    /** "Done when: … · 2 of 3 checks passed". */
    fun liveStageLine(s: PlanStage): String {
        val all = s.pre + s.post
        return "Done when: ${s.doneWhen} · ${all.count { it.status == "passed" }} of ${all.size} checks passed"
    }

    /** "2 findings wait for the plan's revision.", or null when none waits. */
    fun findingsWait(p: Plan): String? {
        val n = p.findings.count { it.revision == null }
        return if (n == 0) null else if (n == 1) "1 finding waits for the plan's revision." else "$n findings wait for the plan's revision."
    }

    /** "Revision 2 · small, applied by itself: <why> (from f2)", "… · waiting for your word: …". */
    fun revisionLine(r: PlanRevision): String {
        if (r.status == "kept") return "Revision ${r.n} · kept: ${r.why.trimEnd('.')}"
        val what = when {
            r.status == "proposed" -> "waiting for your word"
            r.status == "rejected" -> "kept out by you"
            r.size == "big" -> "big, applied on your yes"
            else -> "small, applied by itself"
        }
        val from = if (r.findings.isEmpty()) "" else " (from ${r.findings.joinToString(", ")})"
        return "Revision ${r.n} · $what: ${r.why.trimEnd('.')}$from"
    }

    /** What changed: the last 4 revisions, newest first. */
    fun lastRevisions(p: Plan): List<PlanRevision> = p.revisions.takeLast(4).reversed()

    /** A big revision's card (why "plan", key "plan:<plan>:rev:<n>"): its detail is the diff, shown whole. */
    fun isRevisionCard(d: Decision): Boolean = d.why == "plan" && Regex("""^plan:[^:]+:rev:\d+$""").matches(d.key)

    /**
     * The state's `plan`, or null when none is open. Read on its own: a plan
     * the app can't read is no plan here, and never breaks the rest of the
     * state.
     */
    fun parse(e: JsonElement?): Plan? {
        if (e == null || e is JsonNull || e !is JsonObject) return null
        return runCatching { TowerJson.decodeFromJsonElement(Plan.serializer(), e) }.getOrNull()
    }
}
