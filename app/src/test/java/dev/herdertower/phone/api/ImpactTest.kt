package dev.herdertower.phone.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImpactTest {
    private val impact = """{"repo": "shop-api",
        "things": [{"kind": "function", "name": "formatTotal", "change": "renamed", "renamed_to": "formatAmount", "file": "src/money.ts"},
                   {"kind": "field", "name": "total", "change": "removed", "file": "src/api/cart.ts"}],
        "dependents": [{"repo": "shop-web", "file": "src/cart/Total.tsx", "line": 4, "uses": "formatTotal", "within": "renderTotal", "text": "formatTotal(cents)"},
                       {"repo": "shop-app", "file": "lib/cart.dart", "line": 30, "uses": "total", "text": "json['total']"}],
        "searched": ["shop-api", "shop-web", "shop-app"],
        "reports": [{"verdict": "breaks", "text": "shop-web's tests fail", "by": "t61", "at_ms": 1700}],
        "sha": "0dcd66e", "checked_ms": 1600}"""

    @Test fun aPrsImpactWhereverItsScoreIs() {
        val pr = TowerJson.decodeFromString(PrResponse.serializer(), """{"pr": {"key": "o/a#7", "impact": $impact}}""").pr!!
        val i = pr.impact!!
        assertEquals("shop-api", i.repo)
        assertEquals("formatAmount", i.things[0].renamedTo)
        assertNull(i.things[1].renamedTo)
        assertEquals(30, i.dependents[1].line)
        assertEquals("", i.dependents[1].within)
        assertEquals(listOf("shop-api", "shop-web", "shop-app"), i.searched)
        assertEquals(ImpactReport("breaks", "shop-web's tests fail", "t61", 1700), i.reports[0])
        assertEquals(1600L, i.checkedMs)
        // The pr event and a merge decision's PRs carry it too; a tower before it sends none.
        assertEquals(2, TowerJson.decodeFromString(PrEvent.serializer(), """{"op": "update", "pr": {"key": "o/a#7", "impact": $impact}}""").pr.impact!!.dependents.size)
        val d = TowerJson.decodeFromString(Decision.serializer(), """{"id": "d1", "kind": "merge", "prs": [{"repo": "o/a", "number": 7, "impact": $impact}]}""")
        assertEquals("formatTotal", d.prs[0].impact!!.things[0].name)
        assertNull(TowerJson.decodeFromString(PrResponse.serializer(), """{"pr": {"key": "o/a#8"}}""").pr!!.impact)
    }
}
