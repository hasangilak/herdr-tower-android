package dev.herdertower.phone.ui

import dev.herdertower.phone.api.EdgeCase
import dev.herdertower.phone.api.PrCheck
import dev.herdertower.phone.api.PrValidation

/**
 * How a PR is validated, in the tower's own words (src/prscore.rs:
 * `Check::short`, `Check::status_word`, `Validation::line`): passed in cyan,
 * failed in rose, not run or not reported yet in amber.
 */
object ValidationModel {
    enum class Tone { Passed, Failed, Missing }

    /** "passed", "failed", "not run", "not reported yet" (planned, or anything else). */
    fun statusWord(c: PrCheck): String = when (c.status) {
        "passed" -> "passed"
        "failed" -> "failed"
        "not_run" -> "not run"
        else -> "not reported yet"
    }

    fun tone(c: PrCheck): Tone = when (c.status) {
        "passed" -> Tone.Passed
        "failed" -> Tone.Failed
        else -> Tone.Missing
    }

    /** "c2 The total on dev matches the order": its id and what it shows, cut to 60 characters. */
    fun short(c: PrCheck): String {
        val what = c.what.take(60).trimEnd()
        return "${c.id} $what${if (c.what.length > 60) "…" else ""}"
    }

    private fun missing(v: PrValidation) = v.checks.filter { it.status != "passed" }

    /** The checks not passed, as the merge warning names them: "c2 … (not run), c3 … (failed)". */
    fun missingLine(v: PrValidation): String = missing(v).joinToString(", ") { "${short(it)} (${statusWord(it)})" }

    /**
     * A PR's row: "All 3 planned checks passed", "Both planned checks
     * passed", "The planned check passed", or "Checks missing: c2 … (not
     * run) (1 of 3 passed)"; null without planned checks.
     */
    fun line(v: PrValidation?): String? {
        val n = v?.checks?.size ?: return null
        if (n == 0) return null
        val passed = n - missing(v).size
        return if (passed == n) {
            val which = when (n) {
                1 -> "The "
                2 -> "Both "
                else -> "All $n "
            }
            "${which}planned check${if (n == 1) "" else "s"} passed"
        } else {
            "Checks missing: ${missingLine(v)} ($passed of $n passed)"
        }
    }

    /** The row's colour: cyan when all passed, rose when one failed, amber otherwise. */
    fun tone(v: PrValidation): Tone = when {
        v.checks.isNotEmpty() && v.checks.all { it.status == "passed" } -> Tone.Passed
        v.checks.any { it.status == "failed" } -> Tone.Failed
        else -> Tone.Missing
    }

    /** What a check on a real PR has to show: the evidence its session gave, or what it should give. */
    fun evidence(c: PrCheck): String =
        c.proof?.trim()?.takeIf { it.isNotEmpty() }?.let { "Evidence: $it" } ?: "Evidence wanted: ${c.evidence}"

    /** The checks that cover an edge case: "c1, c2". */
    fun covers(e: EdgeCase): String = e.checks.joinToString(", ")

    /** "VALIDATION · 3", "EDGE CASES · 2": the Mac's headings (shown in capitals). */
    fun checksHead(n: Int) = "Validation · $n"
    fun edgesHead(n: Int) = "Edge cases · $n"
}
