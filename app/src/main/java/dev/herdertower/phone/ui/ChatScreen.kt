package dev.herdertower.phone.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.herdertower.phone.api.HistoryEntry
import dev.herdertower.phone.data.Link
import dev.herdertower.phone.data.TowerSession
import dev.herdertower.phone.orb.OrbGlyph
import kotlinx.coroutines.launch

private sealed interface ChatRow {
    data class Day(val label: String) : ChatRow
    data class Item(val e: HistoryEntry) : ChatRow
}

@Composable
fun ChatScreen(session: TowerSession, onClose: () -> Unit, onVoice: () -> Unit, onOpen: (String) -> Unit, onShot: (String) -> Unit) {
    val history by session.history.collectAsStateWithLifecycle()
    val state by session.state.collectAsStateWithLifecycle()
    val link by session.link.collectAsStateWithLifecycle()
    val voiceOn by session.voiceOn.collectAsStateWithLifecycle()
    val acc = rememberAccounts(session)
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    val perms = rememberPermissions()
    val context = LocalContext.current
    var draft by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }

    fun send() {
        val t = draft.trim()
        if (t.isEmpty() || sending) return
        sending = true
        scope.launch {
            session.act { it.ask(t) }
                .onSuccess { draft = "" }
                .onFailure { snack("Couldn't send: ${TowerSession.describe(it)}") }
            sending = false
        }
    }

    ChatContent(
        entries = history,
        working = link is Link.Live && (state?.phase == "thinking" || state?.phase == "transcribing"),
        connected = link is Link.Live,
        accounts = acc,
        draft = draft,
        sending = sending,
        voiceOn = voiceOn,
        onDraft = { draft = it },
        onSend = ::send,
        onMic = { perms.withMic(onVoice) },
        onClose = onClose,
        onLoadOlder = { scope.launch { if (session.loadOlder() == 0) snack("That's everything.") } },
        onOpen = onOpen,
        onVoiceToggle = { setVoice(it, session, perms, context) },
        onShot = onShot,
        thumb = { e, m -> Thumb(session, shotOf(e), m, maxSide = 600) },
    )
}

/** The screenshot a history entry announces. */
fun shotOf(e: HistoryEntry) = dev.herdertower.phone.api.Screenshot(
    id = e.id.orEmpty(), at = e.at, session = e.session, name = e.name, space = e.space, account = e.account,
    caption = e.caption ?: e.text, url = "/v1/screenshots/${e.id.orEmpty()}",
)

@Composable
fun ChatContent(
    entries: List<HistoryEntry>,
    working: Boolean,
    connected: Boolean,
    accounts: AccountLookup,
    draft: String,
    sending: Boolean,
    voiceOn: Boolean,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onMic: () -> Unit,
    onClose: () -> Unit,
    onLoadOlder: () -> Unit,
    onOpen: (String) -> Unit,
    onVoiceToggle: (Boolean) -> Unit,
    onShot: (String) -> Unit = {},
    thumb: @Composable (HistoryEntry, Modifier) -> Unit = { _, _ -> },
) {
    val list = rememberLazyListState()
    val rows = remember(entries) {
        buildList<ChatRow> {
            var day = ""
            entries.filter { it.kind != "summary" }.forEach { e ->
                val d = dayLabel(e.at)
                if (d != day) {
                    add(ChatRow.Day(d))
                    day = d
                }
                add(ChatRow.Item(e))
            }
        }
    }
    LaunchedEffect(rows.size, working) { if (rows.isNotEmpty()) list.animateScrollToItem(rows.size + (if (working) 1 else 0)) }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Box(Modifier.fillMaxWidth().padding(top = 12.dp, start = 16.dp, end = 16.dp, bottom = 4.dp)) {
            ClosePill(modifier = Modifier.align(Alignment.Center), onClick = onClose)
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = list,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (entries.isNotEmpty()) TextButton(onClick = onLoadOlder) { Text("Earlier messages", color = tc.faint) }
                }
            }
            if (rows.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 120.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Say hi to the tower", style = MaterialTheme.typography.headlineSmall, color = tc.text)
                        Text("Type below, or tap the mic and talk.", style = MaterialTheme.typography.bodyMedium, color = tc.muted)
                    }
                }
            }
            items(rows, key = { r -> when (r) { is ChatRow.Day -> "d" + r.label; is ChatRow.Item -> "e${r.e.at}${r.e.kind}${r.e.text.hashCode()}" } }) { r ->
                when (r) {
                    is ChatRow.Day -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(r.label, style = MaterialTheme.typography.labelSmall, color = tc.faint)
                    }
                    is ChatRow.Item -> when (r.e.kind) {
                        "request" -> UserBubble(r.e)
                        "answer" -> ReplyBubble(r.e)
                        "screenshot" -> if (r.e.id != null) ShotLine(r.e, accounts, onShot, thumb) else EventLine(r.e, accounts, onOpen)
                        else -> EventLine(r.e, accounts, onOpen)
                    }
                }
            }
            if (working) item { WorkingLine() }
        }
        InputBar(draft, sending, connected, voiceOn, onDraft, onSend, onMic, onVoiceToggle)
    }
}

/** What you said: warm copper, on the right. */
@Composable
fun UserBubble(e: HistoryEntry) {
    val voice = e.text.startsWith("[voice] ")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(
            Modifier
                .padding(start = 56.dp)
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 24.dp, bottomEnd = 8.dp))
                .background(tc.userBubble)
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            Text(e.text.removePrefix("[voice] "), style = MaterialTheme.typography.bodyLarge, color = tc.onBubble)
            Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                if (voice) Icon(Icons.Filled.Mic, "spoken", Modifier.size(12.dp), tint = tc.onBubble.copy(alpha = 0.8f))
                Text(" " + clock(e.at), style = MaterialTheme.typography.labelSmall, color = tc.onBubble.copy(alpha = 0.85f))
            }
        }
    }
}

/** The tower's answer: a dark soft bubble with the orb beside it; its markdown rendered. */
@Composable
fun ReplyBubble(e: HistoryEntry) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        OrbGlyph(Modifier.padding(top = 6.dp).size(28.dp).semantics { contentDescription = "Tower" })
        Spacer(Modifier.width(10.dp))
        Column(
            Modifier
                .padding(end = 32.dp)
                .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 24.dp, bottomStart = 24.dp, bottomEnd = 24.dp))
                .background(tc.reply)
                .border(1.dp, tc.edge, RoundedCornerShape(topStart = 8.dp, topEnd = 24.dp, bottomStart = 24.dp, bottomEnd = 24.dp))
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            MarkdownText(e.text, tc.text)
            Text(clock(e.at), Modifier.align(Alignment.End), style = MaterialTheme.typography.labelSmall, color = tc.faint)
        }
    }
}

@Composable
private fun WorkingLine() {
    val reduce = LocalReduceMotion.current
    val a = if (reduce) 1f else rememberInfiniteTransition(label = "working").animateFloat(0.35f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "a").value
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 2.dp)) {
        OrbGlyph(Modifier.size(20.dp).alpha(a))
        Spacer(Modifier.width(10.dp))
        Text("The tower is working…", style = MaterialTheme.typography.bodyMedium, color = tc.faint, fontStyle = FontStyle.Italic)
    }
}

/** The floating input: voice toggle, text (Send on the keyboard sends), and mic or send in one fixed place. */
@Composable
private fun InputBar(
    draft: String, sending: Boolean, connected: Boolean, voiceOn: Boolean,
    onDraft: (String) -> Unit, onSend: () -> Unit, onMic: () -> Unit, onVoiceToggle: (Boolean) -> Unit,
) {
    val shape = RoundedCornerShape(30.dp)
    Row(
        Modifier
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(if (tc.dark) Color(0xFF120B08).copy(alpha = 0.85f) else Color.White.copy(alpha = 0.9f))
            .border(1.dp, tc.edge, shape)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).clickable(role = Role.Button) { onVoiceToggle(!voiceOn) }
                .semantics { contentDescription = if (voiceOn) "Phone is the voice: on" else "Phone is the voice: off" },
            contentAlignment = Alignment.Center,
        ) { Icon(if (voiceOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff, null, tint = tc.muted) }
        BasicTextField(
            draft, onDraft,
            Modifier.weight(1f).heightIn(min = 44.dp).padding(horizontal = 6.dp, vertical = 11.dp).semantics { contentDescription = "Ask the tower" },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = tc.text),
            cursorBrush = SolidColor(tc.accentHi),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            maxLines = 5,
            decorationBox = { inner ->
                if (draft.isEmpty()) Text("Ask the tower", style = MaterialTheme.typography.bodyLarge, color = tc.faint)
                inner()
            },
        )
        MicOrSend(draft.isNotBlank(), sending, enabled = connected, size = 48.dp, onMic = onMic, onSend = onSend)
    }
}

/** How a line of activity looks: icon, colour and words. */
@Composable
fun eventLook(e: HistoryEntry): Triple<ImageVector, Color, String> {
    val who = e.name ?: e.session ?: ""
    return when (e.kind to e.status) {
        "job" to "working" -> Triple(Icons.Filled.NorthEast, tc.working, "Asked $who: ${e.text}")
        "job" to "done", "job" to "idle" -> Triple(Icons.Filled.CheckCircle, tc.done, if (e.text.isEmpty()) "$who finished" else "$who finished: ${e.text}")
        "job" to "blocked" -> Triple(Icons.Filled.Warning, tc.blocked, "$who needs you" + if (e.text.isEmpty()) "" else ": ${e.text}")
        else -> when (e.kind) {
            "job" -> Triple(Icons.Filled.Error, tc.failed, "$who ${statusWord(e.status ?: "")}" + if (e.text.isEmpty()) "" else ": ${e.text}")
            "watch" -> Triple(Icons.Filled.Terminal, tc.muted, e.text)
            "alert" -> Triple(Icons.Filled.Notifications, tc.accentHi.takeIf { tc.dark } ?: tc.accent, e.text)
            "summary" -> Triple(Icons.AutoMirrored.Filled.Article, tc.muted, "$who: ${e.text}")
            else -> Triple(Icons.Filled.Info, tc.idle, e.text)
        }
    }
}

/** A session reporting back, an alert, a summary, a job starting or ending. */
@Composable
fun EventLine(e: HistoryEntry, accounts: AccountLookup? = null, onOpen: ((String) -> Unit)? = null) {
    val (icon, color, text) = eventLook(e)
    val session = e.session
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(if (onOpen != null && session != null) Modifier.clickable(role = Role.Button, onClickLabel = "Open $session") { onOpen(session) } else Modifier)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, Modifier.padding(top = 2.dp).size(16.dp), tint = color)
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = if (e.held) tc.faint else tc.muted, maxLines = 6)
        val acc = e.account ?: session?.let { accounts?.of(it) }
        if (acc != null && accounts != null) AccountBadge(acc, acc == accounts.default)
        Text(clock(e.at), Modifier.padding(top = 2.dp), style = MaterialTheme.typography.labelSmall, color = tc.faint)
    }
}

/** Which account each session runs on (from the state and spaces), and the default one. */
class AccountLookup(private val bySession: Map<String, String>, val default: String?) {
    fun of(address: String): String? = bySession[address]
}

@Composable
fun rememberAccounts(session: TowerSession): AccountLookup {
    val state by session.state.collectAsStateWithLifecycle()
    val spaces by session.spaces.collectAsStateWithLifecycle()
    val accounts by session.accounts.collectAsStateWithLifecycle()
    return remember(state, spaces, accounts) {
        val m = HashMap<String, String>()
        spaces.spaces.forEach { sp -> sp.sessions.forEach { s -> s.account?.takeIf { it.isNotBlank() }?.let { m[s.address] = it } } }
        state?.agents?.forEach { a -> a.account?.takeIf { it.isNotBlank() }?.let { m[a.address] = it } }
        AccountLookup(m, accounts.default)
    }
}

/** A session sent a screenshot: its picture, caption and who, inline; tap to view it full size. */
@Composable
private fun ShotLine(e: HistoryEntry, accounts: AccountLookup, onShot: (String) -> Unit, thumb: @Composable (HistoryEntry, Modifier) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(role = Role.Button, onClickLabel = "View screenshot") { onShot(e.id.orEmpty()) }
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.Image, null, Modifier.size(16.dp), tint = tc.muted)
            Text(e.name ?: e.session ?: "A session", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = tc.muted, maxLines = 1)
            (e.account ?: e.session?.let { accounts.of(it) })?.let { AccountBadge(it, it == accounts.default) }
            Text(clock(e.at), style = MaterialTheme.typography.labelSmall, color = tc.faint)
        }
        thumb(e, Modifier.fillMaxWidth(0.72f).aspectRatio(1.4f))
        (e.caption ?: e.text).takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = tc.text) }
    }
}
