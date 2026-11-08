package dev.herdertower.phone.data

import android.content.Context
import android.os.Build
import android.util.Log
import dev.herdertower.phone.api.AccountsResponse
import dev.herdertower.phone.api.Agent
import dev.herdertower.phone.api.CertificateMismatchException
import dev.herdertower.phone.api.Endpoint
import dev.herdertower.phone.api.Fingerprint
import dev.herdertower.phone.api.HistoryEntry
import dev.herdertower.phone.api.Job
import dev.herdertower.phone.api.SSLMismatch
import dev.herdertower.phone.api.Screenshot
import dev.herdertower.phone.api.SpacesResponse
import dev.herdertower.phone.api.TowerApiException
import dev.herdertower.phone.api.TowerClient
import dev.herdertower.phone.api.TowerEvent
import dev.herdertower.phone.api.TowerState
import dev.herdertower.phone.api.UnauthorizedException
import dev.herdertower.phone.api.Unreachable
import dev.herdertower.phone.voice.ClipPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job as CoJob
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The connection to the tower, as the app shows it. */
sealed interface Link {
    /** No tower paired yet. */
    data object Unpaired : Link
    /** In the background and not the voice: the stream is closed on purpose. */
    data object Idle : Link
    data class Connecting(val attempt: Int) : Link
    data class Live(val voice: Boolean) : Link
    /** Dropped; trying again in [seconds]. */
    data class Retrying(val seconds: Int, val reason: String, val attempt: Int) : Link
    /** The token stopped working (revoked, or the Mac forgot this phone): pair again. */
    data class Revoked(val message: String) : Link
    /** The tower's certificate isn't the paired one. */
    data class CertificateChanged(val got: String) : Link
}

/**
 * Everything the app knows about the tower and the one event stream it
 * keeps open: the panel's feed while the app is on screen, or the voice
 * stream (`?voice=1`) for as long as the phone is the tower's voice, screen
 * on or off (kept alive by [dev.herdertower.phone.voice.VoiceService]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TowerSession(private val context: Context, private val store: CredentialStore, val settings: Settings) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _creds = MutableStateFlow(store.load())
    val credentials: StateFlow<Credentials?> = _creds.asStateFlow()

    @Volatile private var client: TowerClient? = _creds.value?.let(::clientFor)

    private val _link = MutableStateFlow<Link>(if (_creds.value == null) Link.Unpaired else Link.Idle)
    val link: StateFlow<Link> = _link.asStateFlow()

    private val _state = MutableStateFlow<TowerState?>(null)
    val state: StateFlow<TowerState?> = _state.asStateFlow()

    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    /** Oldest first. */
    val history: StateFlow<List<HistoryEntry>> = _history.asStateFlow()

    private val _spaces = MutableStateFlow(SpacesResponse())
    val spaces: StateFlow<SpacesResponse> = _spaces.asStateFlow()

    private val _jobs = MutableStateFlow<List<Job>>(emptyList())
    val jobs: StateFlow<List<Job>> = _jobs.asStateFlow()

    private val _agents = MutableStateFlow<List<Agent>>(emptyList())
    val agents: StateFlow<List<Agent>> = _agents.asStateFlow()
    private val _unreachable = MutableStateFlow<List<Unreachable>>(emptyList())
    val unreachable: StateFlow<List<Unreachable>> = _unreachable.asStateFlow()

    private val _screenshots = MutableStateFlow<List<Screenshot>>(emptyList())
    /** Screenshots sessions sent, newest first. */
    val screenshots: StateFlow<List<Screenshot>> = _screenshots.asStateFlow()

    /** Screenshot images, fetched with the token and cached. */
    val images = Images(this)

    private val _accounts = MutableStateFlow(AccountsResponse())
    val accounts: StateFlow<AccountsResponse> = _accounts.asStateFlow()

    private val _voiceOn = MutableStateFlow(settings.voice && _creds.value != null)
    /** Whether this phone is (meant to be) the tower's voice. */
    val voiceOn: StateFlow<Boolean> = _voiceOn.asStateFlow()

    private val _towerName = MutableStateFlow(settings.towerName)
    /** The Mac's name for greetings: what the user set, or a guess from its address. */
    val towerName: StateFlow<String> = _towerName.asStateFlow()

    private val _route = MutableStateFlow<String?>(settings.audioRoute)
    /** Where the replies to what this phone sends are heard: "phone" or "mac" (the Mac keeps it; this is its last known value). */
    val audioRoute: StateFlow<String?> = _route.asStateFlow()

    /** After this phone asks something, the stream stays open a while even in the background, so the reply isn't missed. */
    private val _lingering = MutableStateFlow(false)
    private var lingerJob: CoJob? = null
    /** The last clip heard (or seen) here, to ask for what was missed after a gap. */
    @Volatile private var lastClip: String? = settings.lastClip
        set(v) {
            field = v
            settings.lastClip = v
        }

    private val _foreground = MutableStateFlow(false)
    private val retryNow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var refreshJob: CoJob? = null

    val player = ClipPlayer(context, scope) { clip ->
        (client ?: throw IllegalStateException("not paired")).audio(clip)
    }

    init {
        _creds.value?.let { guessName(it.host) }
        scope.launch {
            combine(_creds, _voiceOn, _foreground, _lingering) { c, v, fg, linger -> if (c == null) null else Want(c, v, fg || linger) }
                .distinctUntilChanged()
                .collectLatest { want ->
                    when {
                        want == null -> {
                            player.hush()
                            _link.value = Link.Unpaired
                        }
                        !want.voice && !want.foreground -> {
                            player.hush()
                            _link.value = Link.Idle
                        }
                        else -> {
                            if (!want.voice) player.hush()
                            stream(want.voice)
                        }
                    }
                }
        }
        // While the app is on screen: refresh what isn't pushed every 20 s.
        scope.launch {
            combine(_link, _foreground) { l, fg -> l is Link.Live && fg }.distinctUntilChanged().collectLatest { on ->
                var n = 0
                while (on) {
                    delay(20_000)
                    refreshLists()
                    // The accounts change rarely: every minute (the panel re-reads them every 30 s).
                    if (++n % 3 == 0) quietly { _accounts.value = it.accounts() }
                }
            }
        }
    }

    private data class Want(val creds: Credentials, val voice: Boolean, val foreground: Boolean)

    fun setForeground(fg: Boolean) {
        _foreground.value = fg
    }

    fun setVoice(on: Boolean) {
        settings.voice = on
        _voiceOn.value = on && _creds.value != null
    }

    /** Skip the backoff wait and reconnect now. */
    fun reconnect() {
        retryNow.tryEmit(Unit)
    }

    fun renameTower(name: String) {
        settings.towerName = name.trim()
        _towerName.value = name.trim()
    }

    /** A Mac answers to its name on the LAN (reverse DNS / mDNS): use it until the user names it. */
    private fun guessName(host: String) {
        if (settings.towerName.isNotBlank()) return
        scope.launch {
            val name = withContext(Dispatchers.IO) {
                runCatching { java.net.InetAddress.getByName(host).canonicalHostName }.getOrNull()
            }?.takeIf { it != host && !it.first().isDigit() }
                ?.substringBefore('.')
                ?.replace('-', ' ')
            if (!name.isNullOrBlank() && settings.towerName.isBlank()) _towerName.value = name
        }
    }

    private fun clientFor(c: Credentials) = TowerClient(c.endpoint, { _creds.value?.token })

    // The stream

    private suspend fun stream(voice: Boolean) {
        var attempt = 0
        while (true) {
            val c = client ?: return
            _link.value = Link.Connecting(attempt)
            val reason = try {
                c.events(voice).collect { ev ->
                    if (ev is TowerEvent.Hello) {
                        attempt = 0
                        _link.value = Link.Live(voice)
                        refreshAll()
                    }
                    handle(ev)
                }
                "The tower closed the connection."
            } catch (e: CancellationException) {
                throw e
            } catch (e: UnauthorizedException) {
                _link.value = Link.Revoked(e.message)
                return
            } catch (e: SSLMismatch) {
                _link.value = Link.CertificateChanged(e.mismatch.got)
                return
            } catch (e: Exception) {
                e.message ?: "Connection lost."
            }
            // A clip half-played when the voice dropped won't be resumed; the Mac takes over.
            if (voice) player.hush()
            val wait = BACKOFF[minOf(attempt, BACKOFF.lastIndex)]
            attempt++
            _link.value = Link.Retrying(wait, reason, attempt)
            withTimeoutOrNull(wait * 1000L) { retryNow.first() }
        }
    }

    private fun handle(ev: TowerEvent) {
        when (ev) {
            is TowerEvent.State -> {
                val before = _state.value
                _state.value = ev.state
                if (ev.state.screenshots.isNotEmpty()) _screenshots.value = mergeShots(_screenshots.value, ev.state.screenshots)
                if (before != null && before.agents.map { it.address to it.status } != ev.state.agents.map { it.address to it.status }) refreshSoon()
            }
            is TowerEvent.History -> {
                _history.value = merge(_history.value, listOf(ev.entry))
                if (ev.entry.kind in setOf("job", "summary", "watch", "alert", "screenshot")) refreshSoon()
            }
            is TowerEvent.Audio -> {
                lastClip = ev.clip.id
                player.enqueue(ev.clip)
            }
            TowerEvent.Hush -> player.hush()
            TowerEvent.Pause -> player.pause()
            TowerEvent.Resume -> player.resume()
            is TowerEvent.Hello, TowerEvent.Ping, is TowerEvent.Unknown -> {}
        }
    }

    // Reading

    private fun refreshAll() {
        scope.launch {
            quietly { r -> setRouteLocal(r.audioRoute().route) }
            catchUp()
            quietly { _history.value = merge(_history.value, it.history(limit = 150).entries) }
            refreshLists()
            quietly { _accounts.value = it.accounts() }
        }
    }

    private fun refreshSoon() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            delay(1000)
            refreshLists()
        }
    }

    suspend fun refreshLists() = coroutineScope {
        val a = async { quietly { _spaces.value = it.spaces() } }
        val b = async { quietly { _jobs.value = it.jobs(status = "all", limit = 100).jobs } }
        val c = async { quietly { _screenshots.value = mergeShots(_screenshots.value, it.screenshots(limit = 60).screenshots) } }
        a.await()
        b.await()
        c.await()
    }

    suspend fun refreshAgents(all: Boolean = false) {
        quietly {
            val r = it.agents(all)
            _agents.value = r.agents
            _unreachable.value = r.unreachable
        }
    }

    /** Pages back: the [limit] entries before the oldest one loaded. Returns how many came. */
    suspend fun loadOlder(limit: Int = 100): Int {
        val first = _history.value.firstOrNull()?.at ?: return 0
        var n = 0
        quietly {
            val older = it.history(limit = limit, beforeMs = first).entries
            n = older.size
            _history.value = merge(_history.value, older)
        }
        return n
    }

    private suspend fun quietly(block: suspend (TowerClient) -> Unit) {
        val c = client ?: return
        try {
            block(c)
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnauthorizedException) {
            _link.value = Link.Revoked(e.message)
        } catch (e: Exception) {
            Log.i(TAG, "refresh failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Clips meant for this phone that came while no stream was open: the
     * recent ones are played (a reply just missed), older ones skipped (their
     * text is in the chat; old speech out of the blue would confuse).
     */
    private suspend fun catchUp() {
        val after = lastClip ?: return
        quietly { c ->
            val missed = c.missedClips(after).clips
            val now = System.currentTimeMillis()
            missed.lastOrNull()?.let { lastClip = it.id }
            missed.filter { it.atMs == 0L || now - it.atMs < RECENT_MS }.forEach { player.enqueue(it) }
        }
    }

    private fun setRouteLocal(route: String) {
        _route.value = route
        settings.audioRoute = route
    }

    /** Hear the replies to what this phone sends on the phone ("phone") or on the Mac ("mac"). */
    suspend fun setAudioRoute(route: String): Result<Unit> {
        val before = _route.value
        setRouteLocal(route) // show it at once; the Mac's answer is the truth
        return act { it.setAudioRoute(route) }.map { setRouteLocal(it.route) }.onFailure { if (before != null) setRouteLocal(before) }
    }

    /** Keep the stream open for a while after asking, even if the app goes to the background. */
    fun linger() {
        _lingering.value = true
        lingerJob?.cancel()
        lingerJob = scope.launch {
            delay(LINGER_MS)
            _lingering.value = false
        }
    }

    /** Runs an action; errors come back as a message for the user. */
    suspend fun <T> act(block: suspend (TowerClient) -> T): Result<T> {
        val c = client ?: return Result.failure(IllegalStateException("Not paired."))
        return try {
            Result.success(block(c))
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnauthorizedException) {
            _link.value = Link.Revoked(e.message)
            Result.failure(e)
        } catch (e: SSLMismatch) {
            _link.value = Link.CertificateChanged(e.mismatch.got)
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Hush: stop the phone now and tell the tower to stop and drop what's queued. */
    fun hush() {
        player.hush()
        scope.launch { act { it.hush() } }
    }

    // Pairing

    /**
     * Pairs with the code shown on the Mac, pinning [endpoint]'s fingerprint.
     * The token is stored encrypted and only here.
     */
    suspend fun pair(endpoint: Endpoint, code: String, name: String): Result<Credentials> = try {
        val r = TowerClient(endpoint, { null }).pair(code, name)
        val fp = Fingerprint.normalize(r.fingerprint)
        if (fp != null && !Fingerprint.matches(fp, endpoint.fingerprint)) {
            throw SSLMismatch(CertificateMismatchException(endpoint.fingerprint, fp))
        }
        val c = Credentials(endpoint.host, endpoint.port, endpoint.fingerprint, r.token, r.device.id, r.device.name.ifBlank { name })
        store.save(c)
        client = clientFor(c)
        _history.value = emptyList()
        _creds.value = c
        guessName(c.host)
        Result.success(c)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** Forgets the tower; with [tellTower] also revokes this phone's token on the Mac first. */
    suspend fun unpair(tellTower: Boolean) {
        if (tellTower) act { it.unpair() }
        setVoice(false)
        player.hush()
        store.clear()
        client = null
        _creds.value = null
        _state.value = null
        _history.value = emptyList()
        _spaces.value = SpacesResponse()
        _jobs.value = emptyList()
        _agents.value = emptyList()
        _screenshots.value = emptyList()
    }

    companion object {
        private const val TAG = "TowerSession"
        /** Seconds between reconnects, as the contract asks: 1, 2, 5, 10, then every 10. */
        val BACKOFF = intArrayOf(1, 2, 5, 10)
        /** Missed clips younger than this are played on reconnect. */
        const val RECENT_MS = 60_000L
        /** How long the stream stays open after asking, with the app in the background. */
        const val LINGER_MS = 120_000L

        fun defaultDeviceName(): String = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android phone"

        /** Merges entries, oldest first, without duplicates (the stream and a reload can both bring one). */
        fun merge(a: List<HistoryEntry>, b: List<HistoryEntry>): List<HistoryEntry> =
            (a + b).distinctBy { listOf(it.at, it.kind, it.session, it.text) }.sortedBy { it.at }.takeLast(2000)

        /** Newest first, each once (the state's recent ones and a fetched page overlap). */
        fun mergeShots(a: List<Screenshot>, b: List<Screenshot>): List<Screenshot> =
            (b + a).distinctBy { it.id }.sortedByDescending { it.at }.take(300)

        fun describe(e: Throwable): String = when (e) {
            is TowerApiException -> when (e.code) {
                "wrong_code" -> "That isn't the code on the Mac." + (e.triesLeft?.let { " $it tries left." } ?: "")
                "pairing_closed" -> "No pairing code is open on the Mac. Run `tower phone pair` there."
                "too_many_failures" -> "Too many failed tries. Wait a minute."
                "too_many_images" -> "Six pictures at most in one message."
                "image_too_large" -> "A picture is too large (15 MB at most)."
                "not_an_image" -> "The tower takes PNG, JPEG, WebP or GIF pictures."
                else -> e.message
            }
            is SSLMismatch -> "The tower's certificate doesn't match. If the Mac's certificate was remade, pair again; otherwise something is in between."
            else -> e.message ?: e.javaClass.simpleName
        }
    }
}
