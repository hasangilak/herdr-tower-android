package dev.herdertower.phone.ui

import android.content.Intent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import dev.herdertower.phone.api.MergeConfirm
import dev.herdertower.phone.api.Pr
import dev.herdertower.phone.data.TowerSession

@Composable
private fun toneColor(t: PrModel.Tone): Color = when (t) {
    PrModel.Tone.Good -> tc.done
    PrModel.Tone.Bad -> tc.failed
    PrModel.Tone.Pending -> tc.blocked
    PrModel.Tone.Quiet -> tc.faint
}

private fun toneIcon(t: PrModel.Tone): ImageVector = when (t) {
    PrModel.Tone.Good -> Icons.Filled.CheckCircle
    PrModel.Tone.Bad -> Icons.Filled.Error
    PrModel.Tone.Pending -> Icons.Filled.HourglassTop
    PrModel.Tone.Quiet -> Icons.Filled.Block
}

/** Opens the PR on GitHub (the browser, or GitHub's app). */
fun openPr(context: android.content.Context, pr: Pr): Boolean {
    val url = pr.url.ifBlank { pr.ref?.let { "https://github.com/${it.owner}/${it.repo}/pull/${it.number}" } } ?: return false
    return runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
}

/** A small status line with its icon: "Checks passing", "Conflicts with main". */
@Composable
private fun StatusBit(text: String, tone: PrModel.Tone) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(toneIcon(tone), null, Modifier.size(15.dp), tint = toneColor(tone))
        Text(" $text", style = MaterialTheme.typography.labelMedium, color = if (tone == PrModel.Tone.Bad) tc.failed else tc.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A PR a session opened, in the chat: its number, repo, title and branches,
 * the checks and conflicts, "Waiting for you" when its session waits on the
 * merge, and Merge. Tap it to open the PR; it changes in place as the tower
 * hears from GitHub.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PrChip(pr: Pr, onMerge: () -> Unit, modifier: Modifier = Modifier, highlighted: Boolean = false, at: Long = 0) {
    val context = LocalContext.current
    val snack = LocalSnack.current
    val phase = PrModel.phase(pr)
    val waiting = phase == PrModel.Phase.Waiting
    val done = phase == PrModel.Phase.Merged || phase == PrModel.Phase.Closed
    val shape = RoundedCornerShape(22.dp)
    val edge = when {
        highlighted -> tc.accentHi
        waiting -> tc.blocked.copy(alpha = 0.7f)
        else -> tc.edge
    }
    val reduce = LocalReduceMotion.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (highlighted) tc.accent.copy(alpha = if (tc.dark) 0.22f else 0.14f) else tc.card)
            .border(if (waiting || highlighted) 1.5.dp else 1.dp, edge, shape)
            .clickable(role = Role.Button, onClickLabel = "Open the PR on GitHub") { if (!openPr(context, pr)) snack("No link for this PR yet.") }
            .then(if (reduce) Modifier else Modifier.animateContentSize())
            .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // The heading row speaks for the chip; the buttons below speak for themselves.
        Column(Modifier.semantics(mergeDescendants = true) { contentDescription = PrModel.describe(pr) }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Merge, null, Modifier.size(18.dp), tint = if (phase == PrModel.Phase.Merged) tc.done else if (done) tc.faint else tc.accentHi)
                Spacer(Modifier.width(8.dp))
                Text(PrModel.heading(pr), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = tc.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                when (phase) {
                    PrModel.Phase.Waiting -> Pill("Waiting for you", tc.blocked)
                    PrModel.Phase.Draft -> Pill("Draft", tc.faint)
                    PrModel.Phase.Merged -> Pill(PrModel.mergedPill(pr), tc.done)
                    PrModel.Phase.Closed -> Pill("Closed", tc.faint)
                    else -> if (at > 0) Text(clock(at), style = MaterialTheme.typography.labelSmall, color = tc.faint)
                }
                Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.padding(start = 6.dp).size(14.dp), tint = tc.faint)
            }
            Text(
                pr.title.ifBlank { "Looking it up on GitHub…" },
                style = MaterialTheme.typography.titleSmall, color = if (done) tc.muted else tc.text, fontWeight = FontWeight.SemiBold,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
            val sub = listOf(PrModel.branches(pr), pr.name).filter { it.isNotBlank() }.joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = tc.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val closed = PrModel.closedLine(pr)
            if (closed != null) {
                StatusBit(closed, if (phase == PrModel.Phase.Merged) PrModel.Tone.Good else PrModel.Tone.Quiet)
            } else if (pr.state.isNotBlank()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val (c, ct) = PrModel.checks(pr)
                    StatusBit(c, ct)
                    PrModel.mergeable(pr)?.let { (m, mt) -> StatusBit(m, mt) }
                }
            }
            // Whether a person should read it before it merges: amber when it needs review, cyan when low risk.
            PrModel.score(pr.score)?.let { (line, needs) -> ScoreLine(line, needs) }
            // Its plan's checks: all passed (cyan), or which are missing (amber; rose when one failed).
            ValidationLine(pr.validation)
            PrModel.autoFailed(pr)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = tc.faint, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            pr.error?.takeIf { pr.open }?.let {
                Text("The Mac couldn't ask GitHub lately: $it", style = MaterialTheme.typography.labelSmall, color = tc.faint, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        // Its checks, how each stands and the evidence given, and its edge cases: folded until asked for.
        pr.validation?.takeIf { LocalValidation.current && (it.checks.isNotEmpty() || it.edgeCases.isNotEmpty()) }?.let { v ->
            var shown by rememberSaveable(pr.id) { mutableStateOf(false) }
            Row(
                Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClickLabel = if (shown) "Hide its checks" else "Show its checks") { shown = !shown }
                    .padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (shown) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null, Modifier.size(18.dp), tint = tc.accentHi)
                Text(
                    if (shown) " Hide the checks" else " Show the checks and edge cases", style = MaterialTheme.typography.labelLarge,
                    color = tc.accentHi, fontWeight = FontWeight.Medium,
                )
            }
            if (shown) ChecksBlock(v.checks, v.edgeCases, real = true, modifier = Modifier.padding(bottom = 4.dp))
        }
        val button = PrModel.mergeButton(pr)
        if (button != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(button.why.orEmpty(), Modifier.weight(1f).padding(end = 8.dp), style = MaterialTheme.typography.labelSmall, color = tc.faint, maxLines = 2, overflow = TextOverflow.Ellipsis)
                MergeButton("Merge", enabled = button.enabled, prominent = waiting, label = "Merge PR #${pr.number}", why = button.why, onClick = onMerge)
            }
        }
    }
}

/** A PR's review score in a line: amber when it needs review, cyan when low risk (the chip and the merge card's rows). */
@Composable
fun ScoreLine(line: String, needsReview: Boolean, modifier: Modifier = Modifier) {
    Text(
        line, modifier, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium,
        color = if (needsReview) tc.decisionInk else tc.live, maxLines = 3, overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun Pill(text: String, color: Color) {
    Text(
        text, Modifier.padding(start = 8.dp).clip(CircleShape).background(color.copy(alpha = 0.16f)).border(1.dp, color.copy(alpha = 0.5f), CircleShape).padding(horizontal = 10.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.SemiBold, maxLines = 1,
    )
}

/** A 48dp pill button; [danger] for the merge that goes past failing checks. */
@Composable
private fun MergeButton(text: String, enabled: Boolean, prominent: Boolean, modifier: Modifier = Modifier, label: String = text, why: String? = null, danger: Boolean = false, onClick: () -> Unit) {
    val bg = when {
        !enabled -> tc.faint.copy(alpha = 0.18f)
        danger -> tc.failed
        prominent -> tc.text
        else -> tc.accent
    }
    val fg = when {
        !enabled -> tc.faint
        prominent && !danger -> tc.bgTop
        else -> Color.White
    }
    Box(
        modifier.heightIn(min = 48.dp).widthIn(min = 96.dp).clip(CircleShape).background(bg)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics {
                contentDescription = label
                if (!enabled) {
                    disabled()
                    why?.let { stateDescription = "Not possible: $it" }
                }
            }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Merge, null, Modifier.size(18.dp), tint = fg)
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, color = fg, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun QuietButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = 48.dp).widthIn(min = 88.dp).clip(CircleShape).border(1.dp, tc.edge, CircleShape)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon?.let { Icon(it, null, Modifier.size(18.dp), tint = tc.text); Spacer(Modifier.width(6.dp)) }
            Text(text, style = MaterialTheme.typography.labelLarge, color = tc.text, maxLines = 1)
        }
    }
}

/**
 * Merging [pr]: asks the tower what it would do (repo, number, base, checks,
 * method, warnings), then merges with that answer's token; failing checks
 * need a second, explicit yes. A failure shows a card with Look again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MergeSheet(session: TowerSession, pr: Pr, onDismiss: () -> Unit) {
    val ref = pr.ref
    if (ref == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val snack = LocalSnack.current
    var step by remember(pr.id) { mutableStateOf<MergeStep>(MergeStep.Looking()) }
    // While it merges the sheet stays: the answer must be seen.
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden || step !is MergeStep.Merging })
    // Each look and each try is its own call; whatever step it leads to, the sheet shows.
    LaunchedEffect(step) {
        when (val s = step) {
            is MergeStep.Looking -> session.confirmMerge(ref)
                .onSuccess { step = MergeStep.looked(it, System.currentTimeMillis(), s.note) }
                .onFailure { step = MergeStep.lookFailed(it, s.last) }
            is MergeStep.Merging -> session.merge(ref, s.confirm.token, s.override)
                .onSuccess {
                    step = MergeStep.merged(it)
                    snack("Merged PR #${ref.number} into ${s.confirm.pr.base.ifBlank { pr.base }}")
                    onDismiss()
                }
                .onFailure { step = MergeStep.mergeFailed(it, s) }
            else -> {}
        }
    }
    val busy = step is MergeStep.Looking || step is MergeStep.Merging
    ModalBottomSheet(
        onDismissRequest = { if (step !is MergeStep.Merging) onDismiss() },
        sheetState = sheet,
        containerColor = if (tc.dark) Color(0xFF17110E) else Color(0xFFFFF8F1),
    ) {
        val shown: MergeConfirm? = when (val s = step) {
            is MergeStep.Looking -> s.last
            is MergeStep.Ready -> s.confirm
            is MergeStep.Override -> s.confirm
            is MergeStep.Merging -> s.confirm
            is MergeStep.Failed -> s.last
            is MergeStep.Merged -> null
        }
        val p = shown?.pr?.takeIf { it.id.isNotBlank() } ?: pr
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Merge PR #${p.number}?", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge, color = tc.text, fontWeight = FontWeight.SemiBold)
            Text(p.title.ifBlank { p.id }, style = MaterialTheme.typography.bodyLarge, color = tc.text, maxLines = 4, overflow = TextOverflow.Ellipsis)
            Column(Modifier.alpha(if (busy && shown != null) 0.6f else 1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Fact("Repo", p.repo)
                Fact("Into", if (p.head.isNotBlank()) "${p.base}  ←  ${p.head}" else p.base, strong = PrModel.isProd(p.base))
                PrModel.checks(p).let { (t, tone) -> Fact("Checks", t, tone = tone) }
                PrModel.mergeable(p)?.let { (t, tone) -> Fact("Conflicts", t, tone = tone) }
                shown?.let { Fact("Method", PrModel.method(it.method)) }
            }
            // Each planned check and how it stands; the missing ones are said again among the warnings below.
            (p.validation ?: pr.validation)?.let { v -> ChecksBlock(v.checks, v.edgeCases, real = true) }
            when (val s = step) {
                is MergeStep.Looking -> Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = tc.accentHi, strokeWidth = 2.dp)
                    Text("  Asking GitHub where it stands…", style = MaterialTheme.typography.bodyMedium, color = tc.muted)
                }
                is MergeStep.Ready -> {
                    s.note?.let { Notice(it, tc.blocked, Icons.Filled.Refresh) }
                    PrModel.warnings(s.confirm.pr.base.ifBlank { pr.base }, s.confirm.warnings).forEach { Notice(it, tc.blocked, Icons.Filled.Warning) }
                    s.confirm.needsOverride?.let { Notice(it, tc.failed, Icons.Filled.Error) }
                    s.blocked?.let { Notice(it, tc.failed, Icons.Filled.Block) }
                    Actions(onCancel = onDismiss) {
                        MergeButton(
                            "Merge into ${p.base}", enabled = s.blocked == null, prominent = true, why = s.blocked,
                            label = if (s.blocked == null) "Merge PR #${p.number} into ${p.base}" else "Merge: not possible", modifier = it,
                        ) { step = MergeStep.mergeTapped(s, System.currentTimeMillis()) }
                    }
                }
                is MergeStep.Override -> {
                    // The second yes: the failure, and what still holds (into main, …), stay in view.
                    PrModel.warnings(s.confirm.pr.base.ifBlank { pr.base }, s.confirm.warnings).forEach { Notice(it, tc.blocked, Icons.Filled.Warning) }
                    Notice(s.confirm.needsOverride ?: "Checks fail. Merge anyway only if you know the failure is unrelated.", tc.failed, Icons.Filled.Error)
                    Text("Are the failing checks unrelated to this change?", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall, color = tc.text, fontWeight = FontWeight.SemiBold)
                    Actions(onCancel = onDismiss, cancel = "Don't merge") {
                        MergeButton("Merge anyway: the failure is unrelated", enabled = true, prominent = true, danger = true, label = "Merge anyway: the failing checks are unrelated", modifier = it) {
                            step = MergeStep.overrideTapped(s, System.currentTimeMillis())
                        }
                    }
                }
                is MergeStep.Merging -> Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = tc.accentHi, strokeWidth = 2.dp)
                    Text("  Merging into ${p.base}…", style = MaterialTheme.typography.bodyMedium, color = tc.muted)
                }
                is MergeStep.Failed -> {
                    RetryCard(s.message) { step = MergeStep.Looking(s.last) }
                    Actions(onCancel = onDismiss, cancel = "Close") {}
                }
                is MergeStep.Merged -> Notice("Merged.", tc.done, Icons.Filled.CheckCircle)
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String, tone: PrModel.Tone? = null, strong: Boolean = false) {
    if (value.isBlank()) return
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(88.dp), style = MaterialTheme.typography.labelLarge, color = tc.faint)
        tone?.let { Icon(toneIcon(it), null, Modifier.padding(end = 6.dp).size(16.dp), tint = toneColor(it)) }
        Text(
            value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            color = if (tone == PrModel.Tone.Bad) tc.failed else tc.text, fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun Notice(text: String, color: Color, icon: ImageVector) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.12f)).border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, null, Modifier.padding(top = 1.dp).size(18.dp), tint = color)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = tc.text)
    }
}

/** What went wrong, and Look again (a try uses its token up: a retry always starts with a fresh look). */
@Composable
private fun RetryCard(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(tc.failed.copy(alpha = 0.10f)).border(1.dp, tc.failed.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
            .padding(14.dp).semantics { liveRegion = LiveRegionMode.Assertive },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.Cancel, null, Modifier.size(20.dp), tint = tc.failed)
            Spacer(Modifier.width(10.dp))
            Column {
                Text("It didn't merge", style = MaterialTheme.typography.titleSmall, color = tc.text, fontWeight = FontWeight.SemiBold)
                Text(message, style = MaterialTheme.typography.bodyMedium, color = tc.text)
            }
        }
        QuietButton("Look again", Modifier.align(Alignment.End), icon = Icons.Filled.Refresh, onClick = onRetry)
    }
}

@Composable
private fun Actions(onCancel: () -> Unit, cancel: String = "Cancel", primary: @Composable (Modifier) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        QuietButton(cancel, onClick = onCancel)
        primary(Modifier.weight(1f))
    }
}
