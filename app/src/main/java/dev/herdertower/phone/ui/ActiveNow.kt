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
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    /** Working or blocked sessions from the state's agents (never test ones), each once: blocked first, then the longest running. */
    fun active(agents: List<StateAgent>, jobs: List<Job>, spaces: SpacesResponse?): List<Active> =
        agents.filter { (it.status == "working" || it.status == "blocked") && it.address.isNotBlank() }.distinctBy { it.address }.mapNotNull { a ->
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
    val agents = state?.agents.orEmpty()
    return remember(agents, jobs, spaces) { ActiveModel.focusFirst(ActiveModel.active(agents, jobs, spaces), spaces.focus) }
}

/**
 * The sessions at work right now, inline in the header: one slim pill each
 * (a dot that pulses, amber when it needs you; the name; the account as a
 * letter; how long it has run), scrolling sideways with faded edges. Tap:
 * the session. Long press: open it, or show it in Herdr on the Mac. Nothing
 * when nothing runs.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ActivePills(session: TowerSession, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val list = activeSessions(session)
    if (list.isEmpty()) return
    val snack = LocalSnack.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            now = System.currentTimeMillis()
        }
    }
    val reduce = LocalReduceMotion.current
    // Read only while drawing the dot, so the pulse redraws the dots, not the whole row, every frame.
    val pulse: State<Float>? = if (reduce) null else rememberInfiniteTransition(label = "active").animateFloat(0.4f, 1f, infiniteRepeatable(tween(1300), RepeatMode.Reverse), label = "pulse")
    // A new first pill (a focus, someone now blocked) is shown, not left scrolled out of view.
    val row = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(list.first().first.address) { row.animateScrollToItem(0) }
    val fadeStart = row.canScrollBackward
    val fadeEnd = row.canScrollForward
    LazyRow(
        modifier
            .semantics { contentDescription = "Active now: ${list.size} ${if (list.size == 1) "session" else "sessions"}" }
            .edgeFade(fadeStart, fadeEnd),
        state = row,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(list, key = { it.first.address }) { (a, dim) ->
            var menu by remember { mutableStateOf(false) }
            val press = remember { MutableInteractionSource() }
            // A 48 dp tall touch target round the slim pill; the ripple stays on the pill.
            Box(
                Modifier.heightIn(min = 48.dp)
                    .combinedClickable(interactionSource = press, indication = null, onClickLabel = "Open ${a.name}", onLongClickLabel = "More for ${a.name}", onLongClick = { menu = true }) { onOpen(a.address) }
                    .semantics { contentDescription = ActiveModel.describe(a, now) },
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    Modifier.alpha(if (dim) 0.5f else 1f).heightIn(min = 34.dp).clip(CircleShape)
                        .background(tc.card).border(1.dp, if (a.blocked) tc.blocked.copy(alpha = 0.75f) else tc.edge, CircleShape)
                        .indication(press, ripple())
                        .padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).graphicsLayer { alpha = pulse?.value ?: 1f }.clip(CircleShape).background(if (a.blocked) tc.blocked else tc.working))
                    Spacer(Modifier.width(6.dp))
                    Text(a.name, Modifier.widthIn(max = 120.dp), style = MaterialTheme.typography.labelLarge, color = tc.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    a.account?.takeIf { it.isNotBlank() }?.let { acc ->
                        Spacer(Modifier.width(5.dp))
                        Box(Modifier.size(16.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color(0xFF000000 or AccountHue.of(acc))), contentAlignment = Alignment.Center) {
                            Text(acc.take(1).uppercase(), style = MaterialTheme.typography.labelSmall.copy(fontSize = with(androidx.compose.ui.platform.LocalDensity.current) { (9f / fontScale).sp }, lineHeight = with(androidx.compose.ui.platform.LocalDensity.current) { (10f / fontScale).sp }), color = androidx.compose.ui.graphics.Color.Black, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                    a.sinceMs?.let {
                        Spacer(Modifier.width(6.dp))
                        Text(span(now - it), style = MaterialTheme.typography.labelMedium, color = if (a.blocked) tc.blocked else tc.muted, maxLines = 1)
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

/** Fades the content out at the start and/or end (where more scrolls in). */
fun Modifier.edgeFade(start: Boolean, end: Boolean, width: androidx.compose.ui.unit.Dp = 18.dp): Modifier =
    if (!start && !end) this else this
        .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val w = width.toPx()
            val clear = androidx.compose.ui.graphics.Color.Transparent
            val solid = androidx.compose.ui.graphics.Color.Black
            if (start) drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(clear, solid), 0f, w), size = androidx.compose.ui.geometry.Size(w, size.height), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
            if (end) drawRect(
                androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(solid, clear), size.width - w, size.width),
                topLeft = androidx.compose.ui.geometry.Offset(size.width - w, 0f), size = androidx.compose.ui.geometry.Size(w, size.height),
                blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
            )
        }

/** Brings a session to the front in Herdr on the Mac (`tower show`). */
fun showInHerdr(session: TowerSession, address: String, name: String, snack: (String) -> Unit) {
    session.scope.launch {
        session.act { it.showSession(address) }
            .onSuccess { snack("Showing $name in Herdr") }
            .onFailure { snack("Couldn't show it: ${TowerSession.describe(it)}") }
    }
}
