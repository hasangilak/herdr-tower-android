package dev.herdertower.phone.ui

import dev.herdertower.phone.api.EdgeCase
import dev.herdertower.phone.api.Pr
import dev.herdertower.phone.api.PrCheck
import dev.herdertower.phone.api.PrValidation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidationModelTest {
    private fun check(id: String, status: String, what: String = "Check $id", proof: String? = null) =
        PrCheck(id = id, what = what, how = "npm test", evidence = "test output", status = status, proof = proof)

    @Test fun theRowsLineInTheTowersWords() {
        assertEquals("The planned check passed", ValidationModel.line(PrValidation(listOf(check("c1", "passed")))))
        assertEquals("Both planned checks passed", ValidationModel.line(PrValidation(listOf(check("c1", "passed"), check("c2", "passed")))))
        assertEquals("All 3 planned checks passed", ValidationModel.line(PrValidation(List(3) { check("c${it + 1}", "passed") })))
        val mixed = PrValidation(listOf(check("c1", "passed"), check("c2", "not_run"), check("c3", "failed"), check("c4", "planned")))
        assertEquals("Checks missing: c2 Check c2 (not run), c3 Check c3 (failed), c4 Check c4 (not reported yet) (1 of 4 passed)", ValidationModel.line(mixed))
        // What's cut to 60 characters, trailing space dropped, with an ellipsis.
        val long = check("c2", "not_run", what = "The total on dev matches the order, the tax and the shipping line by line")
        assertEquals("c2 The total on dev matches the order, the tax and the shipping…", ValidationModel.short(long))
        assertEquals("c1 Short", ValidationModel.short(check("c1", "passed", what = "Short")))
        // No validation, or no planned checks: no line.
        assertNull(ValidationModel.line(null))
        assertNull(ValidationModel.line(PrValidation()))
    }

    @Test fun itsColour() {
        assertEquals(ValidationModel.Tone.Passed, ValidationModel.tone(PrValidation(listOf(check("c1", "passed")))))
        assertEquals(ValidationModel.Tone.Failed, ValidationModel.tone(PrValidation(listOf(check("c1", "not_run"), check("c2", "failed")))))
        assertEquals(ValidationModel.Tone.Missing, ValidationModel.tone(PrValidation(listOf(check("c1", "passed"), check("c2", "not_run")))))
        assertEquals(ValidationModel.Tone.Missing, ValidationModel.tone(PrValidation(listOf(check("c1", "planned")))))
        assertEquals(ValidationModel.Tone.Missing, ValidationModel.tone(PrValidation()))
        assertEquals(ValidationModel.Tone.Failed, ValidationModel.tone(check("c1", "failed")))
        assertEquals(ValidationModel.Tone.Missing, ValidationModel.tone(check("c1", "whatever")))
    }

    @Test fun eachCheckAndEdgeCase() {
        assertEquals(listOf("passed", "failed", "not run", "not reported yet"), listOf("passed", "failed", "not_run", "planned").map { ValidationModel.statusWord(check("c1", it)) })
        assertEquals("Evidence: 12 passing", ValidationModel.evidence(check("c1", "passed", proof = " 12 passing ")))
        assertEquals("Evidence wanted: test output", ValidationModel.evidence(check("c1", "planned")))
        assertEquals("Evidence wanted: test output", ValidationModel.evidence(check("c1", "planned", proof = "  ")))
        assertEquals("c1, c3", ValidationModel.covers(EdgeCase("x", listOf("c1", "c3"))))
        assertEquals("Validation · 3", ValidationModel.checksHead(3))
        assertEquals("Edge cases · 2", ValidationModel.edgesHead(2))
        // A screen reader hears the line with the chip.
        val pr = Pr(key = "o/a#7", repo = "o/a", number = 7, title = "t", state = "open", validation = PrValidation(listOf(check("c1", "passed"))))
        assertTrue(PrModel.describe(pr).endsWith("The planned check passed"))
    }
}
