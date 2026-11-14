package dev.herdertower.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.herdertower.phone.api.DecisionPr

/** What a merge card says a PR does, as the Mac's card says it (herder-tower `describe::about`). */
object DescriptionModel {
    sealed interface About {
        /** Its description in one or two sentences. */
        data class Summary(val text: String) : About
        /** Its description's opening words, while the summary is being made. */
        data class Opening(val text: String) : About
        /** GitHub says it has none ([asked]: its session was asked to write one). */
        data class Missing(val asked: Boolean) : About
    }

    /** Null: the tower hasn't looked (or is older than descriptions). */
    fun about(p: DecisionPr): About? {
        val body = p.body?.trim() ?: return null
        return when {
            body.isEmpty() -> About.Missing(p.askedBody)
            p.summary.isNotBlank() -> About.Summary(p.summary.trim())
            else -> About.Opening(opening(body))
        }
    }

    /** "No description on GitHub. The tower asked its session to write one." */
    fun missingLine(asked: Boolean): String =
        "No description on GitHub." + if (asked) " The tower asked its session to write one." else ""

    /** The first paragraph of prose (headings skipped), its markdown marks left out, cut short. */
    fun opening(body: String): String {
        val para = body.lines().filterNot { it.trimStart().startsWith("#") }.map(::plain)
            .dropWhile { it.isEmpty() }.takeWhile { it.isNotEmpty() }.joinToString(" ")
        return if (para.length <= 220) para else para.take(220).trimEnd() + "…"
    }

    /** A line of markdown as plain words: no heading, list or quote marks, no emphasis or code ticks. */
    fun plain(line: String): String {
        val t = line.trim().trimStart('#', '>', ' ')
        val u = t.removePrefix("- ").let { if (it === t) t.removePrefix("* ") else it }
        return u.replace("**", "").replace("`", "").trim()
    }

    /** The full description as plain paragraphs. */
    fun lines(body: String): List<String> = body.lines().map(::plain).filter { it.isNotEmpty() }
}

/**
 * Under a merge card PR's title: what it does (its summary, else its
 * description's opening words, else that it has none), and its whole
 * description on request.
 */
@Composable
fun PrAbout(p: DecisionPr, modifier: Modifier = Modifier) {
    val about = DescriptionModel.about(p) ?: return
    var open by rememberSaveable(p.key) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (about) {
            is DescriptionModel.About.Summary -> Text(about.text, style = MaterialTheme.typography.bodyMedium, color = tc.text, maxLines = 4, overflow = TextOverflow.Ellipsis)
            is DescriptionModel.About.Opening -> Text(about.text, style = MaterialTheme.typography.bodyMedium, color = tc.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            is DescriptionModel.About.Missing -> Text(DescriptionModel.missingLine(about.asked), style = MaterialTheme.typography.bodySmall, color = tc.decisionInk)
        }
        val body = p.body.orEmpty()
        if (body.isNotBlank()) {
            Text(
                if (open) "Hide description" else "Full description",
                Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClickLabel = if (open) "Hide the description" else "Show the whole description") { open = !open }
                    .heightIn(min = 32.dp).padding(vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge, color = tc.live, fontWeight = FontWeight.SemiBold,
            )
            if (open) Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(tc.screen).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DescriptionModel.lines(body).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = tc.screenText) }
            }
        }
    }
}
