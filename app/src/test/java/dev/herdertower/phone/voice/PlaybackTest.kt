package dev.herdertower.phone.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTest {
    private fun pcm(vararg samples: Int): ByteArray {
        val b = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, v ->
            b[2 * i] = (v and 0xff).toByte()
            b[2 * i + 1] = (v shr 8).toByte()
        }
        return b
    }

    private fun sample(b: ByteArray, i: Int) = ((b[2 * i].toInt() and 0xff) or (b[2 * i + 1].toInt() shl 8)).toShort().toInt()

    @Test fun clipEdgesFadeSoJoinsDontClick() {
        val p = pcm(*IntArray(1000) { 10_000 })
        Wav.fadeEdges(p, sampleRate = 24_000, channels = 1, ms = 3) // 72 samples each end
        assertEquals(0, sample(p, 0))
        assertEquals(0, sample(p, 999))
        assertTrue(sample(p, 36) in 4_000..6_000)
        assertEquals(10_000, sample(p, 72))
        assertEquals(10_000, sample(p, 500))
        assertEquals(10_000, sample(p, 999 - 72))
    }

    @Test fun aTinyClipStillFades() {
        val p = pcm(20_000, 20_000, 20_000, 20_000)
        Wav.fadeEdges(p, 24_000, 1, 3)
        assertEquals(0, sample(p, 0))
        assertEquals(0, sample(p, 3))
    }

    @Test fun levelInDb() {
        assertEquals(-90.0, Wav.rmsDb(pcm(0, 0, 0)), 0.01)
        assertEquals(-6.0, Wav.rmsDb(pcm(16384, -16384, 16384, -16384)), 0.1)
    }

    private fun frame(level: Int) = pcm(*IntArray(320) { if (it % 2 == 0) level else -level })

    /** Feeds [ms] of a steady level; returns the bursts that came out. */
    private fun Bursts.run(level: Int, ms: Int): List<ByteArray> = (0 until ms / 20).mapNotNull { feed(frame(level), level.toDouble()) }

    @Test fun aShortWordComesOutAsABurst() {
        val b = Bursts()
        assertTrue(b.run(100, 1000).isEmpty()) // a quiet room
        assertTrue(b.run(3000, 400).isEmpty()) // "stop"
        val out = b.run(100, 400)
        assertEquals(1, out.size)
        // The word, a little before it, and the pause after it.
        assertTrue(out[0].size / 32 in 500..1000)
    }

    @Test fun theTowersOwnLongSentencesAreNotSent() {
        val b = Bursts()
        b.run(100, 1000)
        assertTrue(b.run(3000, 4000).isEmpty())
        assertTrue(b.run(100, 400).isEmpty()) // too long to be someone saying stop
    }

    @Test fun clicksAreNotWords() {
        val b = Bursts()
        b.run(100, 1000)
        b.run(3000, 60)
        assertTrue(b.run(100, 400).isEmpty())
    }

    @Test fun aWordOverTheTowerTalkingQuietly() {
        val b = Bursts()
        b.run(100, 500)
        b.run(800, 3000) // the tower, heard back softly: the floor follows it
        assertNull(b.run(800, 200).firstOrNull())
        b.run(6000, 400) // someone near the phone says stop
        assertNotNull(b.run(800, 400).firstOrNull())
    }
}
