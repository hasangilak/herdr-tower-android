package dev.herdertower.phone.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.herdertower.phone.data.Link
import dev.herdertower.phone.data.TowerSession
import dev.herdertower.phone.voice.VoiceService
import kotlinx.coroutines.launch

/** What the settings say about the choices, in words. */
object SettingsModel {
    /** Under "Notify me for": what the choice means. */
    fun notifyWords(level: String?): String = when (level) {
        "results" -> "A decision that is yours notifies. One line when something you asked for is finished shows on your queue and is spoken where replies are heard. Nothing else."
        "everything" -> "Everything, as before the quiet tower: every start, finish and message. Set on the Mac."
        null -> "A decision that is yours. This Mac's tower still tells everything by voice: the quiet levels come with its next update."
        else -> "A decision that is yours. Nothing else: no finishes, no deliveries, no messages between sessions."
    }

    /** Under "The tower's brains": what they run on, and what the user must know about a change. */
    fun modelWords(m: dev.herdertower.phone.api.BrainModel): List<String> = buildList {
        m.says.takeIf { it.isNotBlank() }?.let(::add)
        if (m.lockedByArgs) add(
            "The Mac's own claude_args set the " + listOfNotNull(m.locked.model?.let { "model" }, m.locked.effort?.let { "effort" }).ifEmpty { listOf("model") }.joinToString(" and ") +
                ", and that wins: the choice here is kept for when they don't.",
        )
        if (m.restartNeeded) add("Restarting starts each brain on a new conversation; for a few seconds a space's own tower is away.")
    }

    /** One network of the pairing, as Settings lists it. */
    data class NetworkRow(val title: String, val address: String, val status: String, val now: Boolean)

    /**
     * The pairing's networks: one row per stored address (its network's name,
     * or its range; Tailscale), and how it stands: reached now, when it last
     * answered, or that it didn't answer on the last try.
     */
    fun networks(
        hosts: List<dev.herdertower.phone.api.TowerHost>,
        magicDns: String?,
        active: dev.herdertower.phone.api.TowerHost?,
        tries: List<dev.herdertower.phone.data.RouteTry>,
        now: Long,
    ): List<NetworkRow> = hosts.map { h ->
        val title = when {
            h.tailscale -> "Tailscale"
            !h.seenName.isNullOrBlank() -> h.seenName!!
            h.range != null -> "The network ${h.range}.x"
            else -> "Wi-Fi"
        }
        val name = (h.dnsName ?: magicDns?.takeIf { h.tailscale })?.takeIf { it != h.host }
        val address = h.host + (if (h.port != dev.herdertower.phone.api.PairLink.DEFAULT_PORT) ":${h.port}" else "") + (name?.let { " · $it" } ?: "")
        val here = active != null && (active.id == h.id || (active.tailscale && h.tailscale))
        val failed = tries.lastOrNull { it.host.id == h.id }?.takeIf { it.outcome != dev.herdertower.phone.data.RouteOutcome.Answered }
        val status = when {
            here -> "connected now"
            failed != null -> "no answer here" + (h.lastOkMs?.let { " · last ${ago(it, now)}" } ?: "")
            h.lastOkMs != null -> "last answered ${ago(h.lastOkMs, now)}"
            else -> "from the pairing"
        }
        NetworkRow(title, address, status, here)
    }

    /** "on the Office network", "on Wi-Fi", "over Tailscale": which network the tower is reached on. */
    fun reachedOn(tailscale: Boolean, network: String?): String {
        val net = network?.takeIf { it.isNotBlank() }?.let { if (it.startsWith("the ")) it else "the $it network" }
        return when {
            tailscale -> "over Tailscale" + (net?.let { " (the Mac is on $it)" } ?: "")
            net != null -> "on $net"
            else -> "on Wi-Fi"
        }
    }

    /** Under "The tower says out loud". */
    fun voiceLevelWords(v: String): String = when (v) {
        "off" -> "Nothing, here or on the Mac: the tower is muted. It still writes everything down."
        "decisions" -> "A decision, once, in one sentence. No answers, no receipts out loud: they're on the screen."
        else -> "Its answers to what you ask, and a decision once, in one sentence."
    }

    /** Under "Replies are heard on". */
    fun routeWords(route: String?): String =
        if (route == "mac") "The Mac speaks the replies to what you send; the phone shows the text."
        else "Replies to what you send play here while the app is open (or for 2 minutes after you send); the Mac stays silent for them."

    /** "Phone is the voice" off or on. */
    fun voiceWords(on: Boolean): String =
        if (on) "Everything the tower says plays here, also with the screen off, and decisions notify at once. The Mac stays silent."
        else "Off: with the app closed the phone doesn't hear the Mac. It looks for a new decision every 15 minutes, so one notifies within that."
}

/** A section of the settings: a card with its heading. */
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(tc.card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium, color = tc.text, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun Words(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = tc.muted)

/**
 * Settings: this phone's own choices first (where replies are heard, what
 * notifies, the voice, the look, the pairing), then what is the tower's and
 * can be changed from here too.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(session: TowerSession, theme: String, onTheme: (String) -> Unit, onBack: () -> Unit, onRepair: () -> Unit, onBehaviors: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    val context = LocalContext.current
    val perms = rememberPermissions()
    val state by session.state.collectAsStateWithLifecycle()
    val voiceOn by session.voiceOn.collectAsStateWithLifecycle()
    val creds by session.credentials.collectAsStateWithLifecycle()
    val name by session.towerName.collectAsStateWithLifecycle()
    val route by session.audioRoute.collectAsStateWithLifecycle()
    val tell by session.tell.collectAsStateWithLifecycle()
    val link by session.link.collectAsStateWithLifecycle()
    val via by session.activeRoute.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf(false) }
    var renamingPhone by remember { mutableStateOf(false) }
    var confirmUnpair by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopBar("Settings", "This phone", onBack = onBack, settings = false)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Section("Replies are heard on") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("phone" to "Phone", "mac" to "Mac").forEach { (r, label) ->
                        Toggle(label, (route ?: "phone") == r) { scope.launch { session.setAudioRoute(r).onFailure { e -> snack("Couldn't switch: ${TowerSession.describe(e)}") } } }
                    }
                }
                Words(SettingsModel.routeWords(route))
            }

            Section("Notify me for") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val can = tell != null
                    Toggle("Decisions", tell == null || tell == "decisions", enabled = can) { scope.launch { session.setTell("decisions").onFailure { snack(TowerSession.describe(it)) } } }
                    Toggle("Decisions and results", tell == "results", enabled = can) { scope.launch { session.setTell("results").onFailure { snack(TowerSession.describe(it)) } } }
                    if (tell == "everything") Toggle("Everything", true) {}
                }
                Words(SettingsModel.notifyWords(tell))
                if (tell != null) Words("This is the tower's one setting for how much it tells you: it holds on the Mac too.")
                if (!dev.herdertower.phone.data.DecisionNotices.allowedOn(context)) TextButton(onClick = {
                    if (android.os.Build.VERSION.SDK_INT >= 33 && !perms.has(android.Manifest.permission.POST_NOTIFICATIONS)) perms.ask(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS)) {}
                    else openAppSettings(context)
                }) { Text("Notifications are off for this app: turn them on", color = tc.decisionInk) }
            }

            val tellVoice by session.tellVoice.collectAsStateWithLifecycle()
            tellVoice?.let { v ->
                Section("The tower says out loud") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("replies" to "Its replies", "decisions" to "Only decisions", "off" to "Nothing").forEach { (k, label) ->
                            Toggle(label, v == k) { scope.launch { session.setTellVoice(k).onFailure { snack(TowerSession.describe(it)) } } }
                        }
                    }
                    Words(SettingsModel.voiceLevelWords(v))
                }
            }

            Section("Phone is the voice") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Words(SettingsModel.voiceWords(voiceOn)) }
                    Switch(
                        voiceOn, { setVoice(it, session, perms, context) }, Modifier.padding(start = 12.dp).semantics { contentDescription = "Phone is the voice" },
                        colors = SwitchDefaults.colors(checkedTrackColor = tc.decision, checkedThumbColor = tc.onDecision),
                    )
                }
                // Phones (Samsung especially) stop background services to save battery: point to the exemption.
                // The app's own settings page (Battery, then Unrestricted), not the direct exemption request,
                // which needs a permission app stores only allow for a few kinds of app.
                val power = context.getSystemService(android.os.PowerManager::class.java)
                if (voiceOn && power != null && !power.isIgnoringBatteryOptimizations(context.packageName)) {
                    TextButton(onClick = { openAppSettings(context) }) { Text("Keep it running in the background: Battery, then Unrestricted", color = tc.text) }
                }
                if (voiceOn) {
                    val hushWord by session.hushWord.collectAsStateWithLifecycle()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Say “stop” to stop", style = MaterialTheme.typography.bodyLarge, color = tc.text)
                            Words("While it talks here, the phone listens for stop, hush or quiet.")
                        }
                        Switch(hushWord, { on -> if (on) perms.withMic { session.setHushWord(true) } else session.setHushWord(false) }, colors = SwitchDefaults.colors(checkedTrackColor = tc.decision, checkedThumbColor = tc.onDecision))
                    }
                }
            }

            Section("Paired with") {
                val v = via
                val network by session.network.collectAsStateWithLifecycle()
                Text(
                    when {
                        link is Link.Live && v != null -> "${name.ifBlank { "This Mac" }}, " + SettingsModel.reachedOn(v.tailscale, network?.label ?: v.host.seenName) + " (${v.host.host})"
                        link is Link.Live -> name.ifBlank { "This Mac" }
                        else -> "${name.ifBlank { "This Mac" }}: out of reach now"
                    },
                    Modifier.semantics { contentDescription = "Connection: " + (if (link is Link.Live && v != null) v.label else "out of reach") },
                    style = MaterialTheme.typography.bodyLarge, color = tc.text,
                )
                creds?.let { c ->
                    // The pairing: every network the tower was reached on, and how each stands now.
                    val tries by session.routeTries.collectAsStateWithLifecycle()
                    val now = rememberNow(true)
                    Text("Networks it is reached on", style = MaterialTheme.typography.labelLarge, color = tc.faint)
                    SettingsModel.networks(c.hosts, c.magicDns, if (link is Link.Live) v?.host else null, tries, now).forEach { row ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                Text(row.title, style = MaterialTheme.typography.bodyLarge, color = tc.text)
                                Text(row.address, style = MaterialTheme.typography.bodySmall, color = tc.muted)
                            }
                            Text(row.status, style = MaterialTheme.typography.labelMedium, color = if (row.now) tc.live else tc.faint)
                        }
                    }
                    Words("This phone is ${c.deviceName.ifBlank { "paired" }} there. Back on one of these networks it connects by itself; on a new one it looks for the Mac first.")
                    // What the last search for the Mac on this Wi-Fi did (Bonjour), so a try that fails says why.
                    val search by session.search.collectAsStateWithLifecycle()
                    search?.let { Words("Looked for the Mac on this Wi-Fi at ${clock(it.atMs)}: ${it.note}.") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("Name it") { renaming = true }
                    val pairingOn by session.pairingSupported.collectAsStateWithLifecycle()
                    if (pairingOn) PillButton("Rename this phone") { renamingPhone = true }
                }
                // The last way: only when the Mac can't be found from here at all.
                Words("Pairing again is only needed when the phone can't find the Mac on any network: it keeps this pairing.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("Pair again", onClick = onRepair)
                    PillButton("Unpair") { confirmUnpair = true }
                }
            }

            Section("Look") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("system" to "Auto", "dark" to "Dark", "light" to "Light").forEach { (t, label) -> Toggle(label, theme == t) { onTheme(t) } }
                }
                Words("How the orb is drawn")
                var orbChoice by remember { mutableStateOf(session.settings.orbTier) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("auto" to "Auto", "Full" to "Full", "Analytic" to "Simple", "Gradient" to "Gradient").forEach { (k, label) ->
                        Toggle(label, orbChoice == k) {
                            orbChoice = k
                            session.settings.orbTier = k
                            dev.herdertower.phone.orb.OrbProbe.override = dev.herdertower.phone.orb.OrbTier.entries.firstOrNull { it.name == k }
                        }
                    }
                }
            }

            // The tower's own settings: kept on the Mac, changed from here too.
            SectionLabel("The tower")
            // Each Claude account's usage and what the failover moved (a tower with accounts): live with the tower's state.
            AccountsSection(session)
            val model by session.model.collectAsStateWithLifecycle()
            model?.let { bm -> BrainSection(bm, session, scope, snack) }
            val qOn by session.questionsSupported.collectAsStateWithLifecycle()
            val policy by session.questionPolicy.collectAsStateWithLifecycle()
            if (qOn) Section("Questions sessions ask") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("normal" to "Normal", "keep-moving" to "Keep moving", "ask-everything" to "Ask me all").forEach { (p, label) ->
                        Toggle(label, (policy ?: "normal") == p) { scope.launch { session.setQuestionPolicy(p).onFailure { snack(TowerSession.describe(it)) } } }
                    }
                }
                Words(
                    when (policy ?: "normal") {
                        "keep-moving" -> "The tower answers borderline ones too; deploys, pushes, deleting and money still come to you."
                        "ask-everything" -> "Nothing is answered for you: every question comes to your queue."
                        else -> "The tower answers routine ones and writes that down; the rest come to your queue."
                    },
                )
            }
            Section("Mac microphone") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("ptt" to "Press to talk", "handsfree" to "Hands-free", "off" to "Off").forEach { (m, label) ->
                        Toggle(label, state?.mode == m) { scope.launch { session.act { it.mode(m) }.onFailure { e -> snack(TowerSession.describe(e)) } } }
                    }
                }
            }
            val behaviorsOn by session.behaviorsSupported.collectAsStateWithLifecycle()
            val behaviorData by session.behaviors.collectAsStateWithLifecycle()
            if (behaviorsOn) Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(tc.card).clickable(role = Role.Button, onClick = onBehaviors).heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Behaviors", style = MaterialTheme.typography.titleMedium, color = tc.text, fontWeight = FontWeight.SemiBold)
                    Words(behaviorData?.let { "${it.behaviors.count { b -> b.enabled }} rules: how the tower works" } ?: "How the tower works")
                }
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = tc.faint)
            }

            // What's under the hood on this phone (read it out when something looks wrong).
            val orb by dev.herdertower.phone.orb.OrbProbe.report.collectAsStateWithLifecycle()
            val frameMs by dev.herdertower.phone.orb.OrbProbe.frameMs.collectAsStateWithLifecycle()
            val drawnSteps by dev.herdertower.phone.orb.OrbProbe.drawnSteps.collectAsStateWithLifecycle()
            val playback by session.player.stats.collectAsStateWithLifecycle()
            val take by dev.herdertower.phone.voice.Takes.last.collectAsStateWithLifecycle()
            val hushOn by session.hushWord.collectAsStateWithLifecycle()
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(
                    "Diagnostics: " + (orb?.line(frameMs, drawnSteps) ?: "probing the orb…") +
                        "\n" + playback.line() + " · say-stop ${if (hushOn && voiceOn) "on" else "off"}" +
                        "\n" + (take?.line() ?: "no take yet"),
                    Modifier.padding(horizontal = 4.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall, color = tc.faint,
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
    if (renamingPhone) {
        var text by remember { mutableStateOf(creds?.deviceName.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renamingPhone = false },
            title = { Text("This phone's name on the Mac") },
            text = { OutlinedTextField(text, { text = it.take(40) }, singleLine = true, label = { Text("As Settings → Phone on the Mac lists it") }) },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = {
                    renamingPhone = false
                    scope.launch {
                        session.renameDevice(text)
                            .onSuccess { snack("The Mac calls this phone “${text.trim()}” now.") }
                            .onFailure { snack("Couldn't rename it: ${TowerSession.describe(it)}") }
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renamingPhone = false }) { Text("Cancel") } },
        )
    }
    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text("Unpair this phone?") },
            text = { Text("The tower revokes this phone's token, and the app forgets it. You can pair again with “tower phone pair”.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnpair = false
                    scope.launch { session.unpair(tellTower = true) }
                }) { Text("Unpair", color = tc.failed) }
            },
            dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text("Cancel") } },
        )
    }
}

/**
 * The model and effort every tower brain runs on (the main one and each
 * space's own): picked here, applied when a brain starts, or now with a
 * restart. A Codex brain takes its model from its own settings.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BrainSection(m: dev.herdertower.phone.api.BrainModel, session: TowerSession, scope: kotlinx.coroutines.CoroutineScope, snack: (String) -> Unit) {
    var restarting by remember { mutableStateOf(false) }
    fun change(model: String? = null, effort: String? = null) {
        scope.launch { session.setModel(model, effort).onFailure { snack("Couldn't change it: ${TowerSession.describe(it)}") } }
    }
    Section("The tower's brains") {
        if (m.program == "codex") {
            Words("The tower's brain is Codex: its model comes from Codex's own settings.")
            return@Section
        }
        Text("Model", style = MaterialTheme.typography.labelLarge, color = tc.faint)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            m.choices.models.forEach { c -> Toggle(c.label.ifBlank { c.id }, m.model == c.id || m.model == c.label) { if (m.model != c.id) change(model = c.id) } }
            if (m.choices.models.none { it.id == m.model || it.label == m.model } && m.model.isNotBlank()) Toggle(m.model, true) {}
        }
        if (m.choices.efforts.isNotEmpty()) {
            Text("Effort", style = MaterialTheme.typography.labelLarge, color = tc.faint)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                m.choices.efforts.forEach { e -> Toggle(e, m.effort == e) { if (m.effort != e) change(effort = e) } }
            }
        }
        SettingsModel.modelWords(m).forEach { Words(it) }
        if (m.restartNeeded) Row {
            PillButton(if (restarting) "Restarting…" else "Restart the brains now", enabled = !restarting) {
                restarting = true
                scope.launch {
                    session.setModel(restart = true)
                        .onSuccess { r -> snack(if (r.restarted.isEmpty()) "No brain was running." else "Restarted: ${r.restarted.joinToString(", ")}.") }
                        .onFailure { snack("Couldn't restart them: ${TowerSession.describe(it)}") }
                    restarting = false
                }
            }
        }
    }
}

/** The app's page in the system settings (notifications, battery). */
private fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData("package:${context.packageName}".toUri()).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
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
