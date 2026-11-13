package dev.herdertower.phone.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.herdertower.phone.data.Link
import dev.herdertower.phone.data.TowerSession
import dev.herdertower.phone.tower
import dev.herdertower.phone.voice.TalkTarget
import dev.herdertower.phone.voice.VoiceController
import kotlinx.coroutines.launch

/** Shows a short message at the bottom (errors from actions). */
val LocalSnack = staticCompositionLocalOf<(String) -> Unit> { {} }

/**
 * Where the user is. Four places are the tabs (the queue, the spaces, the
 * record, the shots); a space, a session, a new space and the plan open from Spaces;
 * the plan opens from Spaces too; the rest opens over whatever is in front.
 */
sealed interface Route {
    data object Queue : Route
    data object Spaces : Route
    data object Record : Route
    /** The shots: all (the tab), or one [session]'s; [open] shows that one in the viewer. */
    data class Shots(val session: String? = null, val open: String? = null) : Route
    /** A space's own tower. */
    data class Space(val name: String) : Route
    data class Session(val address: String) : Route
    data object NewSpace : Route
    /** The plan that is open: its status, link and predicted PRs. */
    data object Plan : Route
    data object Settings : Route
    /** The whole conversation with the main tower, or with [space]'s own. */
    data class Chat(val space: String? = null) : Route
    /** Answering a question set a session asks, step by step. */
    data class Question(val id: String) : Route
    /** How the tower works. */
    data object Behaviors : Route
    /** One behavior, or a new one ([id] null). */
    data class Behavior(val id: String?) : Route

    /** The tab a route belongs to, or null for one that opens over any of them. */
    val tab: Tab?
        get() = when (this) {
            Queue -> Tab.Queue
            Spaces, is Space, is Session, NewSpace, Plan -> Tab.Spaces
            Record -> Tab.Record
            is Shots -> Tab.Shots
            Settings, is Chat, is Question, Behaviors, is Behavior -> null
        }

    /** One of the four places themselves. */
    val isTab: Boolean get() = this == Queue || this == Spaces || this == Record || this == Shots()

    /** Whether the talk bar and the tabs show under it; not where a screen has its own input and fills the phone. */
    val bars: Boolean get() = this !is Chat && this !is Question && this !is Behaviors && this !is Behavior

    companion object {
        fun of(tab: Tab): Route = when (tab) {
            Tab.Queue -> Queue
            Tab.Spaces -> Spaces
            Tab.Record -> Record
            Tab.Shots -> Shots()
        }

        fun encode(r: Route): String = when (r) {
            Queue -> "queue"
            Spaces -> "spaces"
            Record -> "record"
            // \u0002 between the two: a session's address or a file name may hold a colon.
            is Shots -> "shots:${r.session.orEmpty()}\u0002${r.open.orEmpty()}"
            is Space -> "space:${r.name}"
            is Session -> "session:${r.address}"
            NewSpace -> "new"
            Plan -> "plan"
            Settings -> "settings"
            is Chat -> if (r.space == null) "chat" else "chat:${r.space}"
            is Question -> "question:${r.id}"
            Behaviors -> "behaviors"
            is Behavior -> "behavior:${r.id.orEmpty()}"
        }

        fun decode(s: String): Route = when {
            s == "spaces" -> Spaces
            s == "record" -> Record
            s == "new" -> NewSpace
            s == "plan" -> Plan
            s == "settings" -> Settings
            s == "chat" -> Chat()
            s.startsWith("chat:") -> Chat(s.removePrefix("chat:"))
            s.startsWith("session:") -> Session(s.removePrefix("session:"))
            s.startsWith("space:") -> Space(s.removePrefix("space:"))
            s.startsWith("question:") -> Question(s.removePrefix("question:"))
            s == "behaviors" -> Behaviors
            s.startsWith("behavior:") -> Behavior(s.removePrefix("behavior:").ifEmpty { null })
            s.startsWith("shots:") -> s.removePrefix("shots:").let { rest -> if ('\u0002' in rest) rest.split('\u0002', limit = 2) else rest.split(":", limit = 2) }
                .let { Shots(it[0].ifEmpty { null }, it.getOrNull(1)?.ifEmpty { null }) }
            // Anything else (a route of an older version too): the queue, where the app opens.
            else -> Queue
        }

        /** The stack after going to [r]: a tab starts over from it; anything else goes on top, once. */
        fun push(stack: List<Route>, r: Route): List<Route> =
            if (r.isTab) listOf(r) else stack.filterNot { it::class == r::class } + r

        /** The stack after Back: one screen less; from another tab's own screen, the queue. Null: leave the app. */
        fun pop(stack: List<Route>): List<Route>? = when {
            stack.size > 1 -> stack.dropLast(1)
            stack.lastOrNull() != Queue -> listOf(Queue)
            else -> null
        }

        /** The tab lit for [stack]: the top screen's, or for one that opens over anything the one under it. */
        fun tabOf(stack: List<Route>): Tab = stack.asReversed().firstNotNullOfOrNull { it.tab } ?: Tab.Queue

        val StackSaver = Saver<List<Route>, String>(
            save = { stack -> stack.joinToString("\u0001") { encode(it) } },
            restore = { s -> s.split("\u0001").map { decode(it) } },
        )
    }
}

/** Microphone (and, for the voice service, notifications): asks when needed, then runs [then]. */
class Permissions(private val context: Context, val ask: (Array<String>, () -> Unit) -> Unit) {
    fun has(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    val mic get() = has(Manifest.permission.RECORD_AUDIO)

    fun withMic(then: () -> Unit) = if (mic) then() else ask(arrayOf(Manifest.permission.RECORD_AUDIO)) { if (mic) then() }

    /** The app declares CAMERA (for the QR), so the system camera may only be used once it's granted. */
    fun withCamera(then: () -> Unit) = if (has(Manifest.permission.CAMERA)) then() else ask(arrayOf(Manifest.permission.CAMERA)) { if (has(Manifest.permission.CAMERA)) then() }

    fun forVoice(then: () -> Unit) {
        val want = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
        if (want.all(::has)) then() else ask(want) { then() }
    }
}

@Composable
fun rememberPermissions(): Permissions {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        pending?.invoke()
        pending = null
    }
    return remember(context) {
        Permissions(context) { perms, then ->
            pending = then
            launcher.launch(perms)
        }
    }
}

/**
 * The app once paired: the screen in front, and under it, on every screen,
 * the bar to talk or type to the tower and the four tabs. It opens on the
 * queue.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Shell(
    session: TowerSession, voice: VoiceController, theme: String, onTheme: (String) -> Unit, onRepair: () -> Unit,
    /** A decision whose card to open (its notification was tapped), and that it was. */
    openDecision: String? = null, onDecisionOpened: () -> Unit = {},
) {
    var stack by rememberSaveable(stateSaver = Route.StackSaver) { mutableStateOf(listOf<Route>(Route.Queue)) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val show: (String) -> Unit = { msg -> scope.launch { snack.showSnackbar(msg) } }
    val reduce = LocalReduceMotion.current
    val composer = LocalContext.current.tower.composer

    fun go(r: Route) {
        stack = Route.push(stack, r)
    }
    fun back() {
        Route.pop(stack)?.let { stack = it }
    }
    BackHandler(enabled = Route.pop(stack) != null) { back() }

    // From a decision's notification: the queue, with that card open.
    var focusDecision by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(openDecision) {
        if (openDecision != null) {
            focusDecision = openDecision
            go(Route.Queue)
            onDecisionOpened()
        }
    }

    val state by session.state.collectAsStateWithLifecycle()
    val link by session.link.collectAsStateWithLifecycle()
    val decisions by session.decisions.collectAsStateWithLifecycle()
    val missions by session.missions.collectAsStateWithLifecycle()
    val phase by voice.phase.collectAsStateWithLifecycle()
    val playing by session.player.nowPlaying.collectAsStateWithLifecycle()
    val connected = link is Link.Live
    val waiting = decisions.count { it.open }
    val orbPhase = LinkModel.orbPhase(connected, state?.phase, phase is VoiceController.Phase.Recording, phase is VoiceController.Phase.Sending, playing)

    // The orb: a tap mutes the whole tower (here and on the Mac), another lets it talk again.
    val muted by session.muted.collectAsStateWithLifecycle()
    val validation by session.validationSupported.collectAsStateWithLifecycle()
    val impact by session.impactSupported.collectAsStateWithLifecycle()
    val muting by session.muting.collectAsStateWithLifecycle()
    val mute = remember(muted, muting, connected) {
        MuteState(muted, enabled = connected && !muting) {
            val on = !muted
            scope.launch {
                session.setMuted(on)
                    .onSuccess { show(if (on) "Tower muted: it says nothing, here or on the Mac. Tap the orb to hear it again." else "Tower unmuted") }
                    .onFailure { show("Couldn't ${if (on) "mute" else "unmute"} the tower: ${TowerSession.describe(it)}") }
            }
        }
    }
    // Stop: this reply only, shown the moment the tower talks, here or on the Mac.
    val talking = connected && (playing != null || state?.speaking == true || state?.announcing == true)
    val stop = remember(talking) { StopState(talking) { session.hush() } }

    // Android 17+: no connection to the tower without the local network permission; asked as the app opens.
    rememberLocalNetwork(askNow = true) { session.reconnect() }

    // A decision can wait for you while the app is closed: on Android 13+ its notification needs asking once.
    val perms = rememberPermissions()
    LaunchedEffect(connected) {
        if (connected && Build.VERSION.SDK_INT >= 33 && !perms.has(Manifest.permission.POST_NOTIFICATIONS) && !session.settings.askedNotifications) {
            session.settings.askedNotifications = true
            perms.ask(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) {}
        }
    }

    val top = stack.last()
    // A new space's goal: kept here, so the talk button can write into it.
    var goal by rememberSaveable { mutableStateOf("") }
    val intoGoal = remember { TalkTarget.Into { said -> goal = if (goal.isBlank()) said else goal.trimEnd() + " " + said } }
    // Where words said or typed on this screen go: a space's screens send to that space's own tower.
    val target: TalkTarget = when (top) {
        is Route.Space -> TalkTarget.Space(top.name)
        is Route.Session -> missions.spaces.firstOrNull { m -> m.sessions.any { it.address == top.address } }?.let { TalkTarget.Space(it.name) } ?: TalkTarget.Main
        Route.NewSpace -> intoGoal
        else -> TalkTarget.Main
    }
    // A voice errand that went wrong says so wherever the user is.
    val voiceError by voice.error.collectAsStateWithLifecycle()
    LaunchedEffect(voiceError) {
        voiceError?.let {
            show(it)
            voice.clearError()
        }
    }

    CompositionLocalProvider(
        LocalSnack provides show, LocalMute provides mute, LocalStop provides stop,
        LocalOpenSettings provides { go(Route.Settings) }, LocalReach provides connected, LocalValidation provides validation, LocalImpact provides impact,
    ) {
        Backdrop(orbPhase, needsYou = connected && waiting > 0) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AnimatedContent(
                        targetState = top,
                        transitionSpec = {
                            if (reduce) EnterTransition.None togetherWith ExitTransition.None
                            else fadeIn(tween(220)) togetherWith fadeOut(tween(140))
                        },
                        contentKey = { Route.encode(it) },
                        label = "route",
                    ) { route ->
                        when (route) {
                            Route.Queue -> QueueScreen(
                                session, onQuestion = { go(Route.Question(it)) }, onSession = { go(Route.Session(it)) }, onChat = { go(Route.Chat()) }, onRepair = onRepair,
                                open = focusDecision, onOpened = { focusDecision = null },
                            )
                            Route.Spaces -> MissionsScreen(session, onSpace = { go(Route.Space(it)) }, onNew = { go(Route.NewSpace) }, onPlan = { go(Route.Plan) })
                            Route.Plan -> PlanScreen(session, onBack = { back() })
                            is Route.Space -> MissionScreen(
                                session, route.name, onBack = { back() }, onSession = { go(Route.Session(it)) }, onQuestion = { go(Route.Question(it)) },
                                onShot = { go(Route.Shots(open = it)) }, onChat = { go(Route.Chat(route.name)) },
                            )
                            is Route.Session -> SessionScreen(
                                session, route.address, onBack = { back() }, onShot = { go(Route.Shots(route.address, it)) },
                                onQuestion = { go(Route.Question(it)) }, onSession = { go(Route.Session(it)) },
                            )
                            Route.NewSpace -> NewSpaceScreen(
                                session, goal, { goal = it }, onBack = { back() },
                                onLaunched = { space -> stack = listOf(Route.Spaces, Route.Space(space)) },
                                onAsked = { go(Route.Queue) },
                            )
                            Route.Record -> RecordScreen(session, onSession = { go(Route.Session(it)) }, onShot = { go(Route.Shots(open = it)) }, onChat = { go(Route.Chat()) })
                            is Route.Shots -> ScreenshotsScreen(session, route.session, route.open, onBack = if (route.isTab) null else ({ back() }))
                            Route.Settings -> SettingsScreen(session, theme, onTheme, onBack = { back() }, onRepair = onRepair, onBehaviors = { go(Route.Behaviors) })
                            is Route.Chat -> ChatScreen(
                                session, onClose = { back() },
                                onVoice = {
                                    voice.target = route.space?.let { TalkTarget.Space(it) } ?: TalkTarget.Main
                                    voice.toggle()
                                },
                                space = route.space,
                                onOpen = { go(Route.Session(it)) }, onShot = { go(Route.Shots(open = it)) },
                                onBehavior = { title ->
                                    val b = session.behaviors.value?.behaviors?.let { all -> dev.herdertower.phone.api.BehaviorsApi.fromChatLine(title, all) }
                                    go(Route.Behaviors)
                                    if (b != null) go(Route.Behavior(b.id))
                                },
                            )
                            is Route.Question -> QuestionScreen(session, route.id, onClose = { back() }, onSession = { go(Route.Session(it)) })
                            Route.Behaviors -> BehaviorsScreen(session, onBack = { back() }, onOpen = { go(Route.Behavior(it)) }, onAdd = { go(Route.Behavior(null)) })
                            is Route.Behavior -> BehaviorScreen(session, route.id, onBack = { back() })
                        }
                    }
                    // While a take runs: what the Mac has written down so far, over whatever screen is in front.
                    ListeningOverlay(voice, orbPhase)
                    SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp))
                }
                if (top.bars) {
                    // Above the keyboard while typing (the tabs give way to it), above the tabs otherwise.
                    Column(Modifier.imePadding()) {
                        TalkBar(
                            session, voice, composer, target, input = top != Route.NewSpace,
                            // Sent to the main tower from another screen: the queue is where its reply shows.
                            onSent = { if (target == TalkTarget.Main && top != Route.Queue) go(Route.Queue) },
                        )
                        if (!WindowInsets.isImeVisible) BottomNav(Route.tabOf(stack), waiting) { go(Route.of(it)) }
                        else Spacer(Modifier.navigationBarsPadding())
                    }
                }
            }
        }
    }
}

/** Says clearly what's wrong with the connection, and what to do. */
@Composable
fun ConnectionNotice(link: Link, session: TowerSession, onRepair: () -> Unit, modifier: Modifier = Modifier) {
    // Without the local network permission (Android 17+) retrying can't help: say what's missing instead.
    val localNet = rememberLocalNetwork { session.reconnect() }
    if (localNet.needed) return LocalNetworkNotice(localNet, modifier)
    val notice = LinkModel.connectionNotice(link) ?: return
    val retry = notice.action == NoticeAction.Retry
    GlassCard(modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(if (retry) tc.accentHi else tc.blocked)
                Spacer(Modifier.size(10.dp))
                Text(notice.message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = tc.text)
                if (!notice.scan) TextButton(onClick = if (retry) session::reconnect else onRepair) {
                    Text(if (retry) "Retry now" else "Pair again", color = tc.text, fontWeight = FontWeight.Medium)
                }
            }
            // Not on any network the tower was seen on: what the search on this Wi-Fi did, then pair again here or try once more.
            val search by session.search.collectAsStateWithLifecycle()
            if (notice.scan) search?.let {
                Text("Looked for the Mac on this Wi-Fi at ${clock(it.atMs)}: ${it.note}.", Modifier.padding(start = 18.dp, top = 6.dp), style = MaterialTheme.typography.bodySmall, color = tc.muted)
            }
            if (notice.scan) Row(Modifier.padding(start = 18.dp, top = 6.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                PillButton("Scan the QR", primary = true, onClick = onRepair)
                PillButton("Retry now", onClick = session::reconnect)
            }
        }
    }
}
