package dev.herdertower.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Workspaces
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.herdertower.phone.api.Mood

/** Opens Settings: the top bar's gear, on every screen. */
val LocalOpenSettings = staticCompositionLocalOf<() -> Unit> { {} }

/** Whether the Mac is in reach now (the stream is live). */
val LocalReach = compositionLocalOf { false }

/** The four places of the app; everything else opens from one of them. */
enum class Tab(val label: String, val icon: ImageVector) {
    Queue("Queue", Icons.Filled.Inbox),
    Spaces("Spaces", Icons.Filled.Workspaces),
    Record("Record", Icons.Filled.History),
    Shots("Shots", Icons.Filled.PhotoLibrary),
}

/**
 * A screen's top: its large title, a line under it ([strong] first, in the
 * queue's amber, when something is the user's), then Stop while the tower
 * talks, the orb (tap: mute the tower) and Settings.
 */
@Composable
fun TopBar(title: String, sub: String? = null, strong: String? = null, onBack: (() -> Unit)? = null, settings: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = if (onBack != null) 6.dp else 20.dp, end = 6.dp, top = 6.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) HeaderButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onClick = onBack)
        Column(Modifier.weight(1f).padding(start = if (onBack != null) 4.dp else 0.dp, end = 6.dp)) {
            Text(
                title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall, color = tc.text,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (sub != null || strong != null) {
                val ink = tc.decisionInk
                Text(
                    buildAnnotatedString {
                        if (strong != null) withStyle(SpanStyle(color = ink, fontWeight = FontWeight.SemiBold)) { append(strong) }
                        if (strong != null && !sub.isNullOrBlank()) append(" · ")
                        append(sub.orEmpty())
                    },
                    style = MaterialTheme.typography.bodyMedium, color = tc.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        StopSlot()
        val reach = LocalReach.current
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            MuteOrb(30.dp, status = if (reach) "Tower, Mac in reach. " else "Tower, Mac out of reach. ")
            Dot(if (reach) tc.working else tc.idle, size = 10.dp, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 6.dp).border(2.dp, tc.bgTop, CircleShape))
        }
        if (settings) HeaderButton(Icons.Filled.Settings, "Settings", onClick = LocalOpenSettings.current)
    }
}

/** The four tabs; the queue's carries how many decisions wait, in amber. */
@Composable
fun BottomNav(current: Tab, waiting: Int, onTab: (Tab) -> Unit) {
    val edge = tc.edge
    Row(
        Modifier.fillMaxWidth().drawBehind { drawLine(edge, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1f) }.navigationBarsPadding(),
    ) {
        Tab.entries.forEach { t ->
            val on = t == current
            val count = if (t == Tab.Queue) waiting else 0
            Column(
                Modifier.weight(1f).heightIn(min = 60.dp)
                    .clickable(role = Role.Tab, onClickLabel = t.label) { onTab(t) }
                    .semantics {
                        selected = on
                        contentDescription = if (count > 0) "${t.label}, $count need you" else t.label
                    }
                    .padding(top = 8.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Box(Modifier.size(width = 44.dp, height = 24.dp)) {
                    Icon(t.icon, null, Modifier.align(Alignment.Center).size(22.dp), tint = if (on) tc.text else tc.faint)
                    if (count > 0) Box(
                        Modifier.align(Alignment.TopEnd).heightIn(min = 16.dp).widthIn(min = 16.dp).clip(CircleShape).background(tc.decision).padding(horizontal = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (count > 9) "9+" else "$count", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 13.sp), color = tc.onDecision, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
                Text(t.label, style = MaterialTheme.typography.labelMedium, color = if (on) tc.text else tc.faint, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
            }
        }
    }
}

/** A section's small heading ("SESSIONS", "BETWEEN SESSIONS · 33 MESSAGES"). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), modifier.padding(start = 4.dp, top = 6.dp).semantics { heading() },
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp), fontWeight = FontWeight.SemiBold, color = tc.faint,
    )
}

/**
 * A large answer: one tap. The [primary] one is the tower's pick, in amber;
 * [why] says in a line why one would choose it.
 */
@Composable
fun BigAnswer(label: String, primary: Boolean, modifier: Modifier = Modifier, why: String = "", enabled: Boolean = true, onClick: () -> Unit) {
    val shape = if (why.isBlank()) CircleShape else RoundedCornerShape(20.dp)
    val fg = when {
        !enabled -> tc.faint
        primary -> tc.onDecision
        else -> tc.text
    }
    Column(
        modifier.fillMaxWidth().heightIn(min = 54.dp).clip(shape)
            .then(if (primary && enabled) Modifier.background(tc.decision) else Modifier.border(1.dp, tc.edge, shape))
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { if (!enabled) disabled() }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = fg, fontWeight = FontWeight.SemiBold)
        if (why.isNotBlank()) Text(why, style = MaterialTheme.typography.bodySmall, color = if (primary && enabled) tc.onDecision.copy(alpha = 0.85f) else tc.muted)
    }
}

/** A pill button: amber when it's the screen's one action ([primary]), outlined otherwise. */
@Composable
fun PillButton(text: String, modifier: Modifier = Modifier, primary: Boolean = false, enabled: Boolean = true, icon: ImageVector? = null, onClick: () -> Unit) {
    val fg = when {
        !enabled -> tc.faint
        primary -> tc.onDecision
        else -> tc.text
    }
    Row(
        modifier.heightIn(min = 48.dp).clip(CircleShape)
            .then(if (primary && enabled) Modifier.background(tc.decision) else Modifier.border(1.dp, tc.edge, CircleShape))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { if (!enabled) disabled() }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        icon?.let {
            Icon(it, null, Modifier.size(18.dp), tint = fg)
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** One of a few choices side by side ("Phone", "Mac"): the chosen one filled. */
@Composable
fun Toggle(text: String, on: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = 44.dp).clip(CircleShape)
            .then(if (on) Modifier.background(tc.talk) else Modifier.border(1.dp, tc.edge, CircleShape))
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { selected = on }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (on) tc.onTalk else if (enabled) tc.muted else tc.faint, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** A small outlined label on a card ("Production data", "Finished"). */
@Composable
fun Tag(text: String, color: Color = tc.decisionInk, modifier: Modifier = Modifier) {
    Text(
        text, modifier.clip(CircleShape).border(1.dp, color.copy(alpha = 0.8f), CircleShape).padding(horizontal = 8.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

/** A quiet box with a dashed edge: "Nothing needs you." */
@Composable
fun EmptyBox(text: String, modifier: Modifier = Modifier) {
    val edge = tc.edge
    Box(
        modifier.fillMaxWidth().drawBehind {
            drawRoundRect(
                edge, style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(20.dp.toPx()),
            )
        }.padding(16.dp),
    ) { Text(text, style = MaterialTheme.typography.bodyLarge, color = tc.muted) }
}

/** A card as the design's: rounded, edged, a little lighter than the background. */
@Composable
fun Card(modifier: Modifier = Modifier, strong: Boolean = false, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier.fillMaxWidth().clip(shape)
            .background(if (strong) tc.card.copy(alpha = (tc.card.alpha * 1.7f).coerceAtMost(1f)) else tc.card)
            .border(if (strong) 1.5.dp else 1.dp, if (strong) tc.decisionInk.copy(alpha = 0.55f) else tc.edge, shape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(16.dp),
    ) { content() }
}

/**
 * How a space stands, as a small round sign: an amber number when it needs
 * the user (outlined when its decisions are muted), a dot while it works, a
 * tick when it's done or ready to close, a ring otherwise.
 */
@Composable
fun MoodGlyph(mood: Mood, needs: Int = 0, muted: Boolean = false, modifier: Modifier = Modifier) {
    Box(modifier.size(24.dp), contentAlignment = Alignment.Center) {
        when (mood) {
            Mood.Needs -> Box(
                Modifier.size(24.dp).clip(CircleShape).then(if (muted) Modifier.border(1.5.dp, tc.decisionInk, CircleShape) else Modifier.background(tc.decision)),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (needs > 9) "9+" else "$needs", style = MaterialTheme.typography.labelMedium, color = if (muted) tc.decisionInk else tc.onDecision, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Mood.Working -> Dot(tc.live, size = 10.dp)
            Mood.Ready, Mood.Done -> Icon(Icons.Filled.Check, null, Modifier.size(18.dp), tint = tc.live)
            Mood.Idle, Mood.Quiet -> Box(Modifier.size(10.dp).border(1.5.dp, tc.faint, CircleShape))
        }
    }
}

/** A row's two lines beside its sign: a name with a word at the right, and a line under them. */
@Composable
fun SignRow(sign: @Composable () -> Unit, name: String, right: String, sub: String, modifier: Modifier = Modifier, mono: String? = null, note: String? = null, onClick: (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.padding(top = 1.dp)) { sign() }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = tc.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (right.isNotBlank()) Text(right, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelMedium, color = tc.faint, maxLines = 1)
            }
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodyMedium, color = tc.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!mono.isNullOrBlank()) Text(mono, style = MaterialTheme.typography.labelSmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace), color = tc.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!note.isNullOrBlank()) Text(note, style = MaterialTheme.typography.labelMedium, color = tc.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
