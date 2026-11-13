package dev.herdertower.phone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.herdertower.phone.api.EdgeCase
import dev.herdertower.phone.api.PrCheck
import dev.herdertower.phone.api.PrValidation

/** Whether the tower has `validation`: checks and edge cases show only then. */
val LocalValidation = compositionLocalOf { false }

@Composable
fun validationColor(t: ValidationModel.Tone): Color = when (t) {
    ValidationModel.Tone.Passed -> tc.live
    ValidationModel.Tone.Failed -> tc.failed
    ValidationModel.Tone.Missing -> tc.decisionInk
}

/** A PR's planned checks in a line (a merge card's row, a chip): cyan, rose when one failed, amber when some are missing. */
@Composable
fun ValidationLine(v: PrValidation?, modifier: Modifier = Modifier) {
    if (!LocalValidation.current || v == null) return
    val line = ValidationModel.line(v) ?: return
    Text(
        line, modifier, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium,
        color = validationColor(ValidationModel.tone(v)), maxLines = 3, overflow = TextOverflow.Ellipsis,
    )
}

/**
 * How a PR is validated, as the Mac shows it under VALIDATION · n and EDGE
 * CASES · n: each check's id, what it shows, how, and its evidence; on a
 * [real] PR also how it stands and the evidence its session gave. Each edge
 * case with the checks that cover it.
 */
@Composable
fun ChecksBlock(checks: List<PrCheck>, edgeCases: List<EdgeCase>, real: Boolean, modifier: Modifier = Modifier) {
    if (!LocalValidation.current || (checks.isEmpty() && edgeCases.isEmpty())) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (checks.isNotEmpty()) {
            SmallHead(ValidationModel.checksHead(checks.size))
            checks.forEach { CheckRow(it, real) }
        }
        if (edgeCases.isNotEmpty()) {
            SmallHead(ValidationModel.edgesHead(edgeCases.size), Modifier.padding(top = 4.dp))
            edgeCases.forEach { e ->
                Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Top) {
                    Text("•", Modifier.width(14.dp), style = MaterialTheme.typography.bodySmall, color = tc.faint)
                    Text(e.case, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = tc.text)
                    Text(ValidationModel.covers(e), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall, color = tc.muted)
                }
            }
        }
    }
}

@Composable
private fun SmallHead(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), modifier.padding(top = 2.dp).semantics { heading() },
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp), fontWeight = FontWeight.SemiBold, color = tc.faint,
    )
}

@Composable
private fun CheckRow(c: PrCheck, real: Boolean) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Top) {
        Text(c.id, Modifier.width(30.dp), style = MaterialTheme.typography.bodySmall, color = tc.muted, fontWeight = FontWeight.SemiBold)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(c.what, style = MaterialTheme.typography.bodySmall, color = tc.text)
            if (real) Text(
                ValidationModel.statusWord(c).replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = validationColor(ValidationModel.tone(c)),
            )
            if (c.how.isNotBlank()) Text(c.how, style = MaterialTheme.typography.bodySmall, color = tc.muted)
            Text(
                if (real) ValidationModel.evidence(c) else "Evidence: ${c.evidence}",
                style = MaterialTheme.typography.bodySmall, color = tc.faint, maxLines = 6, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
