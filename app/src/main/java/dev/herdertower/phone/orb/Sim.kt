package dev.herdertower.phone.orb

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The orb's motion: a port of `src/orb/sim.rs` with the prototype's
 * defaults. A hollow liquid cup turns slowly, its surface driven by
 * [Liquid]; it bulges as it spins, jiggles when poked, and boils in one
 * hotspot. Energy (0..1) says how animated it is; moods set it.
 */
class Opts {
    var energy = 0.3
    var gestures = 0.5
    var moodStrength = 0.6
    var moveStrength = 0.6
    var spin = 0.15
    var spinAxis = doubleArrayOf(-0.45, 0.3, -0.84)
    var cone = 0.7
    var phase = 4.71
    var radius = 1.0
    var wall = doubleArrayOf(0.01, 0.15, 1.0)
    var opening = 0.6
    var rim = 0.12
    var tension = 3.0
    var viscosity = 0.2
    var agitation = 0.5
    var wiggle = 0.4
    var boil = 0.06
    var bubbleSize = 2.8
    var boilSpeed = 0.8
    var hotspot = 120.0
    var hotspotDepth = 0.3
    var hotspotSpread = 0.6
    var hotspotWander = 0.08
    var bubbleShape = 2.0
    var size = 1.0
    var beat = 0.0
    var beatStrength = 0.8
    var sag = 0.005
    var swirl = 0.002
    var reach = 0.25
    var poke = 5.0
    var maxDeform = 0.12
    var follow = 7.0
    var density = 1.4
    var specular = 0.15
    var trail = 0.5
    var stepScale = 0.9
    var seed = 7
}

class Sim(val o: Opts = Opts()) {
    private val liquid = Liquid(o.seed)
    private val flow = Flow(o.tension, o.viscosity, o.agitation, o.wiggle, o.maxDeform)
    var t = 0.0
        private set
    val pos = DoubleArray(3)
    private val vel = DoubleArray(3)
    var rot: DoubleArray = startPose(o)
        private set
    private val extraSpin = DoubleArray(3)
    private var energy = o.energy.coerceIn(0.0, 1.0)
    var boil = o.boil
        private set
    var boilPhase = 0.0
        private set
    private var hotAngle = o.hotspot
    private var wander = 0.0
    private var lookDir: DoubleArray? = null
    private var lookUntil = 0.0
    private var lookWeight = 0.0
    private var nextGesture = 0.0
    private var beatPhase = 0.0
    private var echoAt = 0.0
    private val gestureRand = Rng(o.seed + 1)

    private fun lively(low: Double, high: Double) = boost(energy, low, high, o.moodStrength)

    /** Runs the physics up to time [to] in fixed steps. */
    fun simulateTo(to: Double) {
        while (t + STEP <= to + 1e-9) {
            step(STEP)
            t += STEP
        }
    }

    private fun step(dt: Double) {
        energy += (o.energy.coerceIn(0.0, 1.0) - energy) * (1.0 - exp(-dt / 2.5))
        val e = energy
        flow.tension = o.tension * lively(0.8, 1.35)
        flow.agitation = o.agitation * lively(0.4, 1.5)
        flow.wiggle = o.wiggle * lively(0.5, 1.5)
        flow.viscosity = o.viscosity
        flow.maxDeform = o.maxDeform
        boil = o.boil * lively(0.5, 1.4)
        boilPhase += o.boilSpeed * lively(0.6, 1.6) * dt
        val turnTo = ((o.hotspot - hotAngle) % 360.0 + 540.0) % 360.0 - 180.0
        hotAngle += turnTo * (1.0 - exp(-dt / 0.5))
        wander += o.hotspotWander * dt

        if (o.gestures > 0.0 && t >= nextGesture) {
            if (nextGesture > 0.0) gesture(e)
            nextGesture = t + ((0.5 + gestureRand.next()) * (9.0 - 4.0 * e)) / o.gestures
        }

        if (o.beat > 0.0) {
            beatPhase += o.beat * dt
            if (beatPhase >= 1.0) {
                beatPhase -= 1.0
                swell(1.2 * o.beatStrength)
                echoAt = t + 0.22
            }
        }
        if (echoAt > 0.0 && t >= echoAt) {
            swell(0.66 * o.beatStrength)
            echoAt = 0.0
        }

        // The orb hangs on a damped spring to its home.
        val k = o.follow * o.follow
        val c = 2.0 * 0.45 * o.follow
        val acc = doubleArrayOf(k * (0.0 - pos[0]) - c * vel[0], k * (0.0 - pos[1]) - c * vel[1], 0.0)
        for (i in 0..2) {
            vel[i] += acc[i] * dt
            pos[i] += vel[i] * dt
        }
        val fade = exp(-dt / 1.2)
        for (i in 0..2) extraSpin[i] *= fade

        val looking = if (lookDir != null && t < lookUntil) 1.0 else 0.0
        lookWeight += (looking - lookWeight) * (1.0 - exp(-dt / 0.6))
        if (looking == 0.0 && lookWeight < 1e-3) lookDir = null
        val axis = normalize(o.spinAxis)
        val base = 2.0 * PI * o.spin * lively(0.4, 2.0) * (1.0 - lookWeight)
        val spin = DoubleArray(3) { axis[it] * base + extraSpin[it] }
        lookDir?.let { dir ->
            val facing = doubleArrayOf(rot[2], rot[5], rot[8])
            var turn = cross(facing, dir)
            val sn = len(turn)
            val cs = dot(facing, dir)
            if (sn < 1e-6) turn = if (cs < 0.0) cross(facing, doubleArrayOf(0.0, 1.0, 0.0)) else DoubleArray(3)
            val l = len(turn)
            val gain = (2.5 * lookWeight * atan2(sn, cs)) / if (l == 0.0) 1.0 else l
            for (i in 0..2) spin[i] += turn[i] * gain
        }
        val rate = len(spin)
        if (rate > 1e-6) rot = orthonormalize(mul(axisAngle(spin, rate * dt), rot))

        val g = hypot(acc[0], acc[1])
        val gn = if (g == 0.0) 1.0 else g
        val gx = -acc[0] / gn
        val gy = -acc[1] / gn
        val sag = min(g * o.sag, o.maxDeform)
        val rn = if (rate == 0.0) 1.0 else rate
        val sx = spin[0] / rn
        val sy = spin[1] / rn
        val sz = spin[2] / rn
        val swirl = o.swirl * rate * rate
        liquid.step(dt, flow) { x, y, z ->
            var s = 0.0
            if (sag > 1e-4) {
                val u = x * gx + y * gy
                s += sag * (0.75 * (1.5 * u * u - 0.5) + 0.25 * (2.5 * u * u * u - 1.5 * u))
            }
            val v = x * sx + y * sy + z * sz
            s + swirl * (1.0 / 3.0 - v * v)
        }
    }

    // Moves

    fun setEnergy(v: Double) {
        o.energy = v.coerceIn(0.0, 1.0)
    }

    fun mood(name: String) {
        val e = MOODS[name] ?: return
        o.energy = e
        if (name == "thinking") look(90.0, 2.5)
        if (name == "important") pulse(0.35)
    }

    /** Tap the surface at an edge (degrees round the outline; null: random). */
    fun poke(edge: Double?, strength: Double) {
        val deg = edge ?: (gestureRand.next() * 360.0)
        dent(edgeDir(deg, 0.35), o.poke * strength * o.moveStrength)
    }

    /** Push the surface in where the finger pressed (x, y on the orb, in radii). */
    fun press(x: Double, y: Double) {
        val hx = x - pos[0]
        val hy = y - pos[1]
        val h = normalize(doubleArrayOf(hx, hy, sqrt(max(1.0 - hx * hx - hy * hy, 0.0))))
        val size = o.poke
        liquid.impulse { a, b, c -> -size * exp(8.0 * (a * h[0] + b * h[1] + c * h[2] - 1.0)) }
    }

    /** Flick it so its front rolls towards an edge. */
    fun spin(edge: Double?, turns: Double) {
        val deg = edge ?: (gestureRand.next() * 360.0)
        val roll = cross(doubleArrayOf(0.0, 0.0, 1.0), edgeDir(deg, 0.0))
        val rate = (turns * o.moveStrength * 2.0 * PI) / 1.2
        for (i in 0..2) extraSpin[i] += roll[i] * rate
    }

    /** Turn the opening towards an edge (null: the front) and hold. */
    fun look(edge: Double?, seconds: Double) {
        lookDir = if (edge == null) doubleArrayOf(0.0, 0.0, 1.0) else edgeDir(edge, 0.6)
        lookUntil = t + seconds
    }

    /** A swell that ripples through it, for attention. */
    fun pulse(strength: Double) = swell(1.2 * strength * o.moveStrength)

    /** Move the boiling hotspot to an edge (null: the front). */
    fun focus(edge: Double?, depth: Double) {
        if (edge == null) {
            o.hotspotDepth = 1.0
        } else {
            o.hotspot = edge
            o.hotspotDepth = depth
        }
    }

    private fun dent(d: DoubleArray, size: Double) =
        liquid.impulse { x, y, z -> -size * exp(8.0 * (x * d[0] + y * d[1] + z * d[2] - 1.0)) }

    private fun swell(size: Double) = liquid.impulse { _, _, z -> -size * (1.5 * z * z - 0.5) }

    private fun gesture(e: Double) {
        val pick = gestureRand.next()
        val deg = gestureRand.next() * 360.0
        when {
            pick < 0.7 -> poke(deg, 0.08 + 0.2 * e)
            pick < 0.9 -> look(deg, 1.2)
            else -> spin(deg, 0.1 + 0.15 * e)
        }
    }

    // What the shader needs

    fun hotspotDir(): DoubleArray {
        val w = wander
        val deg = hotAngle + 35.0 * sin(w * 2.1) + 20.0 * sin(w * 3.7 + 1.0)
        val depth = (o.hotspotDepth + 0.15 * sin(w * 2.9 + 2.0)).coerceIn(0.0, 1.0)
        val a = Math.toRadians(deg)
        val ring = cos(depth * PI / 2.0)
        return doubleArrayOf(cos(a) * ring, sin(a) * ring, sin(depth * PI / 2.0))
    }

    fun hotspotSpread(): Double = o.hotspotSpread * lively(0.85, 1.2)

    /** Radius of a sphere round the orb holding all of it. */
    fun bound(): Double = o.radius + o.wall[1] + liquid.extent() + 2.4 * boil + 0.1

    fun coeffs(out: FloatArray) = liquid.shaderCoeffs(out)

    companion object {
        const val STEP = 1.0 / 120.0
        private const val NORMAL_ENERGY = 0.3
        val MOODS = mapOf("rest" to 0.1, "idle" to 0.3, "thinking" to 0.45, "working" to 0.6, "important" to 0.8)

        fun boost(e: Double, low: Double, high: Double, strength: Double): Double =
            if (e < NORMAL_ENERGY) low.pow(strength * (NORMAL_ENERGY - e) / NORMAL_ENERGY)
            else high.pow(strength * (e - NORMAL_ENERGY) / (1.0 - NORMAL_ENERGY))

        fun edgeDir(deg: Double, z: Double): DoubleArray {
            val a = Math.toRadians(deg)
            return normalize(doubleArrayOf(cos(a), sin(a), z))
        }

        fun startPose(o: Opts): DoubleArray {
            val a = normalize(o.spinAxis)
            val u = normalize(cross(a, if (kotlin.math.abs(a[1]) < 0.9) doubleArrayOf(0.0, 1.0, 0.0) else doubleArrayOf(1.0, 0.0, 0.0)))
            val v = cross(a, u)
            val cs = cos(o.cone)
            val sn = sin(o.cone)
            val d = DoubleArray(3) { a[it] * cs + sn * (cos(o.phase) * u[it] + sin(o.phase) * v[it]) }
            val axis = cross(doubleArrayOf(0.0, 0.0, 1.0), d)
            if (len(axis) < 1e-6) axis[0] = 1.0
            return axisAngle(axis, acos(d[2].coerceIn(-1.0, 1.0)))
        }

        fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        fun len(a: DoubleArray) = sqrt(dot(a, a))
        fun normalize(v: DoubleArray): DoubleArray {
            val l = len(v)
            return doubleArrayOf(v[0] / l, v[1] / l, v[2] / l)
        }
        fun cross(a: DoubleArray, b: DoubleArray) =
            doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

        /** Row-major 3x3 rotation about an arbitrary axis (Rodrigues). */
        fun axisAngle(axis: DoubleArray, a: Double): DoubleArray {
            val (x, y, z) = normalize(axis).let { Triple(it[0], it[1], it[2]) }
            val c = cos(a)
            val s = sin(a)
            val k = 1.0 - c
            return doubleArrayOf(
                c + x * x * k, x * y * k - z * s, x * z * k + y * s,
                y * x * k + z * s, c + y * y * k, y * z * k - x * s,
                z * x * k - y * s, z * y * k + x * s, c + z * z * k,
            )
        }

        fun mul(a: DoubleArray, b: DoubleArray): DoubleArray {
            val r = DoubleArray(9)
            for (i in 0..2) for (j in 0..2) r[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j]
            return r
        }

        fun orthonormalize(m: DoubleArray): DoubleArray {
            val x = normalize(doubleArrayOf(m[0], m[3], m[6]))
            val y0 = doubleArrayOf(m[1], m[4], m[7])
            val d = dot(x, y0)
            val y = normalize(doubleArrayOf(y0[0] - d * x[0], y0[1] - d * x[1], y0[2] - d * x[2]))
            val z = cross(x, y)
            return doubleArrayOf(x[0], y[0], z[0], x[1], y[1], z[1], x[2], y[2], z[2])
        }
    }
}
