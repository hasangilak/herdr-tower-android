package dev.herdertower.phone.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Workspaces
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.herdertower.phone.data.Link
import dev.herdertower.phone.data.TowerSession
import dev.herdertower.phone.voice.VoiceController
import kotlinx.coroutines.launch

/** Shows a short message at the bottom (errors from actions). */
val LocalSnack = staticCompositionLocalOf<(String) -> Unit> { {} }

enum class Tab(val label: String) { Talk("Talk"), Chat("Chat"), Spaces("Spaces"), Activity("Activity") }

@Composable
fun MainScreen(session: TowerSession, voice: VoiceController, theme: String, onTheme: (String) -> Unit, onRepair: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.Talk) }
    var openSession by rememberSaveable { mutableStateOf<String?>(null) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val show: (String) -> Unit = { msg -> scope.launch { snack.showSnackbar(msg) } }

    val spaces by session.spaces.collectAsStateWithLifecycle()
    val jobs by session.jobs.collectAsStateWithLifecycle()
    val held = spaces.spaces.sumOf { it.unread }
    val running = jobs.count { it.open }

    BackHandler(enabled = openSession != null) { openSession = null }

    CompositionLocalProvider(LocalSnack provides show) {
        Scaffold(
            containerColor = Color.Transparent,
            modifier = Modifier.background(tc.glass),
            snackbarHost = { SnackbarHost(snack) },
            topBar = { Header(session, theme, onTheme, onRepair) },
            bottomBar = {
                if (openSession == null) {
                    NavigationBar(containerColor = if (tc.dark) Color(0xFF10172A) else Color(0xFFF7F9FC)) {
                        Tab.entries.forEach { t ->
                            val count = when (t) {
                                Tab.Spaces -> held
                                Tab.Activity -> running
                                else -> 0
                            }
                            NavigationBarItem(
                                selected = tab == t,
                                onClick = { tab = t },
                                label = { Text(t.label) },
                                colors = NavigationBarItemDefaults.colors(indicatorColor = tc.pale, selectedIconColor = NAVY),
                                icon = {
                                    BadgedBox(badge = { if (count > 0) Badge(containerColor = tc.cyan, contentColor = NAVY) { Text("$count") } }) {
                                        Icon(
                                            when (t) {
                                                Tab.Talk -> Icons.Filled.GraphicEq
                                                Tab.Chat -> Icons.Filled.Forum
                                                Tab.Spaces -> Icons.Filled.Workspaces
                                                Tab.Activity -> Icons.Filled.Timeline
                                            },
                                            contentDescription = t.label,
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            },
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) {
                val addr = openSession
                if (addr != null) {
                    SessionScreen(session, addr, onBack = { openSession = null })
                } else {
                    when (tab) {
                        Tab.Talk -> TalkScreen(session, voice)
                        Tab.Chat -> ChatScreen(session)
                        Tab.Spaces -> SpacesScreen(session, onOpen = { openSession = it })
                        Tab.Activity -> ActivityScreen(session, onOpen = { openSession = it })
                    }
                }
            }
        }
    }
}

/** "Tower", then what it's doing and how many sessions are busy, as the panel's header; the link on the right. */
@Composable
private fun Header(session: TowerSession, theme: String, onTheme: (String) -> Unit, onRepair: () -> Unit) {
    val state by session.state.collectAsStateWithLifecycle()
    val link by session.link.collectAsStateWithLifecycle()
    val spaces by session.spaces.collectAsStateWithLifecycle()
    val live = link is Link.Live
    val s = state
    val (word, dot) = phaseWord(if (live && s != null) s.phase else null)
    var line = if (live) word else ""
    if (live && s != null) {
        val n = s.agents.size
        val busy = s.agents.count { it.status == "working" }
        when {
            n == 0 -> {}
            busy == 0 -> line += " · ${plural(n, "session")}"
            else -> line += " · $busy of $n busy"
        }
        spaces.focus?.let { line += " · Focus: $it" }
    }

    Column(Modifier.statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Tower", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium, color = tc.text)
                if (line.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Dot(dot, 7.dp)
                        Text(line, style = MaterialTheme.typography.bodyMedium, color = tc.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            LinkChip(link) { session.reconnect() }
            Menu(session, s?.mode, theme, onTheme, onRepair)
        }
        Banner(link, session, onRepair)
    }
}

@Composable
private fun LinkChip(link: Link, onRetry: () -> Unit) {
    val (text, color) = when (link) {
        is Link.Live -> (if (link.voice) "Voice" else "Live") to tc.cyan
        is Link.Connecting -> "Connecting" to tc.sky
        is Link.Retrying -> "Retry ${link.seconds}s" to tc.amber
        is Link.Revoked, is Link.CertificateChanged -> "Unpaired" to tc.rose
        Link.Idle -> "Paused" to tc.grey
        Link.Unpaired -> "Unpaired" to tc.grey
    }
    Row(
        Modifier.clip(CircleShape).background(color.copy(alpha = 0.18f)).clickable(onClick = onRetry).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Dot(color, 7.dp)
        Text(text, style = MaterialTheme.typography.labelMedium, color = tc.text)
    }
}

/** Says clearly what's wrong with the connection, and what to do. */
@Composable
private fun Banner(link: Link, session: TowerSession, onRepair: () -> Unit) {
    val (msg, action) = when (link) {
        is Link.Retrying -> "${link.reason} Trying again in ${link.seconds} s." to ("Retry now" to { session.reconnect() })
        is Link.Revoked -> "This phone isn't paired any more (${link.message}). Pair it again: `tower phone pair` on the Mac." to ("Pair again" to onRepair)
        is Link.CertificateChanged -> "The tower's certificate isn't the one this phone paired with. If you remade it on the Mac, pair again; otherwise someone may be in between." to ("Pair again" to onRepair)
        else -> return
    }
    val color = if (link is Link.Retrying) tc.amber else tc.rose
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.16f)).padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(msg, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = tc.text)
        TextButton(onClick = action.second) { Text(action.first, color = if (tc.dark) color else tc.text) }
    }
}

@Composable
private fun Menu(session: TowerSession, mode: String?, theme: String, onTheme: (String) -> Unit, onRepair: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    var confirmUnpair by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    val creds by session.credentials.collectAsStateWithLifecycle()

    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "Menu", tint = tc.text) }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            Text("Mac microphone", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, color = tc.faint)
            listOf("ptt" to "Press to talk", "handsfree" to "Hands-free", "off" to "Off").forEach { (m, label) ->
                DropdownMenuItem(
                    text = { Text((if (mode == m) "● " else "   ") + label) },
                    onClick = {
                        open = false
                        scope.launch { session.act { it.mode(m) }.onFailure { e -> snack(TowerSession.describe(e)) } }
                    },
                )
            }
            HorizontalDivider()
            Text("Theme", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, color = tc.faint)
            listOf("system" to "Follow the phone", "dark" to "Dark", "light" to "Light").forEach { (t, label) ->
                DropdownMenuItem(text = { Text((if (theme == t) "● " else "   ") + label) }, onClick = { open = false; onTheme(t) })
            }
            HorizontalDivider()
            creds?.let {
                Text("${it.deviceName} · ${it.host}:${it.port}", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall, color = tc.faint)
            }
            DropdownMenuItem(text = { Text("Pair again…") }, onClick = { open = false; onRepair() })
            DropdownMenuItem(text = { Text("Unpair this phone", color = tc.rose) }, onClick = { open = false; confirmUnpair = true })
        }
    }
    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text("Unpair this phone?") },
            text = { Text("The tower revokes this phone's token, and the app forgets it. You can pair again with `tower phone pair`.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnpair = false
                    scope.launch { session.unpair(tellTower = true) }
                }) { Text("Unpair", color = tc.rose) }
            },
            dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text("Cancel") } },
        )
    }
}
