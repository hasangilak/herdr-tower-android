package dev.herdertower.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.herdertower.phone.api.Plan
import dev.herdertower.phone.api.PlanStage
import dev.herdertower.phone.api.PlansApi
import dev.herdertower.phone.api.PrCheck

/** A stage's chip colour: rose when it is trouble, the passing teal once done, else the working colour. */
@Composable
private fun stageColor(state: String, bad: Boolean): Color = when {
    bad -> tc.failed
    state == "done" -> tc.live
    else -> tc.working
}

@Composable
private fun Chip(word: String, color: Color) {
    Text(
        word, Modifier.clip(CircleShape).border(1.dp, color, CircleShape).padding(horizontal = 8.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.SemiBold, maxLines = 1,
    )
}

/** A label over a list, small and in capitals as the Mac's ("BEFORE IT STARTS · 2"). */
@Composable
private fun SmallCaps(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier, style = MaterialTheme.typography.labelSmall, color = tc.faint, fontWeight = FontWeight.SemiBold)
}

/** A check of a stage: its mark, when ("pre c1"), what, how, and its evidence or result. */
@Composable
private fun StageCheck(`when`: String, c: PrCheck) {
    val (mark, bad) = PlansApi.checkMark(c)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(mark, Modifier.width(14.dp), style = MaterialTheme.typography.bodySmall, color = if (bad) tc.failed else if (mark == "✓") tc.live else tc.faint)
        Text("$`when` ${c.id}", Modifier.width(52.dp), style = MaterialTheme.typography.bodySmall, color = tc.faint, maxLines = 1)
        Column(Modifier.weight(1f)) {
            Text(c.what, style = MaterialTheme.typography.bodySmall, color = tc.text)
            if (c.how.isNotBlank()) Text(c.how, style = MaterialTheme.typography.bodySmall, color = tc.muted)
            Text(PlansApi.checkResult(c), style = MaterialTheme.typography.bodySmall, color = tc.faint)
        }
    }
}

/** A stage's card: "Stage N · s2 · build" and its chip, its title, what counts as done, what it delivers, and its checks before and after. */
@Composable
private fun StageCard(i: Int, s: PlanStage, spikeWaits: Boolean = false) {
    val (word, bad) = PlansApi.stageWord(s.state)
    val color = stageColor(s.state, bad)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(tc.card)
            .border(1.dp, if (bad) tc.failed.copy(alpha = 0.4f) else tc.edge, RoundedCornerShape(14.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                listOf("Stage ${i + 1}", s.id, s.kind).filter { it.isNotBlank() }.joinToString(" · "), Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium, color = tc.faint, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Chip(word, color)
        }
        Text(s.title, style = MaterialTheme.typography.titleSmall, color = tc.text, fontWeight = FontWeight.SemiBold)
        if (s.doneWhen.isNotBlank()) Text("Done when: ${s.doneWhen}", style = MaterialTheme.typography.bodyMedium, color = tc.muted)
        PlansApi.stageLine(s).takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = tc.faint) }
        if (s.tasks.isNotEmpty()) Text("Tasks: ${PlansApi.tasksLine(s.tasks)}", style = MaterialTheme.typography.bodySmall, color = tc.muted)
        if (spikeWaits) Text(PlansApi.SPIKE_WAITS, style = MaterialTheme.typography.bodySmall, color = tc.decisionInk)
        if (s.pre.isNotEmpty()) {
            SmallCaps("Before it starts · ${s.pre.size}", Modifier.padding(top = 4.dp))
            s.pre.forEach { StageCheck("pre", it) }
        }
        SmallCaps("Done when these pass · ${s.post.size}", Modifier.padding(top = 4.dp))
        s.post.forEach { StageCheck("post", it) }
    }
}

/** The plan's stages: "Stages · Stage 2 of 4 · 1 done", a card each, and what makes a stage done. */
@Composable
fun PlanStages(p: Plan) {
    if (p.stages.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HeadWith("Stages", PlansApi.stagesHead(p))
        p.stages.forEachIndexed { i, s -> StageCard(i, s, spikeWaits = s.id in p.spikesPending) }
        Text(
            "A stage is done only when every check of it passed with evidence, and the PRs it delivers passed theirs.",
            Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = tc.faint,
        )
    }
}

@Composable
private fun HeadWith(label: String, sub: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionLabel(label)
        Text("  $sub", style = MaterialTheme.typography.labelMedium, color = tc.faint)
    }
}

/** The plan's questions: "Questions · 2 open of 4", each with where it is, or its answer. */
@Composable
fun PlanQuestions(p: Plan) {
    if (p.questions.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HeadWith("Questions", PlansApi.questionsHead(p))
        p.questions.forEach { q ->
            val asked = q.open && q.status == "asked"
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(tc.card)
                    .border(1.dp, if (asked) tc.decision.copy(alpha = 0.6f) else tc.edge, RoundedCornerShape(14.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text("${q.id} · ${q.text}", style = MaterialTheme.typography.titleSmall, color = tc.text, fontWeight = FontWeight.SemiBold)
                if (q.why.isNotBlank()) Text(q.why, style = MaterialTheme.typography.bodySmall, color = tc.faint)
                Text(PlansApi.questionLine(q), style = MaterialTheme.typography.bodyMedium, color = if (q.open) tc.decisionInk else tc.live)
            }
        }
    }
}

/** What the plan takes for granted: each with its mark, how it is checked, and by which stage. */
@Composable
fun PlanAssumptions(p: Plan) {
    if (p.assumptions.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel("Assumptions")
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(tc.card).border(1.dp, tc.edge, RoundedCornerShape(14.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            p.assumptions.forEach { a ->
                val mark = PlansApi.assumptionMark(a)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(mark, Modifier.width(14.dp), style = MaterialTheme.typography.bodySmall, color = when (mark) { "✓" -> tc.live; "✗" -> tc.failed; else -> tc.faint })
                    Column(Modifier.weight(1f)) {
                        Text("${a.id} · ${a.text}", style = MaterialTheme.typography.bodyMedium, color = tc.text)
                        Text(PlansApi.assumptionLine(a), style = MaterialTheme.typography.bodySmall, color = tc.faint)
                    }
                }
            }
        }
    }
}

/**
 * A space's live plan: "Plan · version 3 · Stage 2 of 4 · 1 done" with Open,
 * each stage's title, state and checks passed, the findings that wait for
 * the plan's revision, and what changed (the last 4 revisions).
 */
@Composable
fun LivePlanCard(p: Plan) {
    val context = LocalContext.current
    val snack = LocalSnack.current
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(PlansApi.liveHead(p), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = tc.text, fontWeight = FontWeight.SemiBold)
                p.url?.takeIf { it.isNotBlank() }?.let { url ->
                    PillButton("Open") { if (!openLink(context, url)) snack("Couldn't open the plan's link") }
                }
            }
            p.stages.forEachIndexed { i, s ->
                val (word, bad) = PlansApi.stageWord(s.state)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${i + 1}", Modifier.width(18.dp), style = MaterialTheme.typography.labelMedium, color = tc.faint)
                    Column(Modifier.weight(1f)) {
                        Text(s.title, style = MaterialTheme.typography.bodyMedium, color = tc.text, fontWeight = FontWeight.SemiBold)
                        Text(PlansApi.liveStageLine(s), style = MaterialTheme.typography.bodySmall, color = tc.faint)
                        if (s.tasks.isNotEmpty()) Text("Tasks: ${PlansApi.tasksLine(s.tasks)}", style = MaterialTheme.typography.bodySmall, color = tc.muted)
                        if (s.id in p.spikesPending) Text(PlansApi.SPIKE_WAITS_LIVE, style = MaterialTheme.typography.bodySmall, color = tc.decisionInk)
                    }
                    Text(word, style = MaterialTheme.typography.labelMedium, color = stageColor(s.state, bad), fontWeight = FontWeight.SemiBold)
                }
            }
            PlansApi.findingsWait(p)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = tc.decisionInk) }
            val revs = PlansApi.lastRevisions(p)
            if (revs.isNotEmpty()) {
                SmallCaps("What changed", Modifier.padding(top = 4.dp))
                revs.forEach { r ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(PlansApi.revisionLine(r), style = MaterialTheme.typography.bodyMedium, color = if (r.status == "proposed") tc.decisionInk else tc.muted)
                        r.changes.take(4).forEach { c -> Text("• $c", Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodySmall, color = tc.faint) }
                    }
                }
            }
        }
    }
}

/** The critic's read of the draft in a line, and the gaps it found, folded. */
@Composable
fun PlanCriticLine(p: Plan) {
    val line = PlansApi.criticLine(p.critic) ?: return
    var open by androidx.compose.runtime.saveable.rememberSaveable(p.id) { androidx.compose.runtime.mutableStateOf(false) }
    val gaps = p.critic?.gaps.orEmpty()
    Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(line, style = MaterialTheme.typography.bodyMedium, color = if (p.critic?.status == "failed") tc.decisionInk else tc.muted)
        if (gaps.isNotEmpty()) {
            Text(
                if (open) "Hide the gaps ▴" else "The ${gaps.size} gap${if (gaps.size == 1) "" else "s"} ▾",
                Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = androidx.compose.ui.semantics.Role.Button) { open = !open }.padding(vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge, color = tc.muted,
            )
            if (open) gaps.forEach { Text("• $it", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall, color = tc.faint) }
        }
    }
}
