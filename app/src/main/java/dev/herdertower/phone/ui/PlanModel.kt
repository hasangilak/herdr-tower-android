package dev.herdertower.phone.ui

import dev.herdertower.phone.api.Plan
import dev.herdertower.phone.api.PrScore
import dev.herdertower.phone.api.PredictedPr

/**
 * What the plan view says, in the Mac window's own words
 * (src/orb/panel/launch.rs: `plan_line`, `pr_rows`, `prs_head`,
 * `prs_note`). The phone only shows auto-merge; it doesn't switch it.
 */
object PlanModel {
    enum class Tone { Busy, Waits, Ready, Failed, Closed }

    /** How the plan stands: a line, its tone, and a word for the spaces list. */
    data class Status(val line: String, val tone: Tone, val word: String)

    /** The space it would open, or "New plan" before the draft names one. */
    fun name(p: Plan): String = p.draft?.space?.trim()?.takeIf { it.isNotEmpty() } ?: "New plan"

    /** "page" or "whiteboard": what it is published as. */
    fun form(p: Plan): String = if (p.form == "whiteboard") "whiteboard" else "page"

    fun status(p: Plan): Status = when {
        p.status == "failed" -> Status(
            p.why.trim().trimEnd('.').takeIf { it.isNotEmpty() }?.let { "The plan didn't come: $it." } ?: "The plan didn't come.",
            Tone.Failed, "failed",
        )
        p.status == "closed" -> Status("This plan is closed.", Tone.Closed, "closed")
        p.status == "ready" -> Status(
            if (!p.url.isNullOrBlank()) "Ready: a ${form(p)} on your claude.ai account (private)." else "Ready, not published yet.",
            Tone.Ready, "ready",
        )
        p.blocked -> Status(
            "The planner waits on a question in its terminal on the Mac (publishing the plan may ask for your go-ahead). Answer it there.",
            Tone.Waits, "waits",
        )
        p.planner == "starting" -> Status("The tower's brain is starting the planner…", Tone.Busy, "starting")
        else -> Status("The planner is writing the plan. It reads the repos first: about five minutes.", Tone.Busy, "writing")
    }

    /** What else to know: a question waiting once it's ready, the planner ended, how often it was revised. */
    fun notes(p: Plan): List<String> = buildList {
        if (p.blocked && p.status == "ready") add("The planner waits on a question in its terminal on the Mac. Answer it there.")
        if (p.status != "failed" && p.why.isNotBlank()) add(p.why.trim())
        if (p.status == "ready") when (p.revision) {
            0 -> {}
            1 -> add("The planner changed the draft once.")
            else -> add("The planner changed the draft ${p.revision} times.")
        }
    }

    /** The spaces list's line: how it stands, then its PRs when there are some. */
    fun rowLine(p: Plan, scores: Boolean): String {
        val st = when (status(p).tone) {
            Tone.Failed -> status(p).line
            Tone.Waits -> "Waits on a question in the Mac's terminal"
            Tone.Busy -> "The planner is writing it"
            Tone.Ready -> "Ready to read"
            Tone.Closed -> "Closed"
        }
        return if (scores && p.prs.isNotEmpty()) "$st · ${prsHead(p.prs)}" else st
    }

    /** "2 pull requests, 1 needs review", "1 pull request, all low risk". */
    fun prsHead(prs: List<PredictedPr>): String {
        val n = prs.size
        val review = prs.count { it.score?.needsReview == true }
        val many = if (n == 1) "1 pull request" else "$n pull requests"
        return when {
            review == 0 -> "$many, all low risk"
            review == n && n == 1 -> "$many, needs review"
            review == n -> "$many, all need review"
            else -> "$many, $review need${if (review == 1) "s" else ""} review"
        }
    }

    /** "PR 1 · eyz-customer-support → dev". */
    fun head(p: PredictedPr): String = "PR ${p.n} · ${p.repo} → ${p.base}"

    /** Its files in a line: the first three, then "and N more"; null with none. */
    fun files(p: PredictedPr): String? = when (val n = p.files.size) {
        0 -> null
        in 1..3 -> p.files.joinToString(", ")
        else -> p.files.take(3).joinToString(", ") + " and ${n - 3} more"
    }

    /** The badge: "Needs review" (amber) or "Low risk" (cyan), and which; null without a score. */
    fun badge(s: PrScore?): Pair<String, Boolean>? = s?.let { (if (it.needsReview) "Needs review" else "Low risk") to it.needsReview }

    /** Why, as the tower scored it: its line after the first ": ". */
    fun reason(s: PrScore?): String? = s?.line?.substringAfter(": ", s.line)?.trim()?.takeIf { it.isNotEmpty() }

    /** "After PR 1", "After PR 1, PR 2"; "Opened: o/r#12" once the real PR opened. */
    fun prNotes(p: PredictedPr): List<String> = buildList {
        if (p.after.isNotEmpty()) add("After PR " + p.after.joinToString(", PR "))
        p.pr?.takeIf { it.isNotBlank() }?.let { add("Opened: $it") }
    }

    /** Branches the tower can merge into itself (as its `is_dev`). */
    fun isDev(base: String): Boolean = base.trim().lowercase() in setOf("dev", "develop", "development")

    /** Who merges which once they open, from the state's `auto_merge`: the Mac window's words. */
    fun mergeNote(prs: List<PredictedPr>, autoMerge: Boolean): String {
        val lowIntoDev = prs.any { it.score?.needsReview == false && isDev(it.base) }
        return when {
            !lowIntoDev -> "Each one comes to you to merge."
            autoMerge -> "The tower merges the low-risk ones into dev itself, once their checks pass and their reviewer finds nothing. Those that need review, and any merge into main or prod, come to you."
            else -> "Each one comes to you to merge: auto-merge of low-risk PRs into dev is off (Settings on the Mac)."
        }
    }
}
