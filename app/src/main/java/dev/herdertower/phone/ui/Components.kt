package dev.herdertower.phone.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

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
    val base = modifier
        .widthIn(max = 120.dp)
        .clip(CircleShape)
        .semantics { contentDescription = "Account $account" + if (isDefault) " (default)" else "" }
    if (isDefault) {
        Text(
            account, base.border(1.dp, tc.edge, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall, color = tc.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    } else {
        Text(
            account, base.background(Color(0xFF000000 or AccountHue.of(account))).padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium, color = NAVY, maxLines = 1, overflow = TextOverflow.Ellipsis,
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
            Modifier.clip(CircleShape).border(1.dp, tc.edge, CircleShape).padding(horizontal = 8.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            distinct.take(3).forEach { Dot(if (it == default) tc.muted else Color(0xFF000000 or AccountHue.of(it)), 5.dp) }
            Text("mixed", style = MaterialTheme.typography.labelSmall, color = tc.muted)
        }
    }
}

/** A round glass icon button (the top bar's alerts and menu). */
@Composable
fun RoundIcon(icon: ImageVector, label: String, modifier: Modifier = Modifier, badge: Int = 0, size: Dp = 44.dp, onClick: () -> Unit) {
    Box(modifier) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(tc.card)
                .border(1.dp, tc.edge, CircleShape)
                .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
                .semantics { contentDescription = if (badge > 0) "$label, $badge" else label },
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(20.dp), tint = tc.text) }
        if (badge > 0) {
            Box(
                Modifier.align(Alignment.TopEnd).size(18.dp).clip(CircleShape).background(tc.blocked),
                contentAlignment = Alignment.Center,
            ) { Text(if (badge > 9) "9+" else "$badge", style = MaterialTheme.typography.labelSmall, color = Color.White, fontWeight = FontWeight.Bold) }
        }
    }
}

/** The copper pill at the top of the voice and chat screens. */
@Composable
fun ClosePill(text: String = "Close", modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .clip(CircleShape)
            .background(tc.card)
            .border(1.dp, tc.edge, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Filled.Close, null, Modifier.size(16.dp), tint = tc.text)
        Text(text, style = MaterialTheme.typography.labelLarge, color = tc.text)
    }
}

/** A rounded pill, filled when [selected]. */
@Composable
fun Pill(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(CircleShape)
            .then(if (selected) Modifier.background(tc.accentBrush) else Modifier.border(1.dp, tc.edge, CircleShape))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) tc.onAccent else tc.muted, maxLines = 1,
        )
    }
}

@Composable
fun Segments(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.clip(CircleShape).background(tc.card).border(1.dp, tc.edge, CircleShape).padding(4.dp),
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
            .then(if (selected) Modifier.background(tc.accentBrush) else Modifier)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, style = MaterialTheme.typography.labelLarge,
            color = if (selected) tc.onAccent else tc.muted,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal, maxLines = 1,
        )
    }
}

/** A glass card: rounded, edged, translucent. */
@Composable
fun GlassCard(modifier: Modifier = Modifier, highlighted: Boolean = false, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier
            .clip(shape)
            .background(if (highlighted) tc.accent.copy(alpha = if (tc.dark) 0.22f else 0.14f) else tc.card)
            .border(1.dp, if (highlighted) tc.accentHi.copy(alpha = 0.6f) else tc.edge, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(18.dp),
    ) { content() }
}

/** One word for the tower's phase and its colour. */
@Composable
fun phaseWord(phase: String?): Pair<String, Color> = when (phase) {
    "listening", "armed" -> "Listening" to tc.working
    "speaking", "announcing" -> "Speaking" to tc.accentHi
    "transcribing" -> "Understanding" to tc.accentHi
    "thinking" -> "Thinking" to tc.accentHi
    "blocked" -> "Needs you" to tc.blocked
    "standby" -> "Standby" to tc.done
    null, "off" -> "Not connected" to tc.idle
    else -> "Ready" to tc.done
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

/** The header of a screen opened from the menu: a round back button and a large title. */
@Composable
fun SubHeader(title: String, subtitle: String? = null, onBack: () -> Unit) {
    Row(
        Modifier.padding(start = 16.dp, end = 20.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundIcon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, "Back", onClick = onBack)
        androidx.compose.foundation.layout.Spacer(Modifier.size(14.dp))
        androidx.compose.foundation.layout.Column {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = tc.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = tc.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}
