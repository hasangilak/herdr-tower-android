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
            // Sizes are unsigned 32-bit: as a Long so a huge one can't wrap around.
            val size = b.getInt(pos + 4).toLong() and 0xFFFFFFFFL
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    require(size >= 16 && body + 16 <= bytes.size) { "bad fmt chunk" }
                    var format = b.getShort(body).toInt() and 0xffff
                    if (format == 0xFFFE && size >= 40 && body + 26 <= bytes.size) format = b.getShort(body + 24).toInt() and 0xffff // WAVE_FORMAT_EXTENSIBLE
                    fmt = intArrayOf(format, b.getShort(body + 2).toInt(), b.getInt(body + 4), b.getShort(body + 14).toInt())
                }
                "data" -> {
                    val f = fmt ?: throw IllegalArgumentException("data before fmt")
                    // Streams written before their length is known say 0 or 0xFFFFFFFF: take the rest.
                    val len = if (size == 0L || body + size > bytes.size) bytes.size - body else size.toInt()
                    return Info(f[0], f[1], f[2], f[3], body, len)
                }
            }
            val next = body + size + (size and 1)
            if (next > bytes.size) break
            pos = next.toInt()
        }
        throw IllegalArgumentException("no data chunk")
    }

    /** The samples as 16-bit little-endian PCM (float WAVs are converted). */
    fun pcm16(bytes: ByteArray, info: Info): ByteArray = when {
        info.format == 1 && info.bitsPerSample == 16 -> bytes.copyOfRange(info.dataOffset, info.dataOffset + info.dataLength)
        info.format == 3 && info.bitsPerSample == 32 -> {
            val src = ByteBuffer.wrap(bytes, info.dataOffset, info.dataLength).order(ByteOrder.LITTLE_ENDIAN)
            val out = ByteBuffer.allocate(info.dataLength / 4 * 2).order(ByteOrder.LITTLE_ENDIAN)
            while (src.remaining() >= 4) out.putShort((src.float.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            out.array()
        }
        else -> throw IllegalArgumentException("unsupported WAV: format ${info.format}, ${info.bitsPerSample} bits")
    }

    /**
     * Fades the first and last [ms] of 16-bit PCM in and out, in place, so
     * clips joined end to end don't click where a sentence starts or stops
     * off zero.
     */
    fun fadeEdges(pcm: ByteArray, sampleRate: Int, channels: Int, ms: Int = 3) {
        val frames = pcm.size / (2 * channels)
        val n = minOf(sampleRate * ms / 1000, frames / 2)
        if (n <= 0) return
        fun scale(frame: Int, g: Float) {
            for (c in 0 until channels) {
                val i = (frame * channels + c) * 2
                val v = ((pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8)).toShort()
                val out = (v * g).toInt()
                pcm[i] = (out and 0xff).toByte()
                pcm[i + 1] = (out shr 8).toByte()
            }
        }
        for (k in 0 until n) {
            val g = k.toFloat() / n
            scale(k, g)
            scale(frames - 1 - k, g)
        }
    }

    /** The level of 16-bit PCM in dBFS (RMS), for the clip log: -90 for silence. */
    fun rmsDb(pcm: ByteArray): Double {
        var sum = 0.0
        val n = pcm.size / 2
        if (n == 0) return -90.0
        for (i in 0 until n) {
            val v = ((pcm[2 * i].toInt() and 0xff) or (pcm[2 * i + 1].toInt() shl 8)).toShort().toDouble()
            sum += v * v
        }
        val rms = kotlin.math.sqrt(sum / n) / 32768.0
        return if (rms <= 0) -90.0 else maxOf(-90.0, 20 * kotlin.math.log10(rms))
    }

    private fun tag(bytes: ByteArray, at: Int) = String(bytes, at, 4, Charsets.US_ASCII)
}
