package dev.herdertower.phone.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One way a PR is validated for sure (docs/mobile-api.md, "Validation"):
 * [what] it shows, [how] (the exact test, command or live step), the
 * [evidence] it produces. On a real PR, [status] `planned`, `passed`,
 * `failed` or `not_run`, with the [proof] its session gave, [by] whom and
 * when.
 */
@Serializable
data class PrCheck(
    val id: String = "",
    val what: String = "",
    val how: String = "",
    val evidence: String = "",
    val status: String = "planned",
    val proof: String? = null,
    val by: String? = null,
    @SerialName("at_ms") val atMs: Long? = null,
)

/** An edge case, and the checks (by id) that cover it. */
@Serializable
data class EdgeCase(val case: String = "", val checks: List<String> = emptyList())

/** A PR a plan predicted: its planned checks as they stand, and its edge cases (absent on a PR no plan predicted). */
@Serializable
data class PrValidation(
    val checks: List<PrCheck> = emptyList(),
    @SerialName("edge_cases") val edgeCases: List<EdgeCase> = emptyList(),
)
