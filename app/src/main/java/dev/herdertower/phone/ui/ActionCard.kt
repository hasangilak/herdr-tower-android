package dev.herdertower.phone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.herdertower.phone.api.Assumed
import dev.herdertower.phone.api.CloseReceipt
import dev.herdertower.phone.api.LaunchPlan
import dev.herdertower.phone.api.LaunchSession
import dev.herdertower.phone.api.TowerAction
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** What a launch's plan and a close's receipt say, in words (the cards and the tests read these). */
object ActionModel {
    /** "orca · the setup's", "fix-login · the goal's first two words": what the tower chose, and why. */
    fun assumed(a: Assumed): List<String> = listOfNotNull(
        a.name?.takeIf { it.isNotBlank() }?.let { "Named $it" + (a.nameWhy?.let { w -> ": $w" } ?: "") },
        a.setup?.takeIf { it.isNotBlank() }?.let { "Setup $it" + (a.setupWhy?.let { w -> ": $w" } ?: "") },
        a.account?.takeIf { it.isNotBlank() }?.let { "Account $it" + (a.accountWhy?.let { w -> ": $w" } ?: "") },
        a.worktrees?.takeIf { it }?.let { "Worktrees" + (a.worktreesWhy?.let { w -> ": $w" } ?: "") },
    )

    /** A launch's session as a row: where it is now. */
    fun session(s: LaunchSession, planned: Boolean): Pair<String, String> {
        val where = listOfNotNull(s.branch?.takeIf { it.isNotBlank() }, s.port?.takeIf { it > 0 }?.let { "port $it" }).joinToString(" · ")
        val state = when {
            s.error != null -> "couldn't open: ${s.error}"
            s.opened -> "opened as ${s.address}"
            planned -> "to open"
            else -> "opening…"
        }
        return s.name.ifBlank { s.folder } to listOf(state, where).filter { it.isNotBlank() }.joinToString(" · ")
    }

    /** The heading of a launch: "Launching fix-login…", "Launched fix-login", "fix-login didn't launch". */
    fun launchTitle(a: TowerAction, plan: LaunchPlan?): String {
        val name = plan?.space?.ifBlank { null } ?: a.space
        return when (a.state) {
            "done" -> if (plan?.made == false) "Joined $name" else "Launched $name"
            "failed" -> "$name didn't launch"
            else -> "Launching $name…"
        }
    }

    /** A close's receipt in lines: what ended, what was removed, and what was kept and why (that's the part to read). */
    fun closed(r: CloseReceipt): List<String> = buildList {
        if (r.sessionsEnded.isNotEmpty()) add("Ended ${plural(r.sessionsEnded.size, "session")}: " + r.sessionsEnded.joinToString(", ") { it.label })
        r.sessionsLeft.forEach { add("Left running: ${it.name.ifBlank { it.address }}" + if (it.why.isNotBlank()) " (${it.why})" else "") }
        if (r.worktreesRemoved.isNotEmpty()) add("Removed ${plural(r.worktreesRemoved.size, "worktree")}")
        r.keptWorktrees.forEach { add("Kept ${it.folder}" + (it.branch?.let { b -> " on $b" } ?: "") + if (it.why.isNotBlank()) ": ${it.why}" else "") }
        if (r.towerEnded.isNotEmpty()) add("Its tower ended")
    }
}

/**
 * A launch or a close as it goes: each session of a launch as it opens (or
 * why it couldn't), what the tower assumed; a close's receipt when it is
 * done, with the worktrees it kept and why.
 */
@Composable
fun ActionCard(a: TowerAction, modifier: Modifier = Modifier) {
    Card(modifier, strong = a.running) {
        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val plan = a.launch
            val title = if (a.kind == "launch") ActionModel.launchTitle(a, plan) else when (a.state) {
                "done" -> "Closed ${a.space}"
                "failed" -> "${a.space} wasn't closed"
                else -> "Closing ${a.space}…"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                StateSign(a.state)
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, color = tc.text, fontWeight = FontWeight.SemiBold)
            }
            a.error?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = tc.failed) }
            if (plan != null) {
                plan.sessions.forEach { s ->
                    val (name, line) = ActionModel.session(s, planned = a.receipt == null && !a.running)
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(name, style = MaterialTheme.typography.bodyLarge, color = tc.text)
                        Text(line, style = MaterialTheme.typography.bodySmall, color = if (s.error != null) tc.failed else tc.muted)
                    }
                }
                if (a.state == "done") ActionModel.assumed(plan.assumed).takeIf { it.isNotEmpty() }?.let { lines ->
                    Text("Assumed", style = MaterialTheme.typography.labelLarge, color = tc.faint)
                    lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = tc.muted) }
                }
            }
            a.closed?.let { r ->
                ActionModel.closed(r).forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodyMedium, color = if (line.startsWith("Kept") || line.startsWith("Left")) tc.decisionInk else tc.muted)
                }
                r.report.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = tc.text) }
            }
        }
    }
}

/** What launching would do, before it is done: the space's name, a session per repo, and what the tower assumed. */
@Composable
fun PlanCard(plan: LaunchPlan, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (plan.made) "Opens ${plan.space}" else "Joins ${plan.space}, which is there already",
                style = MaterialTheme.typography.titleMedium, color = tc.text, fontWeight = FontWeight.SemiBold,
            )
            plan.sessions.forEach { s ->
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        "${s.folder}${s.account?.let { " on $it" } ?: ""}",
                        style = MaterialTheme.typography.bodyLarge, color = tc.text,
                    )
                    s.branch?.takeIf { it.isNotBlank() }?.let {
                        Text("its own worktree, branch $it", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = tc.muted)
                    }
                }
            }
            ActionModel.assumed(plan.assumed).takeIf { it.isNotEmpty() }?.let { lines ->
                Text("It assumes", style = MaterialTheme.typography.labelLarge, color = tc.faint)
                lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = tc.muted) }
            }
        }
    }
}

@Composable
private fun StateSign(state: String) {
    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
        when (state) {
            "done" -> Icon(Icons.Filled.Check, null, Modifier.size(20.dp), tint = tc.live)
            "undone" -> Icon(Icons.Filled.Check, null, Modifier.size(20.dp), tint = tc.faint)
            "failed" -> Icon(Icons.Filled.PriorityHigh, null, Modifier.size(18.dp), tint = tc.failed)
            else -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = tc.decisionInk)
        }
    }
}

/**
 * A receipt for a command (design frame A8): what the tower opened, in one
 * line; what it assumed, as chips (tap one for why); Undo while it can be
 * taken back. Undone, it says what the undo did.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ActCard(act: dev.herdertower.phone.api.Act, session: dev.herdertower.phone.data.TowerSession, modifier: Modifier = Modifier, framed: Boolean = true) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val snack = LocalSnack.current
    var why by androidx.compose.runtime.remember(act.id) { androidx.compose.runtime.mutableStateOf<dev.herdertower.phone.api.Assumption?>(null) }
    var busy by androidx.compose.runtime.remember(act.id) { androidx.compose.runtime.mutableStateOf(false) }
    var held by androidx.compose.runtime.remember(act.id) { androidx.compose.runtime.mutableStateOf<dev.herdertower.phone.data.ActHeld?>(null) }
    fun undo(force: Boolean) {
        busy = true
        scope.launch {
            session.undoAct(act.id, force)
                .onSuccess { r -> snack(r.closing ?: "Undone.") }
                .onFailure { e -> if (e is dev.herdertower.phone.data.ActHeld) held = e else snack("Couldn't undo it: ${dev.herdertower.phone.data.TowerSession.describe(e)}") }
            busy = false
        }
    }
    val body: @Composable () -> Unit = {
        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                StateSign(if (act.undone) "undone" else "done")
                Spacer(Modifier.width(10.dp))
                Text(
                    if (act.undone) act.closing?.takeIf { it.isNotBlank() } ?: "Undone." else act.line,
                    style = MaterialTheme.typography.bodyLarge, color = if (act.undone) tc.muted else tc.text, fontWeight = if (act.undone) FontWeight.Normal else FontWeight.SemiBold,
                )
            }
            if (!act.undone && act.assumed.isNotEmpty()) {
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    act.assumed.forEach { a ->
                        Toggle(dev.herdertower.phone.api.ActsApi.chip(a), why == a) { why = if (why == a) null else a }
                    }
                }
                why?.let { a -> Text("${a.what.replaceFirstChar { it.uppercase() }} ${a.value}: ${a.why ?: "the tower's default"}", style = MaterialTheme.typography.bodySmall, color = tc.muted) }
            }
            when {
                act.undone -> {}
                act.undo.possible -> Row { PillButton(if (busy) "Undoing…" else "Undo", enabled = !busy) { undo(force = false) } }
                !act.undo.why.isNullOrBlank() -> Text("No undo: ${act.undo.why}", style = MaterialTheme.typography.bodySmall, color = tc.faint)
            }
        }
    }
    if (framed) Card(modifier) { body() } else Box(modifier) { body() }
    held?.let { h ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { held = null },
            title = { Text("Undoing it would lose work") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    h.sessions.forEach { s -> Text("${s.name.ifBlank { s.address }}: ${s.why}", style = MaterialTheme.typography.bodyMedium) }
                    Text("Undo anyway ends them all the same. A worktree with changes is kept, and you're told which.", style = MaterialTheme.typography.bodyMedium, color = tc.muted)
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    held = null
                    undo(force = true)
                }) { Text("Undo anyway", color = tc.failed) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { held = null }) { Text("Keep it") } },
        )
    }
}
