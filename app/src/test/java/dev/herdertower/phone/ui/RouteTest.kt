package dev.herdertower.phone.ui

import dev.herdertower.phone.api.Screenshot
import dev.herdertower.phone.data.Images
import dev.herdertower.phone.data.TowerSession
import org.junit.Assert.assertEquals
import org.junit.Test

class RouteTest {
    @Test fun routesSurviveSaveAndRestore() {
        val routes = listOf(Route.Home, Route.Chat, Route.Spaces, Route.Activity, Route.Session("vps/t3"), Route.Screenshots(), Route.Screenshots("t22", "1794160000000-login.png"))
        routes.forEach { assertEquals(it, Route.decode(Route.encode(it))) }
        // A colon in a session's address or a file's name doesn't split it.
        val colon = Route.Screenshots("mac:t3", "12:30-shot.png")
        assertEquals(colon, Route.decode(Route.encode(colon)))
        // The voice screen comes back without listening again.
        assertEquals(Route.Voice(false), Route.decode(Route.encode(Route.Voice(true))))
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
