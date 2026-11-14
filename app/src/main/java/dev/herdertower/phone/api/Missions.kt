package dev.herdertower.phone.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** A session of a space, as its mission lists it: where it works and on which port. */
@Serializable
data class MissionSession(
    val address: String = "",
    val name: String = "",
    val status: String = "",
    val account: String? = null,
    val worktree: String? = null,
    val branch: String? = null,
    val port: Int? = null,
    /** Its last words, when the tower says them. */
    val said: String? = null,
    /** A job it left running in the background (feature `jobs`): its turn may be done, its work isn't. */
    val job: MissionJob? = null,
    /** Where it is, in a few plain sentences, kept up by the tower (absent from an older tower). */
    val brief: SessionBrief? = null,
) {
    val label: String get() = name.ifBlank { address }

    /** A job of it runs and isn't quiet: at work, whatever its turn says. */
    val runsJob: Boolean get() = job != null && !job.stuck

    /** Its status as its row says it: "running a job" (or "job quiet") while its turn is over and a job runs. */
    val statusShown: String get() = when {
        job != null && status in setOf("done", "idle", "") -> if (job.stuck) "job quiet" else "running a job"
        else -> status
    }
}

/**
 * A job a session left running, as the tower shows it: what, since when, its
 * last progress (Unix ms), [stuck] when every job of the session is quiet and
 * idle, [more] other jobs, and [line] ("running a job: …, since 00:21, last progress 00:33").
 */
@Serializable
data class MissionJob(
    val what: String = "",
    @SerialName("since_ms") val sinceMs: Long = 0,
    @SerialName("progress_ms") val progressMs: Long? = null,
    val stuck: Boolean = false,
    val more: Int = 0,
    val line: String = "",
)

/** A request of the user's in a space and how far it is: [done] of [total] tasks. */
@Serializable
data class MissionRequest(
    val id: String = "",
    val title: String = "",
    val state: String = "",
    val done: Int = 0,
    val total: Int = 0,
) {
    val finished: Boolean get() = state in setOf("done", "verified", "closed", "finished") || (total > 0 && done >= total)
}

/**
 * A space as a mission with an end (docs/quiet-tower.md, "Step 5 and 8"):
 * its goal and what counts as done, [state] `open`, `quiet`, `ready` or
 * `closed`, how many decisions wait there ([needs]), its closing [report].
 */
@Serializable
data class Mission(
    val name: String = "",
    val goal: String? = null,
    @SerialName("done_when") val doneWhen: String? = null,
    /** How the goal came to be, oldest first: the last is the goal now (empty from a tower that doesn't keep it). */
    @SerialName("goal_history") val goalHistory: List<GoalVersion> = emptyList(),
    val standing: Boolean = false,
    val state: String = "open",
    val follow: Boolean = false,
    val mute: Boolean = false,
    val needs: Int = 0,
    val report: String? = null,
    val sessions: List<MissionSession> = emptyList(),
    val requests: List<MissionRequest> = emptyList(),
    @SerialName("thread_count") val threadCount: Int = 0,
    /** The space's own tower, or null when it has none and is due none. */
    val tower: MissionTower? = null,
    @SerialName("quiet_since_ms") val quietSinceMs: Long? = null,
    @SerialName("ready_since_ms") val readySinceMs: Long? = null,
    /** When a ready space closes by itself (null: it won't). */
    @SerialName("auto_close_ms") val autoCloseMs: Long? = null,
    /** How long its sessions have been at work, and waited on the user (absent from an older tower). */
    val active: MissionActive? = null,
    /** Where the space is, in a few plain sentences, kept up by the tower (absent from an older tower). */
    val brief: SpaceBrief? = null,
) {
    /** Its own tower takes what is asked here (else the main tower does, with the space named). */
    val ownTower: Boolean get() = tower?.status == "ready" || tower?.status == "working"
}

/**
 * One version of a space's goal: [by] `request` (the tower, after the user's
 * [request]; [why] what it added), `you`, `launch`, `tower`, or "" (before
 * versions were kept); [atMs] when it became the goal.
 */
@Serializable
data class GoalVersion(
    val goal: String = "",
    @SerialName("done_when") val doneWhen: String = "",
    @SerialName("at_ms") val atMs: Long = 0,
    val by: String = "",
    val request: String? = null,
    val why: String? = null,
)

object GoalsApi {
    /** The version that is the goal now: the last, when it says what the space says; null otherwise. */
    fun current(m: Mission): GoalVersion? = m.goalHistory.lastOrNull()?.takeIf { it.goal.trim() == m.goal.orEmpty().trim() && it.doneWhen.trim() == m.doneWhen.orEmpty().trim() }

    /** "Goal, updated by your request at 15:55", "Goal, set by you at …", "Goal, set at launch"; null when how it was set isn't known. */
    fun how(v: GoalVersion?, clock: (Long) -> String): String? {
        v ?: return null
        val at = if (v.atMs > 0) " at ${clock(v.atMs)}" else ""
        return when (v.by) {
            "request" -> "Goal, updated by your request$at"
            "you" -> "Goal, set by you$at"
            "launch" -> "Goal, set at launch"
            "tower" -> "Goal, set by the tower on your word$at"
            else -> null
        }
    }

    /** The earlier versions, newest first. */
    fun earlier(m: Mission): List<GoalVersion> = (if (current(m) != null) m.goalHistory.dropLast(1) else m.goalHistory).reversed()

    /** Who set a version, in a few words: "your request", "you", "launch", "the tower". */
    fun by(v: GoalVersion): String = when (v.by) {
        "request" -> "your request"
        "you" -> "you"
        "launch" -> "launch"
        "tower" -> "the tower"
        else -> "before versions were kept"
    }
}

/** One version of a brief: when it said so, and what. */
@Serializable
data class BriefVersion(@SerialName("at_ms") val atMs: Long = 0, val text: String = "")

/**
 * Where a space is ("where are we"): the whole of it in a few sentences
 * ([text]), its [stage], what was done lately (newest first, "<session>:
 * <result>"), who works on what now, what is queued, what waits for the
 * user, the risks; when what it says last changed, and its earlier versions
 * ([history], newest first, the first the current one).
 */
@Serializable
data class SpaceBrief(
    val text: String = "",
    val goal: String = "",
    val stage: String = "",
    val done: List<String> = emptyList(),
    val doing: List<String> = emptyList(),
    val next: List<String> = emptyList(),
    val waits: List<String> = emptyList(),
    val risks: List<String> = emptyList(),
    @SerialName("changed_ms") val changedMs: Long = 0,
    val history: List<BriefVersion> = emptyList(),
)

/**
 * Where a session is: what it was asked last and where that stands
 * ([asked]), what came of it, what else it finished lately, what it does
 * [now], what is next, and a task stuck ([stuck]) with why.
 */
@Serializable
data class SessionBrief(
    val text: String = "",
    val asked: String = "",
    val result: String? = null,
    val done: List<String> = emptyList(),
    val now: String = "",
    val next: List<String> = emptyList(),
    val stuck: String? = null,
    @SerialName("changed_ms") val changedMs: Long = 0,
    val history: List<BriefVersion> = emptyList(),
)

/** What a brief says, part by part, as the Mac shows it. */
object Briefs {
    /** How a part looks: plain, amber (waits for the user, stuck), rose (risks). */
    enum class Tone { Plain, Waits, Risk }

    data class Part(val label: String, val text: String, val tone: Tone = Tone.Plain)

    /** Two of a done list, then "(and N more today)". */
    fun twoAndMore(list: List<String>): String =
        list.take(2).joinToString("; ") + (list.size - 2).takeIf { it > 0 }?.let { " (and $it more today)" }.orEmpty()

    /** A space's "Where we are": Stage, Done, In progress, Next, Waiting for you (amber), Risks (rose); empty parts left out. */
    fun space(b: SpaceBrief): List<Part> = buildList {
        b.stage.takeIf { it.isNotBlank() }?.let { add(Part("Stage", it)) }
        b.done.takeIf { it.isNotEmpty() }?.let { add(Part("Done", twoAndMore(it))) }
        b.doing.takeIf { it.isNotEmpty() }?.let { add(Part("In progress", it.joinToString("; "))) }
        b.next.takeIf { it.isNotEmpty() }?.let { add(Part("Next", it.joinToString("; "))) }
        b.waits.takeIf { it.isNotEmpty() }?.let { add(Part("Waiting for you", it.joinToString("; "), Tone.Waits)) }
        b.risks.takeIf { it.isNotEmpty() }?.let { add(Part("Risks", it.joinToString("; "), Tone.Risk)) }
        // A brief with none of its parts: its whole text.
        if (isEmpty() && b.text.isNotBlank()) add(Part("", b.text))
    }

    /** A session's brief opened: Asked, Result, Earlier today: N more tasks done, Next, Stuck (amber). */
    fun session(b: SessionBrief): List<Part> = buildList {
        b.asked.takeIf { it.isNotBlank() }?.let { add(Part("Asked", it)) }
        b.result?.takeIf { it.isNotBlank() }?.let { add(Part("Result", it)) }
        b.done.takeIf { it.isNotEmpty() }?.let { add(Part("Earlier today", "${it.size} more task${if (it.size == 1) "" else "s"} done")) }
        b.next.takeIf { it.isNotEmpty() }?.let { add(Part("Next", it.joinToString("; "))) }
        b.stuck?.takeIf { it.isNotBlank() }?.let { add(Part("Stuck", it, Tone.Waits)) }
        if (isEmpty() && b.text.isNotBlank()) add(Part("", b.text))
    }

    /** The earlier versions, newest first: every one but the current. */
    fun earlier(history: List<BriefVersion>): List<BriefVersion> = history.drop(1)
}

/**
 * A space's active time, as the tower last worked it out ([atMs]): [now]
 * a session of it works or runs a background job (since [sinceMs]), its
 * time at work today (the Mac's day) and since it opened, and the time it
 * waited on the user. Its own tower, its reviewers and idle time don't count.
 */
@Serializable
data class MissionActive(
    @SerialName("at_ms") val atMs: Long = 0,
    val now: Boolean = false,
    @SerialName("since_ms") val sinceMs: Long? = null,
    @SerialName("today_ms") val todayMs: Long = 0,
    @SerialName("total_ms") val totalMs: Long = 0,
    @SerialName("waiting_now") val waitingNow: Boolean = false,
    @SerialName("waiting_today_ms") val waitingTodayMs: Long = 0,
    @SerialName("waiting_total_ms") val waitingTotalMs: Long = 0,
)

/**
 * Active time between two reads: the time since the tower's figures goes
 * to what runs now (at work, or waiting on the user), so the numbers move
 * without asking the tower again.
 */
object ActiveTime {
    data class Live(val stretchMs: Long?, val todayMs: Long, val totalMs: Long, val waitingTodayMs: Long, val waitingTotalMs: Long)

    fun live(a: MissionActive, now: Long): Live {
        val gone = if (a.atMs > 0) (now - a.atMs).coerceAtLeast(0) else 0
        val work = if (a.now) gone else 0
        val wait = if (a.waitingNow && !a.now) gone else 0
        return Live(
            stretchMs = a.sinceMs?.takeIf { a.now && it > 0 }?.let { (now - it).coerceAtLeast(0) },
            todayMs = a.todayMs + work, totalMs = a.totalMs + work,
            waitingTodayMs = a.waitingTodayMs + wait, waitingTotalMs = a.waitingTotalMs + wait,
        )
    }

    /** "42m" under an hour, else "1h 05m". */
    fun duration(ms: Long): String {
        val min = (ms / 60_000).coerceAtLeast(0)
        return if (min < 60) "${min}m" else "${min / 60}h ${(min % 60).toString().padStart(2, '0')}m"
    }

    private const val MINUTE = 60_000L

    /**
     * The space screen's line, as the Mac's: "Active now · 1h 12m this
     * stretch · 3h 05m today · 14h 20m since it opened · waited on you 25m
     * today", "Not at work now · …", "Waiting on you now · …"; null when
     * it has been at work under a minute in all and isn't now. A stretch
     * under a minute isn't said: "Active now" alone says it ("0m this
     * stretch" read as nothing at work).
     */
    fun line(a: MissionActive?, now: Long): String? {
        a ?: return null
        val l = live(a, now)
        if (!a.now && l.totalMs < MINUTE) return null
        return buildList {
            add(
                when {
                    a.now -> "Active now"
                    a.waitingNow -> "Waiting on you now"
                    else -> "Not at work now"
                },
            )
            l.stretchMs?.takeIf { it >= MINUTE }?.let { add("${duration(it)} this stretch") }
            add("${duration(l.todayMs)} today")
            add("${duration(l.totalMs)} since it opened")
            if (l.waitingTodayMs >= MINUTE) add("waited on you ${duration(l.waitingTodayMs)} today")
        }.joinToString(" · ")
    }

    /** The spaces list's word: "at work 12m" (this stretch) while at work ("at work now" under a minute), else "1h 12m today"; null under a minute today. */
    fun compact(a: MissionActive?, now: Long): String? {
        a ?: return null
        val l = live(a, now)
        if (a.now) return l.stretchMs?.takeIf { it >= MINUTE }?.let { "at work " + duration(it) } ?: "at work now"
        return l.todayMs.takeIf { it >= MINUTE }?.let { "${duration(it)} today" }
    }
}

/** A space's own tower (its own brain): [status] `ready`, `working`, `starting`, `blocked` or `off`. */
@Serializable
data class MissionTower(val name: String = "", val status: String = "", val address: String? = null, val account: String? = null)

@Serializable
data class MissionsResponse(
    val spaces: List<Mission> = emptyList(),
    val quiet: List<String> = emptyList(),
    @SerialName("closed_today") val closedToday: List<String> = emptyList(),
)

/** A message one session sent another (`GET /v1/thread`), newest last; [delivered] false: its receiver was gone. */
@Serializable
data class ThreadMessage(
    val id: String = "",
    val at: Long = 0,
    val space: String? = null,
    val delivered: Boolean = true,
    val from: String = "",
    @SerialName("from_name") val fromName: String = "",
    val to: String = "",
    @SerialName("to_name") val toName: String = "",
    val text: String = "",
) {
    /** "mediaroom → eyzflow". */
    val who: String get() = "${fromName.ifBlank { from }} → ${toName.ifBlank { to }}"
}

@Serializable
data class ThreadResponse(val space: String? = null, val messages: List<ThreadMessage> = emptyList(), val more: Boolean = false)

/** The `thread` event: one new message between two sessions of [space]. */
@Serializable
data class ThreadEvent(val space: String? = null, val message: ThreadMessage = ThreadMessage())

/** A saved setup a space is launched from: an account and its repos. */
@Serializable
data class Setup(val name: String = "", val account: String = "", val repos: List<String> = emptyList())

@Serializable
data class SetupsResponse(val setups: List<Setup> = emptyList())

@Serializable
data class LaunchRequest(
    val goal: String,
    val setup: String? = null,
    val name: String? = null,
    @SerialName("dry_run") val dryRun: Boolean? = null,
)

/** What the tower chose without asking, and why. */
@Serializable
data class Assumed(
    val setup: String? = null,
    @SerialName("setup_why") val setupWhy: String? = null,
    val account: String? = null,
    @SerialName("account_why") val accountWhy: String? = null,
    val name: String? = null,
    @SerialName("name_why") val nameWhy: String? = null,
    val worktrees: Boolean? = null,
    @SerialName("worktrees_why") val worktreesWhy: String? = null,
)

/** A session of a launch: as planned, or as opened ([address], [port], [task]), or why it couldn't open ([error]). */
@Serializable
data class LaunchSession(
    val name: String = "",
    val repo: String = "",
    val address: String? = null,
    val account: String? = null,
    val worktree: String? = null,
    val branch: String? = null,
    val port: Int? = null,
    val task: String? = null,
    val error: String? = null,
) {
    val opened: Boolean get() = !address.isNullOrBlank()
    val folder: String get() = repo.trimEnd('/').substringAfterLast('/')
}

/** A launch's plan (a dry run), or its receipt so far: [made] false when a space with that name was joined. */
@Serializable
data class LaunchPlan(
    val space: String = "",
    val goal: String = "",
    val made: Boolean = true,
    @SerialName("dry_run") val dryRun: Boolean = false,
    val agenda: String? = null,
    val sessions: List<LaunchSession> = emptyList(),
    val assumed: Assumed = Assumed(),
    /** The act the launch became (its undo), once it is through. */
    val act: String? = null,
)

/** What a close did: a worktree with work on no remote is kept, and why. */
@Serializable
data class CloseReceipt(
    val closed: String = "",
    @SerialName("sessions_ended") val sessionsEnded: List<DecisionSession> = emptyList(),
    @SerialName("sessions_left") val sessionsLeft: List<LeftSession> = emptyList(),
    @SerialName("herdr_space") val herdrSpace: String? = null,
    @SerialName("worktrees_removed") val worktreesRemoved: List<String> = emptyList(),
    @SerialName("kept_worktrees") val keptWorktrees: List<KeptWorktree> = emptyList(),
    @SerialName("tower_ended") val towerEnded: List<String> = emptyList(),
    val report: String = "",
)

@Serializable
data class LeftSession(val address: String = "", val name: String = "", val why: String = "")

@Serializable
data class KeptWorktree(val path: String = "", val branch: String? = null, val repo: String? = null, val why: String = "") {
    val folder: String get() = path.trimEnd('/').substringAfterLast('/')
}

/**
 * A launch or a close a phone started: it answers at once and goes on by
 * itself. [state] `running`, `done` or `failed`; [plan] a launch's plan,
 * [receipt] what it did so far (a launch's [LaunchPlan], a close's
 * [CloseReceipt]).
 */
@Serializable
data class TowerAction(
    val id: String = "",
    val kind: String = "",
    val space: String = "",
    val state: String = "running",
    val plan: kotlinx.serialization.json.JsonElement? = null,
    val receipt: kotlinx.serialization.json.JsonElement? = null,
    val error: String? = null,
    val by: String? = null,
    @SerialName("started_ms") val startedMs: Long = 0,
    @SerialName("finished_ms") val finishedMs: Long? = null,
) {
    val running: Boolean get() = state == "running"

    /** A launch's plan, or its receipt so far. */
    val launch: LaunchPlan? get() = (receipt ?: plan)?.let { e -> runCatching { TowerJson.decodeFromJsonElement(LaunchPlan.serializer(), e) }.getOrNull() }?.takeIf { kind == "launch" }

    val closed: CloseReceipt? get() = receipt?.takeIf { kind == "close" }?.let { e -> runCatching { TowerJson.decodeFromJsonElement(CloseReceipt.serializer(), e) }.getOrNull() }
}

@Serializable
data class ActionResponse(val action: TowerAction = TowerAction())

@Serializable
data class ActionsResponse(val actions: List<TowerAction> = emptyList())

@Serializable
data class GoalRequest(
    val goal: String? = null,
    @SerialName("done_when") val doneWhen: String? = null,
    val standing: Boolean? = null,
)

@Serializable
data class OnRequest(val on: Boolean)

@Serializable
data class CloseRequest(val force: Boolean = false)

/** A session that keeps a close from going ahead: it works, or waits for the user. */
@Serializable
data class BusySession(val address: String = "", val name: String = "", val status: String = "") {
    val label: String get() = name.ifBlank { address }
}

/** How much the tower tells the user: `decisions`, `results` or `everything`. */
@Serializable
data class Tell(val level: String = "decisions", val voice: String? = null)

@Serializable
data class TellRequest(val level: String? = null, val voice: String? = null)

/** One model the picker offers. */
@Serializable
data class ModelChoice(val id: String = "", val label: String = "")

@Serializable
data class ModelChoices(val models: List<ModelChoice> = emptyList(), val efforts: List<String> = emptyList())

/** A brain that runs; [restart] true: it still runs on what it was started with. */
@Serializable
data class RunningBrain(
    val brain: String = "",
    val space: String? = null,
    val address: String? = null,
    val model: String? = null,
    val effort: String? = null,
    val restart: Boolean = false,
)

@Serializable
data class ModelLock(val model: String? = null, val effort: String? = null)

/**
 * The model and effort every tower brain runs on (`GET /v1/model`): one
 * setting for the main brain and each space's own; [says] it in a sentence.
 */
@Serializable
data class BrainModel(
    val program: String = "claude",
    val model: String = "",
    val effort: String = "",
    val choices: ModelChoices = ModelChoices(),
    val running: List<RunningBrain> = emptyList(),
    @SerialName("restart_needed") val restartNeeded: Boolean = false,
    @SerialName("locked_by_args") val lockedByArgs: Boolean = false,
    val locked: ModelLock = ModelLock(),
    val says: String = "",
    val restarted: List<String> = emptyList(),
) {
    /** The picker's label for [model] ("Opus 5.5"), or the id itself. */
    val modelLabel: String get() = choices.models.firstOrNull { it.id == model || it.label == model }?.label ?: model
}

@Serializable
data class ModelRequest(val model: String? = null, val effort: String? = null, val restart: Boolean? = null)

/** How a mission looks in a list. */
enum class Mood { Needs, Working, Ready, Done, Idle, Quiet }

object MissionsApi {
    /** A mission from an answer that holds it as `mission` (goal, follow, mute), or is the mission itself. */
    fun mission(body: String): Mission? = runCatching {
        val e = TowerJson.parseToJsonElement(body) as? JsonObject ?: return null
        val inner = (e["mission"] as? JsonObject) ?: (e["space"] as? JsonObject) ?: e
        TowerJson.decodeFromJsonElement(Mission.serializer(), inner).takeIf { it.name.isNotBlank() }
    }.getOrNull()

    /** The `missions` event's list, or null when it only says that something changed. */
    fun event(data: String): MissionsResponse? = runCatching {
        val e = TowerJson.parseToJsonElement(data) as? JsonObject ?: return null
        if ("spaces" !in e) null else TowerJson.decodeFromJsonElement(MissionsResponse.serializer(), e)
    }.getOrNull()

    fun mood(m: Mission): Mood = when {
        m.needs > 0 -> Mood.Needs
        m.state == "ready" -> Mood.Ready
        m.state == "quiet" || m.state == "closed" -> Mood.Quiet
        // A session whose turn is over while a job it started runs is at work too.
        m.sessions.any { it.status == "working" || it.status == "blocked" || it.runsJob } -> Mood.Working
        m.requests.isNotEmpty() && m.requests.all { it.finished } -> Mood.Done
        else -> Mood.Idle
    }

    /** How far it is, in a word or two: "ready to close", "5 of 6", "working", "done". */
    fun progress(m: Mission): String {
        if (m.state == "ready") return "ready to close"
        if (m.state == "closed") return "closed"
        val r = m.requests.firstOrNull { !it.finished } ?: m.requests.lastOrNull()
        return when {
            r != null && !r.finished && r.total > 1 -> "${r.done} of ${r.total}"
            r != null && r.finished -> "done"
            m.sessions.any { it.status == "working" } -> "working"
            m.sessions.any { it.status == "blocked" } -> "waiting"
            m.state == "quiet" || m.sessions.isEmpty() -> "quiet"
            r != null -> r.state.ifBlank { "open" }
            else -> "idle"
        }
    }

    /** The line under its name: its goal, or for a standing space what it works on now. */
    fun line(m: Mission): String {
        val request = (m.requests.firstOrNull { !it.finished } ?: m.requests.lastOrNull())?.title?.takeIf { it.isNotBlank() }
        val goal = m.goal?.takeIf { it.isNotBlank() }
        return (if (m.standing) request ?: goal else goal ?: request) ?: "No goal yet"
    }

    /** "3 sessions on orca", "2 sessions", "No sessions". */
    fun sessionsLine(m: Mission): String {
        val n = m.sessions.size
        if (n == 0) return "No sessions"
        val accounts = m.sessions.mapNotNull { it.account?.takeIf { a -> a.isNotBlank() } }.distinct()
        return "$n session${if (n == 1) "" else "s"}" + if (accounts.size == 1) " on ${accounts[0]}" else ""
    }

    /** How many spaces are at work (the queue's "3 working"). */
    fun working(all: MissionsResponse): Int = all.spaces.count { m -> m.sessions.any { it.status == "working" } }

    /**
     * The missions as a tower without `/v1/missions` would have them: its
     * spaces, each agenda as the goal, the open jobs as what is asked, and
     * how many of [decisions] wait in each. A space with no session is quiet.
     */
    fun fromSpaces(spaces: SpacesResponse, jobs: List<Job>, decisions: List<Decision>): MissionsResponse {
        val all = spaces.spaces.distinctBy { it.space }.map { sp ->
            val mine = jobs.filter { it.space == sp.space }
            val open = mine.filter { it.open }
            Mission(
                name = sp.space,
                goal = sp.agenda,
                state = if (sp.sessions.isEmpty()) "quiet" else "open",
                needs = decisions.count { it.space == sp.space && it.live },
                sessions = sp.sessions.map { MissionSession(it.address, it.name, it.status, it.account) },
                requests = (open.ifEmpty { mine.sortedByDescending { it.updatedMs }.take(1) })
                    .map { MissionRequest(it.job, it.asked, if (it.open) it.status else "done") },
            )
        }
        val (live, quiet) = all.partition { it.state != "quiet" }
        return MissionsResponse(live, quiet.map { it.name })
    }
}
