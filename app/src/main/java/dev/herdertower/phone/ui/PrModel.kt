package dev.herdertower.phone.ui

import dev.herdertower.phone.api.MergeConfirm
import dev.herdertower.phone.api.MergeResult
import dev.herdertower.phone.api.Pr
import dev.herdertower.phone.api.TowerApiException
import dev.herdertower.phone.api.UnreachableException

/** What a PR chip says, in words (the chip and the merge sheet draw these). */
object PrModel {
    enum class Tone { Pending, Good, Bad, Quiet }

    /** Where it stands: open, waiting for you, a draft, merged, closed. */
    enum class Phase { Open, Waiting, Draft, Merged, Closed, Unknown }

    fun phase(pr: Pr): Phase = when (pr.state) {
        "merged" -> Phase.Merged
        "closed" -> Phase.Closed
        "open" -> when {
            pr.draft -> Phase.Draft
            pr.waiting -> Phase.Waiting
            else -> Phase.Open
        }
        else -> Phase.Unknown
    }

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
            pr.mergedBy.startsWith("phone:") -> " from a phone"
            else -> ""
        }
        "closed" -> "Closed without merging"
        else -> null
    }

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
            else -> {}
        }
        closedLine(pr)?.let { add(it) } ?: run {
            add(checks(pr).first)
            mergeable(pr)?.let { add(it.first) }
        }
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
