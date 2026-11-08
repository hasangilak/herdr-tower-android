package dev.herdertower.phone.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

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
data class PairRequest(val code: String, val name: String)

@Serializable
data class PairResponse(
    val token: String,
    val device: Device = Device(),
    val fingerprint: String = "",
    val api: Int = 1,
)

@Serializable
data class Hello(
    val api: Int = 1,
    val tower: String = "",
    val device: Device = Device(),
    val voice: String = "",
    @SerialName("phone_is_voice") val phoneIsVoice: Boolean = false,
    /** Where this phone's replies are heard: "phone" or "mac" (null: a tower from before the setting). */
    @SerialName("audio_route") val audioRoute: String? = null,
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
    val phone: String? = null,
    /** The latest screenshots sessions sent (newest first). */
    val screenshots: List<Screenshot> = emptyList(),
    /** What the brain is doing right now, or null between turns. */
    val work: Work? = null,
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
)

@Serializable
data class HistoryResponse(val entries: List<HistoryEntry> = emptyList())

@Serializable
data class Account(val name: String = "", val email: String? = null)

@Serializable
data class AccountsResponse(val default: String? = null, val accounts: List<Account> = emptyList())

@Serializable
data class Screen(val address: String = "", val screen: String = "")

@Serializable
data class AskRequest(val text: String)

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

@Serializable
data class VoiceResult(val heard: String = "", val sent: Boolean = false)

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
data class StreamHello(val api: Int = 1, val device: String = "", val voice: Boolean = false)

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
