package dev.herdertower.phone.ui

import dev.herdertower.phone.api.Dependent
import dev.herdertower.phone.api.ImpactReport
import dev.herdertower.phone.api.ImpactThing
import dev.herdertower.phone.api.Pr
import dev.herdertower.phone.api.PrImpact
import dev.herdertower.phone.api.PrScore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImpactModelTest {
    private val rename = ImpactThing(kind = "function", name = "formatTotal", change = "renamed", renamedTo = "formatAmount", file = "src/money.ts")
    private val web = Dependent(repo = "shop-web", file = "src/cart/Total.tsx", line = 4, uses = "formatTotal", within = "renderTotal", text = "formatTotal(cents)")

    @Test fun whatItChangesInTheTowersWords() {
        assertEquals("renames formatTotal to formatAmount", ImpactModel.said(rename))
        assertEquals("removes formatTotal", ImpactModel.said(rename.copy(change = "removed", renamedTo = null)))
        assertEquals("changes the signature of Cart", ImpactModel.said(ImpactThing(kind = "type", name = "Cart", change = "signature")))
        assertEquals("renames the field clip_url to audio_url", ImpactModel.said(ImpactThing(kind = "field", name = "clip_url", change = "renamed", renamedTo = "audio_url")))
        assertEquals("removes the route /v1/clips", ImpactModel.said(ImpactThing(kind = "route", name = "/v1/clips", change = "removed")))
        assertEquals("removes the column total", ImpactModel.said(ImpactThing(kind = "column", name = "total", change = "removed")))
        assertEquals("stops reading the config key CLIPS_CDN", ImpactModel.said(ImpactThing(kind = "config", name = "CLIPS_CDN", change = "unread")))
        assertEquals("removes the event clip.played", ImpactModel.said(ImpactThing(kind = "event", name = "clip.played", change = "removed")))
        // A rename without its new name reads as a removal, as the tower says it.
        assertEquals("removes formatTotal", ImpactModel.said(rename.copy(renamedTo = null)))
        assertEquals("shop-web src/cart/Total.tsx:4 in renderTotal", ImpactModel.said(web))
        assertEquals("shop-api src/index.ts:1", ImpactModel.said(Dependent(repo = "shop-api", file = "src/index.ts", line = 1, uses = "formatTotal")))
    }

    @Test fun whatBreaksAndWhoUsesIt() {
        // The tower's own example (src/orb/panel/prchip.rs).
        val one = PrImpact(things = listOf(rename), dependents = listOf(web), searched = listOf("shop-api", "shop-web"))
        assertTrue(ImpactModel.breaks(one))
        assertEquals(listOf("renames formatTotal to formatAmount, used by shop-web src/cart/Total.tsx:4 in renderTotal"), ImpactModel.breaking(one))
        assertEquals("Breaking change: renames formatTotal to formatAmount, used by shop-web src/cart/Total.tsx:4 in renderTotal", ImpactModel.line(one))
        assertNull(ImpactModel.quiet(one))
        // Another repo's use first, then one inside a function, before the PR's own repo or an import line; then "and N more".
        val own = Dependent(repo = "shop-api", file = "src/cart.ts", line = 9, uses = "formatTotal", within = "cartTotal")
        val import = Dependent(repo = "shop-admin", file = "src/a.ts", line = 1, uses = "formatTotal", text = "import { formatTotal } from 'shop-api'")
        val many = PrImpact(repo = "shop-api", things = listOf(rename), dependents = listOf(own, import, web), searched = listOf("shop-api", "shop-web", "shop-admin"))
        assertEquals(listOf(web, import, own), ImpactModel.ordered(many, many.dependents))
        assertEquals(listOf("renames formatTotal to formatAmount, used by shop-web src/cart/Total.tsx:4 in renderTotal and 2 more"), ImpactModel.breaking(many))
        // A reviewer's "breaks" report is its own line; more than two breaks: "; and N more" on the line.
        val field = ImpactThing(kind = "field", name = "total", change = "renamed", renamedTo = "amount")
        val reader = Dependent(repo = "shop-app", file = "lib/cart.dart", line = 30, uses = "total", within = "build")
        val reported = many.copy(things = listOf(rename, field), dependents = many.dependents + reader,
            reports = listOf(ImpactReport("clean", "an older check"), ImpactReport("breaks", "shop-web's cart tests fail: 3 of 41")))
        assertEquals(
            listOf("renames formatTotal to formatAmount, used by shop-web src/cart/Total.tsx:4 in renderTotal and 2 more",
                "renames the field total to amount, used by shop-app lib/cart.dart:30 in build", "shop-web's cart tests fail: 3 of 41"),
            ImpactModel.breaking(reported),
        )
        assertTrue(ImpactModel.line(reported).endsWith("in build; and 1 more"))
        // A break reported with no dependents found still breaks.
        assertTrue(ImpactModel.breaks(PrImpact(reports = listOf(ImpactReport("breaks", "x")))))
        // Every dependent, by what it uses, ordered the same way; one that uses no listed thing apart.
        val stray = Dependent(repo = "shop-x", file = "y.ts", line = 2, uses = "other")
        val groups = ImpactModel.byThing(reported.copy(dependents = reported.dependents + stray))
        assertEquals(listOf("renames formatTotal to formatAmount", "renames the field total to amount", "Other uses"), groups.map { it.first })
        assertEquals(listOf(web, import, own), groups[0].second)
    }

    @Test fun nothingOutsideDependsOnIt() {
        val none = PrImpact(searched = listOf("shop-api", "shop-web"))
        assertFalse(ImpactModel.breaks(none))
        assertEquals("Nothing outside depends on what it changes (no name others can use changes; searched 2 repos)", ImpactModel.quiet(none))
        val one = PrImpact(things = listOf(ImpactThing(kind = "type", name = "DrawingProps", change = "signature")), searched = listOf("landing"),
            reports = listOf(ImpactReport("clean", "an older check"), ImpactReport("clean", "type-checked landing-admin: no errors")))
        assertEquals("Nothing outside depends on what it changes (changes the signature of DrawingProps; searched 1 repo; checked: type-checked landing-admin: no errors)", ImpactModel.quiet(one))
        assertEquals("Nothing outside depends on what it changes (3 things others could use change; searched 0 repos)",
            ImpactModel.quiet(PrImpact(things = List(3) { ImpactThing(kind = "function", name = "f$it", change = "removed") })))
        assertNull(ImpactModel.quiet(null))
    }

    @Test fun theScoreStepsAsideOnlyWhenTheBreakIsAllItSays() {
        val breaks = PrImpact(things = listOf(rename), dependents = listOf(web))
        val onlyBreak = PrScore(needsReview = true, line = "Needs review: a breaking change (…)", flags = listOf("breaking"))
        assertTrue(ImpactModel.hidesScore(onlyBreak, breaks))
        assertFalse(ImpactModel.hidesScore(onlyBreak.copy(flags = listOf("deploy", "breaking")), breaks))
        assertFalse(ImpactModel.hidesScore(onlyBreak, PrImpact()))
        assertFalse(ImpactModel.hidesScore(onlyBreak, null))
        assertFalse(ImpactModel.hidesScore(null, breaks))
        // A screen reader hears the warning with the chip.
        val pr = Pr(key = "o/a#7", repo = "o/a", number = 7, title = "t", state = "open", impact = breaks)
        assertTrue(PrModel.describe(pr).endsWith("Breaking change: renames formatTotal to formatAmount, used by shop-web src/cart/Total.tsx:4 in renderTotal"))
    }
}
