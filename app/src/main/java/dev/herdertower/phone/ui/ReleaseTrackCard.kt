package dev.herdertower.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.herdertower.phone.api.ReleaseTrack
import dev.herdertower.phone.api.RepoTrack

@Composable
private fun toneColor(t: ReleaseModel.Tone): Color = when (t) {
    ReleaseModel.Tone.Prod -> tc.live
    ReleaseModel.Tone.Dev -> tc.decisionInk
    ReleaseModel.Tone.Rule -> tc.failed
    ReleaseModel.Tone.Quiet -> tc.muted
}

/**
 * A space's release track, as the Mac's space panel shows it: per repo
 * where its changes stand (on prod in cyan, on dev only in amber, a skip of
 * dev in rose), each change with its stage, and each release to prod with
 * what it carried. A change or a release opens on GitHub.
 */
@Composable
fun ReleaseTrackCard(t: ReleaseTrack, now: Long, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Release track", style = MaterialTheme.typography.titleMedium, color = tc.text, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                if (t.checkedMs > 0) Text(ReleaseModel.checked(t.checkedMs, now), style = MaterialTheme.typography.labelMedium, color = tc.faint)
            }
            t.repos.forEach { RepoBlock(it, now) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RepoBlock(r: RepoTrack, now: Long) {
    val context = LocalContext.current
    val snack = LocalSnack.current
    fun open(url: String) {
        if (!openLink(context, url)) snack("No link for it.")
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Text(r.repo, style = MaterialTheme.typography.titleSmall, color = tc.text, fontWeight = FontWeight.SemiBold)
            Text("${r.dev} → ${r.prod}", style = MaterialTheme.typography.labelMedium, color = tc.faint)
        }
        r.lines.forEach { l ->
            Text(l, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = toneColor(ReleaseModel.lineTone(l, r.dev, r.prod)))
        }
        if (r.changes.isNotEmpty()) Column(Modifier.padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            r.changes.forEach { c ->
                val chip = ReleaseModel.stageTone(c.stage).let { if (it == ReleaseModel.Tone.Quiet) tc.faint else toneColor(it) }
                Column(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).background(tc.card)
                        .clickable(role = Role.Button, onClickLabel = "Open #${c.number} on GitHub") { open(c.url) }
                        .padding(horizontal = 10.dp, vertical = 7.dp).semantics(mergeDescendants = true) {},
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text("#${c.number}", style = MaterialTheme.typography.bodyMedium, color = tc.text, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(8.dp))
                        Text(c.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = tc.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        Text(
                            ReleaseModel.stageWord(c.stage, r.dev, r.prod),
                            Modifier.clip(CircleShape).border(1.dp, chip, CircleShape).padding(horizontal = 8.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelMedium, color = chip,
                        )
                        Text(ReleaseModel.detail(c, now), style = MaterialTheme.typography.labelMedium, color = tc.faint)
                    }
                }
            }
        }
        if (r.releases.isNotEmpty()) {
            SectionLabel("Releases", Modifier.padding(top = 4.dp))
            r.releases.forEach { rel ->
                val (landed, merged) = ReleaseModel.landed(rel, now)
                Column(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
                        .clickable(role = Role.Button, onClickLabel = "Open release #${rel.number} on GitHub") { open(rel.url) }
                        .padding(horizontal = 4.dp, vertical = 5.dp).semantics(mergeDescendants = true) {},
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        Text("Release #${rel.number}", style = MaterialTheme.typography.bodyMedium, color = tc.text, fontWeight = FontWeight.SemiBold)
                        Text("${rel.head} → ${rel.base}", style = MaterialTheme.typography.labelMedium, color = tc.faint)
                        Text(landed, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, color = if (merged) tc.live else tc.decisionInk)
                    }
                    Text(ReleaseModel.carried(rel), style = MaterialTheme.typography.bodySmall, color = tc.muted)
                }
            }
        }
    }
}
