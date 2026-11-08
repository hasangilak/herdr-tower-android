package dev.herdertower.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.herdertower.phone.api.HistoryEntry
import dev.herdertower.phone.data.Link
import dev.herdertower.phone.data.TowerSession
import kotlinx.coroutines.launch

private sealed interface ChatRow {
    data class Day(val label: String) : ChatRow
    data class Item(val e: HistoryEntry) : ChatRow
}

@Composable
fun ChatScreen(session: TowerSession) {
    val history by session.history.collectAsStateWithLifecycle()
    val state by session.state.collectAsStateWithLifecycle()
    val link by session.link.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    var text by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var loadingOlder by remember { mutableStateOf(false) }

    val rows = remember(history) {
        buildList<ChatRow> {
            var day = ""
            history.filter { it.kind != "summary" }.forEach { e ->
                val d = dayLabel(e.at)
                if (d != day) {
                    add(ChatRow.Day(d))
                    day = d
                }
                add(ChatRow.Item(e))
            }
        }
    }
    val thinking = state?.phase == "thinking" && link is Link.Live
    LaunchedEffect(rows.size, thinking) { if (rows.isNotEmpty()) list.animateScrollToItem(rows.size - 1 + (if (thinking) 1 else 0)) }

    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = list,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (history.isNotEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        TextButton(enabled = !loadingOlder, onClick = {
                            loadingOlder = true
                            scope.launch {
                                if (session.loadOlder() == 0) snack("That's everything.")
                                loadingOlder = false
                            }
                        }) { Text(if (loadingOlder) "Loading…" else "Earlier messages", color = tc.faint) }
                    }
                }
            }
            if (rows.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 120.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Say hi to the tower", style = MaterialTheme.typography.titleMedium, color = tc.text)
                        Text("Type below, or talk on the Talk tab.", style = MaterialTheme.typography.bodyMedium, color = tc.muted)
                    }
                }
            }
            items(rows, key = { r -> when (r) { is ChatRow.Day -> "d" + r.label; is ChatRow.Item -> "e${r.e.at}${r.e.kind}${r.e.text.hashCode()}" } }) { r ->
                when (r) {
                    is ChatRow.Day -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(r.label, style = MaterialTheme.typography.labelSmall, color = tc.faint)
                    }
                    is ChatRow.Item -> if (r.e.kind == "request" || r.e.kind == "answer") Bubble(r.e) else EventLine(r.e)
                }
            }
            if (thinking) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(14.dp), color = tc.cyan, strokeWidth = 2.dp)
                        Text("Thinking…", style = MaterialTheme.typography.bodyMedium, color = tc.muted, fontStyle = FontStyle.Italic)
                    }
                }
            }
        }

        // The pill
        Row(
            Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(26.dp)).background(if (tc.dark) Color.White.copy(alpha = 0.94f) else Color.White)
                .border(1.dp, tc.edge, RoundedCornerShape(26.dp)).padding(start = 4.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                text, { text = it },
                Modifier.weight(1f),
                placeholder = { Text("Ask the tower", color = Color(0xFF8A93A3)) },
                maxLines = 5,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = Color(0xFF1D2330), unfocusedTextColor = Color(0xFF1D2330), cursorColor = Color(0xFF1D2330),
                ),
            )
            val can = text.isNotBlank() && !sending && link is Link.Live
            FilledIconButton(
                onClick = {
                    val t = text.trim()
                    sending = true
                    scope.launch {
                        session.act { it.ask(t) }
                            .onSuccess { text = "" }
                            .onFailure { snack("Couldn't send: ${TowerSession.describe(it)}") }
                        sending = false
                    }
                },
                enabled = can,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF1D2330), contentColor = Color.White, disabledContainerColor = Color(0xFFD5DAE2), disabledContentColor = Color.White),
            ) {
                if (sending) CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                else Icon(Icons.AutoMirrored.Filled.Send, "Send")
            }
        }
    }
}

/** What you said (right) or the tower's answer (left). */
@Composable
fun Bubble(e: HistoryEntry) {
    val mine = e.kind == "request"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(20.dp)).background(if (mine) tc.bubbleMe else tc.bubbleTower)
                .border(1.dp, tc.edge, RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(e.text.removePrefix("[voice] "), style = MaterialTheme.typography.bodyLarge, color = tc.text)
            if (e.attachments.isNotEmpty()) {
                Text(plural(e.attachments.size, "attachment"), style = MaterialTheme.typography.labelSmall, color = tc.faint)
            }
            Text(clock(e.at), Modifier.align(Alignment.End), style = MaterialTheme.typography.labelSmall, color = tc.faint)
        }
    }
}

/** How the panel marks a line of activity: icon, colour and words. */
@Composable
fun eventLook(e: HistoryEntry): Triple<ImageVector, Color, String> {
    val who = e.name ?: e.session ?: ""
    return when (e.kind to e.status) {
        "job" to "working" -> Triple(Icons.Filled.NorthEast, tc.cyan, "Asked $who: ${e.text}")
        "job" to "done", "job" to "idle" -> Triple(Icons.Filled.CheckCircle, tc.pale, if (e.text.isEmpty()) "$who finished" else "$who finished: ${e.text}")
        "job" to "blocked" -> Triple(Icons.Filled.Warning, tc.amber, "$who needs you" + if (e.text.isEmpty()) "" else ": ${e.text}")
        else -> when (e.kind) {
            "job" -> Triple(Icons.Filled.Error, tc.rose, "$who ${statusWord(e.status ?: "")}" + if (e.text.isEmpty()) "" else ": ${e.text}")
            "watch" -> Triple(Icons.Filled.Terminal, tc.sky, e.text)
            "alert" -> Triple(Icons.Filled.Notifications, tc.amber, e.text)
            "summary" -> Triple(Icons.Filled.Article, tc.peri, "$who: ${e.text}")
            else -> Triple(Icons.Filled.Info, tc.grey, e.text)
        }
    }
}

/** A session reporting back, an alert, a summary, a job starting or ending. */
@Composable
fun EventLine(e: HistoryEntry, accounts: AccountLookup? = null, onOpen: ((String) -> Unit)? = null) {
    val (icon, color, text) = eventLook(e)
    Row(
        Modifier.fillMaxWidth().then(if (onOpen != null && e.session != null) Modifier.clip(RoundedCornerShape(8.dp)).clickableNoRipple { onOpen(e.session) } else Modifier),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(clock(e.at), Modifier.width(42.dp).padding(top = 2.dp), style = MaterialTheme.typography.labelSmall, color = tc.faint)
        Icon(icon, null, Modifier.size(15.dp).padding(top = 2.dp), tint = color)
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = if (e.held) tc.muted else tc.text, maxLines = 6)
        val acc = e.account ?: e.session?.let { accounts?.of(it) }
        if (acc != null && accounts != null) AccountBadge(acc, acc == accounts.default)
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

fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)
