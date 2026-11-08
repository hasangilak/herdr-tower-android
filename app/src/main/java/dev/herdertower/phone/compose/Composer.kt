package dev.herdertower.phone.compose

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.TextRange
import dev.herdertower.phone.api.ImagePart
import dev.herdertower.phone.data.TowerSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** A picture in the draft: its token number and the copy kept with the draft. */
data class Attachment(val number: Int, val path: String, val mime: String) {
    val file: File get() = File(path)
}

/**
 * The one draft the app has: the text input Home and Chat both show, and
 * the pictures attached to it. Saved on every change, so it survives
 * rotation, switching apps and the process being killed; going from typing
 * to talking and back never loses it.
 */
class Composer(context: Context, private val session: TowerSession) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("draft", Context.MODE_PRIVATE)
    private val dir = File(app.filesDir, "draft").apply { mkdirs() }

    val input = TextFieldState(prefs.getString("text", "") ?: "")

    private val _attachments = MutableStateFlow(loadAttachments())
    val attachments: StateFlow<List<Attachment>> = _attachments.asStateFlow()

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    val dictation = Dictation(app)
    /** Where the text stood when dictation began: what it says goes after it. */
    private var dictationBase: String? = null

    init {
        session.scope.launch {
            snapshotFlow { input.text.toString() }.collect { t ->
                prefs.edit().putString("text", t).apply()
                prune(t)
                // You typed over the dictation: stop following it.
                val base = dictationBase
                if (base != null && !t.startsWith(base)) stopDictation()
            }
        }
    }

    val isEmpty: Boolean get() = input.text.isBlank() && _attachments.value.isEmpty()

    // Pictures

    /** Adds pictures (photo picker, camera, paste); returns a message when some couldn't be. */
    suspend fun add(uris: List<Uri>): String? {
        val room = MAX_IMAGES - _attachments.value.size
        if (room <= 0) return "Six pictures at most in one message."
        var failed = 0
        for (uri in uris.take(room)) {
            val made = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("unreadable")
                    store(bytes)
                }.getOrNull()
            }
            if (made == null) failed++ else attach(made.first, made.second)
        }
        return when {
            uris.size > room -> "Six pictures at most in one message."
            failed > 0 -> "Couldn't read ${if (failed == 1) "a picture" else "$failed pictures"}."
            else -> null
        }
    }

    /** A file the camera wrote into the draft folder. */
    suspend fun addCameraFile(file: File): String? {
        if (_attachments.value.size >= MAX_IMAGES) return "Six pictures at most in one message."
        val made = withContext(Dispatchers.IO) { runCatching { store(file.readBytes()).also { file.delete() } }.getOrNull() } ?: return "Couldn't read the photo."
        attach(made.first, made.second)
        return null
    }

    /** A fresh file for the camera to write to. */
    fun cameraFile(): File = File(dir, "camera-${UUID.randomUUID()}.jpg")

    private fun attach(file: File, mime: String) {
        val n = DraftTokens.next(input.text.toString(), _attachments.value.map { it.number })
        val text = input.text.toString()
        val (out, cursor) = DraftTokens.insert(text, input.selection.end, n)
        _attachments.value = _attachments.value + Attachment(n, file.path, mime)
        saveAttachments()
        input.edit {
            replace(0, length, out)
            selection = TextRange(cursor)
        }
    }

    fun remove(n: Int) {
        val out = DraftTokens.remove(input.text.toString(), n)
        input.edit {
            replace(0, length, out)
            placeCursorAtEnd()
        }
        prune(out)
    }

    /** Drops pictures whose token was deleted from the text. */
    private fun prune(text: String) {
        val keep = DraftTokens.numbers(text).toSet()
        val (kept, gone) = _attachments.value.partition { it.number in keep }
        if (gone.isEmpty()) return
        gone.forEach { it.file.delete() }
        _attachments.value = kept
        saveAttachments()
    }

    /** Copies a picture into the draft folder as a type the tower takes (PNG, JPEG, WebP, GIF), not too big. */
    private fun store(bytes: ByteArray): Pair<File, String> {
        val (data, mime) = Images.normalize(bytes)
        val ext = when (mime) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> "jpg"
        }
        val f = File(dir, "${UUID.randomUUID()}.$ext")
        f.writeBytes(data)
        return f to mime
    }

    // Dictation

    /** Dictates into the draft: what you say appears live after what's there, to edit before sending. */
    fun toggleDictation() {
        if (dictation.active.value) return stopDictation()
        val t = input.text.toString()
        val base = if (t.isEmpty() || t.last().isWhitespace()) t else "$t "
        dictationBase = base
        dictation.start { said ->
            val b = dictationBase ?: return@start
            input.edit {
                replace(0, length, b + said)
                placeCursorAtEnd()
            }
        }
    }

    fun stopDictation() {
        dictationBase = null
        dictation.stop()
    }

    // Sending

    /** Sends the draft (text and pictures together) and clears it when the tower took it. */
    suspend fun send(): Result<Unit> {
        if (isEmpty || _sending.value) return Result.success(Unit)
        stopDictation()
        _sending.value = true
        try {
            // The tower's markers count files in order: number the tokens 1..n as they stand.
            val (text, renumber) = DraftTokens.renumber(input.text.toString().trim())
            val ordered = _attachments.value.mapNotNull { a -> renumber[a.number]?.let { it to a } }.sortedBy { it.first }.map { it.second }
            val result = if (ordered.isEmpty()) {
                session.act { it.ask(text) }.map { }
            } else {
                val parts = withContext(Dispatchers.IO) { ordered.map { ImagePart(it.file.name, it.file.readBytes(), it.mime) } }
                session.act { it.ask(text, parts) }.map { }
            }
            if (result.isSuccess) clear()
            return result
        } finally {
            _sending.value = false
        }
    }

    fun clear() {
        input.clearText()
        _attachments.value.forEach { it.file.delete() }
        _attachments.value = emptyList()
        saveAttachments()
    }

    private fun saveAttachments() {
        prefs.edit().putString("attachments", _attachments.value.joinToString("\n") { "${it.number}\t${it.mime}\t${it.path}" }).apply()
    }

    private fun loadAttachments(): List<Attachment> =
        (prefs.getString("attachments", "") ?: "").lines().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size != 3) return@mapNotNull null
            Attachment(p[0].toIntOrNull() ?: return@mapNotNull null, p[2], p[1]).takeIf { it.file.exists() }
        }

    companion object {
        /** The tower takes six pictures with one message. */
        const val MAX_IMAGES = 6
    }

    /** Pictures as the tower takes them. */
    object Images {
        private const val MAX_BYTES = 15 * 1024 * 1024
        private const val MAX_SIDE = 4096
        private const val SHRINK_TO = 3072

        /** PNG, JPEG, WebP or GIF by their first bytes, or null. */
        fun sniff(b: ByteArray): String? = when {
            b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() -> "image/png"
            b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "image/jpeg"
            b.size >= 12 && String(b, 0, 4, Charsets.US_ASCII) == "RIFF" && String(b, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
            b.size >= 6 && String(b, 0, 4, Charsets.US_ASCII) == "GIF8" -> "image/gif"
            else -> null
        }

        /** Keeps a picture as it is when the tower takes it; otherwise (HEIC, too big) a JPEG of at most 3072 px. */
        fun normalize(bytes: ByteArray): Pair<ByteArray, String> {
            val type = sniff(bytes)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val side = maxOf(bounds.outWidth, bounds.outHeight)
            if (type != null && bytes.size <= MAX_BYTES && side <= MAX_SIDE) return bytes to type
            require(side > 0) { "not a picture" }
            var sample = 1
            while (side / (sample * 2) >= SHRINK_TO) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: error("not a picture")
            val longer = maxOf(bmp.width, bmp.height)
            val scaled = if (longer > SHRINK_TO) {
                val s = SHRINK_TO.toFloat() / longer
                Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true)
            } else bmp
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)
            return out.toByteArray() to "image/jpeg"
        }
    }
}
