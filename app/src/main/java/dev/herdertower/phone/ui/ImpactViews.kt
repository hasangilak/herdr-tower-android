package dev.herdertower.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.herdertower.phone.api.PrImpact
import dev.herdertower.phone.api.PrScore

/** Whether the tower has `impact`: breaking changes show only then. */
val LocalImpact = compositionLocalOf { false }

/** Whether the score's line shows: not when its only reason is the break the box already says. */
@Composable
fun scoreShown(score: PrScore?, impact: PrImpact?): Boolean = !(LocalImpact.current && ImpactModel.hidesScore(score, impact))

/**
 * A breaking change, as the Mac's chip warns of it: a rose box, "Breaking
 * change: needs your review", and a line per break (what it changes, who
 * uses it), or a reviewer's own words. Nothing when nothing breaks.
 */
@Composable
fun BreakingBox(i: PrImpact?, modifier: Modifier = Modifier) {
    if (!LocalImpact.current || i == null) return
    val lines = ImpactModel.breaking(i)
    if (lines.isEmpty()) return
    val rose = tc.failed
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(rose.copy(alpha = 0.12f)).border(1.dp, rose.copy(alpha = 0.45f), shape)
            .padding(horizontal = 12.dp, vertical = 9.dp).semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, null, Modifier.size(16.dp), tint = rose)
            Spacer(Modifier.width(6.dp))
            Text("Breaking change: needs your review", style = MaterialTheme.typography.labelLarge, color = rose, fontWeight = FontWeight.SemiBold)
        }
        lines.forEach { b ->
            Row(verticalAlignment = Alignment.Top) {
                Text("•", Modifier.width(12.dp), style = MaterialTheme.typography.bodySmall, color = rose)
                Text(b, style = MaterialTheme.typography.bodySmall, color = tc.text)
            }
        }
    }
}

/** When nothing breaks, said quietly: "Nothing outside depends on what it changes (…; searched N repos)". */
@Composable
fun QuietImpactLine(i: PrImpact?, modifier: Modifier = Modifier) {
    if (!LocalImpact.current) return
    val line = ImpactModel.quiet(i) ?: return
    Text(line, modifier, style = MaterialTheme.typography.labelSmall, color = tc.faint, maxLines = 3, overflow = TextOverflow.Ellipsis)
}

/** The merge sheet's way to see every dependent: by what it uses, each with its repo, file:line, function and the line itself. */
@Composable
fun DependentsList(i: PrImpact?, key: String) {
    if (!LocalImpact.current || i == null || i.dependents.isEmpty()) return
    var open by rememberSaveable(key) { mutableStateOf(false) }
    val n = i.dependents.size
    Row(
        Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClickLabel = if (open) "Hide the dependents" else "Show every dependent") { open = !open }
            .padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null, Modifier.size(18.dp), tint = tc.accentHi)
        Text(
            if (open) " Hide the dependents" else " Every dependent ($n), in ${i.searched.size} repo${if (i.searched.size == 1) "" else "s"} searched",
            style = MaterialTheme.typography.labelLarge, color = tc.accentHi, fontWeight = FontWeight.Medium,
        )
    }
    if (!open) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ImpactModel.byThing(i).forEach { (what, users) ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(what.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelLarge, color = tc.text, fontWeight = FontWeight.SemiBold)
                users.forEach { d ->
                    Column(Modifier.fillMaxWidth().padding(start = 10.dp).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text("${d.repo}  ${d.file}:${d.line}", style = MaterialTheme.typography.bodySmall, color = tc.text, fontWeight = FontWeight.Medium)
                        if (d.within.isNotEmpty()) Text("in ${d.within}", style = MaterialTheme.typography.bodySmall, color = tc.muted)
                        if (d.text.isNotBlank()) Text(
                            d.text.trim(), style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = tc.faint,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
