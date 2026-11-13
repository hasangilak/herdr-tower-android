package dev.herdertower.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.herdertower.phone.api.Decision
import dev.herdertower.phone.api.DecisionPr
import dev.herdertower.phone.api.DecisionsApi
import dev.herdertower.phone.api.Pr
import dev.herdertower.phone.data.DecisionOver
import dev.herdertower.phone.data.TowerSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What a decision's card says, in words (the card and the tests read these). */
object DecisionModel {
    /** "3 sessions wait · 2 min". */
    fun stakeLine(d: Decision, now: Long): String = listOf(DecisionsApi.stake(d), DecisionsApi.waited(d, now)).filter { it.isNotBlank() }.joinToString(" · ")

    /** A waiting row's second line: "sandbox · 3 sessions wait · 2 min". */
    fun rowLine(d: Decision, now: Long): String = listOf(d.space, DecisionsApi.stake(d).lowercase(), DecisionsApi.waited(d, now)).filter { it.isNotBlank() }.joinToString(" · ")

    /** A PR's checks in the card's words (the tower's own when it gives them) and whether they're bad news. */
    fun checks(pr: DecisionPr): Pair<String, PrModel.Tone>? {
        val tone = when (pr.checks) {
            "pass" -> PrModel.Tone.Good
            "pending" -> PrModel.Tone.Pending
            "fail" -> PrModel.Tone.Bad
            else -> return pr.note.takeIf { it.isNotBlank() }?.let { it to PrModel.Tone.Pending }
        }
        val word = pr.note.takeIf { it.isNotBlank() } ?: when (tone) {
            PrModel.Tone.Good -> "checks pass"
            PrModel.Tone.Bad -> "checks fail"
            else -> "checks running"
        }
        return word to tone
    }

    /** What the box under the title shows: what the detail says beyond the title (a question's whole text often starts with it), or null. */
    fun detail(d: Decision): String? {
        val full = d.detail.trim()
        val title = d.title.trim().removeSuffix("…").trimEnd()
        val rest = if (title.isNotEmpty() && full.startsWith(title)) full.removePrefix(title).trim() else full
        return rest.takeIf { it.isNotBlank() }
    }

    fun answerable(d: Decision): Boolean = DecisionsApi.answerable(d)

    /**
     * What a card without answers to tap offers instead: the stepper for a
     * question set, the user's own words for one that takes them, Show on
     * Mac for a session blocked on what the tower can't read (and for a kind
     * the app doesn't know, when it names a session), nothing for PRs merged
     * one by one.
     */
    enum class Fallback { Questions, Words, Show, None }

    fun fallback(d: Decision): Fallback = when {
        !answerable(d) -> if (d.sessions.any { it.address.isNotBlank() }) Fallback.Show else Fallback.None
        d.options.isNotEmpty() -> Fallback.None
        d.questionId != null -> Fallback.Questions
        d.kind == "merge" -> Fallback.None
        else -> Fallback.Words
    }

    /** "Show on Mac", or with several sessions "Show mediaroom on Mac". */
    fun showLabel(d: Decision, s: dev.herdertower.phone.api.DecisionSession): String =
        if (d.sessions.size > 1) "Show ${s.label} on Mac" else "Show on Mac"

    /** Whether "Other answer" is offered beside the answers: for what a person could answer in words. */
    fun takesWords(d: Decision): Boolean = d.options.isNotEmpty() && d.kind in setOf("question", "choice", "raised", "failed")

    /** The queue's line under its title: "3 need you", or null, and "2 working". */
    fun status(open: Int, working: Int, reach: Boolean): Pair<String?, String> {
        val need = if (open > 0) "$open need${if (open == 1) "s" else ""} you" else null
        val rest = buildList {
            if (need == null) add("Nothing needs you")
            if (working > 0) add("$working working")
            if (!reach) add("Mac out of reach")
        }.joinToString(" · ")
        return need to rest
    }

    /** Which open decision is the one shown open: the picked one, else the first; and its tag. */
    fun focus(open: List<Decision>, picked: String?): Pair<Decision?, String> {
        val f = open.firstOrNull { it.id == picked } ?: open.firstOrNull()
        return f to if (f != null && f.id != open.first().id) "Picked" else "Next"
    }
}

/**
 * What the screens that show decisions share: sending an answer, answering
 * in one's own words, dismissing, and merging: a merge is never answered
 * through its decision, but through its PR's own two steps (the sheet shows
 * what merging would do, and asks a second time when checks fail).
 */
class DecisionHost(private val session: TowerSession, private val scope: CoroutineScope, private val snack: (String) -> Unit) {
    var words by mutableStateOf<Decision?>(null)
    var merging by mutableStateOf<Pr?>(null)

    fun answer(d: Decision, index: Int) {
        if (d.kind == "merge") {
            // One option per PR, in the order to merge them.
            d.prs.getOrNull(index)?.let(::merge) ?: snack("That pull request isn't there any more.")
            return
        }
        scope.launch { session.answerDecision(d, index).onFailure(::failed) }
    }

    fun sendWords(d: Decision, text: String) {
        scope.launch { session.answerDecisionOther(d, text).onFailure(::failed) }
    }

    /** Brings a session to the front in Herdr on the Mac: where a `blocked` decision is answered. */
    fun show(s: dev.herdertower.phone.api.DecisionSession) = showInHerdr(session, s.address, s.label, snack)

    private fun failed(e: Throwable) = snack(if (e is DecisionOver) e.message.orEmpty() else "Couldn't answer: ${TowerSession.describe(e)}")

    fun dismiss(d: Decision) {
        scope.launch { session.dismissDecision(d).onSuccess { snack("Dismissed.") }.onFailure { snack(TowerSession.describe(it)) } }
    }

    /** A PR's own Merge: the sheet that looks first, then merges. */
    fun merge(pr: DecisionPr) {
        merging = session.prs.value[pr.key] ?: Pr(key = pr.key, repo = pr.repo, number = pr.number, base = pr.base, title = pr.title, url = pr.url.orEmpty(), merge = pr.merge ?: dev.herdertower.phone.api.PrGate(can = true))
    }

    @Composable
    fun Dialogs() {
        words?.let { d ->
            var text by remember(d.id) { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { words = null },
                title = { Text("Your answer") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(d.title, style = MaterialTheme.typography.bodyMedium, color = tc.muted)
                        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 2, maxLines = 6, placeholder = { Text("Say what should happen") })
                    }
                },
                confirmButton = {
                    TextButton(enabled = text.isNotBlank(), onClick = {
                        sendWords(d, text.trim())
                        words = null
                    }) { Text("Send") }
                },
                dismissButton = { TextButton(onClick = { words = null }) { Text("Cancel") } },
            )
        }
        merging?.let { p ->
            val prs by session.prs.collectAsStateWithLifecycle()
            MergeSheet(session, prs[p.id] ?: p) { merging = null }
        }
    }
}

@Composable
fun rememberDecisionHost(session: TowerSession): DecisionHost {
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    return remember(session) { DecisionHost(session, scope, snack) }
}

/**
 * A decision, open: where it comes from and why it's the user's, what waits
 * on it and for how long, the decision in a sentence, and its answers as
 * large buttons, the tower's own pick first in amber.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DecisionCard(
    d: Decision,
    now: Long,
    host: DecisionHost,
    onQuestion: (String) -> Unit,
    onSession: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** "Next" for the head of the queue, "Picked" for one opened from further down. */
    tag: String? = null,
    showSpace: Boolean = true,
) {
    val context = LocalContext.current
    val snack = LocalSnack.current
    var menu by remember { mutableStateOf(false) }
    Card(modifier, strong = true) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    if (tag != null) Text(
                        tag, Modifier.clip(CircleShape).background(tc.talk).padding(horizontal = 8.dp, vertical = 1.dp),
                        style = MaterialTheme.typography.labelSmall, color = tc.onTalk, fontWeight = FontWeight.Bold,
                    )
                    if (showSpace && d.space.isNotBlank()) Text(d.space, style = MaterialTheme.typography.labelLarge, color = tc.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (d.why.isNotBlank()) Tag(d.why)
                    Text(DecisionModel.stakeLine(d, now), style = MaterialTheme.typography.labelMedium, color = tc.muted)
                }
                Box {
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = "More for this decision") { menu = true }
                            .semantics { contentDescription = "More for this decision" },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.MoreVert, null, Modifier.size(20.dp), tint = tc.muted) }
                    DropdownMenu(menu, { menu = false }) {
                        d.sessions.forEach { s ->
                            DropdownMenuItem(text = { Text("Open ${s.label}") }, onClick = { menu = false; onSession(s.address) })
                        }
                        d.questionId?.let { q -> DropdownMenuItem(text = { Text("Every option") }, onClick = { menu = false; onQuestion(q) }) }
                        if (perPr(d)) d.prs.forEach { pr ->
                            DropdownMenuItem(text = { Text("Open #${pr.number} on GitHub") }, onClick = {
                                menu = false
                                if (!openPr(context, Pr(repo = pr.repo, number = pr.number, url = pr.url.orEmpty()))) snack("No link for this pull request.")
                            })
                        }
                        if (d.open && !(d.legacy && d.kind == "merge")) DropdownMenuItem(text = { Text("Dismiss: it isn't mine") }, onClick = { menu = false; host.dismiss(d) })
                    }
                }
            }
            Text(d.title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge, color = tc.text, fontWeight = FontWeight.SemiBold)
            DecisionModel.detail(d)?.let { detail -> Text(
                detail,
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(tc.screen).heightIn(max = 160.dp).verticalScroll(rememberScrollState()).padding(12.dp),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = tc.screenText,
            ) }
            // A merge with an answer per PR says it all in its answers; its links are in the menu.
            if (d.prs.isNotEmpty() && !perPr(d)) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                d.prs.forEach { pr ->
                    PrRow(pr, merge = if (d.legacy && d.open) ({ host.merge(pr) }) else null) {
                        if (!openPr(context, Pr(repo = pr.repo, number = pr.number, url = pr.url.orEmpty()))) snack("No link for this pull request.")
                    }
                }
            }
            if (d.sessions.size > 1 || (d.sessions.size == 1 && (!showSpace || DecisionModel.fallback(d) == DecisionModel.Fallback.Show))) Text(
                "Waiting: " + d.sessions.joinToString(", ") { if (showSpace) it.label else dev.herdertower.phone.api.ActsApi.inSpace(it.label, d.space) },
                style = MaterialTheme.typography.bodySmall, color = tc.muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            d.error?.let { Text("It didn't go in: $it", Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium, color = tc.failed) }
            when {
                d.state == "answering" -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = tc.decisionInk)
                    Spacer(Modifier.width(12.dp))
                    Text("Carrying out your answer…", style = MaterialTheme.typography.bodyMedium, color = tc.muted)
                }
                !d.open -> DoneLine(d)
                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (DecisionModel.answerable(d)) d.options.forEachIndexed { i, o ->
                        BigAnswer(o.label, primary = o.pick, why = o.why) { host.answer(d, i) }
                        // A merge's row, as the Mac's: a breaking change first (rose), its review score (amber: needs review; cyan: low
                        // risk; not when the break is its only reason), its planned checks, or that nothing outside depends on it.
                        if (d.kind == "merge") d.prs.getOrNull(i)?.let { p ->
                            val pad = Modifier.padding(start = 16.dp, end = 8.dp)
                            BreakingBox(p.impact)
                            if (scoreShown(p.score, p.impact)) PrModel.score(p.score)?.let { (line, needs) -> ScoreLine(line, needs, pad) }
                            ValidationLine(p.validation, pad)
                            QuietImpactLine(p.impact, pad)
                        }
                    }
                    when (DecisionModel.fallback(d)) {
                        DecisionModel.Fallback.Questions -> BigAnswer(if (d.why.endsWith("questions")) "Answer the ${d.why}" else "Answer", primary = true) { d.questionId?.let(onQuestion) }
                        DecisionModel.Fallback.Words -> BigAnswer("Answer in my words", primary = true) { host.words = d }
                        DecisionModel.Fallback.Show -> d.sessions.filter { it.address.isNotBlank() }.forEachIndexed { i, s ->
                            BigAnswer(DecisionModel.showLabel(d, s), primary = i == 0, why = if (d.kind == "blocked" && i == 0) "It's answered in the session itself" else "") { host.show(s) }
                        }
                        DecisionModel.Fallback.None -> {}
                    }
                    if (DecisionModel.takesWords(d)) TextButton(onClick = { if (d.questionId != null) onQuestion(d.questionId) else host.words = d }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Other answer", color = tc.muted)
                    }
                }
            }
        }
    }
}

/** A merge whose answers are its PRs, one each (the tower's queue): no rows besides them. */
private fun perPr(d: Decision): Boolean = d.kind == "merge" && d.options.size == d.prs.size && d.prs.isNotEmpty()

/** One of a merge's pull requests: its number, where it goes, its checks; Merge when each is merged by itself. */
@Composable
private fun PrRow(pr: DecisionPr, merge: (() -> Unit)?, onOpen: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(tc.card)
            .clickable(role = Role.Button, onClickLabel = "Open #${pr.number} on GitHub", onClick = onOpen)
            .padding(start = 12.dp, end = if (merge != null) 6.dp else 12.dp, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("#${pr.number}", style = MaterialTheme.typography.bodyMedium, color = tc.text, fontWeight = FontWeight.SemiBold)
        Text(
            "into ${pr.base.ifBlank { "its base" }}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            color = tc.muted, fontWeight = if (DecisionsApi.isProd(pr.base)) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        DecisionModel.checks(pr)?.let { (word, tone) ->
            Text(
                word, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                color = when (tone) {
                    PrModel.Tone.Good -> tc.live
                    PrModel.Tone.Bad -> tc.failed
                    else -> tc.decisionInk
                },
            )
        }
        if (merge != null) PillButton("Merge", onClick = merge)
    }
    BreakingBox(pr.impact, Modifier.padding(top = 4.dp))
    if (scoreShown(pr.score, pr.impact)) PrModel.score(pr.score)?.let { (line, needs) -> ScoreLine(line, needs) }
    ValidationLine(pr.validation)
    QuietImpactLine(pr.impact)
    }
}

@Composable
private fun DoneLine(d: Decision) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 40.dp)) {
        Icon(Icons.Filled.Check, null, Modifier.size(18.dp), tint = tc.live)
        Spacer(Modifier.width(8.dp))
        Text(if (d.state == "dismissed") "Dismissed" else "Answered", style = MaterialTheme.typography.bodyMedium, color = tc.muted)
    }
}

/** A decision answered a moment ago (or on its way in): one quiet line with a tick. */
@Composable
fun DecisionDone(d: Decision, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(tc.card).padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (d.state == "answering") CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = tc.live)
        else Icon(Icons.Filled.Check, null, Modifier.size(18.dp), tint = tc.live)
        Spacer(Modifier.width(10.dp))
        Text(
            (if (d.state == "answering") "On its way: " else "Answered: ") + d.title,
            style = MaterialTheme.typography.bodyMedium, color = tc.muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A decision waiting behind the open one: its title and what waits on it. Tap to open it. */
@Composable
fun DecisionRow(d: Decision, now: Long, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(tc.card)
            .clickable(role = Role.Button, onClickLabel = "Open this decision", onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(d.title, style = MaterialTheme.typography.titleMedium, color = tc.text, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(DecisionModel.rowLine(d, now), style = MaterialTheme.typography.bodySmall, color = tc.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Answers just given that the tower offers to make standing rules: its own
 * words for the rule, one tap to send it, or not now. In a space's view only
 * that space's.
 */
@Composable
fun RuleOffers(session: TowerSession, space: String? = null, modifier: Modifier = Modifier) {
    val offers by session.ruleOffers.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    val shown = offers.filter { space == null || it.space == space }.takeLast(2)
    if (shown.isEmpty()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        shown.forEach { o ->
            var sending by remember(o.id) { mutableStateOf(false) }
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Make it a rule?", style = MaterialTheme.typography.labelLarge, color = tc.faint)
                    Text(o.text.removePrefix("Make this a rule: ").replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyMedium, color = tc.text)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton(if (sending) "Sending…" else "Make it a rule", primary = true, enabled = !sending) {
                            sending = true
                            scope.launch {
                                session.makeRule(o)
                                    .onSuccess { snack("Asked the tower: it shows as a behavior change when it's added.") }
                                    .onFailure { snack("Couldn't make it a rule: ${TowerSession.describe(it)}") }
                                sending = false
                            }
                        }
                        PillButton("Not now") { session.dropRule(o) }
                    }
                }
            }
        }
    }
}
