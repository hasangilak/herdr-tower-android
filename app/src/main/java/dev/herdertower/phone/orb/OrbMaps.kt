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
 * Values are 16-bit, offset by 0.5, split into two alpha-only planes (high
 * byte, low byte): alpha is never colour-converted, whatever the layer's
 * colour space, and bilinear filtering of both planes is still linear.
 */
class OrbMaps(val lw: Int = 128, val lh: Int = 64, val bw: Int = 128, val bh: Int = 64) {
    /** The unnormalised basis at each liquid texel's direction (the shader's coefficients apply to it). */
    private val basis = FloatArray(lw * lh * Modes.COUNT)
    private val boilDirs = DoubleArray(bw * bh * 3)
    val liquidHi = ByteArray(lw * lh)
    val liquidLo = ByteArray(lw * lh)
    val boilHi = ByteArray(bw * bh)
    val boilLo = ByteArray(bw * bh)

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

    private val lattice = Lattice()
    /** Whether the boil map holds the flat surface already (no boiling). */
    private var boilFlat = false

    /**
     * Fills both maps for this frame, without allocating. Returns whether the
     * boil map changed (it stays as it was while nothing boils).
     */
    fun update(coeffs: FloatArray, boil: BoilParams): Boolean {
        for (t in 0 until lw * lh) {
            val o = t * Modes.COUNT
            var h = 0f
            for (k in 0 until Modes.COUNT) h += basis[o + k] * coeffs[k]
            split(h.toDouble(), liquidHi, liquidLo, t)
        }
        // Below this height every texel rounds to the same 16 bits as no boiling at all.
        if (kotlin.math.abs(boil.height) < FLAT) {
            if (boilFlat) return false
            fill(0.0, boilHi, boilLo)
            boilFlat = true
            return true
        }
        boilFlat = false
        lattice.newFrame()
        for (t in 0 until bw * bh) {
            val o = t * 3
            split(lattice.boilAt(boilDirs[o], boilDirs[o + 1], boilDirs[o + 2], boil), boilHi, boilLo, t)
        }
        return true
    }

    /**
     * [boilAt] with the noise's lattice hashes kept for the frame: most
     * texels share their lattice corners, and the hashes (three `sin` each)
     * are most of the cost. The values are exactly those of [boilAt].
     */
    internal class Lattice(bits: Int = 12) {
        private val mask = (1 shl bits) - 1
        private val limit = (mask + 1) * 7 / 10
        private val kx = DoubleArray(mask + 1)
        private val ky = DoubleArray(mask + 1)
        private val kz = DoubleArray(mask + 1)
        private val stamp = IntArray(mask + 1)
        private val hashes = DoubleArray((mask + 1) * 3)
        private var frame = 1
        private var used = 0
        // The three hashes of the corner last looked up.
        private var h0 = 0.0
        private var h1 = 0.0
        private var h2 = 0.0

        /** Forgets the last frame's corners (the boil's phase moves them). */
        fun newFrame() {
            used = 0
            if (++frame == Int.MAX_VALUE) {
                stamp.fill(0)
                frame = 1
            }
        }

        /** Sets [h0], [h1], [h2] to the hashes of lattice point (px, py, pz). */
        private fun hashesAt(px: Double, py: Double, pz: Double) {
            var i = ((px.toLong() * 73856093L) xor (py.toLong() * 19349663L) xor (pz.toLong() * 83492791L)).toInt() and mask
            while (stamp[i] == frame) {
                if (kx[i] == px && ky[i] == py && kz[i] == pz) {
                    h0 = hashes[i * 3]; h1 = hashes[i * 3 + 1]; h2 = hashes[i * 3 + 2]
                    return
                }
                i = (i + 1) and mask
            }
            h0 = h(px, py, pz, 0); h1 = h(px, py, pz, 1); h2 = h(px, py, pz, 2)
            if (used >= limit) return // full: computed, not kept
            stamp[i] = frame
            kx[i] = px; ky[i] = py; kz[i] = pz
            hashes[i * 3] = h0; hashes[i * 3 + 1] = h1; hashes[i * 3 + 2] = h2
            used++
        }

        private fun corner(ix: Double, iy: Double, iz: Double, cx: Int, cy: Int, cz: Int, fx: Double, fy: Double, fz: Double): Double {
            hashesAt(ix + cx, iy + cy, iz + cz)
            return h0 * (fx - cx) + h1 * (fy - cy) + h2 * (fz - cz)
        }

        fun gnoise(x: Double, y: Double, z: Double): Double {
            val ix = floor(x); val iy = floor(y); val iz = floor(z)
            val fx = x - ix; val fy = y - iy; val fz = z - iz
            val ux = fx * fx * (3.0 - 2.0 * fx)
            val uy = fy * fy * (3.0 - 2.0 * fy)
            val uz = fz * fz * (3.0 - 2.0 * fz)
            val c000 = corner(ix, iy, iz, 0, 0, 0, fx, fy, fz)
            val c100 = corner(ix, iy, iz, 1, 0, 0, fx, fy, fz)
            val c010 = corner(ix, iy, iz, 0, 1, 0, fx, fy, fz)
            val c110 = corner(ix, iy, iz, 1, 1, 0, fx, fy, fz)
            val c001 = corner(ix, iy, iz, 0, 0, 1, fx, fy, fz)
            val c101 = corner(ix, iy, iz, 1, 0, 1, fx, fy, fz)
            val c011 = corner(ix, iy, iz, 0, 1, 1, fx, fy, fz)
            val c111 = corner(ix, iy, iz, 1, 1, 1, fx, fy, fz)
            return mix(mix(mix(c000, c100, ux), mix(c010, c110, ux), uy), mix(mix(c001, c101, ux), mix(c011, c111, ux), uy), uz)
        }

        /** [OrbMaps.boilAt], line for line, on the kept hashes. */
        fun boilAt(x: Double, y: Double, z: Double, b: BoilParams): Double {
            val heat = exp(-(1.0 - (x * b.hot[0] + y * b.hot[1] + z * b.hot[2])) / (b.spread * b.spread))
            if (heat < 0.01) return 0.0
            val f = b.frequency
            val t = b.phase
            val a = min(max(gnoise(x * f, y * f, z * f + t), 0.0) / 0.6, 1.0)
            val c = min(max(gnoise(x * f * 2.1 + t * 1.3, y * f * 2.1 - t * 0.7, z * f * 2.1 + 3.1), 0.0) / 0.6, 1.0)
            return (a.pow(b.shape) + 0.6 * c.pow(b.shape)) * 1.44 * b.height * heat
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

        /** [v] (-0.5..0.5) as 16 bits, high byte into [hi], low into [lo]. */
        fun split(v: Double, hi: ByteArray, lo: ByteArray, at: Int) {
            val x = ((v + 0.5).coerceIn(0.0, 1.0) * 65535.0 + 0.5).toInt()
            hi[at] = (x shr 8).toByte()
            lo[at] = (x and 0xff).toByte()
        }

        /** What the shader's `unpack` makes of the two bytes. */
        fun join(hi: Byte, lo: Byte): Double = (((hi.toInt() and 0xff) * 256 + (lo.toInt() and 0xff)) / 65535.0) - 0.5

        /** Fills every texel with [v] (the lookup probe's known value). */
        fun fill(v: Double, hi: ByteArray, lo: ByteArray) {
            for (i in hi.indices) split(v, hi, lo, i)
        }

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

        private const val FLAT = 1e-6

        private fun fract(v: Double) = v - floor(v)

        /** `hash3` of the shader, one component at a time: -1 + 2 * fract(sin(dot(p, k)) * 43758.5453123). */
        internal fun h(px: Double, py: Double, pz: Double, k: Int): Double {
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

        internal fun mix(a: Double, b: Double, t: Double) = a + (b - a) * t

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
