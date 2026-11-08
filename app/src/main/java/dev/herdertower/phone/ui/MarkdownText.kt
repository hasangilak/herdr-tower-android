package dev.herdertower.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Renders [text]'s markdown (see [Markdown]) in [color]; links are tappable. */
@Composable
fun MarkdownText(text: String, color: Color, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyLarge) {
    val blocks = remember(text) { Markdown.parse(text) }
    val codeBg = if (tc.dark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.06f)
    val link = tc.accentHi.takeIf { tc.dark } ?: tc.accent
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { b ->
            when (b) {
                is Markdown.Block.Paragraph -> Text(annotate(b.runs, codeBg, link), style = style, color = color)
                is Markdown.Block.Heading -> Text(annotate(b.runs, codeBg, link), style = style.copy(fontWeight = FontWeight.SemiBold), color = color)
                is Markdown.Block.Bullet -> Row(Modifier.padding(start = (12 * b.depth).dp)) {
                    Text("•", Modifier.width(16.dp), style = style, color = color)
                    Text(annotate(b.runs, codeBg, link), style = style, color = color)
                }
                is Markdown.Block.Numbered -> Row(Modifier.padding(start = (12 * b.depth).dp)) {
                    Text("${b.number}.", Modifier.width(24.dp), style = style, color = color)
                    Text(annotate(b.runs, codeBg, link), style = style, color = color)
                }
                is Markdown.Block.Code -> Text(
                    b.text,
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(codeBg).horizontalScroll(rememberScrollState()).padding(10.dp),
                    style = style.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp),
                    color = color, softWrap = false,
                )
            }
        }
    }
}

fun annotate(runs: List<Markdown.Run>, codeBg: Color, link: Color): AnnotatedString = buildAnnotatedString {
    runs.forEach { r ->
        val span = SpanStyle(
            fontWeight = if (r.bold) FontWeight.SemiBold else null,
            fontStyle = if (r.italic) FontStyle.Italic else null,
            fontFamily = if (r.code) FontFamily.Monospace else null,
            background = if (r.code) codeBg else Color.Unspecified,
        )
        if (r.url != null) {
            withLink(LinkAnnotation.Url(r.url, TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline)))) {
                withStyle(span) { append(r.text) }
            }
        } else {
            withStyle(span) { append(r.text) }
        }
    }
}
