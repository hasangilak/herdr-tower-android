package dev.herdertower.phone.ui

import dev.herdertower.phone.api.Dependent
import dev.herdertower.phone.api.ImpactThing
import dev.herdertower.phone.api.PrImpact
import dev.herdertower.phone.api.PrScore

/**
 * Who outside depends on what a PR changes, in the tower's own words
 * (src/impact.rs: `Thing::said`, `Dependent::said`, `Impact::breaking`,
 * `Impact::line`), and when the Mac's chip hides the score line.
 */
object ImpactModel {
    /** "renames formatTotal to formatAmount", "removes the field translated_text", "stops reading the config key X". */
    fun said(t: ImpactThing): String {
        val what = when (t.kind) {
            "function", "type" -> t.name
            "route" -> "the route ${t.name}"
            "field" -> "the field ${t.name}"
            "column" -> "the column ${t.name}"
            "config" -> "the config key ${t.name}"
            "event" -> "the event ${t.name}"
            else -> "the ${t.kind} ${t.name}"
        }
        return when {
            t.change == "renamed" && t.renamedTo != null -> "renames $what to ${t.renamedTo}"
            t.change == "signature" -> "changes the signature of $what"
            t.change == "unread" -> "stops reading $what"
            else -> "removes $what"
        }
    }

    /** "shop-web src/cart/Total.tsx:12 in renderTotal". */
    fun said(d: Dependent): String = "${d.repo} ${d.file}:${d.line}" + if (d.within.isNotEmpty()) " in ${d.within}" else ""

    /** Something outside uses what it changes, or a break was reported. */
    fun breaks(i: PrImpact): Boolean = i.dependents.isNotEmpty() || i.reports.any { it.verdict == "breaks" }

    /** Another repo's use first, then a use inside a function (a call, not an import); otherwise as found. */
    fun ordered(i: PrImpact, users: List<Dependent>): List<Dependent> =
        users.sortedWith(compareBy({ it.repo.equals(i.repo, ignoreCase = true) }, { it.within.isEmpty() }))

    /** One line per break: "renames X to Y, used by repo file:line in fn and 2 more"; then each "breaks" report's text. */
    fun breaking(i: PrImpact): List<String> = buildList {
        for (t in i.things) {
            val users = ordered(i, i.dependents.filter { it.uses == t.name })
            val first = users.firstOrNull() ?: continue
            add("${said(t)}, used by ${said(first)}" + if (users.size > 1) " and ${users.size - 1} more" else "")
        }
        i.reports.filter { it.verdict == "breaks" }.forEach { add(it.text) }
    }

    /** The PR's line: the warning ("Breaking change: …"), or that nothing outside depends on it. */
    fun line(i: PrImpact): String {
        val b = breaking(i)
        if (b.isNotEmpty()) return "Breaking change: " + b.take(2).joinToString("; ") + if (b.size > 2) "; and ${b.size - 2} more" else ""
        val what = when (i.things.size) {
            0 -> "no name others can use changes"
            1 -> said(i.things[0])
            else -> "${i.things.size} things others could use change"
        }
        val clean = i.reports.lastOrNull { it.verdict == "clean" }?.let { "; checked: ${it.text}" } ?: ""
        return "Nothing outside depends on what it changes ($what; searched ${i.searched.size} repo${if (i.searched.size == 1) "" else "s"}$clean)"
    }

    /** The quiet line when nothing breaks; null when it breaks (the box says it) or without an impact. */
    fun quiet(i: PrImpact?): String? = i?.takeIf { !breaks(it) }?.let(::line)

    /** The score's line steps aside when its only reason is the break: the box says it all. */
    fun hidesScore(score: PrScore?, i: PrImpact?): Boolean =
        score != null && i != null && score.flags == listOf("breaking") && breaking(i).isNotEmpty()

    /** Every dependent, by what it uses (in the PR's order of things), each group ordered as the lines are. */
    fun byThing(i: PrImpact): List<Pair<String, List<Dependent>>> {
        val groups = i.things.mapNotNull { t ->
            ordered(i, i.dependents.filter { it.uses == t.name }).takeIf { it.isNotEmpty() }?.let { said(t) to it }
        }
        val known = i.things.map { it.name }.toSet()
        val other = ordered(i, i.dependents.filter { it.uses !in known })
        return if (other.isEmpty()) groups else groups + ("Other uses" to other)
    }
}
