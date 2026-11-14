package dev.herdertower.phone.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A pull request one of the tower's sessions opened (`GET /v1/prs`, a chat
 * message of role `pr`, event `pr`): its chip in the chat. See
 * herder-tower's docs/mobile-api.md, "PRs".
 */
@Serializable
data class Pr(
    /** `owner/repo#number`: its id everywhere. */
    val key: String = "",
    val repo: String = "",
    val number: Long = 0,
    val url: String = "",
    val title: String = "",
    /** The branch it merges into, and from. */
    val base: String = "",
    val head: String = "",
    @SerialName("head_sha") val headSha: String = "",
    /** `open`, `merged` or `closed`; empty until GitHub has been asked. */
    val state: String = "",
    val draft: Boolean = false,
    /** `mergeable`, `conflicting` or `unknown` (GitHub still working it out). */
    val mergeable: String = "",
    val checks: PrChecks = PrChecks(),
    /** The session that opened it (address), its name and space. */
    val session: String = "",
    val name: String = "",
    val space: String? = null,
    /** Its session ended its turn on it, or asked the user to merge. */
    val waiting: Boolean = false,
    @SerialName("found_ms") val foundMs: Long = 0,
    @SerialName("checked_ms") val checkedMs: Long = 0,
    @SerialName("closed_ms") val closedMs: Long? = null,
    /** `mac`, `phone:<device>`, `tower` (its own merge of a low-risk PR) or `github`, once merged. */
    @SerialName("merged_by") val mergedBy: String? = null,
    /** The last time the Mac couldn't ask GitHub about it. */
    val error: String? = null,
    /** Whether the Merge button works now, and what it warns about. */
    val merge: PrGate = PrGate(),
    /** Whether it needs a human code review (a tower with `scores`); null on a PR found before scores. */
    val score: PrScore? = null,
    /** The tower's own merge of it (auto-merge on): merged, or failed and why. */
    val auto: PrAuto? = null,
    /** Its plan's checks as they stand, and its edge cases (a tower with `validation`); null on a PR no plan predicted. */
    val validation: PrValidation? = null,
    /** Who outside depends on what it changes (a tower with `impact`). */
    val impact: PrImpact? = null,
    /** GitHub's word on its merge (`clean`, `behind`, `dirty`, `blocked`, `unknown`, …), lowercased; "" from an older tower. */
    @SerialName("merge_state") val mergeState: String = "",
    /** Its reviewer session's review, short (a tower with `reviews`); null when it has none. */
    val review: PrReview? = null,
    /** Its session asked to fix its conflicts (or bring it up to date with its base), and how that goes; null when nobody was. */
    val fix: PrFix? = null,
) {
    val open: Boolean get() = state == "open"

    /** `owner/repo` and the number, from the fields or else the key. */
    val ref: PrRef? get() = PrRef.of(repo, number) ?: PrRef.parse(key)

    /** Its key as the tower writes it, also when only the parts came. */
    val id: String get() = key.ifBlank { if (repo.isNotBlank() && number > 0) "$repo#$number" else "" }
}

/**
 * A PR's review score (docs/mobile-api.md, "Review scores"): it **needs
 * review** for a migration, a breaking change or anything on the escalate
 * list, else it is low risk. [line] is what the chip says.
 */
@Serializable
data class PrScore(
    @SerialName("needs_review") val needsReview: Boolean = false,
    val line: String = "",
    val reasons: List<String> = emptyList(),
    /** `migration`, `breaking`, `secrets`, `infrastructure`, `auth`, `deploy`, `data`, `money`, `dependencies`, `security`, `escalate`. */
    val flags: List<String> = emptyList(),
    /** The plan's prediction it carries (`p3#1`), when a plan predicted it. */
    val planned: String? = null,
    /** The head commit whose files were read. */
    val sha: String? = null,
)

/** A PR's review by its reviewer session: [status] `reviewing`, `changes`, `agreed`, `stuck` or `ended`; [line] what its chip says. */
@Serializable
data class PrReview(
    val status: String = "",
    val rounds: Int = 0,
    val critical: Int = 0,
    val reviewer: String? = null,
    @SerialName("reviewer_name") val reviewerName: String? = null,
    val line: String = "",
) {
    /** The review still runs: its reviewer reads it, or its session fixes what the review found. */
    val running: Boolean get() = status == "reviewing" || status == "changes"
}

/**
 * A PR its session was asked to fix (herder-tower's `fixes`): [what]
 * `conflicts` or `behind`, the session asked ([to], [toName]), when and how
 * many times; [back]: why it came back to the user (it is theirs then).
 */
@Serializable
data class PrFix(
    val what: String = "",
    val to: String = "",
    @SerialName("to_name") val toName: String = "",
    @SerialName("asked_ms") val askedMs: Long = 0,
    val tries: Int = 0,
    val back: String? = null,
)

/** The tower's own merge of a PR: `merged` or `failed`, and why. */
@Serializable
data class PrAuto(
    val state: String = "",
    val why: String? = null,
    val sha: String? = null,
    @SerialName("at_ms") val atMs: Long? = null,
)

@Serializable
data class PrChecks(
    /** `pending`, `passing`, `failing` or `none`. */
    val state: String = "",
    val failing: List<String> = emptyList(),
    val pending: List<String> = emptyList(),
    val passed: Int = 0,
    val total: Int = 0,
)

/** Whether it can be merged now (`merge` in a PR, and the confirmation's answer). */
@Serializable
data class PrGate(
    val can: Boolean = false,
    /** Why it can't be merged at all (merged, closed, a draft, conflicts). */
    val blocked: String? = null,
    /** Checks fail: merging needs a second, explicit yes ("the failure is unrelated"). */
    @SerialName("needs_override") val needsOverride: String? = null,
    val warnings: List<String> = emptyList(),
)

/** A PR by repo and number: the path of its endpoints (`/v1/prs/{owner}/{repo}/{number}`). */
data class PrRef(val owner: String, val repo: String, val number: Long) {
    val key: String get() = "$owner/$repo#$number"

    companion object {
        fun of(repo: String, number: Long): PrRef? {
            val parts = repo.split('/')
            return if (parts.size == 2 && parts.all { it.isNotBlank() } && number > 0) PrRef(parts[0], parts[1], number) else null
        }

        /** `owner/repo#12`. */
        fun parse(key: String): PrRef? {
            val repo = key.substringBefore('#', "")
            return of(repo, key.substringAfter('#', "").toLongOrNull() ?: return null)
        }
    }
}

@Serializable
data class PrsResponse(val prs: List<Pr> = emptyList())

@Serializable
data class PrResponse(val pr: Pr? = null)

/** `{"op": "open"|"update"|"closed", "pr": {…}}`. */
@Serializable
data class PrEvent(val op: String = "", val pr: Pr = Pr())

/**
 * Step one of a merge (`POST …/confirm`): what merging would do now, and a
 * token for this phone, this PR and the head commit shown, good once for two
 * minutes.
 */
@Serializable
data class MergeConfirm(
    val token: String = "",
    @SerialName("expires_ms") val expiresMs: Long = 0,
    val pr: Pr = Pr(),
    /** `merge`, `squash` or `rebase`: the repo's default. */
    val method: String = "",
    val can: Boolean = false,
    val blocked: String? = null,
    @SerialName("needs_override") val needsOverride: String? = null,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class MergeRequest(val token: String, val override: Boolean? = null)

@Serializable
data class MergeResult(val merged: Boolean = false, val pr: Pr? = null)

/** The PRs as the app keeps them: by key, the freshest copy of each. */
object PrsApi {
    /**
     * Whether [new] replaces [old]: a later look at GitHub does, and so does
     * a merged or closed one over an open one (it never opens again); an
     * older copy (a page of the chat loaded late) doesn't.
     */
    fun newer(old: Pr?, new: Pr): Boolean = when {
        old == null -> true
        old.open && !new.open && new.state.isNotBlank() -> true
        !old.open && old.state.isNotBlank() && new.open -> false
        else -> new.checkedMs >= old.checkedMs
    }

    /** [prs] with [more] in it, each where it's newer. A copy without a key is skipped. */
    fun merge(prs: Map<String, Pr>, more: List<Pr>): Map<String, Pr> {
        if (more.isEmpty()) return prs
        val out = LinkedHashMap(prs)
        for (p in more) {
            val k = p.id.ifBlank { continue }
            if (newer(out[k], p)) out[k] = if (p.key.isBlank()) p.copy(key = k) else p
        }
        return out
    }

    /** What keeps it from merging that its session fixes, as the tower reads it: `conflicts`, `behind`, or null. */
    fun trouble(pr: Pr): String? = when {
        !pr.open || pr.draft -> null
        pr.mergeable == "conflicting" || pr.mergeState == "dirty" -> "conflicts"
        pr.mergeState == "behind" -> "behind"
        else -> null
    }

    /** Its session is fixing it now (asked, not back with the user): the tower's, not the user's, until it merges cleanly. */
    fun fixing(pr: Pr): Boolean = pr.open && !pr.draft && pr.fix != null && pr.fix.back == null &&
        (trouble(pr) != null || pr.mergeable == "unknown" || pr.mergeState == "unknown")

    /**
     * Ready for the user's merge, as the tower decides it: open, not a
     * draft, no review of it still running (its reviewer reading it, or its
     * session fixing what the review found), and nothing its session is
     * fixing. Until then it waits for nobody: it is in no merge.
     */
    fun readyForUser(pr: Pr): Boolean = pr.open && !pr.draft && pr.review?.running != true && !fixing(pr)

    /** A chat message's PR, when it is a PR chip; a chip without one (not looked up yet) is just its key and title. */
    fun of(m: ChatMessage): Pr? = m.pr?.takeIf { m.role == "pr" || m.kind == "pr" }
}
