package dev.herdertower.phone.orb

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
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.herdertower.phone.ui.LocalReduceMotion
import dev.herdertower.phone.ui.tc
import kotlin.math.exp
import kotlin.math.min

/**
 * The Mac's orb, running on the phone: the same physics ([Sim]), the same
 * phase choreography ([Drive]) and the same shader ([OrbShader]). This is
 * the frame loop of `render.rs`: advance the clock at the cue's speed, ease
 * the fade and cue, simulate, fill the uniforms.
 */
class OrbEngine(size: Double = 1.0) {
    val sim = Sim().also { it.o.size = size }
    private val drive = Drive()
    private var cue = Cue.CALM
    private var fade = 1.0
    private var time = 0.0
    private var realT = 0.0
    private val coeffs = FloatArray(48)
    private val maps = OrbMaps()
    private val boilParams = OrbMaps.BoilParams()
    private val liquidBitmap by lazy { android.graphics.Bitmap.createBitmap(maps.lw, maps.lh, android.graphics.Bitmap.Config.ARGB_8888) }
    private val boilBitmap by lazy { android.graphics.Bitmap.createBitmap(maps.bw, maps.bh, android.graphics.Bitmap.Config.ARGB_8888) }
    private val liquidShader by lazy { mapShader(liquidBitmap) }
    private val boilShader by lazy { mapShader(boilBitmap) }

    /** Repeats round the longitude, clamps at the poles, filtered linearly. */
    private fun mapShader(b: android.graphics.Bitmap) =
        android.graphics.BitmapShader(b, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.CLAMP).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) filterMode = android.graphics.BitmapShader.FILTER_MODE_LINEAR
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
        time += dt * cue.speed
        drive.tick(sim)
        sim.simulateTo(time)
        fade += (drive.fade(sim.t) - fade) * (1.0 - exp(-dt / 0.25))
        cue = cue.eased(drive.cue(), 1.0 - exp(-dt / 0.3))
    }

    /** Settles the orb without animating (reduce motion). */
    fun settle() {
        repeat(30) { advance(0.1) }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun fill(s: RuntimeShader, w: Float, h: Float, dark: Boolean) {
        val m = sim.rot
        s.setFloatUniform("rot0", m[0].toFloat(), m[3].toFloat(), m[6].toFloat(), 0f)
        s.setFloatUniform("rot1", m[1].toFloat(), m[4].toFloat(), m[7].toFloat(), 0f)
        s.setFloatUniform("rot2", m[2].toFloat(), m[5].toFloat(), m[8].toFloat(), 0f)
        s.setFloatUniform("center", sim.pos[0].toFloat(), sim.pos[1].toFloat(), sim.pos[2].toFloat(), sim.bound().toFloat())
        val o = sim.o
        // liquid() and boil(), evaluated over directions on the CPU (see OrbMaps).
        sim.coeffs(coeffs)
        boilParams.height = sim.boil
        boilParams.frequency = 7.0 / maxOf(o.bubbleSize, 0.1)
        boilParams.phase = sim.boilPhase
        boilParams.hot = sim.hotspotDir()
        boilParams.spread = sim.hotspotSpread()
        boilParams.shape = maxOf(o.bubbleShape, 0.5)
        maps.update(coeffs, boilParams)
        liquidBitmap.setPixels(maps.liquidPixels, 0, maps.lw, 0, 0, maps.lw, maps.lh)
        boilBitmap.setPixels(maps.boilPixels, 0, maps.bw, 0, 0, maps.bw, maps.bh)
        s.setInputShader("liquidMap", liquidShader)
        s.setInputShader("boilMap", boilShader)
        s.setFloatUniform("maps", maps.lw.toFloat(), maps.lh.toFloat(), maps.bw.toFloat(), maps.bh.toFloat())
        s.setFloatUniform("shape", o.radius.toFloat(), o.opening.toFloat(), o.rim.toFloat(), 0f)
        s.setFloatUniform("wall", o.wall[0].toFloat(), o.wall[1].toFloat(), o.wall[2].toFloat(), 0f)
        s.setFloatUniform("p1", o.density.toFloat(), o.specular.toFloat(), o.stepScale.toFloat(), (OrbShader.FOCAL * o.size).toFloat())
        // Dark screens get the glow look (black backdrop made see-through), light ones the light look (beige).
        s.setFloatUniform("p2", maxOf(o.bubbleShape, 0.5).toFloat(), if (dark) 1f else 0f, if (dark) 2f else 0f, 0f)
        s.setFloatUniform("res", w, h, 0f, 0f)
        s.setFloatUniform("outU", fade.toFloat(), 0f, 0f, 0f)
        s.setFloatUniform("tint", cue.tint[0], cue.tint[1], cue.tint[2], cue.tintAmount)
        val haloAmount = (cue.haloAmount * drive.haloPulse(sim, realT)).toFloat()
        s.setFloatUniform("halo", cue.halo[0], cue.halo[1], cue.halo[2], haloAmount)
    }

    /** Colours for the fallback drawing (before Android 13): the cue's tint and halo. */
    fun tintColor() = Color(cue.tint[0], cue.tint[1], cue.tint[2])
    fun haloColor() = Color(cue.halo[0], cue.halo[1], cue.halo[2], (cue.haloAmount * drive.haloPulse(sim, realT)).toFloat().coerceIn(0f, 1f))
    fun alpha() = fade.toFloat()
}

/** Compiles the orb's shader once: the Mac's 160 steps, or the most this device's AGSL accepts. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private object OrbShaders {
    private var tried = false
    private var steps = 0

    fun get(): RuntimeShader? {
        if (tried) return if (steps > 0) RuntimeShader(OrbShader.source(steps)) else null
        tried = true
        for (n in OrbShader.STEP_LADDER) {
            val s = runCatching { RuntimeShader(OrbShader.source(n)) }
                .onFailure { android.util.Log.i("TowerOrb", "orb shader with $n steps: ${it.message?.lineSequence()?.firstOrNull()}") }
                .getOrNull()
            if (s != null) {
                steps = n
                android.util.Log.i("TowerOrb", "orb shader: $n steps")
                return s
            }
        }
        return null
    }
}

/** How far past its box the orb's canvas reaches (for the halo), and the orb's size in its box (1: the Mac's framing). */
private const val BLEED = 1.5f
private const val ORB_SIZE = 1.0

/** The red dot's colours on the Mac (Herdr's palette), with the ring round it. */
private val DOT_DARK = Color(0xFFF0705F)
private val DOT_LIGHT = Color(0xFFE0533F)

/**
 * The orb. [phase] is the tower's phase as the Mac's orb gets it (`idle`,
 * `listening`, `thinking`, `speaking`, `announcing`, `blocked`, `off`, …);
 * [needsYou] shows the Mac's red dot. [renderScale] draws the raymarch at a
 * fraction of the pixels and scales it up (it's soft anyway).
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
) {
    // The shader draws over BLEED times the box so the halo fades out before
    // its edge (as in the Mac's roomy window); the orb is sized to match.
    val engine = remember { OrbEngine(size = ORB_SIZE / BLEED) }
    val reduce = LocalReduceMotion.current
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(phase) { engine.enter(phase) }
    LaunchedEffect(reduce) {
        if (reduce) {
            engine.settle()
            frame++
            return@LaunchedEffect
        }
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) engine.advance((now - last) / 1e9)
                last = now
                frame++
            }
        }
    }

    BoxWithConstraints(
        modifier.then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val boxW = maxWidth
        val boxH = maxHeight
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val press = if (interactive) Modifier.pointerInput(Unit) {
            detectTapGestures(onPress = { p ->
                val per = OrbShader.CAM_Z / (OrbShader.FOCAL * engine.sim.o.size) / (min(size.width, size.height) * BLEED)
                engine.press((p.x - size.width / 2.0) * per, -(p.y - size.height / 2.0) * per)
            })
        } else Modifier

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Box(Modifier.fillMaxSize().then(press), contentAlignment = Alignment.Center) {
                ShaderOrb(engine, { frame }, wPx * BLEED, hPx * BLEED, renderScale, dark, Modifier.requiredSize(boxW * BLEED, boxH * BLEED))
            }
        } else {
            FallbackOrb(engine, { frame }, Modifier.fillMaxSize().then(press))
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

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun ShaderOrb(engine: OrbEngine, frame: () -> Long, wPx: Float, hPx: Float, scale: Float, dark: Boolean, modifier: Modifier) {
    val shader = remember { OrbShaders.get() }
    if (shader == null) {
        FallbackOrb(engine, frame, modifier)
        return
    }
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
            engine.fill(shader, size.width, size.height, dark)
            drawRect(brush)
        }
    }
}

/** Before Android 13 (no runtime shaders): a soft glass sphere in the cue's colours. */
@Composable
private fun FallbackOrb(engine: OrbEngine, frame: () -> Long, modifier: Modifier) {
    Canvas(modifier) {
        frame()
        val r = min(size.width, size.height) * 0.33f
        val c = center
        val a = engine.alpha()
        val halo = engine.haloColor()
        if (halo.alpha > 0f) drawCircle(Brush.radialGradient(listOf(halo.copy(alpha = halo.alpha * a), Color.Transparent), c, r * 1.5f), r * 1.5f, c)
        drawCircle(
            Brush.radialGradient(
                listOf(Color(0xFF0A1026), Color(0xFF182546), Color(0xFF2F5291), engine.tintColor()),
                c + Offset(-r * 0.15f, -r * 0.15f), r * 1.15f,
            ),
            r, c, alpha = a,
        )
        drawCircle(Color(0xFFECE4F0).copy(alpha = 0.7f * a), r, c, style = androidx.compose.ui.graphics.drawscope.Stroke(r * 0.04f))
    }
}

/** A small static orb (chat avatars, the home greeting): the glow look's colours without the raymarch. */
@Composable
fun OrbGlyph(modifier: Modifier = Modifier, dark: Boolean = tc.dark) {
    Canvas(modifier) {
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
        drawCircle(Color(0xFFECE4F0).copy(alpha = 0.8f), r * 0.96f, c, style = androidx.compose.ui.graphics.drawscope.Stroke(r * 0.08f))
    }
}
