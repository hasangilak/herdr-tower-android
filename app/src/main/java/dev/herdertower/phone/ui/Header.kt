package dev.herdertower.phone.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.herdertower.phone.orb.OrbGlyph
import androidx.compose.ui.unit.sp

/**
 * The tower-wide mute (`tower mute`: it says nothing, on the Mac or any
 * phone) and how to flip it; [enabled] is false while not connected or while
 * a change is on its way. The header's orb reads this.
 */
data class MuteState(val muted: Boolean = false, val enabled: Boolean = true, val toggle: () -> Unit = {})

val LocalMute = compositionLocalOf { MuteState() }

/** Whether the tower is talking (here or on the Mac), and how to stop this reply: the header's Stop reads this. */
data class StopState(val talking: Boolean = false, val onStop: () -> Unit = {})

val LocalStop = compositionLocalOf { StopState() }

/** The header's own height, below the status bar. */
val HEADER_HEIGHT = 56.dp

/** Where content starts so it isn't hidden under the header at first (it scrolls under it after). */
@Composable
fun headerTop(): Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + HEADER_HEIGHT

/**
 * A screen with the glass header: [content] fills the screen and scrolls
 * under the header, which shows it blurred (Android 12+) under a light tint
 * and a hairline. [header] is the bar's row: Close or the orb on the left,
 * actions on the right, and [StopSlot] last, always in the same place.
 */
@Composable
fun GlassScaffold(
    header: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    /** Something pinned under the header's row, inside the glass ("Active now" on Chat). */
    below: (@Composable () -> Unit)? = null,
    belowHeight: Dp = 0.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val blurred = rememberGraphicsLayer()
    // Made once, not on every draw.
    val radius = with(androidx.compose.ui.platform.LocalDensity.current) { 18.dp.toPx() }
    val blur = remember(radius) { BlurEffect(radius, radius, TileMode.Clamp) }
    val top = headerTop() + belowHeight
    val tint = tc.bgTop
    val edge = tc.edge
    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().drawWithContent {
                val h = top.toPx()
                // Sharp below the header; under it, only a blurred copy (the backdrop still shows through).
                clipRect(top = h) { this@drawWithContent.drawContent() }
                clipRect(bottom = h) {
                    if (canBlur) {
                        if (blurred.renderEffect !== blur) blurred.renderEffect = blur
                        blurred.record { this@drawWithContent.drawContent() }
                        drawLayer(blurred)
                    } else {
                        this@drawWithContent.drawContent()
                    }
                    drawRect(tint.copy(alpha = if (canBlur) 0.55f else 0.86f))
                }
                drawLine(edge, Offset(0f, h - 0.5f), Offset(size.width, h - 0.5f), strokeWidth = 1f)
            },
            content = content,
        )
        androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)) {
            Row(
                Modifier.fillMaxWidth().height(HEADER_HEIGHT).padding(start = 6.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = header,
            )
            if (below != null) Box(Modifier.fillMaxWidth().height(belowHeight)) { below() }
        }
    }
}

/** A round header button: 40dp to see, 48dp to touch. */
@Composable
fun HeaderButton(icon: ImageVector, label: String, badge: Int = 0, onClick: () -> Unit) {
    // Not clipped: the badge sits on the circle's corner and must show whole; the ripple stays round on its own.
    Box(
        Modifier.size(48.dp)
            .clickable(interactionSource = null, indication = ripple(bounded = false, radius = 24.dp), role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = if (badge > 0) "$label, $badge" else label },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(tc.card).border(1.dp, tc.edge, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(20.dp), tint = tc.text)
        }
        // A pill (round for one digit, wider for "9+"), ringed in the header's colour so it reads apart from the circle.
        if (badge > 0) Box(
            Modifier.align(Alignment.TopEnd).heightIn(min = 18.dp).widthIn(min = 18.dp)
                .border(1.5.dp, tc.bgTop, CircleShape).padding(1.5.dp).clip(CircleShape).background(tc.blocked).padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (badge > 9) "9+" else "$badge", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 14.sp),
                color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1,
            )
        }
    }
}

/** Close, always first in the header. */
@Composable
fun HeaderClose(onClose: () -> Unit) = HeaderButton(Icons.Filled.Close, "Close", onClick = onClose)

/** The header's title, between its buttons. */
@Composable
fun RowScope.HeaderTitle(text: String) {
    Text(
        text, Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.titleMedium, color = tc.text,
        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

/**
 * The tower's orb, as a switch: on, the whole tower is muted (it stops
 * talking now and says nothing, here or on the Mac, until switched off).
 * Muted, the orb is dimmed with a slash. [size] is the orb; the touch target
 * is at least 48dp. To stop only this reply, there's [StopSlot].
 */
@Composable
fun MuteOrb(size: Dp, modifier: Modifier = Modifier, status: String = "") {
    val mute = LocalMute.current
    Box(
        modifier.size(maxOf(size, 48.dp))
            .toggleable(
                value = mute.muted, enabled = mute.enabled, role = Role.Switch,
                interactionSource = null, indication = ripple(bounded = false, radius = size / 2 + 6.dp),
                onValueChange = { mute.toggle() },
            )
            .semantics {
                contentDescription = status + "Mute the tower, here and on the Mac"
                stateDescription = if (mute.muted) "Muted" else "Talking allowed"
            },
        contentAlignment = Alignment.Center,
    ) { OrbGlyph(Modifier.size(size), muted = mute.muted) }
}

/**
 * Stop, last in the header while the tower talks (here or on the Mac): it
 * slides in at the right edge then and takes no room otherwise, so the
 * header's own buttons sit flush at the right edge. It stops this reply
 * only; the orb mutes the tower.
 */
@Composable
fun StopSlot() {
    val stop = LocalStop.current
    val reduce = LocalReduceMotion.current
    AnimatedVisibility(
        visible = stop.talking,
        enter = if (reduce) EnterTransition.None else fadeIn(tween(180)) + expandHorizontally(tween(180), expandFrom = Alignment.Start),
        exit = if (reduce) ExitTransition.None else fadeOut(tween(180)) + shrinkHorizontally(tween(180), shrinkTowards = Alignment.Start),
    ) {
    Box(Modifier.heightIn(min = 48.dp).padding(start = 4.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier.heightIn(min = 40.dp).widthIn(min = 84.dp).clip(CircleShape).background(tc.text)
                .clickable(role = Role.Button, onClickLabel = "Stop the tower talking", onClick = stop.onStop)
                .semantics { contentDescription = "Stop the tower talking" }
                .padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Stop, null, Modifier.size(22.dp), tint = tc.bgTop)
            Spacer(Modifier.width(6.dp))
            Text("Stop", style = MaterialTheme.typography.titleSmall, color = tc.bgTop, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
    }
}
