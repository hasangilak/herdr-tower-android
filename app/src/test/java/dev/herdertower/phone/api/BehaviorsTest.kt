package dev.herdertower.phone.api

import dev.herdertower.phone.ui.ChatModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BehaviorsTest {
    private val listing = """
        {"categories":["Talking","Sessions","Spaces and focus","Accounts","Releases and safety","Questions 90/10","Screenshots","Browsers","Misc"],
         "behaviors":[
          {"id":"release-dev-then-prod","title":"Release dev then prod","category":"Releases and safety","enabled":true,"source":"learned","summary":"Releases always go to dev first, then dev to prod","created_ms":1,"updated_ms":2,"text":"Always release via dev first."},
          {"id":"be-brief","title":"Be brief","category":"Talking","enabled":false,"source":"builtin","summary":"One sentence","created_ms":1,"updated_ms":2,"text":"Say the result first."},
          {"id":"x","title":"Odd one","category":"Something new","enabled":true,"source":"user","summary":"","created_ms":1,"updated_ms":2,"text":"t"}],
         "core":{"title":"Core","text":"You are the tower.","read_only":true},
         "summary":{"count":3,"enabled":2,"by_category":{"Talking":1},"updated_ms":2}}
    """.trimIndent()

    private val r = TowerJson.decodeFromString(BehaviorsResponse.serializer(), listing)

    @Test fun readsTheListing() {
        assertEquals(3, r.behaviors.size)
        assertTrue(r.core!!.readOnly)
        assertEquals(2, r.summary!!.enabled)
        assertEquals("Learned", BehaviorsApi.sourceWord(r.behaviors[0].source))
        assertEquals("Built in", BehaviorsApi.sourceWord("builtin"))
        assertEquals("You", BehaviorsApi.sourceWord("user"))
    }

    @Test fun groupedInTheTowersOrderUnknownLast() {
        val groups = BehaviorsApi.byCategory(r.behaviors, r.categories)
        assertEquals(listOf("Talking", "Releases and safety", "Something new"), groups.map { it.first })
    }

    @Test fun searchAndFilters() {
        assertEquals(listOf("release-dev-then-prod"), BehaviorsApi.filter(r.behaviors, "PROD", "all").map { it.id })
        assertEquals(listOf("be-brief"), BehaviorsApi.filter(r.behaviors, "", "off").map { it.id })
        assertEquals(listOf("release-dev-then-prod"), BehaviorsApi.filter(r.behaviors, "", "learned").map { it.id })
        assertEquals(2, BehaviorsApi.filter(r.behaviors, "", "on").size)
    }

    @Test fun diffsLineByLine() {
        val d = BehaviorsApi.diff("  Open links for main sessions in Chrome.\n- Open links for orca sessions in Chrome.\n+ Open links for orca sessions in Brave, never Chrome.")
        assertEquals(listOf(DiffLine.Kind.Same, DiffLine.Kind.Removed, DiffLine.Kind.Added), d.map { it.kind })
        assertEquals("Open links for orca sessions in Brave, never Chrome.", d[2].text)
        // Only what changed, with a line around it; gaps as null.
        val long = BehaviorsApi.diff((1..8).joinToString("\n") { "  line $it" } + "\n+ new")
        val short = BehaviorsApi.changedOnly(long)
        assertEquals(listOf(null, DiffLine(DiffLine.Kind.Same, "line 8"), DiffLine(DiffLine.Kind.Added, "new")), short)
    }

    @Test fun aChatCardOpensItsBehavior() {
        assertEquals("release-dev-then-prod", BehaviorsApi.fromChatLine("Changed a behavior: Release dev then prod", r.behaviors)?.id)
        assertNull(BehaviorsApi.fromChatLine("Removed a behavior: Gone", r.behaviors))
        val m = ChatApi.message("""{"op":"add","message":{"id":"h9","at":1,"role":"event","kind":"behavior","text":"Added a behavior: Be brief","diff":"+ Say the result first."}}""")
        val e = ChatModel.entry(m)
        assertEquals("behavior", e.kind)
        assertEquals("+ Say the result first.", e.diff)
        assertTrue(ChatModel.items(listOf(e)).isNotEmpty())
    }

    @Test fun patchesSendOnlyWhatChanged() {
        assertEquals("""{"enabled":false}""", BehaviorPatch(enabled = false).json())
        assertEquals("""{"title":"T","text":"rule"}""", BehaviorPatch(title = "T", text = "rule").json())
        val ev = TowerEvent.decode(RawEvent(null, "behaviors", """{"at":5,"op":"disable","id":"be-brief","title":"Be brief","by":"brain","undid":null,"diff":""}"""))
        assertTrue(ev is TowerEvent.BehaviorsChanged && ev.change.by == "brain")
        assertEquals("Turned off", BehaviorsApi.verb("disable"))
    }

    @Test fun theTowersOwnSetupIsNeverUndoneFromHere() {
        val seeded = listOf(BehaviorChange(3, "seed", "a", "A", "tower"), BehaviorChange(2, "migrate", "b", "B", "tower"))
        assertTrue(!BehaviorsApi.canUndo(seeded))
        assertTrue(BehaviorsApi.visible(seeded).isEmpty())
        val edited = listOf(BehaviorChange(4, "edit", "a", "A", "user")) + seeded
        assertTrue(BehaviorsApi.canUndo(edited))
        assertEquals(1, BehaviorsApi.visible(edited).size)
        // Undoing the edit leaves the seed as the next target: no more undo from here.
        assertTrue(!BehaviorsApi.canUndo(listOf(BehaviorChange(5, "undo", "a", "A", "user", undid = 4)) + edited))
        // Two edits, one undone: the other is next.
        val two = listOf(BehaviorChange(6, "undo", "c", "C", "user", undid = 5), BehaviorChange(5, "edit", "c", "C", "user"), BehaviorChange(4, "add", "d", "D", "brain")) + seeded
        assertEquals("d", BehaviorsApi.undoTarget(two)?.id)
        // The tower's own seeding, recorded as adds by the tower: not undone from here.
        assertTrue(!BehaviorsApi.canUndo(listOf(BehaviorChange(9, "add", "x", "X", "tower"))))
        assertEquals("set up by the tower", BehaviorsApi.byWord(BehaviorChange(by = "tower")))
    }
}
