package dev.herdertower.phone.ui

import dev.herdertower.phone.api.MergeConfirm
import dev.herdertower.phone.api.MergeResult
import dev.herdertower.phone.api.Pr
import dev.herdertower.phone.api.TowerApiException
import dev.herdertower.phone.api.UnreachableException

/** What a PR chip says, in words (the chip and the merge sheet draw these). */
object PrModel {
    enum class Tone { Pending, Good, Bad, Quiet }

    /**
     * Where it stands: open, waiting for you, a draft, its review still
     * running, being fixed by its session (conflicts, or behind its base),
     * merged, closed. Only Waiting is the user's to merge.
     */
    enum class Phase { Open, Waiting, Draft, InReview, Fixing, Merged, Closed, Unknown }

    fun phase(pr: Pr): Phase = when (pr.state) {
        "merged" -> Phase.Merged
        "closed" -> Phase.Closed
        "open" -> when {
            pr.draft -> Phase.Draft
            fixing(pr) -> Phase.Fixing
            pr.review?.running == true -> Phase.InReview
            pr.waiting -> Phase.Waiting
            else -> Phase.Open
        }
        else -> Phase.Unknown
    }

    /** What keeps it from merging that its session fixes, as the tower reads it: `conflicts`, `behind`, or null. */
    fun trouble(pr: Pr): String? = dev.herdertower.phone.api.PrsApi.trouble(pr)

    /** Its session is fixing it now (asked, not back with the user): the tower's, not the user's, until it merges cleanly. */
    fun fixing(pr: Pr): Boolean = dev.herdertower.phone.api.PrsApi.fixing(pr)

    /** "Being fixed by eyzflow: resolving its conflicts with dev.", or once it came back "Not fixed: … Yours to look at."; null otherwise. */
    fun fixLine(pr: Pr): String? {
        val f = pr.fix ?: return null
        if (!pr.open || pr.draft) return null
        f.back?.takeIf { it.isNotBlank() }?.let { return if (trouble(pr) != null) "Not fixed: ${it.trimEnd('.')}. Yours to look at." else null }
        if (!fixing(pr)) return null
        val who = f.toName.ifBlank { f.to }.ifBlank { "its session" }
        val base = pr.base.ifBlank { "its base" }
        val doing = if (f.what == "conflicts" || trouble(pr) == "conflicts") "resolving its conflicts with $base" else "bringing it up to date with $base"
        return "Being fixed by $who: $doing."
    }

    /** Ready for the user's merge: open, not a draft, its review over, nothing its session is fixing. */
    fun readyForUser(pr: Pr): Boolean = dev.herdertower.phone.api.PrsApi.readyForUser(pr)

    /** "PR #12 · owner/app". */
    fun heading(pr: Pr): String = buildString {
        append(if (pr.number > 0) "PR #${pr.number}" else "PR")
        if (pr.repo.isNotBlank()) append(" · ${pr.repo}")
    }

    /** "dev → main". */
    fun branches(pr: Pr): String = when {
        pr.base.isBlank() -> ""
        pr.head.isBlank() -> "into ${pr.base}"
        else -> "${pr.head} → ${pr.base}"
    }

    /** The checks in a few words, and how they look. */
    fun checks(pr: Pr): Pair<String, Tone> {
        val c = pr.checks
        return when (c.state) {
            "passing" -> (if (c.total > 0) "Checks passing (${c.total})" else "Checks passing") to Tone.Good
            "failing" -> {
                val names = c.failing.take(2).joinToString(", ")
                val more = (c.failing.size - 2).takeIf { it > 0 }?.let { " and $it more" }.orEmpty()
                (if (names.isEmpty()) "Checks failing" else "Failing: $names$more") to Tone.Bad
            }
            "pending" -> {
                val done = (c.total - c.pending.size).coerceAtLeast(0)
                (if (c.total > 0) "Checks running ($done of ${c.total} done)" else "Checks running") to Tone.Pending
            }
            "none" -> "No checks" to Tone.Quiet
            else -> "Checks not known yet" to Tone.Quiet
        }
    }

    /** Whether it merges cleanly; null once it's merged or closed (it doesn't matter then). */
    fun mergeable(pr: Pr): Pair<String, Tone>? = if (!pr.open) null else when (pr.mergeable) {
        "mergeable" -> "No conflicts" to Tone.Good
        "conflicting" -> "Conflicts with ${pr.base.ifBlank { "its base" }}" to Tone.Bad
        else -> "Checking for conflicts" to Tone.Pending
    }

    /** "Merged into main from the Mac", "Closed". */
    fun closedLine(pr: Pr): String? = when (pr.state) {
        "merged" -> "Merged" + (if (pr.base.isNotBlank()) " into ${pr.base}" else "") + when {
            pr.mergedBy == null -> ""
            pr.mergedBy == "mac" -> " from the Mac"
            pr.mergedBy == "github" -> " on GitHub"
            pr.mergedBy == "tower" -> " by the tower"
            pr.mergedBy.startsWith("phone:") -> " from a phone"
            else -> ""
        }
        "closed" -> "Closed without merging"
        else -> null
    }

    /** The merged chip's pill: "Merged by the tower" when the tower merged it itself (auto-merge), else "Merged". */
    fun mergedPill(pr: Pr): String = if (pr.mergedBy == "tower") "Merged by the tower" else "Merged"

    /** The review score's line and whether it needs review (amber) or is low risk (cyan); null without a score. */
    fun score(s: dev.herdertower.phone.api.PrScore?): Pair<String, Boolean>? =
        s?.line?.takeIf { it.isNotBlank() }?.let { it to s.needsReview }

    /** The tower tried to merge it itself and couldn't: why. */
    fun autoFailed(pr: Pr): String? = pr.auto?.takeIf { it.state == "failed" && pr.open }?.let { "The tower couldn't merge it" + (it.why?.let { w -> ": $w" } ?: "") }

    /** Whether the Merge button shows (an open PR), and if it works: null, or why not. */
    fun mergeButton(pr: Pr): MergeButton? = when {
        !pr.open -> null
        pr.merge.can && pr.merge.blocked == null -> MergeButton(true, null)
        else -> MergeButton(false, pr.merge.blocked ?: "It can't be merged now.")
    }

    data class MergeButton(val enabled: Boolean, val why: String?)

    /** Branches whose merge ships to production (as the tower's `is_prod`). */
    fun isProd(base: String): Boolean = base.lowercase() in setOf("main", "master", "prod", "production", "release", "live")

    /** What a screen reader says for the whole chip. */
    fun describe(pr: Pr): String = buildList {
        add(heading(pr) + ": " + pr.title.ifBlank { "untitled" })
        branches(pr).takeIf { it.isNotEmpty() }?.let { add(it.replace("→", "into")) }
        when (phase(pr)) {
            Phase.Waiting -> add("waiting for you")
            Phase.Draft -> add("a draft")
            Phase.InReview -> add("its review still runs")
            Phase.Fixing -> add("being fixed by its session")
            else -> {}
        }
        closedLine(pr)?.let { add(it) } ?: run {
            add(checks(pr).first)
            mergeable(pr)?.let { add(it.first) }
        }
        score(pr.score)?.let { add(it.first) }
        ValidationModel.line(pr.validation)?.let { add(it) }
        pr.impact?.let { add(ImpactModel.line(it)) }
    }.joinToString(", ")

    /** The warnings the sheet shows: the tower's, and the production one first even if an older tower didn't send it. */
    fun warnings(base: String, given: List<String>): List<String> {
        val prod = isProd(base) && given.none { it.contains("into $base", ignoreCase = true) }
        return (if (prod) listOf("It merges into $base: that ships to production.") else emptyList()) + given
    }

    /** "Squash and merge", "Rebase and merge", "Merge commit". */
    fun method(m: String): String = when (m) {
        "squash" -> "Squash and merge"
        "rebase" -> "Rebase and merge"
        "merge" -> "Merge commit"
        else -> "The repo's default"
    }
}

/**
 * Merging a PR from the phone, as the tower wants it: look first (confirm),
 * then merge with that look's token; failing checks need a second, explicit
 * yes. Pure: the sheet runs the calls and feeds their results back in, so
 * every path is a test.
 */
sealed interface MergeStep {
    /** Asking the tower (and GitHub) what merging would do; [last] stays on screen meanwhile (no blank flicker). */
    data class Looking(val last: MergeConfirm? = null, val note: String? = null) : MergeStep

    /** What it would do, with the token; [at] is when it came (this phone's clock). */
    data class Ready(val confirm: MergeConfirm, val at: Long, val note: String? = null) : MergeStep {
        /** Merge can't go ahead at all: why. */
        val blocked: String? get() = confirm.blocked ?: if (!confirm.can) "It can't be merged now." else null
    }

    /** Checks fail: the second yes ("the failure is unrelated") before it merges. */
    data class Override(val confirm: MergeConfirm, val at: Long) : MergeStep

    data class Merging(val confirm: MergeConfirm, val override: Boolean) : MergeStep

    data class Merged(val pr: Pr?) : MergeStep

    /** It didn't go: why, and the details last seen. Retrying always looks again (a token is used up by any try). */
    data class Failed(val message: String, val last: MergeConfirm?) : MergeStep

    companion object {
        /** A token is good for two minutes: a little less here, the clocks and the trip there differ. */
        const val TOKEN_MS = 2 * 60_000L - 10_000L

        fun looked(c: MergeConfirm, now: Long, note: String? = null): MergeStep = Ready(c, now, note)

        fun lookFailed(e: Throwable, last: MergeConfirm?): MergeStep = Failed(
            when {
                e is TowerApiException && e.code == "no_pr" -> "The tower doesn't know this PR any more."
                e is TowerApiException && e.code == "github" -> "The Mac couldn't ask GitHub: ${e.message}"
                e is UnreachableException -> "Couldn't reach the tower: ${e.message}"
                else -> e.message ?: "Couldn't look at it."
            },
            last,
        )

        /** Merge tapped on [s]: the second yes when checks fail, else merging; an old look is renewed first. */
        fun mergeTapped(s: Ready, now: Long): MergeStep = when {
            s.blocked != null -> s
            now - s.at > TOKEN_MS -> Looking(s.confirm, "That look was over two minutes old: looked again. Check it, then merge.")
            s.confirm.needsOverride != null -> Override(s.confirm, s.at)
            else -> Merging(s.confirm, false)
        }

        /** The second yes. */
        fun overrideTapped(s: Override, now: Long): MergeStep =
            if (now - s.at > TOKEN_MS) Looking(s.confirm, "That look was over two minutes old: looked again. Check it, then merge.")
            else Merging(s.confirm, true)

        fun merged(r: MergeResult): MergeStep = Merged(r.pr)

        fun mergeFailed(e: Throwable, s: Merging): MergeStep = Failed(
            when {
                e is TowerApiException -> when (e.code) {
                    "bad_token" -> "The go-ahead ran out or was used already. Look again, then merge."
                    "changed" -> "New commits came since you looked: look again before merging."
                    "needs_override" -> "Checks fail: it needs your second yes. Look again."
                    "blocked" -> e.message.ifBlank { "It can't be merged now." }
                    "merge_failed" -> "GitHub didn't merge it: ${e.message}"
                    else -> e.message
                }
                // The request may have reached the Mac: looking again shows whether it merged.
                e is UnreachableException -> "Couldn't reach the tower: ${e.message} Look again to see whether it merged."
                else -> e.message ?: "It didn't merge."
            },
            s.confirm,
        )
    }
}
