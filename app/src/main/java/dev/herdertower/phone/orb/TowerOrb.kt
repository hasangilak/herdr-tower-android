package dev.herdertower.phone.orb

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.herdertower.phone.ui.LocalReduceMotion
import dev.herdertower.phone.ui.tc
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * The Mac's orb, running on the phone: the same physics ([Sim]), the same
 * phase choreography ([Drive]) and the same shader ([OrbShader]). This is
 * the frame loop of `render.rs`: advance the clock at the cue's speed, ease
 * the fade and cue, simulate, fill the uniforms.
 */
class OrbEngine(size: Double = 1.0) {
    val sim = Sim().also { it.o.size = size }
    private val drive = Drive()
    // The cue now, eased towards the phase's (as Cue.eased, kept in place so a frame allocates nothing).
    private val tint = Cue.CALM.tint.copyOf()
    private var tintAmount = Cue.CALM.tintAmount
    private val haloRgb = Cue.CALM.halo.copyOf()
    private var haloAmount = Cue.CALM.haloAmount
    private var speed = Cue.CALM.speed
    private var fade = 1.0
    private var time = 0.0
    private var realT = 0.0
    private val coeffs = FloatArray(48)
    private val maps by lazy { OrbMaps() }
    private val boilParams = OrbMaps.BoilParams()
    private val bitmaps by lazy {
        listOf(maps.lw to maps.lh, maps.lw to maps.lh, maps.bw to maps.bh, maps.bw to maps.bh).map { (w, h) -> createBitmap(w, h, Bitmap.Config.ALPHA_8) }
    }
    private val buffers by lazy { listOf(maps.liquidHi, maps.liquidLo, maps.boilHi, maps.boilLo).map { ByteBuffer.wrap(it) } }
    private var boilUploaded = false
    private val mapShaders by lazy {
        bitmaps.map { b ->
            BitmapShader(b, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.CLAMP).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) filterMode = BitmapShader.FILTER_MODE_LINEAR
            }
        }
    }

    fun enter(phase: String) = drive.enter(phase, sim)

    val phase: String get() = drive.phase

    /** A finger pressed the orb at (x, y) in radii from its centre (y up). */
    fun press(x: Double, y: Double) {
        if (kotlin.math.hypot(x - sim.pos[0], y - sim.pos[1]) < sim.o.radius * 1.1) sim.press(x, y)
    }

    fun advance(dtReal: Double) {
        val dt = min(dtReal, 0.1) // a pause waits; it doesn't fast-forward
        realT += dt
        time += dt * speed
        drive.tick(sim)
        sim.simulateTo(time)
        fade += (drive.fade(sim.t) - fade) * (1.0 - exp(-dt / 0.25))
        ease(drive.cue(), 1.0 - exp(-dt / 0.3))
    }

    private fun ease(to: Cue, k: Double) {
        val kf = k.toFloat()
        for (i in 0..2) {
            tint[i] = tint[i] + (to.tint[i] - tint[i]) * kf
            haloRgb[i] = haloRgb[i] + (to.halo[i] - haloRgb[i]) * kf
        }
        tintAmount += (to.tintAmount - tintAmount) * kf
        haloAmount += (to.haloAmount - haloAmount) * kf
        speed += (to.speed - speed) * k
    }

    /** Settles the orb without animating (reduce motion, and the probe). */
    fun settle() {
        repeat(30) { advance(0.1) }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun fill(s: RuntimeShader, w: Float, h: Float, dark: Boolean, mode: OrbShader.Mode = OrbShader.Mode.Maps, halo: Boolean = true) {
        val m = sim.rot
        s.setFloatUniform("rot0", m[0].toFloat(), m[3].toFloat(), m[6].toFloat(), 0f)
        s.setFloatUniform("rot1", m[1].toFloat(), m[4].toFloat(), m[7].toFloat(), 0f)
        s.setFloatUniform("rot2", m[2].toFloat(), m[5].toFloat(), m[8].toFloat(), 0f)
        s.setFloatUniform("center", sim.pos[0].toFloat(), sim.pos[1].toFloat(), sim.pos[2].toFloat(), sim.bound().toFloat())
        val o = sim.o
        sim.coeffs(coeffs)
        if (mode == OrbShader.Mode.Maps) {
            // liquid() and boil(), evaluated over directions on the CPU (see OrbMaps).
            boilParams.height = sim.boil
            boilParams.frequency = 7.0 / maxOf(o.bubbleSize, 0.1)
            boilParams.phase = sim.boilPhase
            sim.hotspotDir(boilParams.hot)
            boilParams.spread = sim.hotspotSpread()
            boilParams.shape = maxOf(o.bubbleShape, 0.5)
            val boilChanged = maps.update(coeffs, boilParams)
            // The boil map goes up again only when it changed (it's flat while nothing boils).
            val upload = if (boilChanged || !boilUploaded) 4 else 2
            for (i in 0 until upload) bitmaps[i].copyPixelsFromBuffer((buffers[i] as java.nio.Buffer).rewind())
            boilUploaded = true
            s.setInputShader("liquidHi", mapShaders[0])
            s.setInputShader("liquidLo", mapShaders[1])
            s.setInputShader("boilHi", mapShaders[2])
            s.setInputShader("boilLo", mapShaders[3])
            s.setFloatUniform("maps", maps.lw.toFloat(), maps.lh.toFloat(), maps.bw.toFloat(), maps.bh.toFloat())
        } else {
            s.setFloatUniform("sh", coeffs)
        }
        s.setFloatUniform("shape", o.radius.toFloat(), o.opening.toFloat(), o.rim.toFloat(), 0f)
        s.setFloatUniform("wall", o.wall[0].toFloat(), o.wall[1].toFloat(), o.wall[2].toFloat(), 0f)
        s.setFloatUniform("p1", o.density.toFloat(), o.specular.toFloat(), o.stepScale.toFloat(), (OrbShader.FOCAL * o.size).toFloat())
        // Dark screens get the glow look (black backdrop made see-through), light ones the light look (beige).
        s.setFloatUniform("p2", maxOf(o.bubbleShape, 0.5).toFloat(), if (dark) 1f else 0f, if (dark) 2f else 0f, 0f)
        s.setFloatUniform("res", w, h, 0f, 0f)
        s.setFloatUniform("outU", fade.toFloat(), 0f, 0f, 0f)
        s.setFloatUniform("tint", tint[0], tint[1], tint[2], tintAmount)
        val h = if (halo) (haloAmount * drive.haloPulse(sim, realT)).toFloat() else 0f
        s.setFloatUniform("halo", haloRgb[0], haloRgb[1], haloRgb[2], h)
    }

    // For the gradient orb: the cue's colours and the liquid's motion.
    fun tintColor() = Color(tint[0], tint[1], tint[2])
    fun haloColor() = Color(haloRgb[0], haloRgb[1], haloRgb[2], (haloAmount * drive.haloPulse(sim, realT)).toFloat().coerceIn(0f, 1f))
    fun alpha() = fade.toFloat()
    /** Where the cup's opening points (object +z in world space): the blobs follow it. */
    fun opening(): Offset = Offset(sim.rot[2].toFloat(), -sim.rot[5].toFloat())
    val clock: Double get() = sim.t
}

/** The red dot's colours on the Mac (Herdr's palette), with the ring round it. */
private val DOT_DARK = Color(0xFFF0705F)
private val DOT_LIGHT = Color(0xFFE0533F)

/** How far past its box the orb's canvas reaches (for the halo), and the orb's size in its box (1: the Mac's framing). */
private const val BLEED = 1.5f
private const val ORB_SIZE = 1.0

/**
 * The orb. [phase] is the tower's phase as the Mac's orb gets it (`idle`,
 * `listening`, `thinking`, `speaking`, `announcing`, `blocked`, `off`, …);
 * [needsYou] shows the Mac's red dot. [renderScale] draws the raymarch at a
 * fraction of the pixels and scales it up (it's soft anyway). How it's drawn
 * follows what [OrbProbe] found this device can do.
 */
@Composable
fun TowerOrb(
    phase: String,
    modifier: Modifier = Modifier,
    needsYou: Boolean = false,
    dark: Boolean = tc.dark,
    renderScale: Float = 0.5f,
    interactive: Boolean = true,
    description: String? = null,
    /** A tap on the orb (stopping the tower while it speaks); the press still ripples it. */
    onTap: (() -> Unit)? = null,
) {
    // The shader draws over BLEED times the box so the halo fades out before
    // its edge (as in the Mac's roomy window); the orb is sized to match.
    val engine = remember { OrbEngine(size = ORB_SIZE / BLEED) }
    val reduce = LocalReduceMotion.current
    val report by OrbProbe.report.collectAsStateWithLifecycle()
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(phase) { engine.enter(phase) }
    // No motion: each phase is still shown, settled and drawn once.
    LaunchedEffect(reduce, phase) {
        if (!reduce) return@LaunchedEffect
        engine.enter(phase)
        engine.settle()
        frame++
    }
    LaunchedEffect(reduce) {
        if (reduce) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = (now - last) / 1e9
                    engine.advance(dt)
                    OrbProbe.frame((dt * 1000).toFloat())
                }
                last = now
                frame++
            }
        }
    }

    val tap by androidx.compose.runtime.rememberUpdatedState(onTap)
    BoxWithConstraints(
        modifier.semantics {
            if (description != null) contentDescription = description
            // The tap, for a screen reader too (a pointer gesture alone isn't an action).
            if (interactive && onTap != null) onClick { tap?.invoke(); true }
        },
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val boxW = maxWidth
        val boxH = maxHeight
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val press = if (interactive) Modifier.pointerInput(Unit) {
            detectTapGestures(onTap = { tap?.invoke() }, onPress = { p ->
                val per = OrbShader.CAM_Z / (OrbShader.FOCAL * engine.sim.o.size) / (min(size.width, size.height) * BLEED)
                engine.press((p.x - size.width / 2.0) * per, -(p.y - size.height / 2.0) * per)
            })
        } else Modifier

        val tier = report?.tier ?: OrbTier.Gradient
        val mode = if (tier == OrbTier.Full) OrbShader.Mode.Maps else OrbShader.Mode.Analytic
        val shader = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && tier != OrbTier.Gradient) {
            remember(mode, report?.steps) { compile(mode, report?.steps ?: 160) }
        } else null
        if (shader != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Box(Modifier.fillMaxSize().then(press), contentAlignment = Alignment.Center) {
                ShaderOrb(engine, { frame }, shader as RuntimeShader, wPx * BLEED, hPx * BLEED, renderScale, dark, mode, Modifier.requiredSize(boxW * BLEED, boxH * BLEED))
            }
        } else {
            GradientOrb(engine, { frame }, dark, Modifier.fillMaxSize().then(press))
        }
        if (needsYou) {
            val d = min(maxWidth.value, maxHeight.value)
            val dot = maxOf(8f, d * 0.035f).dp
            // Where the Mac puts it: up and to the right of the orb.
            Box(
                Modifier
                    .offset((d * 0.27f).dp, (-d * 0.27f).dp)
                    .size(dot)
                    .clip(CircleShape)
                    .background(if (dark) DOT_DARK else DOT_LIGHT)
                    .border(2.dp, if (dark) Color(0xFF17191D) else Color.White, CircleShape),
            )
        }
    }
}

/** The shader for [mode], at [steps] or the most below it this device's AGSL accepts; null if none compiles. */
private fun compile(mode: OrbShader.Mode, steps: Int): Any? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    for (n in OrbShader.STEP_LADDER.filter { it <= steps }.ifEmpty { listOf(OrbShader.STEP_LADDER.last()) }) {
        runCatching { return RuntimeShader(OrbShader.source(n, mode)).also { OrbProbe.drawn(n) } }
            .onFailure { android.util.Log.w("TowerOrb", "${mode.name} shader, $n steps: ${it.message?.lineSequence()?.take(3)?.joinToString(" | ")}") }
    }
    return null
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun ShaderOrb(engine: OrbEngine, frame: () -> Long, shader: RuntimeShader, wPx: Float, hPx: Float, scale: Float, dark: Boolean, mode: OrbShader.Mode, modifier: Modifier) {
    val density = LocalDensity.current
    val w = (wPx * scale).coerceAtLeast(1f)
    val h = (hPx * scale).coerceAtLeast(1f)
    val brush = remember(shader) { ShaderBrush(shader) }
    Box(modifier) {
        // Rendered small into its own layer, then scaled up to fill the box.
        Canvas(
            Modifier
                .align(Alignment.TopStart)
                .requiredSize(with(density) { w.toDp() }, with(density) { h.toDp() })
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = 1f / scale
                    scaleY = 1f / scale
                },
        ) {
            frame()
            engine.fill(shader, size.width, size.height, dark, mode)
            drawRect(brush)
        }
    }
}

/**
 * The orb without a shader (before Android 13, or a GPU the probe found
 * wanting): the cue's halo, a navy glass body, three blobs of liquid drifting
 * with the sim's own motion inside it, a lit rim and a highlight. Not the
 * raymarched cup, but the same colours, moods and movement.
 */
@Composable
private fun GradientOrb(engine: OrbEngine, frame: () -> Long, dark: Boolean, modifier: Modifier) {
    Canvas(modifier) {
        frame()
        val r = min(size.width, size.height) * 0.33f
        val c = center
        val a = engine.alpha()
        val tint = engine.tintColor()
        val halo = engine.haloColor()
        val t = engine.clock.toFloat()
        val open = engine.opening()
        if (halo.alpha > 0f) {
            drawCircle(Brush.radialGradient(listOf(halo.copy(alpha = halo.alpha * a * 0.9f), halo.copy(alpha = 0f)), c, r * 1.6f), r * 1.6f, c)
        }
        // The body: deep navy, lighter towards the rim (the glass seen edge-on).
        drawCircle(
            Brush.radialGradient(
                0f to Color(0xFF070B18), 0.55f to Color(0xFF14224A), 0.85f to Color(0xFF2F5291), 1f to tint.copy(alpha = 0.95f),
                center = c + Offset(open.x * r * 0.15f, open.y * r * 0.15f), radius = r * 1.05f,
            ),
            r, c, alpha = a,
        )
        // The liquid: soft blobs circling inside, pulled along by the cup's turn.
        val clip = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, r * 0.97f)) }
        clipPath(clip) {
            for (i in 0 until 3) {
                val ph = t * (0.35f + i * 0.13f) + i * 2.1f
                val p = c + Offset(
                    (cos(ph) * 0.45f + open.x * 0.25f) * r,
                    (sin(ph * 1.3f) * 0.38f + open.y * 0.25f) * r,
                )
                val br = r * (0.55f + 0.12f * sin(ph * 0.7f))
                val col = if (i == 1) Color(0xFFA5B9D9) else tint
                drawCircle(Brush.radialGradient(listOf(col.copy(alpha = 0.42f * a), col.copy(alpha = 0f)), p, br), br, p)
            }
        }
        // The rim: bright where it faces up and left, as the glow look's edges.
        drawCircle(
            Brush.sweepGradient(listOf(Color(0xFFECE4F0), tint, Color(0xFF2F5291), tint, Color(0xFFECE4F0)), c),
            r * 0.985f, c, alpha = 0.85f * a, style = Stroke(r * 0.045f),
        )
        drawCircle(
            Brush.radialGradient(listOf(Color.White.copy(alpha = 0.32f * a), Color.Transparent), c + Offset(-r * 0.38f, -r * 0.42f), r * 0.45f),
            r * 0.45f, c + Offset(-r * 0.38f, -r * 0.42f),
        )
        if (!dark) drawCircle(Color(0xFF162966).copy(alpha = 0.25f * a), r, c, style = Stroke(r * 0.02f))
    }
}

/**
 * A small static orb (chat avatars, the home greeting): the glow look's
 * colours without the raymarch. [muted]: dimmed, with a thin slash across.
 */
@Composable
fun OrbGlyph(modifier: Modifier = Modifier, dark: Boolean = tc.dark, muted: Boolean = false) {
    Canvas(modifier.graphicsLayer { alpha = if (muted) 0.45f else 1f }) {
        val r = min(size.width, size.height) / 2f
        val c = center
        drawCircle(
            Brush.radialGradient(
                if (dark) listOf(Color(0xFF0A1026), Color(0xFF182546), Color(0xFF2F5291), Color(0xFFA5B9D9))
                else listOf(Color(0xFF1A1C24), Color(0xFF162966), Color(0xFF3669AB), Color(0xFFC1B9B3)),
                c + Offset(-r * 0.2f, -r * 0.25f), r * 1.2f,
            ),
            r, c,
        )
        drawCircle(Color(0xFFECE4F0).copy(alpha = 0.8f), r * 0.96f, c, style = Stroke(r * 0.08f))
        if (muted) {
            val d = r * 0.62f
            drawLine(Color(0xFFECE4F0), c + Offset(-d, -d), c + Offset(d, d), strokeWidth = r * 0.12f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}
