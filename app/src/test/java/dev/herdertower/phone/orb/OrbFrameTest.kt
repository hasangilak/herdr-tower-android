package dev.herdertower.phone.orb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The frame's allocation-free paths give exactly what the plain ones give. */
class OrbFrameTest {
    private fun boil(height: Double, phase: Double) = OrbMaps.BoilParams(
        height = height, frequency = 7.0 / 2.8, phase = phase, hot = doubleArrayOf(0.3, -0.2, 0.93), spread = 0.6, shape = 2.0,
    )

    @Test fun keptLatticeHashesGiveTheSameBoil() {
        val lattice = OrbMaps.Lattice(bits = 8) // small, so it also fills up and computes the rest directly
        for (phase in listOf(0.0, 1.37, 523.9)) {
            val b = boil(0.06, phase)
            lattice.newFrame()
            for (j in 0 until 64) for (i in 0 until 128) {
                val d = OrbMaps.dir(i, j, 128, 64)
                assertEquals(OrbMaps.boilAt(d[0], d[1], d[2], b), lattice.boilAt(d[0], d[1], d[2], b), 0.0)
            }
        }
    }

    @Test fun theMapsAreTheTexelByTexelBoil() {
        val maps = OrbMaps(16, 8, 16, 8)
        val b = boil(0.08, 2.5)
        assertTrue(maps.update(FloatArray(48), b))
        val hi = ByteArray(16 * 8)
        val lo = ByteArray(16 * 8)
        for (j in 0 until 8) for (i in 0 until 16) {
            val d = OrbMaps.dir(i, j, 16, 8)
            OrbMaps.split(OrbMaps.boilAt(d[0], d[1], d[2], b), hi, lo, j * 16 + i)
        }
        assertArrayEquals(hi, maps.boilHi)
        assertArrayEquals(lo, maps.boilLo)
    }

    @Test fun noBoilingIsFilledOnceAndIsTheSameAsComputed() {
        val maps = OrbMaps(16, 8, 16, 8)
        val flat = boil(1e-7, 0.4)
        assertTrue(maps.update(FloatArray(48), flat))
        assertFalse(maps.update(FloatArray(48), flat)) // unchanged: not uploaded again
        for (j in 0 until 8) for (i in 0 until 16) {
            val d = OrbMaps.dir(i, j, 16, 8)
            val t = j * 16 + i
            assertEquals(OrbMaps.join(maps.boilHi[t], maps.boilLo[t]), OrbMaps.join(byteOf(d, flat, true), byteOf(d, flat, false)), 0.0)
        }
        assertTrue(maps.update(FloatArray(48), boil(0.06, 0.4))) // boiling again
    }

    private fun byteOf(d: DoubleArray, b: OrbMaps.BoilParams, high: Boolean): Byte {
        val hi = ByteArray(1)
        val lo = ByteArray(1)
        OrbMaps.split(OrbMaps.boilAt(d[0], d[1], d[2], b), hi, lo, 0)
        return if (high) hi[0] else lo[0]
    }

    @Test fun inPlaceMathIsTheSame() {
        val r = Rng(3)
        repeat(200) {
            val axis = doubleArrayOf(r.gauss(), r.gauss(), r.gauss())
            val angle = r.next() * 6.0
            val m = DoubleArray(9)
            Sim.axisAngleInto(axis, angle, m)
            assertArrayEquals(Sim.axisAngle(axis, angle), m, 0.0)
            val other = Sim.axisAngle(doubleArrayOf(r.gauss(), r.gauss(), r.gauss()), r.next())
            val p = DoubleArray(9)
            Sim.mulInto(m, other, p)
            assertArrayEquals(Sim.mul(m, other), p, 0.0)
            val o = DoubleArray(9)
            Sim.orthonormalizeInto(p, o)
            assertArrayEquals(Sim.orthonormalize(p), o, 0.0)
            val n = DoubleArray(3)
            Sim.normalizeInto(axis, n)
            assertArrayEquals(Sim.normalize(axis), n, 0.0)
            val c = DoubleArray(3)
            Sim.crossInto(axis, n, c)
            assertArrayEquals(Sim.cross(axis, n), c, 0.0)
        }
    }

    @Test fun theHotspotLandsInTheArrayGiven() {
        val s = Sim()
        s.simulateTo(1.0)
        val out = DoubleArray(3)
        assertTrue(s.hotspotDir(out) === out)
        assertArrayEquals(s.hotspotDir(), out, 0.0)
    }
}
