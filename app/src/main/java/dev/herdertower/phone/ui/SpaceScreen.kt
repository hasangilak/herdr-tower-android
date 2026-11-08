package dev.herdertower.phone.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.herdertower.phone.data.TowerSession
import kotlinx.coroutines.launch

/** When each space was last opened here: its held news after that is new. */
private fun readPrefs(c: Context) = c.getSharedPreferences("spaces_read", Context.MODE_PRIVATE)

/** One space, as the panel's space detail: focus, agenda, sessions, what's new, jobs, screenshots, the story so far. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpaceScreen(session: TowerSession, name: String, onBack: () -> Unit, onOpen: (String) -> Unit, onShot: (String) -> Unit) {
    val context = LocalContext.current
    val spaces by session.spaces.collectAsStateWithLifecycle()
    val jobs by session.jobs.collectAsStateWithLifecycle()
    val history by session.history.collectAsStateWithLifecycle()
    val shots by session.screenshots.collectAsStateWithLifecycle()
    val acc = rememberAccounts(session)
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    val sp = spaces.spaces.firstOrNull { it.space == name }
    val focused = spaces.focus == name
    // What was held here counts as delivered once you've looked (when you leave).
    val prevRead = remember(name) { readPrefs(context).getLong(name, 0L) }
    DisposableEffect(name) { onDispose { readPrefs(context).edit().putLong(name, System.currentTimeMillis()).apply() } }
    val now = System.currentTimeMillis()

    val section: @Composable (String) -> Unit = { t ->
        Text(t.uppercase(), Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium, color = tc.faint)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())) {
        SubHeader(name, if (focused) "Focused: its news is spoken" else null, onBack)
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill(if (focused) "Focused" else "Focus this space", focused) {
                    scope.launch {
                        session.act { it.focus(if (focused) null else name) }.onSuccess { session.refreshLists() }.onFailure { snack(TowerSession.describe(it)) }
                    }
                }
                SpaceAccountBadge(sp?.sessions.orEmpty().mapNotNull { it.account ?: acc.of(it.address) }, acc.default)
            }
            Text(
                sp?.agenda?.takeIf { it.isNotBlank() } ?: "No agenda yet. Tell the tower what this space is for.",
                style = MaterialTheme.typography.bodyLarge,
                color = if (sp?.agenda.isNullOrBlank()) tc.faint else tc.text,
            )

            val sessions = sp?.sessions.orEmpty()
            if (sessions.isNotEmpty()) {
                section("Sessions")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    sessions.forEach { s ->
                        Row(
                            Modifier.clip(CircleShape).background(tc.card).clickable(role = Role.Button, onClickLabel = "Open ${s.label}") { onOpen(s.address) }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Dot(tc.status(s.status), 7.dp)
                            Text(s.label, style = MaterialTheme.typography.labelLarge, color = tc.text)
                            if (s.status.isNotEmpty()) Text(s.status, style = MaterialTheme.typography.labelMedium, color = tc.muted)
                            (s.account ?: acc.of(s.address))?.let { AccountBadge(it, it == acc.default) }
                        }
                    }
                }
            }

            val fresh = history.filter { it.held && it.space == name && it.at > prevRead }.asReversed().take(30)
            if (fresh.isNotEmpty()) {
                section("New while you were elsewhere · ${fresh.size}")
                fresh.forEach { EventLine(it, acc, onOpen) }
            }

            val mine = jobs.filter { it.space == name }
            val (running, done) = mine.partition { it.open }
            if (running.isNotEmpty()) {
                section("Running")
                running.forEach { j -> JobCard(j, now, acc, shots.filter { it.job != null && it.job == j.job }, onShot) { onOpen(j.session) } }
            }
            val spaceShots = shots.filter { it.space == name }
            if (spaceShots.isNotEmpty()) {
                section("Screenshots")
                ShotStrip(session, spaceShots.take(12)) { onShot(it.id) }
            }
            if (done.isNotEmpty()) {
                section("Done")
                done.take(8).forEach { j -> JobCard(j, now, acc, shots.filter { it.job != null && it.job == j.job }, onShot) { onOpen(j.session) } }
            }

            val story = history.filter { it.space == name && it.kind in setOf("summary", "watch", "alert", "note") && !(it.held && it.at > prevRead) }.asReversed().take(20)
            if (story.isNotEmpty()) {
                section("Story so far")
                story.forEach { EventLine(it, acc, onOpen) }
            }
            Spacer(Modifier.size(32.dp))
        }
    }
}
