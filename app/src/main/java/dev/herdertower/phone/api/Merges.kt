package dev.herdertower.phone.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * A "Merge all N" the tower runs (herder-tower's `merges`): the PRs of a
 * merge card ([card], the decision's id), merged in order by the tower, one
 * once the one before landed; a PR that fell behind or conflicts is fixed by
 * its session first. [over]: every PR merged or passed over, or it stopped.
 */
@Serializable
data class MergeChain(
    val id: String = "",
    val card: String = "",
    val space: String? = null,
    /** Who confirmed it: `mac`, `phone:<device>`. */
    val by: String = "",
    val links: List<MergeLink> = emptyList(),
    @SerialName("started_ms") val startedMs: Long = 0,
    val stopped: Boolean = false,
    val over: Boolean = false,
) {
    /** Still merging: nothing more to do from the card but watch, or stop it. */
    val running: Boolean get() = !over && !stopped
}

/** One PR of a chain: where it stands ([step]) and the tower's line for it ("waiting for its checks"). */
@Serializable
data class MergeLink(
    val key: String = "",
    val number: Long = 0,
    val base: String = "",
    val step: MergeStepState = MergeStepState(),
    @SerialName("since_ms") val sinceMs: Long = 0,
    val fix: String? = null,
    val line: String = "",
)

/** `queued`, `waiting`, `merging`, `fixing`, `merged`, `skipped` or `back`, and why. */
@Serializable
data class MergeStepState(val step: String = "queued", val why: String? = null)

@Serializable
data class MergeChainResponse(val merge: MergeChain = MergeChain())

@Serializable
data class MergeAllRequest(val prs: List<String>)

object MergesApi {
    /** The state's `merges`, read on its own: a chain the app can't read is left out, never breaks the state. */
    fun parse(e: JsonElement?): List<MergeChain> {
        val list = (e as? JsonArray) ?: ((e as? JsonObject)?.get("merges") as? JsonArray) ?: return emptyList()
        return list.mapNotNull { runCatching { TowerJson.decodeFromJsonElement(MergeChain.serializer(), it) }.getOrNull() }
    }

    /** The chain of a merge card: the running one, else its latest. */
    fun of(chains: List<MergeChain>, card: String): MergeChain? =
        chains.filter { it.card == card && card.isNotBlank() }.let { l -> l.lastOrNull { it.running } ?: l.maxByOrNull { it.startedMs } }

    /** A link's words: the tower's line, else its step ("#842: waiting"). */
    fun line(l: MergeLink): String = "#${l.number}: " + l.line.ifBlank {
        when (l.step.step) {
            "queued" -> "its turn hasn't come"
            "waiting" -> "waiting" + (l.step.why?.let { " for $it" } ?: "")
            "merging" -> "merging"
            "fixing" -> "being fixed" + (l.step.why?.let { ": $it" } ?: "")
            "merged" -> "merged"
            "skipped" -> "passed over" + (l.step.why?.let { ": $it" } ?: "")
            "back" -> "yours: " + (l.step.why ?: "it can't be merged without you")
            else -> l.step.step
        }
    }

    /** The chain's head line: "Merging in order · 1 of 2 merged", "Merged all 2", "Stopped: #842 is yours", "Stopped by you". */
    fun head(c: MergeChain): String {
        val merged = c.links.count { it.step.step == "merged" }
        val back = c.links.firstOrNull { it.step.step == "back" }
        return when {
            back != null -> "Stopped: #${back.number} is yours"
            c.stopped -> "Stopped: $merged of ${c.links.size} merged"
            c.over -> if (merged == c.links.size) "Merged all ${c.links.size}" else "Done: $merged of ${c.links.size} merged"
            else -> "The tower merges in order · $merged of ${c.links.size} merged"
        }
    }
}
