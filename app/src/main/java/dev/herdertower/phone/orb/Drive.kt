package dev.herdertower.phone.orb

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/**
 * How each state looks (`Cue` in `src/orb/render.rs`): the material's tint
 * and how far it's lifted towards it, a halo round the orb, and how fast the
 * orb's clock runs. Listening is icy cyan with a breathing halo, replying
 * periwinkle with a halo that throbs on each heartbeat, an alert warm amber,
 * everything else a calm pale blue.
 */
data class Cue(
    val tint: FloatArray,
    val tintAmount: Float,
    val halo: FloatArray,
    val haloAmount: Float,
    val speed: Double,
) {
    fun eased(to: Cue, k: Double): Cue {
        val kf = k.toFloat()
        return Cue(
            FloatArray(3) { tint[it] + (to.tint[it] - tint[it]) * kf },
            tintAmount + (to.tintAmount - tintAmount) * kf,
            FloatArray(3) { halo[it] + (to.halo[it] - halo[it]) * kf },
            haloAmount + (to.haloAmount - haloAmount) * kf,
            speed + (to.speed - speed) * k,
        )
    }

    override fun equals(other: Any?) = other is Cue && tint.contentEquals(other.tint) && tintAmount == other.tintAmount &&
        halo.contentEquals(other.halo) && haloAmount == other.haloAmount && speed == other.speed

    override fun hashCode() = tint.contentHashCode() * 31 + halo.contentHashCode()

    companion object {
        val CALM = Cue(floatArrayOf(0.80f, 0.88f, 1.0f), 0.25f, floatArrayOf(0.55f, 0.75f, 1.0f), 0.0f, 0.5)
        val LISTENING = Cue(floatArrayOf(0.55f, 0.92f, 1.0f), 0.55f, floatArrayOf(0.35f, 0.85f, 1.0f), 0.55f, 0.8)
        val REPLYING = Cue(floatArrayOf(0.64f, 0.62f, 1.0f), 0.5f, floatArrayOf(0.50f, 0.48f, 1.0f), 0.75f, 0.8)
        val ANNOUNCING = Cue(floatArrayOf(1.0f, 0.80f, 0.52f), 0.5f, floatArrayOf(1.0f, 0.70f, 0.32f), 0.8f, 0.8)
    }
}

/**
 * The tower's phases played on the orb, as the Mac's `Drive` does:
 *
 * - off, standby: at rest; faded (off), or breathing in and out (standby)
 * - idle: idle
 * - listening: working, boiling on the face towards you, a heartbeat each 0.7 s
 * - transcribing: very lively and spinning
 * - thinking: thinking, the opening turned up and kept there
 * - speaking: working, a quicker, softer heartbeat (0.42 s)
 * - announcing (saying an alert): as speaking, in amber, with a swell first
 * - armed: important (a swell for attention)
 * - blocked: thinking, with a swell every 3 s (and the red dot)
 */
class Drive {
    var phase = ""
        private set
    private var since = 0.0
    private var next = 0.0

    fun enter(phase: String, sim: Sim) {
        if (phase == this.phase) return
        this.phase = phase
        since = sim.t
        next = sim.t
        val rest = Opts()
        sim.o.beat = 0.0
        sim.o.hotspot = rest.hotspot
        sim.o.hotspotDepth = rest.hotspotDepth
        when (phase) {
            "off", "standby" -> sim.mood("rest")
            "listening" -> {
                sim.mood("working")
                sim.focus(null, 1.0)
                sim.o.beat = 1.0 / 0.7
                sim.o.beatStrength = 0.5
            }
            "transcribing" -> {
                sim.setEnergy(0.9)
                sim.spin(null, 0.5)
            }
            "thinking" -> sim.mood("thinking")
            "speaking", "announcing" -> {
                sim.mood("working")
                sim.o.beat = 1.0 / 0.42
                sim.o.beatStrength = 0.35
                if (phase == "announcing") sim.pulse(0.35)
            }
            "armed" -> sim.mood("important")
            "blocked" -> sim.mood("thinking")
            else -> sim.mood("idle")
        }
    }

    /** Moves that repeat while a phase lasts. */
    fun tick(sim: Sim) {
        if (sim.t < next) return
        when (phase) {
            "thinking" -> {
                sim.look(90.0, 2.5)
                next = sim.t + 2.4
            }
            "blocked" -> {
                if (sim.t > since) sim.pulse(0.35)
                next = sim.t + 3.0
            }
            "transcribing" -> {
                if (sim.t > since) sim.spin(null, 0.3)
                next = sim.t + 0.7
            }
            else -> next = Double.POSITIVE_INFINITY
        }
    }

    fun cue(): Cue = when (phase) {
        "listening", "armed" -> Cue.LISTENING
        "speaking" -> Cue.REPLYING
        "announcing" -> Cue.ANNOUNCING
        else -> Cue.CALM
    }

    /** The halo's strength now (0..1, times the cue's). */
    fun haloPulse(sim: Sim, realT: Double): Double = when {
        phase == "listening" || phase == "armed" -> 0.55 + 0.45 * sin(realT * 2 * PI / 1.1)
        (phase == "speaking" || phase == "announcing") && sim.o.beat > 0.0 -> {
            val x = sim.t * sim.o.beat
            0.35 + 0.65 * exp(-(x - floor(x)) * 4.0)
        }
        else -> 1.0
    }

    /** How opaque the orb is. */
    fun fade(t: Double): Double = when (phase) {
        "off" -> 0.35
        "standby" -> {
            val f = (t - since) / 2.6
            val x0 = f - floor(f)
            val x = if (x0 < 0.5) x0 * 2.0 else (1.0 - x0) * 2.0
            val e = if (x < 0.5) 4.0 * x * x * x else 1.0 - (-2.0 * x + 2.0).pow(3) / 2.0
            1.0 - 0.45 * e
        }
        else -> 1.0
    }
}
