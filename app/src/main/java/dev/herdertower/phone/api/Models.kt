package dev.herdertower.phone.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull

/**
 * The tower's JSON, as `docs/mobile-api.md` (v1) describes it. Unknown keys are
 * ignored and missing ones take defaults, so additions on the Mac don't break
 * the app; a `null` where a value has a default becomes the default.
 */
val TowerJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    isLenient = true
    encodeDefaults = true
}

@Serializable
data class Device(val id: String = "", val name: String = "")

@Serializable
data class PairRequest(
    val code: String,
    val name: String,
    /** Made once per install and kept: pairing again with it re-pairs the same device on the Mac. */
    @SerialName("install_id") val installId: String? = null,
)

@Serializable
data class PairResponse(
    val token: String,
    val device: Device = Device(),
    val fingerprint: String = "",
    val api: Int = 1,
    /** What this tower has beyond the first v1 (`quiet`); null from a tower before features. */
    val features: List<String>? = null,
    /** This install was paired here before: the same device, re-paired in place. */
    val repaired: Boolean = false,
    /** Where the tower is now: merged into the QR's addresses, and the network named from the first pairing. */
    val hosts: List<TowerHost>? = null,
    val network: TowerNetwork? = null,
    @SerialName("magic_dns") val magicDns: String? = null,
)

@Serializable
data class RenameRequest(val name: String)

@Serializable
data class DeviceResponse(val device: Device = Device())

@Serializable
data class Hello(
    val api: Int = 1,
    val tower: String = "",
    val device: Device = Device(),
    val voice: String = "",
    @SerialName("phone_is_voice") val phoneIsVoice: Boolean = false,
    /** Where this phone's replies are heard: "phone" or "mac" (null: a tower from before the setting). */
    @SerialName("audio_route") val audioRoute: String? = null,
    /** What this tower has beyond the first v1 (`quiet`); null from a tower before features. */
    val features: List<String>? = null,
)

@Serializable
data class AudioRoute(val route: String = "phone")

@Serializable
data class MissedClips(val clips: List<AudioClip> = emptyList())

@Serializable
data class Alert(
    @SerialName("at_ms") val atMs: Long = 0,
    val terminal: String = "",
    val text: String = "",
    val level: String = "",
)

/** An agent as the state lists it. */
@Serializable
data class StateAgent(
    val address: String = "",
    val name: String = "",
    val status: String = "",
    val app: String = "",
    val account: String? = null,
)

@Serializable
data class TowerState(
    val version: Long = 0,
    @SerialName("updated_ms") val updatedMs: Long = 0,
    val running: Boolean = false,
    val phase: String = "idle",
    val mode: String = "",
    val mic: String = "",
    val armed: Boolean = false,
    val transcribing: Boolean = false,
    val speaking: Boolean = false,
    /** The tower-wide mute (`tower mute`): it says nothing, on the Mac or any phone. */
    val muted: Boolean = false,
    val announcing: Boolean = false,
    val brain: String = "",
    @SerialName("brain_app") val brainApp: String = "",
    val hearing: String = "",
    val heard: String = "",
    @SerialName("heard_ms") val heardMs: Long = 0,
    val said: String = "",
    @SerialName("said_ms") val saidMs: Long = 0,
    val alerts: List<Alert> = emptyList(),
    val agents: List<StateAgent> = emptyList(),
    val error: String = "",
    @SerialName("talk_key") val talkKey: String = "",
    val voice: String = "",
    @SerialName("stt_model") val sttModel: String = "",
    val lan: String = "",
    /** Every address the tower listens on, in order (LAN, then Tailscale while it runs there); empty from an older tower. */
    @kotlinx.serialization.SerialName("lan_hosts") val lanHosts: List<TowerHost> = emptyList(),
    /** Tailscale on the Mac, as the tower sees it; null from an older tower. */
    val tailscale: TailscaleInfo? = null,
    val phone: String? = null,
    /** The latest screenshots sessions sent (newest first). */
    val screenshots: List<Screenshot> = emptyList(),
    /** What the brain is doing right now, or null between turns. */
    val work: Work? = null,
    /** What blocked sessions ask (question sets), and how the tower handles them. */
    val questions: List<Question> = emptyList(),
    @SerialName("question_policy") val questionPolicy: String? = null,
    /** The open decisions outside muted spaces (the badge); the list itself has its own call and event. Null from a tower before them. */
    @SerialName("decisions_count") val decisionsCount: Int? = null,
    /** How much the tower tells the user: `decisions`, `results` or `everything`. Null from a tower before it. */
    @SerialName("tell_level") val tellLevel: String? = null,
    /** The network the Mac is on (a tower with `pairing`). */
    val network: TowerNetwork? = null,
    /** What the tower says out loud: `off`, `decisions` or `replies`. Null from a tower before it. */
    @SerialName("tell_voice") val tellVoice: String? = null,
    /** The brains' model, as `GET /v1/model` reports it. */
    @SerialName("brain_model") val brainModel: BrainModel? = null,
    /** The plan that is open, or null: read with [PlansApi.parse], so a plan the app can't read never breaks the state. */
    val plan: JsonElement? = null,
    /** Whether the tower merges low-risk PRs into dev itself (`tower automerge`, feature `scores`). Null from a tower before it. */
    @SerialName("auto_merge") val autoMerge: Boolean? = null,
)

/** The brain's current step, as the panel's working line shows it ("Delegating to orb-rust · 12s"). */
@Serializable
data class Work(
    val word: String = "",
    val detail: String = "",
    /** When the turn began. */
    @SerialName("since_ms") val sinceMs: Long = 0,
    /** When this step began. */
    @SerialName("at_ms") val atMs: Long = 0,
    /** When the step finished; 0 while it runs. */
    @SerialName("done_ms") val doneMs: Long = 0,
)

/** An agent as `GET /v1/agents` (`tower agents --json`) lists it. */
@Serializable
data class Agent(
    val address: String = "",
    val account: String? = null,
    val machine: String? = null,
    val name: String? = null,
    val spoken: String? = null,
    val agent: String? = null,
    val status: String? = null,
    val doing: String? = null,
    val title: String? = null,
    val folder: String? = null,
    val where: JsonElement? = null,
) {
    val label: String get() = name?.takeIf { it.isNotBlank() } ?: address
}

@Serializable
data class Unreachable(val machine: String? = null, val error: String = "")

@Serializable
data class AgentsResponse(
    val agents: List<Agent> = emptyList(),
    val unreachable: List<Unreachable> = emptyList(),
)

@Serializable
data class SpaceSession(
    val address: String = "",
    val name: String = "",
    val status: String = "",
    val account: String? = null,
) {
    val label: String get() = name.ifBlank { address }
}

@Serializable
data class Space(
    val space: String = "",
    val focus: Boolean = false,
    val agenda: String? = null,
    val unread: Int = 0,
    @SerialName("running_jobs") val runningJobs: Int = 0,
    val sessions: List<SpaceSession> = emptyList(),
    @SerialName("last_activity_ms") val lastActivityMs: Long = 0,
)

@Serializable
data class SpacesResponse(val focus: String? = null, val spaces: List<Space> = emptyList())

@Serializable
data class Job(
    val job: String = "",
    val session: String = "",
    val name: String = "",
    val space: String = "",
    val account: String? = null,
    val status: String = "",
    val asked: String = "",
    val summary: String = "",
    @SerialName("started_ms") val startedMs: Long = 0,
    @SerialName("updated_ms") val updatedMs: Long = 0,
) {
    val open: Boolean get() = status == "working" || status == "blocked"
}

@Serializable
data class JobsResponse(val jobs: List<Job> = emptyList())

@Serializable
data class HistoryEntry(
    val at: Long = 0,
    val kind: String = "",
    val session: String? = null,
    val name: String? = null,
    val space: String? = null,
    val job: String? = null,
    val account: String? = null,
    val status: String? = null,
    val text: String = "",
    val attachments: List<String> = emptyList(),
    val held: Boolean = false,
    /** kind = screenshot: the image's id (fetch `/v1/screenshots/{id}`), caption and path on the Mac. */
    val id: String? = null,
    val caption: String? = null,
    val path: String? = null,
    /** kind = behavior: the rule's diff. */
    val diff: String? = null,
    /** kind = peer: the session that wrote to [session] (its address), and both names when the tower gives them. */
    val from: String? = null,
    @SerialName("from_name") val fromName: String? = null,
    @SerialName("to_name") val toName: String? = null,
)

@Serializable
data class HistoryResponse(val entries: List<HistoryEntry> = emptyList())

@Serializable
data class Account(val name: String = "", val email: String? = null)

@Serializable
data class AccountsResponse(val default: String? = null, val accounts: List<Account> = emptyList())

@Serializable
data class Screen(val address: String = "", val screen: String = "")

/** [space]: the request goes to that space's tower (the main one without it). */
@Serializable
data class AskRequest(val text: String, val space: String? = null)

@Serializable
data class AskResponse(val sent: String = "", val attachments: List<String> = emptyList())

/** A file sent with an ask or a take: its name, type and content (bytes, or a file on disk for big ones). */
class FilePart(val name: String, val mime: String, val bytes: ByteArray? = null, val file: java.io.File? = null) {
    val isImage: Boolean get() = mime.startsWith("image/")
}

@Serializable
data class AnswerRequest(val decision: String, val note: String? = null)

@Serializable
data class FocusRequest(val space: String?)

@Serializable
data class FocusResponse(val focus: String? = null)

@Serializable
data class ModeRequest(val mode: String)

/** `POST /v1/mute` and its answer. */
@Serializable
data class Mute(val muted: Boolean = false)

@Serializable
data class VoiceResult(val heard: String = "", val sent: Boolean = false, val space: String? = null)

@Serializable
data class ErrorDetail(
    val code: String = "",
    val message: String = "",
    @SerialName("tries_left") val triesLeft: Int? = null,
)

@Serializable
data class ErrorBody(val error: ErrorDetail = ErrorDetail())

/** `audio` event: a clip to fetch from [url] and play in event order. */
@Serializable
data class AudioClip(
    val id: String = "",
    val kind: String = "",
    val text: String = "",
    val ms: Long = 0,
    val url: String = "",
    /** When it was made (in `GET /v1/audio?after=` listings). */
    @SerialName("at_ms") val atMs: Long = 0,
)

/** `hello` event: the first on every stream. */
@Serializable
data class StreamHello(
    val api: Int = 1,
    val device: String = "",
    val voice: Boolean = false,
    /** What this tower has beyond the first v1; a tower from before features sends none: read as none. */
    val features: List<String>? = null,
    /** The Mac's addresses now and the network it is on (a tower that has them): merged into the stored ones. */
    val hosts: List<TowerHost>? = null,
    val network: TowerNetwork? = null,
    @SerialName("magic_dns") val magicDns: String? = null,
) {
    /** The Quiet Tower: decisions, missions, threads, launches, the tell level and the model. */
    val quiet: Boolean get() = features.orEmpty().contains("quiet")

    /** Pairing on more than one network: install ids, the Mac's addresses as they change, renaming the phone. */
    val pairing: Boolean get() = features.orEmpty().contains("pairing")

    /** Receipts for commands, with undo; rules from answers; the tower's voice level. */
    val acts: Boolean get() = features.orEmpty().contains("acts")

    /** Review scores: on PRs, a plan's predicted PRs, `auto_merge` in the state. */
    val scores: Boolean get() = features.orEmpty().contains("scores")
}

/** A screenshot a session sent to the tower (`GET /v1/screenshots`). */
@Serializable
data class Screenshot(
    val id: String = "",
    val at: Long = 0,
    val session: String? = null,
    val name: String? = null,
    val space: String? = null,
    val account: String? = null,
    val job: String? = null,
    val caption: String = "",
    val path: String = "",
    val url: String = "",
) {
    val who: String get() = name?.takeIf { it.isNotBlank() } ?: session.orEmpty()
}

@Serializable
data class ScreenshotsResponse(val screenshots: List<Screenshot> = emptyList())

/**
 * A message of the tower's main chat thread (`GET /v1/chat`, event `chat`):
 * the same thread the Mac panel shows. [role] is who speaks ("user" for you,
 * from the phone or the Mac; "tower" for its replies; others are reports),
 * [source] where it came from. A reply that is still being written comes
 * again with the same [id] and more text.
 */
@Serializable
data class ChatMessage(
    val id: String = "",
    val at: Long = 0,
    val role: String = "",
    val text: String = "",
    val source: String? = null,
    /** The files for its `#n` tokens: `{n, kind, name, path, url}` (or bare paths). */
    val attachments: List<JsonElement> = emptyList(),
    @kotlinx.serialization.SerialName("updated_at") val updatedAt: Long = 0,
    /** A behavior change's diff (`kind: behavior`). */
    val diff: String? = null,
    /** Screenshots shown with it: ids, or whole screenshot objects. */
    val screenshots: List<JsonElement> = emptyList(),
    val session: String? = null,
    val name: String? = null,
    val account: String? = null,
    val kind: String? = null,
    val status: String? = null,
    val space: String? = null,
    /** role = pr: the PR as it is now (it changes in place: the same message comes again, updated). */
    val pr: Pr? = null,
    /** kind = act: what the tower opened for the request before it, with what it assumed (it changes in place too). */
    val act: Act? = null,
) {
    val mine: Boolean get() = role == "user" || role == "you" || role == "request"
    val fromTower: Boolean get() = role == "tower" || role == "assistant" || role == "answer"

    /** Its place in the thread: the line of `h<line>`, so the thread is in the tower's order. */
    val line: Long get() = id.removePrefix("h").toLongOrNull() ?: Long.MAX_VALUE

    /** The attachments' paths on the Mac, in token order (`attachments[n-1]` is token `#n`). */
    fun attachmentPaths(): List<String> = attachments.mapIndexedNotNull { i, e ->
        // A JSON null is no path (not the word "null"), but keeps its place in the token order.
        fun text(v: JsonElement?) = (v as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        when (e) {
            is kotlinx.serialization.json.JsonPrimitive -> (i + 1) to text(e).orEmpty()
            is kotlinx.serialization.json.JsonObject -> {
                val n = text(e["n"])?.toIntOrNull() ?: (i + 1)
                n to (text(e["path"]) ?: text(e["url"]) ?: "")
            }
            else -> null
        }
    }.sortedBy { it.first }.map { it.second }

    /** The screenshots as objects the gallery and viewer can show. */
    fun shots(): List<Screenshot> = screenshots.mapNotNull { e ->
        when (e) {
            is kotlinx.serialization.json.JsonPrimitive -> e.contentOrNull?.takeIf { it.isNotBlank() }?.let { Screenshot(id = it, at = at, url = "/v1/screenshots/$it") }
            is kotlinx.serialization.json.JsonObject -> runCatching { TowerJson.decodeFromJsonElement(Screenshot.serializer(), e) }.getOrNull()
                ?.let { if (it.url.isBlank()) it.copy(url = "/v1/screenshots/${it.id}") else it }
            else -> null
        }
    }
}

@Serializable
data class ChatPage(
    val messages: List<ChatMessage> = emptyList(),
    /** Whether older messages exist before this page (when the tower says). */
    val more: Boolean? = null,
)
