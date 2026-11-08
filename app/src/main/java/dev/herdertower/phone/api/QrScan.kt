package dev.herdertower.phone.api

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/** Finds a QR code in a camera frame's luminance (the Y plane) and returns its text. */
class QrScan {
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true))
    }

    /** [y] is width x height bytes, row after row. */
    fun decode(y: ByteArray, width: Int, height: Int): String? = try {
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(PlanarYUVLuminanceSource(y, width, height, 0, 0, width, height, false)))).text
    } catch (e: Exception) {
        null
    } finally {
        reader.reset()
    }
}
