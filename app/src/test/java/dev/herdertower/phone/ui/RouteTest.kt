package dev.herdertower.phone.ui

import dev.herdertower.phone.api.Screenshot
import dev.herdertower.phone.data.Images
import dev.herdertower.phone.data.TowerSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteTest {
    @Test fun routesSurviveSaveAndRestore() {
        val routes = listOf(
            Route.Queue, Route.Spaces, Route.Record, Route.Shots(), Route.Shots("t22", "1794160000000-login.png"), Route.Space("industrial-landing-page"),
            Route.Session("vps/t3"), Route.NewSpace, Route.Settings, Route.Chat(), Route.Chat("B2C support"), Route.Question("q3f1"), Route.Behaviors, Route.Behavior("be-brief"), Route.Behavior(null),
        )
        routes.forEach { assertEquals(it, Route.decode(Route.encode(it))) }
        // A colon in a session's address, a file's name or a space's doesn't split it.
        val colon = Route.Shots("mac:t3", "12:30-shot.png")
        assertEquals(colon, Route.decode(Route.encode(colon)))
        assertEquals(Route.Space("a:b"), Route.decode(Route.encode(Route.Space("a:b"))))
        // What an older version saved (its home, its voice screen, its activity): the queue, where the app opens.
        listOf("home", "voice:true", "activity", "").forEach { assertEquals(Route.Queue, Route.decode(it)) }
        val stack = listOf(Route.Spaces, Route.Space("sandbox"), Route.Session("s2"))
        val saved = with(Route.StackSaver) { androidx.compose.runtime.saveable.SaverScope { true }.save(stack) }!!
        assertEquals(stack, Route.StackSaver.restore(saved))
    }

    @Test fun aTabStartsOverAndTheRestGoesOnTop() {
        var stack = listOf<Route>(Route.Queue)
        stack = Route.push(stack, Route.Spaces)
        assertEquals(listOf<Route>(Route.Spaces), stack)
        stack = Route.push(stack, Route.Space("sandbox"))
        stack = Route.push(stack, Route.Session("s2"))
        assertEquals(listOf(Route.Spaces, Route.Space("sandbox"), Route.Session("s2")), stack)
        // The same kind of screen again replaces the one in the stack (no pile of sessions).
        assertEquals(listOf(Route.Spaces, Route.Space("sandbox"), Route.Session("s3")), Route.push(stack, Route.Session("s3")))
        // One session's shots are a screen over it, not the tab.
        assertEquals(4, Route.push(stack, Route.Shots("s2", "a.png")).size)
        assertEquals(listOf<Route>(Route.Shots()), Route.push(stack, Route.Shots()))
        assertEquals(listOf<Route>(Route.Queue), Route.push(stack, Route.Queue))
    }

    @Test fun backGoesUpThenToTheQueueThenOut() {
        assertEquals(listOf(Route.Spaces, Route.Space("sandbox")), Route.pop(listOf(Route.Spaces, Route.Space("sandbox"), Route.Session("s2"))))
        assertEquals(listOf<Route>(Route.Queue), Route.pop(listOf(Route.Spaces)))
        assertEquals(listOf<Route>(Route.Queue), Route.pop(listOf(Route.Record)))
        assertNull("from the queue, Back leaves the app", Route.pop(listOf(Route.Queue)))
    }

    @Test fun theTabThatIsLit() {
        assertEquals(Tab.Queue, Route.tabOf(listOf(Route.Queue)))
        assertEquals(Tab.Spaces, Route.tabOf(listOf(Route.Spaces, Route.Space("sandbox"), Route.Session("s2"))))
        assertEquals(Tab.Spaces, Route.tabOf(listOf(Route.Spaces, Route.NewSpace)))
        assertEquals(Tab.Shots, Route.tabOf(listOf(Route.Shots())))
        // Settings (or the conversation) over a tab keeps that tab lit.
        assertEquals(Tab.Record, Route.tabOf(listOf(Route.Record, Route.Settings)))
        assertEquals(Tab.Queue, Route.tabOf(listOf(Route.Settings)))
        Tab.entries.forEach { assertEquals(it, Route.of(it).tab) }
        assertTrue(Tab.entries.all { Route.of(it).isTab })
        assertFalse(Route.Shots("s2").isTab)
    }

    @Test fun whereTheTalkBarShows() {
        listOf(Route.Queue, Route.Spaces, Route.Record, Route.Shots(), Route.Space("x"), Route.Session("s"), Route.NewSpace, Route.Settings).forEach { assertTrue("$it", it.bars) }
        // These fill the phone and have their own input.
        listOf(Route.Chat(), Route.Chat("sandbox"), Route.Question("q"), Route.Behaviors, Route.Behavior(null)).forEach { assertFalse("$it", it.bars) }
    }

    @Test fun screenshotsMergeNewestFirstEachOnce() {
        val a = listOf(Screenshot("b", 2), Screenshot("a", 1))
        val b = listOf(Screenshot("c", 3), Screenshot("b", 2))
        assertEquals(listOf("c", "b", "a"), TowerSession.mergeShots(a, b).map { it.id })
    }

    @Test fun thumbnailsDecodeSubsampled() {
        assertEquals(1, Images.sampleSize(800, 600, 480))
        assertEquals(4, Images.sampleSize(2532, 1170, 480))
        assertEquals(2, Images.sampleSize(2532, 1170, 1200))
    }
}
