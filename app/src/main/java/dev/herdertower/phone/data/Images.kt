package dev.herdertower.phone.data

import android.graphics.Bitmap
import androidx.core.graphics.scale
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
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
 * full images (capped in size) for the viewer. The tower serves only the
 * full image, so thumbnails are also kept on disk ([diskDir]): fetched once,
 * not again on every visit or after a restart.
 */
class Images(private val session: TowerSession, private val diskDir: () -> File? = { null }) {
    /** Thumbnails apart from full images, so opening a few full ones doesn't push every thumbnail out. */
    private val thumbs = bitmaps(Runtime.getRuntime().maxMemory() / 16)
    private val full = bitmaps(Runtime.getRuntime().maxMemory() / 16)

    /** A lock per stripe of ids (a fixed number, however many pictures go by): one fetch per picture at a time. */
    private val locks = Array(LOCKS) { Mutex() }

    /** The files themselves, so a thumbnail and the full view fetch once. */
    private val raw = object : LruCache<String, ByteArray>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }

    private fun lock(id: String) = locks[stripe(id)]

    /** Forgets every picture (paired with another tower: its ids could be the same). */
    fun clear() {
        thumbs.evictAll()
        full.evictAll()
        raw.evictAll()
        diskDir()?.listFiles()?.forEach { it.delete() }
    }

    private fun cache(maxSide: Int) = if (maxSide <= THUMB_SIDE) thumbs else full

    /** A screenshot at most [maxSide] pixels on its longer side, or null when it can't be had. */
    suspend fun load(shot: Screenshot, maxSide: Int): ImageBitmap? = load("shot:${shot.id}", maxSide) { it.screenshotImage(shot) }

    /** A picture sent with a message, from its path on the Mac. */
    suspend fun loadAttachment(path: String, maxSide: Int): ImageBitmap? = load("att:$path", maxSide) { it.attachmentImage(path) }

    private suspend fun load(id: String, maxSide: Int, fetch: suspend (dev.herdertower.phone.api.TowerClient) -> ByteArray): ImageBitmap? {
        val key = "$id@$maxSide"
        val decoded = cache(maxSide)
        decoded.get(key)?.let { return it.asImageBitmap() }
        return lock(id).withLock {
            decoded.get(key)?.let { return@withLock it.asImageBitmap() }
            val disk = if (maxSide <= THUMB_SIDE) diskDir()?.let { File(it, fileName(key)) } else null
            disk?.takeIf { it.isFile }?.let { f ->
                withContext(Dispatchers.IO) { BitmapFactory.decodeFile(f.path) }?.let { bmp ->
                    decoded.put(key, bmp)
                    return@withLock bmp.asImageBitmap()
                }
            }
            val data = raw.get(id) ?: session.act { fetch(it) }.getOrNull()?.also { raw.put(id, it) } ?: return@withLock null
            val bmp = withContext(Dispatchers.Default) { decode(data, maxSide) } ?: return@withLock null
            decoded.put(key, bmp)
            if (disk != null) withContext(Dispatchers.IO) { save(bmp, disk) }
            bmp.asImageBitmap()
        }
    }

    /** Writes a thumbnail to disk (best effort), keeping the folder under [DISK_BYTES] by dropping the oldest. */
    private fun save(bmp: Bitmap, f: File) {
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            tmp.renameTo(f)
            val files = f.parentFile?.listFiles()?.sortedBy { it.lastModified() }.orEmpty()
            var total = files.sumOf { it.length() }
            for (old in files) {
                if (total <= DISK_BYTES) break
                total -= old.length()
                old.delete()
            }
        }
    }

    companion object {
        /** Up to this size a picture counts as a thumbnail (the grid's, the strips', the chat's). */
        const val THUMB_SIDE = 720
        const val LOCKS = 32
        /** How much the thumbnails on disk may take. */
        const val DISK_BYTES = 40L * 1024 * 1024

        /** A file name for a cache key: a hash, so any id or path is a safe name. */
        fun fileName(key: String): String =
            java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) } + ".jpg"

        fun stripe(id: String): Int = (id.hashCode() and Int.MAX_VALUE) % LOCKS

        private fun bitmaps(bytes: Long) = object : LruCache<String, Bitmap>(bytes.toInt().coerceAtLeast(1)) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        }

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
            return bmp.scale((bmp.width * s).toInt().coerceAtLeast(1), (bmp.height * s).toInt().coerceAtLeast(1))
        }

        fun sampleSize(w: Int, h: Int, maxSide: Int): Int {
            var n = 1
            while (maxOf(w, h) / (n * 2) >= maxSide) n *= 2
            return n
        }
    }
}
