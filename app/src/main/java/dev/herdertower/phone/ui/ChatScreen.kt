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
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.runtime.produceState
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoLibrary
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
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import dev.herdertower.phone.tower
import dev.herdertower.phone.orb.OrbGlyph
import kotlinx.coroutines.launch

private sealed interface ChatRow {
    data class Day(val label: String) : ChatRow
    data class Item(val e: HistoryEntry) : ChatRow
}

@Composable
fun ChatScreen(session: TowerSession, onClose: () -> Unit, onVoice: () -> Unit, onOpen: (String) -> Unit, onShot: (String) -> Unit) {
    val history by session.history.collectAsStateWithLifecycle()
    val thread by session.thread.collectAsStateWithLifecycle()
    val threadOn by session.threadSupported.collectAsStateWithLifecycle()
    val threadMore by session.threadMore.collectAsStateWithLifecycle()
    val state by session.state.collectAsStateWithLifecycle()
    val link by session.link.collectAsStateWithLifecycle()
    val shots by session.screenshots.collectAsStateWithLifecycle()
    val acc = rememberAccounts(session)
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    val context = LocalContext.current
    val composer = context.tower.composer
    val actions = rememberComposerActions(composer, onTalk = onVoice)
    val thinking = link is Link.Live && (state?.phase == "thinking" || state?.phase == "transcribing")
    // A tower from before `work`: the phone times the turn from when it saw it start.
    var since by remember { mutableLongStateOf(0L) }
    LaunchedEffect(thinking) { since = if (thinking) System.currentTimeMillis() else 0L }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }
    var viewing by remember { mutableStateOf<Pair<List<Pic>, Int>?>(null) }

    val pendingSent by composer.pending.collectAsStateWithLifecycle()
    // The tower's thread when it has one (the same as the Mac panel's); otherwise the history.
    val useThread = threadOn == true
    val entries = remember(useThread, thread, history) { if (useThread) thread.map(ChatModel::entry) else history }
    val work = state?.work?.takeIf { link is Link.Live && ChatModel.showWork(it, entries, now) }
        ?: if (thinking && since > 0) dev.herdertower.phone.api.Work("Thinking", "", since, since, 0) else null
    // The reply in progress: what the tower says this turn, until the answer lands on the thread.
    val saying = state?.takeIf { work != null && it.said.isNotBlank() && it.saidMs >= work.sinceMs }?.said
    val shotsById = remember(thread) { thread.associate { it.id to it.shots() } }
    val waiting = remember(pendingSent, thread, now) { if (useThread) ChatModel.unmatched(pendingSent, thread, now) else emptyList() }

    Box(Modifier.fillMaxSize()) {
    ChatContent(
        entries = entries,
        curated = useThread,
        pending = waiting,
        canLoadMore = if (useThread) threadMore else history.isNotEmpty(),
        work = work,
        saying = saying,
        now = now,
        accounts = acc,
        onClose = onClose,
        onLoadOlder = { scope.launch { if (useThread) session.loadOlderThread() else if (session.loadOlder() == 0) snack("That's everything.") } },
        rowShots = { e -> shotsById[e.id].orEmpty().takeIf { it.isNotEmpty() }?.let { shots -> ShotStrip(session, shots) { onShot(it.id) } } },
        onOpen = onOpen,
        onShot = onShot,
        thumb = { e, m -> Thumb(session, shotOf(e), m, maxSide = 600) },
        replyShots = { refs, m ->
            val found = refs.flatMap { r ->
                when (r) {
                    is ChatModel.ShotRef.One -> listOfNotNull(shots.firstOrNull { it.id == r.id } ?: dev.herdertower.phone.api.Screenshot(id = r.id, url = "/v1/screenshots/${r.id}"))
                    is ChatModel.ShotRef.OfSession -> shots.filter { it.session == r.session }.take(4)
                }
            }.distinctBy { it.id }
            if (found.isNotEmpty()) ShotStrip(session, found) { onShot(it.id) }
        },
        sentFiles = { e ->
            SentFiles(session, e) { pics, i -> viewing = pics to i }
        },
        inputBar = {
            Column(
                Modifier
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(if (tc.dark) Color(0xFF120B08).copy(alpha = 0.88f) else Color.White.copy(alpha = 0.92f))
                    .border(1.dp, tc.edge, RoundedCornerShape(28.dp))
                    .padding(start = 8.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            ) {
                Column(Modifier.padding(start = 8.dp, top = 6.dp)) {
                    DictationHint(composer)
                    AttachmentStrip(composer, Modifier.padding(bottom = 6.dp))
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    AttachButton(actions, size = 44.dp)
                    DraftField(composer, "Ask the tower", 1, actions.send, actions.add, Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 11.dp))
                    DictateButton(composer, actions, size = 44.dp)
                    Spacer(Modifier.width(6.dp))
                    SendOrTalk(composer, session, actions, 48.dp)
                }
            }
        },
    )
    viewing?.let { (pics, i) -> PicViewer(pics, i) { viewing = null } }
    }
}

/** The files sent with a message: its pictures (from `GET /v1/attachments/…`), videos and files by name. */
@Composable
private fun SentFiles(session: TowerSession, e: HistoryEntry, onOpen: (List<Pic>, Int) -> Unit) {
    val pictures = e.attachments.filter(ChatModel::isPicture)
    val pics = remember(e.attachments) {
        pictures.mapIndexed { i, path ->
            Pic("att:$path", path.substringAfterLast('/').substringAfter('-'), "Sent ${clock(e.at)} · ${dayLabel(e.at)}") { side -> session.images.loadAttachment(path, side) }
        }
    }
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (pictures.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                pictures.forEachIndexed { i, path ->
                    val img by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, path) { value = session.images.loadAttachment(path, 360) }
                    Box(
                        Modifier.size(if (pictures.size == 1) 180.dp else 88.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.18f))
                            .clickable(role = Role.Button, onClickLabel = "View picture ${i + 1}") { onOpen(pics, i) }
                            .semantics { contentDescription = "Picture ${i + 1} you sent" },
                        contentAlignment = Alignment.Center,
                    ) {
                        img?.let { androidx.compose.foundation.Image(it, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
                            ?: Icon(Icons.Filled.Image, null, Modifier.size(22.dp), tint = tc.onBubble)
                    }
                }
            }
        }
        e.attachments.filterNot(ChatModel::isPicture).forEach { path ->
            val name = path.substringAfterLast('/').substringAfter('-')
            val video = path.substringAfterLast('.', "").lowercase() in setOf("mov", "mp4", "m4v", "webm", "mkv", "avi")
            Row(
                Modifier.clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.18f)).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (video) Icons.Filled.PlayCircle else Icons.Filled.Description, null, Modifier.size(16.dp), tint = tc.onBubble)
                Text(" $name", style = MaterialTheme.typography.labelMedium, color = tc.onBubble, maxLines = 1)
            }
        }
    }
}

/** The screenshot a history entry announces. */
fun shotOf(e: HistoryEntry) = dev.herdertower.phone.api.Screenshot(
    id = e.id.orEmpty(), at = e.at, session = e.session, name = e.name, space = e.space, account = e.account,
    caption = e.caption ?: e.text, url = "/v1/screenshots/${e.id.orEmpty()}",
)

@Composable
fun ChatContent(
    entries: List<HistoryEntry>,
    work: dev.herdertower.phone.api.Work?,
    now: Long,
    accounts: AccountLookup,
    onClose: () -> Unit,
    /** What the tower is saying this turn, before its answer lands. */
    saying: String? = null,
    onLoadOlder: () -> Unit,
    onOpen: (String) -> Unit,
    onShot: (String) -> Unit = {},
    thumb: @Composable (HistoryEntry, Modifier) -> Unit = { _, _ -> },
    replyShots: @Composable (List<ChatModel.ShotRef>, Modifier) -> Unit = { _, _ -> },
    sentFiles: @Composable (HistoryEntry) -> Unit = {},
    /** The thread is the tower's own (shown as it is); the history is filtered as the panel does. */
    curated: Boolean = false,
    pending: List<ChatModel.Pending> = emptyList(),
    canLoadMore: Boolean = true,
    /** Screenshots that came with a thread message. */
    rowShots: @Composable (HistoryEntry) -> Unit = {},
    inputBar: @Composable () -> Unit,
) {
    val list = rememberLazyListState()
    val rows = remember(entries) {
        buildList<ChatRow> {
            var day = ""
            (if (curated) entries else ChatModel.items(entries)).forEach { e ->
                val d = dayLabel(e.at)
                if (d != day) {
                    add(ChatRow.Day(d))
                    day = d
                }
                add(ChatRow.Item(e))
            }
        }
    }
    val working = work != null
    // The view sticks to the newest message (also while pictures load and grow), until you scroll up;
    // back at the end, or something new at the end (a message, the working line, something sent), it follows again.
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(list) {
        list.interactionSource.interactions.collect { if (it is androidx.compose.foundation.interaction.DragInteraction.Start) follow = false }
    }
    LaunchedEffect(list) {
        androidx.compose.runtime.snapshotFlow { list.canScrollForward }.collect { if (!it && list.layoutInfo.totalItemsCount > 0) follow = true }
    }
    val lastKey = (rows.lastOrNull() as? ChatRow.Item)?.e?.let { "${it.id}${it.at}${it.text.length}" }
    LaunchedEffect(lastKey, working, pending.size, saying?.length) { follow = true }
    LaunchedEffect(list) {
        androidx.compose.runtime.snapshotFlow { Triple(list.layoutInfo.totalItemsCount, list.canScrollForward, follow) }.collect { (n, more, f) ->
            if (f && more && n > 0) {
                list.scrollToItem(n - 1)
                list.scrollBy(1_000_000f) // to the very end of the last item
            }
        }
    }
    // Scrolled up to the top: load the older messages.
    val atTop by remember { androidx.compose.runtime.derivedStateOf { list.firstVisibleItemIndex == 0 && list.layoutInfo.totalItemsCount > 1 } }
    LaunchedEffect(atTop, canLoadMore, follow) { if (atTop && !follow && canLoadMore && rows.isNotEmpty()) onLoadOlder() }

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
                    if (entries.isNotEmpty() && canLoadMore) TextButton(onClick = onLoadOlder) { Text("Earlier messages", color = tc.faint) }
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
            items(rows, key = { r -> when (r) { is ChatRow.Day -> "d" + r.label; is ChatRow.Item -> r.e.id?.let { "i$it" } ?: "e${r.e.at}${r.e.kind}${r.e.text.hashCode()}" } }) { r ->
                when (r) {
                    is ChatRow.Day -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(r.label, style = MaterialTheme.typography.labelSmall, color = tc.faint)
                    }
                    is ChatRow.Item -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        when (r.e.kind) {
                            "request" -> UserBubble(r.e, sentFiles)
                            "answer" -> ReplyBubble(r.e, replyShots)
                            "screenshot" -> if (r.e.id != null && !curated) ShotLine(r.e, accounts, onShot, thumb) else EventLine(r.e, accounts, onOpen)
                            else -> EventLine(r.e, accounts, onOpen)
                        }
                        if (curated) Box(Modifier.padding(start = if (r.e.kind == "answer") 38.dp else 0.dp)) { rowShots(r.e) }
                    }
                }
            }
            items(pending, key = { "pending${it.atMs}" }) { p -> PendingBubble(p) }
            if (saying != null) item(key = "saying") { ReplyBubble(HistoryEntry(at = now, kind = "answer", text = saying)) }
            if (work != null) item(key = "working") { WorkingLine(work, now) }
        }
        inputBar()
    }
}

/** What you said: warm copper, on the right. */
@Composable
fun UserBubble(e: HistoryEntry, files: @Composable (HistoryEntry) -> Unit = {}) {
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
            if (e.attachments.isNotEmpty()) files(e)
            Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                if (voice) Icon(Icons.Filled.Mic, "spoken", Modifier.size(12.dp), tint = tc.onBubble.copy(alpha = 0.8f))
                Text(" " + clock(e.at), style = MaterialTheme.typography.labelSmall, color = tc.onBubble.copy(alpha = 0.85f))
            }
        }
    }
}

/** The tower's answer: a dark soft bubble with the orb beside it; its markdown rendered. */
@Composable
fun ReplyBubble(e: HistoryEntry, shots: @Composable (List<ChatModel.ShotRef>, Modifier) -> Unit = { _, _ -> }) {
    val (text, refs) = remember(e.text) { ChatModel.shots(e.text) }
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
            if (text.isNotBlank()) MarkdownText(text, tc.text)
            if (refs.isNotEmpty()) shots(refs, Modifier.padding(top = 8.dp))
            Text(clock(e.at), Modifier.align(Alignment.End), style = MaterialTheme.typography.labelSmall, color = tc.faint)
        }
    }
}

/** While the brain works: its step ("Delegating to orb-rust") and how long the turn has taken, as the panel's working line. */
@Composable
private fun WorkingLine(work: dev.herdertower.phone.api.Work, now: Long) {
    val reduce = LocalReduceMotion.current
    val (word, detail) = ChatModel.workLine(work, now)
    val elapsed = (now - work.sinceMs).coerceAtLeast(0)
    val a = if (reduce) 1f else rememberInfiniteTransition(label = "working").animateFloat(0.35f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "a").value
    Row(
        Modifier.clip(RoundedCornerShape(18.dp)).background(tc.reply).border(1.dp, tc.edge, RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 10.dp)
            .semantics { contentDescription = "The tower: ${if (detail.isEmpty()) word else "$word $detail"}, ${span(elapsed)}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OrbGlyph(Modifier.size(18.dp).alpha(a))
        Spacer(Modifier.width(10.dp))
        Text(if (detail.isEmpty()) "$word…" else word, style = MaterialTheme.typography.bodyMedium, color = tc.text, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
        if (detail.isNotEmpty()) Text(" $detail…", Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium, color = tc.text, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        if (elapsed >= 1000) Text("  · ${span(elapsed)}", style = MaterialTheme.typography.bodyMedium, color = tc.faint)
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
            "watch" -> Triple(Icons.Filled.Terminal, tc.muted, ChatModel.report(e.text))
            "alert" -> Triple(Icons.Filled.Notifications, tc.accentHi.takeIf { tc.dark } ?: tc.accent, e.text)
            "summary" -> Triple(Icons.AutoMirrored.Filled.Article, tc.muted, "$who: ${e.text}")
            // A card of screenshots a session sent (the pictures follow).
            "screenshot" -> Triple(Icons.Filled.PhotoLibrary, tc.muted, e.text.ifBlank { if (who.isEmpty()) "Screenshots" else "Screenshots from $who" })
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

/** Something sent from here, until the thread brings it back. */
@Composable
private fun PendingBubble(p: ChatModel.Pending) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(
            Modifier.padding(start = 56.dp).widthIn(max = 320.dp).alpha(0.7f)
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 24.dp, bottomEnd = 8.dp))
                .background(tc.userBubble).padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            Text(p.text, style = MaterialTheme.typography.bodyLarge, color = tc.onBubble)
            Text("sending…" + if (p.files > 0) " · ${plural(p.files, "file")}" else "", Modifier.align(Alignment.End), style = MaterialTheme.typography.labelSmall, color = tc.onBubble.copy(alpha = 0.85f))
        }
    }
}
