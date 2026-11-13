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
)

object PlansApi {
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
