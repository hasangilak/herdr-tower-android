package dev.herdertower.phone.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.herdertower.phone.api.HistoryEntry
import dev.herdertower.phone.data.TowerSession
import kotlinx.coroutines.launch

/** One line of the record: when, what kind of thing, where and who, and the words. */
data class RecordRow(
    val key: String,
    val at: Long,
    val tag: String,
    val tone: RecordModel.Tone,
    val space: String,
    val who: String,
    val text: String,
    val session: String?,
    /** A screenshot's id, when the line is one. */
    val shot: String?,
)

/** The history as the record shows it: everything the tower did and heard, by space. */
object RecordModel {
    enum class Tone { Done, Talk, You, Yours, Quiet, Bad }

    /** The rows, newest first. [nameOf] gives a session's name for its address. */
    fun rows(history: List<HistoryEntry>, nameOf: (String) -> String?): List<RecordRow> {
        val keys = LazyKeys()
        return history.asReversed().mapNotNull { e ->
            val (tag, tone) = kind(e)
            val who = who(e, nameOf)
            val text = when (e.kind) {
                "request" -> e.text.removePrefix("[voice] ")
                "answer" -> ChatModel.preview(e.text)
                "watch" -> ChatModel.preview(ChatModel.report(e.text))
                "screenshot" -> e.caption ?: e.text
                "answered" -> e.text.removePrefix("answered for you: ")
                else -> ChatModel.preview(e.text)
            }.trim()
            if (text.isEmpty() && e.kind != "screenshot") return@mapNotNull null
            RecordRow(keys.unique(historyKey(e)), e.at, tag, tone, e.space.orEmpty(), who, text.ifEmpty { "A screenshot" }, e.session, e.id.takeIf { e.kind == "screenshot" })
        }
    }

    /** What kind of line it is, as a label, and how it looks. */
    fun kind(e: HistoryEntry): Pair<String, Tone> = when (e.kind) {
        "request" -> "You asked" to Tone.You
        "answer" -> "The tower" to Tone.Talk
        "receipt" -> "Receipt" to Tone.Talk
        "job" -> when (e.status) {
            "working" -> "Handed out" to Tone.Quiet
            "done", "idle" -> "Finished" to Tone.Done
            "blocked" -> "Waits" to Tone.Yours
            else -> "Failed" to Tone.Bad
        }
        "watch" -> "Finished" to Tone.Done
        "summary" -> "Summary" to Tone.Quiet
        "alert" -> "Alert" to Tone.Yours
        "answered" -> "Answered for you" to Tone.Quiet
        "screenshot" -> "Screenshots" to Tone.Quiet
        "peer" -> "Between sessions" to Tone.Talk
        "note" -> "Note" to Tone.Quiet
        "pr" -> "Pull request" to Tone.Quiet
        "behavior" -> "Behavior" to Tone.Quiet
        "decision", "raise" -> "Decision" to Tone.Yours
        else -> e.kind.replace('_', ' ').replaceFirstChar { it.uppercase() }.ifBlank { "Note" } to Tone.Quiet
    }

    /** Who a line is about: "you", "tower", a session, or for a message between two "mediaroom → eyzflow". */
    fun who(e: HistoryEntry, nameOf: (String) -> String?): String {
        fun name(address: String?, given: String?) = given?.takeIf { it.isNotBlank() } ?: address?.let { nameOf(it) ?: it }.orEmpty()
        return when (e.kind) {
            "request" -> "you"
            "answer", "receipt", "behavior" -> "tower"
            "peer" -> "${name(e.from, e.fromName)} → ${name(e.session, e.toName ?: e.name)}"
            else -> name(e.session, e.name).ifBlank { "tower" }
        }
    }

    /** The spaces that have lines, in the order they last had one. */
    fun spaces(rows: List<RecordRow>): List<String> = rows.map { it.space }.filter { it.isNotBlank() }.distinct()

    /** "sandbox › mediaroom", or only who when the line belongs to no space. */
    fun where(r: RecordRow): String = if (r.space.isBlank()) r.who else "${r.space} › ${r.who}"
}

/**
 * The record: everything the tower did, by space, newest first. Nothing
 * here asks for anything: it is read when you want it.
 */
@Composable
fun RecordScreen(session: TowerSession, onSession: (String) -> Unit, onShot: (String) -> Unit, onChat: () -> Unit) {
    val history by session.history.collectAsStateWithLifecycle()
    val state by session.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    var filter by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    val names = remember(state) { state?.agents.orEmpty().associate { it.address to it.name } }
    val all = remember(history, names) { RecordModel.rows(history) { names[it]?.takeIf { n -> n.isNotBlank() } } }
    val spaces = remember(all) { RecordModel.spaces(all) }
    val rows = if (filter.isEmpty() || filter !in spaces) all else all.filter { it.space == filter }

    Column(Modifier.fillMaxSize()) {
        TopBar("Record", if (filter.isEmpty() || filter !in spaces) "All spaces" else filter)
        if (spaces.isNotEmpty()) LazyRow(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "all") { Toggle("All spaces", filter.isEmpty() || filter !in spaces) { filter = "" } }
            items(spaces, key = { it }) { sp -> Toggle(sp, filter == sp) { filter = sp } }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 16.dp)) {
            item(key = "chat") {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = onChat).padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Your conversation with the tower", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = tc.text)
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = tc.faint)
                }
            }
            if (rows.isEmpty()) item(key = "none") { EmptyBox("Nothing in the record yet.", Modifier.padding(top = 8.dp)) }
            var day = ""
            val keys = LazyKeys()
            rows.forEach { r ->
                val d = dayLabel(r.at)
                if (d != day) {
                    day = d
                    item(key = keys.unique("day$d")) { SectionLabel(d, Modifier.padding(top = 8.dp, bottom = 2.dp)) }
                }
                item(key = keys.unique(r.key)) {
                    RecordLine(r, onClick = when {
                        r.shot != null -> ({ onShot(r.shot) })
                        r.session != null -> ({ onSession(r.session) })
                        else -> null
                    })
                }
            }
            if (history.isNotEmpty()) item(key = "earlier") {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextButton(enabled = !loading, onClick = {
                        loading = true
                        scope.launch {
                            if (session.loadOlder() == 0) snack("That's everything.")
                            loading = false
                        }
                    }) { Text(if (loading) "Reading…" else "Earlier", color = tc.muted) }
                }
            }
        }
    }
}

@Composable
private fun RecordLine(r: RecordRow, onClick: (() -> Unit)?) {
    val edge = tc.edge
    val tone: Color = when (r.tone) {
        RecordModel.Tone.Done -> tc.live
        RecordModel.Tone.Talk -> tc.muted
        RecordModel.Tone.You -> tc.text
        RecordModel.Tone.Yours -> tc.decisionInk
        RecordModel.Tone.Quiet -> tc.faint
        RecordModel.Tone.Bad -> tc.failed
    }
    Row(
        Modifier.fillMaxWidth()
            .drawBehind { drawLine(edge, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1f) }
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 4.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(clock(r.at), Modifier.width(44.dp).padding(top = 1.dp), style = MaterialTheme.typography.labelMedium, color = tc.faint, maxLines = 1)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tag(r.tag, tone)
                Text(RecordModel.where(r), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = tc.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(r.text, style = MaterialTheme.typography.bodyMedium, color = tc.text, maxLines = 6, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A history line's list key: what the history keeps it apart by (time, kind, session, words). */
internal fun historyKey(e: HistoryEntry) = "h${e.at}|${e.kind}|${e.session.orEmpty()}|${e.text.hashCode()}"

/** Keys for one lazy list, each used once: a repeat gets a suffix (a duplicate key crashes the list). */
internal class LazyKeys {
    private val seen = HashSet<String>()

    fun unique(key: String): String {
        if (seen.add(key)) return key
        var n = 2
        while (!seen.add("$key#$n")) n++
        return "$key#$n"
    }
}
