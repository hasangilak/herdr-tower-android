package dev.herdertower.phone.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The PR shapes as herder-tower's docs/mobile-api.md ("PRs") writes them. */
class PrsTest {
    /** A PR with its review score and the tower's own merge, as the doc's "Review scores" writes them. */
    @Test fun aScoreAndTheTowersOwnMerge() {
        val p = TowerJson.decodeFromString(Pr.serializer(), """{"key":"eyzhub/eyz-mediaroom-serverless#1250","repo":"eyzhub/eyz-mediaroom-serverless","number":1250,
            "state":"open","base":"main",
            "score":{"needs_review":true,"line":"Needs review: a database migration (db/migrations/0042_tickets.sql)",
                     "reasons":["a database migration (db/migrations/0042_tickets.sql)"],"flags":["migration"],"planned":"p3#1","sha":"0dcd66e"},
            "auto":null}""")
        assertTrue(p.score!!.needsReview)
        assertEquals(listOf("migration"), p.score!!.flags)
        assertEquals("p3#1", p.score!!.planned)
        assertNull(p.auto)
        val merged = TowerJson.decodeFromString(Pr.serializer(), """{"key":"o/app#41","state":"merged","merged_by":"tower",
            "score":{"needs_review":false,"line":"Low risk: no migration, breaking change, secrets, infrastructure or auth"},
            "auto":{"state":"merged","why":"low risk, into dev; its checks passed and its reviewer agreed","sha":"9f2c","at_ms":1794238500000}}""")
        assertEquals("tower", merged.mergedBy)
        assertEquals("merged", merged.auto?.state)
        assertFalse(merged.score!!.needsReview)
        // A PR found before scores has none.
        assertNull(TowerJson.decodeFromString(Pr.serializer(), """{"key":"o/app#1"}""").score)
    }

    /** The doc's example, as written. */
    private val docPr = """
        {
          "key": "eyzhub/eyz-mediaroom-serverless#1250",
          "repo": "eyzhub/eyz-mediaroom-serverless", "number": 1250,
          "url": "https://github.com/eyzhub/eyz-mediaroom-serverless/pull/1250",
          "title": "release: support tickets can open a new request beside an open one",
          "base": "main", "head": "dev", "head_sha": "0dcd66e",
          "state": "open",
          "draft": false,
          "mergeable": "mergeable",
          "checks": {"state": "failing", "failing": ["CI / lint"], "pending": [], "passed": 4, "total": 5},
          "session": "t58", "name": "b2c tickets mediaroom", "space": "b2c-tickets",
          "waiting": true,
          "found_ms": 1794238300000, "checked_ms": 1794238390000,
          "closed_ms": null,
          "merged_by": null,
          "merge": {
            "can": true,
            "blocked": null,
            "needs_override": "1 check failing: CI / lint. Merge anyway only if you know the failure is unrelated.",
            "warnings": ["It merges into main: that ships to production."]
          }
        }
    """.trimIndent()

    @Test fun theDocsPrReadsWhole() {
        val p = TowerJson.decodeFromString(Pr.serializer(), docPr)
        assertEquals("eyzhub/eyz-mediaroom-serverless#1250", p.id)
        assertEquals(PrRef("eyzhub", "eyz-mediaroom-serverless", 1250), p.ref)
        assertEquals("main", p.base)
        assertEquals("0dcd66e", p.headSha)
        assertEquals(listOf("CI / lint"), p.checks.failing)
        assertEquals(5, p.checks.total)
        assertTrue(p.waiting && p.open)
        assertEquals(1794238390000L, p.checkedMs)
        assertNull(p.closedMs)
        assertTrue(p.merge.can)
        assertEquals("1 check failing: CI / lint. Merge anyway only if you know the failure is unrelated.", p.merge.needsOverride)
        assertEquals(listOf("It merges into main: that ships to production."), p.merge.warnings)
    }

    @Test fun aChipInTheChatAndThePrEvent() {
        val page = ChatApi.page("""{"messages":[{"id":"h1840","at":5,"role":"pr","kind":"pr","text":"release","source":"tower","attachments":[],"screenshots":[],"session":"t58","name":"mediaroom","space":"b2c","pr":$docPr}],"more":false}""")
        val m = page.messages.single()
        assertEquals("b2c", m.space)
        assertEquals(1250L, PrsApi.of(m)?.number)
        // Not a PR chip: no PR, whatever it carries.
        assertNull(PrsApi.of(m.copy(role = "tower", kind = "answer")))

        val ev = TowerEvent.decode(RawEvent(null, "pr", """{"op":"closed","pr":${docPr.replace("\"open\"", "\"merged\"")}}"""))
        ev as TowerEvent.PrChanged
        assertEquals("closed", ev.change.op)
        assertEquals("merged", ev.change.pr.state)
    }

    @Test fun aChipNotLookedUpYetIsItsKeyAndTitle() {
        val m = ChatApi.message("""{"op":"add","message":{"id":"h9","role":"pr","text":"fix","pr":{"key":"o/app#3","title":"fix"}}}""")
        val p = PrsApi.of(m)!!
        assertEquals("o/app#3", p.id)
        assertEquals(PrRef("o", "app", 3), p.ref)
        assertEquals("", p.state)
        assertFalse(p.merge.can)
    }

    @Test fun theConfirmationReads() {
        val c = TowerJson.decodeFromString(
            MergeConfirm.serializer(),
            """{"token":"9f2c","expires_ms":1794238510000,"method":"merge","can":true,"blocked":null,"needs_override":null,"warnings":["It merges into main: that ships to production."],"pr":$docPr}""",
        )
        assertEquals("9f2c", c.token)
        assertEquals("merge", c.method)
        assertTrue(c.can)
        assertNull(c.needsOverride)
        assertEquals(1250L, c.pr.number)
    }

    @Test fun refsParseOnlyWholeKeys() {
        assertEquals(PrRef("o", "app", 12), PrRef.parse("o/app#12"))
        assertNull(PrRef.parse("o/app"))
        assertNull(PrRef.parse("app#12"))
        assertNull(PrRef.parse("o/app#x"))
        assertNull(PrRef.parse("o/a/b#1"))
    }

    private fun p(state: String, checked: Long) = Pr(key = "o/app#1", repo = "o/app", number = 1, state = state, checkedMs = checked)

    @Test fun theFreshestCopyWinsAndAMergeNeverUndoes() {
        val open = mapOf("o/app#1" to p("open", 10))
        assertEquals(20L, PrsApi.merge(open, listOf(p("open", 20)))["o/app#1"]?.checkedMs)
        // An older look (a page of the chat loaded late) doesn't go back.
        assertEquals(10L, PrsApi.merge(open, listOf(p("open", 5)))["o/app#1"]?.checkedMs)
        // Merged: wins even with an older clock, and an open copy after it doesn't bring it back.
        val merged = PrsApi.merge(open, listOf(p("merged", 9)))
        assertEquals("merged", merged["o/app#1"]?.state)
        assertEquals("merged", PrsApi.merge(merged, listOf(p("open", 50)))["o/app#1"]?.state)
        // A copy that is only its parts gets its key.
        assertEquals("o/app#1", PrsApi.merge(emptyMap(), listOf(Pr(repo = "o/app", number = 1)))["o/app#1"]?.key)
        // Nothing to key it by: skipped.
        assertTrue(PrsApi.merge(emptyMap(), listOf(Pr(title = "x"))).isEmpty())
    }
}
