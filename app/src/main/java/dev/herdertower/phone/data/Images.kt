package dev.herdertower.phone.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.herdertower.phone.api.Screenshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Screenshots from the tower, fetched with the token (an image loader
 * can't add the header) and kept in memory: small thumbnails for the grid,
 * full images (capped in size) for the viewer.
 */
class Images(private val session: TowerSession) {
    private val decoded = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val locks = HashMap<String, Mutex>()

    /** The files themselves, so a thumbnail and the full view fetch once. */
    private val raw = object : LruCache<String, ByteArray>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }

    private fun lock(id: String) = synchronized(locks) { locks.getOrPut(id) { Mutex() } }

    /** The image at most [maxSide] pixels on its longer side, or null when it can't be had. */
    suspend fun load(shot: Screenshot, maxSide: Int): ImageBitmap? {
        val key = "${shot.id}@$maxSide"
        decoded.get(key)?.let { return it.asImageBitmap() }
        return lock(shot.id).withLock {
            decoded.get(key)?.let { return@withLock it.asImageBitmap() }
            val data = raw.get(shot.id) ?: session.act { it.screenshotImage(shot) }.getOrNull()?.also { raw.put(shot.id, it) } ?: return@withLock null
            val bmp = withContext(Dispatchers.Default) { decode(data, maxSide) } ?: return@withLock null
            decoded.put(key, bmp)
            bmp.asImageBitmap()
        }
    }

    companion object {
        /** Decodes with the largest power-of-two subsampling that keeps it at least [maxSide], then scales down to fit. */
        fun decode(data: ByteArray, maxSide: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide) }
            val bmp = BitmapFactory.decodeByteArray(data, 0, data.size, opts) ?: return null
            val longer = maxOf(bmp.width, bmp.height)
            if (longer <= maxSide) return bmp
            val s = maxSide.toFloat() / longer
            return Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt().coerceAtLeast(1), (bmp.height * s).toInt().coerceAtLeast(1), true)
        }

        fun sampleSize(w: Int, h: Int, maxSide: Int): Int {
            var n = 1
            while (maxOf(w, h) / (n * 2) >= maxSide) n *= 2
            return n
        }
    }
}
