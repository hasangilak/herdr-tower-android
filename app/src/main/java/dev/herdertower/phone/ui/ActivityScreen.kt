package dev.herdertower.phone.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.herdertower.phone.api.Job
import dev.herdertower.phone.api.Screenshot
import dev.herdertower.phone.data.TowerSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Filter { Working, Done, All, Shots }

/** What Activity is narrowed to: everything, a space or a session (the panel's scope chips). */
private data class Scope(val label: String, val space: String? = null, val session: String? = null) {
    fun fits(space: String?, session: String?) = when {
        this.space != null -> space == this.space
        this.session != null -> session == this.session
        else -> true
    }
}

@Composable
fun ActivityScreen(session: TowerSession, onBack: () -> Unit, onOpen: (String) -> Unit, onShot: (String) -> Unit = {}) {
    val jobs by session.jobs.collectAsStateWithLifecycle()
    val history by session.history.collectAsStateWithLifecycle()
    val spaces by session.spaces.collectAsStateWithLifecycle()
    val shots by session.screenshots.collectAsStateWithLifecycle()
    val acc = rememberAccounts(session)
    var filter by rememberSaveable { mutableStateOf(Filter.Working) }
    var scopeKey by rememberSaveable { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }

    // All sessions, each space, then the sessions that have done something.
    val targets = remember(spaces, jobs) {
        buildList {
            add(Scope("All sessions"))
            spaces.spaces.forEach { add(Scope(it.space, space = it.space)) }
            jobs.map { it.session to it.name.ifBlank { it.session } }.distinctBy { it.first }.take(12).forEach { (a, n) -> add(Scope(n, session = a)) }
        }
    }
    val current = targets.firstOrNull { key(it) == scopeKey } ?: targets.first()

    val working = jobs.filter { it.open && current.fits(it.space, it.session) }
    val done = jobs.filter { !it.open && current.fits(it.space, it.session) }
    val gallery = shots.filter { current.fits(it.space, it.session) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        SubHeader("Activity", if (working.isEmpty()) "Nothing running" else "${working.size} running", onBack)
        Segments(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
            Segment("Working · ${working.size}", filter == Filter.Working) { filter = Filter.Working }
            Segment("Done · ${done.size}", filter == Filter.Done) { filter = Filter.Done }
            Segment("All", filter == Filter.All) { filter = Filter.All }
            if (gallery.isNotEmpty() || filter == Filter.Shots) Segment("Shots · ${gallery.size}", filter == Filter.Shots) { filter = Filter.Shots }
        }
        if (targets.size > 1) {
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(targets, key = { key(it) }) { t -> Pill(t.label, t == current) { scopeKey = key(t) } }
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (filter) {
                Filter.Working, Filter.Done -> {
                    val list = if (filter == Filter.Working) working else done
                    if (list.isEmpty()) item {
                        Empty(
                            if (filter == Filter.Working) "No work running" else "Nothing finished yet",
                            if (filter == Filter.Working) "When the tower hands a session a job, it shows here with how long it's been going." else "Finished jobs land here with what came of them.",
                        )
                    }
                    items(list, key = { it.job.ifEmpty { "${it.session}${it.startedMs}" } }) { j ->
                        JobCard(j, now, acc, shots.filter { it.job != null && it.job == j.job }, onShot) { onOpen(j.session) }
                    }
                }
                Filter.All -> {
                    val lines = history.filter { it.kind != "request" && it.kind != "answer" && it.session != null && current.fits(it.space, it.session) }.asReversed()
                    if (lines.isEmpty()) item { Empty("No activity yet", "Jobs, session reports and alerts show up here as they happen.") }
                    var day = ""
                    lines.forEach { e ->
                        val d = dayLabel(e.at)
                        if (d != day) {
                            day = d
                            item(key = "day$d") { Text(d, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, color = tc.faint) }
                        }
                        item(key = "h${e.at}${e.kind}${e.text.hashCode()}") { EventLine(e, acc, onOpen) }
                    }
                    if (history.isNotEmpty()) item {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            TextButton(enabled = !loading, onClick = {
                                loading = true
                                scope.launch {
                                    session.loadOlder()
                                    loading = false
                                }
                            }) { Text(if (loading) "Loading…" else "Earlier", color = tc.faint) }
                        }
                    }
                }
                Filter.Shots -> {
                    if (gallery.isEmpty()) item { Empty("No screenshots yet", "When a session shows you what it made, it lands here.") }
                    items(gallery.chunked(2), key = { it.first().id }) { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { s ->
                                Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).clickable(role = Role.Button) { onShot(s.id) }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Thumb(session, s, Modifier.fillMaxWidth().aspectRatio(0.8f))
                                    Text(s.caption, Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = tc.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(listOf(s.who, ago(s.at, now)).filter { it.isNotBlank() }.joinToString(" · "), Modifier.padding(horizontal = 4.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = tc.muted, maxLines = 1)
                                }
                            }
                            if (row.size == 1) Box(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

private fun key(s: Scope) = "${s.space.orEmpty()}|${s.session.orEmpty()}"

@Composable
private fun Empty(title: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(top = 80.dp, start = 24.dp, end = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = tc.text)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = tc.muted)
    }
}

/** A job, as the panel's job card: mark, what was asked, summary, how long, who, account, and its screenshots small. */
@Composable
fun JobCard(j: Job, now: Long, acc: AccountLookup, shots: List<Screenshot> = emptyList(), onShot: (String) -> Unit = {}, onClick: () -> Unit) {
    val who = j.name.ifEmpty { j.session }
    val color = tc.status(j.status)
    val whenText = when (j.status) {
        "working" -> "running ${span(now - j.startedMs)}"
        "blocked" -> "waiting for you · ${span(now - j.startedMs)}"
        else -> "${statusWord(j.status)} ${ago(j.updatedMs, now)} · took ${span(j.updatedMs - j.startedMs)}"
    }
    GlassCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.width(16.dp), contentAlignment = Alignment.Center) {
                    when (j.status) {
                        "working" -> Dot(color)
                        "blocked" -> Icon(Icons.Filled.Warning, null, Modifier.size(16.dp), tint = color)
                        "done", "idle" -> Icon(Icons.Filled.CheckCircle, null, Modifier.size(16.dp), tint = color)
                        else -> Icon(Icons.Filled.Error, null, Modifier.size(16.dp), tint = color)
                    }
                }
                Text(j.asked.ifEmpty { "Work in $who" }, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = tc.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (j.summary.isNotEmpty()) Text(j.summary, Modifier.padding(start = 24.dp), style = MaterialTheme.typography.bodyMedium, color = tc.text, maxLines = 6, overflow = TextOverflow.Ellipsis)
            if (shots.isNotEmpty()) {
                Row(Modifier.padding(start = 24.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    shots.take(4).forEach { s -> SmallShot(s, onShot) }
                }
            }
            Row(Modifier.padding(start = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(whenText, style = MaterialTheme.typography.labelMedium, color = color)
                Text(listOf(who, j.space).filter { it.isNotEmpty() }.joinToString(" · "), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = tc.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                (j.account ?: acc.of(j.session))?.let { AccountBadge(it, it == acc.default) }
            }
        }
    }
}

/** A job's screenshot, small on its card. */
@Composable
private fun SmallShot(s: Screenshot, onShot: (String) -> Unit) {
    val session = androidx.compose.ui.platform.LocalContext.current.let { (it.applicationContext as dev.herdertower.phone.TowerApplication).session }
    Thumb(session, s, Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClickLabel = "Open screenshot") { onShot(s.id) }, maxSide = 200)
}
