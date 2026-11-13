package dev.herdertower.phone.ui

import dev.herdertower.phone.api.TrackChange
import dev.herdertower.phone.api.TrackRelease
import java.time.Instant
import java.time.ZoneId

/**
 * A space's release track in words, as the Mac's space panel says it
 * (src/releases.rs `when`, `Change::detail`; src/orb/panel/space.rs
 * `stage_word`, `release_card`), with times in the phone's own local time.
 */
object ReleaseModel {
    enum class Tone { Prod, Dev, Rule, Quiet }

    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /** "14:20" today, "Nov 12 14:20" another day. */
    fun whenText(ms: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val t = Instant.ofEpochMilli(ms).atZone(zone)
        val n = Instant.ofEpochMilli(now).atZone(zone)
        val hm = "%02d:%02d".format(t.hour, t.minute)
        return if (t.toLocalDate() == n.toLocalDate()) hm else "${MONTHS[t.monthValue - 1]} ${t.dayOfMonth} $hm"
    }

    /** The chip: open, merged into dev, on dev, on main, closed. */
    fun stageWord(stage: String, dev: String, prod: String): String = when (stage) {
        "open" -> "open"
        "merged" -> "merged into $dev"
        "deployed" -> "on $dev"
        "released" -> "on $prod"
        else -> "closed"
    }

    /** On prod in cyan, on dev in amber, open or closed quiet. */
    fun stageTone(stage: String): Tone = when (stage) {
        "released" -> Tone.Prod
        "merged", "deployed" -> Tone.Dev
        else -> Tone.Quiet
    }

    /** Beside the chip: "since 14:20 · release #840", "deployed 15:42", "merged 15:30, not deployed yet", "into dev". */
    fun detail(c: TrackChange, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        fun at(ms: Long?) = ms?.let { whenText(it, now, zone) }.orEmpty()
        return when (c.stage) {
            "open" -> "into ${c.base}"
            "merged" -> if (c.mergedMs != null) "merged ${at(c.mergedMs)}, not deployed yet" else "merged, not deployed yet"
            "deployed" -> "deployed ${at(c.deployedMs)}".trimEnd()
            "released" -> if (c.skippedDev) "since ${at(c.releasedMs)} · skipped dev"
                else "since ${at(c.releasedMs)}" + (c.release?.let { " · release #$it" } ?: "")
            else -> "not merged"
        }
    }

    /** A line's colour: "On main since …" cyan, "On dev, not on main …" amber, the dev-then-prod rule rose, the rest muted. */
    fun lineTone(line: String, dev: String, prod: String): Tone = when {
        line.startsWith("On $prod since") -> Tone.Prod
        line.startsWith("On $dev,") -> Tone.Dev
        line.contains("against the dev-then-prod rule") -> Tone.Rule
        else -> Tone.Quiet
    }

    /** "landed on main 14:20" (cyan) or "under way" (amber): the words, and whether it landed. */
    fun landed(r: TrackRelease, now: Long, zone: ZoneId = ZoneId.systemDefault()): Pair<String, Boolean> = when {
        r.state == "merged" && r.mergedMs != null -> "landed on ${r.base} ${whenText(r.mergedMs, now, zone)}" to true
        r.state == "merged" -> "landed on ${r.base}" to true
        else -> "under way" to false
    }

    /** "carried #829, #830", or that it carries nothing of this space yet. */
    fun carried(r: TrackRelease): String =
        "carried " + if (r.carried.isEmpty()) "nothing of this space yet" else r.carried.joinToString(", ") { "#$it" }

    /** The card's note: "from GitHub, 14:20". */
    fun checked(ms: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String = "from GitHub, ${whenText(ms, now, zone)}"
}
