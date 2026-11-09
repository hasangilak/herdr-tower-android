package dev.herdertower.phone.ui

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The tower's warm look: copper light falling into black (dark), or a soft
 * peach into cream (light). Every text colour here was checked for at least
 * 4.5:1 contrast against the backgrounds it's used on.
 */
@Immutable
data class TowerColors(
    val dark: Boolean,
    val bgTop: Color,
    val bgMid: Color,
    val bgBottom: Color,
    val text: Color,
    val muted: Color,
    val faint: Color,
    val edge: Color,
    val card: Color,
    val cardStrong: Color,
    val accent: Color,
    val accentHi: Color,
    val onAccent: Color,
    val bubbleTop: Color,
    val bubbleBottom: Color,
    val onBubble: Color,
    val reply: Color,
    val working: Color,
    val blocked: Color,
    val done: Color,
    val failed: Color,
    val idle: Color,
    val screen: Color,
    val screenText: Color,
    val scrim: Color,
) {
    /** A session's or job's status. */
    fun status(status: String?): Color = when (status) {
        "working" -> working
        "blocked" -> blocked
        "done", "idle" -> done
        "failed", "error", "exited" -> failed
        else -> idle
    }

    val userBubble: Brush get() = Brush.linearGradient(listOf(bubbleTop, bubbleBottom))
    val accentBrush: Brush get() = Brush.linearGradient(listOf(accentHi, accent))
}

val DarkTower = TowerColors(
    dark = true,
    bgTop = Color(0xFF9A4316),
    bgMid = Color(0xFF4A1C0A),
    bgBottom = Color(0xFF070504),
    text = Color(0xFFFFF6EE),
    muted = Color(0xFFFFF6EE).copy(alpha = 0.82f),
    faint = Color(0xFFFFF6EE).copy(alpha = 0.60f),
    edge = Color.White.copy(alpha = 0.12f),
    card = Color.White.copy(alpha = 0.07f),
    cardStrong = Color(0xFF1C1411).copy(alpha = 0.88f),
    accent = Color(0xFFE2681F),
    accentHi = Color(0xFFFFA057),
    onAccent = Color(0xFF1C0B03),
    bubbleTop = Color(0xFFC2541B),
    bubbleBottom = Color(0xFFA8420F),
    onBubble = Color.White,
    reply = Color(0xFF1E1512).copy(alpha = 0.92f),
    working = Color(0xFF6FD3C1),
    blocked = Color(0xFFFF7A5C),
    done = Color(0xFFE6CFB0),
    failed = Color(0xFFF0506E),
    idle = Color(0xFFA39689),
    screen = Color(0xFF0E0907),
    screenText = Color(0xFFF3E6DA),
    scrim = Color.Black.copy(alpha = 0.55f),
)

val LightTower = TowerColors(
    dark = false,
    bgTop = Color(0xFFF3B57E),
    bgMid = Color(0xFFF8DCC0),
    bgBottom = Color(0xFFFBF4EC),
    text = Color(0xFF2B1810),
    muted = Color(0xFF5E4738),
    faint = Color(0xFF7A6555),
    edge = Color(0xFF2B1810).copy(alpha = 0.12f),
    card = Color.White.copy(alpha = 0.62f),
    cardStrong = Color.White.copy(alpha = 0.92f),
    accent = Color(0xFFBC5216),
    accentHi = Color(0xFFE07A30),
    onAccent = Color.White,
    bubbleTop = Color(0xFFBC5216),
    bubbleBottom = Color(0xFFA8420F),
    onBubble = Color.White,
    reply = Color.White.copy(alpha = 0.88f),
    working = Color(0xFF0F7A6C),
    blocked = Color(0xFFC23D23),
    done = Color(0xFF7F5E3A),
    failed = Color(0xFFB81F42),
    idle = Color(0xFF7A6B60),
    screen = Color(0xFF2A1A12),
    screenText = Color(0xFFF7EADF),
    scrim = Color.Black.copy(alpha = 0.35f),
)

val NAVY = Color(0xFF16233A) // text on the Mac's account hues

val LocalTower = staticCompositionLocalOf { DarkTower }

/** True when the system asks for no animation ("Remove animations", or animator scale 0). */
val LocalReduceMotion = staticCompositionLocalOf { false }

/** Shortcut: `tc.accent`. */
val tc: TowerColors @Composable get() = LocalTower.current

/** Large, soft type: big headlines with tight tracking, roomy body text. */
private val TowerType = Typography().let { t ->
    t.copy(
        displaySmall = t.displaySmall.copy(fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-1).sp, fontWeight = FontWeight.Medium),
        headlineMedium = t.headlineMedium.copy(fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.5).sp, fontWeight = FontWeight.Medium),
        headlineSmall = t.headlineSmall.copy(fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.3).sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = (-0.2).sp),
        bodyLarge = t.bodyLarge.copy(lineHeight = 24.sp, letterSpacing = 0.sp),
        bodyMedium = t.bodyMedium.copy(letterSpacing = 0.sp),
    )
}

/** The transcript on the voice screen. */
val TranscriptStyle = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = (-0.4).sp, fontWeight = FontWeight.Medium)

@Composable
fun TowerTheme(mode: String = "system", content: @Composable () -> Unit) {
    val dark = when (mode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val t = if (dark) DarkTower else LightTower
    val context = LocalContext.current
    // Followed while the app runs: the setting can change from the quick settings.
    var reduce by remember { mutableStateOf(animationsOff(context)) }
    DisposableEffect(context) {
        val watch = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduce = animationsOff(context)
            }
        }
        val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        runCatching { context.contentResolver.registerContentObserver(uri, false, watch) }
        reduce = animationsOff(context)
        onDispose { runCatching { context.contentResolver.unregisterContentObserver(watch) } }
    }
    val scheme = if (dark) {
        darkColorScheme(
            primary = t.accentHi, onPrimary = t.onAccent, secondary = t.accent, onSecondary = t.onAccent,
            tertiary = t.working, error = t.failed,
            background = t.bgBottom, onBackground = t.text,
            surface = Color(0xFF1A1310), onSurface = t.text, surfaceVariant = Color(0xFF2A1E19), onSurfaceVariant = t.muted,
            surfaceContainerLowest = Color(0xFF0E0A08), surfaceContainerLow = Color(0xFF16100D), surfaceContainer = Color(0xFF1C1512),
            surfaceContainerHigh = Color(0xFF241B17), surfaceContainerHighest = Color(0xFF2C221D),
            outline = t.edge, outlineVariant = t.edge,
            primaryContainer = Color(0xFF5A2A10), onPrimaryContainer = t.text,
            secondaryContainer = Color(0xFF3A2216), onSecondaryContainer = t.text,
        )
    } else {
        lightColorScheme(
            primary = t.accent, onPrimary = Color.White, secondary = t.accentHi, onSecondary = t.text,
            tertiary = t.working, error = t.failed,
            background = t.bgBottom, onBackground = t.text,
            surface = Color(0xFFFFF9F3), onSurface = t.text, surfaceVariant = Color(0xFFF3E4D6), onSurfaceVariant = t.muted,
            surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFFF7EF), surfaceContainer = Color(0xFFFBEFE3),
            surfaceContainerHigh = Color(0xFFF7E8DA), surfaceContainerHighest = Color(0xFFF2E1D1),
            outline = Color(0xFFD9C3B1), outlineVariant = Color(0xFFE8D8CA),
            primaryContainer = Color(0xFFFBD9BE), onPrimaryContainer = t.text,
            secondaryContainer = Color(0xFFF6E2D0), onSecondaryContainer = t.text,
        )
    }
    CompositionLocalProvider(LocalTower provides t, LocalReduceMotion provides reduce) {
        MaterialTheme(colorScheme = scheme, typography = TowerType, content = content)
    }
}

private fun animationsOff(context: Context): Boolean =
    runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)

/** The backdrop's glow for the orb's phase: the Mac cue's colour, or copper when calm. */
fun glowFor(phase: String, needsYou: Boolean): Color = when {
    phase == "listening" || phase == "armed" -> Color(0xFF59D9FF)
    phase == "speaking" -> Color(0xFFA39EFF)
    phase == "announcing" -> Color(0xFFFFB347)
    phase == "blocked" || needsYou -> Color(0xFFF0705F)
    phase == "thinking" || phase == "transcribing" -> Color(0xFFFFA860)
    phase == "off" -> Color(0xFF8A7B70)
    else -> Color(0xFFE0702A)
}

/**
 * The background: copper light from the top falling into black (or peach
 * into cream), with a glow in the current phase's colour that shifts slowly.
 */
@Composable
fun Backdrop(phase: String, needsYou: Boolean, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val t = tc
    val reduce = LocalReduceMotion.current
    val glow by animateColorAsState(
        glowFor(phase, needsYou).copy(alpha = if (t.dark) 0.42f else 0.30f),
        if (reduce) tween(0) else tween(1600),
        label = "backdrop-glow",
    )
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0f to t.bgTop, 0.38f to t.bgMid, 0.75f to t.bgBottom))
            .drawBehind {
                drawRect(Brush.radialGradient(listOf(glow, Color.Transparent), center = Offset(size.width / 2, size.height * 0.08f), radius = size.height * 0.6f))
            },
    ) {
        // The background fills the whole screen; content keeps clear of a cutout at the side (landscape).
        Box(
            Modifier.fillMaxSize().windowInsetsPadding(
                androidx.compose.foundation.layout.WindowInsets.displayCutout.only(androidx.compose.foundation.layout.WindowInsetsSides.Horizontal),
            ),
            content = content,
        )
    }
}
