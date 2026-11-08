package dev.herdertower.phone.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** RIFF WAVE in and out: 16-bit PCM up (16 kHz mono), whatever the tower sends down (24 kHz mono 16-bit). */
object Wav {
    const val HEADER = 44

    /** A 44-byte header in front of little-endian 16-bit PCM. */
    fun encode(pcm: ByteArray, sampleRate: Int = 16_000, channels: Int = 1): ByteArray {
        val byteRate = sampleRate * channels * 2
        val b = ByteBuffer.allocate(HEADER + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort())
            .putInt(sampleRate).putInt(byteRate).putShort((channels * 2).toShort()).putShort(16)
        b.put("data".toByteArray()).putInt(pcm.size).put(pcm)
        return b.array()
    }

    /** Where the samples are and how they're laid out. `format` 1 = PCM, 3 = IEEE float. */
    data class Info(val format: Int, val channels: Int, val sampleRate: Int, val bitsPerSample: Int, val dataOffset: Int, val dataLength: Int) {
        val bytesPerFrame: Int get() = channels * bitsPerSample / 8
        val durationMs: Long get() = if (bytesPerFrame == 0 || sampleRate == 0) 0 else dataLength.toLong() * 1000 / (bytesPerFrame.toLong() * sampleRate)
    }

    /** Walks the chunks; throws [IllegalArgumentException] for anything that isn't a WAV it can play. */
    fun parse(bytes: ByteArray): Info {
        require(bytes.size >= 12) { "too short for a WAV" }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(tag(bytes, 0) == "RIFF" && tag(bytes, 8) == "WAVE") { "not a RIFF WAVE" }
        var pos = 12
        var fmt: IntArray? = null
        while (pos + 8 <= bytes.size) {
            val id = tag(bytes, pos)
            val size = b.getInt(pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    require(size >= 16 && body + 16 <= bytes.size) { "bad fmt chunk" }
                    var format = b.getShort(body).toInt() and 0xffff
                    if (format == 0xFFFE && size >= 40) format = b.getShort(body + 24).toInt() and 0xffff // WAVE_FORMAT_EXTENSIBLE
                    fmt = intArrayOf(format, b.getShort(body + 2).toInt(), b.getInt(body + 4), b.getShort(body + 14).toInt())
                }
                "data" -> {
                    val f = fmt ?: throw IllegalArgumentException("data before fmt")
                    // Streams written before their length is known say 0 or 0xFFFFFFFF: take the rest.
                    val len = if (size <= 0 || body + size > bytes.size) bytes.size - body else size
                    return Info(f[0], f[1], f[2], f[3], body, len)
                }
            }
            if (size < 0) break
            pos = body + size + (size and 1)
        }
        throw IllegalArgumentException("no data chunk")
    }

    /** The samples as 16-bit little-endian PCM (float WAVs are converted). */
    fun pcm16(bytes: ByteArray, info: Info): ByteArray = when {
        info.format == 1 && info.bitsPerSample == 16 -> bytes.copyOfRange(info.dataOffset, info.dataOffset + info.dataLength)
        info.format == 3 && info.bitsPerSample == 32 -> {
            val src = ByteBuffer.wrap(bytes, info.dataOffset, info.dataLength).order(ByteOrder.LITTLE_ENDIAN)
            val out = ByteBuffer.allocate(info.dataLength / 2).order(ByteOrder.LITTLE_ENDIAN)
            while (src.remaining() >= 4) out.putShort((src.float.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            out.array()
        }
        else -> throw IllegalArgumentException("unsupported WAV: format ${info.format}, ${info.bitsPerSample} bits")
    }

    private fun tag(bytes: ByteArray, at: Int) = String(bytes, at, 4, Charsets.US_ASCII)
}
