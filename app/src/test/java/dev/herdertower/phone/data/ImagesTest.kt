package dev.herdertower.phone.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagesTest {
    @Test fun subsamplingKeepsAtLeastTheSizeAsked() {
        assertEquals(1, Images.sampleSize(800, 600, 720))
        assertEquals(4, Images.sampleSize(3456, 2234, 720)) // 864 px across, then scaled to 720
        assertEquals(4, Images.sampleSize(5120, 2880, 720))
        assertEquals(1, Images.sampleSize(5120, 2880, 3200))
        for ((w, h, side) in listOf(Triple(100, 50, 720), Triple(6000, 4000, 360), Triple(4000, 9000, 1200))) {
            val n = Images.sampleSize(w, h, side)
            assertTrue(maxOf(w, h) / n >= side || n == 1)
            assertTrue(maxOf(w, h) / (n * 2) < side)
        }
    }

    @Test fun locksAreAFixedSet() {
        val stripes = (0 until 5000).map { Images.stripe("shot:$it") }.toSet()
        assertTrue(stripes.all { it in 0 until Images.LOCKS })
        assertEquals(Images.LOCKS, stripes.size)
        assertTrue(Images.stripe("${Int.MIN_VALUE}") in 0 until Images.LOCKS)
    }

    @Test fun diskNamesAreSafeAndDistinct() {
        // Ids and Mac paths can hold slashes and colons: the file name is a hash, the same each time.
        val a = Images.fileName("shot:1794160000001-checkout.png@720")
        val b = Images.fileName("att:/Users/me/attachments/2026/a b:c.png@360")
        assertTrue(Regex("^[0-9a-f]{64}\\.jpg$").matches(a))
        assertTrue(Regex("^[0-9a-f]{64}\\.jpg$").matches(b))
        assertTrue(a != b)
        assertTrue(a == Images.fileName("shot:1794160000001-checkout.png@720"))
        assertTrue(a != Images.fileName("shot:1794160000001-checkout.png@360"))
    }
}
