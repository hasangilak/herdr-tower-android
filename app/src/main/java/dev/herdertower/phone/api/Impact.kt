package dev.herdertower.phone.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Something a PR changes that others can use (docs/mobile-api.md,
 * "Breaking changes"): [kind] `function`, `type`, `route`, `field`,
 * `column`, `config` or `event`; [change] `removed`, `renamed`, `signature`
 * or `unread`.
 */
@Serializable
data class ImpactThing(
    val kind: String = "",
    val name: String = "",
    val change: String = "",
    @SerialName("renamed_to") val renamedTo: String? = null,
    val file: String = "",
)

/** A use of something the PR changes: its repo, file and line, the name it [uses], the function it is [within], the line's [text]. */
@Serializable
data class Dependent(
    val repo: String = "",
    val file: String = "",
    val line: Int = 0,
    val uses: String = "",
    val within: String = "",
    val text: String = "",
)

/** What the reviewer (or the PR's session) found running the dependents' tests: `breaks` or `clean`. */
@Serializable
data class ImpactReport(
    val verdict: String = "",
    val text: String = "",
    val by: String? = null,
    @SerialName("at_ms") val atMs: Long = 0,
)

/** Who outside depends on what a PR changes, as the tower found it ([repo]: the PR's own, by folder name). */
@Serializable
data class PrImpact(
    val repo: String = "",
    val things: List<ImpactThing> = emptyList(),
    val dependents: List<Dependent> = emptyList(),
    val searched: List<String> = emptyList(),
    val reports: List<ImpactReport> = emptyList(),
    val sha: String = "",
    @SerialName("checked_ms") val checkedMs: Long = 0,
)
