package dev.herdertower.phone.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.herdertower.phone.api.Job
import dev.herdertower.phone.api.SpacesResponse
import dev.herdertower.phone.api.StateAgent
import dev.herdertower.phone.data.TowerSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A session at work right now: working, or blocked on the user. */
data class Active(
    val address: String,
    val name: String,
    val space: String,
    val account: String?,
    val status: String,
    /** When its open job began, if it has one. */
    val sinceMs: Long?,
    /** What it was asked (its open job), if known. */
    val doing: String,
) {
    val blocked: Boolean get() = status == "blocked"
}

/** "Active now", as the Mac panel's row: which sessions, in what order. */
object ActiveModel {
    /** The tower's test sessions: never shown. */
    fun isTest(name: String): Boolean = name.trim().lowercase().let { it.startsWith("qfix") || it.startsWith("tower.test") }

    /** Working or blocked sessions from the state's agents (never test ones): blocked first, then the longest running. */
    fun active(agents: List<StateAgent>, jobs: List<Job>, spaces: SpacesResponse?): List<Active> =
        agents.filter { it.status == "working" || it.status == "blocked" }.mapNotNull { a ->
            val name = a.name.ifBlank { a.address }
            if (isTest(name)) return@mapNotNull null
            val job = jobs.filter { it.session == a.address && it.open }.maxByOrNull { it.startedMs }
            val space = spaces?.spaces?.firstOrNull { sp -> sp.sessions.any { it.address == a.address } }?.space ?: job?.space.orEmpty()
            Active(a.address, name, space, a.account ?: job?.account, a.status, job?.startedMs?.takeIf { it > 0 }, job?.asked.orEmpty())
        }.sortedWith(compareBy<Active>({ !it.blocked }, { it.sinceMs ?: Long.MAX_VALUE }))

    /** With a focus: its space's sessions first, the others after, dimmed (true). */
    fun focusFirst(list: List<Active>, focus: String?): List<Pair<Active, Boolean>> {
        if (focus == null) return list.map { it to false }
        val (mine, rest) = list.partition { it.space == focus }
        return mine.map { it to false } + rest.map { it to true }
    }

    /** What a screen reader says for a chip. */
    fun describe(a: Active, now: Long): String = buildString {
        append(a.name)
        append(if (a.blocked) ", needs you" else ", working")
        if (a.space.isNotBlank()) append(", in ${a.space}")
        a.sinceMs?.let { append(", for ${span(now - it)}") }
        if (a.doing.isNotBlank()) append(": ${a.doing}")
    }
}

/**
 * The sessions at work right now, as one sideways row of chips: a soft
 * pulse while working, the alert colour when it needs you, its space,
 * account and how long it has run. Tap: the session. Long press: open it,
 * or show it in Herdr on the Mac.
 */
/** The active sessions, live, in the strip's order (with whether each is dimmed by the focus). */
@Composable
fun activeSessions(session: TowerSession): List<Pair<Active, Boolean>> {
    val state by session.state.collectAsStateWithLifecycle()
    val jobs by session.jobs.collectAsStateWithLifecycle()
    val spaces by session.spaces.collectAsStateWithLifecycle()
    return ActiveModel.focusFirst(ActiveModel.active(state?.agents.orEmpty(), jobs, spaces), spaces.focus)
}

/** How tall the strip is: a row of chips, or the quiet line. */
fun activeNowHeight(count: Int) = if (count > 0) 84.dp else 50.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ActiveNow(session: TowerSession, onOpen: (String) -> Unit, modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp)) {
    val spaces by session.spaces.collectAsStateWithLifecycle()
    val accounts by session.accounts.collectAsStateWithLifecycle()
    val snack = LocalSnack.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            now = System.currentTimeMillis()
        }
    }
    val list = activeSessions(session)
    val reduce = LocalReduceMotion.current
    val pulse = if (reduce) 1f else rememberInfiniteTransition(label = "active").animateFloat(0.45f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "pulse").value

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.padding(contentPadding).padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Active now", style = MaterialTheme.typography.labelLarge, color = tc.faint)
            if (list.isNotEmpty()) Text("  ${list.size}", style = MaterialTheme.typography.labelLarge, color = tc.faint)
            spaces.focus?.let { Text("  ·  ${it} first", style = MaterialTheme.typography.labelMedium, color = tc.faint) }
        }
        if (list.isEmpty()) {
            Text("Nothing running", Modifier.padding(contentPadding), style = MaterialTheme.typography.bodyMedium, color = tc.faint)
            return@Column
        }
        // A new first chip (a focus, someone now blocked) is shown, not left scrolled out of view.
        val row = androidx.compose.foundation.lazy.rememberLazyListState()
        LaunchedEffect(list.first().first.address) { row.animateScrollToItem(0) }
        LazyRow(state = row, contentPadding = contentPadding, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.first.address }) { (a, dim) ->
                var menu by remember { mutableStateOf(false) }
                Box {
                    Row(
                        Modifier.alpha(if (dim) 0.5f else 1f).heightIn(min = 48.dp).widthIn(max = 240.dp).clip(RoundedCornerShape(16.dp))
                            .background(tc.card).border(1.dp, if (a.blocked) tc.blocked.copy(alpha = 0.7f) else tc.edge, RoundedCornerShape(16.dp))
                            .combinedClickable(onClickLabel = "Open ${a.name}", onLongClickLabel = "More for ${a.name}", onLongClick = { menu = true }) { onOpen(a.address) }
                            .semantics { contentDescription = ActiveModel.describe(a, now) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(9.dp).alpha(if (a.blocked) 1f else pulse).clip(CircleShape).background(if (a.blocked) tc.blocked else tc.working))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(a.name, style = MaterialTheme.typography.bodyMedium, color = tc.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                a.account?.let {
                                    Spacer(Modifier.width(6.dp))
                                    AccountBadge(it, it == accounts.default)
                                }
                            }
                            val sub = listOfNotNull(if (a.blocked) "needs you" else null, a.space.ifBlank { null }, a.sinceMs?.let { span(now - it) }).joinToString(" · ")
                            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.labelMedium, color = if (a.blocked) tc.blocked else tc.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Open ${a.name}") }, onClick = { menu = false; onOpen(a.address) })
                        DropdownMenuItem(text = { Text("Show it in Herdr") }, onClick = {
                            menu = false
                            showInHerdr(session, a.address, a.name, snack)
                        })
                    }
                }
            }
        }
    }
}

/** Brings a session to the front in Herdr on the Mac (`tower show`). */
fun showInHerdr(session: TowerSession, address: String, name: String, snack: (String) -> Unit) {
    session.scope.launch {
        session.act { it.showSession(address) }
            .onSuccess { snack("Showing $name in Herdr") }
            .onFailure { snack("Couldn't show it: ${TowerSession.describe(it)}") }
    }
}
