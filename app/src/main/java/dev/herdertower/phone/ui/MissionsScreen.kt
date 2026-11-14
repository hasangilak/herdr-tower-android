package dev.herdertower.phone.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.herdertower.phone.api.MissionsApi
import dev.herdertower.phone.api.MissionsResponse
import dev.herdertower.phone.api.Mood
import dev.herdertower.phone.data.TowerSession
import kotlinx.coroutines.launch

/** What the spaces list says, in words. */
object MissionsModel {
    /** "5 open · 7 quiet", "No spaces yet". */
    fun counts(all: MissionsResponse): String = when {
        all.spaces.isEmpty() && all.quiet.isEmpty() -> "No spaces yet"
        all.quiet.isEmpty() -> "${all.spaces.size} open"
        else -> "${all.spaces.size} open · ${all.quiet.size} quiet"
    }

}

/**
 * Spaces, each as a mission: its goal, how far it is, and which are ready
 * to close. The quiet ones (no live session) fold away under the list.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MissionsScreen(session: TowerSession, onSpace: (String) -> Unit, onNew: () -> Unit, onPlan: () -> Unit = {}) {
    val missions by session.missions.collectAsStateWithLifecycle()
    // The plan that is open, above the spaces: a plan becomes a space once launched.
    val state by session.state.collectAsStateWithLifecycle()
    val scores by session.scoresSupported.collectAsStateWithLifecycle()
    val plan = remember(state?.plan) { dev.herdertower.phone.api.PlansApi.parse(state?.plan)?.takeIf { it.status != "closed" } }
    // Each space's release track (a tower with releases): its summary under the space's name, "prod 14:20 · 1 on dev".
    val releasesOn by session.releasesSupported.collectAsStateWithLifecycle()
    val tracks = remember(state?.releases, releasesOn) { if (releasesOn) dev.herdertower.phone.api.ReleasesApi.parse(state?.releases) else emptyMap() }
    fun summary(space: String) = dev.herdertower.phone.api.ReleasesApi.of(tracks, space)?.summary?.takeIf { it.isNotBlank() }
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var quietOpen by rememberSaveable { mutableStateOf(false) }
    // In the tower's own order, as its window on the Mac lists them.
    val list = missions.spaces
    // Each Claude account's usage (a tower with accounts): over the spaces, as the Mac's sidebar has it.
    val accounts = remember(state?.accounts) { dev.herdertower.phone.api.AccountsApi.parse(state?.accounts)?.takeIf { it.list.isNotEmpty() } }
    val now = rememberNow(accounts != null)

    Column(Modifier.fillMaxSize()) {
        TopBar("Spaces", MissionsModel.counts(missions))
        PullToRefreshBox(refreshing, { refreshing = true; scope.launch { session.refreshLists(); refreshing = false } }, Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                accounts?.let { b ->
                    item(key = "accounts") {
                        val inSpaces = missions.spaces.flatMap { m -> m.sessions.map { it.address } + listOfNotNull(m.tower?.address) }.toSet()
                        val outside = dev.herdertower.phone.api.AccountsApi.outside(state?.brains.orEmpty(), state?.agents.orEmpty(), inSpaces)
                        AccountsStrip(b, now, Modifier.padding(bottom = 8.dp), outside = outside)
                    }
                }
                plan?.let { p -> item(key = "plan") { PlanRow(p, scores, onClick = onPlan) } }
                if (list.isEmpty() && missions.quiet.isEmpty() && plan == null) item(key = "none") {
                    EmptyBox("No spaces yet. Start one from a goal: the tower opens its sessions and hands the work out.", Modifier.padding(horizontal = 2.dp, vertical = 6.dp))
                }
                items(list, key = { "s:" + it.name }) { m ->
                    SignRow(
                        sign = { MoodGlyph(MissionsApi.mood(m), m.needs, m.mute) },
                        name = m.name, right = MissionsApi.progress(m), sub = MissionsApi.line(m), note = summary(m.name),
                        onClick = { onSpace(m.name) },
                    )
                }
                item(key = "new") {
                    Row(Modifier.padding(start = 2.dp, top = 12.dp, bottom = 8.dp)) { PillButton("New space", primary = true, icon = Icons.Filled.Add, onClick = onNew) }
                }
                if (missions.quiet.isNotEmpty()) {
                    item(key = "quiet") {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp))
                                .clickable(role = Role.Button, onClickLabel = if (quietOpen) "Fold the quiet spaces" else "Show the quiet spaces") { quietOpen = !quietOpen }
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("${missions.quiet.size} quiet", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = tc.muted)
                            Icon(if (quietOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null, tint = tc.faint)
                        }
                    }
                    if (quietOpen) items(missions.quiet, key = { "q:$it" }) { name ->
                        SignRow(sign = { MoodGlyph(Mood.Quiet) }, name = name, right = "quiet", sub = "", note = summary(name), onClick = { onSpace(name) })
                    }
                }
                if (missions.closedToday.isNotEmpty()) item(key = "closed") {
                    Text(
                        "Closed today: " + missions.closedToday.joinToString(", "), Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodySmall, color = tc.faint,
                    )
                }
            }
        }
    }
}
