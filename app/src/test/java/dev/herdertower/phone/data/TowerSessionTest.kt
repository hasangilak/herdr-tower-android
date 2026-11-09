package dev.herdertower.phone.data

import dev.herdertower.phone.api.Fingerprint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The session against a fake tower over pinned HTTPS: the one event stream
 * (kept open, reconnected, stopped for good), the tower-wide mute and
 * unpairing. Real time, short waits: the stream's own backoff is 1 s.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TowerSessionTest {
    private lateinit var server: MockWebServer
    private val cert = HeldCertificate.Builder().commonName("herder-tower").ecdsa256().build()
    private val fp = Fingerprint.of(cert.certificate)
    private val sessions = mutableListOf<TowerSession>()

    /** When each `/v1/events` request came, and how it was answered. */
    private val streams = CopyOnWriteArrayList<Long>()
    private val muteBodies = CopyOnWriteArrayList<String>()
    /** Every other request: method, path and the token it carried. */
    private val others = CopyOnWriteArrayList<Triple<String, String, String?>>()
    /** How the n-th stream request (0-based) is answered; the last one repeats. */
    private var answers: List<() -> MockResponse> = listOf({ stream(hello(), state(muted = false)) })

    @Before fun setUp() {
        server = MockWebServer()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                return when {
                    path == "/v1/events" -> {
                        val n = streams.size
                        streams += System.currentTimeMillis()
                        answers[minOf(n, answers.lastIndex)]()
                    }
                    path == "/v1/mute" -> {
                        val body = request.body?.utf8().orEmpty()
                        muteBodies += body
                        json(if ("true" in body) """{"muted": true}""" else """{"muted": false}""")
                    }
                    path == "/v1/chat" -> json("""{"error": {"code": "not_found", "message": "no"}}""", 404)
                    else -> {
                        others += Triple(request.method ?: "", path, request.headers["Authorization"])
                        json("{}")
                    }
                }
            }
        }
        server.start()
    }

    @After fun tearDown() {
        sessions.forEach { it.scope.cancel() }
        server.close()
    }

    companion object {
        // One main thread for the whole class: setting Main again while the last test's coroutines wind down throws.
        private val main: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor { Thread(it, "test-main").apply { isDaemon = true } }.asCoroutineDispatcher()

        @JvmStatic @org.junit.BeforeClass fun setMain() = Dispatchers.setMain(main)

        @JvmStatic @org.junit.AfterClass fun resetMain() {
            Dispatchers.resetMain()
            main.close()
        }
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    private fun hello() = "event: hello\ndata: {\"api\":1,\"device\":\"d_test\",\"voice\":true}\n\n"
    private fun state(muted: Boolean) = "event: state\ndata: {\"phase\":\"idle\",\"muted\":$muted}\n\n"

    /**
     * An event stream: [events] at once, then pings dripped slowly, so it
     * stays open for the test (a stream that ends would be reconnected).
     * [later] comes after about [laterMs].
     */
    private fun stream(vararg events: String, later: String = "", laterMs: Int = 0): MockResponse {
        val ping = ": ping\n\n"
        val bytesPerSecond = 2000
        val pad = ping.repeat(laterMs * bytesPerSecond / 1000 / ping.length)
        val tail = ping.repeat(20 * bytesPerSecond / ping.length) // ~20 s more of pings
        return MockResponse.Builder()
            .addHeader("Content-Type", "text/event-stream")
            .body(events.joinToString("") + pad + later + tail)
            .throttleBody(bytesPerSecond / 20L, 50, TimeUnit.MILLISECONDS)
            .build()
    }

    private fun session(voice: Boolean = false): TowerSession {
        val creds = Credentials(server.hostName, server.port, fp, "htk_test", "d_test", "test phone")
        val s = TowerSession(FakeContext(), FakeStore(creds), Settings(FakeContext()).also { it.voice = voice; it.towerName = "Test Mac" })
        sessions += s
        return s
    }

    private fun until(timeoutMs: Long = 5_000, what: String = "condition", check: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!check()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    @Test fun theVoiceStreamStaysOpenWhenTheAppComesAndGoes() {
        val s = session(voice = true)
        until(what = "live") { s.link.value == Link.Live(true) }
        // On screen, off, on again, and a reply being waited for: none of it reopens the voice stream.
        s.setForeground(true)
        s.setForeground(false)
        s.setForeground(true)
        s.linger()
        s.setForeground(false)
        Thread.sleep(1_500)
        assertEquals("one stream for all of it", 1, streams.size)
        assertEquals(Link.Live(true), s.link.value)
    }

    @Test fun thePanelFeedClosesInTheBackgroundAndOpensAgain() {
        val s = session(voice = false)
        s.setForeground(true)
        until(what = "live") { s.link.value == Link.Live(false) }
        s.setForeground(false)
        until(what = "idle") { s.link.value == Link.Idle }
        s.setForeground(true)
        until(what = "live again") { s.link.value == Link.Live(false) }
        assertEquals(2, streams.size)
    }

    @Test fun aRetryAskedForDuringAnAttemptIsKept() {
        // The first attempt takes a while and fails; Retry is tapped while it's still on its way.
        answers = listOf(
            { MockResponse.Builder().headersDelay(800, TimeUnit.MILLISECONDS).code(503).body("{}").build() },
            { stream(hello(), state(muted = false)) },
        )
        val s = session(voice = false)
        s.setForeground(true)
        until(what = "first attempt") { streams.size == 1 }
        Thread.sleep(200)
        assertTrue(s.link.value is Link.Connecting)
        s.reconnect()
        until(what = "the failure") { s.link.value is Link.Retrying || streams.size >= 2 }
        val failedAt = System.currentTimeMillis()
        until(what = "second attempt") { streams.size == 2 }
        // Without the kept tap it would wait out the 1 s backoff.
        assertTrue("retried after ${System.currentTimeMillis() - failedAt} ms", System.currentTimeMillis() - failedAt < 600)
        until(what = "live") { s.link.value == Link.Live(false) }
    }

    @Test fun whileRetryingTheCardKeepsTheReason() {
        answers = listOf({ json("""{"error": {"code": "busy", "message": "The tower is busy."}}""", 503) })
        val s = session(voice = false)
        // Every state the link goes through (an attempt in flight is brief: polling could miss it).
        val seen = CopyOnWriteArrayList<Link>()
        s.scope.launch { s.link.collect { seen += it } }
        s.setForeground(true)
        until(what = "two failures") { streams.size >= 2 && seen.count { it is Link.Retrying } >= 2 }
        val afterFirst = seen.dropWhile { it !is Link.Retrying }
        assertEquals("The tower is busy.", (afterFirst.first() as Link.Retrying).reason)
        // The next attempt is in flight: it still carries why the last one failed, so the card stays.
        val retry = afterFirst.first { it is Link.Connecting } as Link.Connecting
        assertEquals("The tower is busy.", retry.failing)
        // And the card shows for every state after the first failure: it never blinks out.
        assertTrue(afterFirst.all { dev.herdertower.phone.ui.HomeModel.connectionNotice(it) != null })
    }

    @Test fun aRefusedTokenStopsTheStreamUntilPairingAgain() {
        answers = listOf({ json("""{"error": {"code": "unauthorized", "message": "This phone isn't paired."}}""", 401) })
        val s = session(voice = false)
        s.setForeground(true)
        until(what = "revoked") { s.link.value is Link.Revoked }
        s.reconnect()
        Thread.sleep(2_500)
        assertEquals("no retries with a refused token", 1, streams.size)
        assertTrue(s.link.value is Link.Revoked)
    }

    @Test fun theMuteFollowsTheTowerAndAStaleStateDoesntUndoIt() = runBlocking {
        // Muted on the Mac; a second state, sent before the phone's unmute was seen, comes a second later.
        answers = listOf({ stream(hello(), state(muted = true), later = state(muted = true), laterMs = 1_000) })
        val s = session(voice = false)
        s.setForeground(true)
        until(what = "muted from the tower") { s.muted.value }
        assertTrue(s.player.silenced)
        assertTrue(s.setMuted(false).isSuccess)
        assertEquals("""{"muted":false}""", muteBodies.single())
        assertFalse(s.muted.value)
        assertFalse(s.player.silenced)
        Thread.sleep(1_800) // the stale state arrives in here
        assertFalse("a state sent before the unmute is ignored", s.muted.value)
    }

    @Test fun unpairingForgetsEverythingFromTheTower() = runBlocking {
        answers = listOf({ stream(hello(), state(muted = true)) })
        val s = session(voice = false)
        s.setForeground(true)
        until(what = "muted from the tower") { s.muted.value && s.state.value != null }
        s.unpair(tellTower = false)
        until(what = "unpaired") { s.link.value == Link.Unpaired }
        assertNull(s.state.value)
        assertFalse(s.muted.value)
        assertFalse(s.player.silenced)
        assertNull(s.credentials.value)
        assertEquals("", s.towerName.value)
        assertTrue(s.questions.value.isEmpty())
    }

    @Test fun pairingWithAnotherTowerRevokesTheOldToken() = runBlocking {
        // A second tower, with its own certificate.
        val otherCert = HeldCertificate.Builder().commonName("herder-tower").ecdsa256().build()
        val otherFp = Fingerprint.of(otherCert.certificate)
        val other = MockWebServer()
        other.useHttps(HandshakeCertificates.Builder().heldCertificate(otherCert).build().sslSocketFactory())
        other.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.url.encodedPath == "/v1/pair") json("""{"token": "htk_new", "device": {"id": "d_new", "name": "test phone"}, "fingerprint": "$otherFp", "api": 1}""")
                else json("{}")
        }
        other.start()
        try {
            val s = session(voice = false)
            val result = s.pair(dev.herdertower.phone.api.Endpoint(other.hostName, other.port, otherFp), "12345678", "test phone")
            assertTrue(result.isSuccess)
            assertEquals("htk_new", s.credentials.value?.token)
            // The first tower is told to forget this phone, with the old token.
            until(what = "the old token revoked") { others.any { it.first == "DELETE" && it.second == "/v1/device" } }
            assertEquals("Bearer htk_test", others.first { it.first == "DELETE" && it.second == "/v1/device" }.third)
        } finally {
            other.close()
        }
    }
}
