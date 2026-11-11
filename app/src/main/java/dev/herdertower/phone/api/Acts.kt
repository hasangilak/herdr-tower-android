package dev.herdertower.phone.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A session an act opened; [label] is its name ready to show. */
@Serializable
data class ActSession(
    val address: String = "",
    val name: String = "",
    val label: String? = null,
    val worktree: String? = null,
    val branch: String? = null,
) {
    val shown: String get() = label?.takeIf { it.isNotBlank() } ?: name.ifBlank { address }
}

/** One thing the tower settled by itself (or was told), and why: a chip on the receipt. */
@Serializable
data class Assumption(val what: String = "", val value: String = "", val why: String? = null)

/** The request an act answered: its chat message's [at]. */
@Serializable
data class ActRequest(val at: Long = 0, val text: String = "")

/** Whether an act can be taken back now, and why not. */
@Serializable
data class ActUndo(val possible: Boolean = false, val why: String? = null)

/**
 * What the tower opened for a command, without asking (docs/mobile-api.md,
 * "Acts"): its one [line], the sessions, what it [assumed], and whether it
 * can be undone. [state] `done` or `undone`; [closing] what the undo did.
 */
@Serializable
data class Act(
    val id: String = "",
    val kind: String = "",
    @SerialName("at_ms") val atMs: Long = 0,
    val by: String? = null,
    val space: String? = null,
    val line: String = "",
    val sessions: List<ActSession> = emptyList(),
    val assumed: List<Assumption> = emptyList(),
    val request: ActRequest? = null,
    val state: String = "done",
    val undo: ActUndo = ActUndo(),
    val closing: String? = null,
    @SerialName("undone_ms") val undoneMs: Long? = null,
) {
    val undone: Boolean get() = state == "undone"
}

@Serializable
data class ActsResponse(val acts: List<Act> = emptyList())

@Serializable
data class ActResponse(val act: Act = Act())

@Serializable
data class UndoRequest(val force: Boolean = false)

@Serializable
data class KeptPath(val path: String = "", val why: String = "")

/** What an undo did: the sessions ended, the worktrees removed, those kept and why, whether the space went too. */
@Serializable
data class UndoResult(
    val act: Act = Act(),
    val closing: String? = null,
    @SerialName("sessions_ended") val sessionsEnded: List<String> = emptyList(),
    @SerialName("worktrees_removed") val worktreesRemoved: List<String> = emptyList(),
    @SerialName("kept_worktrees") val keptWorktrees: List<KeptPath> = emptyList(),
    @SerialName("space_closed") val spaceClosed: Boolean = false,
)

/** A session an undo would lose work in (`409 held`): with force it is ended all the same. */
@Serializable
data class HeldSession(val address: String = "", val name: String = "", val why: String = "")

/** Whether an answer can become a standing rule, and the words that would ask for it. */
@Serializable
data class DecisionRule(val can: Boolean = false, val text: String? = null)

@Serializable
data class RuleRequest(val answer: String? = null)

@Serializable
data class RuleSent(val sent: String = "", val space: String? = null)

object ActsApi {
    /** A chip's words: "account orca"; the value alone when it names itself ("default model"). */
    fun chip(a: Assumption): String =
        if (a.what.isNotBlank() && a.value.contains(a.what, ignoreCase = true)) a.value
        else listOf(a.what, a.value).filter { it.isNotBlank() }.joinToString(" ")

    /** Session names come ready to show ("sandbox › mediaroom"); inside that space the space's part says nothing. */
    fun inSpace(name: String, space: String?): String =
        if (space != null && name.startsWith("$space › ")) name.removePrefix("$space › ") else name
}
