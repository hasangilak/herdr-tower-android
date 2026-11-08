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
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Stop
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Workspaces
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.herdertower.phone.data.Link
import dev.herdertower.phone.data.TowerSession
import dev.herdertower.phone.voice.VoiceController
import dev.herdertower.phone.voice.VoiceService
import kotlinx.coroutines.launch

/** Shows a short message at the bottom (errors from actions). */
val LocalSnack = staticCompositionLocalOf<(String) -> Unit> { {} }

/** Where the user is: Home, Voice and Chat are the main flow; the rest open from the menu. */
sealed interface Route {
    data object Home : Route
    data class Voice(val listen: Boolean) : Route
    data object Chat : Route
    data object Spaces : Route
    data object Activity : Route
    data class Session(val address: String) : Route
    data class Space(val name: String) : Route
    /** Screenshots: all, or one [session]'s; [open] shows that one in the viewer. */
    data class Screenshots(val session: String? = null, val open: String? = null) : Route
    /** Answering a question a session asks. */
    data class Question(val id: String) : Route

    companion object {
        fun encode(r: Route): String = when (r) {
            Home -> "home"
            is Voice -> "voice:${r.listen}"
            Chat -> "chat"
            Spaces -> "spaces"
            Activity -> "activity"
            is Session -> "session:${r.address}"
            is Space -> "space:${r.name}"
            is Screenshots -> "shots:${r.session.orEmpty()}:${r.open.orEmpty()}"
            is Question -> "question:${r.id}"
        }

        fun decode(s: String): Route = when {
            s.startsWith("voice:") -> Voice(false) // don't start listening again after a restore
            s == "chat" -> Chat
            s == "spaces" -> Spaces
            s == "activity" -> Activity
            s.startsWith("session:") -> Session(s.removePrefix("session:"))
            s.startsWith("space:") -> Space(s.removePrefix("space:"))
            s.startsWith("question:") -> Question(s.removePrefix("question:"))
            s.startsWith("shots:") -> s.removePrefix("shots:").split(":", limit = 2).let { Screenshots(it[0].ifEmpty { null }, it.getOrNull(1)?.ifEmpty { null }) }
            else -> Home
        }

        val StackSaver = Saver<List<Route>, String>(
            save = { stack -> stack.joinToString("\u0001") { encode(it) } },
            restore = { s -> s.split("\u0001").map { decode(it) } },
        )
    }
}

/** Microphone (and, for the voice service, notifications): asks when needed, then runs [then]. */
class Permissions(private val context: Context, private val ask: (Array<String>, () -> Unit) -> Unit) {
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

/** Where replies are heard, in words: for the status lines. */
fun routeWords(route: String?, connected: Boolean): String = when {
    route == "mac" -> "replies play on the Mac"
    !connected -> "not connected: replies to you wait on the Mac for 15 minutes"
    else -> "replies play on this phone"
}

/** The replies switch: this phone or the Mac. */
@Composable
fun RouteToggle(session: TowerSession, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 40.dp) {
    val route by session.audioRoute.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    val onMac = route == "mac"
    RoundIcon(
        if (onMac) androidx.compose.material.icons.Icons.Filled.Computer else androidx.compose.material.icons.Icons.AutoMirrored.Filled.VolumeUp,
        if (onMac) "Replies play on the Mac. Tap to hear them on this phone." else "Replies play on this phone. Tap to hear them on the Mac.",
        modifier, size = size,
    ) {
        scope.launch {
            val to = if (onMac) "phone" else "mac"
            session.setAudioRoute(to)
                .onSuccess { snack(if (to == "mac") "Replies play on the Mac" else "Replies play on this phone") }
                .onFailure { snack("Couldn't switch: ${TowerSession.describe(it)}") }
        }
    }
}

/** Turns "the phone is the voice" on (with its permissions and service) or off. */
fun setVoice(on: Boolean, session: TowerSession, perms: Permissions, context: Context) {
    if (!on) return session.setVoice(false)
    perms.forVoice {
        session.setVoice(true)
        VoiceService.start(context)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Shell(session: TowerSession, voice: VoiceController, theme: String, onTheme: (String) -> Unit, onRepair: () -> Unit, openQuestion: String? = null, onQuestionOpened: () -> Unit = {}) {
    var stack by rememberSaveable(stateSaver = Route.StackSaver) { mutableStateOf(listOf<Route>(Route.Home)) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val show: (String) -> Unit = { msg -> scope.launch { snack.showSnackbar(msg) } }
    var menu by remember { mutableStateOf(false) }
    val reduce = LocalReduceMotion.current

    fun go(r: Route) {
        stack = if (r == Route.Home) listOf(Route.Home) else stack.filterNot { it::class == r::class } + r
    }
    fun back() {
        if (stack.size > 1) stack = stack.dropLast(1)
    }
    BackHandler(enabled = stack.size > 1) { back() }
    // From the notification: straight to that question.
    LaunchedEffect(openQuestion) {
        if (openQuestion != null) {
            go(Route.Question(openQuestion))
            onQuestionOpened()
        }
    }

    val state by session.state.collectAsStateWithLifecycle()
    val link by session.link.collectAsStateWithLifecycle()
    val jobs by session.jobs.collectAsStateWithLifecycle()
    val phase by voice.phase.collectAsStateWithLifecycle()
    val playing by session.player.nowPlaying.collectAsStateWithLifecycle()
    val connected = link is Link.Live
    val blocked = HomeModel.blocked(state?.agents.orEmpty(), jobs).size
    val orbPhase = HomeModel.orbPhase(connected, state?.phase, phase is VoiceController.Phase.Recording, phase is VoiceController.Phase.Sending, playing)
    val needsYou = HomeModel.needsYou(connected, state?.phase, blocked)

    CompositionLocalProvider(LocalSnack provides show) {
        Backdrop(orbPhase, needsYou) {
            AnimatedContent(
                targetState = stack.last(),
                transitionSpec = {
                    if (reduce) EnterTransition.None togetherWith ExitTransition.None
                    else (fadeIn(tween(520)) + scaleIn(tween(520), initialScale = 0.985f)) togetherWith fadeOut(tween(320))
                },
                contentKey = { Route.encode(it) },
                label = "route",
            ) { route ->
                when (route) {
                    Route.Home -> HomeScreen(session, voice, onVoice = { go(Route.Voice(it)) }, onChat = { go(Route.Chat) }, onOpen = { go(Route.Session(it)) }, onAlerts = { go(Route.Activity) }, onMenu = { menu = true }, onRepair = onRepair, onQuestion = { go(Route.Question(it)) })
                    is Route.Voice -> VoiceScreen(session, voice, startListening = route.listen, onClose = { back() }, onChat = { go(Route.Chat) })
                    Route.Chat -> ChatScreen(session, onClose = { back() }, onVoice = { go(Route.Voice(true)) }, onOpen = { go(Route.Session(it)) }, onShot = { go(Route.Screenshots(open = it)) }, onQuestion = { go(Route.Question(it)) })
                    Route.Spaces -> SpacesScreen(session, onBack = { back() }, onOpen = { go(Route.Session(it)) }, onSpace = { go(Route.Space(it)) })
                    Route.Activity -> ActivityScreen(session, onBack = { back() }, onOpen = { go(Route.Session(it)) }, onShot = { go(Route.Screenshots(open = it)) })
                    is Route.Space -> SpaceScreen(session, route.name, onBack = { back() }, onOpen = { go(Route.Session(it)) }, onShot = { go(Route.Screenshots(open = it)) })
                    is Route.Session -> SessionScreen(session, route.address, onBack = { back() }, onShot = { go(Route.Screenshots(route.address, it)) }, onQuestion = { go(Route.Question(it)) })
                    is Route.Question -> QuestionScreen(session, route.id, onClose = { back() }, onSession = { go(Route.Session(it)) })
                    is Route.Screenshots -> ScreenshotsScreen(session, route.session, route.open, onBack = { back() })
                }
            }
            // The moment the tower speaks (here or on the Mac), one big Stop on every screen.
            StopButton(
                visible = connected && (playing != null || state?.speaking == true || state?.announcing == true),
                onStop = { session.hush() },
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(bottom = 92.dp),
            )
            SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp))
        }
        if (menu) {
            MenuSheet(session, theme, onTheme, onDismiss = { menu = false }, onGo = { menu = false; go(it) }, onRepair = { menu = false; onRepair() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MenuSheet(session: TowerSession, theme: String, onTheme: (String) -> Unit, onDismiss: () -> Unit, onGo: (Route) -> Unit, onRepair: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    val context = LocalContext.current
    val perms = rememberPermissions()
    val state by session.state.collectAsStateWithLifecycle()
    val spaces by session.spaces.collectAsStateWithLifecycle()
    val jobs by session.jobs.collectAsStateWithLifecycle()
    val voiceOn by session.voiceOn.collectAsStateWithLifecycle()
    val creds by session.credentials.collectAsStateWithLifecycle()
    val name by session.towerName.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf(false) }
    var confirmUnpair by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = if (tc.dark) androidx.compose.ui.graphics.Color(0xFF17110E) else androidx.compose.ui.graphics.Color(0xFFFFF8F1)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val held = spaces.spaces.sumOf { it.unread }
            val running = jobs.count { it.open }
            SheetRow(Icons.Filled.Workspaces, "Spaces", if (held > 0) "$held new" else plural(spaces.spaces.size, "space")) { onGo(Route.Spaces) }
            SheetRow(Icons.Filled.Timeline, "Activity", if (running > 0) "$running running" else "Jobs and history") { onGo(Route.Activity) }
            SheetRow(Icons.Filled.Forum, "Conversation", "Everything you and the tower said") { onGo(Route.Chat) }
            val shots by session.screenshots.collectAsStateWithLifecycle()
            SheetRow(Icons.Filled.PhotoLibrary, "Screenshots", if (shots.isEmpty()) "What sessions show you" else plural(shots.size, "screenshot")) { onGo(Route.Screenshots()) }
            Spacer(Modifier.height(10.dp))
            val route by session.audioRoute.collectAsStateWithLifecycle()
            Text("Replies to what you send", style = MaterialTheme.typography.labelLarge, color = tc.faint)
            Segments(Modifier.fillMaxWidth()) {
                listOf("phone" to "This phone", "mac" to "The Mac").forEach { (r, label) ->
                    Segment(label, (route ?: "phone") == r) {
                        scope.launch { session.setAudioRoute(r).onFailure { e -> snack("Couldn't switch: ${TowerSession.describe(e)}") } }
                    }
                }
            }
            Text(
                if (route == "mac") "The Mac speaks; the phone shows the text." else "Heard here while the app is open (or for 2 minutes after you send); the Mac stays silent for them.",
                style = MaterialTheme.typography.bodySmall, color = tc.muted,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Phone is the voice", style = MaterialTheme.typography.titleMedium, color = tc.text)
                    Text(
                        if (voiceOn) "Everything the tower says, alerts too, plays here, also with the screen off. The Mac stays silent."
                        else "Off: alerts and the Mac's own turns are spoken on the Mac.",
                        style = MaterialTheme.typography.bodySmall, color = tc.muted,
                    )
                }
                Switch(voiceOn, { setVoice(it, session, perms, context) }, colors = SwitchDefaults.colors(checkedTrackColor = tc.accent, checkedThumbColor = tc.onAccent))
            }
            // Phones (Samsung especially) stop background services to save battery: offer the exemption.
            val power = context.getSystemService(android.os.PowerManager::class.java)
            if (voiceOn && power != null && !power.isIgnoringBatteryOptimizations(context.packageName)) {
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                .setData(android.net.Uri.parse("package:" + context.packageName)),
                        )
                    }
                }) { Text("Keep the voice running in the background (battery setting)", color = tc.text) }
            }
            if (voiceOn) {
                val hushWord by session.hushWord.collectAsStateWithLifecycle()
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Say \"stop\" to stop", style = MaterialTheme.typography.titleMedium, color = tc.text)
                        Text(
                            "While it talks here, the phone listens for stop, hush or quiet.",
                            style = MaterialTheme.typography.bodySmall, color = tc.muted,
                        )
                    }
                    Switch(hushWord, { on -> if (on) perms.withMic { session.setHushWord(true) } else session.setHushWord(false) }, colors = SwitchDefaults.colors(checkedTrackColor = tc.accent, checkedThumbColor = tc.onAccent))
                }
            }
            val qOn by session.questionsSupported.collectAsStateWithLifecycle()
            val policy by session.questionPolicy.collectAsStateWithLifecycle()
            if (qOn) {
                Spacer(Modifier.height(10.dp))
                Text("Questions sessions ask", style = MaterialTheme.typography.labelLarge, color = tc.faint)
                Segments(Modifier.fillMaxWidth()) {
                    listOf("normal" to "Normal", "keep-moving" to "Keep moving", "ask-everything" to "Ask me all").forEach { (p, label) ->
                        Segment(label, (policy ?: "normal") == p) {
                            scope.launch { session.setQuestionPolicy(p).onFailure { snack(TowerSession.describe(it)) } }
                        }
                    }
                }
                Text(
                    when (policy ?: "normal") {
                        "keep-moving" -> "The tower answers borderline ones too; deploys, pushes, deleting and money still come to you."
                        "ask-everything" -> "Nothing is answered for you: every question comes here."
                        else -> "The tower answers routine ones (a quiet line in the chat) and leaves the rest to you."
                    },
                    style = MaterialTheme.typography.bodySmall, color = tc.muted,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text("Mac microphone", style = MaterialTheme.typography.labelLarge, color = tc.faint)
            Segments(Modifier.fillMaxWidth()) {
                listOf("ptt" to "Press to talk", "handsfree" to "Hands-free", "off" to "Off").forEach { (m, label) ->
                    Segment(label, state?.mode == m) {
                        scope.launch { session.act { it.mode(m) }.onFailure { e -> snack(TowerSession.describe(e)) } }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("Look", style = MaterialTheme.typography.labelLarge, color = tc.faint)
            Segments(Modifier.fillMaxWidth()) {
                listOf("system" to "Auto", "dark" to "Dark", "light" to "Light").forEach { (t, label) -> Segment(label, theme == t) { onTheme(t) } }
            }
            Text("Orb drawing", style = MaterialTheme.typography.labelLarge, color = tc.faint)
            var orbChoice by remember { mutableStateOf(session.settings.orbTier) }
            Segments(Modifier.fillMaxWidth()) {
                listOf("auto" to "Auto", "Full" to "Full", "Analytic" to "Simple", "Gradient" to "Gradient").forEach { (k, label) ->
                    Segment(label, orbChoice == k) {
                        orbChoice = k
                        session.settings.orbTier = k
                        dev.herdertower.phone.orb.OrbProbe.override = dev.herdertower.phone.orb.OrbTier.entries.firstOrNull { it.name == k }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = { renaming = true }) { Text("Name this tower (${name.ifBlank { "not set" }})", color = tc.text) }
            TextButton(onClick = onRepair) { Text("Pair again…", color = tc.text) }
            TextButton(onClick = { confirmUnpair = true }) { Text("Unpair this phone", color = tc.failed) }
            creds?.let { Text("${it.deviceName} · ${it.host}:${it.port}", style = MaterialTheme.typography.labelSmall, color = tc.faint) }
            // What's under the hood on this phone (read it out when something looks wrong).
            val orb by dev.herdertower.phone.orb.OrbProbe.report.collectAsStateWithLifecycle()
            val frameMs by dev.herdertower.phone.orb.OrbProbe.frameMs.collectAsStateWithLifecycle()
            val drawnSteps by dev.herdertower.phone.orb.OrbProbe.drawnSteps.collectAsStateWithLifecycle()
            val playback by session.player.stats.collectAsStateWithLifecycle()
            val hushOn by session.hushWord.collectAsStateWithLifecycle()
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(
                    "Diagnostics: " + (orb?.line(frameMs, drawnSteps) ?: "probing the orb…") +
                        "\n" + playback.line() + " · say-stop ${if (hushOn && voiceOn) "on" else "off"}",
                    Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelSmall, color = tc.faint,
                )
            }
        }
    }
    if (renaming) {
        var text by remember { mutableStateOf(name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Name this tower") },
            text = { OutlinedTextField(text, { text = it.take(40) }, singleLine = true, label = { Text("The Mac's name") }) },
            confirmButton = { TextButton(onClick = { session.renameTower(text); renaming = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text("Unpair this phone?") },
            text = { Text("The tower revokes this phone's token, and the app forgets it. You can pair again with `tower phone pair`.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnpair = false
                    onDismiss()
                    scope.launch { session.unpair(tellTower = true) }
                }) { Text("Unpair", color = tc.failed) }
            },
            dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SheetRow(icon: ImageVector, title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Icon(icon, null, tint = tc.accentHi.takeIf { tc.dark } ?: tc.accent) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = tc.text, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = tc.muted)
        }
        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = tc.faint)
    }
}

/** Says clearly what's wrong with the connection, and what to do. */
@Composable
fun ConnectionNotice(link: Link, session: TowerSession, onRepair: () -> Unit, modifier: Modifier = Modifier) {
    val (msg, action) = when (link) {
        is Link.Retrying -> "${link.reason} Trying again in ${link.seconds} s." to ("Retry now" to { session.reconnect() })
        is Link.Revoked -> "This phone isn't paired any more. Run `tower phone pair` on the Mac and pair again." to ("Pair again" to onRepair)
        is Link.CertificateChanged -> "The tower's certificate isn't the one this phone paired with. If you remade it, pair again; otherwise someone may be in between." to ("Pair again" to onRepair)
        else -> return
    }
    GlassCard(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(if (link is Link.Retrying) tc.accentHi else tc.blocked)
            Spacer(Modifier.size(10.dp))
            Text(msg, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = tc.text)
            TextButton(onClick = action.second) { Text(action.first, color = tc.text, fontWeight = FontWeight.Medium) }
        }
    }
}

/** Stops the tower talking: quiet here at once, the Mac told too. */
@Composable
fun StopButton(visible: Boolean, onStop: () -> Unit, modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (reduce) EnterTransition.None else fadeIn(tween(160)) + scaleIn(tween(160), initialScale = 0.85f),
        exit = if (reduce) ExitTransition.None else fadeOut(tween(220)),
    ) {
        Row(
            Modifier
                .shadow(12.dp, CircleShape)
                .clip(CircleShape)
                .background(tc.text)
                .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "Stop the tower talking", onClick = onStop)
                .heightIn(min = 56.dp)
                .padding(start = 18.dp, end = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Stop, null, Modifier.size(28.dp), tint = tc.bgTop)
            Spacer(Modifier.width(8.dp))
            Text("Stop", style = MaterialTheme.typography.titleMedium, color = tc.bgTop, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        }
    }
}
