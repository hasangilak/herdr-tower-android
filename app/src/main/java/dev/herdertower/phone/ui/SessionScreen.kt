package dev.herdertower.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DesktopMac
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.herdertower.phone.api.MissionSession
import dev.herdertower.phone.data.Link
import dev.herdertower.phone.data.TowerSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One session: what needs you there, its last words, what its terminal
 * shows now, and Show on Mac, which brings it to the front in Herdr.
 */
@Composable
fun SessionScreen(session: TowerSession, address: String, onBack: () -> Unit, onShot: (String) -> Unit = {}, onQuestion: (String) -> Unit = {}, onSession: (String) -> Unit = {}) {
    val missions by session.missions.collectAsStateWithLifecycle()
    val decisions by session.decisions.collectAsStateWithLifecycle()
    val state by session.state.collectAsStateWithLifecycle()
    val agents by session.agents.collectAsStateWithLifecycle()
    val jobs by session.jobs.collectAsStateWithLifecycle()
    val history by session.history.collectAsStateWithLifecycle()
    val link by session.link.collectAsStateWithLifecycle()
    val questionsOn by session.questionsSupported.collectAsStateWithLifecycle()
    val snack = LocalSnack.current
    val host = rememberDecisionHost(session)

    val mission = missions.spaces.firstOrNull { m -> m.sessions.any { it.address == address } }
    val stateAgent = state?.agents?.firstOrNull { it.address == address }
    val agent = agents.firstOrNull { it.address == address }
    // What the phone knows of it: its space's row first, the state's agents and the agents list for what that lacks.
    val s = (mission?.sessions?.firstOrNull { it.address == address } ?: MissionSession(address = address)).let { ms ->
        ms.copy(
            name = ms.name.ifBlank { stateAgent?.name?.takeIf { it.isNotBlank() } ?: agent?.name.orEmpty() },
            status = stateAgent?.status?.takeIf { it.isNotBlank() } ?: ms.status.ifBlank { agent?.status.orEmpty() },
        )
    }
    val mine = decisions.filter { d -> d.live && d.sessions.any { it.address == address } }
    val said = SessionModel.said(s, history, jobs).ifBlank { agent?.doing.orEmpty() }
    val now = rememberNow(mine.isNotEmpty())

    var screen by remember { mutableStateOf<String?>(null) }
    var screenError by remember { mutableStateOf<String?>(null) }
    // The live screen: re-read every 2 s while this is open and on screen (with the phone as the voice,
    // the stream stays live in the background; the terminal needn't be fetched then).
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    // collectAsState, not ...WithLifecycle: that one stops collecting below STARTED and would never see it stop.
    val started by lifecycle.currentStateFlow.collectAsState()
    val visible = started.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
    LaunchedEffect(address, link is Link.Live, visible) {
        if (link !is Link.Live || !visible) return@LaunchedEffect
        while (true) {
            session.act { it.screen(address, lines = 80) }
                .onSuccess { screen = it.screen; screenError = null }
                .onFailure { screenError = TowerSession.describe(it) }
            delay(2000)
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(listOfNotNull(mission?.name, s.label).joinToString(" › "), SessionModel.line(s).ifBlank { address }, onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            mine.forEach { d ->
                if (d.open) DecisionCard(d, now, host, onQuestion, onSession, showSpace = false) else DecisionDone(d)
            }
            // A tower from before question sets: a blocked session is answered here, with a yes or a no.
            if (!questionsOn && s.status == "blocked" && mine.isEmpty()) LegacyApproval(session, address)

            if (said.isNotBlank()) {
                SectionLabel("Last said")
                Text(said, Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodyLarge, color = tc.muted)
            }
            Text(
                screen?.trimEnd()?.ifEmpty { "(empty)" } ?: screenError ?: "Reading its screen…",
                Modifier.fillMaxWidth().heightIn(min = 120.dp).clip(RoundedCornerShape(18.dp)).background(tc.screen)
                    .horizontalScroll(rememberScrollState()).padding(12.dp),
                fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
                color = if (screen == null && screenError != null) tc.failed else tc.screenText,
                softWrap = false,
            )
            Row {
                PillButton("Show on Mac", primary = true, icon = Icons.Filled.DesktopMac) { showInHerdr(session, address, s.label, snack) }
            }

            val shots by session.screenshots.collectAsStateWithLifecycle()
            val myShots = shots.filter { it.session == address }
            if (myShots.isNotEmpty()) {
                SectionLabel("Shots")
                ShotStrip(session, myShots.take(12)) { onShot(it.id) }
            }
        }
    }
    host.Dialogs()
}

/** Approve or refuse what a blocked session asks, with a note: how a tower without question sets is answered. */
@Composable
private fun LegacyApproval(session: TowerSession, address: String) {
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<String?>(null) }
    fun answer(approve: Boolean) {
        busy = true
        scope.launch {
            session.act { it.answer(address, approve, note) }
                .onSuccess {
                    session.linger()
                    done = if (approve) "Asked the tower to approve." else "Asked the tower to refuse."
                    note = ""
                }
                .onFailure { snack(TowerSession.describe(it)) }
            busy = false
        }
    }
    Card(strong = true) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("It waits for you", style = MaterialTheme.typography.titleLarge, color = tc.text, fontWeight = FontWeight.SemiBold)
            Text("Read what it asks on its screen below. The tower answers it in the session, as if you'd said so.", style = MaterialTheme.typography.bodyMedium, color = tc.muted)
            OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("Note (optional)") }, maxLines = 3)
            BigAnswer("Approve", primary = true, enabled = !busy) { answer(true) }
            BigAnswer("Refuse", primary = false, enabled = !busy) { answer(false) }
            done?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = tc.muted) }
        }
    }
}
