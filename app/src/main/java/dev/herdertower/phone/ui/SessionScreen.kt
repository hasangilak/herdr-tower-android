package dev.herdertower.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.herdertower.phone.data.Link
import dev.herdertower.phone.data.TowerSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One session: what its terminal shows now, and Approve / Refuse when it's blocked. */
@Composable
fun SessionScreen(session: TowerSession, address: String, onBack: () -> Unit) {
    val state by session.state.collectAsStateWithLifecycle()
    val spaces by session.spaces.collectAsStateWithLifecycle()
    val agents by session.agents.collectAsStateWithLifecycle()
    val jobs by session.jobs.collectAsStateWithLifecycle()
    val history by session.history.collectAsStateWithLifecycle()
    val link by session.link.collectAsStateWithLifecycle()
    val acc = rememberAccounts(session)
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current

    val agent = agents.firstOrNull { it.address == address }
    val stateAgent = state?.agents?.firstOrNull { it.address == address }
    val spaceSession = spaces.spaces.flatMap { sp -> sp.sessions.map { sp.space to it } }.firstOrNull { it.second.address == address }
    val name = agent?.name?.takeIf { it.isNotBlank() } ?: stateAgent?.name?.takeIf { it.isNotBlank() } ?: spaceSession?.second?.name?.takeIf { it.isNotBlank() } ?: address
    val status = stateAgent?.status?.takeIf { it.isNotBlank() } ?: agent?.status ?: spaceSession?.second?.status ?: ""
    val account = agent?.account ?: acc.of(address)
    val blocked = status == "blocked" || jobs.any { it.session == address && it.status == "blocked" }

    var screen by remember { mutableStateOf<String?>(null) }
    var screenError by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    var answering by remember { mutableStateOf(false) }
    var answered by remember { mutableStateOf<String?>(null) }

    // The live screen: re-read every 2 s while this is open.
    LaunchedEffect(address, link is Link.Live) {
        if (link !is Link.Live) return@LaunchedEffect
        while (true) {
            session.act { it.screen(address, lines = 80) }
                .onSuccess { screen = it.screen; screenError = null }
                .onFailure { screenError = TowerSession.describe(it) }
            delay(2000)
        }
    }

    fun answer(approve: Boolean) {
        answering = true
        scope.launch {
            session.act { it.answer(address, approve, note) }
                .onSuccess {
                    answered = if (approve) "Asked the tower to approve." else "Asked the tower to refuse."
                    note = ""
                }
                .onFailure { snack(TowerSession.describe(it)) }
            answering = false
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SubHeader(name, null, onBack)
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Dot(tc.status(status), 7.dp)
                    Text(
                        listOfNotNull(status.ifEmpty { null }, address, spaceSession?.first, agent?.machine?.takeIf { it != "this mac" }).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium, color = tc.muted,
                    )
                    account?.let { AccountBadge(it, it == acc.default) }
                }
        agent?.doing?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = tc.text) }

        if (blocked) {
            GlassCard(Modifier.fillMaxWidth(), highlighted = true) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.Warning, null, tint = tc.blocked)
                        Text("Needs you", style = MaterialTheme.typography.titleMedium, color = tc.text)
                    }
                    Text("Read what it's asking below. The tower answers it in the session, as if you'd said so.", style = MaterialTheme.typography.bodySmall, color = tc.muted)
                    OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("Note (optional)") }, placeholder = { Text("only the read") }, maxLines = 3)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { answer(true) }, enabled = !answering, modifier = Modifier.weight(1f).height(52.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = tc.accent, contentColor = Color.White),
                        ) {
                            Icon(Icons.Filled.Check, null, Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Approve")
                        }
                        OutlinedButton(onClick = { answer(false) }, enabled = !answering, modifier = Modifier.weight(1f).height(52.dp)) {
                            Icon(Icons.Filled.Close, null, Modifier.size(18.dp), tint = tc.failed)
                            Spacer(Modifier.size(6.dp))
                            Text("Refuse", color = tc.failed)
                        }
                    }
                    answered?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = tc.working) }
                }
            }
        }

        Text("SCREEN", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium, color = tc.faint)
        Text(
            screen?.trimEnd()?.ifEmpty { "(empty)" } ?: screenError ?: "Reading…",
            Modifier.fillMaxWidth().heightIn(min = 120.dp).clip(RoundedCornerShape(18.dp)).background(tc.screen)
                .horizontalScroll(rememberScrollState()).padding(12.dp),
            fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
            color = if (screen == null && screenError != null) tc.failed else tc.screenText,
            softWrap = false,
        )

        val mine = jobs.filter { it.session == address }
        if (mine.isNotEmpty()) {
            Text("JOBS", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium, color = tc.faint)
            val now = System.currentTimeMillis()
            mine.take(5).forEach { JobCard(it, now, acc) {} }
        }
        val lines = history.filter { it.session == address }.takeLast(15).asReversed()
        if (lines.isNotEmpty()) {
            Text("RECENT", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium, color = tc.faint)
            lines.forEach { EventLine(it, acc) }
        }
        Spacer(Modifier.size(24.dp))
        }
    }
}
