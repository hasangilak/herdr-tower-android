package dev.herdertower.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.herdertower.phone.api.Behavior
import dev.herdertower.phone.api.BehaviorChange
import dev.herdertower.phone.api.BehaviorPatch
import dev.herdertower.phone.api.BehaviorsApi
import dev.herdertower.phone.api.DiffLine
import dev.herdertower.phone.data.TowerSession
import dev.herdertower.phone.voice.Take
import kotlinx.coroutines.launch

private val fieldColors @Composable get() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = tc.text, unfocusedTextColor = tc.text, focusedBorderColor = tc.accent, unfocusedBorderColor = tc.edge, cursorColor = tc.accent,
    focusedLabelColor = tc.accent, unfocusedLabelColor = tc.muted,
)

/**
 * How the tower works, in plain words: every behavior by category, each
 * with its one-line summary, where it came from and an on/off switch; tap
 * one to read or change it. Search, filters, the history (with undo), the
 * fixed core, and an input to tell the tower what to change.
 */
@Composable
fun BehaviorsScreen(session: TowerSession, onBack: () -> Unit, onOpen: (String) -> Unit, onAdd: () -> Unit) {
    val data by session.behaviors.collectAsStateWithLifecycle()
    val history by session.behaviorHistory.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("all") }
    var coreOpen by rememberSaveable { mutableStateOf(false) }
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { session.loadBehaviors() }

    val all = data?.behaviors.orEmpty()
    val shown = BehaviorsApi.filter(all, query, filter)
    fun undo() = scope.launch {
        session.changeBehavior { it.undoBehavior() }
            .onSuccess { r -> snack("Undone: ${r.change?.title ?: "the last change"}") }
            .onFailure { snack(TowerSession.describe(it)) }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        SubHeader("Behaviors", data?.let { "${it.behaviors.count { b -> b.enabled }} of ${it.behaviors.size} on" } ?: "Loading…", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "The rules the tower follows: how it talks, hands out and watches work, and what it decides for you. Tap one to read or change it, or tell the tower below.",
                style = MaterialTheme.typography.bodyMedium, color = tc.muted,
            )
            OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, null, tint = tc.muted) },
                placeholder = { Text("Search behaviors", color = tc.faint) }, colors = fieldColors, shape = CircleShape,
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("all" to "All", "on" to "On", "off" to "Off", "user" to "Yours", "learned" to "Learned", "builtin" to "Built in").forEach { (k, label) ->
                    Pill(label, filter == k) { filter = k }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionChip(Icons.Filled.Add, "Add behavior", primary = true, onClick = onAdd)
                if (BehaviorsApi.canUndo(history)) ActionChip(Icons.AutoMirrored.Filled.Undo, "Undo last change", onClick = { undo() })
            }
            if (data == null) Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = tc.muted)
                Spacer(Modifier.width(10.dp))
                Text("Reading the tower's behaviors…", color = tc.muted)
            }
            if (data != null && shown.isEmpty()) Text("Nothing matches.", style = MaterialTheme.typography.bodyMedium, color = tc.faint)
            BehaviorsApi.byCategory(shown, data?.categories.orEmpty()).forEach { (category, list) ->
                Text(category, style = MaterialTheme.typography.titleMedium, color = tc.text, modifier = Modifier.padding(top = 8.dp))
                list.forEach { b ->
                    BehaviorCard(b, onClick = { onOpen(b.id) }) { on ->
                        scope.launch { session.changeBehavior { it.updateBehavior(b.id, BehaviorPatch(enabled = on)) }.onFailure { snack(TowerSession.describe(it)) } }
                    }
                }
            }

            // What changed, newest first.
            val shownHistory = BehaviorsApi.visible(history)
            if (shownHistory.isNotEmpty()) {
                Collapsible("History", plural(shownHistory.size, "change"), historyOpen) { historyOpen = !historyOpen }
                if (historyOpen) shownHistory.forEach { c -> ChangeRow(c) { all.firstOrNull { it.id == c.id }?.let { onOpen(it.id) } } }
            }
            // The fixed part.
            data?.core?.let { core ->
                Collapsible("How the tower is wired (fixed)", "Read only: who it is, its tools, how messages reach it", coreOpen) { coreOpen = !coreOpen }
                if (coreOpen) Text(
                    core.text.trim(), Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(tc.card).border(1.dp, tc.edge, RoundedCornerShape(16.dp)).padding(14.dp),
                    style = MaterialTheme.typography.bodySmall, color = tc.muted,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        TellTheTower(session)
    }
}

/** One behavior: title, its one line, where it came from, on or off. */
@Composable
private fun BehaviorCard(b: Behavior, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(b.title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium, color = if (b.enabled) tc.text else tc.faint, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(8.dp))
                    SourceBadge(b.source)
                }
                if (b.summary.isNotBlank()) Text(b.summary, style = MaterialTheme.typography.bodyMedium, color = if (b.enabled) tc.muted else tc.faint, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(10.dp))
            Switch(b.enabled, onToggle, colors = SwitchDefaults.colors(checkedTrackColor = tc.accent, checkedThumbColor = tc.onAccent))
        }
    }
}

@Composable
fun SourceBadge(source: String) {
    val (bg, fg) = when (source) {
        "builtin" -> tc.edge to tc.muted
        "learned" -> tc.working.copy(alpha = 0.22f) to tc.text
        else -> tc.accent.copy(alpha = 0.25f) to tc.text
    }
    Text(BehaviorsApi.sourceWord(source), Modifier.clip(CircleShape).background(bg).padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = fg)
}

@Composable
private fun Collapsible(title: String, subtitle: String, open: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = onToggle).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (title.contains("fixed")) {
                    Icon(Icons.Filled.Lock, null, Modifier.size(16.dp), tint = tc.muted)
                    Spacer(Modifier.width(6.dp))
                }
                Text(title, style = MaterialTheme.typography.titleMedium, color = tc.text)
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = tc.faint)
        }
        Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, if (open) "Collapse" else "Expand", tint = tc.muted)
    }
}

@Composable
private fun ChangeRow(c: BehaviorChange, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${BehaviorsApi.verb(c.op)} ${c.title}", style = MaterialTheme.typography.bodyMedium, color = if (c.undid != null || c.op == "undo") tc.muted else tc.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOf(BehaviorsApi.byWord(c), ago(c.at)).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = tc.faint)
        }
    }
}

@Composable
private fun ActionChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.heightIn(min = 44.dp).clip(CircleShape)
            .then(if (primary) Modifier.background(tc.accentBrush) else Modifier.border(1.dp, tc.edge, CircleShape))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = if (primary) tc.onAccent else tc.text)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (primary) tc.onAccent else if (enabled) tc.text else tc.faint, fontWeight = FontWeight.SemiBold)
    }
}

/** Diff colours: readable on the dark code panel in both themes. */
private val ADDED = Color(0xFF7EE2A0)
private val REMOVED = Color(0xFFFF8A8A)

/** A diff, line by line: added in green, removed struck in red, the rest plain. */
@Composable
fun DiffView(diff: String, modifier: Modifier = Modifier, onlyChanges: Boolean = false) {
    val lines = BehaviorsApi.diff(diff)
    val rows = if (onlyChanges) BehaviorsApi.changedOnly(lines) else lines
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(tc.screen).padding(vertical = 8.dp)) {
        if (rows.isEmpty()) Text("No change to the rule's text.", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall, color = tc.screenText.copy(alpha = 0.7f))
        rows.forEach { l ->
            if (l == null) {
                Text("…", Modifier.padding(horizontal = 12.dp), color = tc.screenText.copy(alpha = 0.5f), style = MaterialTheme.typography.bodySmall)
                return@forEach
            }
            val (mark, bg, fg) = when (l.kind) {
                DiffLine.Kind.Added -> Triple("+", ADDED.copy(alpha = 0.18f), ADDED)
                DiffLine.Kind.Removed -> Triple("−", REMOVED.copy(alpha = 0.18f), REMOVED)
                DiffLine.Kind.Same -> Triple(" ", Color.Transparent, tc.screenText.copy(alpha = 0.6f))
            }
            Row(Modifier.fillMaxWidth().background(bg).padding(horizontal = 12.dp, vertical = 2.dp)) {
                Text(mark, Modifier.width(16.dp), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = fg)
                Text(
                    l.text, style = MaterialTheme.typography.bodySmall.copy(textDecoration = if (l.kind == DiffLine.Kind.Removed) androidx.compose.ui.text.style.TextDecoration.LineThrough else null),
                    color = fg,
                )
            }
        }
    }
}

/** A behavior change in the chat: what changed, its diff, and a way to the Behaviors screen. */
@Composable
fun BehaviorChangeCard(text: String, diff: String?, onOpen: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(tc.card).border(1.dp, tc.edge, RoundedCornerShape(18.dp))
            .clickable(role = Role.Button, onClickLabel = "Open behaviors", onClick = onOpen).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Tune, null, Modifier.size(18.dp), tint = tc.accentHi.takeIf { tc.dark } ?: tc.accent)
            Spacer(Modifier.width(8.dp))
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = tc.text)
        }
        if (!diff.isNullOrBlank()) DiffView(diff, onlyChanges = true)
        Text("Behaviors →", style = MaterialTheme.typography.labelLarge, color = tc.accentHi.takeIf { tc.dark } ?: tc.accent)
    }
}

/**
 * Tell the tower what to change ("add a behavior: …", "turn off the
 * screenshot one"): sent like the chat, typed or said; the brain changes
 * the behaviors and answers in one line, and the change shows up here live.
 */
@Composable
private fun TellTheTower(session: TowerSession) {
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    val perms = rememberPermissions()
    var text by rememberSaveable { mutableStateOf("") }
    var take by remember { mutableStateOf<Take?>(null) }
    var busy by remember { mutableStateOf(false) }
    val last by session.lastBehaviorChange.collectAsStateWithLifecycle()
    var sentAt by remember { mutableStateOf(0L) }

    fun send() {
        val t = text.trim()
        if (t.isEmpty() || busy) return
        busy = true
        scope.launch {
            session.act { it.ask(t) }
                .onSuccess {
                    text = ""
                    sentAt = System.currentTimeMillis()
                    session.linger()
                }
                .onFailure { snack("Couldn't send: ${TowerSession.describe(it)}") }
            busy = false
        }
    }
    fun dictate() {
        take?.let { running -> scope.launch { running.stop() }; return }
        perms.withMic {
            val tk = runCatching { Take.start(session.scope, endOnSilence = true) }.getOrElse { snack("The microphone isn't available."); return@withMic }
            take = tk
            scope.launch {
                runCatching { tk.ended.await() }
                val wav = tk.stop()
                take = null
                if (!tk.heardSpeech) return@launch
                busy = true
                session.act { it.transcribe(wav) }.onSuccess { r -> if (r.heard.isNotBlank()) text = (text.trim() + " " + r.heard).trim() }
                    .onFailure { snack("Couldn't transcribe: ${TowerSession.describe(it)}") }
                busy = false
            }
        }
    }

    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
        // What the tower did with it, as soon as the change comes in.
        last?.takeIf { sentAt > 0 && it.at >= sentAt - 5_000 }?.let { c ->
            Text("${BehaviorsApi.verb(c.op)} ${c.title}", Modifier.padding(start = 8.dp, bottom = 6.dp), style = MaterialTheme.typography.bodySmall, color = tc.done)
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(tc.cardStrong).border(1.dp, tc.edge, RoundedCornerShape(26.dp)).padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).padding(vertical = 8.dp)) {
                if (text.isEmpty()) Text(if (take != null) "Listening…" else "Tell the tower what to change…", color = tc.faint, style = MaterialTheme.typography.bodyLarge)
                androidx.compose.foundation.text.BasicTextField(
                    text, { text = it }, Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodyLarge.copy(color = tc.text), maxLines = 4,
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(tc.accent),
                )
            }
            RoundIcon(if (take != null) Icons.Filled.Stop else Icons.Filled.Mic, if (take != null) "Stop dictating" else "Dictate", size = 40.dp, onClick = { dictate() })
            Spacer(Modifier.width(6.dp))
            if (busy) Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = tc.accent) }
            else RoundIcon(Icons.AutoMirrored.Filled.Send, "Send to the tower", size = 44.dp, onClick = { send() })
        }
    }
}

/**
 * One behavior: its full rule, on/off, Edit (with the diff shown before
 * saving), Delete and Undo. With [id] null it's a new one (Add).
 */
@Composable
fun BehaviorScreen(session: TowerSession, id: String?, onBack: () -> Unit) {
    val data by session.behaviors.collectAsStateWithLifecycle()
    val history by session.behaviorHistory.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    LaunchedEffect(Unit) { if (data == null) session.loadBehaviors() }
    val b = id?.let { i -> data?.behaviors?.firstOrNull { it.id == i } }
    val categories = data?.categories?.ifEmpty { null } ?: BehaviorsApi.CATEGORIES

    var editing by rememberSaveable(id) { mutableStateOf(id == null) }
    var title by rememberSaveable(id, b?.updatedMs) { mutableStateOf(b?.title.orEmpty()) }
    var category by rememberSaveable(id, b?.updatedMs) { mutableStateOf(b?.category ?: "Misc") }
    var summary by rememberSaveable(id, b?.updatedMs) { mutableStateOf(b?.summary.orEmpty()) }
    var text by rememberSaveable(id, b?.updatedMs) { mutableStateOf(b?.text.orEmpty()) }
    var preview by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    fun review() {
        if (title.isBlank() || text.isBlank()) return snack("A behavior needs a title and its rule.")
        if (b == null) {
            preview = text.trim().lines().joinToString("\n") { "+ $it" } // all new
            return
        }
        busy = true
        scope.launch {
            session.act { it.previewBehavior(b.id, text.trim()) }.onSuccess { preview = it.diff }.onFailure { snack(TowerSession.describe(it)) }
            busy = false
        }
    }
    fun save() {
        busy = true
        scope.launch {
            val r = if (b == null) session.changeBehavior { it.addBehavior(title.trim(), category, summary.trim(), text.trim()) }
            else session.changeBehavior { c ->
                c.updateBehavior(
                    b.id,
                    BehaviorPatch(
                        title = title.trim().takeIf { it != b.title }, category = category.takeIf { it != b.category },
                        summary = summary.trim().takeIf { it != b.summary }, text = text.trim().takeIf { it != b.text.trim() },
                    ),
                )
            }
            r.onSuccess {
                preview = null
                editing = false
                snack(if (b == null) "Added: ${title.trim()}" else "Saved")
                if (b == null) onBack()
            }.onFailure { snack(TowerSession.describe(it)) }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        SubHeader(if (b == null) "Add a behavior" else b.title, b?.let { "${it.category} · ${BehaviorsApi.sourceWord(it.source)}" }, onBack)
        if (id != null && b == null) {
            Text(if (data == null) "Loading…" else "This behavior is gone (removed, or undone).", Modifier.padding(20.dp), color = tc.muted)
            return@Column
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (!editing && b != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SourceBadge(b.source)
                    Spacer(Modifier.weight(1f))
                    Text(if (b.enabled) "On" else "Off", style = MaterialTheme.typography.labelLarge, color = tc.muted)
                    Spacer(Modifier.width(8.dp))
                    Switch(b.enabled, { on -> scope.launch { session.changeBehavior { it.updateBehavior(b.id, BehaviorPatch(enabled = on)) }.onFailure { e -> snack(TowerSession.describe(e)) } } }, colors = SwitchDefaults.colors(checkedTrackColor = tc.accent, checkedThumbColor = tc.onAccent))
                }
                if (b.summary.isNotBlank()) Text(b.summary, style = MaterialTheme.typography.titleMedium, color = tc.text)
                Text("The rule, as the tower reads it", style = MaterialTheme.typography.labelLarge, color = tc.faint)
                Text(b.text.trim(), Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(tc.card).border(1.dp, tc.edge, RoundedCornerShape(16.dp)).padding(14.dp), style = MaterialTheme.typography.bodyMedium, color = tc.text)
                Text("Changed ${ago(b.updatedMs)}", style = MaterialTheme.typography.bodySmall, color = tc.faint)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionChip(Icons.Filled.Tune, "Edit", primary = true) { editing = true }
                    ActionChip(Icons.Filled.Stop, if (b.enabled) "Disable" else "Enable") {
                        scope.launch { session.changeBehavior { it.updateBehavior(b.id, BehaviorPatch(enabled = !b.enabled)) }.onFailure { e -> snack(TowerSession.describe(e)) } }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick = { confirmDelete = true }) { Text("Delete…", color = tc.failed) }
                    if (BehaviorsApi.canUndo(history) && BehaviorsApi.undoTarget(history)?.id == b.id) TextButton(onClick = {
                        scope.launch { session.changeBehavior { it.undoBehavior() }.onSuccess { snack("Undone") }.onFailure { e -> snack(TowerSession.describe(e)) } }
                    }) { Text("Undo the last change", color = tc.text) }
                }
            } else {
                OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Title") }, singleLine = true, colors = fieldColors, shape = RoundedCornerShape(16.dp))
                CategoryPicker(category, categories) { category = it }
                OutlinedTextField(summary, { summary = it }, Modifier.fillMaxWidth(), label = { Text("In one line (for you)") }, maxLines = 3, colors = fieldColors, shape = RoundedCornerShape(16.dp))
                OutlinedTextField(text, { text = it; preview = null }, Modifier.fillMaxWidth().heightIn(min = 160.dp), label = { Text("The rule (what the tower reads)") }, colors = fieldColors, shape = RoundedCornerShape(16.dp))
                preview?.let { d ->
                    Text("What changes", style = MaterialTheme.typography.labelLarge, color = tc.faint)
                    DiffView(d)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (editing) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                preview = null
                if (b == null) onBack() else {
                    editing = false
                    title = b.title; category = b.category; summary = b.summary; text = b.text
                }
            }) { Text("Cancel", color = tc.text) }
            Spacer(Modifier.weight(1f))
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = tc.accent)
            else if (preview == null) ActionChip(Icons.Filled.Search, "Review changes", primary = true) { review() }
            else ActionChip(Icons.Filled.Add, if (b == null) "Add it" else "Save", primary = true) { save() }
        }
    }
    if (confirmDelete && b != null) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete “${b.title}”?") },
        text = { Text("The tower stops following it. You can undo this right after.") },
        confirmButton = {
            TextButton(onClick = {
                confirmDelete = false
                scope.launch { session.changeBehavior { it.removeBehavior(b.id) }.onSuccess { snack("Deleted ${b.title}"); onBack() }.onFailure { e -> snack(TowerSession.describe(e)) } }
            }) { Text("Delete", color = tc.failed) }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep it") } },
    )
}

@Composable
private fun CategoryPicker(value: String, categories: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, tc.edge, RoundedCornerShape(16.dp)).clickable(role = Role.DropdownList) { open = true }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Category", style = MaterialTheme.typography.labelSmall, color = tc.muted)
                Text(value, style = MaterialTheme.typography.bodyLarge, color = tc.text)
            }
            Icon(Icons.Filled.ExpandMore, null, tint = tc.muted)
        }
        DropdownMenu(open, { open = false }) {
            categories.forEach { c -> DropdownMenuItem(text = { Text(c) }, onClick = { open = false; onPick(c) }) }
        }
    }
}
