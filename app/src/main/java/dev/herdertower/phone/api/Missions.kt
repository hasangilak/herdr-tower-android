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
) {
    val label: String get() = name.ifBlank { address }
}

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
) {
    /** Its own tower takes what is asked here (else the main tower does, with the space named). */
    val ownTower: Boolean get() = tower?.status == "ready" || tower?.status == "working"
}

/** A space's own tower (its own brain): [status] `ready`, `working`, `starting`, `blocked` or `off`. */
@Serializable
data class MissionTower(val name: String = "", val status: String = "", val address: String? = null)

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
        m.sessions.any { it.status == "working" || it.status == "blocked" } -> Mood.Working
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
