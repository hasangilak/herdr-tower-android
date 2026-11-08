package dev.herdertower.phone.orb

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The orb's surface as functions of direction: the liquid's displacement
 * (`liquid()` in the Mac's shader) and the boiling (`boil()`), evaluated
 * here each frame over a grid of directions (longitude x latitude), so the
 * shader can look them up instead of computing them at every march step
 * (AGSL caps a program's size, and those two are most of it).
 *
 * Values are packed 16-bit into two 8-bit channels (R high, G low), offset
 * by 0.5, opaque; bilinear filtering of both channels is still linear.
 */
class OrbMaps(val lw: Int = 128, val lh: Int = 64, val bw: Int = 128, val bh: Int = 64) {
    /** The unnormalised basis at each liquid texel's direction (the shader's coefficients apply to it). */
    private val basis = FloatArray(lw * lh * Modes.COUNT)
    private val boilDirs = DoubleArray(bw * bh * 3)
    val liquidPixels = IntArray(lw * lh)
    val boilPixels = IntArray(bw * bh)

    init {
        val row = DoubleArray(Modes.COUNT)
        for (j in 0 until lh) for (i in 0 until lw) {
            val d = dir(i, j, lw, lh)
            Modes.rawBasis(d[0], d[1], d[2], row)
            val o = (j * lw + i) * Modes.COUNT
            for (k in 0 until Modes.COUNT) basis[o + k] = row[k].toFloat()
        }
        for (j in 0 until bh) for (i in 0 until bw) {
            val d = dir(i, j, bw, bh)
            val o = (j * bw + i) * 3
            boilDirs[o] = d[0]; boilDirs[o + 1] = d[1]; boilDirs[o + 2] = d[2]
        }
    }

    /** Fills both maps for this frame. */
    fun update(coeffs: FloatArray, boil: BoilParams) {
        for (t in 0 until lw * lh) {
            val o = t * Modes.COUNT
            var h = 0f
            for (k in 0 until Modes.COUNT) h += basis[o + k] * coeffs[k]
            liquidPixels[t] = pack(h.toDouble())
        }
        for (t in 0 until bw * bh) {
            val o = t * 3
            boilPixels[t] = pack(boilAt(boilDirs[o], boilDirs[o + 1], boilDirs[o + 2], boil))
        }
    }

    /** What `boil()` takes from the uniforms. */
    class BoilParams(
        var height: Double = 0.0,
        var frequency: Double = 2.5,
        var phase: Double = 0.0,
        var hot: DoubleArray = doubleArrayOf(0.0, 0.0, 1.0),
        var spread: Double = 0.6,
        var shape: Double = 2.0,
    )

    companion object {
        /** The direction at a texel's centre: longitude across, latitude (from +z) down. */
        fun dir(i: Int, j: Int, w: Int, h: Int): DoubleArray {
            val phi = (i + 0.5) / w * 2.0 * PI - PI
            val theta = (j + 0.5) / h * PI
            val s = sin(theta)
            return doubleArrayOf(s * cos(phi), s * sin(phi), cos(theta))
        }

        fun pack(v: Double): Int {
            val x = ((v + 0.5).coerceIn(0.0, 1.0) * 65535.0 + 0.5).toInt()
            return (0xff shl 24) or ((x shr 8) shl 16) or ((x and 0xff) shl 8)
        }

        fun unpack(argb: Int): Double = ((((argb shr 16) and 0xff) * 256 + ((argb shr 8) and 0xff)) / 65535.0) - 0.5

        /** `boil()` from the Mac's shader: smooth noise drifting through itself, swelling into bubbles round the hotspot. */
        fun boilAt(x: Double, y: Double, z: Double, b: BoilParams): Double {
            val heat = exp(-(1.0 - (x * b.hot[0] + y * b.hot[1] + z * b.hot[2])) / (b.spread * b.spread))
            if (heat < 0.01) return 0.0
            val f = b.frequency
            val t = b.phase
            val a = min(max(gnoise(x * f, y * f, z * f + t), 0.0) / 0.6, 1.0)
            val c = min(max(gnoise(x * f * 2.1 + t * 1.3, y * f * 2.1 - t * 0.7, z * f * 2.1 + 3.1), 0.0) / 0.6, 1.0)
            return (a.pow(b.shape) + 0.6 * c.pow(b.shape)) * 1.44 * b.height * heat
        }

        private fun fract(v: Double) = v - floor(v)

        /** `hash3` of the shader, one component at a time: -1 + 2 * fract(sin(dot(p, k)) * 43758.5453123). */
        private fun h(px: Double, py: Double, pz: Double, k: Int): Double {
            val d = when (k) {
                0 -> px * 127.1 + py * 311.7 + pz * 74.7
                1 -> px * 269.5 + py * 183.3 + pz * 246.1
                else -> px * 113.5 + py * 271.9 + pz * 124.6
            }
            return -1.0 + 2.0 * fract(sin(d) * 43758.5453123)
        }

        /** dot(hash3(i + c), f - c) for a lattice corner c. */
        private fun corner(ix: Double, iy: Double, iz: Double, cx: Int, cy: Int, cz: Int, fx: Double, fy: Double, fz: Double): Double {
            val px = ix + cx
            val py = iy + cy
            val pz = iz + cz
            return h(px, py, pz, 0) * (fx - cx) + h(px, py, pz, 1) * (fy - cy) + h(px, py, pz, 2) * (fz - cz)
        }

        private fun mix(a: Double, b: Double, t: Double) = a + (b - a) * t

        /** `gnoise` of the shader: gradient noise. */
        fun gnoise(x: Double, y: Double, z: Double): Double {
            val ix = floor(x); val iy = floor(y); val iz = floor(z)
            val fx = x - ix; val fy = y - iy; val fz = z - iz
            val ux = fx * fx * (3.0 - 2.0 * fx)
            val uy = fy * fy * (3.0 - 2.0 * fy)
            val uz = fz * fz * (3.0 - 2.0 * fz)
            fun c(a: Int, b: Int, d: Int) = corner(ix, iy, iz, a, b, d, fx, fy, fz)
            return mix(
                mix(mix(c(0, 0, 0), c(1, 0, 0), ux), mix(c(0, 1, 0), c(1, 1, 0), ux), uy),
                mix(mix(c(0, 0, 1), c(1, 0, 1), ux), mix(c(0, 1, 1), c(1, 1, 1), ux), uy),
                uz,
            )
        }
    }
}
