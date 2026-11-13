package dev.herdertower.phone.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ValidationTest {
    private val validation = """{"checks": [
        {"id": "c1", "what": "The migration rolls back cleanly", "how": "npm run db:migrate, then db:rollback", "evidence": "the output", "status": "passed",
         "proof": "migrate ok, rollback ok", "by": "s5", "at_ms": 1700},
        {"id": "c2", "what": "The total on dev matches the order", "how": "on dev, place one order", "evidence": "a screenshot", "status": "not_run",
         "proof": "not reported when its session finished its turn on the PR"},
        {"id": "c3", "what": "Refunds keep the old total", "how": "npm test -- refunds", "evidence": "test output", "status": "failed", "proof": "1 failing: refund total"}],
      "edge_cases": [{"case": "An order with no items", "checks": ["c2", "c3"]}]}"""

    @Test fun aRealPrsValidation() {
        val pr = TowerJson.decodeFromString(PrResponse.serializer(), """{"pr": {"key": "o/a#7", "repo": "o/a", "number": 7, "validation": $validation}}""").pr!!
        val v = pr.validation!!
        assertEquals(listOf("passed", "not_run", "failed"), v.checks.map { it.status })
        assertEquals("migrate ok, rollback ok", v.checks[0].proof)
        assertEquals("s5", v.checks[0].by)
        assertEquals(1700L, v.checks[0].atMs)
        assertNull(v.checks[1].by)
        assertEquals(listOf(EdgeCase("An order with no items", listOf("c2", "c3"))), v.edgeCases)
        // A PR no plan predicted has none.
        assertNull(TowerJson.decodeFromString(PrResponse.serializer(), """{"pr": {"key": "o/a#8"}}""").pr!!.validation)
        // The PR event carries it as well.
        val ev = TowerJson.decodeFromString(PrEvent.serializer(), """{"op": "update", "pr": {"key": "o/a#7", "validation": $validation}}""")
        assertEquals(3, ev.pr.validation!!.checks.size)
    }

    @Test fun aMergeDecisionsPrsAndAPlansPredictedPrs() {
        val d = TowerJson.decodeFromString(Decision.serializer(), """{"id": "d1", "kind": "merge", "prs": [{"repo": "o/a", "number": 7, "base": "dev", "validation": $validation}]}""")
        assertEquals("c3", d.prs[0].validation!!.checks[2].id)
        val p = PlansApi.parse(
            TowerJson.parseToJsonElement(
                """{"id": "p3", "status": "ready", "prs": [{"n": 1, "repo": "eyzflow", "base": "dev",
                   "checks": [{"id": "c1", "what": "w", "how": "h", "evidence": "e", "status": "planned"}],
                   "edge_cases": [{"case": "Rolling back after rows exist", "checks": ["c1"]}]}]}""",
            ),
        )!!
        assertEquals(listOf(PrCheck("c1", "w", "h", "e", "planned")), p.prs[0].checks)
        assertEquals("Rolling back after rows exist", p.prs[0].edgeCases[0].case)
        // A plan from before validation: none, and nothing breaks.
        assertEquals(emptyList<PrCheck>(), PlansApi.parse(TowerJson.parseToJsonElement("""{"id": "p1", "prs": [{"n": 1}]}"""))!!.prs[0].checks)
    }
}
