package dev.herdertower.phone.api

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** The client against a fake tower: HTTPS with a self-signed ECDSA certificate the client pins, as the Mac's. */
class TowerClientTest {
    private lateinit var server: MockWebServer
    private val cert = HeldCertificate.Builder().commonName("herder-tower").ecdsa256().build()
    private val fp = Fingerprint.of(cert.certificate)
    private var token: String? = "htk_secret"

    @Before fun setUp() {
        server = MockWebServer()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory())
        server.start()
    }

    @After fun tearDown() = server.close()

    private fun client(pin: String = fp) = TowerClient(Endpoint(server.hostName, server.port, pin), { token })

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    private fun taken(): RecordedRequest = server.takeRequest(5, TimeUnit.SECONDS) ?: error("no request")

    @Test fun pairsWithoutATokenAndReturnsTheTokenOnce() = runBlocking {
        token = null
        server.enqueue(json("""{"token": "htk_abc", "device": {"id": "d_3fa9c2e1", "name": "Pixel 9"}, "fingerprint": "$fp", "api": 1}"""))
        val r = client().pair("48213907", "Pixel 9")
        assertEquals("htk_abc", r.token)
        assertEquals(Device("d_3fa9c2e1", "Pixel 9"), r.device)
        val req = taken()
        assertEquals("POST", req.method)
        assertEquals("/v1/pair", req.url.encodedPath)
        assertNull(req.headers["Authorization"])
        assertEquals("""{"code":"48213907","name":"Pixel 9"}""", req.body!!.utf8())
    }

    @Test fun aWrongCodeSaysHowManyTriesAreLeft() = runBlocking {
        server.enqueue(json("""{"error": {"code": "wrong_code", "message": "that isn't the code on the Mac", "tries_left": 4}}""", 403))
        try {
            client().pair("00000000", "p")
            fail()
        } catch (e: TowerApiException) {
            assertEquals(403, e.status)
            assertEquals("wrong_code", e.code)
            assertEquals(4, e.triesLeft)
        }
    }

    @Test fun sendsTheBearerTokenAndOneRequestPerConnection() = runBlocking {
        server.enqueue(json("""{"api":1,"tower":"0.1.0","device":{"id":"d_1","name":"Pixel"},"voice":"af_bella","phone_is_voice":true,"new_key":1}"""))
        val h = client().hello()
        assertEquals(Hello(1, "0.1.0", Device("d_1", "Pixel"), "af_bella", true), h)
        val req = taken()
        assertEquals("Bearer htk_secret", req.headers["Authorization"])
        assertEquals("close", req.headers["Connection"])
    }

    @Test fun aDifferentCertificateFailsTheHandshake() = runBlocking {
        server.enqueue(json("{}"))
        try {
            client(pin = "0".repeat(64)).hello()
            fail()
        } catch (e: SSLMismatch) {
            assertEquals(fp, e.mismatch.got)
        }
    }

    @Test fun unauthorizedMeansRepair() = runBlocking {
        server.enqueue(json("""{"error": {"code": "unauthorized", "message": "unknown token"}}""", 401))
        try {
            client().state()
            fail()
        } catch (e: UnauthorizedException) {
            assertEquals("unauthorized", e.code)
        }
    }

    @Test fun addressesAreEncodedInPaths() = runBlocking {
        server.enqueue(json("{}", 202))
        client().answer("vps/t3", approve = true, note = "only the read")
        val req = taken()
        assertEquals("/v1/agents/vps%2Ft3/answer", req.url.encodedPath)
        assertEquals("""{"decision":"approve","note":"only the read"}""", req.body!!.utf8())

        server.enqueue(json("""{"address": "vps/t3", "screen": "Allow? (y/n)"}"""))
        val s = client().screen("vps/t3", lines = 120)
        assertEquals("Allow? (y/n)", s.screen)
        val r2 = taken()
        assertEquals("/v1/sessions/vps%2Ft3/screen", r2.url.encodedPath)
        assertEquals("120", r2.url.queryParameter("lines"))
    }

    @Test fun refuseWithoutANoteLeavesItOut() = runBlocking {
        server.enqueue(json("{}", 202))
        client().answer("t22", approve = false, note = "  ")
        assertEquals("""{"decision":"refuse"}""", taken().body!!.utf8())
    }

    @Test fun clearingFocusSendsAnExplicitNull() = runBlocking {
        server.enqueue(json("""{"focus": null}"""))
        assertNull(client().focus(null).focus)
        assertEquals("""{"space":null}""", taken().body!!.utf8())
        server.enqueue(json("""{"focus": "B2C support"}"""))
        assertEquals("B2C support", client().focus("B2C support").focus)
        assertEquals("""{"space":"B2C support"}""", taken().body!!.utf8())
    }

    @Test fun readsListsWithQueries() = runBlocking {
        server.enqueue(json("""{"jobs":[{"job":"j1","session":"t3","name":"api","space":"B2C","account":null,"status":"blocked",
            "asked":"fix the build","summary":"","started_ms":1,"updated_ms":2}]}"""))
        val jobs = client().jobs(status = "open", session = "t3", limit = 10).jobs
        assertTrue(jobs.single().open)
        val q = taken().url
        assertEquals(listOf("open", "t3", "10"), listOf(q.queryParameter("status"), q.queryParameter("session"), q.queryParameter("limit")))

        server.enqueue(json("""{"entries":[{"at":1,"kind":"request","text":"hi"},{"at":2,"kind":"answer","text":"hello","held":true}]}"""))
        val h = client().history(limit = 2, kind = "request,answer", beforeMs = 99).entries
        assertEquals(listOf("request", "answer"), h.map { it.kind })
        assertTrue(h[1].held)
        assertEquals("before_ms=99", taken().url.encodedQuery!!.split('&').first { it.startsWith("before_ms") })

        server.enqueue(json("""{"focus":"B2C","spaces":[{"space":"B2C","focus":true,"agenda":null,"unread":2,"running_jobs":1,
            "sessions":[{"address":"t3","name":"api","status":"working","account":"eyz"}],"last_activity_ms":5}]}"""))
        val sp = client().spaces()
        assertEquals("B2C", sp.focus)
        assertEquals("eyz", sp.spaces.single().sessions.single().account)

        server.enqueue(json("""{"default":"main","accounts":[{"name":"main","email":"a@b"},{"name":"eyz"}]}"""))
        assertEquals(listOf("main", "eyz"), client().accounts().accounts.map { it.name })
    }

    @Test fun uploadsAVoiceTakeAsWav() = runBlocking {
        server.enqueue(json("""{"heard": "what's everyone doing", "sent": true}"""))
        val wav = dev.herdertower.phone.voice.Wav.encode(ByteArray(6400))
        val r = client().voice(wav)
        assertEquals(VoiceResult("what's everyone doing", true), r)
        val req = taken()
        assertEquals("audio/wav", req.headers["Content-Type"])
        assertEquals(wav.size.toString(), req.headers["Content-Length"])
        assertEquals(wav.size, req.body!!.size)
    }

    @Test fun fetchesClipsWithTheToken() = runBlocking {
        val clip = dev.herdertower.phone.voice.Wav.encode(ByteArray(480), 24_000)
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "audio/wav").body(okio.Buffer().write(clip)).build())
        val bytes = client().audio(AudioClip(id = "a17", url = "/v1/audio/a17"))
        assertEquals(clip.toList(), bytes.toList())
        val req = taken()
        assertEquals("/v1/audio/a17", req.url.encodedPath)
        assertEquals("Bearer htk_secret", req.headers["Authorization"])
    }

    @Test fun listsScreenshotsAndFetchesTheImage() = runBlocking {
        server.enqueue(json("""{"screenshots":[{"id":"1794160000000-login.png","at":1794160000000,"session":"t22","name":"Claude in eyzflow",
            "space":"B2C support","account":"default","job":null,"caption":"The new login page","path":"/x/login.png","url":"/v1/screenshots/1794160000000-login.png"}]}"""))
        val list = client().screenshots(limit = 30, session = "t22").screenshots
        assertEquals("The new login page", list.single().caption)
        assertEquals("Claude in eyzflow", list.single().who)
        val q = taken().url
        assertEquals("/v1/screenshots", q.encodedPath)
        assertEquals("30", q.queryParameter("limit"))
        assertEquals("t22", q.queryParameter("session"))

        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 1, 2, 3)
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "image/png").body(okio.Buffer().write(png)).build())
        assertEquals(png.toList(), client().screenshotImage(list.single()).toList())
        val req = taken()
        assertEquals("/v1/screenshots/1794160000000-login.png", req.url.encodedPath)
        assertEquals("Bearer htk_secret", req.headers["Authorization"])
    }

    @Test fun aScreenshotHistoryEntryCarriesItsId() {
        val e = TowerJson.decodeFromString(HistoryEntry.serializer(), """{"at":1,"kind":"screenshot","session":"t22","id":"a.png","caption":"Login","path":"/x/a.png","text":"Login"}""")
        assertEquals("a.png", e.id)
        assertEquals("Login", e.caption)
    }

    @Test fun asksWithPicturesAsMultipart() = runBlocking {
        server.enqueue(json("""{"sent": "[Image #1] what's wrong? [Image #2]", "attachments": ["/a/1-a.png", "/a/2-b.jpg"]}""", 202))
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 1)
        val jpg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 2)
        val r = client().ask("[Image #1] what's wrong? [Image #2]", listOf(FilePart("a.png", "image/png", png), FilePart("b.jpg", "image/jpeg", jpg)))
        assertEquals(2, r.attachments.size)
        val req = taken()
        assertEquals("/v1/ask", req.url.encodedPath)
        val type = req.headers["Content-Type"]!!
        assertTrue(type.startsWith("multipart/form-data; boundary="))
        assertEquals(req.body!!.size.toString(), req.headers["Content-Length"]) // no chunked uploads
        val body = req.body!!.string(Charsets.ISO_8859_1)
        val text = body.indexOf("Content-Disposition: form-data; name=\"text\"")
        assertTrue(text >= 0 && body.indexOf("[Image #1] what's wrong? [Image #2]") > text)
        val first = body.indexOf("name=\"image\"; filename=\"a.png\"")
        val second = body.indexOf("name=\"image\"; filename=\"b.jpg\"")
        assertTrue(first in 0 until second)
        assertTrue(body.contains("Content-Type: image/jpeg"))
    }

    @Test fun filesThatArentPicturesGoAsFilePartsFromDisk() = runBlocking {
        server.enqueue(json("""{"sent": "[File #1: notes.pdf] read this", "attachments": ["/a/1-notes.pdf"]}""", 202))
        val pdf = java.io.File.createTempFile("notes", ".pdf").apply { writeBytes("%PDF-1.7 hello".toByteArray()); deleteOnExit() }
        client().ask("[File #1: notes.pdf] read this", listOf(FilePart("notes.pdf", "application/pdf", file = pdf)))
        val req = taken()
        val body = req.body!!.string(Charsets.ISO_8859_1)
        assertTrue(body.contains("name=\"file\"; filename=\"notes.pdf\""))
        assertTrue(body.contains("Content-Type: application/pdf"))
        assertTrue(body.contains("%PDF-1.7 hello"))
        assertEquals(req.body!!.size.toString(), req.headers["Content-Length"])
    }

    @Test fun transcribesWithoutSending() = runBlocking {
        server.enqueue(json("""{"heard": "check the login page", "sent": false}"""))
        val r = client().transcribe(dev.herdertower.phone.voice.Wav.encode(ByteArray(16000)))
        assertEquals(VoiceResult("check the login page", false), r)
        val req = taken()
        assertEquals("/v1/transcribe", req.url.encodedPath)
        assertEquals("audio/wav", req.headers["Content-Type"])
    }

    @Test fun fetchesASentPictureByTheEndOfItsPath() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "image/png").body(okio.Buffer().write(byteArrayOf(1, 2))).build())
        client().attachmentImage("/Users/x/.local/share/herder-tower/attachments/1794165000000-phone/1-my login.png")
        assertEquals("/v1/attachments/1794165000000-phone/1-my%20login.png", taken().url.encodedPath)
    }

    @Test fun theStateCarriesTheBrainsWork() {
        val st = TowerJson.decodeFromString(TowerState.serializer(), """{"work": {"word": "Reading", "detail": "eyzflow", "since_ms": 10, "at_ms": 14, "done_ms": 0}}""")
        assertEquals(Work("Reading", "eyzflow", 10, 14, 0), st.work)
        assertNull(TowerJson.decodeFromString(TowerState.serializer(), """{"work": null}""").work)
    }

    @Test fun anOlderTowerSaysItCantTakePictures() = runBlocking {
        server.enqueue(json("""{"error": {"code": "empty", "message": "send {\"text\": \"...\"}"}}""", 400))
        try {
            client().ask("look", listOf(FilePart("a.png", "image/png", byteArrayOf(1))))
            fail()
        } catch (e: TowerApiException) {
            assertEquals("no_images", e.code)
        }
    }

    @Test fun readsAndSetsTheAudioRoute() = runBlocking {
        server.enqueue(json("""{"route": "mac"}"""))
        assertEquals("mac", client().audioRoute().route)
        assertEquals("/v1/device/audio", taken().url.encodedPath)
        server.enqueue(json("""{"route": "phone"}"""))
        assertEquals("phone", client().setAudioRoute("phone").route)
        val req = taken()
        assertEquals("PUT", req.method)
        assertEquals("""{"route":"phone"}""", req.body!!.utf8())
    }

    @Test fun listsMissedClipsAfterTheLastOne() = runBlocking {
        server.enqueue(json("""{"clips": [{"id": "a18", "kind": "reply", "text": "Done.", "ms": 900, "url": "/v1/audio/a18", "at_ms": 1794170000000}]}"""))
        val clips = client().missedClips("a17").clips
        assertEquals(AudioClip("a18", "reply", "Done.", 900, "/v1/audio/a18", 1794170000000), clips.single())
        val req = taken()
        assertEquals("/v1/audio", req.url.encodedPath)
        assertEquals("a17", req.url.queryParameter("after"))
    }

    @Test fun helloCarriesTheRoute() {
        assertEquals("mac", TowerJson.decodeFromString(Hello.serializer(), """{"api":1,"audio_route":"mac"}""").audioRoute)
        assertNull(TowerJson.decodeFromString(Hello.serializer(), """{"api":1}""").audioRoute)
    }

    @Test fun pagesTheThreadBack() = runBlocking {
        server.enqueue(json("""{"messages":[{"id":"m7","at":7,"role":"user","text":"hi","source":"mac"}],"more":false}"""))
        val p = client().chat(before = "m8", limit = 20)
        assertEquals("mac", p.messages.single().source)
        assertEquals(false, p.more)
        val q = taken().url
        assertEquals("/v1/chat", q.encodedPath)
        assertEquals("m8", q.queryParameter("before"))
        assertEquals("20", q.queryParameter("limit"))
    }

    @Test fun answersAQuestionAsJson() = runBlocking {
        server.enqueue(json("""{"ok":true,"id":"q1","submitted":true}"""))
        client().answerQuestion("q1", listOf(TabAnswer(0, Choice.Pick(1)), TabAnswer(1, Choice.Several(listOf(0, 2), "CSV"))))
        val req = taken()
        assertEquals("/v1/questions/q1/answer", req.url.encodedPath)
        assertEquals("""{"answers":[{"tab":0,"choice":1},{"tab":1,"choice":{"options":[0,2],"other":"CSV"}}],"submit":true}""", req.body!!.utf8())
    }

    @Test fun aTypedAnswerWithFilesIsMultipart() = runBlocking {
        server.enqueue(json("""{"ok":true}"""))
        val png = FilePart("sketch.png", "image/png", byteArrayOf(0x89.toByte(), 'P'.code.toByte()))
        client().answerQuestion("q1", listOf(TabAnswer(2, Choice.Own("like this"), listOf(png))))
        val body = taken().body!!.string(Charsets.ISO_8859_1)
        val j = body.indexOf("name=\"json\"")
        assertTrue(j >= 0 && body.indexOf("{\"answers\":[{\"tab\":2,\"choice\":{\"other\":\"like this\"}}]") > j)
        assertTrue(body.contains("name=\"tab2\"; filename=\"sketch.png\""))
    }

    @Test fun aStoppedAnswerSaysWhere() = runBlocking {
        server.enqueue(json("""{"error":{"code":"changed","message":"the dialog moved on"}}""", 409))
        val e = runCatching { client().answerQuestion("q1", listOf(TabAnswer(0, Choice.Pick(0)))) }.exceptionOrNull()
        assertTrue(e is TowerApiException && e.code == "changed" && e.message!!.contains("moved on"))
    }

    @Test fun questionPolicyAndVoice() = runBlocking {
        server.enqueue(json("""{"policy":"keep-moving"}"""))
        assertEquals("keep-moving", client().setQuestionPolicy("keep-moving"))
        val put = taken()
        assertEquals("PUT", put.method)
        assertEquals("""{"policy":"keep-moving"}""", put.body!!.utf8())
        server.enqueue(json("""{"tab":1,"heard":"the second one","choice":{"options":[1],"other":null}}"""))
        val r = client().questionVoice("q1", 1, byteArrayOf(1, 2))
        assertEquals("/v1/questions/q1/voice?tab=1", taken().url.let { it.encodedPath + "?" + it.encodedQuery })
        assertEquals(Choice.Several(listOf(1)), r.parsed)
        server.enqueue(json("", 200))
        client().dismissQuestion("q1")
        assertEquals("DELETE", taken().method)
    }

    @Test fun behaviorCalls() = runBlocking {
        server.enqueue(json("""{"behavior":{"id":"x","title":"T"},"change":{"at":1,"op":"add","id":"x","title":"T","by":"user","diff":"+ rule"}}""", 201))
        val added = client().addBehavior("T", "Misc", "one line", "rule")
        assertEquals("add", added.change!!.op)
        var req = taken()
        assertEquals("POST", req.method)
        assertEquals("""{"title":"T","category":"Misc","summary":"one line","text":"rule"}""", req.body!!.utf8())
        server.enqueue(json("""{"behavior":{"id":"x","enabled":false},"change":{"op":"disable"}}"""))
        client().updateBehavior("x", BehaviorPatch(enabled = false))
        req = taken()
        assertEquals("PUT", req.method)
        assertEquals("/v1/behaviors/x", req.url.encodedPath)
        server.enqueue(json("""{"id":"x","diff":"- a\n+ b"}"""))
        assertEquals("- a\n+ b", client().previewBehavior("x", "b").diff)
        assertEquals("/v1/behaviors/x/preview", taken().url.encodedPath)
        server.enqueue(json("""{"error":{"code":"behavior","message":"nothing to undo"}}""", 400))
        val e = runCatching { client().undoBehavior() }.exceptionOrNull()
        assertTrue(e is TowerApiException && e.message!!.contains("nothing to undo"))
        server.enqueue(json("""{"changes":[{"at":2,"op":"edit","id":"x","title":"T","by":"brain","diff":""}]}"""))
        assertEquals("brain", client().behaviorHistory(10).single().by)
        assertEquals("limit=10", taken().let { taken() }.url.encodedQuery)
    }

    @Test fun hushPostsAnEmptyBody() = runBlocking {
        server.enqueue(json("""{"ok": true}"""))
        client().hush()
        val req = taken()
        assertEquals("POST", req.method)
        assertEquals("0", req.headers["Content-Length"])
    }

    @Test fun streamsEventsInOrder() = runBlocking {
        val body = buildString {
            append("id: 1\nevent: hello\ndata: {\"api\":1,\"device\":\"d_1\",\"voice\":true}\n\n")
            append("id: 2\nevent: state\ndata: {\"version\":3,\"phase\":\"thinking\"}\n\n")
            append(": ping\n\n")
            append("id: 3\nevent: audio\ndata: {\"id\":\"a1\",\"kind\":\"ack\",\"text\":\"Okay.\",\"ms\":400,\"url\":\"/v1/audio/a1\"}\n\n")
            append("id: 4\nevent: audio\ndata: {\"id\":\"a2\",\"kind\":\"reply\",\"text\":\"Nothing is blocked.\",\"ms\":1200,\"url\":\"/v1/audio/a2\"}\n\n")
            append("id: 5\nevent: history\ndata: {\"at\":7,\"kind\":\"answer\",\"text\":\"Nothing is blocked.\"}\n\n")
            append("id: 6\nevent: pause\ndata: {}\n\nid: 7\nevent: resume\ndata: {}\n\nid: 8\nevent: hush\ndata: {}\n\n")
            append("id: 9\nevent: later\ndata: {}\n\n")
        }
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "text/event-stream").body(body).build())
        val events = client().events(voice = true).toList()
        assertEquals(
            listOf(
                TowerEvent.Hello(StreamHello(1, "d_1", true)),
                TowerEvent.State(TowerState(version = 3, phase = "thinking")),
                TowerEvent.Ping,
                TowerEvent.Audio(AudioClip("a1", "ack", "Okay.", 400, "/v1/audio/a1")),
                TowerEvent.Audio(AudioClip("a2", "reply", "Nothing is blocked.", 1200, "/v1/audio/a2")),
                TowerEvent.History(HistoryEntry(at = 7, kind = "answer", text = "Nothing is blocked.")),
                TowerEvent.Pause,
                TowerEvent.Resume,
                TowerEvent.Hush,
                TowerEvent.Unknown("later"),
            ),
            events,
        )
        val req = taken()
        assertEquals("/v1/events", req.url.encodedPath)
        assertEquals("1", req.url.queryParameter("voice"))
        assertEquals("Bearer htk_secret", req.headers["Authorization"])
        assertEquals("text/event-stream", req.headers["Accept"])
    }

    @Test fun thePanelFeedHasNoVoiceParameter() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("id: 1\nevent: hello\ndata: {\"api\":1,\"device\":\"d\",\"voice\":false}\n\n").build())
        client().events(voice = false).toList()
        assertNull(taken().url.queryParameter("voice"))
    }

    @Test fun aRevokedStreamFailsAsUnauthorized() = runBlocking {
        server.enqueue(json("""{"error": {"code": "unauthorized", "message": "revoked"}}""", 401))
        try {
            client().events(voice = true).toList()
            fail()
        } catch (e: UnauthorizedException) {
            assertEquals("revoked", e.message)
        }
    }

    @Test fun anUnreachableTowerSaysSo() = runBlocking {
        val port = server.port
        server.close()
        try {
            TowerClient(Endpoint("127.0.0.1", port, fp), { token }).hello()
            fail()
        } catch (e: UnreachableException) {
            assertTrue(e.message!!.isNotEmpty())
        }
    }

    @Test fun theProbeReadsTheFingerprintForManualPairing() {
        assertEquals(fp, CertificateProbe.fingerprint(server.hostName, server.port))
    }
}
