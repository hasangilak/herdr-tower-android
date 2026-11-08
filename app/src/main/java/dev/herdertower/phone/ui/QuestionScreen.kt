package dev.herdertower.phone.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.herdertower.phone.api.FilePart
import dev.herdertower.phone.api.Question
import dev.herdertower.phone.api.QuestionTab
import dev.herdertower.phone.api.Recommendation
import dev.herdertower.phone.data.TowerSession
import dev.herdertower.phone.voice.Take
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A question a session asks, as a card: who asks, what, the tower's pick, its state. Opens the stepper. */
@Composable
fun QuestionCard(q: Question, onOpen: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    GlassCard(modifier.fillMaxWidth(), highlighted = true, onClick = onOpen) {
        Column(verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.HelpOutline, null, Modifier.size(18.dp), tint = tc.accentHi.takeIf { tc.dark } ?: tc.accent)
                Spacer(Modifier.width(8.dp))
                Text("${q.who} asks", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = tc.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(QuestionModel.kindWord(q), style = MaterialTheme.typography.labelMedium, color = tc.faint)
            }
            Text(q.headline, style = if (compact) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleMedium, color = tc.text, maxLines = if (compact) 2 else 3, overflow = TextOverflow.Ellipsis)
            val words = QuestionModel.statusWords(q)
            when {
                words != null -> Text(words, style = MaterialTheme.typography.bodySmall, color = if (q.status == "failed") tc.failed else tc.muted)
                q.recommendations == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = tc.muted)
                    Spacer(Modifier.width(8.dp))
                    Text("Thinking of answers…", style = MaterialTheme.typography.bodySmall, color = tc.muted)
                }
                else -> q.recommendationsFor(0).firstOrNull()?.let { r ->
                    Text("I'd say: ${r.label}", style = MaterialTheme.typography.bodySmall, color = tc.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** The questions left to the user, as cards, oldest first. */
@Composable
fun QuestionCards(questions: List<Question>, onOpen: (String) -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    if (questions.isEmpty()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        questions.forEach { q -> QuestionCard(q, { onOpen(q.id) }, compact = compact) }
    }
}

/**
 * Answering a question set: one step per question (the tower's three
 * recommendations as big one-tap answers, the dialog's own options, and
 * "Other": typed, said or with files), then a review of every answer and
 * Submit. The tower types it all into the session's dialog.
 */
@Composable
fun QuestionScreen(session: TowerSession, id: String, onClose: () -> Unit, onSession: (String) -> Unit) {
    val all by session.questions.collectAsStateWithLifecycle()
    val live = all.firstOrNull { it.id == id }
    // Once it's gone (answered, or the session moved on) the last copy is shown as done.
    var last by remember(id) { mutableStateOf(live) }
    LaunchedEffect(live) { if (live != null) last = live }
    val q = live ?: last
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    val context = LocalContext.current
    val perms = rememberPermissions()

    if (q == null) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("This question is gone.", style = MaterialTheme.typography.titleMedium, color = tc.text)
            Spacer(Modifier.height(12.dp))
            ClosePill(onClick = onClose)
        }
        return
    }
    val done = live == null || q.status == "answered"
    LaunchedEffect(done) {
        if (done) {
            delay(1600)
            onClose()
        }
    }

    var drafts by remember(id) { mutableStateOf(mapOf<Int, TabDraft>()) }
    val effective = QuestionModel.withDefaults(q, drafts)
    fun setDraft(t: Int, d: TabDraft) {
        drafts = effective + (t to d)
    }
    val steps = QuestionModel.steps(q)
    var step by rememberSaveable(id) { mutableStateOf(0) }
    val reviewing = steps > 1 && step >= q.tabs.size
    val answers = QuestionModel.answers(q, effective)
    val busy = q.status == "answering"
    var heard by remember(id) { mutableStateOf<Pair<Int, String>?>(null) }

    fun submit() {
        val a = answers ?: return
        scope.launch {
            session.answerQuestion(q.id, a).onFailure { snack("Couldn't answer: ${TowerSession.describe(it)}") }
        }
    }

    // Saying an answer: a take, then the Mac transcribes and reads it as a choice, shown before it's sent.
    var take by remember { mutableStateOf<Take?>(null) }
    var interpreting by remember { mutableStateOf(false) }
    fun say(t: Int) {
        take?.let { running ->
            scope.launch { running.stop() }
            return
        }
        perms.withMic {
            val tk = runCatching { Take.start(session.scope, endOnSilence = true) }.getOrElse {
                snack("The microphone isn't available.")
                return@withMic
            }
            take = tk
            scope.launch {
                runCatching { tk.ended.await() }
                val wav = tk.stop()
                take = null
                if (!tk.heardSpeech) return@launch snack("Didn't hear anything.")
                interpreting = true
                session.act { it.questionVoice(q.id, t, wav) }
                    .onSuccess { r ->
                        heard = t to r.heard
                        val now = effective[t] ?: TabDraft()
                        setDraft(t, QuestionModel.draftOf(r.parsed, q.tabs[t], touched = true, files = now.files))
                    }
                    .onFailure { snack("Couldn't understand it: ${TowerSession.describe(it)}") }
                interpreting = false
            }
        }
    }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        val t = step.coerceAtMost(q.tabs.lastIndex)
        scope.launch {
            val parts = withContext(Dispatchers.IO) { uris.take(6).mapNotNull { readPart(context, it) } }
            val d = effective[t] ?: TabDraft()
            setDraft(t, d.copy(files = (d.files + parts).take(6), touched = true))
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Box(Modifier.fillMaxWidth().padding(top = 12.dp, start = 16.dp, end = 8.dp, bottom = 4.dp)) {
            ClosePill(modifier = Modifier.align(Alignment.Center), onClick = onClose)
            if (!done) TextButton(onClick = {
                scope.launch { session.dismissQuestion(q.id).onSuccess { onClose() }.onFailure { snack(TowerSession.describe(it)) } }
            }, Modifier.align(Alignment.CenterEnd)) { Text("Dismiss", color = tc.muted) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${q.who} asks", Modifier.weight(1f).clickable { onSession(q.session) }, style = MaterialTheme.typography.titleMedium, color = tc.accentHi.takeIf { tc.dark } ?: tc.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(QuestionModel.kindWord(q), style = MaterialTheme.typography.labelLarge, color = tc.faint)
            }
            if (q.context.isNotBlank()) Text("Working on: ${q.context}", style = MaterialTheme.typography.bodyMedium, color = tc.muted)
            if (steps > 1) Progress(q, step, effective) { step = it }

            when {
                done -> Done()
                reviewing -> Review(q, effective) { step = it }
                else -> {
                    val t = step.coerceAtMost(q.tabs.lastIndex)
                    TabPage(
                        q, t, effective[t] ?: TabDraft(),
                        onDraft = { setDraft(t, it) },
                        heard = heard?.takeIf { it.first == t }?.second,
                        listening = take != null, interpreting = interpreting,
                        onSay = { say(t) }, onFiles = { pickFiles.launch("*/*") },
                    )
                    if (steps == 1) answers?.let { a -> Text("Your answer: " + a.first().choice.words(q.tabs.first()), style = MaterialTheme.typography.bodyMedium, color = tc.muted) }
                }
            }
            QuestionModel.statusWords(q)?.takeIf { !done }?.let { words ->
                StatusBox(words, failed = q.status == "failed", busy = busy)
                if (q.status == "failed") TextButton(onClick = { onSession(q.session) }) { Text("Open ${q.who} to answer in the terminal", color = tc.text) }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (!done) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (step > 0) BigButton("Back", primary = false) { step-- }
            Spacer(Modifier.weight(1f))
            val last = steps == 1 || reviewing
            if (last) BigButton(if (q.status == "failed") "Try again" else "Submit", enabled = answers != null && !busy) { submit() }
            else BigButton(if (step == q.tabs.lastIndex) "Review" else "Next") { step++ }
        }
    }
}

/** The steps as segments: each question, then the review; tap one to go there. */
@Composable
private fun Progress(q: Question, step: Int, drafts: Map<Int, TabDraft>, onGo: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (i in 0..q.tabs.size) {
                val answered = i < q.tabs.size && QuestionModel.choice(q.tabs[i], drafts[i] ?: TabDraft()) != null
                Box(
                    Modifier.weight(1f).height(6.dp).clip(CircleShape)
                        .background(if (i == step) tc.accent else if (answered) tc.accent.copy(alpha = 0.45f) else tc.edge)
                        .clickable(role = Role.Tab, onClickLabel = if (i == q.tabs.size) "Review" else q.tabs[i].title) { onGo(i) },
                )
            }
        }
        Text(
            if (step >= q.tabs.size) "Review your answers" else "Question ${step + 1} of ${q.tabs.size}" + q.tabs[step].title.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
            style = MaterialTheme.typography.labelLarge, color = tc.muted,
        )
    }
}

@Composable
private fun TabPage(
    q: Question, t: Int, d: TabDraft, onDraft: (TabDraft) -> Unit,
    heard: String?, listening: Boolean, interpreting: Boolean, onSay: () -> Unit, onFiles: () -> Unit,
) {
    val tab = q.tabs[t]
    Text(tab.question.ifBlank { q.title }, style = MaterialTheme.typography.headlineSmall, color = tc.text)
    if (q.detail.isNotBlank()) {
        Text(
            q.detail, Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(tc.screen).padding(12.dp).heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = tc.screenText,
        )
    }
    if (tab.multi) Text("Pick any number.", style = MaterialTheme.typography.bodySmall, color = tc.faint)

    // The tower's three answers: never waited for.
    val recs = q.recommendationsFor(t)
    if (q.recommendations == null) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { contentDescription = "The tower is thinking of answers" }) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = tc.muted)
            Spacer(Modifier.width(10.dp))
            Text("Thinking of three answers… you can answer already.", style = MaterialTheme.typography.bodyMedium, color = tc.muted)
        }
    } else recs.forEachIndexed { i, r ->
        RecommendationCard(r, recommended = i == 0, selected = QuestionModel.matches(tab, d, r)) {
            onDraft(QuestionModel.draftOf(r.parsed, tab, touched = true, files = d.files))
        }
    }

    if (tab.options.isNotEmpty()) {
        Text(if (recs.isEmpty()) "Options" else "All options", style = MaterialTheme.typography.labelLarge, color = tc.faint, modifier = Modifier.padding(top = 6.dp))
        Column(Modifier.clip(RoundedCornerShape(18.dp)).border(1.dp, tc.edge, RoundedCornerShape(18.dp))) {
            tab.options.forEachIndexed { i, o ->
                val on = i in d.picks
                Row(
                    Modifier.fillMaxWidth().clickable(role = if (tab.multi) Role.Checkbox else Role.RadioButton) { onDraft(QuestionModel.toggle(tab, d, i)) }
                        .background(if (on) tc.accent.copy(alpha = 0.12f) else Color.Transparent).padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val icon: ImageVector = if (tab.multi) (if (on) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank) else (if (on) Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked)
                    Icon(icon, null, Modifier.size(22.dp), tint = if (on) tc.accent else tc.muted)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(o.label, style = MaterialTheme.typography.bodyLarge, color = tc.text)
                        if (o.description.isNotBlank()) Text(o.description, style = MaterialTheme.typography.bodySmall, color = tc.muted)
                    }
                }
            }
        }
    }

    if (tab.allowsOther) {
        Text("Other / my own answer", style = MaterialTheme.typography.labelLarge, color = tc.faint, modifier = Modifier.padding(top = 6.dp))
        OutlinedTextField(
            d.other, { onDraft(QuestionModel.type(tab, d, it)) },
            Modifier.fillMaxWidth(), minLines = 2, maxLines = 8,
            placeholder = { Text(if (tab.multi) "Add something of your own" else "Type it, or say it", color = tc.faint) },
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = tc.text, unfocusedTextColor = tc.text, focusedBorderColor = tc.accent, unfocusedBorderColor = tc.edge, cursorColor = tc.accent),
            shape = RoundedCornerShape(18.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            SmallAction(if (listening) Icons.Filled.Stop else Icons.Filled.Mic, if (listening) "Listening… tap to stop" else "Say it", active = listening, onClick = onSay)
            Spacer(Modifier.width(10.dp))
            SmallAction(Icons.Filled.AttachFile, "Files", onClick = onFiles)
            if (interpreting) {
                Spacer(Modifier.width(10.dp))
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = tc.muted)
            }
        }
        heard?.let { Text("Heard: “$it”", style = MaterialTheme.typography.bodySmall, color = tc.muted) }
        d.files.forEachIndexed { i, f ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AttachFile, null, Modifier.size(16.dp), tint = tc.muted)
                Spacer(Modifier.width(6.dp))
                Text(f.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = tc.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Filled.Close, "Remove ${f.name}", Modifier.size(20.dp).clickable { onDraft(d.copy(files = d.files.filterIndexed { j, _ -> j != i }, touched = true)) }, tint = tc.muted)
            }
        }
        if (d.files.isNotEmpty() && d.other.isBlank()) Text("Files go with your own words: type a line about them.", style = MaterialTheme.typography.bodySmall, color = tc.faint)
    }
}

/** One of the tower's answers: big, one tap. The first is its pick. */
@Composable
private fun RecommendationCard(r: Recommendation, recommended: Boolean, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (selected) tc.accent.copy(alpha = if (tc.dark) 0.26f else 0.16f) else tc.card)
            .border(if (selected) 2.dp else 1.dp, if (selected) tc.accent else tc.edge, shape)
            .clickable(role = Role.RadioButton, onClickLabel = "Answer ${r.label}", onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(r.label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = tc.text, fontWeight = FontWeight.SemiBold)
            if (recommended) Text(
                "Recommended", Modifier.clip(CircleShape).background(tc.accent).padding(horizontal = 10.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelMedium, color = tc.onAccent,
            )
            if (selected) {
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Filled.Check, "Chosen", Modifier.size(20.dp), tint = tc.accent)
            }
        }
        if (r.why.isNotBlank()) Text(r.why, style = MaterialTheme.typography.bodyMedium, color = tc.muted)
    }
}

/** Every question with its answer; tap one to change it. */
@Composable
private fun Review(q: Question, drafts: Map<Int, TabDraft>, onEdit: (Int) -> Unit) {
    q.tabs.forEachIndexed { t, tab ->
        val c = QuestionModel.choice(tab, drafts[t] ?: TabDraft())
        GlassCard(onClick = { onEdit(t) }) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(tab.title.ifBlank { "Question ${t + 1}" }, style = MaterialTheme.typography.labelLarge, color = tc.faint)
                Text(tab.question, style = MaterialTheme.typography.bodyMedium, color = tc.muted)
                Text(c?.words(tab) ?: "Not answered yet: tap to answer", style = MaterialTheme.typography.titleMedium, color = if (c == null) tc.failed else tc.text)
                val files = drafts[t]?.files.orEmpty()
                if (c != null && files.isNotEmpty() && (drafts[t]?.other?.isNotBlank() == true)) Text("with ${plural(files.size, "file")}", style = MaterialTheme.typography.bodySmall, color = tc.muted)
            }
        }
    }
}

@Composable
private fun StatusBox(words: String, failed: Boolean, busy: Boolean) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (failed) tc.failed.copy(alpha = 0.14f) else tc.card).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = tc.accent)
            Spacer(Modifier.width(12.dp))
        }
        Text(words, style = MaterialTheme.typography.bodyMedium, color = if (failed) tc.failed else tc.text)
    }
}

@Composable
private fun Done() {
    Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(tc.done.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Check, null, Modifier.size(36.dp), tint = tc.done)
        }
        Text("Answered", style = MaterialTheme.typography.headlineSmall, color = tc.text)
    }
}

@Composable
private fun BigButton(text: String, primary: Boolean = true, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 52.dp).clip(CircleShape)
            .then(if (primary && enabled) Modifier.background(tc.accentBrush) else Modifier.border(1.dp, tc.edge, CircleShape))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = if (primary && enabled) tc.onAccent else if (enabled) tc.text else tc.faint, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SmallAction(icon: ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(if (active) tc.accent else tc.card).border(1.dp, tc.edge, CircleShape)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = if (active) tc.onAccent else tc.text)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (active) tc.onAccent else tc.text)
    }
}

/** A picked file as a part to send (up to 50 MB). */
private fun readPart(context: android.content.Context, uri: Uri): FilePart? = runCatching {
    val cr = context.contentResolver
    val name = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "file"
    val mime = cr.getType(uri) ?: "application/octet-stream"
    val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: return null
    if (bytes.size > 50 * 1024 * 1024) return null
    FilePart(name, mime, bytes)
}.getOrNull()
