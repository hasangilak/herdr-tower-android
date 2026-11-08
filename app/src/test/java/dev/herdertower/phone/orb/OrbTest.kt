package dev.herdertower.phone.orb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/** The port of the Mac's orb (`src/orb/{liquid,sim,render}.rs`). */
class OrbTest {
    @Test fun mulberryMatchesTheReference() {
        // mulberry32(7), as the prototype's JS and the Mac's Rust compute it.
        val r = Rng(7)
        assertEquals(0.011704753153026104, r.next(), 1e-15)
        assertEquals(0.06195825757458806, r.next(), 1e-15)
        assertEquals(0.97690763277933, r.next(), 1e-14)
    }

    @Test fun fortyFiveModesInTheShadersOrder() {
        assertEquals(45, Modes.DEGREE.size)
        assertEquals(listOf(2, 3, 4, 5, 6, 2, 2, 3, 3), Modes.DEGREE.take(9).toList())
    }

    @Test fun aPokeRingsThenSettles() {
        // Liquid's own test on the Mac.
        val l = Liquid(7)
        val flow = Flow(3.0, 0.2, 0.0, 0.0, 0.12)
        l.impulse { x, _, _ -> -5.0 * kotlin.math.exp(8.0 * (x - 1.0)) }
        var peak = 0.0
        repeat(120) {
            l.step(1.0 / 120.0, flow, null)
            peak = maxOf(peak, l.extent())
        }
        assertTrue(peak > 0.01 && peak <= 0.12 + 1e-9)
        repeat(120 * 20) { l.step(1.0 / 120.0, flow, null) }
        assertTrue(l.extent() < peak * 0.05)
    }

    @Test fun turnsAndStaysARotation() {
        // Sim's own test on the Mac.
        val s = Sim()
        val r0 = s.rot.copyOf()
        s.simulateTo(3.0)
        assertNotEquals(r0.toList(), s.rot.toList())
        val c0 = doubleArrayOf(s.rot[0], s.rot[3], s.rot[6])
        assertEquals(1.0, sqrt(c0[0] * c0[0] + c0[1] * c0[1] + c0[2] * c0[2]), 1e-9)
        assertTrue(s.bound() > 1.2 && s.bound() < 1.6)
    }

    @Test fun phasesPlayAsOnTheMac() {
        val sim = Sim()
        val d = Drive()
        d.enter("listening", sim)
        assertEquals(Cue.LISTENING, d.cue())
        assertEquals(1.0 / 0.7, sim.o.beat, 1e-12)
        assertEquals(1.0, sim.o.hotspotDepth, 0.0) // boiling on the face towards you
        d.enter("speaking", sim)
        assertEquals(Cue.REPLYING, d.cue())
        assertEquals(1.0 / 0.42, sim.o.beat, 1e-12)
        d.enter("announcing", sim)
        assertEquals(Cue.ANNOUNCING, d.cue())
        d.enter("blocked", sim)
        assertEquals(Cue.CALM, d.cue())
        assertEquals(0.45, sim.o.energy, 0.0) // thinking
        d.enter("off", sim)
        assertEquals(0.35, d.fade(sim.t), 0.0)
        assertEquals(0.1, sim.o.energy, 0.0)
    }

    @Test fun cuesEaseTowardsEachOther() {
        val half = Cue.CALM.eased(Cue.LISTENING, 0.5)
        assertEquals((0.80f + 0.55f) / 2, half.tint[0], 1e-6f)
        assertEquals(0.65, half.speed, 1e-12)
    }

    @Test fun theLiquidFunctionIsTheMacsStraightLineCode() {
        val src = OrbShader.liquidFunction()
        assertTrue(src.startsWith("float liquid(float3 n) {"))
        // First terms, as liquid_msl() writes them.
        assertTrue("  q = 1.0;" in src)
        assertTrue("  q = 1.0 * n.z * q1 - 0.0 * q2;" in src)
        assertTrue("  h += sh[0].x * q;" in src)
        assertTrue("  h += q * (sh[10].w * re + sh[11].x * im);" in src)
        assertEquals(45, Regex("""sh\[\d+]\.[xyzw]""").findAll(src).count())
        assertTrue(OrbShader.SOURCE.contains("half4 main(float2 fragCoord)"))
    }

    @Test fun mapValuesSurviveTheTwoAlphaBytes() {
        val hi = ByteArray(3)
        val lo = ByteArray(3)
        listOf(-0.5, 0.0123, 0.2999).forEachIndexed { i, v -> OrbMaps.split(v, hi, lo, i) }
        assertEquals(-0.5, OrbMaps.join(hi[0], lo[0]), 1e-4)
        assertEquals(0.0123, OrbMaps.join(hi[1], lo[1]), 1e-4)
        assertEquals(0.2999, OrbMaps.join(hi[2], lo[2]), 1e-4)
    }

    @Test fun theAnalyticLiquidKeepsOnlyTheBroadModes() {
        val src = OrbShader.liquidFunction(OrbShader.ANALYTIC_LMAX)
        // l <= 3: m = 0 has 2 terms, m = 1..3 have 2, 2, 1 pairs: 2 + 2*5 = 12 coefficients.
        assertEquals(12, Regex("""sh\[\d+]\.[xyzw]""").findAll(src).count())
        assertTrue(OrbShader.source(100, OrbShader.Mode.Analytic).contains("uniform float4 sh[12];"))
        assertTrue(OrbShader.source(100, OrbShader.Mode.Maps).contains("uniform shader boilLo;"))
        assertTrue(OrbShader.LOOKUP_PROBE.contains("half4 main(float2 p)"))
    }

    @Test fun boostIsOneAtNormalEnergy() {
        assertEquals(1.0, Sim.boost(0.3, 0.4, 2.0, 0.6), 1e-12)
        assertTrue(abs(Sim.boost(1.0, 0.4, 2.0, 0.6) - Math.pow(2.0, 0.6)) < 1e-12)
    }
}
