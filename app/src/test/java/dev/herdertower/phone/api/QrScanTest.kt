package dev.herdertower.phone.api

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The pairing QR, from the Mac's link to a parsed PairLink, through the same decoder the camera uses. */
class QrScanTest {
    private val fp = "abababababababababababababababababababababababababababababababab"
    // As `src/daemon/lan/mod.rs` writes it: format!("herdertower://pair?h={host}&p={port}&c={code}&fp={}", fingerprint)
    private val link = "herdertower://pair?h=192.168.1.20&p=7843&c=04821390&fp=$fp"

    /** A camera-like frame: the QR at [module] pixels per module on a grey background, optionally rotated a quarter turn. */
    private fun frame(text: String, size: Int = 640, module: Int = 6, rotate: Boolean = false): ByteArray {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 4))
        val y = ByteArray(size * size) { 180.toByte() }
        val off = (size - m.width * module) / 2
        for (r in 0 until m.height) for (c in 0 until m.width) {
            val v: Byte = if (m.get(c, r)) 20 else 235.toByte()
            for (dy in 0 until module) for (dx in 0 until module) {
                var x = off + c * module + dx
                var yy = off + r * module + dy
                if (rotate) yy = (off + c * module + dx).also { x = size - 1 - (off + r * module + dy) }
                y[yy * size + x] = v
            }
        }
        return y
    }

    @Test fun decodesTheMacsPairingQrAndParsesIt() {
        val text = QrScan().decode(frame(link), 640, 640)
        assertEquals(link, text)
        assertEquals(PairLink("192.168.1.20", 7843, "04821390", fp), PairLink.parse(text!!))
    }

    @Test fun decodesItTurnedAQuarter() {
        assertEquals(link, QrScan().decode(frame(link, rotate = true), 640, 640))
    }

    @Test fun anEmptyFrameHasNoCode() {
        assertNull(QrScan().decode(ByteArray(320 * 240) { 128.toByte() }, 320, 240))
    }
}
