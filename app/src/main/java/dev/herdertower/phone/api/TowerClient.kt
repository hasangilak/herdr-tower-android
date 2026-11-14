package dev.herdertower.phone.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.Call
import okhttp3.Callback
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
    /** How long a connection may take: short when trying one address of several (the LAN, before Tailscale). */
    connectTimeoutMs: Long = 5_000,
) {
    private val trust = PinnedTrustManager(endpoint.fingerprint)

    private val http: OkHttpClient = base.newBuilder()
        // Calls are queued on OkHttp's dispatcher (so they can be cancelled); the tower serves one request per
        // connection, and a reconnect asks for several things at once: don't hold them to OkHttp's 5 per host.
        .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 32; maxRequestsPerHost = 16 })
        .sslSocketFactory(trust.sslContext().socketFactory, trust)
        .hostnameVerifier(PinnedTrustManager.ANY_HOST)
        // The tower speaks HTTP/1.1 only.
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        // The tower serves one request per connection.
        .addNetworkInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("Connection", "close").build()) }
        .build()

    /**
     * The event stream: no overall deadline; a `: ping` comes every 10 s, so 22 s of silence (two missed) means it's
     * gone: a route that died without a word (Tailscale stopped on the Mac) is left that soon. The phone's own network
     * changes are caught sooner, by the session's network check.
     */
    private val streaming: OkHttpClient = http.newBuilder()
        .readTimeout(22, TimeUnit.SECONDS)
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

    /**
     * Runs [call] and waits for its response. Cancelling the coroutine cancels
     * the call: a blocking `execute()` would carry on, so an upload the screen
     * gave up on would still reach the tower.
     */
    private suspend fun execute(call: Call): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(translate(e))
            }

            override fun onResponse(call: Call, response: Response) {
                // Cancelled as it came: close it off the main thread (closing a TLS socket there throws).
                cont.resume(response) { _, r, _ -> closeOffMain(r) }
            }
        })
    }

    /** Runs [call] and reads its response, both on the IO threads; the response is always closed. */
    private suspend fun <T> fetch(call: Call, read: (Response) -> T): T = withContext(Dispatchers.IO) {
        execute(call).use(read)
    }

    private suspend fun <T> call(client: OkHttpClient, req: Request, serializer: KSerializer<T>): T =
        fetch(client.newCall(req)) {
            val body = it.body.string()
            if (!it.isSuccessful) throw error(it.code, body)
            TowerJson.decodeFromString(serializer, body.ifBlank { "{}" })
        }

    private suspend fun <T> get(serializer: KSerializer<T>, vararg segments: String, query: Map<String, String?> = emptyMap(), client: OkHttpClient = http): T =
        call(client, request(url(*segments, query = query)).get().build(), serializer)

    private suspend fun <T> post(serializer: KSerializer<T>, body: RequestBody, vararg segments: String, client: OkHttpClient = http): T =
        call(client, request(url(*segments)).post(body).build(), serializer)

    // Pairing

    /** `POST /v1/pair`: trades the code shown on the Mac for this phone's token (returned once). */
    suspend fun pair(code: String, name: String, installId: String? = null): PairResponse {
        val req = request(url("pair"), auth = false).post(json(PairRequest.serializer(), PairRequest(code, name, installId))).build()
        return call(http, req, PairResponse.serializer())
    }

    /** `PATCH /v1/device`: this phone's name on the Mac. */
    suspend fun renameDevice(name: String): Device =
        call(http, request(url("device")).patch(json(RenameRequest.serializer(), RenameRequest(name))).build(), DeviceResponse.serializer()).device

    /** `GET /v1/network`: the Mac's addresses now and the network it is on. */
    suspend fun network(): HostsUpdate = get(HostsUpdate.serializer(), "network")

    // Reading

    suspend fun hello(): Hello = get(Hello.serializer(), "hello")
    suspend fun state(): TowerState = get(TowerState.serializer(), "state")
    suspend fun agents(all: Boolean = false): AgentsResponse =
        get(AgentsResponse.serializer(), "agents", query = mapOf("all" to if (all) "1" else null), client = slow)
    suspend fun spaces(): SpacesResponse = get(SpacesResponse.serializer(), "spaces")
    suspend fun jobs(status: String = "all", session: String? = null, limit: Int = 50): JobsResponse =
        get(JobsResponse.serializer(), "jobs", query = mapOf("status" to status, "session" to session, "limit" to limit.toString()))
    suspend fun history(limit: Int = 100, session: String? = null, kind: String? = null, beforeMs: Long? = null, space: String? = null): HistoryResponse =
        get(HistoryResponse.serializer(), "history", query = mapOf("limit" to limit.toString(), "session" to session, "kind" to kind, "before_ms" to beforeMs?.toString(), "space" to space))
    suspend fun accounts(): AccountsResponse = get(AccountsResponse.serializer(), "accounts")
    suspend fun screen(address: String, lines: Int = 60): Screen =
        get(Screen.serializer(), "sessions", address, "screen", query = mapOf("lines" to lines.toString()))

    suspend fun screenshots(limit: Int = 60, session: String? = null): ScreenshotsResponse =
        get(ScreenshotsResponse.serializer(), "screenshots", query = mapOf("limit" to limit.toString(), "session" to session))

    /** `GET /v1/screenshots/{id}`: the image's bytes (PNG, JPEG, WebP or GIF). */
    suspend fun screenshotImage(shot: Screenshot): ByteArray = bytes(shot.url, listOf("screenshots", shot.id), "image/*")

    /**
     * `GET /v1/chat?before=&limit=`: a page of the chat thread (the latest, or
     * before message [before]). With [space], that space's own conversation;
     * with [main], the main one without those (a Quiet Tower).
     */
    suspend fun chat(before: String? = null, limit: Int = 50, space: String? = null, main: Boolean = false): ChatPage {
        val req = request(url("chat", query = mapOf("before" to before, "limit" to limit.toString(), "space" to space, "main" to if (main) "1" else null))).get().build()
        return fetch(http.newCall(req)) {
            val body = it.body.string()
            if (!it.isSuccessful) throw error(it.code, body)
            ChatApi.page(body)
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

    /** `POST /v1/ask`; with [space] the request goes to that space's tower. */
    suspend fun ask(text: String, space: String? = null): AskResponse = post(AskResponse.serializer(), json(AskRequest.serializer(), AskRequest(text, space)), "ask")

    /**
     * `POST /v1/ask` with files: multipart, a `text` field and one part per
     * file (`image` for pictures, `file` for videos and others), in token
     * order. The tower saves them on the Mac and passes their paths after the
     * text, `[Image #N]`, `[Video #N]` or `[File #N: name]` marking each.
     */
    suspend fun ask(text: String, files: List<FilePart>, space: String? = null): AskResponse {
        if (files.isEmpty()) return ask(text, space)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("text", text)
            .apply { if (space != null) addFormDataPart("space", space) }
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

    /**
     * `POST /v1/sessions/{address}/show`: brings the session to the front in
     * Herdr on the Mac (`tower show`). A tower without it (404) gets the
     * request as words instead, which its brain carries out.
     */
    suspend fun showSession(address: String) {
        try {
            post(Ignored.serializer(), ByteArray(0).toRequestBody(null), "sessions", address, "show")
        } catch (e: TowerApiException) {
            if (e.status != 404 || e.code == "no_session") throw e
            ask("Show $address in Herdr.")
        }
    }

    // Behaviors (docs/behaviors.md)

    suspend fun behaviors(): BehaviorsResponse = get(BehaviorsResponse.serializer(), "behaviors")

    suspend fun addBehavior(title: String, category: String, summary: String, text: String): BehaviorResult =
        post(BehaviorResult.serializer(), BehaviorPatch(title, category, summary, text).json().toRequestBody(JSON), "behaviors")

    /** `PUT /v1/behaviors/{id}`: any of title, category, summary, text, enabled (enabled alone turns it on or off). */
    suspend fun updateBehavior(id: String, patch: BehaviorPatch): BehaviorResult =
        call(http, request(url("behaviors", id)).put(patch.json().toRequestBody(JSON)).build(), BehaviorResult.serializer())

    /** What an edit of the rule's text would change, not saved. */
    suspend fun previewBehavior(id: String, text: String): BehaviorPreview =
        post(BehaviorPreview.serializer(), BehaviorPatch(text = text).json().toRequestBody(JSON), "behaviors", id, "preview")

    suspend fun removeBehavior(id: String): BehaviorResult =
        call(http, request(url("behaviors", id)).delete().build(), BehaviorResult.serializer())

    /** Undoes the latest change not undone yet (`400` when there's nothing to undo). */
    suspend fun undoBehavior(): BehaviorResult = post(BehaviorResult.serializer(), ByteArray(0).toRequestBody(null), "behaviors", "undo")

    suspend fun behaviorHistory(limit: Int = 50): List<BehaviorChange> =
        get(BehaviorHistory.serializer(), "behaviors", "history", query = mapOf("limit" to limit.toString())).changes

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

    // PRs the sessions opened (docs/mobile-api.md, "PRs")

    /** `GET /v1/prs`: open ones and the last day's closed, waiting ones first. */
    suspend fun prs(): List<Pr> = get(PrsResponse.serializer(), "prs").prs

    /** `GET /v1/prs/{owner}/{repo}/{number}`. `404 no_pr`. */
    suspend fun pr(ref: PrRef): Pr? = get(PrResponse.serializer(), "prs", ref.owner, ref.repo, ref.number.toString()).pr

    /** Step one of a merge: GitHub is asked again; the answer says what merging would do, with a token for step two. */
    suspend fun confirmMerge(ref: PrRef): MergeConfirm =
        post(MergeConfirm.serializer(), "{}".toRequestBody(JSON), "prs", ref.owner, ref.repo, ref.number.toString(), "confirm")

    /**
     * Step two: merges as confirmed (the repo's method, pinned to the commit
     * shown, never forced). [override] only after the user said a second time
     * that the failing checks are unrelated. The token is used up either way.
     */
    suspend fun merge(ref: PrRef, token: String, override: Boolean = false): MergeResult =
        post(MergeResult.serializer(), json(MergeRequest.serializer(), MergeRequest(token, override.takeIf { it })), "prs", ref.owner, ref.repo, ref.number.toString(), "merge")

    // Quiet Tower (herder-tower docs/quiet-tower.md, "Step 11: the phone")

    /** `GET /v1/decisions`: the main queue in order (muted spaces left out), or one [space]'s own (muted or not). */
    suspend fun decisions(space: String? = null): DecisionsResponse =
        get(DecisionsResponse.serializer(), "decisions", query = mapOf("space" to space))

    /**
     * `POST /v1/decisions/{id}/answer` with [body]: `{"choice":0}`, `{"other":"…"}`,
     * or a question set's answers. A question's answer is typed in before it
     * returns (some seconds a session). `409 closed`: it is over already.
     */
    suspend fun answerDecision(id: String, body: String): DecisionAnswered =
        post(DecisionAnswered.serializer(), body.toRequestBody(JSON), "decisions", id, "answer", client = slow)

    /** `DELETE /v1/decisions/{id}`: not the user's after all; nothing is sent. */
    suspend fun dismissDecision(id: String) {
        call(http, request(url("decisions", id)).delete().build(), Ignored.serializer())
    }

    /** `POST /v1/decisions/{id}/hold`: off the user's plate, with their words ([reason]), until it changes. `409 closed`: over already. */
    suspend fun holdDecision(id: String, reason: String?): Decision =
        post(DecisionResponse.serializer(), DecisionsApi.holdBody(reason).toRequestBody(JSON), "decisions", id, "hold").decision

    /** `POST /v1/decisions/{id}/unhold`: back in the queue. `409 not_on_hold`, `409 closed`. */
    suspend fun unholdDecision(id: String, why: String? = null): Decision =
        post(DecisionResponse.serializer(), DecisionsApi.unholdBody(why).toRequestBody(JSON), "decisions", id, "unhold").decision

    /** `POST /v1/decisions/{id}/merge-all`: the tower merges [prs] (keys, in order) of that merge card itself. `409 running`: one runs already. */
    suspend fun mergeAll(id: String, prs: List<String>): MergeChain =
        post(MergeChainResponse.serializer(), json(MergeAllRequest.serializer(), MergeAllRequest(prs)), "decisions", id, "merge-all").merge

    /** `POST /v1/merges/{id}/stop`: nothing more of that chain is merged. */
    suspend fun stopMerges(id: String): MergeChain =
        post(MergeChainResponse.serializer(), "{}".toRequestBody(JSON), "merges", id, "stop").merge

    /** `GET /v1/missions`: every open space as a mission, the quiet ones and today's closed ones by name. */
    suspend fun missions(): MissionsResponse = get(MissionsResponse.serializer(), "missions")

    private suspend fun mission(body: RequestBody, vararg segments: String): Mission? =
        fetch(http.newCall(request(url(*segments)).post(body).build())) {
            val text = it.body.string()
            if (!it.isSuccessful) throw error(it.code, text)
            MissionsApi.mission(text)
        }

    /** `POST /v1/spaces/{space}/goal`: what the space is for and what counts as done; answers with the mission (null: read them again). */
    suspend fun setGoal(space: String, goal: String?, doneWhen: String?, standing: Boolean?): Mission? =
        mission(json(GoalRequest.serializer(), GoalRequest(goal, doneWhen, standing)), "spaces", space, "goal")

    /** `POST /v1/spaces/{space}/follow`: its results reach the user as they land. */
    suspend fun follow(space: String, on: Boolean): Mission? = mission(json(OnRequest.serializer(), OnRequest(on)), "spaces", space, "follow")

    /** `POST /v1/spaces/{space}/mute`: its decisions stay in its own queue. */
    suspend fun muteSpace(space: String, on: Boolean): Mission? = mission(json(OnRequest.serializer(), OnRequest(on)), "spaces", space, "mute")

    /**
     * `POST /v1/spaces/{space}/close`: answers at once with the action that
     * closes it. `409 busy` (with the sessions at work) unless [force].
     */
    suspend fun closeSpace(space: String, force: Boolean = false): TowerAction =
        post(ActionResponse.serializer(), json(CloseRequest.serializer(), CloseRequest(force)), "spaces", space, "close").action

    /** `GET /v1/thread?space=&before=`: what the sessions of a space wrote each other, newest last. */
    suspend fun thread(space: String, limit: Int = 50, before: String? = null): ThreadResponse =
        get(ThreadResponse.serializer(), "thread", query = mapOf("space" to space, "limit" to limit.toString(), "before" to before))

    suspend fun setups(): List<Setup> = get(SetupsResponse.serializer(), "setups").setups

    /** `POST /v1/launch` with `dry_run`: what launching would do; nothing is opened. */
    suspend fun launchPlan(goal: String, setup: String? = null, name: String? = null): LaunchPlan =
        post(LaunchPlan.serializer(), json(LaunchRequest.serializer(), LaunchRequest(goal, setup, name?.takeIf { it.isNotBlank() }, dryRun = true)), "launch")

    /** `POST /v1/launch`: a space from one sentence. It answers at once with the action that opens its sessions. */
    suspend fun launch(goal: String, setup: String? = null, name: String? = null): TowerAction =
        post(ActionResponse.serializer(), json(LaunchRequest.serializer(), LaunchRequest(goal, setup, name?.takeIf { it.isNotBlank() })), "launch").action

    /** `GET /v1/actions/{id}`: a launch or a close as it stands. */
    suspend fun action(id: String): TowerAction = get(ActionResponse.serializer(), "actions", id).action

    suspend fun tell(): Tell = get(Tell.serializer(), "tell")

    /** `POST /v1/tell`: how much the tower tells ([level]) and what it says out loud ([voice]), either or both. */
    suspend fun setTell(level: String? = null, voice: String? = null): Tell =
        post(Tell.serializer(), json(TellRequest.serializer(), TellRequest(level, voice)), "tell")

    /** `GET /v1/acts`: what the tower opened lately, newest first. */
    suspend fun acts(): List<Act> = get(ActsResponse.serializer(), "acts").acts

    /** `GET /v1/acts/{id}`: one act as it stands. */
    suspend fun act(id: String): Act = get(ActResponse.serializer(), "acts", id).act

    /** `POST /v1/acts/{id}/undo`: ends what the act opened. `409 held` while work would be lost, unless [force]. */
    suspend fun undoAct(id: String, force: Boolean = false): UndoResult =
        post(UndoResult.serializer(), json(UndoRequest.serializer(), UndoRequest(force)), "acts", id, "undo", client = slow)

    /** `POST /v1/decisions/{id}/rule`: the answer just given becomes a standing rule ([answer]: its words, when the tower doesn't know them). */
    suspend fun decisionRule(id: String, answer: String? = null): RuleSent =
        post(RuleSent.serializer(), json(RuleRequest.serializer(), RuleRequest(answer)), "decisions", id, "rule")

    /** `GET /v1/model`: what the tower's brains run on. */
    suspend fun model(): BrainModel = get(BrainModel.serializer(), "model")

    /** `POST /v1/model`: another model or effort (applies when a brain starts), and/or [restart] every brain now. */
    suspend fun setModel(model: String? = null, effort: String? = null, restart: Boolean? = null): BrainModel =
        post(BrainModel.serializer(), json(ModelRequest.serializer(), ModelRequest(model, effort, restart)), "model", client = slow)

    suspend fun questionPolicy(): String = get(QuestionPolicy.serializer(), "questions-policy").policy

    suspend fun setQuestionPolicy(policy: String): String =
        call(http, request(url("questions-policy")).put(json(QuestionPolicy.serializer(), QuestionPolicy(policy))).build(), QuestionPolicy.serializer()).policy

    suspend fun hush() {
        post(Ignored.serializer(), ByteArray(0).toRequestBody(null), "hush")
    }

    /** The tower-wide mute: the tower stops talking now and says nothing, anywhere, until unmuted. */
    suspend fun setMuted(on: Boolean): Mute = post(Mute.serializer(), json(Mute.serializer(), Mute(on)), "mute")

    suspend fun mode(mode: String) {
        post(Ignored.serializer(), json(ModeRequest.serializer(), ModeRequest(mode)), "mode")
    }

    /** `POST /v1/voice`: one recorded take as WAV; returns what the Mac heard. */
    suspend fun voice(wav: ByteArray): VoiceResult = post(VoiceResult.serializer(), wav.toRequestBody(WAV), "voice", client = slow)

    /** `POST /v1/voice?space=`: a take for that space's own tower (a Quiet Tower). `404 no_space` before anything is transcribed. */
    suspend fun voice(wav: ByteArray, space: String): VoiceResult =
        call(slow, request(url("voice", query = mapOf("space" to space))).post(wav.toRequestBody(WAV)).build(), VoiceResult.serializer())

    /** `GET /v1/audio/{id}`: a reply clip's WAV bytes. */
    suspend fun audio(clip: AudioClip): ByteArray = bytes(clip.url, listOf("audio", clip.id), "audio/wav")

    /** Bytes from the tower's own [path] (`/v1/…`), or from [segments] when it has none. */
    private suspend fun bytes(path: String, segments: List<String>, accept: String): ByteArray {
        val u = path.takeIf { it.startsWith("/v1/") }?.let { p ->
            HttpUrl.Builder().scheme("https").host(endpoint.host).port(endpoint.port).encodedPath(p).build()
        } ?: url(*segments.toTypedArray())
        return fetch(http.newCall(request(u).header("Accept", accept).get().build())) {
            if (!it.isSuccessful) throw error(it.code, it.body.string())
            it.body.bytes()
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
        /** Closes a response on an IO thread: a cancellation can run on the main thread, where network calls throw. */
        private fun closeOffMain(r: Response) {
            Dispatchers.IO.asExecutor().execute { runCatching { r.close() } }
        }

        val JSON = "application/json; charset=utf-8".toMediaType()
        val WAV = "audio/wav".toMediaType()

        /** Turns an error response into the matching exception. */
        fun error(status: Int, body: String): TowerApiException {
            val detail = runCatching { TowerJson.decodeFromString(ErrorBody.serializer(), body).error }.getOrNull()
            val extra = runCatching { (TowerJson.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject)?.get("error") as? kotlinx.serialization.json.JsonObject }.getOrNull()
            val code = detail?.code?.takeIf { it.isNotEmpty() } ?: "http_$status"
            val message = detail?.message?.takeIf { it.isNotEmpty() } ?: "The tower answered $status."
            return if (status == 401) UnauthorizedException(code, message) else TowerApiException(status, code, message, detail?.triesLeft, extra)
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
                is ConnectException, is NoRouteToHostException -> UnreachableException("Can't reach the tower. Is the Mac on this network and “tower phone enable” on?", e)
                is UnknownHostException -> UnreachableException("Unknown address.", e)
                is SSLException -> UnreachableException("Secure connection failed: ${e.message}", e)
                else -> UnreachableException(e.message ?: "Connection failed.", e)
            }
        }
    }
}

/** The handshake failed because the certificate isn't the paired one: re-pair, or someone is in between. */
class SSLMismatch(val mismatch: CertificateMismatchException) : IOException(mismatch.message, mismatch)
