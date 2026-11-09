package dev.herdertower.phone.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The session starts as on a phone: the main dispatcher runs its coroutines
 * at once, inside the constructor, so everything they touch must already be
 * set (a flow declared after `init` was still null there and crashed the app
 * on launch).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionStartTest {
    @Before fun immediateMain() = Dispatchers.setMain(Dispatchers.Unconfined)

    @After fun reset() = Dispatchers.resetMain()

    @Test fun startsPairedOrNotWithCoroutinesRunningAtOnce() {
        // A coroutine that fails has no handler: on a phone that kills the app; here it reaches the thread's handler.
        val uncaught = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val before = Thread.currentThread().uncaughtExceptionHandler
        Thread.currentThread().uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, e -> uncaught += e }
        try {
            start()
        } finally {
            Thread.currentThread().uncaughtExceptionHandler = before
        }
        assertEquals("nothing failed while starting: $uncaught", emptyList<Throwable>(), uncaught.toList())
    }

    private fun start() {
        val creds = Credentials("192.168.2.211", 7843, "5d8e798d".repeat(8), "htk_x", "d_1", "phone")
        for (stored in listOf(null, creds)) {
            // Named: no reverse lookup of the Mac's address, which could finish after this test on a dispatcher that's gone.
            val s = TowerSession(FakeContext(), FakeStore(stored), Settings(FakeContext()).also { it.towerName = "Test Mac" })
            s.networkChanged()
            assertEquals(stored, s.credentials.value)
            s.scope.cancel()
        }
    }
}
