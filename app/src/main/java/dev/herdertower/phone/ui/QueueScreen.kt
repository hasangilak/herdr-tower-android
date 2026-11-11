package dev.herdertower.phone.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.herdertower.phone.api.ChatMessage
import dev.herdertower.phone.api.HistoryEntry
import dev.herdertower.phone.api.MissionsApi
import dev.herdertower.phone.api.Work
import dev.herdertower.phone.data.Link
import dev.herdertower.phone.data.TowerSession
import dev.herdertower.phone.tower
import kotlinx.coroutines.delay

/**
 * Your queue: where the app opens. One decision open with large answers,
 * the others waiting behind it in the tower's order; above them the reply
 * to what you last said or typed. Nothing else is here.
 */
@Composable
fun QueueScreen(
    session: TowerSession,
    onQuestion: (String) -> Unit,
    onSession: (String) -> Unit,
    onChat: () -> Unit,
    onRepair: () -> Unit,
    /** A decision to open (its notification was tapped), and that it was. */
    open: String? = null,
    onOpened: () -> Unit = {},
) {
    val decisions by session.decisions.collectAsStateWithLifecycle()
    val missions by session.missions.collectAsStateWithLifecycle()
    val link by session.link.collectAsStateWithLifecycle()
    val host = rememberDecisionHost(session)
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(open) {
        if (open != null) {
            picked = open
            onOpened()
        }
    }
    val now = rememberNow(decisions.isNotEmpty())
    val reach = link is Link.Live
    val waiting = decisions.filter { it.open }
    val busy = decisions.filter { it.state == "answering" || it.state == "answered" }
    val (focus, tag) = DecisionModel.focus(waiting, picked)
    val rest = waiting.filter { it.id != focus?.id }
    val (need, line) = DecisionModel.status(waiting.size, MissionsApi.working(missions), reach)
    val list = rememberLazyListState()
    // A decision opened from further down (or from its notification) is brought into view.
    LaunchedEffect(focus?.id) { if (focus != null && picked == focus.id) list.animateScrollToItem(0) }

    Column(Modifier.fillMaxSize()) {
        TopBar("Your queue", line, strong = need)
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 0.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // One item for both: when neither shows, nothing is left of them (no gap above the first card).
            item(key = "head") {
                Column(Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ConnectionNotice(link, session, onRepair)
                    ReplyCard(session, space = null, onChat = onChat)
                    RuleOffers(session)
                }
            }
            items(busy, key = { "b" + it.id }) { DecisionDone(it) }
            if (focus != null) item(key = "open") {
                DecisionCard(focus, now, host, onQuestion, onSession, tag = tag)
            }
            if (waiting.isEmpty() && busy.isEmpty()) item(key = "empty") {
                EmptyBox(if (reach || decisions.isNotEmpty()) "Nothing needs you." else "Nothing needs you, as far as the phone knows: the Mac is out of reach.")
            }
            if (rest.isNotEmpty()) {
                item(key = "more") { SectionLabel("${rest.size} more waiting") }
                items(rest, key = { "r" + it.id }) { d -> DecisionRow(d, now, onClick = { picked = d.id }) }
            }
        }
    }
    host.Dialogs()
}

/** The clock for "2 min": moving once every half minute while something shows a wait. */
@Composable
fun rememberNow(ticking: Boolean): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(ticking) {
        while (ticking) {
            now = System.currentTimeMillis()
            delay(30_000)
        }
    }
    return now
}

/** The last thing said to a tower and what it answered, for the card above the queue (or a space's own). */
object ReplyModel {
    data class Exchange(
        /** What the user said or typed; null when the tower spoke first (a receipt). */
        val asked: String?,
        val askedAt: Long,
        /** The tower's answer, or null while it works on one. */
        val reply: String?,
        val at: Long,
        /** What the tower opened for it without asking (a command's receipt), with what it assumed. */
        val act: dev.herdertower.phone.api.Act? = null,
    )

    private fun scoped(m: ChatMessage, space: String?) = if (space == null) dev.herdertower.phone.api.ChatApi.inMain(m) else dev.herdertower.phone.api.ChatApi.inSpace(m, space)

    /**
     * The latest exchange with the main tower ([space] null) or with a
     * space's own: the last thing the user said and the tower's first words
     * after it (its reply). What the tower says by itself is not shown,
     * unless it sends [receipts] (its level is "results", or the space is
     * followed): then its latest such line is, alone, as the receipt it is.
     * A tower that still tells everything would otherwise fill the queue
     * with its reports.
     */
    fun last(thread: List<ChatMessage>, space: String?, receipts: Boolean = false): Exchange? {
        val i = thread.indexOfLast { it.mine && scoped(it, space) }
        val mine = thread.getOrNull(i)
        // The thread is in the tower's order: what comes after is later.
        val after = thread.drop(i + 1).filter { it.fromTower && scoped(it, space) }
        // A command's act comes right after it, then the tower's own words.
        val act = if (mine != null) after.firstOrNull { it.kind == "act" } else null
        val told = after.filter { it.kind != "act" }
        val reply = if (mine != null) told.firstOrNull() else null
        val receipt = if (receipts) told.lastOrNull()?.takeIf { it !== reply } else null
        return when {
            receipt != null -> Exchange(null, 0, receipt.text, receipt.at)
            mine != null -> Exchange(words(mine.text, space), mine.at, reply?.text, maxOf(reply?.at ?: mine.at, act?.at ?: 0), act?.act)
            else -> null
        }
    }

    /** The same from the history, for a tower without the thread (it has no spaces of its own, and no receipts either). */
    fun lastOf(history: List<HistoryEntry>): Exchange? {
        val i = history.indexOfLast { it.kind == "request" }
        if (i < 0) return null
        val mine = history[i]
        val said = history.drop(i + 1).firstOrNull { it.kind == "answer" }
        return Exchange(words(mine.text), mine.at, said?.text, said?.at ?: mine.at)
    }

    /** A long reply, cut for the card at a word: the whole of it is in the conversation. */
    fun brief(text: String, max: Int = BRIEF): String {
        if (text.length <= max) return text
        val cut = text.take(max)
        val at = cut.lastIndexOfAny(charArrayOf(' ', '\n'))
        return (if (at > max * 2 / 3) cut.take(at) else cut).trimEnd().trimEnd(',', ';', ':') + "…"
    }

    private fun words(text: String, space: String? = null) = if (space != null) dev.herdertower.phone.api.ChatApi.bare(text, space) else text.removePrefix("[voice] ")

    /** Whether the card shows: not closed since, and fresh (an old reply is in the conversation, not on the queue). */
    fun shows(x: Exchange?, closedAt: Long, now: Long, freshMs: Long = FRESH_MS): Boolean =
        x != null && x.at > closedAt && now - x.at < freshMs

    /** "You, to the main tower", "You, to the sandbox tower". */
    fun who(space: String?): String = if (space == null) "You, to the main tower" else "You, to the $space tower"

    /** How long a reply stays on the queue; after that the queue is the queue again, and the reply is in the conversation. */
    const val FRESH_MS = 10 * 60_000L
    /** About what a card holds without pushing the first decision off the screen. */
    const val BRIEF = 360
}

/**
 * The reply to what you last said or typed here: your words, then the
 * tower's answer (or what it is doing about it). It goes when you close it
 * or after a while; the whole conversation is one tap away.
 */
@Composable
fun ReplyCard(
    session: TowerSession, space: String?, onChat: () -> Unit, modifier: Modifier = Modifier,
    /** The space is followed: its results reach the user as they land, whatever the tower's level. */
    followed: Boolean = false,
) {
    val mainThread by session.thread.collectAsStateWithLifecycle()
    val spaceChats by session.spaceChats.collectAsStateWithLifecycle()
    // A space's own conversation is read when its view opens; the main one is always there.
    LaunchedEffect(space) { if (space != null) session.loadSpaceChat(space) }
    val thread = if (space == null) mainThread else spaceChats[space].orEmpty()
    val threadOn by session.threadSupported.collectAsStateWithLifecycle()
    val history by session.history.collectAsStateWithLifecycle()
    val state by session.state.collectAsStateWithLifecycle()
    val link by session.link.collectAsStateWithLifecycle()
    val pending by LocalContext.current.tower.composer.pending.collectAsStateWithLifecycle()
    val tell by session.tell.collectAsStateWithLifecycle()
    val acts by session.acts.collectAsStateWithLifecycle()
    var closedAt by rememberSaveable(space) { mutableStateOf(0L) }
    val sent = pending.lastOrNull { it.space == space }
    val exchange = if (threadOn == true) ReplyModel.last(thread, space, receipts = tell == "results" || followed) else if (space == null) ReplyModel.lastOf(history) else null
    // Just sent from here, and not back from the tower yet: shown as on its way.
    val shown = if (sent != null && (exchange == null || exchange.askedAt < sent.atMs - 10_000)) ReplyModel.Exchange(sent.text, sent.atMs, null, sent.atMs) else exchange
    val busy = shown != null && shown.reply == null
    val now = rememberNow(shown != null)
    if (!ReplyModel.shows(shown, closedAt, now) || shown == null) return
    // The main tower's working line; a space's own tower doesn't say what it does.
    val work: Work? = state?.work?.takeIf { space == null && busy && link is Link.Live && now - it.sinceMs < 30 * 60_000 }
    val saying = state?.takeIf { work != null && it.said.isNotBlank() && it.saidMs >= work.sinceMs }?.said
    // The working line counts its seconds.
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val working = work != null
    LaunchedEffect(working) {
        while (working) {
            tick = System.currentTimeMillis()
            delay(1000)
        }
    }
    Card(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (shown.asked != null) {
                        Text(ReplyModel.who(space), style = MaterialTheme.typography.labelMedium, color = tc.faint)
                        Text(shown.asked, style = MaterialTheme.typography.bodyMedium, color = tc.muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    } else {
                        Text(if (space == null) "The tower" else "The $space tower", style = MaterialTheme.typography.labelMedium, color = tc.faint)
                    }
                }
                Box(
                    Modifier.size(36.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Close this reply") { closedAt = System.currentTimeMillis() }
                        .semantics { contentDescription = "Close this reply" },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Close, null, Modifier.size(18.dp), tint = tc.muted) }
            }
            // A command's receipt: what was opened, what was assumed, and Undo.
            shown.act?.let { a -> ActCard(acts[a.id] ?: a, session, framed = false) }
            val reply = shown.reply ?: saying
            when {
                shown.act != null && reply == null -> {}
                reply != null -> MarkdownText(ReplyModel.brief(ChatModel.shots(reply).first), tc.text)
                work != null -> WorkingLine(work, tick)
                else -> Text(if (link is Link.Live) "On it…" else "Sent. The Mac is out of reach: the reply shows when it's back.", style = MaterialTheme.typography.bodyMedium, color = tc.muted)
            }
            TextButton(onClick = onChat, modifier = Modifier.heightIn(min = 44.dp)) { Text("The whole conversation", color = tc.muted) }
        }
    }
}
