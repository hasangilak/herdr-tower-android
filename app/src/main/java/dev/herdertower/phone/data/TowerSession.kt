package dev.herdertower.phone.data

import android.content.Context
import android.os.Build
import android.util.Log
import dev.herdertower.phone.api.AccountsResponse
import dev.herdertower.phone.api.BehaviorChange
import dev.herdertower.phone.api.BehaviorResult
import dev.herdertower.phone.api.BehaviorsResponse
import dev.herdertower.phone.api.Question
import dev.herdertower.phone.api.QuestionsApi
import dev.herdertower.phone.api.TabAnswer
import dev.herdertower.phone.api.Agent
import dev.herdertower.phone.api.Act
import dev.herdertower.phone.api.BrainModel
import dev.herdertower.phone.api.HeldSession
import dev.herdertower.phone.api.UndoResult
import dev.herdertower.phone.api.BusySession
import dev.herdertower.phone.api.LaunchPlan
import dev.herdertower.phone.api.TowerAction
import dev.herdertower.phone.api.Decision
import dev.herdertower.phone.api.DecisionAnswered
import dev.herdertower.phone.api.DecisionsApi
import dev.herdertower.phone.api.FilePart
import dev.herdertower.phone.api.Mission
import dev.herdertower.phone.api.MissionsApi
import dev.herdertower.phone.api.MissionsResponse
import dev.herdertower.phone.api.Setup
import dev.herdertower.phone.api.ThreadMessage
import dev.herdertower.phone.api.VoiceResult
import dev.herdertower.phone.voice.TalkTarget
import dev.herdertower.phone.api.CertificateMismatchException
import dev.herdertower.phone.api.ChatApi
import dev.herdertower.phone.api.ChatMessage
import dev.herdertower.phone.api.MergeConfirm
import dev.herdertower.phone.api.MergeResult
import dev.herdertower.phone.api.Pr
import dev.herdertower.phone.api.PrRef
import dev.herdertower.phone.api.PrsApi
import dev.herdertower.phone.api.ActiveRoute
import dev.herdertower.phone.api.Endpoint
import dev.herdertower.phone.api.Hosts
import dev.herdertower.phone.api.TowerHost
import dev.herdertower.phone.api.UnreachableException
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The connection to the tower, as the app shows it. */
sealed interface Link {
    /** No tower paired yet. */
    data object Unpaired : Link
    /** In the background and not the voice: the stream is closed on purpose. */
    data object Idle : Link
    /** Opening the stream; [failing] is why the last attempt failed (null on a first try), so a retry in flight still shows it. */
    data class Connecting(val attempt: Int, val failing: String? = null) : Link
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
class TowerSession(
    private val context: Context,
    private val store: CredentialStore,
    val settings: Settings,
    /** The phone's side of Tailscale (whether its VPN is up), for saying why the Mac can't be reached. */
    private val net: PhoneNet = SystemPhoneNet(context),
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _creds = MutableStateFlow(store.load())
    val credentials: StateFlow<Credentials?> = _creds.asStateFlow()

    /** The client for the route in use (the last that worked, until a connect picks again). */
    @Volatile private var client: TowerClient? = _creds.value?.let { c -> clientAt(c, c.hosts.firstOrNull { it.id == settings.lastRoute } ?: c.hosts.first()) }

    private val _activeRoute = MutableStateFlow<ActiveRoute?>(null)
    /** How the phone reaches the tower now (Wi-Fi or Tailscale), once a connect got through; shown while live. */
    val activeRoute: StateFlow<ActiveRoute?> = _activeRoute.asStateFlow()

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

    private val _thread = MutableStateFlow<List<ChatMessage>>(emptyList())
    /** The tower's main chat thread, the same as the Mac panel's (oldest first). */
    val thread: StateFlow<List<ChatMessage>> = _thread.asStateFlow()

    private val _threadSupported = MutableStateFlow<Boolean?>(null)
    /** Whether the tower has the thread (`/v1/chat`); false: a tower from before, the chat is built from the history. */
    val threadSupported: StateFlow<Boolean?> = _threadSupported.asStateFlow()

    private val _threadMore = MutableStateFlow(true)
    /** Whether older messages may exist before the oldest loaded. */
    val threadMore: StateFlow<Boolean> = _threadMore.asStateFlow()

    private val _screenshots = MutableStateFlow<List<Screenshot>>(emptyList())
    /** Screenshots sessions sent, newest first. */
    val screenshots: StateFlow<List<Screenshot>> = _screenshots.asStateFlow()

    /** Screenshot images, fetched with the token and cached. */
    val images = Images(this) { context.cacheDir?.let { java.io.File(it, "thumbs") } }

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
    /**
     * Skip the backoff: conflated, so a tap (or the Wi-Fi coming back) while
     * an attempt is still in flight isn't lost; it retries as soon as that
     * attempt fails. Drained once the stream is live.
     */
    private val retryNow = Channel<Unit>(Channel.CONFLATED)

    // Declared before init: its coroutines start at once on the main thread and read these.
    /** Network changes, settled and acted on in init (see [networkChanged]). */
    private val netChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Drops the live stream so the next connect picks the route again (the LAN first), without the retry card. */
    private val kick = Channel<Unit>(Channel.CONFLATED)
    /** The token was refused or the certificate changed: no stream until this phone pairs again. */
    private val _blocked = MutableStateFlow<Link?>(null)
    private var refreshJob: CoJob? = null

    val player = ClipPlayer(context, scope) { clip ->
        (client ?: throw IllegalStateException("not paired")).audio(clip)
    }

    init {
        _creds.value?.let { guessName(it.host) }
        scope.launch {
            // Keyed on the mode, not on each flag: with the phone as the voice, going to the background
            // or back must not reopen the voice stream (the Mac would take over mid-reply).
            combine(_creds, _voiceOn, _foreground, _lingering, _blocked) { c, v, fg, linger, blocked ->
                // The pairing, not its address list: the tower sending its current addresses mustn't reopen the stream.
                if (c == null) null else Want("${c.deviceId}|${c.fingerprint}|${c.token.hashCode()}", streamMode(v, fg, linger), blocked)
            }
                .distinctUntilChanged()
                .collectLatest { want ->
                    when {
                        want == null -> {
                            player.hush()
                            _link.value = Link.Unpaired
                        }
                        want.blocked != null -> {
                            player.hush()
                            _link.value = want.blocked
                        }
                        want.mode == StreamMode.Idle -> {
                            player.hush()
                            _link.value = Link.Idle
                        }
                        else -> {
                            if (want.mode != StreamMode.Voice) player.hush()
                            stream(want.mode == StreamMode.Voice)
                        }
                    }
                }
        }
        // Network changes: settle, then see whether the route still holds (or the LAN is back).
        scope.launch {
            netChanges.collectLatest {
                delay(NET_SETTLE_MS)
                recheckRoute()
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

    private data class Want(val pairing: String, val mode: StreamMode, val blocked: Link?)

    /** Which stream to keep open: none, the panel's feed, or the voice stream (which also feeds the panel). */
    enum class StreamMode { Idle, Panel, Voice }

    fun setForeground(fg: Boolean) {
        _foreground.value = fg
        // On screen, the cards show: their notifications go.
        if (fg) decisionNotices.clearAll()
    }

    fun setVoice(on: Boolean) {
        settings.voice = on
        _voiceOn.value = on && _creds.value != null
    }

    /** Skip the backoff wait and reconnect now. */
    fun reconnect() {
        retryNow.trySend(Unit)
    }

    /**
     * The phone's network changed (Wi-Fi joined or lost, Tailscale's VPN on
     * or off). Not connected: try again now. Connected: the route in use may
     * be gone (Tailscale turned off leaves the stream silent until its read
     * timeout), or the LAN may be back while on Tailscale; either way the
     * stream moves at once (see [recheckRoute]).
     */
    fun networkChanged() {
        netChanges.tryEmit(Unit)
    }


    /** The stream was dropped on purpose to move to another route. */
    private class RouteChanged : java.io.IOException("Moving to another route.")

    /** The tower ended the stream. */
    private class StreamEnded : java.io.IOException("The tower closed the connection.")

    /** After a network change has settled: is the route in use still there, and is the LAN back? */
    private suspend fun recheckRoute() {
        val c = _creds.value ?: return
        if (_link.value !is Link.Live) {
            retryNow.trySend(Unit)
            return
        }
        val route = _activeRoute.value?.host ?: return
        if (probe(c, route).outcome != RouteOutcome.Answered) {
            // It just failed: the next connect tries it last, not first again.
            avoidRoute = route.id
            kick.trySend(Unit)
            return
        }
        // On Tailscale and the LAN answers again (home): the direct path.
        if (route.tailscale && Hosts.candidates(c.hosts, c.magicDns, null).filter { !it.tailscale }.any { probe(c, it).outcome == RouteOutcome.Answered }) {
            kick.trySend(Unit)
        }
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

    private fun clientAt(c: Credentials, h: TowerHost) = TowerClient(c.endpointAt(h), { _creds.value?.token })

    // Routes: the LAN, then Tailscale

    private sealed interface Pick {
        data class Found(val host: TowerHost) : Pick
        /** Every address answered with another certificate. */
        data class Mismatch(val got: String) : Pick
        data class Nowhere(val message: String) : Pick
    }

    private val _routeTries = MutableStateFlow<List<RouteTry>>(emptyList())
    /** What each address did on the last try (shown in the menu, and in the message when none answered). */
    val routeTries: StateFlow<List<RouteTry>> = _routeTries.asStateFlow()

    /** Why nothing answered, in words that say what to do on this phone. */
    private fun unreachable(c: Credentials): String = RouteWords.unreachable(
        tailnet = Hosts.candidates(c.hosts, c.magicDns, null).any { it.tailscale },
        vpnOn = net.vpnOn(),
        tailscaleInstalled = net.tailscaleInstalled(),
        onWifi = net.onWifi(),
        localNetworkBlocked = net.localNetworkBlocked(),
        lan = _routeTries.value.firstOrNull { !it.host.tailscale && it.outcome != RouteOutcome.Answered },
    )

    /** Tries one address: hello over a short connect, the pinned certificate checked as everywhere. */
    private suspend fun probe(c: Credentials, h: TowerHost): RouteTry {
        val client = TowerClient(c.endpointAt(h), { _creds.value?.token }, connectTimeoutMs = PROBE_CONNECT_MS)
        val outcome = try {
            withTimeoutOrNull(PROBE_TOTAL_MS) { client.hello(); RouteOutcome.Answered } ?: RouteOutcome.TimedOut
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnauthorizedException) {
            RouteOutcome.Answered // it's there; the stream then says the token was refused
        } catch (e: TowerApiException) {
            RouteOutcome.Answered
        } catch (e: SSLMismatch) {
            return RouteTry(h, RouteOutcome.OtherCertificate, System.currentTimeMillis(), e.mismatch.got)
        } catch (e: Exception) {
            RouteOutcome.of(e)
        }
        return RouteTry(h, outcome, System.currentTimeMillis())
    }

    /**
     * Where the tower answers now: each address in turn (the LAN first, with
     * a short connect timeout), the same pinned certificate for all. One
     * address only: no probe, the stream's own connect says.
     */
    /** A route that just failed a check: tried last by the next connect (once). */
    @Volatile private var avoidRoute: String? = null

    private suspend fun pick(c: Credentials): Pick {
        val avoid = avoidRoute.also { avoidRoute = null }
        val all = Hosts.candidates(c.hosts, c.magicDns, settings.lastRoute).sortedBy { it.id == avoid }
        if (all.size <= 1) return Pick.Found(all.firstOrNull() ?: c.hosts.first())
        val tries = mutableListOf<RouteTry>()
        var got = ""
        try {
            for (h in all) {
                val t = probe(c, h)
                tries += t
                if (t.outcome == RouteOutcome.Answered) return Pick.Found(h)
                // Another machine at this address (another network's 192.168.x): not the tower, try the next.
                t.got?.let { got = it }
            }
        } finally {
            _routeTries.value = tries.toList()
        }
        return if (tries.all { it.outcome == RouteOutcome.OtherCertificate }) Pick.Mismatch(got) else Pick.Nowhere(unreachable(c))
    }

    /** The client for [h], kept while the route stays the same. */
    private fun useRoute(c: Credentials, h: TowerHost): TowerClient {
        val current = client
        if (current != null && current.endpoint == c.endpointAt(h)) return current
        return clientAt(c, h).also { client = it }
    }

    /** The tower's current addresses (state `lan_hosts`): stored, so the next connect tries them; the stream stays. */
    private fun learnHosts(fresh: List<TowerHost>, dns: String?) {
        val c = _creds.value ?: return
        val hosts = Hosts.refresh(c.hosts, fresh)
        val magic = dns?.trim()?.trimEnd('.')?.takeIf { it.isNotEmpty() } ?: hosts.firstNotNullOfOrNull { it.dnsName } ?: c.magicDns
        if (hosts == c.hosts && magic == c.magicDns) return
        val next = c.copy(hosts = hosts, magicDns = magic)
        store.save(next)
        _creds.value = next
    }

    // The stream

    private suspend fun stream(voice: Boolean) {
        var attempt = 0
        var failing: String? = null
        var switching = false
        while (true) {
            val creds = _creds.value ?: return
            // Moving to another route: the app stays "live" until it lands (no flash of Not connected).
            if (!switching) _link.value = Link.Connecting(attempt, failing)
            switching = false
            kick.tryReceive() // a move asked for before this connect
            var route: TowerHost? = null
            var live = false
            val reason = try {
                val c = when (val p = pick(creds)) {
                    is Pick.Found -> useRoute(creds, p.host).also { route = p.host }
                    is Pick.Mismatch -> {
                        block(Link.CertificateChanged(p.got))
                        return
                    }
                    is Pick.Nowhere -> throw UnreachableException(p.message, null)
                }
                // The stream, until it ends or the route is to change (network change, see recheckRoute).
                // A stream the tower ends itself (it stopped, or restarted) ends this too: merged as it is, the
                // route kicks (which never end) would keep the collect open on nothing, and the app "live" on a dead stream.
                val events = kotlinx.coroutines.flow.flow {
                    emitAll(c.events(voice))
                    throw StreamEnded()
                }
                merge(events, kick.receiveAsFlow().map<Unit, TowerEvent> { throw RouteChanged() }).collect { ev ->
                    if (ev is TowerEvent.Hello) {
                        // What this tower has is checked once, on connecting: the Quiet Tower, or the calls from before it.
                        quiet(ev.hello.quiet)
                        _actsSupported.value = ev.hello.acts
                        live = true
                        route?.let {
                            _activeRoute.value = ActiveRoute(it)
                            settings.lastRoute = it.id
                        }
                        attempt = 0
                        failing = null
                        retryNow.tryReceive() // a retry asked for before this connect is done
                        _link.value = Link.Live(voice)
                        refreshAll()
                    }
                    handle(ev)
                }
                "The tower closed the connection."
            } catch (e: CancellationException) {
                throw e
            } catch (e: RouteChanged) {
                switching = true
                ""
            } catch (e: UnauthorizedException) {
                block(Link.Revoked(e.message))
                return
            } catch (e: SSLMismatch) {
                block(Link.CertificateChanged(e.mismatch.got))
                return
            } catch (e: UnreachableException) {
                // A single address (no probes): what it did, for the message and the menu.
                if (!live && route != null && _routeTries.value.none { it.host == route }) {
                    _routeTries.value = listOf(RouteTry(route!!, RouteOutcome.of(e), System.currentTimeMillis()))
                }
                // Never got through this time: say what the phone can do about it (Tailscale off, another network).
                if (live) e.message ?: "Connection lost." else e.message?.takeIf { it.startsWith("Can't reach the Mac") || it.startsWith("Secure connection failed") } ?: unreachable(creds)
            } catch (e: Exception) {
                e.message ?: "Connection lost."
            }
            if (switching) {
                // Straight on to the new route: no backoff, no retry card.
                attempt = 0
                failing = null
                continue
            }
            // A clip half-played when the voice dropped won't be resumed; the Mac takes over.
            if (voice) player.hush()
            val wait = BACKOFF[minOf(attempt, BACKOFF.lastIndex)]
            attempt++
            failing = reason
            _link.value = Link.Retrying(wait, reason, attempt)
            withTimeoutOrNull(wait * 1000L) { retryNow.receive() }
        }
    }

    /** Stops the stream for good (until pairing again) and says why. */
    private fun block(l: Link) {
        _link.value = l
        _blocked.value = l
    }

    private fun handle(ev: TowerEvent) {
        when (ev) {
            is TowerEvent.State -> {
                val before = _state.value
                _state.value = ev.state
                if (ev.state.lanHosts.isNotEmpty()) learnHosts(ev.state.lanHosts, ev.state.tailscale?.dnsName?.takeIf { ev.state.tailscale?.running == true })
                // While a mute this phone asked for is on its way (and just after), a state sent before it is stale.
                if (ev.state.muted != _muted.value && !_muting.value && System.currentTimeMillis() >= muteHeldUntil) mutedIs(ev.state.muted)
                if (ev.state.screenshots.isNotEmpty()) _screenshots.value = mergeShots(_screenshots.value, ev.state.screenshots)
                ev.state.questionPolicy?.let {
                    _questionPolicy.value = it
                    _questionsSupported.value = true
                    setQuestions(ev.state.questions)
                }
                ev.state.tellLevel?.let { _tell.value = it }
                ev.state.tellVoice?.let { _tellVoice.value = it }
                ev.state.brainModel?.let { _model.value = it }
                if (before != null && before.agents.map { it.address to it.status } != ev.state.agents.map { it.address to it.status }) refreshSoon()
            }
            is TowerEvent.History -> {
                _history.value = merge(_history.value, listOf(ev.entry))
                if (ev.entry.kind in setOf("job", "summary", "watch", "alert", "screenshot")) refreshSoon()
            }
            is TowerEvent.Chat -> {
                _threadSupported.value = true
                val m = ev.message
                m.act?.let(::setAct)
                if (ChatApi.inMain(m)) _thread.value = ChatApi.merge(_thread.value, listOf(m))
                m.space?.takeIf { it.isNotBlank() && ChatApi.inSpace(m, it) }?.let { sp ->
                    _spaceChats.value = _spaceChats.value + (sp to ChatApi.merge(_spaceChats.value[sp].orEmpty(), listOf(m)))
                }
                PrsApi.of(m)?.let { setPrs(listOf(it)) }
            }
            is TowerEvent.PrChanged -> {
                _prsSupported.value = true
                setPrs(listOf(ev.change.pr))
            }
            is TowerEvent.Audio -> {
                lastClip = ev.clip.id
                player.enqueue(ev.clip)
            }
            TowerEvent.Hush -> player.hush()
            TowerEvent.Pause -> player.pause()
            TowerEvent.Resume -> player.resume()
            is TowerEvent.BehaviorsChanged -> {
                _behaviorsSupported.value = true
                _lastBehaviorChange.value = ev.change
                scope.launch { loadBehaviors() }
            }
            is TowerEvent.QuestionChanged -> {
                _questionsSupported.value = true
                setQuestions(QuestionsApi.apply(_questions.value, ev.change))
            }
            is TowerEvent.DecisionsChanged -> {
                _decisionsSupported.value = true
                ev.decisions?.let { setDecisions(it.decisions) } ?: scope.launch { loadDecisions() }
            }
            is TowerEvent.MissionsChanged -> {
                _missionsSupported.value = true
                ev.missions?.let { _missions.value = it } ?: scope.launch { loadMissions() }
            }
            is TowerEvent.ThreadAdded -> {
                val sp = ev.event.space ?: ev.event.message.space
                if (sp != null) _spaceThreads.value[sp]?.let { t -> _spaceThreads.value = _spaceThreads.value + (sp to t.with(listOf(ev.event.message))) }
            }
            is TowerEvent.ActionChanged -> setAction(ev.action)
            is TowerEvent.ActChanged -> setAct(ev.act)
            is TowerEvent.Hello, TowerEvent.Ping, is TowerEvent.Unknown -> {}
        }
    }

    // The queue: the decisions only the user can make

    private val _decisions = MutableStateFlow<List<Decision>>(emptyList())
    /** What waits for the user, in the tower's order (muted spaces left out: they are in the space's own view). */
    val decisions: StateFlow<List<Decision>> = _decisions.asStateFlow()

    private val _decisionsSupported = MutableStateFlow<Boolean?>(null)
    /**
     * Whether the tower has the queue (`/v1/decisions`). False: a tower from
     * before it, and the queue is made here from its questions and its
     * waiting PRs. Null: not known yet.
     */
    val decisionsSupported: StateFlow<Boolean?> = _decisionsSupported.asStateFlow()

    private val decisionNotices = DecisionNotices(context)

    /** What this phone did to a decision the tower doesn't show yet (id to state and until when), as [localStatus] for questions. */
    private val decisionHold = mutableMapOf<String, Pair<String, Long>>()
    /** Why the last answer from here didn't go in, by decision. */
    private val decisionErrors = mutableMapOf<String, String>()

    /** Every open decision as the tower last gave it (muted ones marked), or as it was made here, before this phone's own marks on it. */
    private var queue: List<Decision> = emptyList()

    private fun setDecisions(list: List<Decision>) {
        queue = list
        showDecisions()
        decisionNotices.follow(held(queue), background = !_foreground.value)
        deriveMissions()
    }

    /** The main queue with this phone's marks: shown again whenever one of them changes. */
    private fun showDecisions() {
        _decisions.value = held(DecisionsApi.main(queue))
    }

    /** [list] as this phone shows it: an answer on its way stays "answering", a failed one says why. */
    private fun held(list: List<Decision>): List<Decision> {
        val now = System.currentTimeMillis()
        decisionHold.entries.removeAll { it.value.second < now }
        return list.map { d ->
            val state = decisionHold[d.id]?.first?.takeIf { d.state == "open" }
            val error = decisionErrors[d.id]
            when {
                state != null -> d.copy(state = state, error = null)
                error != null && d.error == null && d.open -> d.copy(error = error)
                else -> d
            }
        }
    }

    /** A tower without the queue: the same queue from what it does have. */
    private fun deriveDecisions() {
        if (_decisionsSupported.value != false) return
        setDecisions(DecisionsApi.fromLegacy(_questions.value, _prs.value.values, ::spaceOf, System.currentTimeMillis()))
    }

    /** The space a session is in, as far as the phone knows. */
    private fun spaceOf(address: String): String? =
        _missions.value.spaces.firstOrNull { m -> m.sessions.any { it.address == address } }?.name
            ?: _spaces.value.spaces.firstOrNull { sp -> sp.sessions.any { it.address == address } }?.space

    /**
     * Reads the queue. A read that fails for a moment (the Mac busy, the
     * network hiccuping) is tried again: the queue is what notifies, also
     * with the app in the background, where nothing else would read it again.
     */
    private suspend fun loadDecisions() {
        if (_decisionsSupported.value == false) return
        for (wait in RELOAD_WAITS_MS) {
            val c = client ?: return
            try {
                val list = c.decisions().decisions
                _decisionsSupported.value = true
                // The main queue leaves a muted space's out: those the event brought stay (they're seen, never notified).
                setDecisions(list + queue.filter { it.muted && list.none { d -> d.id == it.id } })
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: UnauthorizedException) {
                return block(Link.Revoked(e.message))
            } catch (e: TowerApiException) {
                if (e.status == 404) {
                    _decisionsSupported.value = false
                    deriveDecisions()
                }
                return
            } catch (e: Exception) {
                Log.i(TAG, "decisions: ${e.javaClass.simpleName}")
            }
            if (wait < 0 || _link.value !is Link.Live) return
            delay(wait)
        }
    }

    /** One space's own decisions (a muted space's aren't in the queue), in order. */
    suspend fun spaceDecisions(space: String): List<Decision> {
        if (_decisionsSupported.value == true) {
            act { it.decisions(space) }.onSuccess { return held(it.decisions) }
        }
        return held(queue.filter { it.space == space })
    }

    /** Answers [d] with its option [index]; the tower carries it out. A merge goes through its PR's own two steps instead. */
    suspend fun answerDecision(d: Decision, index: Int): Result<Unit> {
        val option = d.options.getOrNull(index) ?: return Result.failure(IllegalArgumentException("That answer isn't there any more."))
        if (d.kind == "merge" && !d.legacy) return Result.failure(IllegalStateException("A merge is done from its pull request: open the app to see what merging would do."))
        DecisionsApi.legacyQuestionId(d.id)?.let { qid ->
            val q = _questions.value.firstOrNull { it.id == qid } ?: act { it.question(qid) }.getOrElse { return Result.failure(it) }
            val choice = DecisionsApi.legacyChoice(q, option.label) ?: return Result.failure(IllegalStateException("The question changed: open it to answer."))
            return answerQuestion(qid, listOf(TabAnswer(0, choice)))
        }
        return sendDecision(d.id, d, option.label) { it.answerDecision(d.id, DecisionsApi.choiceBody(index)) }
    }

    /** Answers [d] in the user's own words. */
    suspend fun answerDecisionOther(d: Decision, text: String): Result<Unit> {
        DecisionsApi.legacyQuestionId(d.id)?.let { qid ->
            return answerQuestion(qid, listOf(TabAnswer(0, dev.herdertower.phone.api.Choice.Own(text))))
        }
        return sendDecision(d.id, d, text) { it.answerDecision(d.id, DecisionsApi.otherBody(text)) }
    }

    private suspend fun sendDecision(id: String, d: Decision? = null, words: String? = null, call: suspend (TowerClient) -> DecisionAnswered): Result<Unit> {
        decisionHold[id] = "answering" to Long.MAX_VALUE
        decisionErrors.remove(id)
        showDecisions()
        return act { call(it) }.map { r ->
            // A question's answer may still be going in ("answering"): the decisions event says when it's over.
            decisionHold[id] = r.state.ifBlank { "answered" } to System.currentTimeMillis() + ANSWERED_HOLD_MS
            showDecisions()
            decisionNotices.answered(id)
            linger()
            // The answer can become a standing rule: offered once, beside the queue.
            r.rule?.takeIf { it.can && !it.text.isNullOrBlank() }?.let { rule ->
                _ruleOffers.value = _ruleOffers.value.filterNot { it.id == id } + RuleOffer(id, d?.space.orEmpty(), d?.title.orEmpty(), rule.text!!, words)
            }
            Unit
        }.recoverCatching { e ->
            val over = DecisionsApi.closedBy(e) ?: throw e
            // Answered on another screen or in the session meanwhile: the card goes, nothing was sent.
            decisionHold[id] = over to System.currentTimeMillis() + ANSWERED_HOLD_MS
            showDecisions()
            decisionNotices.answered(id)
            scope.launch { loadDecisions() }
            throw DecisionOver(DecisionsApi.closedWords(over))
        }.onFailure { e ->
            if (e is DecisionOver) return@onFailure
            decisionHold.remove(id)
            decisionErrors[id] = describe(e)
            showDecisions()
        }
    }

    /** Not the user's after all: it leaves the queue (a merge's PRs make no decision again while they wait). */
    suspend fun dismissDecision(d: Decision): Result<Unit> {
        DecisionsApi.legacyQuestionId(d.id)?.let { return dismissQuestion(it) }
        if (d.legacy) return Result.failure(IllegalStateException("Merge or close the pull requests: they wait until then."))
        return act { it.dismissDecision(d.id) }.onSuccess {
            decisionNotices.answered(d.id)
            setDecisions(queue.filterNot { it.id == d.id })
        }
    }

    /** An answer tapped on a decision's notification (the app may not be on screen, or even running). */
    suspend fun answerFromNotice(id: String, index: Int, label: String) {
        val d = _decisions.value.firstOrNull { it.id == id }
        val result = when {
            d != null -> answerDecision(d, d.options.indexOfFirst { it.label.take(40) == label }.takeIf { it >= 0 } ?: index)
            // The process was started for this tap: no queue yet. The tower's own ids answer by index; one made here by its label.
            DecisionsApi.legacyQuestionId(id) != null -> answerDecision(Decision(id = id, options = List(index + 1) { dev.herdertower.phone.api.DecisionOption(label) }), index)
            else -> sendDecision(id) { it.answerDecision(id, DecisionsApi.choiceBody(index)) }
        }
        // Over already (answered elsewhere): nothing left to say on it.
        result.onSuccess { decisionNotices.answered(id) }.onFailure { if (it is DecisionOver) decisionNotices.answered(id) else noticeFailed(id, describe(it)) }
    }

    fun noticeFailed(id: String, why: String) {
        decisionNotices.failed(_decisions.value.firstOrNull { it.id == id } ?: Decision(id = id, title = "A decision waits for you"), why)
    }

    /**
     * The check the background worker makes (DecisionCheck, every 15
     * minutes while paired): with no stream open (the app closed, the phone
     * not the voice), the queue is read once, so a new decision notifies all
     * the same. With the stream open it has nothing to do: the stream follows
     * the queue. Returns what it did, for the log and the tests.
     */
    suspend fun checkInBackground(): String {
        val creds = _creds.value ?: return "not paired"
        if (_blocked.value != null) return "blocked: ${_blocked.value}"
        if (_link.value is Link.Live) return "the stream is open"
        val c = when (val p = pick(creds)) {
            is Pick.Found -> clientAt(creds, p.host)
            is Pick.Mismatch -> {
                block(Link.CertificateChanged(p.got))
                return "another certificate"
            }
            is Pick.Nowhere -> return "unreachable: ${p.message}"
        }
        return try {
            val quiet = c.hello().features.orEmpty().contains("quiet")
            if (quiet) {
                _decisionsSupported.value = true
                val list = c.decisions().decisions
                setDecisions(list + queue.filter { it.muted && list.none { d -> d.id == it.id } })
                "${list.size} open"
            } else {
                // A tower from before the queue: what needs the user is its escalated questions and its waiting PRs.
                _decisionsSupported.value = false
                setQuestions(c.questions())
                runCatching { setPrs(c.prs()) }
                deriveDecisions()
                "${_decisions.value.size} open (made here)"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnauthorizedException) {
            block(Link.Revoked(e.message))
            "revoked"
        } catch (e: Exception) {
            "failed: ${e.javaClass.simpleName}"
        }
    }

    /** What this tower has, from its hello: the Quiet Tower's calls, or the queue and missions made here from the ones before it. */
    private fun quiet(on: Boolean) {
        _decisionsSupported.value = on
        _missionsSupported.value = on
        if (!on) {
            deriveMissions()
            deriveDecisions()
        }
    }

    // Make it a rule: an answer given here, as a standing rule

    /** An answer that can become a rule: [text] is what the tower would be asked; [answer] the words given. */
    data class RuleOffer(val id: String, val space: String, val title: String, val text: String, val answer: String?)

    private val _ruleOffers = MutableStateFlow<List<RuleOffer>>(emptyList())
    /** Answers just given that the tower offers to make standing rules, oldest first. */
    val ruleOffers: StateFlow<List<RuleOffer>> = _ruleOffers.asStateFlow()

    /** Asks the tower of the decision's space to add the rule; it arrives as a behavior change. */
    suspend fun makeRule(o: RuleOffer): Result<Unit> = act { it.decisionRule(o.id, o.answer) }.map { }.also { r ->
        // Sent, or not to be offered any more (409 no_rule, not_answered): the offer goes either way but for a network failure.
        if (r.isSuccess || (r.exceptionOrNull() as? TowerApiException)?.status == 409) dropRule(o)
        if (r.isSuccess) linger()
    }

    fun dropRule(o: RuleOffer) {
        _ruleOffers.value = _ruleOffers.value.filterNot { it.id == o.id }
    }

    // Acts: what the tower opened for a command, and taking it back

    private val _actsSupported = MutableStateFlow(false)
    /** Whether the tower keeps acts (receipts with undo), rules from answers and a voice level. */
    val actsSupported: StateFlow<Boolean> = _actsSupported.asStateFlow()

    private val _acts = MutableStateFlow<Map<String, Act>>(emptyMap())
    /** Every act seen (in the chat, from its event, or undone here), as it last stood. */
    val acts: StateFlow<Map<String, Act>> = _acts.asStateFlow()

    private fun setAct(a: Act) {
        if (a.id.isNotBlank()) _acts.value = _acts.value + (a.id to a)
    }

    /**
     * Ends what an act opened. While a session of it would lose work it
     * fails with [ActHeld] (ask, then [force]); undone already, it is read
     * again and shown as it is.
     */
    suspend fun undoAct(id: String, force: Boolean = false): Result<UndoResult> = act { it.undoAct(id, force) }
        .onSuccess { r ->
            setAct(r.act)
            scope.launch {
                loadMissions()
                loadDecisions()
            }
        }
        .recoverCatching { e ->
            val t = e as? TowerApiException ?: throw e
            when {
                t.status == 409 && t.code == "held" ->
                    throw ActHeld(t.list("sessions") { dev.herdertower.phone.api.TowerJson.decodeFromJsonElement(HeldSession.serializer(), it) }, t.message)
                t.status == 409 && t.code == "undone" -> {
                    quietly { c -> c.acts().forEach(::setAct) }
                    throw IllegalStateException("It was undone already.")
                }
                else -> throw e
            }
        }

    // Spaces as missions

    private val _missions = MutableStateFlow(MissionsResponse())
    /** Every open space as a mission, the quiet ones and today's closed ones by name. */
    val missions: StateFlow<MissionsResponse> = _missions.asStateFlow()

    private val _missionsSupported = MutableStateFlow<Boolean?>(null)
    /** Whether the tower has missions (`/v1/missions`); false: they're made here from its spaces and jobs. */
    val missionsSupported: StateFlow<Boolean?> = _missionsSupported.asStateFlow()

    private fun deriveMissions() {
        if (_missionsSupported.value != false) return
        _missions.value = MissionsApi.fromSpaces(_spaces.value, _jobs.value, _decisions.value)
    }

    private suspend fun loadMissions() {
        if (_missionsSupported.value == false) return
        val c = client ?: return
        try {
            _missions.value = c.missions()
            _missionsSupported.value = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnauthorizedException) {
            block(Link.Revoked(e.message))
        } catch (e: TowerApiException) {
            if (e.status == 404) {
                _missionsSupported.value = false
                deriveMissions()
                // The queue names each decision's space from these.
                deriveDecisions()
            }
        } catch (e: Exception) {
            Log.i(TAG, "missions: ${e.javaClass.simpleName}")
        }
    }

    /** Runs a change to one space and shows the mission the tower answers with (or reads them all again). */
    private suspend fun changeMission(call: suspend (TowerClient) -> Mission?): Result<Unit> = act(call).map { m ->
        if (m == null) loadMissions()
        else _missions.value = _missions.value.let { all -> all.copy(spaces = if (all.spaces.any { it.name == m.name }) all.spaces.map { if (it.name == m.name) m else it } else all.spaces + m) }
    }

    suspend fun setGoal(space: String, goal: String, doneWhen: String?, standing: Boolean): Result<Unit> =
        changeMission { it.setGoal(space, goal, doneWhen.orEmpty(), standing) }

    suspend fun followSpace(space: String, on: Boolean): Result<Unit> = changeMission { it.follow(space, on) }

    /** Muted, a space's decisions stay in its own tower: off the queue. The queue is read again. */
    suspend fun muteSpace(space: String, on: Boolean): Result<Unit> = changeMission { it.muteSpace(space, on) }.onSuccess { loadDecisions() }

    /**
     * Closes a space: the tower answers at once with the action that ends its
     * sessions and removes its worktrees, followed in [actions]. While a
     * session there works or waits for the user it fails with [SpaceBusy]
     * (ask, then [force]: their work in progress is cut off).
     */
    suspend fun closeSpace(space: String, force: Boolean = false): Result<TowerAction> = act { it.closeSpace(space, force) }
        .recoverCatching { e ->
            val busy = e as? TowerApiException
            when {
                busy?.status == 409 && busy.code == "busy" ->
                    throw SpaceBusy(busy.list("sessions") { dev.herdertower.phone.api.TowerJson.decodeFromJsonElement(BusySession.serializer(), it) }, busy.message)
                busy?.status == 409 && busy.code == "closing" -> busy.word("action")?.let { id -> act { it.action(id) }.getOrThrow() } ?: throw e
                else -> throw e
            }
        }
        .onSuccess(::follow)

    // A space's thread: what its sessions wrote each other

    /** What a space's sessions wrote each other ([more]: older ones remain), as far as loaded. */
    data class SpaceThread(val messages: List<ThreadMessage> = emptyList(), val more: Boolean = false) {
        fun with(add: List<ThreadMessage>): SpaceThread = copy(messages = (messages + add).distinctBy { it.id.ifBlank { "${it.at}:${it.from}:${it.text.hashCode()}" } }.sortedBy { it.at })
    }

    private val _spaceThreads = MutableStateFlow<Map<String, SpaceThread>>(emptyMap())
    /** The threads of the spaces looked at, kept up to date by the `thread` event. */
    val spaceThreads: StateFlow<Map<String, SpaceThread>> = _spaceThreads.asStateFlow()

    suspend fun loadSpaceThread(space: String, limit: Int = 50): Result<SpaceThread> = act { it.thread(space, limit) }.map { r ->
        val t = SpaceThread(r.messages, r.more).let { fresh -> _spaceThreads.value[space]?.let { fresh.with(it.messages) } ?: fresh }
        _spaceThreads.value = _spaceThreads.value + (space to t)
        t
    }

    /** The messages before the oldest loaded. */
    suspend fun olderSpaceThread(space: String, limit: Int = 50): Result<Int> {
        val t = _spaceThreads.value[space] ?: return loadSpaceThread(space, limit).map { it.messages.size }
        val oldest = t.messages.firstOrNull()?.id?.takeIf { it.isNotBlank() } ?: return Result.success(0)
        return act { it.thread(space, limit, before = oldest) }.map { r ->
            _spaceThreads.value = _spaceThreads.value + (space to t.with(r.messages).copy(more = r.more))
            r.messages.size
        }
    }

    // A space's own conversation (its own tower's)

    private val _spaceChats = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    /** Each space's own conversation as far as loaded (requests, answers, receipts), kept up to date by the `chat` event. */
    val spaceChats: StateFlow<Map<String, List<ChatMessage>>> = _spaceChats.asStateFlow()

    suspend fun loadSpaceChat(space: String, limit: Int = 60) {
        if (_missionsSupported.value != true) return
        quietly { c ->
            val page = c.chat(limit = limit, space = space).messages.filter { ChatApi.inSpace(it, space) }
            page.mapNotNull { it.act }.forEach(::setAct)
            _spaceChats.value = _spaceChats.value + (space to ChatApi.merge(_spaceChats.value[space].orEmpty(), page))
        }
    }

    suspend fun setups(): Result<List<Setup>> = act { it.setups() }

    /** What launching [goal] would do (a dry run): the space's name, its sessions, and what the tower assumed. */
    suspend fun launchPlan(goal: String, setup: String?, name: String? = null): Result<LaunchPlan> = act { it.launchPlan(goal, setup, name) }

    /**
     * A space from one sentence: the tower answers at once with the action
     * that opens its sessions, followed in [actions] until it is done. A
     * launch already under way is the one followed.
     */
    suspend fun launch(goal: String, setup: String?, name: String? = null): Result<TowerAction> = act { it.launch(goal, setup, name) }
        .recoverCatching { e ->
            val running = (e as? TowerApiException)?.takeIf { it.status == 409 && it.code == "launching" }?.word("action") ?: throw e
            act { it.action(running) }.getOrThrow()
        }
        .onSuccess {
            follow(it)
            linger()
        }

    // Launches and closes: actions

    private val _actions = MutableStateFlow<Map<String, TowerAction>>(emptyMap())
    /** The launches and closes this phone started, as each last stood, by id. */
    val actions: StateFlow<Map<String, TowerAction>> = _actions.asStateFlow()

    private fun setAction(a: TowerAction) {
        val before = _actions.value[a.id]
        _actions.value = _actions.value + (a.id to a)
        // Over: the spaces and the queue as they are now (the action events come before theirs, or instead of them).
        if (!a.running && before?.running != false) scope.launch {
            loadMissions()
            loadDecisions()
            // A launch became an act: its undo shows in the new space.
            a.launch?.act?.takeIf { _actsSupported.value }?.let { id -> quietly { c -> setAct(c.act(id)) } }
        }
    }

    /**
     * Follows an action: its events move it, and it is asked about every few
     * seconds as well (a stream that dropped, or a tower that sends no action
     * events), until it is over or the tower no longer knows it.
     */
    private fun follow(a: TowerAction) {
        setAction(a)
        if (!a.running) return
        scope.launch {
            val until = System.currentTimeMillis() + ACTION_WATCH_MS
            while (_actions.value[a.id]?.running == true && System.currentTimeMillis() < until) {
                delay(ACTION_POLL_MS)
                if (_actions.value[a.id]?.running != true) break
                val c = client ?: break
                try {
                    setAction(c.action(a.id))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: TowerApiException) {
                    // A restarted tower forgets its actions: what it did is in the missions.
                    if (e.status == 404) {
                        loadMissions()
                        break
                    }
                } catch (e: Exception) {
                    Log.i(TAG, "action: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    // The brains' model

    private val _model = MutableStateFlow<BrainModel?>(null)
    /** What the tower's brains run on; null: not known (or a tower from before the setting). */
    val model: StateFlow<BrainModel?> = _model.asStateFlow()

    /** Another model or effort (it applies when a brain starts), and/or [restart] every brain now. */
    suspend fun setModel(model: String? = null, effort: String? = null, restart: Boolean? = null): Result<BrainModel> =
        act { it.setModel(model, effort, restart) }.onSuccess { _model.value = it }

    /** Reads the model again (after a restart, the brains say what they run on). */
    suspend fun loadModel() {
        if (_missionsSupported.value != true) return
        quietly { _model.value = it.model() }
    }

    // How much the tower tells the user

    private val _tell = MutableStateFlow<String?>(null)
    /** `decisions`, `results` or `everything`; null: a tower from before the setting. */
    val tell: StateFlow<String?> = _tell.asStateFlow()

    suspend fun setTell(level: String): Result<Unit> {
        val before = _tell.value
        _tell.value = level
        return act { it.setTell(level = level) }.map { t ->
            _tell.value = t.level
            t.voice?.let { _tellVoice.value = it }
            Unit
        }.onFailure { _tell.value = before }
    }

    private val _tellVoice = MutableStateFlow<String?>(null)
    /** What the tower says out loud: `off`, `decisions` or `replies`; null: a tower from before the setting. */
    val tellVoice: StateFlow<String?> = _tellVoice.asStateFlow()

    suspend fun setTellVoice(voice: String): Result<Unit> {
        val before = _tellVoice.value
        _tellVoice.value = voice
        return act { it.setTell(voice = voice) }.map { t -> _tellVoice.value = t.voice ?: voice }.onFailure { _tellVoice.value = before }
    }

    // Asking

    /** [text] for [space]'s tower; a tower from before spaces had their own gets the space in words. */
    private fun forSpace(text: String, space: String?): String =
        if (space != null && _missionsSupported.value == false) "In the space $space: $text" else text

    /** Sends words (and files) to the main tower, or with [space] to that space's own. */
    suspend fun ask(text: String, files: List<FilePart> = emptyList(), space: String? = null): Result<Unit> =
        act { it.ask(forSpace(text, space), files, space) }.map { }

    /**
     * A take from the talk button: to the main tower as it is; for a space,
     * written down by the Mac first and then sent with that space; into a
     * field (a new space's goal), only written down.
     */
    suspend fun take(wav: ByteArray, target: TalkTarget): Result<VoiceResult> = when (target) {
        TalkTarget.Main -> act { it.voice(wav) }
        is TalkTarget.Space -> act { c ->
            val heard = c.transcribe(wav).heard.trim()
            if (heard.isEmpty()) VoiceResult("", false) else {
                c.ask(forSpace(heard, target.name), target.name)
                VoiceResult(heard, true)
            }
        }
        is TalkTarget.Into -> act { c -> VoiceResult(c.transcribe(wav).heard.trim(), false).also { if (it.heard.isNotEmpty()) target.take(it.heard) } }
    }

    // Behaviors

    private val _behaviors = MutableStateFlow<BehaviorsResponse?>(null)
    /** How the tower works: every behavior, the fixed core; null until loaded (or a tower without them). */
    val behaviors: StateFlow<BehaviorsResponse?> = _behaviors.asStateFlow()

    private val _behaviorsSupported = MutableStateFlow(false)
    val behaviorsSupported: StateFlow<Boolean> = _behaviorsSupported.asStateFlow()

    private val _behaviorHistory = MutableStateFlow<List<BehaviorChange>>(emptyList())
    val behaviorHistory: StateFlow<List<BehaviorChange>> = _behaviorHistory.asStateFlow()

    private val _lastBehaviorChange = MutableStateFlow<BehaviorChange?>(null)
    /** The latest change seen live (from here, the Mac or the brain). */
    val lastBehaviorChange: StateFlow<BehaviorChange?> = _lastBehaviorChange.asStateFlow()

    suspend fun loadBehaviors() {
        quietly { c ->
            _behaviors.value = c.behaviors()
            _behaviorsSupported.value = true
            _behaviorHistory.value = c.behaviorHistory(30)
        }
    }

    /** Runs a behavior change and reloads (the `behaviors` event reloads too, for changes made elsewhere). */
    suspend fun changeBehavior(block: suspend (TowerClient) -> BehaviorResult): Result<BehaviorResult> =
        act(block).onSuccess { r ->
            r.change?.let { _lastBehaviorChange.value = it }
            loadBehaviors()
        }

    // Questions

    private val _questions = MutableStateFlow<List<Question>>(emptyList())
    /** The open question sets (all of them: the UI shows those for the user, [Question.forUser]). */
    val questions: StateFlow<List<Question>> = _questions.asStateFlow()

    private val _questionsSupported = MutableStateFlow(false)
    val questionsSupported: StateFlow<Boolean> = _questionsSupported.asStateFlow()

    private val _questionPolicy = MutableStateFlow<String?>(null)
    /** `normal`, `keep-moving` or `ask-everything`; null until the tower says (or it has no questions). */
    val questionPolicy: StateFlow<String?> = _questionPolicy.asStateFlow()

    /**
     * What this phone did to a question the tower doesn't show yet (id to
     * status and until when): a `state` sent before the answer landed would
     * set it back to open, and the screen would offer Submit again.
     */
    private val localStatus = mutableMapOf<String, Pair<String, Long>>()

    private fun setQuestions(list: List<Question>) {
        val now = System.currentTimeMillis()
        localStatus.entries.removeAll { it.value.second < now }
        val shown = list.map { q -> localStatus[q.id]?.takeIf { q.status == "open" }?.let { q.copy(status = it.first) } ?: q }
        _questions.value = shown
        deriveDecisions()
    }

    suspend fun setQuestionPolicy(policy: String): Result<String> =
        act { it.setQuestionPolicy(policy) }.onSuccess { _questionPolicy.value = it }

    /** Answers a question set; the tower types it into the session's dialog. */
    suspend fun answerQuestion(id: String, answers: List<TabAnswer>): Result<Unit> {
        localStatus[id] = "answering" to Long.MAX_VALUE
        _questions.value = _questions.value.map { if (it.id == id) it.copy(status = "answering", error = null) else it }
        // A tower without the queue: the question's card there follows.
        deriveDecisions()
        return act { it.answerQuestion(id, answers) }.onSuccess {
            localStatus[id] = "answered" to System.currentTimeMillis() + ANSWERED_HOLD_MS
            _questions.value = _questions.value.map { if (it.id == id && it.status == "answering") it.copy(status = "answered") else it }
            deriveDecisions()
            linger()
        }.onFailure { e ->
            localStatus.remove(id)
            _questions.value = _questions.value.map { if (it.id == id) it.copy(status = "failed", error = describe(e)) else it }
            deriveDecisions()
        }
    }

    suspend fun dismissQuestion(id: String): Result<Unit> =
        act { it.dismissQuestion(id) }.onSuccess { setQuestions(_questions.value.filterNot { q -> q.id == id }) }

    private suspend fun loadQuestions() {
        quietly { c ->
            val list = c.questions()
            _questionsSupported.value = true
            setQuestions(list)
            _questionPolicy.value = c.questionPolicy()
        }
    }

    // PRs the sessions opened

    private val _prs = MutableStateFlow<Map<String, Pr>>(emptyMap())
    /** The PRs the tower's sessions opened, by key (`owner/repo#n`), each as last heard: from the list, the chat and the `pr` events. */
    val prs: StateFlow<Map<String, Pr>> = _prs.asStateFlow()

    private val _prsSupported = MutableStateFlow(false)
    /** Whether the tower tracks PRs (`/v1/prs`). */
    val prsSupported: StateFlow<Boolean> = _prsSupported.asStateFlow()

    private fun setPrs(more: List<Pr>) {
        val next = PrsApi.merge(_prs.value, more)
        if (next == _prs.value) return
        _prs.value = next
        deriveDecisions()
    }

    private suspend fun loadPrs() {
        val c = client ?: return
        try {
            setPrs(c.prs())
            _prsSupported.value = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: TowerApiException) {
            // A tower from before PRs: no chips, no merging.
            if (e.status == 404) _prsSupported.value = false
        } catch (e: UnauthorizedException) {
            block(Link.Revoked(e.message))
        } catch (e: Exception) {
            Log.i(TAG, "prs: ${e.javaClass.simpleName}")
        }
    }

    /** Step one of merging [ref]: what it would do now, and the token for [merge]. The PR's copy here is updated too. */
    suspend fun confirmMerge(ref: PrRef): Result<MergeConfirm> =
        act { it.confirmMerge(ref) }.onSuccess { if (it.pr.id.isNotBlank()) setPrs(listOf(it.pr)) }

    /** Step two: merges with the token from [confirmMerge]; [override] after the user's second yes to failing checks. */
    suspend fun merge(ref: PrRef, token: String, override: Boolean = false): Result<MergeResult> =
        act { it.merge(ref, token, override) }.onSuccess { r -> r.pr?.let { setPrs(listOf(it)) } }

    // Reading

    private fun refreshAll() {
        scope.launch { loadThread() }
        scope.launch { loadPrs() }
        scope.launch { loadQuestions() }
        scope.launch { loadDecisions() }
        scope.launch { loadMissions() }
        scope.launch {
            if (_missionsSupported.value == true) quietly { c ->
                val t = c.tell()
                _tell.value = t.level
                t.voice?.let { _tellVoice.value = it }
            }
        }
        scope.launch { loadModel() }
        scope.launch { loadBehaviors() }
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
        // The queue and the missions are pushed, and read again here all the same: a missed event mustn't leave a
        // decision unseen, and what a failed first look left unknown is asked again.
        val d = async { if (_missionsSupported.value != false) loadMissions() }
        val e = async { if (_decisionsSupported.value != false) loadDecisions() }
        a.await()
        b.await()
        c.await()
        d.await()
        e.await()
        // A tower from before missions: they follow its spaces and jobs.
        deriveMissions()
        deriveDecisions()
    }

    suspend fun refreshAgents(all: Boolean = false) {
        quietly {
            val r = it.agents(all)
            _agents.value = r.agents
            _unreachable.value = r.unreachable
        }
    }

    /** The latest page of the thread (on every connect: what came while away is in it). */
    private suspend fun loadThread() {
        val c = client ?: return
        try {
            val page = c.chat(limit = 60, main = _missionsSupported.value == true)
            _threadSupported.value = true
            setPrs(page.messages.mapNotNull(PrsApi::of))
            page.messages.mapNotNull { it.act }.forEach(::setAct)
            // The newest page joins what's loaded when they overlap; after a gap (or another tower) it replaces it.
            val joins = page.messages.isEmpty() || _thread.value.any { it.id == page.messages.first().id }
            _thread.value = if (joins) ChatApi.merge(_thread.value, page.messages) else ChatApi.merge(emptyList(), page.messages)
            if (_thread.value.size == page.messages.size) _threadMore.value = page.more ?: (page.messages.size >= 60)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TowerApiException) {
            // A tower from before the thread: the chat falls back to the history.
            if (e.status == 404) _threadSupported.value = false
        } catch (e: Exception) {
            Log.i(TAG, "thread: ${e.javaClass.simpleName}")
        }
    }

    /** Pages the thread back: the messages before the oldest loaded. Returns how many came. */
    suspend fun loadOlderThread(limit: Int = 50): Int {
        val oldest = _thread.value.firstOrNull() ?: return 0
        var n = 0
        quietly { c ->
            val page = c.chat(before = oldest.id.ifBlank { oldest.at.toString() }, limit = limit, main = _missionsSupported.value == true)
            n = page.messages.size
            _thread.value = ChatApi.merge(_thread.value, page.messages)
            setPrs(page.messages.mapNotNull(PrsApi::of))
            _threadMore.value = page.more ?: (n >= limit)
        }
        return n
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
            block(Link.Revoked(e.message))
        } catch (e: SSLMismatch) {
            block(Link.CertificateChanged(e.mismatch.got))
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
        player.newTurn()
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
            block(Link.Revoked(e.message))
            Result.failure(e)
        } catch (e: SSLMismatch) {
            block(Link.CertificateChanged(e.mismatch.got))
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Stop: quiet here at once (no waiting for the network), the rest of this
     * turn's clips ignored, and the tower told to stop and drop what's queued,
     * which also silences the Mac if it's the one speaking.
     */
    fun hush() {
        player.stop()
        // Several ways to stop can fire for one press (the button, the volume keys): tell the Mac once.
        val now = System.currentTimeMillis()
        if (now - lastHush < 500) return
        lastHush = now
        scope.launch { act { it.hush() } }
    }
    private var lastHush = 0L

    private val _muted = MutableStateFlow(false)
    /** The tower-wide mute (`tower mute`), as the tower last said: it says nothing on the Mac or any phone. */
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    private val _muting = MutableStateFlow(false)
    /** A mute or unmute this phone asked for is on its way. */
    val muting: StateFlow<Boolean> = _muting.asStateFlow()
    /** Until then, a `state` that disagrees with the mute just set is one sent before it. */
    private var muteHeldUntil = 0L

    private fun mutedIs(on: Boolean) {
        _muted.value = on
        // The tower sends nothing to say while muted; this keeps a clip already on its way quiet too.
        player.silenced = on
        // Quiet now, without arming the "rest of this reply" drop: unmuted, the next reply plays.
        if (on) player.hush() else player.newTurn()
    }

    /** A tap on the orb: mutes the whole tower (it stops talking now), or lets it talk again. Ignored while one is on its way. */
    suspend fun setMuted(on: Boolean): Result<Unit> {
        if (_muting.value) return Result.success(Unit)
        _muting.value = true
        return try {
            act { it.setMuted(on) }.map { r ->
                muteHeldUntil = System.currentTimeMillis() + MUTE_HOLD_MS
                _state.value = _state.value?.copy(muted = r.muted)
                mutedIs(r.muted)
            }
        } finally {
            _muting.value = false
        }
    }

    private val _hushWord = MutableStateFlow(settings.hushWord)
    /** Whether saying "stop" while it talks on the phone stops it. */
    val hushWord: StateFlow<Boolean> = _hushWord.asStateFlow()

    fun setHushWord(on: Boolean) {
        settings.hushWord = on
        _hushWord.value = on
    }

    // Pairing

    /**
     * Pairs with the code shown on the Mac, pinning [endpoint]'s fingerprint.
     * The token is stored encrypted and only here.
     */
    suspend fun pair(endpoint: Endpoint, code: String, name: String): Result<Credentials> =
        pair(listOf(TowerHost(Hosts.kindOf(endpoint.host), endpoint.host, endpoint.port)), null, endpoint.fingerprint, code, name)

    /**
     * Pairs over the first of [hosts] that answers (in order: the LAN, then
     * Tailscale), pinning [fingerprint] on each, and keeps every address and
     * [magicDns] for connecting later.
     */
    suspend fun pair(hosts: List<TowerHost>, magicDns: String?, fingerprint: String, code: String, name: String): Result<Credentials> = try {
        require(hosts.isNotEmpty()) { "No address to pair with." }
        val tries = Hosts.candidates(hosts, magicDns, null)
        var answered: Pair<TowerHost, dev.herdertower.phone.api.PairResponse>? = null
        var lastError: Exception? = null
        for ((i, h) in tries.withIndex()) {
            val last = i == tries.lastIndex
            try {
                val r = TowerClient(Endpoint(h.host, h.port, fingerprint), { null }, connectTimeoutMs = if (last) 5_000 else PROBE_CONNECT_MS).pair(code, name)
                answered = h to r
                break
            } catch (e: UnreachableException) {
                lastError = e // not there from here: the next address
            } catch (e: SSLMismatch) {
                lastError = e // another machine at this address
            }
        }
        val (via, r) = answered ?: throw (lastError ?: UnreachableException("Can't reach the tower.", null))
        val fp = Fingerprint.normalize(r.fingerprint)
        if (fp != null && !Fingerprint.matches(fp, fingerprint)) {
            throw SSLMismatch(CertificateMismatchException(fingerprint, fp))
        }
        val first = hosts.first()
        val c = Credentials(first.host, first.port, fingerprint, r.token, r.device.id, r.device.name.ifBlank { name }, hosts, magicDns)
        val before = _creds.value
        store.save(c)
        client = clientAt(c, via)
        val sameTower = before != null && before.fingerprint == c.fingerprint && before.hosts.any { b -> c.hosts.any { it.id == b.id } }
        // The old pairing's token is revoked on its tower (best effort, it may be off): another tower's always,
        // the same tower's when this pairing is a new device there (pairing again over a revoked token is a no-op).
        if (before != null && before.token != c.token && (!sameTower || before.deviceId != c.deviceId)) {
            scope.launch {
                withTimeoutOrNull(10_000) {
                    runCatching { TowerClient(before.endpoint, { before.token }).unpair() }
                        .onFailure { Log.i(TAG, "old pairing not revoked: ${it.javaClass.simpleName}") }
                }
            }
        }
        // Another tower (or the same one again): nothing from before carries over.
        forgetTower(sameTower = sameTower)
        settings.lastRoute = via.id
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
        forgetTower(sameTower = false)
    }

    /** Drops everything learnt from the tower (on unpairing, or pairing again); [sameTower] keeps its name. */
    private fun forgetTower(sameTower: Boolean) {
        _blocked.value = null
        _state.value = null
        _history.value = emptyList()
        _spaces.value = SpacesResponse()
        _jobs.value = emptyList()
        _agents.value = emptyList()
        _unreachable.value = emptyList()
        _screenshots.value = emptyList()
        _thread.value = emptyList()
        _threadSupported.value = null
        _threadMore.value = true
        _accounts.value = AccountsResponse()
        _behaviors.value = null
        _behaviorsSupported.value = false
        _behaviorHistory.value = emptyList()
        _lastBehaviorChange.value = null
        localStatus.clear()
        _questions.value = emptyList()
        _questionsSupported.value = false
        _questionPolicy.value = null
        _prs.value = emptyMap()
        _prsSupported.value = false
        decisionHold.clear()
        decisionErrors.clear()
        queue = emptyList()
        _decisions.value = emptyList()
        _decisionsSupported.value = null
        decisionNotices.forget()
        _missions.value = MissionsResponse()
        _missionsSupported.value = null
        _tell.value = null
        _model.value = null
        _actions.value = emptyMap()
        _acts.value = emptyMap()
        _actsSupported.value = false
        _ruleOffers.value = emptyList()
        _tellVoice.value = null
        _spaceThreads.value = emptyMap()
        _spaceChats.value = emptyMap()
        images.clear() // another tower's pictures could share an id
        lastClip = null
        _route.value = null
        settings.audioRoute = null
        muteHeldUntil = 0
        mutedIs(false)
        _activeRoute.value = null
        _routeTries.value = emptyList()
        settings.lastRoute = null
        if (!sameTower) renameTower("")
    }

    companion object {
        /** Trying one address of several: a LAN that isn't there gives up in this long, then Tailscale is tried. */
        const val PROBE_CONNECT_MS = 2_500L
        /** And the whole look at one address (connect, TLS, hello). */
        const val PROBE_TOTAL_MS = 4_000L
        /** A network change comes as several callbacks within a moment: act once they've settled. */
        const val NET_SETTLE_MS = 800L
        private const val TAG = "TowerSession"
        /** Seconds between reconnects, as the contract asks: 1, 2, 5, 10, then every 10. */
        val BACKOFF = intArrayOf(1, 2, 5, 10)
        /** Missed clips younger than this are played on reconnect. */
        const val RECENT_MS = 60_000L
        /** How long the stream stays open after asking, with the app in the background. */
        const val LINGER_MS = 120_000L
        /** How long a `state` that disagrees with a mute just set is taken as stale. */
        private const val MUTE_HOLD_MS = 3_000L
        /** After a read of the queue fails: how long before each further try (the last, negative: give up until the next refresh). */
        private val RELOAD_WAITS_MS = longArrayOf(2_000, 5_000, -1)
        /** How long an answer sent from here shows as answered while the tower still says open. */
        private const val ANSWERED_HOLD_MS = 5_000L
        /** How often an action is asked about while it runs, and for how long at most. */
        private const val ACTION_POLL_MS = 2_000L
        private const val ACTION_WATCH_MS = 15 * 60_000L

        /** The stream to keep open: the voice whenever this phone is it (on screen or not), else the feed while on screen or lingering. */
        fun streamMode(voice: Boolean, foreground: Boolean, lingering: Boolean): StreamMode = when {
            voice -> StreamMode.Voice
            foreground || lingering -> StreamMode.Panel
            else -> StreamMode.Idle
        }

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
                "pairing_closed" -> "No pairing code is open on the Mac. Run “tower phone pair” there."
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

/** An answer came too late: the decision is over already (answered on another screen or in its session, dismissed, or its sessions gone). */
class DecisionOver(message: String) : Exception(message)

/** A close the tower held back: these sessions work, or wait for the user. Closing anyway cuts their work in progress off. */
class SpaceBusy(val sessions: List<BusySession>, message: String) : Exception(message)

/** An undo the tower held back: these sessions would lose work. Undoing anyway ends them all the same (a worktree with changes is still kept). */
class ActHeld(val sessions: List<HeldSession>, message: String) : Exception(message)
