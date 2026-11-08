package dev.herdertower.phone.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * The orb panel's colours (`src/orb/panel.rs`): the orb's own accents on
 * a cool navy glass. Light mode keeps the hues, deepened enough to read on
 * a pale background.
 */
@Immutable
data class TowerColors(
    val dark: Boolean,
    val pale: Color,
    val cyan: Color,
    val peri: Color,
    val sky: Color,
    val amber: Color,
    val rose: Color,
    val grey: Color,
    val navy: Color,
    val text: Color,
    val muted: Color,
    val faint: Color,
    val edge: Color,
    val bubbleMe: Color,
    val bubbleTower: Color,
    val card: Color,
    val glassTop: Color,
    val glassBottom: Color,
    val screen: Color,
    val screenText: Color,
) {
    val glass: Brush get() = Brush.verticalGradient(listOf(glassTop, glassBottom))

    /** A session's or job's status, as the panel colours it. */
    fun status(status: String?): Color = when (status) {
        "working" -> cyan
        "blocked" -> amber
        "done", "idle" -> pale
        "failed", "error", "exited" -> rose
        else -> grey
    }
}

// The orb's constants.
private val PALE = Color(0xFFCCE0FF)
private val CYAN = Color(0xFF59D9FF)
private val PERI = Color(0xFFA39EFF)
private val SKY = Color(0xFF8EC5FF)
private val AMBER = Color(0xFFFFCF70)
private val ROSE = Color(0xFFFF8F8F)
private val GREY = Color(0xFF9AA3B2)
val NAVY = Color(0xFF16233A)

val DarkTower = TowerColors(
    dark = true,
    pale = PALE, cyan = CYAN, peri = PERI, sky = SKY, amber = AMBER, rose = ROSE, grey = GREY, navy = NAVY,
    text = Color.White,
    muted = Color.White.copy(alpha = 0.80f),
    faint = Color.White.copy(alpha = 0.60f),
    edge = Color.White.copy(alpha = 0.22f),
    bubbleMe = Color.White.copy(alpha = 0.22f),
    bubbleTower = Color.White.copy(alpha = 0.10f),
    card = Color.White.copy(alpha = 0.08f),
    glassTop = Color(0xFF2A3A57),
    glassBottom = Color(0xFF121A2B),
    screen = Color(0xFF0B1020),
    screenText = Color(0xFFDDE6F5),
)

val LightTower = TowerColors(
    dark = false,
    pale = Color(0xFF7D9CD1), cyan = Color(0xFF1597C9), peri = Color(0xFF6F68E0), sky = Color(0xFF3F8EDB),
    amber = Color(0xFFC98A12), rose = Color(0xFFD94F4F), grey = Color(0xFF8A93A3), navy = NAVY,
    text = Color(0xFF1D2330),
    muted = Color(0xFF4F5868),
    faint = Color(0xFF8A93A3),
    edge = Color(0xFF1D2330).copy(alpha = 0.14f),
    bubbleMe = Color(0xFF4A6280).copy(alpha = 0.18f),
    bubbleTower = Color.White,
    card = Color.White,
    glassTop = Color(0xFFF6F8FC),
    glassBottom = Color(0xFFE3E9F2),
    screen = Color(0xFF16233A),
    screenText = Color(0xFFDDE6F5),
)

val LocalTower = staticCompositionLocalOf { DarkTower }

/** Shortcut: `tc.cyan`. */
val tc: TowerColors @Composable get() = LocalTower.current

@Composable
fun TowerTheme(mode: String = "system", content: @Composable () -> Unit) {
    val dark = when (mode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val t = if (dark) DarkTower else LightTower
    val scheme = if (dark) {
        darkColorScheme(
            primary = CYAN, onPrimary = NAVY, secondary = PERI, onSecondary = NAVY, tertiary = AMBER, onTertiary = NAVY,
            error = ROSE, onError = NAVY,
            background = t.glassBottom, onBackground = t.text,
            surface = Color(0xFF18223A), onSurface = t.text,
            surfaceVariant = Color(0xFF243150), onSurfaceVariant = t.muted,
            surfaceContainer = Color(0xFF1B2640), surfaceContainerHigh = Color(0xFF223050), surfaceContainerHighest = Color(0xFF29385A),
            surfaceContainerLow = Color(0xFF172038), surfaceContainerLowest = Color(0xFF10172A),
            outline = t.edge, outlineVariant = t.edge,
            primaryContainer = Color(0xFF1E4A66), onPrimaryContainer = PALE,
            secondaryContainer = Color(0xFF34406A), onSecondaryContainer = PALE,
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF0B7FB0), onPrimary = Color.White, secondary = t.peri, onSecondary = Color.White,
            tertiary = t.amber, onTertiary = Color.White, error = t.rose, onError = Color.White,
            background = t.glassBottom, onBackground = t.text,
            surface = Color(0xFFF7F9FC), onSurface = t.text,
            surfaceVariant = Color(0xFFE3E8F0), onSurfaceVariant = t.muted,
            surfaceContainer = Color(0xFFEEF1F5), surfaceContainerHigh = Color(0xFFE6EAF1), surfaceContainerHighest = Color(0xFFDDE3EC),
            surfaceContainerLow = Color(0xFFF3F5F9), surfaceContainerLowest = Color.White,
            outline = Color(0xFFC3CAD6), outlineVariant = Color(0xFFD5DAE2),
            primaryContainer = Color(0xFFD4ECF8), onPrimaryContainer = NAVY,
            secondaryContainer = Color(0xFFDCE4F5), onSecondaryContainer = NAVY,
        )
    }
    CompositionLocalProvider(LocalTower provides t) {
        MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
    }
}
