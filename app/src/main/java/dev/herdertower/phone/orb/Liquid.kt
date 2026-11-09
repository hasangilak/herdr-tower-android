package dev.herdertower.phone.orb

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The orb's liquid surface: a port of `src/orb/liquid.rs` (itself a port of
 * `liquid.js` from the orbs prototype), kept line for line so the phone's orb
 * moves as the Mac's does.
 *
 * Real spherical harmonics of degree 2..6, each a damped oscillator with a
 * real drop's frequency (~ l(l-1)(l+2)) and viscous decay (~ (l-1)(2l+1)).
 */
object Modes {
    const val LMAX = 6
    const val COUNT = 45
    private const val SAMPLES = 512

    /** Mode order: m = 0..LMAX, then l = max(m, 2)..LMAX, cosine before sine (the shader walks them the same way). */
    val DEGREE: IntArray = buildList {
        for (m in 0..LMAX) for (l in maxOf(m, 2)..LMAX) {
            add(l)
            if (m > 0) add(l)
        }
    }.toIntArray()

    /** Unnormalised real spherical harmonics of a unit vector. */
    fun rawBasis(x: Double, y: Double, z: Double, out: DoubleArray) {
        var k = 0
        var re = 1.0
        var im = 0.0
        var qmm = 1.0
        for (m in 0..LMAX) {
            if (m > 0) {
                val r = re * x - im * y
                im = re * y + im * x
                re = r
                qmm *= (2 * m - 1).toDouble()
            }
            var q1 = 0.0
            var q2 = 0.0
            for (l in m..LMAX) {
                val q = if (l == m) qmm else ((2 * l - 1) * z * q1 - (l + m - 1) * q2) / (l - m)
                q2 = q1
                q1 = q
                if (l < 2) continue
                out[k++] = q * re
                if (m > 0) out[k++] = q * im
            }
        }
    }

    val dirs: Array<DoubleArray>
    val table: Array<DoubleArray>
    /** Raw basis -> normalised mode. */
    val scale = DoubleArray(COUNT)
    private val norm = DoubleArray(COUNT)

    init {
        check(DEGREE.size == COUNT)
        dirs = Array(SAMPLES) { DoubleArray(3) }
        table = Array(SAMPLES) { DoubleArray(COUNT) }
        for (i in 0 until SAMPLES) {
            val dz = 1.0 - (2.0 * (i + 0.5)) / SAMPLES
            val r = sqrt(1.0 - dz * dz)
            val th = i * PI * (3.0 - sqrt(5.0))
            dirs[i][0] = r * cos(th); dirs[i][1] = r * sin(th); dirs[i][2] = dz
            rawBasis(dirs[i][0], dirs[i][1], dirs[i][2], table[i])
            for (k in 0 until COUNT) scale[k] += table[i][k] * table[i][k]
        }
        for (k in 0 until COUNT) {
            scale[k] = 1.0 / sqrt(4.0 * PI * (scale[k] / SAMPLES))
            for (row in table) {
                row[k] *= scale[k]
                norm[k] += row[k] * row[k]
            }
        }
    }

    /** Least-squares mode coefficients of shape(x, y, z), added to [out]; [acc] is scratch space (it's cleared). */
    fun project(shape: Shape, out: DoubleArray, acc: DoubleArray = DoubleArray(COUNT)) {
        acc.fill(0.0)
        for (i in dirs.indices) {
            val d = dirs[i]
            val f = shape.at(d[0], d[1], d[2])
            if (f == 0.0) continue
            val row = table[i]
            for (k in 0 until COUNT) acc[k] += f * row[k]
        }
        for (k in 0 until COUNT) out[k] += acc[k] / norm[k]
    }
}

/** A displacement over directions. Not a Kotlin function type, which would box every Double it's called with. */
fun interface Shape {
    fun at(x: Double, y: Double, z: Double): Double
}

/** mulberry32, as in the prototype and the Mac, so the same seed moves the same way. */
class Rng(seed: Int) {
    private var s = seed

    fun next(): Double {
        s += 0x6d2b79f5
        val x = s
        var t = (x xor (x ushr 15)) * (1 or x)
        t = (t + ((t xor (t ushr 7)) * (61 or t))) xor t
        return ((t xor (t ushr 14)).toLong() and 0xffffffffL).toDouble() / 4294967296.0
    }

    fun gauss(): Double = sqrt(-2.0 * ln(1.0 - next())) * cos(2.0 * PI * next())
}

/** Settings read on every step. */
class Flow(var tension: Double, var viscosity: Double, var agitation: Double, var wiggle: Double, var maxDeform: Double)

class Liquid(seed: Int) {
    private val n = Modes.COUNT
    private val amp = DoubleArray(n)
    private val vel = DoubleArray(n)
    private val stir = DoubleArray(n)
    private val rand = Rng(seed)
    private val stiff = DoubleArray(n)
    private val damp = DoubleArray(n)
    private val stirSize = DoubleArray(n)
    private val wiggleSize = DoubleArray(n)
    private val rest = DoubleArray(n)
    private val scratch = DoubleArray(n)

    init {
        val stirByDegree = doubleArrayOf(0.0, 0.0, 0.035, 0.0175, 0.024, 0.018, 0.016)
        for (k in 0 until n) {
            val l = Modes.DEGREE[k].toDouble()
            stiff[k] = l * (l - 1.0) * (l + 2.0) / 8.0
            damp[k] = 2.0 * (l - 1.0) * (2.0 * l + 1.0)
            stirSize[k] = stirByDegree[Modes.DEGREE[k]]
            wiggleSize[k] = stirSize[k] * (2.0 / l).pow(2)
        }
    }

    /** Advance by dt; [shape] is the equilibrium displacement in a direction, or null when nothing pushes. */
    fun step(dt: Double, flow: Flow, shape: Shape?) {
        val tension = max(flow.tension, 0.1)
        val keep = exp(-dt * tension / 4.5)
        val kick = sqrt(1.0 - keep * keep)
        for (k in 0 until n) {
            stir[k] = stir[k] * keep + stirSize[k] * flow.agitation * kick * rand.gauss()
            rest[k] = stir[k]
        }
        if (shape != null) Modes.project(shape, rest, scratch)
        val sqdt = sqrt(dt)
        for (k in 0 until n) {
            val omega2 = tension * tension * stiff[k]
            val damping = flow.viscosity * damp[k]
            val force = omega2 * (rest[k] - amp[k]) - damping * vel[k]
            val kickSize = flow.wiggle * wiggleSize[k] * sqrt(2.0 * damping * omega2)
            vel[k] += force * dt + kickSize * sqdt * rand.gauss()
            amp[k] += vel[k] * dt
        }
        val e = extent()
        if (e > flow.maxDeform) {
            val s = flow.maxDeform / e
            for (k in 0 until n) {
                amp[k] *= s
                vel[k] *= s
            }
        }
    }

    /** Instant change of surface velocity, e.g. a poke. */
    fun impulse(shape: Shape) = Modes.project(shape, vel, scratch)

    /** How far the surface is displaced at most (over the sample directions). */
    fun extent(): Double {
        var m = 0.0
        for (row in Modes.table) {
            var s = 0.0
            for (k in 0 until n) s += row[k] * amp[k]
            m = max(m, abs(s))
        }
        return m
    }

    /** Coefficients for the shader, which evaluates the unnormalised basis. */
    fun shaderCoeffs(out: FloatArray) {
        for (k in 0 until n) out[k] = (amp[k] * Modes.scale[k]).toFloat()
    }
}
