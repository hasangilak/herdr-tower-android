package dev.herdertower.phone.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** What is on prod since one release: its time and the space's changes it carried. */
@Serializable
data class OnProd(@SerialName("since_ms") val sinceMs: Long = 0, val prs: List<Long> = emptyList())

/**
 * One of a space's PRs on its way to prod (docs/mobile-api.md, "Release
 * track"): [stage] `open`, `merged` (into dev), `deployed` (on dev),
 * `released` (on prod) or `closed`; [release] the release PR that carried
 * it; [skippedDev] it went straight into prod.
 */
@Serializable
data class TrackChange(
    val number: Long = 0,
    val title: String = "",
    val url: String = "",
    val base: String = "",
    val stage: String = "",
    @SerialName("merged_ms") val mergedMs: Long? = null,
    @SerialName("deployed_ms") val deployedMs: Long? = null,
    @SerialName("released_ms") val releasedMs: Long? = null,
    val release: Long? = null,
    @SerialName("skipped_dev") val skippedDev: Boolean = false,
    @SerialName("stage_line") val stageLine: String = "",
)

/** A release PR to prod: `open` (under way) or `merged` (landed at [mergedMs]), and the space's changes it [carried]. */
@Serializable
data class TrackRelease(
    val number: Long = 0,
    val title: String = "",
    val url: String = "",
    val head: String = "",
    val base: String = "",
    val state: String = "",
    @SerialName("merged_ms") val mergedMs: Long? = null,
    val carried: List<Long> = emptyList(),
)

/** Dev's last deployment. */
@Serializable
data class TrackDeployed(val sha: String = "", @SerialName("at_ms") val atMs: Long = 0, val environment: String = "")

/** One repo of a space: its [dev] and [prod] branches, the tower's [lines], its changes and releases. */
@Serializable
data class RepoTrack(
    val repo: String = "",
    val dev: String = "dev",
    val prod: String = "main",
    val lines: List<String> = emptyList(),
    @SerialName("on_prod") val onProd: List<OnProd> = emptyList(),
    @SerialName("on_dev_not_prod") val onDevNotProd: List<Long> = emptyList(),
    val changes: List<TrackChange> = emptyList(),
    val releases: List<TrackRelease> = emptyList(),
    val deployed: TrackDeployed? = null,
)

/** Where a space's changes stand, worked out from GitHub: [summary] ("prod 14:20 · 1 on dev") and each repo. */
@Serializable
data class ReleaseTrack(
    val space: String = "",
    val summary: String = "",
    @SerialName("checked_ms") val checkedMs: Long = 0,
    val repos: List<RepoTrack> = emptyList(),
)

object ReleasesApi {
    /**
     * The state's `releases` (by space): each track read on its own, so one
     * the app can't read is left out and never breaks the others or the state.
     */
    fun parse(e: JsonElement?): Map<String, ReleaseTrack> {
        val obj = e as? JsonObject ?: return emptyMap()
        return obj.mapNotNull { (space, v) ->
            (v as? JsonObject)?.let { runCatching { TowerJson.decodeFromJsonElement(ReleaseTrack.serializer(), it) }.getOrNull() }?.let { space to it }
        }.toMap()
    }

    /** A space's track, its name matched without regard to case (as the tower matches spaces). */
    fun of(tracks: Map<String, ReleaseTrack>, space: String): ReleaseTrack? =
        tracks[space] ?: tracks.entries.firstOrNull { it.key.equals(space, ignoreCase = true) }?.value
}
