package dev.herdertower.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.herdertower.phone.api.Decision
import dev.herdertower.phone.api.HistoryEntry
import dev.herdertower.phone.api.Job
import dev.herdertower.phone.api.Mission
import dev.herdertower.phone.api.MissionSession
import dev.herdertower.phone.api.MissionsApi
import dev.herdertower.phone.api.ThreadMessage
import dev.herdertower.phone.data.SpaceBusy
import dev.herdertower.phone.data.TowerSession
import kotlinx.coroutines.launch

/** What a session's row and its own screen say about it, from what the phone has. */
object SessionModel {
    /** A job's line: "Restart v2 training · since 01:21 · last progress 01:42", "… · quiet", "… · watched". */
    fun jobLine(j: dev.herdertower.phone.api.AgentJob, clock: (Long) -> String = { dayClock(it) }): String = listOfNotNull(
        j.what.ifBlank { j.command.orEmpty() }.ifBlank { "A job" },
        j.sinceMs.takeIf { it > 0 }?.let { "since ${clock(it)}" },
        j.progressMs?.takeIf { it > 0 }?.let { "last progress ${clock(it)}" },
        if (j.stuck) "quiet, possibly stuck" else if (j.busy) "working" else null,
        j.note?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")

    /** A server's line: "npm run dev · port 3002". */
    fun serverLine(v: dev.herdertower.phone.api.AgentServer): String =
        v.what.ifBlank { "A server" } + when (v.ports.size) {
            0 -> ""
            1 -> " · port ${v.ports[0]}"
            else -> " · ports " + v.ports.joinToString(", ")
        }

    /** Its last words: what the tower gives, else its last report in the history, else what its last job came to. */
    fun said(s: MissionSession, history: List<HistoryEntry>, jobs: List<Job>): String {
        s.said?.takeIf { it.isNotBlank() }?.let { return it }
        history.lastOrNull { it.session == s.address && it.kind in setOf("summary", "watch", "job") && it.text.isNotBlank() }?.let { e ->
            return ChatModel.preview(ChatModel.report(e.text)).removePrefix("${s.address} (${s.name}): ")
        }
        val job = jobs.filter { it.session == s.address }.maxByOrNull { it.updatedMs } ?: return ""
        return job.summary.ifBlank { job.asked }
    }

    /** "mediaroom@sandbox · port 3011", or what there is of it. */
    fun place(s: MissionSession): String = listOfNotNull(s.worktree?.takeIf { it.isNotBlank() }, s.port?.takeIf { it > 0 }?.let { "port $it" }).joinToString(" · ")

    /** "done · worktree mediaroom@sandbox". */
    fun line(s: MissionSession): String = listOfNotNull(
        s.status.takeIf { it.isNotBlank() }?.let(::statusWord),
        s.worktree?.takeIf { it.isNotBlank() }?.let { "worktree $it" },
        s.port?.takeIf { it > 0 }?.let { "port $it" },
    ).joinToString(" · ")

    /** A session's status in a word, as the rows show it. */
    fun statusWord(status: String): String = when (status) {
        "idle" -> "done"
        "blocked" -> "waits"
        else -> status
    }
}

/**
 * A space's own tower: its goal and what counts as done, what needs you
 * there, its sessions, and what they wrote each other. What you say or type
 * on this screen goes to this space.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MissionScreen(
    session: TowerSession,
    name: String,
    onBack: () -> Unit,
    onSession: (String) -> Unit,
    onQuestion: (String) -> Unit,
    onShot: (String) -> Unit,
    onChat: () -> Unit,
) {
    val missions by session.missions.collectAsStateWithLifecycle()
    val supported by session.missionsSupported.collectAsStateWithLifecycle()
    val queue by session.decisions.collectAsStateWithLifecycle()
    val history by session.history.collectAsStateWithLifecycle()
    val jobs by session.jobs.collectAsStateWithLifecycle()
    val shots by session.screenshots.collectAsStateWithLifecycle()
    val spaces by session.spaces.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    val host = rememberDecisionHost(session)
    // Closed while on screen, it leaves the missions: what it was for stays on screen beside its receipt.
    var last by remember(name) { mutableStateOf<Mission?>(null) }
    val found = missions.spaces.firstOrNull { it.name == name }
    LaunchedEffect(found) { if (found != null) last = found }
    val closed = name in missions.closedToday
    val m = found ?: last?.takeIf { closed }?.copy(state = "closed", sessions = emptyList(), needs = 0, tower = null)
        ?: Mission(name = name, state = if (closed) "closed" else "quiet")
    val full = supported == true

    // This space's own decisions: a muted space's aren't in the queue, so they're read here.
    var own by remember(name) { mutableStateOf<List<Decision>?>(null) }
    // And those put on hold here: listed apart, never counted.
    val mainHeld by session.onHold.collectAsStateWithLifecycle()
    var ownHeld by remember(name) { mutableStateOf<List<Decision>?>(null) }
    LaunchedEffect(name, queue, mainHeld, m.needs, m.mute) {
        val (open, held) = session.spaceQueue(name)
        own = open
        ownHeld = held
    }
    val decisions = (own ?: queue.filter { it.space == name }).filter { it.live && !it.held }
    val held = ownHeld ?: mainHeld.filter { it.space == name }

    // What its sessions wrote each other: read once here, then kept current by the thread event.
    val threads by session.spaceThreads.collectAsStateWithLifecycle()
    val spaceThread = threads[name]
    val thread = spaceThread?.messages.orEmpty()
    var allThread by rememberSaveable(name) { mutableStateOf(false) }
    LaunchedEffect(name, full) { if (full) session.loadSpaceThread(name) }

    var editing by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    // A close the tower held back (sessions at work), and the close under way, by its action's id.
    var busy by remember { mutableStateOf<SpaceBusy?>(null) }
    var closingId by rememberSaveable(name) { mutableStateOf<String?>(null) }
    val actions by session.actions.collectAsStateWithLifecycle()
    val closeAction = closingId?.let { actions[it] }

    fun close(force: Boolean) {
        scope.launch {
            session.closeSpace(name, force)
                .onSuccess { a -> closingId = a.id }
                .onFailure { e -> if (e is SpaceBusy) busy = e else snack("Couldn't close it: ${TowerSession.describe(e)}") }
        }
    }
    val now = rememberNow(decisions.isNotEmpty())
    val focused = spaces.focus == name
    // Where its changes stand, repo by repo (a tower with releases): from the state, current with every state event.
    val state by session.state.collectAsStateWithLifecycle()
    val releasesOn by session.releasesSupported.collectAsStateWithLifecycle()
    val track = remember(state?.releases, releasesOn, name) {
        if (releasesOn) dev.herdertower.phone.api.ReleasesApi.of(dev.herdertower.phone.api.ReleasesApi.parse(state?.releases), name) else null
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(
            name,
            // Its release summary first ("prod 14:20 · 1 on dev"): the line is cut at its end, and this is what it says under the name on the Mac.
            (track?.summary?.takeIf { it.isNotBlank() }?.let { "$it · " } ?: "") +
                MissionsApi.sessionsLine(m) + (if (m.ownTower) " · its own tower" else "") + (if (m.state == "closed") " · closed" else ""),
            onBack = onBack,
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // The goal, and what counts as done.
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).padding(start = 4.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Labelled("Goal", m.goal?.takeIf { it.isNotBlank() } ?: "No goal yet. Say what this space is for.", dim = m.goal.isNullOrBlank())
                    m.doneWhen?.takeIf { it.isNotBlank() }?.let { Labelled("Done when", it) }
                    GoalHistory(m)
                }
                if (full && m.state != "closed") Box(
                    Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Change the goal") { editing = true }.semantics { contentDescription = "Change the goal" },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Edit, null, Modifier.size(18.dp), tint = tc.muted) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (full && m.state != "closed") {
                    Toggle("Follow", m.follow) { scope.launch { session.followSpace(name, !m.follow).onSuccess { snack(if (!m.follow) "Following $name: its results reach you as they land." else "Not following $name.") }.onFailure { snack(TowerSession.describe(it)) } } }
                    Toggle("Mute", m.mute) { scope.launch { session.muteSpace(name, !m.mute).onSuccess { snack(if (!m.mute) "$name is muted: its decisions stay here, off your queue." else "$name is back on your queue.") }.onFailure { snack(TowerSession.describe(it)) } } }
                } else if (supported == false) {
                    // A tower from before Follow and Mute: its focus holds the other spaces' news.
                    Toggle("Focus", focused) { scope.launch { session.act { it.focus(if (focused) null else name) }.onSuccess { session.refreshLists() }.onFailure { snack(TowerSession.describe(it)) } } }
                }
                if (m.standing) Tag("Standing", tc.muted, Modifier.align(Alignment.CenterVertically))
            }

            // A close under way, or its receipt (what was ended, removed, and kept and why).
            closeAction?.let { a ->
                ActionCard(a)
                if (a.state == "done") Row { PillButton("Back to the spaces", primary = true, onClick = onBack) }
            }

            // Ready to close: its report, and the one button.
            if (closeAction == null && (m.state == "ready" || !m.report.isNullOrBlank())) Card {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Ready to close", style = MaterialTheme.typography.titleMedium, color = tc.text, fontWeight = FontWeight.SemiBold)
                    Text(m.report?.takeIf { it.isNotBlank() } ?: "Every request here is finished.", style = MaterialTheme.typography.bodyMedium, color = tc.muted)
                    m.autoCloseMs?.takeIf { it > System.currentTimeMillis() }?.let {
                        Text("It closes by itself at ${clock(it)} if it's left alone.", style = MaterialTheme.typography.bodySmall, color = tc.faint)
                    }
                    if (full) PillButton("Close $name", primary = true) { closing = true }
                }
            }

            ReplyCard(session, space = name, onChat = onChat, followed = m.follow)
            RuleOffers(session, space = name)
            // A launch from this phone became an act: it can be taken back from here.
            val acts by session.acts.collectAsStateWithLifecycle()
            acts.values.filter { it.space == name && it.kind == "launch" && !it.undone && it.undo.possible }.maxByOrNull { it.atMs }?.let { ActCard(it, session) }

            decisions.forEachIndexed { i, d ->
                if (d.open) DecisionCard(d, now, host, onQuestion, onSession, showSpace = false, tag = if (i == 0 && decisions.count { it.open } > 1) "Next" else null)
                else DecisionDone(d)
            }
            if (held.isNotEmpty()) {
                SectionLabel(DecisionModel.holdLabel(held.size))
                held.forEach { d -> HeldCard(d, now, host, onQuestion, onSession, showSpace = false) }
            }

            // On prod, on dev, open; and its releases: as the Mac's space panel shows them.
            track?.takeIf { it.repos.isNotEmpty() }?.let { ReleaseTrackCard(it, now) }

            if (m.requests.isNotEmpty()) {
                SectionLabel("Requests")
                m.requests.forEach { r ->
                    SignRow(
                        sign = { if (r.finished) Icon(Icons.Filled.Check, null, Modifier.size(18.dp), tint = tc.live) else Dot(tc.live, size = 9.dp, modifier = Modifier.padding(start = 5.dp, top = 6.dp)) },
                        name = r.title.ifBlank { "A request" },
                        right = if (!r.finished && r.total > 1) "${r.done} of ${r.total}" else if (r.finished) "done" else r.state,
                        sub = "",
                    )
                }
            }

            // Which account each runs on, and what the failover moved (a tower with accounts).
            val board = remember(state?.accounts) { dev.herdertower.phone.api.AccountsApi.parse(state?.accounts) }
            fun seat(address: String?, account: String?) = dev.herdertower.phone.api.AccountsApi.seat(board, address, account) { dayClock(it) }
            if (m.sessions.isNotEmpty()) {
                SectionLabel("Sessions")
                // Its own tower first, with its account.
                m.tower?.takeIf { it.status.isNotBlank() && it.status != "off" }?.let { t ->
                    val acc = t.account ?: state?.brains?.firstOrNull { it.space == name }?.account
                    SignRow(
                        sign = { SessionSign(if (t.status == "working") "working" else if (t.status == "blocked") "blocked" else "idle") },
                        name = "Its tower", right = SessionModel.statusWord(t.status), sub = t.name,
                        note = seat(t.address, acc),
                        onClick = t.address?.takeIf { it.isNotBlank() }?.let { a -> { onSession(a) } },
                    )
                }
                m.sessions.forEach { s ->
                    SignRow(
                        sign = { SessionSign(if (s.runsJob) "working" else s.status) },
                        name = dev.herdertower.phone.api.ActsApi.inSpace(s.label, name), right = SessionModel.statusWord(s.statusShown),
                        // A job it left running says what, since when and its last progress, over its last words.
                        sub = s.job?.line?.takeIf { it.isNotBlank() } ?: SessionModel.said(s, history, jobs),
                        mono = SessionModel.place(s).takeIf { it.isNotBlank() },
                        note = seat(s.address, s.account),
                        onClick = { onSession(s.address) },
                    )
                }
            } else if (m.state != "closed") {
                EmptyBox("No session runs here now.")
            }

            if (thread.isNotEmpty() || m.threadCount > 0) {
                val count = maxOf(m.threadCount, thread.size)
                SectionLabel("Between sessions · $count message${if (count == 1) "" else "s"}")
                val shown = if (allThread) thread else thread.takeLast(3)
                if (shown.isNotEmpty()) Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(tc.card).padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    shown.forEach { t ->
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Row {
                                Text(t.who, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = tc.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (!t.delivered) Text("not delivered  ", style = MaterialTheme.typography.labelSmall, color = tc.failed)
                                if (t.at > 0) Text(clock(t.at), style = MaterialTheme.typography.labelSmall, color = tc.faint)
                            }
                            Text(t.text, style = MaterialTheme.typography.bodyMedium, color = tc.muted, maxLines = if (allThread) 12 else 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Row {
                    if (thread.size > 3) TextButton(onClick = { allThread = !allThread }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (allThread) "Only the latest" else "All ${thread.size}", color = tc.muted)
                    }
                    if (allThread && spaceThread?.more == true) TextButton(onClick = { scope.launch { session.olderSpaceThread(name).onFailure { snack(TowerSession.describe(it)) } } }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Earlier", color = tc.muted)
                    }
                }
            }

            val mine = shots.filter { it.space == name }
            if (mine.isNotEmpty()) {
                SectionLabel("Shots")
                ShotStrip(session, mine.take(12)) { onShot(it.id) }
            }

            if (full && closeAction == null && m.state != "closed" && m.state != "ready" && m.report.isNullOrBlank()) TextButton(onClick = { closing = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Close this space", color = tc.muted)
            }
        }
    }
    host.Dialogs()

    if (editing) GoalDialog(m, onDismiss = { editing = false }) { goal, doneWhen, standing ->
        editing = false
        scope.launch { session.setGoal(name, goal, doneWhen, standing).onFailure { snack("Couldn't change the goal: ${TowerSession.describe(it)}") } }
    }
    if (closing) AlertDialog(
        onDismissRequest = { closing = false },
        title = { Text("Close $name?") },
        text = { Text("Its sessions are ended and the worktrees the tower made for it are removed. A worktree with work that is on no remote is kept, and you're told which.") },
        confirmButton = {
            TextButton(onClick = {
                closing = false
                close(force = false)
            }) { Text("Close it", color = tc.failed) }
        },
        dismissButton = { TextButton(onClick = { closing = false }) { Text("Keep it open") } },
    )
    busy?.let { b ->
        AlertDialog(
            onDismissRequest = { busy = null },
            title = { Text("Sessions are still at work") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    b.sessions.forEach { s -> Text("${s.label}: ${SessionModel.statusWord(s.status)}", style = MaterialTheme.typography.bodyMedium) }
                    Text("Closing now cuts their work in progress off.", style = MaterialTheme.typography.bodyMedium, color = tc.muted)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    busy = null
                    close(force = true)
                }) { Text("Close anyway", color = tc.failed) }
            },
            dismissButton = { TextButton(onClick = { busy = null }) { Text("Wait for them") } },
        )
    }
}

/**
 * How the goal came to be: "Goal, updated by your request at 15:55", the
 * request and what it added; then the earlier versions, folded, newest first.
 */
@Composable
private fun GoalHistory(m: Mission) {
    val now = dev.herdertower.phone.api.GoalsApi.current(m)
    dev.herdertower.phone.api.GoalsApi.how(now, ::clock)?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = tc.faint) }
    now?.takeIf { it.by == "request" }?.let { v ->
        v.request?.takeIf { it.isNotBlank() }?.let { Text("Your request: “$it”", style = MaterialTheme.typography.bodySmall, color = tc.muted, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        v.why?.takeIf { it.isNotBlank() }?.let { Text(it.replaceFirstChar { c -> c.uppercase() }, style = MaterialTheme.typography.bodySmall, color = tc.muted) }
    }
    val earlier = dev.herdertower.phone.api.GoalsApi.earlier(m)
    if (earlier.isEmpty()) return
    var open by rememberSaveable(m.name) { mutableStateOf(false) }
    TextButton(onClick = { open = !open }, modifier = Modifier.heightIn(min = 40.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp)) {
        Text(if (open) "Hide the earlier versions" else "${earlier.size} earlier version${if (earlier.size == 1) "" else "s"}", color = tc.muted, style = MaterialTheme.typography.labelLarge)
    }
    if (open) earlier.forEach { v ->
        Column(Modifier.padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(listOf(if (v.atMs > 0) clock(v.atMs) else "", dev.herdertower.phone.api.GoalsApi.by(v)).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = tc.faint)
            Text(v.goal, style = MaterialTheme.typography.bodySmall, color = tc.muted)
            v.doneWhen.takeIf { it.isNotBlank() }?.let { Text("Done when $it", style = MaterialTheme.typography.bodySmall, color = tc.faint) }
            v.request?.takeIf { it.isNotBlank() && v.by == "request" }?.let { Text("Your request: “$it”", style = MaterialTheme.typography.bodySmall, color = tc.faint, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** "**Goal** A plan for the storefront live editor". */
@Composable
private fun Labelled(label: String, text: String, dim: Boolean = false) {
    val strong = tc.text
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = strong, fontWeight = FontWeight.SemiBold)) { append(label) }
            append("  ")
            append(text)
        },
        style = MaterialTheme.typography.bodyLarge, color = if (dim) tc.faint else tc.muted,
    )
}

/** A session's sign: a tick when it's done, a dot while it works, an amber mark when it waits. */
@Composable
fun SessionSign(status: String) {
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        when (status) {
            "working" -> Dot(tc.live, size = 10.dp)
            "blocked" -> Box(Modifier.size(22.dp).clip(CircleShape).background(tc.decision), contentAlignment = Alignment.Center) { Icon(Icons.Filled.PriorityHigh, "Waits for you", Modifier.size(14.dp), tint = tc.onDecision) }
            "done", "idle" -> Icon(Icons.Filled.Check, null, Modifier.size(18.dp), tint = tc.live)
            "failed", "error", "exited" -> Dot(tc.failed, size = 10.dp)
            else -> Box(Modifier.size(10.dp).clip(CircleShape).background(tc.idle))
        }
    }
}

@Composable
private fun GoalDialog(m: Mission, onDismiss: () -> Unit, onSave: (goal: String, doneWhen: String?, standing: Boolean) -> Unit) {
    var goal by remember { mutableStateOf(m.goal.orEmpty()) }
    var doneWhen by remember { mutableStateOf(m.doneWhen.orEmpty()) }
    var standing by remember { mutableStateOf(m.standing) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What ${m.name} is for") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(goal, { goal = it }, Modifier.fillMaxWidth(), label = { Text("Goal") }, minLines = 2, maxLines = 5)
                OutlinedTextField(doneWhen, { doneWhen = it }, Modifier.fillMaxWidth(), label = { Text("Done when") }, placeholder = { Text("the change is live and checked") }, maxLines = 4)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Standing", style = MaterialTheme.typography.bodyLarge)
                        Text("It has no end: it never asks to be closed.", style = MaterialTheme.typography.bodySmall, color = tc.muted)
                    }
                    Switch(standing, { standing = it }, colors = SwitchDefaults.colors(checkedTrackColor = tc.decision, checkedThumbColor = tc.onDecision))
                }
            }
        },
        confirmButton = { TextButton(enabled = goal.isNotBlank(), onClick = { onSave(goal.trim(), doneWhen.trim().ifEmpty { null }, standing) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
