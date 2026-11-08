package dev.herdertower.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.herdertower.phone.api.Agent
import dev.herdertower.phone.api.Space
import dev.herdertower.phone.api.SpaceSession
import dev.herdertower.phone.data.TowerSession
import kotlinx.coroutines.launch

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SpacesScreen(session: TowerSession, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val spaces by session.spaces.collectAsStateWithLifecycle()
    val agents by session.agents.collectAsStateWithLifecycle()
    val unreachable by session.unreachable.collectAsStateWithLifecycle()
    val acc = rememberAccounts(session)
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    var refreshing by remember { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }

    fun refresh() {
        refreshing = true
        scope.launch {
            session.refreshLists()
            session.refreshAgents()
            refreshing = false
        }
    }
    LaunchedEffect(Unit) { session.refreshAgents() }

    fun setFocus(space: String?) {
        scope.launch {
            session.act { it.focus(space) }
                .onSuccess { session.refreshLists() }
                .onFailure { snack(TowerSession.describe(it)) }
        }
    }

    // Focused first, then those with news, then the most recent.
    val sorted = spaces.spaces.sortedWith(compareBy<Space>({ !it.focus }, { -it.unread }, { -it.lastActivityMs }))

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
    SubHeader("Spaces", spaces.focus?.let { "Focused on $it" } ?: plural(spaces.spaces.size, "space"), onBack)
    PullToRefreshBox(refreshing, ::refresh, Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        spaces.focus?.let { "Focused on $it: other spaces' news waits here instead of being spoken." }
                            ?: "No focus: every space's news is spoken. Focus one to hold the rest.",
                        Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = tc.muted,
                    )
                    if (spaces.focus != null) Pill("Clear focus", false) { setFocus(null) }
                }
            }
            if (sorted.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("No spaces yet", style = MaterialTheme.typography.titleMedium, color = tc.text)
                        Text("Spaces you make in Herdr show up here, each with its sessions and news.", style = MaterialTheme.typography.bodyMedium, color = tc.muted)
                    }
                }
            }
            items(sorted, key = { "s:" + it.space }) { sp ->
                SpaceCard(sp, acc, expanded = expanded == sp.space, onToggle = { expanded = if (expanded == sp.space) null else sp.space }, onFocus = { setFocus(if (sp.focus) null else sp.space) }, onOpen = onOpen)
            }
            if (agents.isNotEmpty()) {
                item { Text("ALL SESSIONS", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium, color = tc.faint) }
                items(agents.sortedBy { order(it.status) }, key = { "a:" + it.address }) { a -> AgentRow(a, acc) { onOpen(a.address) } }
            }
            items(unreachable, key = { "u:" + it.machine }) { u ->
                Text("${u.machine ?: "A machine"} didn't answer: ${u.error}", style = MaterialTheme.typography.bodySmall, color = tc.failed)
            }
        }
    }
    }
}

private fun order(status: String?) = when (status) {
    "blocked" -> 0
    "working" -> 1
    "done", "idle" -> 2
    else -> 3
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpaceCard(sp: Space, acc: AccountLookup, expanded: Boolean, onToggle: () -> Unit, onFocus: () -> Unit, onOpen: (String) -> Unit) {
    val urgent = sp.sessions.any { it.status == "blocked" }
    val busy = sp.sessions.count { it.status == "working" }
    val (dot, mood) = when {
        urgent -> tc.blocked to "needs you"
        busy > 0 -> tc.working to "$busy working"
        else -> tc.idle to "quiet"
    }
    val meta = buildList {
        add(mood)
        add(plural(sp.sessions.size, "session"))
        if (sp.runningJobs > 0) add(plural(sp.runningJobs, "job") + " running")
        ago(sp.lastActivityMs).takeIf { it.isNotEmpty() }?.let(::add)
    }
    GlassCard(Modifier.fillMaxWidth(), highlighted = sp.focus, onClick = onToggle) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Dot(dot)
                Text(sp.space, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, color = tc.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                SpaceAccountBadge(sp.sessions.mapNotNull { it.account ?: acc.of(it.address) }, acc.default)
                if (sp.unread > 0) {
                    Text(
                        "${sp.unread} new",
                        Modifier.clip(CircleShape).background(if (urgent) tc.blocked else tc.accent).padding(horizontal = 8.dp, vertical = 1.dp),
                        style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium, color = tc.onAccent,
                    )
                }
                Pill(if (sp.focus) "Focused" else "Focus", sp.focus, onClick = onFocus)
            }
            Text(
                sp.agenda?.takeIf { it.isNotBlank() } ?: "No agenda yet",
                style = MaterialTheme.typography.bodyMedium,
                color = if (sp.agenda.isNullOrBlank()) tc.faint else tc.text,
                maxLines = if (expanded) 8 else 2, overflow = TextOverflow.Ellipsis,
            )
            Text(meta.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = tc.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (expanded && sp.sessions.isNotEmpty()) {
                FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    sp.sessions.forEach { s -> SessionChip(s, acc) { onOpen(s.address) } }
                }
            }
        }
    }
}

@Composable
private fun SessionChip(s: SpaceSession, acc: AccountLookup, onClick: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(tc.card).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Dot(tc.status(s.status), 6.dp)
        Text(s.label, style = MaterialTheme.typography.labelMedium, color = tc.text)
        if (s.status.isNotEmpty()) Text(s.status, style = MaterialTheme.typography.labelMedium, color = tc.muted)
        (s.account ?: acc.of(s.address))?.let { AccountBadge(it, it == acc.default) }
    }
}

@Composable
private fun AgentRow(a: Agent, acc: AccountLookup, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(tc.card).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Dot(tc.status(a.status))
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(a.label, style = MaterialTheme.typography.bodyLarge, color = tc.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                a.status?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = tc.status(it)) }
            }
            val sub = listOfNotNull(a.doing?.takeIf { it.isNotBlank() } ?: a.title?.takeIf { it.isNotBlank() }, a.address, a.machine?.takeIf { it != "this mac" }, a.agent).joinToString(" · ")
            Text(sub, style = MaterialTheme.typography.labelMedium, color = tc.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        (a.account ?: acc.of(a.address))?.let { AccountBadge(it, it == acc.default) }
    }
}
