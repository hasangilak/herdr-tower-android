package dev.herdertower.phone.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** Where the tower is and which certificate it must present. */
data class Endpoint(val host: String, val port: Int, val fingerprint: String)

/**
 * The tower's phone API (v1) over HTTPS with the certificate pinned.
 *
 * The bearer token is read from [token] for each call and never logged; no
 * logging interceptor is installed.
 */
class TowerClient(
    val endpoint: Endpoint,
    private val token: () -> String?,
    base: OkHttpClient = OkHttpClient(),
) {
    private val trust = PinnedTrustManager(endpoint.fingerprint)

    private val http: OkHttpClient = base.newBuilder()
        .sslSocketFactory(trust.sslContext().socketFactory, trust)
        .hostnameVerifier(PinnedTrustManager.ANY_HOST)
        // The tower speaks HTTP/1.1 only.
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        // The tower serves one request per connection.
        .addNetworkInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("Connection", "close").build()) }
        .build()

    /** The event stream: no overall deadline; a `: ping` comes every 10 s, so 25 s of silence means it's gone. */
    private val streaming: OkHttpClient = http.newBuilder()
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    /** Voice uploads wait for the transcription. */
    private val slow: OkHttpClient = http.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()

    /** Files up to 6 x 50 MB: give the upload time on a slow network. */
    private val upload: OkHttpClient = http.newBuilder().writeTimeout(5, TimeUnit.MINUTES).readTimeout(2, TimeUnit.MINUTES).build()

    private fun url(vararg segments: String, query: Map<String, String?> = emptyMap()): HttpUrl {
        val b = HttpUrl.Builder().scheme("https").host(endpoint.host).port(endpoint.port).addPathSegment("v1")
        segments.forEach { b.addPathSegment(it) } // encodes `/` in addresses as %2F
        query.forEach { (k, v) -> if (v != null) b.addQueryParameter(k, v) }
        return b.build()
    }

    private fun request(url: HttpUrl, auth: Boolean = true): Request.Builder {
        val b = Request.Builder().url(url).header("Accept", "application/json")
        if (auth) {
            val t = token() ?: throw UnauthorizedException("unauthorized", "This phone isn't paired.")
            b.header("Authorization", "Bearer $t")
        }
        return b
    }

    private fun <T> json(serializer: KSerializer<T>, value: T): RequestBody =
        TowerJson.encodeToString(serializer, value).toRequestBody(JSON)

    private suspend fun execute(call: Call): Response = withContext(Dispatchers.IO) {
        try {
            call.execute()
        } catch (e: IOException) {
            throw translate(e)
        }
    }

    private suspend fun <T> call(client: OkHttpClient, req: Request, serializer: KSerializer<T>): T {
        val resp = execute(client.newCall(req))
        return withContext(Dispatchers.IO) {
            resp.use {
                val body = it.body.string()
                if (!it.isSuccessful) throw error(it.code, body)
                TowerJson.decodeFromString(serializer, body.ifBlank { "{}" })
            }
        }
    }

    private suspend fun <T> get(serializer: KSerializer<T>, vararg segments: String, query: Map<String, String?> = emptyMap(), client: OkHttpClient = http): T =
        call(client, request(url(*segments, query = query)).get().build(), serializer)

    private suspend fun <T> post(serializer: KSerializer<T>, body: RequestBody, vararg segments: String, client: OkHttpClient = http): T =
        call(client, request(url(*segments)).post(body).build(), serializer)

    // Pairing

    /** `POST /v1/pair`: trades the code shown on the Mac for this phone's token (returned once). */
    suspend fun pair(code: String, name: String): PairResponse {
        val req = request(url("pair"), auth = false).post(json(PairRequest.serializer(), PairRequest(code, name))).build()
        return call(http, req, PairResponse.serializer())
    }

    // Reading

    suspend fun hello(): Hello = get(Hello.serializer(), "hello")
    suspend fun state(): TowerState = get(TowerState.serializer(), "state")
    suspend fun agents(all: Boolean = false): AgentsResponse =
        get(AgentsResponse.serializer(), "agents", query = mapOf("all" to if (all) "1" else null), client = slow)
    suspend fun spaces(): SpacesResponse = get(SpacesResponse.serializer(), "spaces")
    suspend fun jobs(status: String = "all", session: String? = null, limit: Int = 50): JobsResponse =
        get(JobsResponse.serializer(), "jobs", query = mapOf("status" to status, "session" to session, "limit" to limit.toString()))
    suspend fun history(limit: Int = 100, session: String? = null, kind: String? = null, beforeMs: Long? = null): HistoryResponse =
        get(HistoryResponse.serializer(), "history", query = mapOf("limit" to limit.toString(), "session" to session, "kind" to kind, "before_ms" to beforeMs?.toString()))
    suspend fun accounts(): AccountsResponse = get(AccountsResponse.serializer(), "accounts")
    suspend fun screen(address: String, lines: Int = 60): Screen =
        get(Screen.serializer(), "sessions", address, "screen", query = mapOf("lines" to lines.toString()))

    suspend fun screenshots(limit: Int = 60, session: String? = null): ScreenshotsResponse =
        get(ScreenshotsResponse.serializer(), "screenshots", query = mapOf("limit" to limit.toString(), "session" to session))

    /** `GET /v1/screenshots/{id}`: the image's bytes (PNG, JPEG, WebP or GIF). */
    suspend fun screenshotImage(shot: Screenshot): ByteArray = bytes(shot.url, listOf("screenshots", shot.id), "image/*")

    /** `GET /v1/chat?before=&limit=`: a page of the main chat thread (the latest, or before message [before]). */
    suspend fun chat(before: String? = null, limit: Int = 50): ChatPage {
        val req = request(url("chat", query = mapOf("before" to before, "limit" to limit.toString()))).get().build()
        val resp = execute(http.newCall(req))
        return withContext(Dispatchers.IO) {
            resp.use {
                val body = it.body.string()
                if (!it.isSuccessful) throw error(it.code, body)
                ChatApi.page(body)
            }
        }
    }

    /** `GET /v1/device/audio`: where this phone's replies are heard. */
    suspend fun audioRoute(): AudioRoute = get(AudioRoute.serializer(), "device", "audio")

    /** `GET /v1/audio?after=<id>`: clips meant for this phone it missed while no stream was open (last 15 minutes). */
    suspend fun missedClips(after: String?): MissedClips = get(MissedClips.serializer(), "audio", query = mapOf("after" to after))

    // Acting

    /** `PUT /v1/device/audio`: hear the replies to what this phone sends on the phone or on the Mac. */
    suspend fun setAudioRoute(route: String): AudioRoute =
        call(http, request(url("device", "audio")).put(json(AudioRoute.serializer(), AudioRoute(route))).build(), AudioRoute.serializer())

    suspend fun ask(text: String): AskResponse = post(AskResponse.serializer(), json(AskRequest.serializer(), AskRequest(text)), "ask")

    /**
     * `POST /v1/ask` with files: multipart, a `text` field and one part per
     * file (`image` for pictures, `file` for videos and others), in token
     * order. The tower saves them on the Mac and passes their paths after the
     * text, `[Image #N]`, `[Video #N]` or `[File #N: name]` marking each.
     */
    suspend fun ask(text: String, files: List<FilePart>): AskResponse {
        if (files.isEmpty()) return ask(text)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("text", text)
            .apply { files.forEach { addFormDataPart(if (it.isImage) "image" else "file", it.name, partBody(it)) } }
            .build()
        return try {
            post(AskResponse.serializer(), body, "ask", client = upload)
        } catch (e: TowerApiException) {
            // A tower from before files reads the multipart body as empty JSON.
            if (e.status == 400 && e.code in setOf("empty", "bad_json")) throw TowerApiException(400, "no_images", "This tower can't take files yet: it needs the update that adds them to POST /v1/ask.")
            throw e
        }
    }

    private fun partBody(f: FilePart): RequestBody {
        val type = f.mime.toMediaType()
        return f.file?.asRequestBody(type) ?: (f.bytes ?: ByteArray(0)).toRequestBody(type)
    }

    /** `POST /v1/transcribe`: what the Mac hears in a recording, sent nowhere (dictation into the text box). */
    suspend fun transcribe(wav: ByteArray): VoiceResult = post(VoiceResult.serializer(), wav.toRequestBody(WAV), "transcribe", client = slow)

    /** `GET /v1/attachments/{folder}/{name}`: a picture sent with a message, from its path on the Mac. */
    suspend fun attachmentImage(path: String): ByteArray {
        val parts = path.trimEnd('/').split('/')
        require(parts.size >= 2) { "not an attachment path" }
        return bytes("", listOf("attachments", parts[parts.size - 2], parts.last()), "image/*")
    }

    suspend fun answer(address: String, approve: Boolean, note: String? = null) {
        val body = json(AnswerRequest.serializer(), AnswerRequest(if (approve) "approve" else "refuse", note?.trim()?.takeIf { it.isNotEmpty() }))
        post(Ignored.serializer(), body, "agents", address, "answer")
    }

    /** Focuses a space, or clears focus with null (sent as `{"space": null}`). */
    suspend fun focus(space: String?): FocusResponse {
        val body = buildJsonObject { put("space", space?.let { JsonPrimitive(it) } ?: JsonNull) }.toString().toRequestBody(JSON)
        return post(FocusResponse.serializer(), body, "focus")
    }

    // Questions (docs/questions.md)

    suspend fun questions(): List<Question> = get(QuestionsResponse.serializer(), "questions").questions

    suspend fun question(id: String): Question = get(Question.serializer(), "questions", id)

    /**
     * `POST /v1/questions/{id}/answer`: types the answers into the session's
     * dialog and submits. With files (for a tab's typed words) it's
     * multipart: the JSON as part `json`, each file as part `tab<N>`.
     * A `409` says where it stopped (`stopped`, `changed`, `cannot_answer`).
     */
    suspend fun answerQuestion(id: String, answers: List<TabAnswer>, submit: Boolean = true) {
        val text = QuestionsApi.body(answers, submit)
        val files = answers.flatMap { a -> a.files.map { a.tab to it } }
        val body = if (files.isEmpty()) text.toRequestBody(JSON) else MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("json", null, text.toRequestBody(JSON))
            .apply { files.forEach { (tab, f) -> addFormDataPart("tab$tab", f.name, partBody(f)) } }
            .build()
        post(Ignored.serializer(), body, "questions", id, "answer", client = if (files.isEmpty()) slow else upload)
    }

    /** `DELETE /v1/questions/{id}`: not a question after all, or answered in the terminal. */
    suspend fun dismissQuestion(id: String) {
        call(http, request(url("questions", id)).delete().build(), Ignored.serializer())
    }

    /** `POST /v1/questions/{id}/interpret`: what words said for [tab] pick ("the second one"), to show before sending. */
    suspend fun interpret(id: String, tab: Int, text: String): Interpreted {
        val body = buildJsonObject { put("tab", JsonPrimitive(tab)); put("text", JsonPrimitive(text)) }.toString().toRequestBody(JSON)
        return post(Interpreted.serializer(), body, "questions", id, "interpret", client = slow)
    }

    /** `POST /v1/questions/{id}/voice?tab=N`: a spoken answer (WAV), transcribed and interpreted on the Mac. */
    suspend fun questionVoice(id: String, tab: Int, wav: ByteArray): Interpreted =
        call(slow, request(url("questions", id, "voice", query = mapOf("tab" to tab.toString()))).post(wav.toRequestBody(WAV)).build(), Interpreted.serializer())

    suspend fun questionPolicy(): String = get(QuestionPolicy.serializer(), "questions-policy").policy

    suspend fun setQuestionPolicy(policy: String): String =
        call(http, request(url("questions-policy")).put(json(QuestionPolicy.serializer(), QuestionPolicy(policy))).build(), QuestionPolicy.serializer()).policy

    suspend fun hush() {
        post(Ignored.serializer(), ByteArray(0).toRequestBody(null), "hush")
    }

    suspend fun mode(mode: String) {
        post(Ignored.serializer(), json(ModeRequest.serializer(), ModeRequest(mode)), "mode")
    }

    /** `POST /v1/voice`: one recorded take as WAV; returns what the Mac heard. */
    suspend fun voice(wav: ByteArray): VoiceResult = post(VoiceResult.serializer(), wav.toRequestBody(WAV), "voice", client = slow)

    /** `GET /v1/audio/{id}`: a reply clip's WAV bytes. */
    suspend fun audio(clip: AudioClip): ByteArray = bytes(clip.url, listOf("audio", clip.id), "audio/wav")

    /** Bytes from the tower's own [path] (`/v1/…`), or from [segments] when it has none. */
    private suspend fun bytes(path: String, segments: List<String>, accept: String): ByteArray {
        val u = path.takeIf { it.startsWith("/v1/") }?.let { p ->
            HttpUrl.Builder().scheme("https").host(endpoint.host).port(endpoint.port).encodedPath(p).build()
        } ?: url(*segments.toTypedArray())
        val resp = execute(http.newCall(request(u).header("Accept", accept).get().build()))
        return withContext(Dispatchers.IO) {
            resp.use {
                if (!it.isSuccessful) throw error(it.code, it.body.string())
                it.body.bytes()
            }
        }
    }

    /** `DELETE /v1/device`: unpairs this phone; its token stops working. */
    suspend fun unpair() {
        call(http, request(url("device")).delete().build(), Ignored.serializer())
    }

    // Live

    /**
     * `GET /v1/events` as a flow of events. It completes when the tower ends
     * the stream and throws when the connection fails; cancelling the
     * collector closes the connection. With [voice] this phone is the
     * tower's voice while the stream is open.
     */
    fun events(voice: Boolean): Flow<TowerEvent> = callbackFlow {
        val req = request(url("events", query = mapOf("voice" to if (voice) "1" else null)))
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .get()
            .build()
        val call = streaming.newCall(req)
        launch(Dispatchers.IO) {
            try {
                val resp = try { call.execute() } catch (e: IOException) { throw translate(e) }
                resp.use {
                    if (!it.isSuccessful) throw error(it.code, it.body.string())
                    val source = it.body.source()
                    val parser = SseParser(
                        onEvent = { raw -> trySend(TowerEvent.decode(raw)) },
                        onComment = { trySend(TowerEvent.Ping) },
                    )
                    while (true) {
                        val line = try { source.readUtf8Line() } catch (e: IOException) { throw translate(e) } ?: break
                        parser.line(line)
                    }
                }
                close()
            } catch (e: Throwable) {
                close(e)
            }
        }
        awaitClose { call.cancel() }
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)

    @kotlinx.serialization.Serializable
    private class Ignored

    companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        val WAV = "audio/wav".toMediaType()

        /** Turns an error response into the matching exception. */
        fun error(status: Int, body: String): TowerApiException {
            val detail = runCatching { TowerJson.decodeFromString(ErrorBody.serializer(), body).error }.getOrNull()
            val code = detail?.code?.takeIf { it.isNotEmpty() } ?: "http_$status"
            val message = detail?.message?.takeIf { it.isNotEmpty() } ?: "The tower answered $status."
            return if (status == 401) UnauthorizedException(code, message) else TowerApiException(status, code, message, detail?.triesLeft)
        }

        /** A changed certificate anywhere in the failure: its causes, or other routes' failures OkHttp kept as suppressed. */
        private fun mismatchIn(t: Throwable, depth: Int = 0): CertificateMismatchException? {
            if (t is CertificateMismatchException) return t
            if (depth > 8) return null
            t.cause?.let { c -> mismatchIn(c, depth + 1)?.let { return it } }
            t.suppressed.forEach { s -> mismatchIn(s, depth + 1)?.let { return it } }
            return null
        }

        /** Network failures in words a person understands; a changed certificate stays recognisable. */
        fun translate(e: IOException): IOException {
            mismatchIn(e)?.let { return SSLMismatch(it) }
            return when (e) {
                is TowerApiException, is UnreachableException, is SSLMismatch -> e
                is SocketTimeoutException -> UnreachableException("The tower didn't answer in time.", e)
                is ConnectException, is NoRouteToHostException -> UnreachableException("Can't reach the tower. Is the Mac on this network and `tower phone enable` on?", e)
                is UnknownHostException -> UnreachableException("Unknown address.", e)
                is SSLException -> UnreachableException("Secure connection failed: ${e.message}", e)
                else -> UnreachableException(e.message ?: "Connection failed.", e)
            }
        }
    }
}

/** The handshake failed because the certificate isn't the paired one: re-pair, or someone is in between. */
class SSLMismatch(val mismatch: CertificateMismatchException) : IOException(mismatch.message, mismatch)
