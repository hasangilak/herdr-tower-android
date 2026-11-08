package dev.herdertower.phone.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DraftTokensTest {
    @Test fun insertsATokenWhereTheCursorIsWithSpaces() {
        assertEquals("[Image #1] " to 11, DraftTokens.insert("", 0, 1))
        assertEquals("what is wrong here? [Image #1] " to 31, DraftTokens.insert("what is wrong here?", 19, 1))
        assertEquals("compare [Image #2] with this" to 19, DraftTokens.insert("compare with this", 8, 2))
    }

    @Test fun removingAPictureRemovesItsTokenAndTheDoubleSpace() {
        assertEquals("compare with this", DraftTokens.remove("compare [Image #2] with this", 2))
        assertEquals("", DraftTokens.remove("[Image #1] ", 1))
        assertEquals("[Image #1] and", DraftTokens.remove("[Image #1] and [Image #3]", 3))
    }

    @Test fun numbersGoOnPastEveryOneUsed() {
        assertEquals(1, DraftTokens.next("", emptyList()))
        assertEquals(3, DraftTokens.next("see [Image #2]", listOf(1, 2)))
        assertEquals(listOf(2, 1), DraftTokens.numbers("[Image #2] then [Image #1]"))
    }

    @Test fun renumbersInOrderForTheTowersMarkers() {
        val (text, map) = DraftTokens.renumber("[Image #3] vs [Image #1], again [Image #3]")
        assertEquals("[Image #1] vs [Image #2], again [Image #1]", text)
        assertEquals(mapOf(3 to 1, 1 to 2), map)
    }

    @Test fun picturesAreKnownByTheirBytes() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)
        assertEquals("image/png", Composer.Images.sniff(png))
        assertEquals("image/jpeg", Composer.Images.sniff(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertEquals("image/webp", Composer.Images.sniff("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)))
        assertEquals("image/gif", Composer.Images.sniff("GIF89a".toByteArray()))
        assertNull(Composer.Images.sniff("\u0000\u0000\u0000\u0018ftypheic".toByteArray(Charsets.ISO_8859_1))) // HEIC: re-encoded as JPEG
    }
}
