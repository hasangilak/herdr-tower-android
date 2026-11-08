package dev.herdertower.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.herdertower.phone.api.HistoryEntry
import dev.herdertower.phone.data.Link
import dev.herdertower.phone.tower
import dev.herdertower.phone.data.TowerSession
import dev.herdertower.phone.orb.OrbGlyph
import dev.herdertower.phone.voice.VoiceController
import kotlinx.coroutines.launch
import java.util.Calendar

/** Everything Home shows, so it can be previewed and tested without a tower. */
data class HomeUi(
    val greeting: String,
    val towerName: String,
    val needsLine: String,
    val statusLine: String,
    val connected: Boolean,
    val alerts: Int,
    val suggestions: List<Suggestion>,
    val lastAnswer: HistoryEntry?,
    val focus: String?,
    val spaces: List<String>,
    val voiceOn: Boolean,
)

@Composable
fun HomeScreen(
    session: TowerSession,
    voice: VoiceController,
    onVoice: (listen: Boolean) -> Unit,
    onChat: () -> Unit,
    onOpen: (String) -> Unit,
    onAlerts: () -> Unit,
    onMenu: () -> Unit,
    onRepair: () -> Unit,
    onQuestion: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val perms = rememberPermissions()
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    val state by session.state.collectAsStateWithLifecycle()
    val link by session.link.collectAsStateWithLifecycle()
    val questions by session.questions.collectAsStateWithLifecycle()
    val spaces by session.spaces.collectAsStateWithLifecycle()
    val jobs by session.jobs.collectAsStateWithLifecycle()
    val agents by session.agents.collectAsStateWithLifecycle()
    val history by session.history.collectAsStateWithLifecycle()
    val thread by session.thread.collectAsStateWithLifecycle()
    val threadOn by session.threadSupported.collectAsStateWithLifecycle()
    val voiceOn by session.voiceOn.collectAsStateWithLifecycle()
    val name by session.towerName.collectAsStateWithLifecycle()
    val composer = context.tower.composer
    var asking by remember { mutableStateOf(false) }

    val connected = link is Link.Live
    val s = state
    val blocked = HomeModel.blocked(s?.agents.orEmpty(), jobs)
    val busy = s?.agents?.count { it.status == "working" } ?: 0
    val (word, _) = phaseWord(if (connected) s?.phase else null)
    val status = buildList {
        add(word)
        if (connected && spaces.focus != null) add("Focus: ${spaces.focus}")
    }.joinToString(" · ")
    val ui = HomeUi(
        greeting = HomeModel.timeGreeting(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)),
        towerName = name.ifBlank { "Your Mac" },
        needsLine = if (connected) HomeModel.needsLine(blocked.size, busy, s?.agents?.size ?: 0) else "not connected",
        statusLine = status,
        connected = connected,
        alerts = blocked.size + spaces.spaces.sumOf { it.unread },
        // A session whose question has a card isn't suggested again.
        suggestions = if (connected) HomeModel.suggestions(s?.agents.orEmpty(), spaces, jobs, agents)
            .filterNot { sg -> sg is Suggestion.Answer && questions.any { it.forUser && it.session == sg.address } } else emptyList(),
        lastAnswer = (if (threadOn == true) thread.lastOrNull { it.fromTower }?.let(ChatModel::entry) else null) ?: history.lastOrNull { it.kind == "answer" },
        focus = spaces.focus,
        spaces = spaces.spaces.map { it.space },
        voiceOn = voiceOn,
    )

    /** A suggestion's question, sent as it is (the draft stays). */
    fun ask(text: String) {
        if (asking) return
        asking = true
        scope.launch {
            session.act { it.ask(text) }
                .onSuccess { composer.addPending(text); session.linger(); onChat() }
                .onFailure { snack("Couldn't send: ${TowerSession.describe(it)}") }
            asking = false
        }
    }
    val actions = rememberComposerActions(composer, onTalk = { onVoice(true) }, onSent = onChat)
    fun focus(space: String?) {
        scope.launch {
            session.act { it.focus(space) }.onSuccess { session.refreshLists() }.onFailure { snack(TowerSession.describe(it)) }
        }
    }

    HomeContent(
        ui = ui,
        notice = { ConnectionNotice(link, session, onRepair) },
        activeNow = { ActiveNow(session, onOpen) },
        questions = {
            QuestionCards(QuestionModel.forUser(questions), onQuestion, Modifier.padding(horizontal = 20.dp))
        },
        askBox = {
            AskBox(
                field = { DraftField(composer, "Ask the tower, or describe the work", 2, actions.send, actions.add, Modifier.fillMaxWidth()) },
                above = {
                    DictationHint(composer)
                    AttachmentStrip(composer, Modifier.padding(bottom = 10.dp))
                },
                leading = {
                    AttachButton(actions)
                    Spacer(Modifier.width(8.dp))
                    RouteToggle(session)
                    Spacer(Modifier.width(8.dp))
                    FocusPill(ui.focus, ui.spaces, ::focus, Modifier.weight(1f, fill = false))
                },
                trailing = {
                    DictateButton(composer, actions)
                    Spacer(Modifier.width(10.dp))
                    SendOrTalk(composer, session, actions, 52.dp)
                },
            )
        },
        onSuggestion = { sg ->
            when (sg) {
                is Suggestion.Answer -> onOpen(sg.address)
                is Suggestion.Ask -> ask(sg.prompt)
                is Suggestion.Focus -> focus(sg.space)
                is Suggestion.ClearFocus -> focus(null)
            }
        },
        onAlerts = onAlerts,
        onMenu = onMenu,
        onChat = onChat,
    )
}

@Composable
fun HomeContent(
    ui: HomeUi,
    notice: @Composable () -> Unit = {},
    /** The questions sessions leave to the user, as cards. */
    questions: @Composable () -> Unit = {},
    /** The sessions at work right now. */
    activeNow: @Composable () -> Unit = {},
    askBox: @Composable () -> Unit,
    onSuggestion: (Suggestion) -> Unit,
    onAlerts: () -> Unit,
    onMenu: () -> Unit,
    onChat: () -> Unit,
) {
    GlassScaffold(header = {
        // The tower, how it is: where the other screens have Close.
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            OrbGlyph(Modifier.size(34.dp).semantics { contentDescription = if (ui.connected) "Tower, connected" else "Tower, not connected" })
            Dot(if (ui.connected) tc.working else tc.idle, 10.dp, Modifier.align(Alignment.BottomEnd).padding(end = 4.dp, bottom = 4.dp).border(2.dp, tc.bgTop, CircleShape))
        }
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).padding(horizontal = 6.dp)) {
            // Narrow (a small phone, large text, Stop showing): what matters only, on two lines.
            val roomy = maxWidth >= 120.dp
            Column {
                if (roomy) Text("${ui.greeting} · ${ui.towerName}", style = MaterialTheme.typography.bodySmall, color = tc.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    ui.needsLine.replaceFirstChar { it.uppercase() }, style = if (roomy) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium, color = tc.text,
                    fontWeight = FontWeight.Medium, maxLines = if (roomy) 1 else 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        HeaderButton(Icons.Filled.Notifications, "Activity", badge = ui.alerts, onClick = onAlerts)
        HeaderButton(Icons.Filled.Menu, "Menu", onClick = onMenu)
        StopSlot()
    }) {
    Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(headerTop() + 10.dp))
            activeNow()
            Box(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) { notice() }
            Spacer(Modifier.height(28.dp))
            Text(
                "What should the tower do?",
                Modifier.padding(horizontal = 24.dp).semantics { heading() },
                style = MaterialTheme.typography.displaySmall, color = tc.text,
            )
            Row(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                val (_, color) = phaseWord(if (ui.connected) "idle" else null)
                Dot(if (ui.needsLine.contains("need")) tc.blocked else color, 7.dp)
                Spacer(Modifier.width(8.dp))
                Text(ui.statusLine, style = MaterialTheme.typography.bodyMedium, color = tc.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            questions()
            Spacer(Modifier.height(20.dp))
            if (ui.suggestions.isNotEmpty()) {
                // A new first card (someone needs you) is shown, not left scrolled out of view.
                val row = androidx.compose.foundation.lazy.rememberLazyListState()
                LaunchedEffect(ui.suggestions.first().key) { row.animateScrollToItem(0) }
                LazyRow(state = row, contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(ui.suggestions, key = { it.key }) { SuggestionCard(it) { onSuggestion(it) } }
                }
            }
            ui.lastAnswer?.let { e ->
                Spacer(Modifier.height(20.dp))
                Row(
                    Modifier
                        .padding(horizontal = 20.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .clickable(role = Role.Button, onClickLabel = "Open the conversation", onClick = onChat)
                        .padding(4.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    OrbGlyph(Modifier.padding(top = 2.dp).size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Last reply · ${ago(e.at)}", style = MaterialTheme.typography.labelMedium, color = tc.faint)
                        Text(ChatModel.preview(e.text), style = MaterialTheme.typography.bodyMedium, color = tc.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.padding(start = 8.dp, top = 8.dp).size(18.dp), tint = tc.faint)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        askBox()
    }
    }
}

@Composable
private fun SuggestionCard(s: Suggestion, onClick: () -> Unit) {
    val (icon, tint, title, detail) = when (s) {
        is Suggestion.Answer -> Quad(Icons.Filled.Warning, tc.blocked, "${s.name} needs you", s.detail)
        is Suggestion.Ask -> Quad(Icons.Filled.AutoAwesome, tc.accentHi.takeIf { tc.dark } ?: tc.accent, s.title, s.detail)
        is Suggestion.Focus -> Quad(Icons.Filled.CenterFocusStrong, tc.working, "Focus on ${s.space}", s.detail)
        is Suggestion.ClearFocus -> Quad(Icons.Filled.CenterFocusStrong, tc.muted, "Stop focusing on ${s.space}", "Hear every space again")
    }
    Column(
        Modifier
            .width(168.dp)
            .heightIn(min = 148.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(if (tc.dark) Color(0xFF140D0A).copy(alpha = 0.55f) else Color.White.copy(alpha = 0.6f))
            .border(1.dp, tc.edge, RoundedCornerShape(24.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = tint)
        Spacer(Modifier.height(28.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = tc.text, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = tc.muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

private data class Quad(val icon: androidx.compose.ui.graphics.vector.ImageVector, val tint: Color, val title: String, val detail: String?)

/** The roomy rounded input: the draft, its pictures, and a row of controls. */
@Composable
fun AskBox(
    field: @Composable () -> Unit,
    above: @Composable () -> Unit = {},
    leading: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    trailing: @Composable () -> Unit = {},
) {
    val shape = RoundedCornerShape(30.dp)
    Column(
        Modifier
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(if (tc.dark) Color(0xFF120B08).copy(alpha = 0.72f) else Color.White.copy(alpha = 0.78f))
            .border(1.dp, tc.edge, shape)
            .padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 12.dp),
    ) {
        above()
        Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(end = 8.dp)) { field() }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The left controls give way (the focus pill shortens) so the round button keeps its size.
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) { leading() }
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/** Picks the space focus: its news is spoken, the others' held. */
@Composable
fun FocusPill(focus: String?, spaces: List<String>, onFocus: (String?) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier
                .clip(CircleShape)
                .background(tc.card)
                .border(1.dp, tc.edge, CircleShape)
                .clickable(role = Role.DropdownList, onClickLabel = "Choose the focused space") { open = true }
                .padding(start = 14.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(focus ?: "All spaces", style = MaterialTheme.typography.labelLarge, color = tc.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).widthIn(max = 150.dp))
            Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(18.dp), tint = tc.muted)
        }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text("All spaces (no focus)") }, onClick = { open = false; onFocus(null) })
            spaces.forEach { sp -> DropdownMenuItem(text = { Text((if (sp == focus) "● " else "") + sp) }, onClick = { open = false; onFocus(sp) }) }
        }
    }
}
