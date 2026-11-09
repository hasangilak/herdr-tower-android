package dev.herdertower.phone.orb

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import android.graphics.BitmapShader
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.opengl.EGL14
import android.opengl.GLES20
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer

/** How the orb is drawn on this device, best first. */
enum class OrbTier(val label: String) {
    Full("full shader"),
    Analytic("shader, analytic liquid"),
    Gradient("layered gradients"),
}

/** What the probe found, for the diagnostics line in the menu. */
data class OrbReport(
    val tier: OrbTier,
    val steps: Int = 0,
    /** Share of the orb's disk the probe frame filled with material (0..1). */
    val coverage: Float = 0f,
    /** What the maps read back as against what was written, e.g. "0.80/0.30 ok". */
    val lookup: String = "",
    val error: String = "",
    val gpu: String = "",
) {
    val device: String get() = "Android ${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}"

    fun line(frameMs: Float, drawnSteps: Int = 0): String = buildList {
        add(device)
        if (gpu.isNotBlank()) add("GPU $gpu")
        val n = if (tier != OrbTier.Gradient && drawnSteps > 0) drawnSteps else steps
        add("orb: ${tier.label}" + if (n > 0 && tier != OrbTier.Gradient) ", $n steps" else "")
        if (tier != OrbTier.Gradient || coverage > 0f) add("coverage ${(coverage * 100).toInt()}%")
        if (lookup.isNotBlank()) add("maps $lookup")
        if (error.isNotBlank()) add(error)
        if (frameMs > 0f) add("%.1f ms/frame".format(frameMs))
    }.joinToString(" · ")
}

/**
 * Finds how this device can draw the orb, by drawing it: each way is
 * compiled, one frame is rendered offscreen on the GPU (the same pipeline the
 * screen uses) and read back, and the first whose orb disk is actually
 * filled wins. A phone that compiles the shader but samples its maps wrong
 * (a ring with nothing inside) is caught here and gets the next way.
 */
object OrbProbe {
    private val probed = MutableStateFlow<OrbReport?>(null)
    private val _report = MutableStateFlow<OrbReport?>(null)
    /** What draws the orb: the probe's choice, or the one chosen in the menu. Null until the probe has run. */
    val report: StateFlow<OrbReport?> = _report.asStateFlow()

    /** The menu's choice: null for the probe's. */
    var override: OrbTier? = null
        set(v) {
            field = v
            apply()
        }

    private fun apply() {
        _drawnSteps.value = 0 // until the newly chosen shader draws
        val p = probed.value ?: return
        val o = override
        _report.value = if (o == null || o == p.tier) p else p.copy(tier = o, steps = if (o == OrbTier.Gradient) 0 else p.steps.takeIf { it > 0 } ?: 64, error = "chosen in the menu")
    }

    private val _frameMs = MutableStateFlow(0f)
    val frameMs: StateFlow<Float> = _frameMs.asStateFlow()

    private val _drawnSteps = MutableStateFlow(0)
    /** The march steps of the shader actually drawing (it may compile below what the probe tried). */
    val drawnSteps: StateFlow<Int> = _drawnSteps.asStateFlow()

    fun drawn(steps: Int) {
        _drawnSteps.value = steps
    }

    fun frame(ms: Float) {
        _frameMs.value = if (_frameMs.value == 0f) ms else _frameMs.value * 0.95f + ms * 0.05f
    }

    /** A frame filled less than this (of the disk) counts as empty. */
    private const val MIN_COVERAGE = 0.08f

    /** Runs once, off the main thread. */
    @Synchronized
    fun run() {
        if (probed.value != null) return
        probed.value = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            OrbReport(OrbTier.Gradient, error = "no runtime shaders before Android 13", gpu = gpu())
        } else {
            runCatching { probe() }.getOrElse { OrbReport(OrbTier.Gradient, error = "probe failed: ${it.javaClass.simpleName} ${it.message.orEmpty().take(80)}", gpu = gpu()) }
        }
        android.util.Log.i("TowerOrb", "probe: ${probed.value?.line(0f)}")
        apply()
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun probe(): OrbReport {
        val gpu = gpu()
        val lookup = lookupProbe()
        val errors = mutableListOf<String>()
        val lookupOk = lookup.endsWith("ok")
        if (lookupOk) {
            ladder(OrbShader.Mode.Maps, errors)?.let { (steps, cov) ->
                if (cov >= MIN_COVERAGE) return OrbReport(OrbTier.Full, steps, cov, lookup, gpu = gpu)
                errors += "maps frame empty (${(cov * 100).toInt()}%)"
            }
        }
        ladder(OrbShader.Mode.Analytic, errors)?.let { (steps, cov) ->
            if (cov >= MIN_COVERAGE) return OrbReport(OrbTier.Analytic, steps, cov, lookup, errors.joinToString("; "), gpu)
            errors += "analytic frame empty (${(cov * 100).toInt()}%)"
        }
        return OrbReport(OrbTier.Gradient, 0, 0f, lookup, errors.joinToString("; "), gpu)
    }

    /** The first step count that compiles, and how full its frame is. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun ladder(mode: OrbShader.Mode, errors: MutableList<String>): Pair<Int, Float>? {
        for (n in OrbShader.STEP_LADDER) {
            val s = runCatching { RuntimeShader(OrbShader.source(n, mode)) }.getOrElse {
                if (n == OrbShader.STEP_LADDER.last()) errors += "${mode.name.lowercase()}: ${it.message?.lineSequence()?.firstOrNull().orEmpty()}"
                null
            } ?: continue
            val engine = OrbEngine()
            engine.settle()
            val size = 96
            engine.fill(s, size.toFloat(), size.toFloat(), dark = true, mode = mode, halo = false)
            val px = render(s, size, size) ?: return n to 0f
            return n to coverage(px, size, size)
        }
        return null
    }

    /** Fills the maps with known values and reads them back through the GPU. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun lookupProbe(): String = runCatching {
        val s = RuntimeShader(OrbShader.LOOKUP_PROBE)
        val maps = OrbMaps(8, 4, 8, 4)
        OrbMaps.fill(0.3, maps.boilHi, maps.boilLo)
        OrbMaps.fill(-0.2, maps.liquidHi, maps.liquidLo)
        s.setInputShader("liquidHi", alphaMap(maps.liquidHi, 8, 4))
        s.setInputShader("liquidLo", alphaMap(maps.liquidLo, 8, 4))
        s.setInputShader("boilHi", alphaMap(maps.boilHi, 8, 4))
        s.setInputShader("boilLo", alphaMap(maps.boilLo, 8, 4))
        s.setFloatUniform("maps", 8f, 4f, 8f, 4f)
        val px = render(s, 8, 8) ?: return@runCatching "no readback"
        val c = px[4 * 8 + 4]
        val r = ((c shr 16) and 0xff) / 255f
        val g = ((c shr 8) and 0xff) / 255f
        val ok = kotlin.math.abs(r - 0.8f) < 0.03f && kotlin.math.abs(g - 0.3f) < 0.03f
        "%.2f/%.2f %s".format(r, g, if (ok) "ok" else "wrong (want 0.80/0.30)")
    }.getOrElse { "lookup probe failed: ${it.javaClass.simpleName}" }

    fun alphaMap(bytes: ByteArray, w: Int, h: Int): BitmapShader {
        val b = createBitmap(w, h, Bitmap.Config.ALPHA_8)
        b.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
        return BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.CLAMP).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) filterMode = BitmapShader.FILTER_MODE_LINEAR
        }
    }

    /** Share of the disk round the centre (where the orb is) with real material in it. */
    private fun coverage(px: IntArray, w: Int, h: Int): Float {
        val cx = w / 2f
        val cy = h / 2f
        val r = minOf(w, h) * 0.30f
        var inDisk = 0
        var filled = 0
        for (y in 0 until h) for (x in 0 until w) {
            val dx = x + 0.5f - cx
            val dy = y + 0.5f - cy
            if (dx * dx + dy * dy > r * r) continue
            inDisk++
            if (((px[y * w + x] ushr 24) and 0xff) > 40) filled++
        }
        return if (inDisk == 0) 0f else filled.toFloat() / inDisk
    }

    /** Renders [shader] into a w x h offscreen surface on the GPU and returns its pixels (ARGB). */
    fun render(shader: Shader, w: Int, h: Int): IntArray? {
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 1, HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_CPU_READ_OFTEN)
        val renderer = HardwareRenderer()
        try {
            val node = RenderNode("orb-probe").apply { setPosition(0, 0, w, h) }
            val c = node.beginRecording()
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { this.shader = shader })
            node.endRecording()
            renderer.setSurface(reader.surface)
            renderer.setContentRoot(node)
            renderer.isOpaque = false
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            var image = reader.acquireLatestImage()
            var tries = 0
            while (image == null && tries++ < 50) {
                Thread.sleep(10)
                image = reader.acquireLatestImage()
            }
            image ?: return null
            image.use { img ->
                val plane = img.planes[0]
                val buf = plane.buffer
                val out = IntArray(w * h)
                for (y in 0 until h) for (x in 0 until w) {
                    val i = y * plane.rowStride + x * plane.pixelStride
                    val r = buf.get(i).toInt() and 0xff
                    val g = buf.get(i + 1).toInt() and 0xff
                    val b = buf.get(i + 2).toInt() and 0xff
                    val a = buf.get(i + 3).toInt() and 0xff
                    out[y * w + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
                return out
            }
        } finally {
            renderer.destroy()
            reader.close()
        }
    }

    /** The GPU's name (GL_RENDERER), from a throwaway EGL context. */
    fun gpu(): String = runCatching {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2)
        EGL14.eglInitialize(display, v, 0, v, 1)
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val n = IntArray(1)
        EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE), 0, configs, 0, 1, n, 0)
        val ctx = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        val surface = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(display, surface, surface, ctx)
        val name = GLES20.glGetString(GLES20.GL_RENDERER).orEmpty()
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, ctx)
        name
    }.getOrDefault("")
}
