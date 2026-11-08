package dev.herdertower.phone.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.min

@Composable
fun Dot(color: Color, size: Dp = 8.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}

/**
 * A Claude account's label, as on the Mac: the default account quiet
 * (outlined), any other filled with the colour its name picks.
 */
@Composable
fun AccountBadge(account: String, isDefault: Boolean, modifier: Modifier = Modifier) {
    val shape = CircleShape
    val base = modifier.widthIn(max = 120.dp).clip(shape)
    if (isDefault) {
        Text(
            account,
            modifier = base.border(1.dp, tc.edge, shape).padding(horizontal = 7.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall,
            color = tc.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    } else {
        Text(
            account,
            modifier = base.background(Color(0xFF000000 or AccountHue.of(account))).padding(horizontal = 7.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = NAVY,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A space's account: the one its Claude sessions share, or "mixed" with a dot per account. */
@Composable
fun SpaceAccountBadge(accounts: List<String>, default: String?) {
    val distinct = accounts.distinct().sorted()
    when (distinct.size) {
        0 -> {}
        1 -> AccountBadge(distinct[0], distinct[0] == default)
        else -> Row(
            Modifier.clip(CircleShape).border(1.dp, tc.edge, CircleShape).padding(horizontal = 7.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            distinct.take(3).forEach { Dot(if (it == default) tc.muted else Color(0xFF000000 or AccountHue.of(it)), 5.dp) }
            Text("mixed", style = MaterialTheme.typography.labelSmall, color = tc.muted)
        }
    }
}

/** A rounded pill, filled when [selected] (the panel's segments and focus chips). */
@Composable
fun Pill(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(CircleShape)
            .then(if (selected) Modifier.background(tc.pale) else Modifier.border(1.dp, tc.edge, CircleShape))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) NAVY else tc.muted,
            maxLines = 1,
        )
    }
}

/** The panel's segmented control: a rounded track with one filled segment. */
@Composable
fun Segments(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier
            .clip(CircleShape)
            .background(if (tc.dark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.7f))
            .border(1.dp, tc.edge, CircleShape)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        content = content,
    )
}

@Composable
fun RowScope.Segment(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .weight(1f)
            .clip(CircleShape)
            .then(if (selected) Modifier.background(tc.pale) else Modifier)
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) NAVY else tc.muted,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/** A glass card: the panel's rounded, edged bubble. */
@Composable
fun GlassCard(modifier: Modifier = Modifier, highlighted: Boolean = false, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier
            .clip(shape)
            .background(if (highlighted) (if (tc.dark) Color.White.copy(alpha = 0.18f) else tc.pale.copy(alpha = 0.25f)) else tc.card)
            .border(1.dp, if (highlighted) tc.pale else tc.edge, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp),
    ) { content() }
}

/** One word for the tower's phase and its colour, as the panel's header shows it. */
@Composable
fun phaseWord(phase: String?): Pair<String, Color> = when (phase) {
    "listening", "armed" -> "Listening" to tc.cyan
    "speaking", "announcing" -> "Speaking" to tc.peri
    "transcribing" -> "Understanding" to tc.sky
    "thinking" -> "Thinking" to tc.sky
    "blocked" -> "Needs you" to tc.rose
    "standby" -> "Standby" to tc.pale
    null -> "Off" to tc.grey
    else -> "Ready" to tc.pale
}

fun statusWord(status: String): String = when (status) {
    "done", "idle" -> "finished"
    "error" -> "hit an error"
    else -> status
}

fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"

/** "just now", "5 min ago", "3 h ago", "2 d ago". */
fun ago(ms: Long, now: Long = System.currentTimeMillis()): String {
    val s = (now - ms) / 1000
    return when {
        ms <= 0 -> ""
        s < 45 -> "just now"
        s < 3600 -> "${(s + 30) / 60} min ago"
        s < 86_400 -> "${s / 3600} h ago"
        else -> "${s / 86_400} d ago"
    }
}

/** A duration: "40 s", "12 min", "2 h 5 min". */
fun span(ms: Long): String {
    val s = ms / 1000
    return when {
        s < 60 -> "$s s"
        s < 3600 -> "${s / 60} min"
        else -> "${s / 3600} h ${(s % 3600) / 60} min"
    }
}

fun clock(ms: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

fun dayLabel(ms: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    val today = Calendar.getInstance()
    fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    if (same(c, today)) return "Today"
    today.add(Calendar.DAY_OF_YEAR, -1)
    if (same(c, today)) return "Yesterday"
    return SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(ms))
}

enum class OrbMood { Calm, Listening, Thinking, Speaking, Alert, Off }

/**
 * The orb, drawn: a glassy sphere tinted like the Mac's (pale and calm,
 * cyan and glowing while listening, periwinkle while speaking), breathing
 * slowly; [level] swells the ring while the microphone hears you.
 */
@Composable
fun Orb(mood: OrbMood, level: Float, modifier: Modifier = Modifier) {
    val t = tc
    val tint = when (mood) {
        OrbMood.Calm -> t.sky
        OrbMood.Listening -> t.cyan
        OrbMood.Thinking -> t.sky
        OrbMood.Speaking -> t.peri
        OrbMood.Alert -> t.amber
        OrbMood.Off -> t.grey
    }
    val glowing = mood == OrbMood.Listening || mood == OrbMood.Speaking
    val inf = rememberInfiniteTransition(label = "orb")
    val breath by inf.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(if (mood == OrbMood.Thinking) 900 else 2600, easing = LinearEasing), RepeatMode.Reverse),
        label = "breath",
    )
    val lvl by animateFloatAsState(level, tween(90), label = "level")
    Canvas(modifier) {
        val r = min(size.width, size.height) / 2f * 0.78f
        val c = center
        // Glow
        if (glowing || mood == OrbMood.Alert) {
            val g = r * (1.18f + 0.12f * breath + 0.35f * lvl)
            drawCircle(Brush.radialGradient(listOf(tint.copy(alpha = 0.45f), Color.Transparent), c, g), g, c)
        }
        // Body: dark core, tinted rim, a highlight up and to the left.
        val body = r * (1f + 0.02f * breath + 0.08f * lvl)
        drawCircle(
            Brush.radialGradient(
                listOf(Color(0xFF0E1628), Color(0xFF16233A), tint.copy(alpha = 0.85f)),
                center = c + Offset(-body * 0.18f, -body * 0.2f),
                radius = body * 1.25f,
            ),
            body, c,
        )
        drawCircle(
            Brush.radialGradient(listOf(Color.White.copy(alpha = 0.35f), Color.Transparent), c + Offset(-body * 0.35f, -body * 0.4f), body * 0.55f),
            body, c,
        )
        drawCircle(tint.copy(alpha = if (glowing) 0.95f else 0.6f), body, c, style = Stroke(width = body * (0.035f + 0.05f * lvl)))
    }
}
