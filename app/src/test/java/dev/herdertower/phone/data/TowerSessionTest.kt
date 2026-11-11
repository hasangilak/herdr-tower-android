package dev.herdertower.phone.data

import dev.herdertower.phone.api.Fingerprint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
    /** PR calls (method, path, body) and how they're answered (a tower without PRs by default). */
    private val prCalls = CopyOnWriteArrayList<String>()
    private var prAnswer: (RecordedRequest) -> MockResponse = { json("""{"error": {"code": "not_found", "message": "no"}}""", 404) }
    /** Every call but the stream's: "METHOD /v1/path?query body". */
    private val calls = CopyOnWriteArrayList<String>()
    /** Answers a call itself (the Quiet Tower's, a question's), or null to leave it to the rest. */
    private var extra: (RecordedRequest) -> MockResponse? = { null }
    private val notFound get() = json("""{"error": {"code": "not_found", "message": "no"}}""", 404)
    /** How the n-th stream request (0-based) is answered; the last one repeats. */
    private var answers: List<() -> MockResponse> = listOf({ stream(hello(), state(muted = false)) })

    @Before fun setUp() {
        server = MockWebServer()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                if (path != "/v1/events") {
                    val body = request.body?.utf8().orEmpty()
                    calls += (request.method + " " + path + (request.url.encodedQuery?.let { "?$it" } ?: "") + " " + body).trim()
                    extra(request)?.let { return it }
                }
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
                    path.startsWith("/v1/prs") -> {
                        prCalls += request.method + " " + path + " " + request.body?.utf8().orEmpty()
                        prAnswer(request)
                    }
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

    /** The stream's hello: a Quiet Tower's names its feature; [quiet] false is a tower from before it. */
    private fun hello(quiet: Boolean = true) = "event: hello\ndata: {\"api\":1,\"device\":\"d_test\",\"voice\":true${if (quiet) ",\"features\":[\"quiet\"]" else ""}}\n\n"
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
        assertTrue(afterFirst.all { dev.herdertower.phone.ui.LinkModel.connectionNotice(it) != null })
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

    @Test fun aQrOfTheSameTowerFromAnotherNetworkStillPairsThroughTheStoredAddress() = runBlocking {
        extra = { r -> if (r.url.encodedPath == "/v1/pair") json("""{"token": "htk_again", "device": {"id": "d_test", "name": "test phone"}, "fingerprint": "$fp", "api": 1, "repaired": true}""") else null }
        val s = session(voice = false)
        // The QR names the office address, where nothing answers now; the Mac has gone home, which the phone knows.
        val office = dev.herdertower.phone.api.TowerHost("lan", "127.0.0.1", 1)
        val r = s.pair(listOf(office), null, fp, "12345678", "test phone")
        assertTrue("${r.exceptionOrNull()}", r.isSuccess)
        assertEquals("htk_again", s.credentials.value?.token)
        assertEquals("the same device, re-paired in place", "d_test", s.credentials.value?.deviceId)
        val body = calls.first { it.startsWith("POST /v1/pair") }
        assertTrue(body, body.contains("\"install_id\""))
        // Both addresses kept: the office's (for when the Mac is back there) and the one that answered.
        assertEquals(setOf("127.0.0.1:1", "${server.hostName}:${server.port}"), s.credentials.value?.hosts?.map { it.id }?.toSet())
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
            assertTrue("${result.exceptionOrNull()?.let { it.javaClass.name + ": " + it.message + " @ " + it.stackTrace.take(4).joinToString(" < ") }}", result.isSuccess)
            assertEquals("htk_new", s.credentials.value?.token)
            // The first tower is told to forget this phone, with the old token.
            until(what = "the old token revoked") { others.any { it.first == "DELETE" && it.second == "/v1/device" } }
            assertEquals("Bearer htk_test", others.first { it.first == "DELETE" && it.second == "/v1/device" }.third)
        } finally {
            other.close()
        }
    }

    private fun pr(state: String = "open", checks: String = "pending", checked: Long, waiting: Boolean = true) =
        """{"key":"o/app#12","repo":"o/app","number":12,"title":"release: tickets","base":"main","head":"dev","state":"$state",""" +
            """"mergeable":"mergeable","checks":{"state":"$checks","failing":[],"pending":[],"passed":2,"total":2},"session":"t58","name":"mediaroom",""" +
            """"waiting":$waiting,"checked_ms":$checked,"merge":{"can":${state == "open"},"blocked":${if (state == "open") "null" else "\"It's merged already.\""},"needs_override":null,"warnings":[]}}"""

    @Test fun aPrChipChangesInPlaceFromTheStreamAndAStaleCopyDoesntUndoIt() {
        prAnswer = { json("""{"prs":[${pr(checked = 10)}]}""") }
        val events = "event: pr\ndata: {\"op\":\"update\",\"pr\":${pr(checks = "passing", checked = 20)}}\n\n" +
            "event: chat\ndata: {\"op\":\"update\",\"message\":{\"id\":\"h40\",\"at\":5,\"role\":\"pr\",\"kind\":\"pr\",\"text\":\"release: tickets\",\"pr\":${pr(state = "merged", checks = "passing", checked = 30, waiting = false)}}}\n\n" +
            // Sent before the merge was seen: an open copy with an older look must not bring the Merge button back.
            "event: pr\ndata: {\"op\":\"update\",\"pr\":${pr(checks = "passing", checked = 25)}}\n\n"
        answers = listOf({ stream(hello(), state(muted = false), later = events, laterMs = 800) })
        val s = session(voice = false)
        s.setForeground(true)
        until(what = "the list") { s.prs.value["o/app#12"]?.checks?.state == "pending" }
        assertTrue(s.prsSupported.value)
        assertTrue(s.prs.value.getValue("o/app#12").waiting)
        until(what = "merged from the chat update") { s.prs.value["o/app#12"]?.state == "merged" }
        Thread.sleep(300)
        val p = s.prs.value.getValue("o/app#12")
        assertEquals("merged", p.state)
        assertEquals(30L, p.checkedMs)
        assertEquals("one PR, changed in place", 1, s.prs.value.size)
        assertTrue(s.thread.value.single { it.id == "h40" }.pr != null)
    }

    @Test fun mergingConfirmsThenSendsTheTokenAndTheChipFollows() = runBlocking {
        prAnswer = { r ->
            when {
                r.url.encodedPath == "/v1/prs" -> json("""{"prs":[${pr(checked = 10)}]}""")
                r.url.encodedPath.endsWith("/confirm") -> json("""{"token":"tok1","expires_ms":99,"method":"squash","can":true,"blocked":null,"needs_override":null,"warnings":["It merges into main: that ships to production."],"pr":${pr(checks = "passing", checked = 15)}}""")
                r.url.encodedPath.endsWith("/merge") -> json("""{"merged":true,"pr":${pr(state = "merged", checks = "passing", checked = 16, waiting = false)}}""")
                else -> json("{}", 404)
            }
        }
        val s = session(voice = false)
        s.setForeground(true)
        until(what = "the list") { s.prs.value.isNotEmpty() }
        val ref = s.prs.value.getValue("o/app#12").ref!!
        val c = s.confirmMerge(ref).getOrThrow()
        assertEquals("tok1", c.token)
        assertEquals("the look updates the chip", "passing", s.prs.value.getValue("o/app#12").checks.state)
        val r = s.merge(ref, c.token).getOrThrow()
        assertTrue(r.merged)
        assertEquals("merged", s.prs.value.getValue("o/app#12").state)
        assertEquals(
            listOf("POST /v1/prs/o/app/12/confirm {}", "POST /v1/prs/o/app/12/merge {\"token\":\"tok1\"}"),
            prCalls.filter { it.startsWith("POST") },
        )
    }

    @Test fun aTowerWithoutPrsShowsNone() {
        val s = session(voice = false)
        s.setForeground(true)
        until(what = "live") { s.link.value == Link.Live(false) }
        until(what = "asked") { prCalls.isNotEmpty() }
        Thread.sleep(200)
        assertFalse(s.prsSupported.value)
        assertTrue(s.prs.value.isEmpty())
    }

    // Routes: the LAN, then Tailscale

    private class FakeNet(val vpn: Boolean?, val installed: Boolean?) : PhoneNet {
        override fun vpnOn() = vpn
        override fun tailscaleInstalled() = installed
    }

    /** A port nothing listens on: a connect there is refused at once (a LAN address that isn't this network's). */
    private fun closedPort(): Int = java.net.ServerSocket(0).use { it.localPort }

    /** The same fake tower under its other name (127.0.0.1 or localhost): a second address that answers. */
    private val otherName get() = if (server.hostName == "localhost") "127.0.0.1" else "localhost"

    private fun sessionAt(hosts: List<dev.herdertower.phone.api.TowerHost>, net: PhoneNet = FakeNet(true, true)): TowerSession {
        val first = hosts.first()
        val creds = Credentials(first.host, first.port, fp, "htk_test", "d_test", "test phone", hosts, null)
        val s = TowerSession(FakeContext(), FakeStore(creds), Settings(FakeContext()).also { it.towerName = "Test Mac" }, net)
        sessions += s
        return s
    }

    @Test fun aLanThatIsntThereFallsBackToTailscaleAndIsRemembered() {
        val s = sessionAt(listOf(
            dev.herdertower.phone.api.TowerHost("lan", "127.0.0.1", closedPort()),
            dev.herdertower.phone.api.TowerHost("tailscale", server.hostName, server.port),
        ))
        s.setForeground(true)
        until(what = "live") { s.link.value == Link.Live(false) }
        assertEquals("tailscale", s.activeRoute.value?.host?.kind)
        assertEquals("${server.hostName}:${server.port}", s.settings.lastRoute)
    }

    @Test fun theLanWinsWhenBothAnswer() {
        val s = sessionAt(listOf(
            dev.herdertower.phone.api.TowerHost("lan", server.hostName, server.port),
            dev.herdertower.phone.api.TowerHost("tailscale", otherName, server.port),
        ))
        s.setForeground(true)
        until(what = "live") { s.link.value == Link.Live(false) }
        assertEquals(dev.herdertower.phone.api.TowerHost("lan", server.hostName, server.port), s.activeRoute.value?.host)
        assertEquals("Wi-Fi", s.activeRoute.value?.label)
    }

    @Test fun anotherMachineAtTheLanAddressIsPassedOverNotAChangedCertificate() {
        // Away from home, another network's router sits at the Mac's LAN address and answers TLS with its own certificate.
        val stranger = MockWebServer()
        stranger.useHttps(HandshakeCertificates.Builder().heldCertificate(HeldCertificate.Builder().commonName("router").ecdsa256().build()).build().sslSocketFactory())
        stranger.start()
        try {
            val s = sessionAt(listOf(
                dev.herdertower.phone.api.TowerHost("lan", stranger.hostName, stranger.port),
                dev.herdertower.phone.api.TowerHost("tailscale", server.hostName, server.port),
            ))
            s.setForeground(true)
            until(what = "live over tailscale") { s.link.value == Link.Live(false) }
            assertTrue(s.activeRoute.value!!.tailscale)
        } finally {
            stranger.close()
        }
    }

    @Test fun nothingAnswersAndTailscaleIsOffOnThePhone() {
        val s = sessionAt(
            listOf(
                dev.herdertower.phone.api.TowerHost("lan", "127.0.0.1", closedPort()),
                dev.herdertower.phone.api.TowerHost("tailscale", "127.0.0.1", closedPort()),
            ),
            FakeNet(vpn = false, installed = true),
        )
        s.setForeground(true)
        until(what = "retrying") { s.link.value is Link.Retrying }
        val reason = (s.link.value as Link.Retrying).reason
        assertTrue(reason, "Tailscale is off on this phone" in reason)
        assertEquals(null, s.activeRoute.value)
    }

    @Test fun anOldOneAddressPairingThatCantConnectSaysSo() {
        val s = sessionAt(listOf(dev.herdertower.phone.api.TowerHost("lan", "127.0.0.1", closedPort())), FakeNet(false, false))
        s.setForeground(true)
        until(what = "retrying") { s.link.value is Link.Retrying }
        val reason = (s.link.value as Link.Retrying).reason
        assertTrue(reason, reason.startsWith(RouteWords.OTHER_NETWORK))
    }

    @Test fun theTowersAddressesAreLearntWithoutReopeningTheStream() {
        val hostsState = "event: state\ndata: {\"phase\":\"idle\",\"muted\":false,\"lan_hosts\":[{\"kind\":\"lan\",\"host\":\"${server.hostName}\",\"port\":${server.port}},{\"kind\":\"tailscale\",\"host\":\"100.68.110.33\",\"port\":${server.port},\"dns_name\":\"mac.tailnet.ts.net\"}],\"tailscale\":{\"installed\":true,\"running\":true,\"dns_name\":\"mac.tailnet.ts.net\"}}\n\n"
        answers = listOf({ stream(hello(), hostsState) })
        val s = sessionAt(listOf(dev.herdertower.phone.api.TowerHost("lan", server.hostName, server.port)))
        s.setForeground(true)
        until(what = "hosts learnt") { s.credentials.value?.hosts?.size == 2 }
        assertEquals("mac.tailnet.ts.net", s.credentials.value?.magicDns)
        Thread.sleep(500)
        assertEquals("the stream stays", 1, streams.size)
        assertEquals(Link.Live(false), s.link.value)
    }

    // Moving between routes when the network changes

    /** A second address of the same tower (same certificate), switched up or down; down closes each new connection at once. */
    private inner class Route2 : AutoCloseable {
        @Volatile var up = false
        val events = CopyOnWriteArrayList<Long>()
        val server = MockWebServer().apply {
            useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory())
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    !up -> MockResponse.Builder().onResponseStart(mockwebserver3.SocketEffect.CloseSocket()).build()
                    request.url.encodedPath == "/v1/events" -> {
                        events += System.currentTimeMillis()
                        stream(hello(), state(muted = false))
                    }
                    request.url.encodedPath == "/v1/chat" -> json("""{"error": {"code": "not_found", "message": "no"}}""", 404)
                    else -> json("{}")
                }
            }
            start()
        }
        val host get() = dev.herdertower.phone.api.TowerHost("lan", server.hostName, server.port)
        override fun close() = server.close()
    }

    @Test fun backOnTheMacsWifiTheStreamMovesToTheLanAtOnce() {
        Route2().use { lan ->
            val s = sessionAt(listOf(lan.host, dev.herdertower.phone.api.TowerHost("tailscale", server.hostName, server.port)))
            val seen = CopyOnWriteArrayList<Link>()
            s.scope.launch { s.link.collect { seen += it } }
            s.setForeground(true)
            until(what = "live over tailscale") { s.link.value == Link.Live(false) && s.activeRoute.value?.tailscale == true }
            // Home again: the LAN answers; the phone's network changed.
            lan.up = true
            val at = System.currentTimeMillis()
            s.networkChanged()
            until(timeoutMs = 6_000, what = "on the LAN") { s.activeRoute.value?.host == lan.host && s.link.value == Link.Live(false) }
            assertTrue("moved in ${System.currentTimeMillis() - at} ms, not after a read timeout", System.currentTimeMillis() - at < 5_000)
            assertTrue("no retry card while moving: $seen", seen.none { it is Link.Retrying })
        }
    }

    @Test fun theLanGoneTheStreamMovesToTailscaleAtOnce() {
        Route2().use { lan ->
            lan.up = true
            val s = sessionAt(listOf(lan.host, dev.herdertower.phone.api.TowerHost("tailscale", server.hostName, server.port)))
            val seen = CopyOnWriteArrayList<Link>()
            s.scope.launch { s.link.collect { seen += it } }
            s.setForeground(true)
            until(what = "live on the LAN") { s.link.value == Link.Live(false) && s.activeRoute.value?.host == lan.host }
            // Left home: the LAN address stops answering (its open stream would only end at the read timeout).
            lan.up = false
            val at = System.currentTimeMillis()
            s.networkChanged()
            until(timeoutMs = 10_000, what = "over tailscale") { s.activeRoute.value?.tailscale == true && s.link.value == Link.Live(false) }
            assertTrue("moved in ${System.currentTimeMillis() - at} ms", System.currentTimeMillis() - at < 8_000)
            assertTrue("no retry card while moving: $seen", seen.none { it is Link.Retrying })
        }
    }

    @Test fun theMacsNewAddressesAreMergedInWithoutMovingTheStream() {
        val here = "{\"kind\":\"lan\",\"host\":\"${server.hostName}\",\"port\":${server.port}}"
        val hello = "event: hello\ndata: {\"api\":1,\"device\":\"d_test\",\"voice\":false,\"features\":[\"quiet\"],\"hosts\":[$here],\"network\":{\"range\":null,\"name\":\"Office\"}}\n\n"
        // Then the Mac's addresses change: Tailscale on, and the hosts event says so.
        val later = "event: hosts\ndata: {\"hosts\":[$here,{\"kind\":\"tailscale\",\"host\":\"100.68.110.33\",\"port\":${server.port}}],\"network\":{\"range\":null,\"name\":\"Office\"},\"magic_dns\":\"mac.tailnet.ts.net\"}\n\n"
        answers = listOf({ stream(hello, state(muted = false), later = later, laterMs = 600) })
        val s = session(voice = false)
        s.setForeground(true)
        until(what = "live") { s.link.value == Link.Live(false) }
        assertEquals("Office", s.network.value?.name)
        until(what = "the tailnet address learnt") { s.credentials.value?.hosts?.any { it.tailscale } == true }
        assertEquals("mac.tailnet.ts.net", s.credentials.value?.magicDns)
        assertEquals("Office", s.credentials.value?.hosts?.first()?.seenName)
        assertEquals("the stream stays", 1, streams.size)
    }

    @Test fun aNetworkChangeOnAHealthyRouteChangesNothing() {
        val s = sessionAt(listOf(
            dev.herdertower.phone.api.TowerHost("lan", server.hostName, server.port),
            dev.herdertower.phone.api.TowerHost("tailscale", otherName, server.port),
        ))
        s.setForeground(true)
        until(what = "live") { s.link.value == Link.Live(false) }
        s.networkChanged()
        Thread.sleep(2_500)
        assertEquals("the stream stays", 1, streams.size)
    }

    @Test fun whenNoAddressAnswersEachOneIsRecorded() {
        val s = sessionAt(
            listOf(
                dev.herdertower.phone.api.TowerHost("lan", "127.0.0.1", closedPort()),
                dev.herdertower.phone.api.TowerHost("tailscale", "127.0.0.1", closedPort()),
            ),
            FakeNet(vpn = false, installed = true),
        )
        s.setForeground(true)
        until(what = "retrying") { s.link.value is Link.Retrying }
        assertEquals(listOf(RouteOutcome.Refused, RouteOutcome.Refused), s.routeTries.value.map { it.outcome })
    }

    // The stream the tower ends itself

    @Test fun aStreamTheTowerEndsIsOpenedAgain() {
        // The tower restarts: its stream ends cleanly (no error). The app must not stay "live" on nothing.
        val ends = { MockResponse.Builder().addHeader("Content-Type", "text/event-stream").body(hello() + state(muted = false)).build() }
        answers = listOf(ends, { stream(hello(), state(muted = false)) })
        val s = session(voice = false)
        s.setForeground(true)
        until(timeoutMs = 6_000, what = "a second stream") { streams.size >= 2 }
        until(what = "live again") { s.link.value == Link.Live(false) }
        assertEquals(2, streams.size)
    }

    // The queue: decisions

    private fun decision(id: String, state: String = "open", muted: Boolean = false) =
        """{"id":"$id","key":"k-$id","kind":"permission","space":"sandbox","sessions":[{"address":"s1","name":"eyzflow","waiting":true}],"waiting":1,"title":"Allow $id","why":"Production data",""" +
            """"options":[{"label":"Allow once","why":"","pick":true},{"label":"No","why":"","pick":false}],"pick":"Allow once","prs":[],"question_id":null,"first_ms":1000,"last_ms":1000,"count":1,"state":"$state","muted":$muted}"""

    @Test fun theQueueComesWithItsEventAfterTheState() {
        val first = "event: state\ndata: {\"phase\":\"idle\",\"decisions_count\":1,\"tell_level\":\"decisions\"}\n\n" +
            "event: decisions\ndata: {\"count\":1,\"decisions\":[${decision("d1")}]}\n\n"
        // Every open one, a muted space's marked: it is seen, not shown on the queue.
        val later = "event: decisions\ndata: {\"count\":2,\"decisions\":[${decision("d2")},${decision("d1")},${decision("d3", muted = true)}]}\n\n"
        extra = { r -> if (r.url.encodedPath == "/v1/decisions") json("""{"space":null,"count":1,"decisions":[${decision("d1")}]}""") else null }
        answers = listOf({ stream(hello(), first, later = later, laterMs = 800) })
        val s = session(voice = false)
        s.setForeground(true)
        until(what = "the queue") { s.decisions.value.map { it.id } == listOf("d1") }
        assertEquals(true, s.decisionsSupported.value)
        assertEquals("decisions", s.tell.value)
        assertEquals("Allow d1", s.decisions.value.single().title)
        // In the tower's own order, as it sends it.
        until(what = "the event's queue") { s.decisions.value.map { it.id } == listOf("d2", "d1") }
    }

    @Test fun anAnswerTooLateDropsTheCardWithoutAnError() = runBlocking {
        extra = { r ->
            when {
                r.url.encodedPath == "/v1/decisions" && r.method == "GET" -> json("""{"count":1,"decisions":[${decision("d1")}]}""")
                r.url.encodedPath == "/v1/decisions/d1/answer" -> json("""{"error":{"code":"closed","message":"d1 is answered already","state":"answered"}}""", 409)
                else -> null
            }
        }
        val s = session(voice = false)
        s.setForeground(true)
        until(what = "the queue") { s.decisions.value.size == 1 }
        val e = s.answerDecision(s.decisions.value.single(), 0).exceptionOrNull()
        assertTrue("$e", e is DecisionOver)
        assertEquals("It was answered already, on another screen or in its session.", e!!.message)
        // Not "couldn't answer": the card shows it over until the queue drops it.
        val d = s.decisions.value.single()
        assertEquals("answered", d.state)
        assertEquals(null, d.error)
    }

    @Test fun aMergeIsNotAnsweredThroughItsDecision() = runBlocking {
        val s = session(voice = false)
        s.setForeground(true)
        until(what = "live") { s.decisionsSupported.value == true }
        val merge = dev.herdertower.phone.api.Decision(id = "d9", kind = "merge", options = listOf(dev.herdertower.phone.api.DecisionOption("#12 into dev")))
        assertTrue(s.answerDecision(merge, 0).isFailure)
        assertTrue(calls.none { it.startsWith("POST /v1/decisions") })
    }

    @Test fun theBackgroundCheckReadsTheQueueOnlyWithNoStream() = runBlocking {
        var quiet = true
        extra = { r ->
            when (r.url.encodedPath) {
                "/v1/hello" -> json(if (quiet) """{"api":1,"features":["quiet","acts"]}""" else """{"api":1}""")
                "/v1/decisions" -> json("""{"space":null,"count":1,"decisions":[${decision("d1")}]}""")
                "/v1/questions" -> json("""{"questions":[$escalated]}""")
                else -> null
            }
        }
        // The app closed, the phone not the voice: no stream, so the check reads the queue itself.
        val s = session(voice = false)
        Thread.sleep(300)
        assertEquals(0, streams.size)
        val did = withContext(Dispatchers.Main) { s.checkInBackground() }
        assertEquals("1 open", did)
        assertEquals(listOf("d1"), s.decisions.value.map { it.id })
        assertEquals("no stream was opened for it", 0, streams.size)
        // A tower from before the queue: its escalated questions, as the stream would have them.
        quiet = false
        assertEquals("1 open (made here)", withContext(Dispatchers.Main) { s.checkInBackground() })
        assertEquals("legacy-q:q2", s.decisions.value.single().id)
        // On screen the stream follows the queue: the check has nothing to do.
        quiet = true
        s.setForeground(true)
        until(what = "live") { s.link.value is Link.Live }
        val before = calls.count { it.startsWith("GET /v1/hello") }
        assertEquals("the stream is open", withContext(Dispatchers.Main) { s.checkInBackground() })
        assertEquals(before, calls.count { it.startsWith("GET /v1/hello") })
    }

    @Test fun anAnswerThatCanBeARuleIsOfferedOnce() = runBlocking {
        extra = { r ->
            when (r.url.encodedPath) {
                "/v1/decisions" -> json("""{"count":1,"decisions":[${decision("d1")}]}""")
                "/v1/decisions/d1/answer" -> json("""{"ok":true,"id":"d1","state":"answered","answer":"Allow once","rule":{"can":true,"text":"Make this a rule: when a session in sandbox asks \"Allow d1\", answer \"Allow once\" without asking me."}}""")
                "/v1/decisions/d1/rule" -> json("""{"sent":"Make this a rule: …","space":"sandbox"}""", 202)
                else -> null
            }
        }
        val s = session(voice = false)
        s.setForeground(true)
        until(what = "the queue") { s.decisions.value.size == 1 }
        s.answerDecision(s.decisions.value.single(), 0).getOrThrow()
        val o = s.ruleOffers.value.single()
        assertEquals("sandbox", o.space)
        assertEquals("Allow once", o.answer)
        s.makeRule(o).getOrThrow()
        assertTrue(calls.any { it == """POST /v1/decisions/d1/rule {"answer":"Allow once"}""" })
        assertTrue("offered once", s.ruleOffers.value.isEmpty())
    }

    @Test fun anUndoThatWouldLoseWorkAsksFirst() = runBlocking {
        var forced = false
        extra = { r ->
            when (r.url.encodedPath) {
                "/v1/acts/r1/undo" -> if ("true" in r.body!!.utf8()) json("""{"act":{"id":"r1","line":"Opened notes fix.","state":"undone","undo":{"possible":false},"closing":"Closed it. Kept notes: 1 uncommitted change."},"closing":"Closed it. Kept notes: 1 uncommitted change.","sessions_ended":["t9"]}""").also { forced = true }
                    else json("""{"error":{"code":"held","message":"not undone: notes-fix (t9) has 1 uncommitted change.","sessions":[{"address":"t9","name":"notes-fix","why":"1 uncommitted change (notes.txt)"}]}}""", 409)
                else -> null
            }
        }
        val s = session(voice = false)
        s.setForeground(true)
        until(what = "live") { s.decisionsSupported.value == true }
        val held = s.undoAct("r1").exceptionOrNull()
        assertTrue("$held", held is ActHeld)
        assertEquals("1 uncommitted change (notes.txt)", (held as ActHeld).sessions.single().why)
        val r = s.undoAct("r1", force = true).getOrThrow()
        assertTrue(forced)
        assertTrue(s.acts.value["r1"]!!.undone)
        assertEquals("Closed it. Kept notes: 1 uncommitted change.", r.closing)
    }

    @Test fun anAnswerGoesToTheTowerAndTheCardHoldsUntilTheQueueSaysSo() = runBlocking {
        extra = { r ->
            when {
                r.url.encodedPath == "/v1/decisions" && r.method == "GET" -> json("""{"decisions":[${decision("d1")}]}""")
                r.url.encodedPath == "/v1/decisions/d1/answer" -> json("""{"ok":true,"id":"d1","state":"answered","answered":[{"session":"s1","question":"q1","status":"answered"}],"errors":[]}""")
                r.url.encodedPath == "/v1/decisions/d7/answer" -> json("""{"error":{"code":"stopped","message":"the typing stopped half way"}}""", 409)
                else -> null
            }
        }
        val s = session(voice = false)
        s.setForeground(true)
        until(what = "the queue") { s.decisions.value.size == 1 }
        val d = s.decisions.value.single()
        s.answerDecision(d, 0).getOrThrow()
        assertTrue(calls.any { it == """POST /v1/decisions/d1/answer {"choice":0}""" })
        // The tower still lists it open for a moment (a state sent before the answer landed): the card doesn't offer its answers again.
        assertEquals("answered", s.decisions.value.single().state)
        assertFalse(s.decisions.value.single().open)
        // Words instead of an option.
        s.answerDecisionOther(d, "only the read").getOrThrow()
        assertTrue(calls.any { it == """POST /v1/decisions/d1/answer {"other":"only the read"}""" })
        // One the tower refuses stays open and says why.
        val refused = s.answerDecision(d.copy(id = "d7"), 1)
        assertTrue(refused.isFailure)
    }

    @Test fun aRefusedAnswerStaysOpenAndSaysWhy() = runBlocking {
        extra = { r ->
            when {
                r.url.encodedPath == "/v1/decisions" && r.method == "GET" -> json("""{"decisions":[${decision("d1")}]}""")
                r.url.encodedPath == "/v1/decisions/d1/answer" -> json("""{"error":{"code":"changed","message":"the dialog moved on"}}""", 409)
                else -> null
            }
        }
        val s = session(voice = false)
        s.setForeground(true)
        until(what = "the queue") { s.decisions.value.size == 1 }
        assertTrue(s.answerDecision(s.decisions.value.single(), 1).isFailure)
        val d = s.decisions.value.single()
        assertTrue(d.open)
        assertEquals("the dialog moved on", d.error)
    }

    @Test fun aSpacesOwnDecisionsAreReadForIt() = runBlocking {
        extra = { r ->
            when {
                r.url.encodedPath == "/v1/decisions" && r.url.queryParameter("space") == "isa" -> json("""{"decisions":[${decision("muted-one")}]}""")
                r.url.encodedPath == "/v1/decisions" -> json("""{"decisions":[${decision("d1")}]}""")
                else -> null
            }
        }
        val s = session(voice = false)
        s.setForeground(true)
        until(what = "the queue") { s.decisions.value.size == 1 }
        // A muted space's decision isn't in the queue: its own screen asks for it.
        assertEquals(listOf("muted-one"), s.spaceDecisions("isa").map { it.id })
        assertTrue(calls.any { it.startsWith("GET /v1/decisions?space=isa") })
    }

    // A tower from before the queue: the same from its questions and its waiting PRs

    private val escalated = """{"id":"q2","session":"t5","name":"web","kind":"claude-permission","title":"Run a command","detail":"rm -rf build/",""" +
        """"tabs":[{"title":"","question":"Do you want to proceed?","options":[{"label":"Yes"},{"label":"No"}],"multi":false,"allows_other":false}],""" +
        """"recommendations":[{"tab":0,"options":[{"label":"No","why":"it deletes the build folder","choice":1},{"label":"Yes","why":"generated anyway","choice":0}]}],""" +
        """"decision":"escalate","reason":"deleting files","status":"open","at_ms":5000}"""

    @Test fun aTowerWithoutTheQueueGetsOneFromItsQuestionsAndItsWaitingPrs() = runBlocking {
        prAnswer = { r -> if (r.url.encodedPath == "/v1/prs") json("""{"prs":[${pr(checks = "passing", checked = 10)}]}""") else notFound }
        extra = { r ->
            val path = r.url.encodedPath
            when {
                path.startsWith("/v1/decisions") || path == "/v1/missions" || path == "/v1/tell" -> notFound
                path == "/v1/questions" -> json("""{"questions":[$escalated]}""")
                path == "/v1/questions-policy" -> json("""{"policy":"normal"}""")
                path == "/v1/questions/q2/answer" -> json("""{"ok":true}""")
                path == "/v1/spaces" -> json("""{"focus":null,"spaces":[{"space":"Storefront","agenda":"The shop","sessions":[{"address":"t5","name":"web","status":"blocked"},{"address":"t58","name":"mediaroom","status":"idle"}]}]}""")
                else -> null
            }
        }
        answers = listOf({ stream(hello(quiet = false), state(muted = false)) })
        val s = session(voice = false)
        s.setForeground(true)
        // The queue comes as soon as the questions do; each decision's space once the spaces are read too.
        until(what = "the queue made here") { s.decisions.value.size == 2 && s.decisions.value.first().space == "Storefront" }
        assertEquals(false, s.decisionsSupported.value)
        assertEquals(false, s.missionsSupported.value)
        assertEquals(null, s.tell.value)
        val (q, m) = s.decisions.value
        assertEquals("legacy-q:q2", q.id)
        assertEquals("Storefront", q.space)
        assertEquals(listOf("No", "Yes"), q.options.map { it.label })
        assertEquals("merge", m.kind)
        assertEquals(listOf(12L), m.prs.map { it.number })
        // The spaces as missions, each with what waits there.
        until(what = "the missions made here") { s.missions.value.spaces.singleOrNull()?.needs == 2 }
        assertEquals("The shop", s.missions.value.spaces.single().goal)
        // The tower's pick, "No", is the dialog's second option: that goes to the question's own call.
        s.answerDecision(q, 0).getOrThrow()
        assertTrue(calls.any { it == """POST /v1/questions/q2/answer {"answers":[{"tab":0,"choice":1}],"submit":true}""" })
        assertTrue(calls.none { it.startsWith("POST /v1/decisions") })
        until(what = "the card holds") { s.decisions.value.first { it.id == q.id }.state != "open" }
    }

    // Saying and typing for a space

    @Test fun wordsForASpaceGoWithThatSpace() = runBlocking {
        val s = session(voice = false)
        s.setForeground(true)
        until(what = "live") { s.link.value == Link.Live(false) && s.missionsSupported.value == true }
        s.ask("open one more session here", space = "sandbox").getOrThrow()
        s.ask("what's left?").getOrThrow()
        assertTrue(calls.any { it == """POST /v1/ask {"text":"open one more session here","space":"sandbox"}""" })
        assertTrue("the main tower's call is as it always was", calls.any { it == """POST /v1/ask {"text":"what's left?"}""" })
    }

    @Test fun aTowerWithoutSpaceTowersGetsTheSpaceInWords() = runBlocking {
        extra = { r -> if (r.url.encodedPath == "/v1/missions" || r.url.encodedPath.startsWith("/v1/decisions")) notFound else null }
        answers = listOf({ stream(hello(quiet = false), state(muted = false)) })
        val s = session(voice = false)
        s.setForeground(true)
        until(what = "known to be an older tower") { s.missionsSupported.value == false }
        s.ask("open one more session here", space = "sandbox").getOrThrow()
        assertTrue(calls.any { it == """POST /v1/ask {"text":"In the space sandbox: open one more session here","space":"sandbox"}""" })
    }

    @Test fun aTakeForASpaceIsWrittenDownThenSentWithIt() = runBlocking {
        extra = { r ->
            when (r.url.encodedPath) {
                "/v1/transcribe" -> json("""{"heard":" open one more session here ","sent":false}""")
                "/v1/voice" -> json("""{"heard":"what's left?","sent":true}""")
                else -> null
            }
        }
        val s = session(voice = false)
        s.setForeground(true)
        until(what = "live") { s.link.value == Link.Live(false) && s.missionsSupported.value == true }
        val wav = dev.herdertower.phone.voice.Wav.encode(ByteArray(3200))
        val forSpace = s.take(wav, dev.herdertower.phone.voice.TalkTarget.Space("sandbox")).getOrThrow()
        assertEquals("open one more session here", forSpace.heard)
        assertTrue(forSpace.sent)
        assertTrue(calls.any { it == """POST /v1/ask {"text":"open one more session here","space":"sandbox"}""" })
        // The main tower takes the take itself.
        val main = s.take(wav, dev.herdertower.phone.voice.TalkTarget.Main).getOrThrow()
        assertEquals("what's left?", main.heard)
        assertTrue(calls.count { it.startsWith("POST /v1/voice") } == 1)
        // Into a field: written down only, sent nowhere.
        var goal = ""
        val into = s.take(wav, dev.herdertower.phone.voice.TalkTarget.Into { goal = it }).getOrThrow()
        assertFalse(into.sent)
        assertEquals("open one more session here", goal)
        assertEquals(1, calls.count { it.startsWith("POST /v1/ask") })
    }

    @Test fun launchingASpaceAndChangingOne() = runBlocking {
        var muted = false
        var asked = 0
        var closeTries = 0
        val plan = """{"space":"case-exports","goal":"Case exports as PDF","made":true,"sessions":[{"name":"case-exports-eyzflow","repo":"/w/eyzflow","address":null}],"assumed":{"setup":"eyzflow + mediaroom on orca","account":"orca","name":"case-exports"}}"""
        extra = { r ->
            when (r.url.encodedPath) {
                "/v1/missions" -> json("""{"spaces":[{"name":"isa","goal":"Voice clips","state":"open","mute":$muted}],"quiet":["b2c"]}""")
                "/v1/launch" -> if ("dry_run" in r.body!!.utf8()) json(plan.replace("\"made\"", "\"dry_run\":true,\"made\""))
                    else json("""{"action":{"id":"act_1","kind":"launch","space":"case-exports","state":"running","plan":$plan,"receipt":null}}""", 202)
                // Asked about while it runs: the second time it's done.
                "/v1/actions/act_1" -> json(
                    if (++asked < 2) """{"action":{"id":"act_1","kind":"launch","space":"case-exports","state":"running","plan":$plan}}"""
                    else """{"action":{"id":"act_1","kind":"launch","space":"case-exports","state":"done","plan":$plan,"receipt":${plan.replace("\"address\":null", "\"address\":\"s11\"")}}}""",
                )
                "/v1/spaces/isa/mute" -> json("""{"space":"isa","mute":true,"mission":{"name":"isa","goal":"Voice clips","state":"open","mute":true}}""").also { muted = true }
                "/v1/spaces/isa/goal" -> json("""{"space":"isa","goal":"Voice clips, live","mission":{"name":"isa","goal":"Voice clips, live","done_when":"the tour plays","state":"open","mute":true}}""")
                "/v1/spaces/isa/close" -> if (closeTries++ == 0) json("""{"error":{"code":"busy","message":"isa isn't closed: mediaroom (t4) is working.","sessions":[{"address":"t4","name":"mediaroom","status":"working"}]}}""", 409)
                    else json("""{"action":{"id":"act_2","kind":"close","space":"isa","state":"done","receipt":{"closed":"isa","kept_worktrees":[{"path":"/w/.tower-worktrees/isa/mediaroom","why":"uncommitted work"}]}}}""", 202)
                else -> null
            }
        }
        val s = session(voice = false)
        s.setForeground(true)
        until(what = "the missions") { s.missions.value.spaces.size == 1 }
        assertEquals(listOf("b2c"), s.missions.value.quiet)
        // What it would do, first: nothing is opened.
        val p = s.launchPlan("Case exports as PDF", "eyzflow + mediaroom on orca").getOrThrow()
        assertEquals("case-exports", p.space)
        assertTrue(calls.any { it == """POST /v1/launch {"goal":"Case exports as PDF","setup":"eyzflow + mediaroom on orca","dry_run":true}""" })
        // For real: the action at once, followed until it's done.
        val a = s.launch("Case exports as PDF", "eyzflow + mediaroom on orca").getOrThrow()
        assertEquals("act_1", a.id)
        assertTrue(calls.any { it == """POST /v1/launch {"goal":"Case exports as PDF","setup":"eyzflow + mediaroom on orca"}""" })
        until(timeoutMs = 10_000, what = "the launch done") { s.actions.value["act_1"]?.state == "done" }
        assertEquals("s11", s.actions.value["act_1"]?.launch?.sessions?.single()?.address)
        s.muteSpace("isa", true).getOrThrow()
        assertTrue(calls.any { it == """POST /v1/spaces/isa/mute {"on":true}""" })
        assertTrue(s.missions.value.spaces.single().mute)
        s.setGoal("isa", "Voice clips, live", "the tour plays", standing = false).getOrThrow()
        assertTrue(calls.any { it == """POST /v1/spaces/isa/goal {"goal":"Voice clips, live","done_when":"the tour plays","standing":false}""" })
        assertEquals("the tour plays", s.missions.value.spaces.single().doneWhen)
        // A session at work: the close is held back, and says who.
        val busy = s.closeSpace("isa").exceptionOrNull()
        assertTrue("$busy", busy is SpaceBusy)
        assertEquals(listOf("mediaroom"), (busy as SpaceBusy).sessions.map { it.label })
        val closed = s.closeSpace("isa", force = true).getOrThrow()
        assertTrue(calls.any { it == """POST /v1/spaces/isa/close {"force":true}""" })
        assertEquals("uncommitted work", closed.closed?.keptWorktrees?.single()?.why)
    }

    @Test fun aSpacesThreadGrowsWithItsEvent() = runBlocking {
        val later = "event: thread\ndata: {\"space\":\"sandbox\",\"message\":{\"id\":\"h9\",\"at\":9,\"from\":\"s2\",\"to\":\"s1\",\"text\":\"Done.\",\"space\":\"sandbox\",\"delivered\":true}}\n\n"
        answers = listOf({ stream(hello(), state(muted = false), later = later, laterMs = 1200) })
        extra = { r -> if (r.url.encodedPath == "/v1/thread") json("""{"space":"sandbox","more":false,"messages":[{"id":"h5","at":5,"from":"s1","to":"s2","text":"Which port?","space":"sandbox"}]}""") else null }
        val s = session(voice = false)
        s.setForeground(true)
        until(what = "live") { s.missionsSupported.value == true }
        s.loadSpaceThread("sandbox").getOrThrow()
        assertEquals(listOf("h5"), s.spaceThreads.value["sandbox"]?.messages?.map { it.id })
        until(what = "the event's message") { s.spaceThreads.value["sandbox"]?.messages?.map { it.id } == listOf("h5", "h9") }
    }
}
