package dev.herdertower.phone.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.InputStream

/** Files picked for an answer: streamed to disk with a cap, never read whole into memory. */
class AnswerFilesTest {
    private fun temp() = File.createTempFile("answer", ".bin").also { it.deleteOnExit() }

    @Test fun aFileUnderTheCapIsCopied() {
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        val out = temp()
        copyCapped("photo.png", bytes.inputStream(), out, max = 300_000)
        assertArrayEquals(bytes, out.readBytes())
    }

    @Test fun aFileOverTheCapIsRefusedAndNothingIsLeft() {
        // A stream as long as a 60 MB file that never exists in memory: it stops at the cap.
        var served = 0L
        val huge = object : InputStream() {
            override fun read(): Int = if (served++ < 60L * 1024 * 1024) 0 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (served >= 60L * 1024 * 1024) return -1
                val n = minOf(len.toLong(), 60L * 1024 * 1024 - served).toInt()
                served += n
                return n
            }
        }
        val out = temp()
        try {
            copyCapped("video.mov", huge, out)
            fail("over 50 MB must be refused")
        } catch (e: TooBig) {
            assertEquals("video.mov", e.name)
        }
        assertFalse("the partial copy is deleted", out.exists())
        // It read just past the cap, not the whole 60 MB.
        assertEquals(true, served in MAX_FILE_BYTES..MAX_FILE_BYTES + 64 * 1024)
    }
}
