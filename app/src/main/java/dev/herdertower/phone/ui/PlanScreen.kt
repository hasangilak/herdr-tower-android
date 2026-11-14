package dev.herdertower.phone.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.herdertower.phone.api.Plan
import dev.herdertower.phone.api.PlansApi
import dev.herdertower.phone.api.PredictedPr
import dev.herdertower.phone.data.TowerSession

/**
 * The plan that is open: how it stands, its link once published, the pull
 * requests it predicts with their review scores, and who merges which. Read
 * from the state, so it follows the planner as it goes.
 */
@Composable
fun PlanScreen(session: TowerSession, onBack: () -> Unit) {
    val state by session.state.collectAsStateWithLifecycle()
    val scores by session.scoresSupported.collectAsStateWithLifecycle()
    val plan = remember(state?.plan) { PlansApi.parse(state?.plan) }
    val context = LocalContext.current
    val snack = LocalSnack.current

    Column(Modifier.fillMaxSize()) {
        TopBar(plan?.let(PlanModel::name) ?: "Plan", plan?.let { "Plan · ${PlanModel.form(it)}" } ?: "None open", onBack = onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (plan == null) {
                EmptyBox("No plan is open now. A plan starts on the Mac, from a ticket, and shows here while its planner writes it.")
                return@Column
            }
            val st = PlanModel.status(plan)
            Card(strong = st.tone == PlanModel.Tone.Waits) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        PlanSign(st.tone)
                        Spacer(Modifier.width(10.dp))
                        // The sign carries the tone (red when it failed); the words stay readable on any card.
                        Text(st.line, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = tc.text)
                    }
                    PlanModel.notes(plan).forEach { Text(it, Modifier.padding(start = 34.dp), style = MaterialTheme.typography.bodySmall, color = tc.muted) }
                    plan.url?.takeIf { it.isNotBlank() && plan.status != "failed" }?.let { url ->
                        Row(Modifier.padding(start = 34.dp, top = 2.dp)) {
                            PillButton("Open the plan", primary = true, icon = Icons.AutoMirrored.Filled.OpenInNew) {
                                if (!openLink(context, url)) snack("Couldn't open the plan's link")
                            }
                        }
                    }
                }
            }
            plan.draft?.goal?.takeIf { it.isNotBlank() }?.let { goal ->
                val strong = tc.text
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = strong, fontWeight = FontWeight.SemiBold)) { append("Goal") }
                        append("  ")
                        append(goal)
                    },
                    Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodyLarge, color = tc.muted,
                )
            }
            // Planning v2: no plan goes out with questions open; then its stages, questions and assumptions.
            // The critic read the draft before its questions went out; the planner's account.
            PlanCriticLine(plan)
            plan.account?.takeIf { it.isNotBlank() }?.let { Text("Its planner runs on $it.", Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = tc.faint) }
            PlansApi.launchWaits(plan)?.let { Text(it, Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodyMedium, color = tc.decisionInk) }
            PlanStages(plan)
            PlanQuestions(plan)
            PlanAssumptions(plan)
            if (scores && plan.prs.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SectionLabel("Pull requests")
                    Text(PlanModel.prsHead(plan.prs), Modifier.padding(start = 4.dp), style = MaterialTheme.typography.bodyMedium, color = tc.muted)
                }
                plan.prs.forEach { PredictedPrCard(it) }
                Text(
                    PlanModel.mergeNote(plan.prs, state?.autoMerge == true), Modifier.padding(horizontal = 4.dp),
                    style = MaterialTheme.typography.bodyMedium, color = tc.muted,
                )
            }
        }
    }
}

/** The plan in the spaces list, above the spaces: its space, how it stands and its PRs. */
@Composable
fun PlanRow(plan: Plan, scores: Boolean, onClick: () -> Unit) {
    val st = PlanModel.status(plan)
    SignRow(
        sign = { PlanSign(st.tone) },
        name = plan.draft?.space?.takeIf { it.isNotBlank() }?.let { "Plan for $it" } ?: "New plan",
        right = st.word, sub = PlanModel.rowLine(plan, scores), onClick = onClick,
    )
}

/** How a plan stands, as a small sign: a cyan dot while it's written, amber when it waits, a tick when ready, red when it failed. */
@Composable
fun PlanSign(tone: PlanModel.Tone) {
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        when (tone) {
            PlanModel.Tone.Busy -> Dot(tc.live, size = 10.dp)
            PlanModel.Tone.Waits -> Dot(tc.decision, size = 10.dp)
            PlanModel.Tone.Ready -> Icon(Icons.Filled.Check, null, Modifier.size(18.dp), tint = tc.live)
            PlanModel.Tone.Failed -> Icon(Icons.Filled.Error, null, Modifier.size(18.dp), tint = tc.failed)
            PlanModel.Tone.Closed -> Box(Modifier.size(10.dp).border(1.5.dp, tc.faint, CircleShape))
        }
    }
}

/** One predicted PR: where it goes, what it changes, its files, its badge and why. */
@Composable
private fun PredictedPrCard(p: PredictedPr) {
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(PlanModel.head(p), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = tc.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                PlanModel.badge(p.score)?.let { (word, review) -> ScoreBadge(word, review) }
            }
            if (p.title.isNotBlank()) Text(p.title, style = MaterialTheme.typography.titleMedium, color = tc.text, fontWeight = FontWeight.SemiBold)
            if (p.changes.isNotBlank()) Text(p.changes, style = MaterialTheme.typography.bodyMedium, color = tc.muted)
            PlanModel.files(p)?.let {
                Text(it, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = tc.faint, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            PlanModel.reason(p.score)?.let { ScoreLine(it, p.score?.needsReview == true) }
            PlanModel.prNotes(p).takeIf { it.isNotEmpty() }?.let {
                Text(it.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = tc.faint)
            }
            // As the Mac's: how it is validated, who outside depends on what it changes (what breaks first), then its edge cases.
            ChecksBlock(p.checks, emptyList(), real = false, modifier = Modifier.padding(top = 4.dp))
            PlanDependents(p)
            ChecksBlock(emptyList(), p.edgeCases, real = false, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/**
 * Who outside depends on what a predicted PR changes (a tower with
 * `impact`): each one it breaks in a rose box, "⚠ Breaks"; each it doesn't,
 * quietly; with none, how the planner checked that nothing does.
 */
@Composable
private fun PlanDependents(p: PredictedPr) {
    if (!LocalImpact.current) return
    val rose = tc.failed
    val shape = RoundedCornerShape(10.dp)
    p.dependents.filter { it.breaks }.forEach { d ->
        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp).clip(shape).background(rose.copy(alpha = 0.12f)).border(1.dp, rose.copy(alpha = 0.45f), shape)
                .padding(horizontal = 10.dp, vertical = 7.dp).semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.Top,
        ) {
            Text("⚠ Breaks", style = MaterialTheme.typography.labelMedium, color = rose, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text(PlanModel.dependent(d), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = tc.text)
        }
    }
    p.dependents.filterNot { it.breaks }.forEach { d ->
        Text(PlanModel.stillWorks(d), style = MaterialTheme.typography.bodySmall, color = tc.faint)
    }
    PlanModel.nothingOutside(p)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = tc.faint) }
}

/** "Needs review" in amber, "Low risk" in cyan. */
@Composable
private fun ScoreBadge(word: String, needsReview: Boolean) {
    val color = if (needsReview) tc.decisionInk else tc.live
    Text(
        word, Modifier.padding(start = 8.dp).clip(CircleShape).background(color.copy(alpha = 0.16f)).border(1.dp, color.copy(alpha = 0.5f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.SemiBold, maxLines = 1,
    )
}

/** Opens a web link (the plan on claude.ai) in the browser; only http(s). */
fun openLink(context: Context, url: String): Boolean {
    if (!url.startsWith("https://") && !url.startsWith("http://")) return false
    return runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
}
