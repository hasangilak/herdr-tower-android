package dev.herdertower.phone.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavTest {
    @Test fun encodesA44ByteHeaderThatParsesBack() {
        val pcm = ByteArray(32_000) { (it % 251).toByte() } // one second at 16 kHz mono
        val wav = Wav.encode(pcm)
        assertEquals(44 + pcm.size, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        val le = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(36 + pcm.size, le.getInt(4))
        assertEquals(16_000, le.getInt(24))
        assertEquals(32_000, le.getInt(28)) // byte rate
        val info = Wav.parse(wav)
        assertEquals(Wav.Info(1, 1, 16_000, 16, 44, pcm.size), info)
        assertEquals(1000L, info.durationMs)
        assertArrayEquals(pcm, Wav.pcm16(wav, info))
    }

    @Test fun skipsExtraChunksBeforeData() {
        val pcm = ByteArray(480)
        val plain = Wav.encode(pcm, 24_000)
        // Insert a LIST chunk (odd size, padded) between fmt and data.
        val list = ByteBuffer.allocate(8 + 5 + 1).order(ByteOrder.LITTLE_ENDIAN).put("LIST".toByteArray()).putInt(5).put(ByteArray(6)).array()
        val wav = plain.copyOfRange(0, 36) + list + plain.copyOfRange(36, plain.size)
        val info = Wav.parse(wav)
        assertEquals(24_000, info.sampleRate)
        assertEquals(36 + list.size + 8, info.dataOffset)
        assertEquals(480, info.dataLength)
    }

    @Test fun convertsFloatToPcm16() {
        val floats = floatArrayOf(0f, 1f, -1f, 0.5f)
        val data = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply { floats.forEach { putFloat(it) } }.array()
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + 16).put("WAVE".toByteArray())
            .put("fmt ".toByteArray()).putInt(16).putShort(3).putShort(1).putInt(24_000).putInt(96_000).putShort(4).putShort(32)
            .put("data".toByteArray()).putInt(16).array()
        val wav = h + data
        val pcm = ByteBuffer.wrap(Wav.pcm16(wav, Wav.parse(wav))).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(listOf<Short>(0, 32767, -32767, 16383), List(4) { pcm.short })
    }

    @Test fun rejectsNonWav() {
        assertThrows(IllegalArgumentException::class.java) { Wav.parse("not a wav file at all".toByteArray()) }
    }

    @Test fun aHugeChunkSizeIsNotAWav() {
        val plain = Wav.encode(ByteArray(64))
        // A LIST chunk claiming ~4 GB before the data: it can't wrap around into the file.
        val list = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).put("LIST".toByteArray()).putInt(0x7FFFFFF8).array()
        val wav = plain.copyOfRange(0, 36) + list + plain.copyOfRange(36, plain.size)
        assertThrows(IllegalArgumentException::class.java) { Wav.parse(wav) }
    }

    @Test fun aTruncatedExtensibleHeaderIsNotACrash() {
        // Says extensible (40 bytes of fmt) but the file ends after the first 20.
        val h = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(32).put("WAVE".toByteArray())
            .put("fmt ".toByteArray()).putInt(40).putShort(0xFFFE.toShort()).putShort(1).putInt(16_000).putInt(32_000).putShort(2).putShort(16).array()
        assertThrows(IllegalArgumentException::class.java) { Wav.parse(h) }
    }

    @Test fun floatSamplesThatDontFillAFrameAreDropped() {
        val data = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).putFloat(0.5f).put(0).put(0).array()
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + 6).put("WAVE".toByteArray())
            .put("fmt ".toByteArray()).putInt(16).putShort(3).putShort(1).putInt(24_000).putInt(96_000).putShort(4).putShort(32)
            .put("data".toByteArray()).putInt(6).array()
        val wav = h + data
        assertEquals(2, Wav.pcm16(wav, Wav.parse(wav)).size)
    }
}
