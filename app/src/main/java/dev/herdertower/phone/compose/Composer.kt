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
import dev.herdertower.phone.api.FilePart
import dev.herdertower.phone.data.TowerSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** A file in the draft: its token number, kind, the name it had, and the copy kept with the draft. */
data class Attachment(val number: Int, val path: String, val mime: String, val kind: Kind = Kind.Image, val name: String = "") {
    val file: File get() = File(path)
    val token: String get() = DraftTokens.token(number, kind, name)
}

/**
 * The one draft the app has: the text input Home and Chat both show, and
 * the pictures attached to it. Saved on every change, so it survives
 * rotation, switching apps and the process being killed; going from typing
 * to talking and back never loses it.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class Composer(context: Context, private val session: TowerSession) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("draft", Context.MODE_PRIVATE)
    private val dir = File(app.filesDir, "draft").apply { mkdirs() }

    val input = TextFieldState(prefs.getString("text", "") ?: "")

    private val _attachments = MutableStateFlow(loadAttachments())
    val attachments: StateFlow<List<Attachment>> = _attachments.asStateFlow()

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    /** Dictation: the tower's Whisper while connected, the phone's recognizer otherwise. */
    private val towerDictation = TowerDictation(session)
    private val phoneDictation = Dictation(app)
    private val _dictating = MutableStateFlow<DictationEngine>(towerDictation)
    /** The engine dictating now (or last). */
    val dictation: StateFlow<DictationEngine> = _dictating.asStateFlow()
    /** Where the text stood when dictation began: what it says goes after it. */
    private var dictationBase: String? = null

    init {
        session.scope.launch {
            // Done dictating: what's in the box is the user's to edit.
            _dictating.flatMapLatest { it.active }.collect { if (!it) dictationBase = null }
        }
        session.scope.launch {
            snapshotFlow { input.text.toString() }.collect { t ->
                prefs.edit().putString("text", t).apply()
                prune(t)
                // You typed over the dictation: stop following it.
                val base = dictationBase
                if (base != null && !t.startsWith(base)) {
                    dictationBase = null
                    stopDictation()
                }
            }
        }
    }

    val isEmpty: Boolean get() = input.text.isBlank() && _attachments.value.isEmpty()

    // Pictures

    /** Adds files (photo picker, file picker, camera, paste); returns a message when some couldn't be. */
    suspend fun add(uris: List<Uri>): String? {
        val room = MAX_FILES - _attachments.value.size
        if (room <= 0) return "Six files at most in one message."
        var failed = 0
        var tooBig = 0
        for (uri in uris.take(room)) {
            val made = withContext(Dispatchers.IO) { runCatching { storeUri(uri) } }
            made.onSuccess { attach(it) }.onFailure { if (it is TooBig) tooBig++ else failed++ }
        }
        return when {
            uris.size > room -> "Six files at most in one message."
            tooBig > 0 -> "Too large: videos and files can be 50 MB, pictures 15 MB."
            failed > 0 -> "Couldn't read ${if (failed == 1) "a file" else "$failed files"}."
            else -> null
        }
    }

    private class TooBig : Exception()

    /** Copies what [uri] points at into the draft folder: a picture as one the tower takes, anything else as it is. */
    private fun storeUri(uri: Uri): Attachment {
        val cr = app.contentResolver
        var name = ""
        var size = -1L
        cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0).orEmpty()
                size = if (c.isNull(1)) -1 else c.getLong(1)
            }
        }
        val type = cr.getType(uri).orEmpty()
        val kind = Files.kind(type, name)
        if (kind != Kind.Image) {
            if (size > Files.MAX_OTHER) throw TooBig()
            val f = File(dir, "${UUID.randomUUID()}-${Files.safeName(name.ifBlank { "file" })}")
            cr.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } } ?: error("unreadable")
            if (f.length() > Files.MAX_OTHER) {
                f.delete()
                throw TooBig()
            }
            return Attachment(0, f.path, type.ifBlank { "application/octet-stream" }, kind, name.ifBlank { f.name })
        }
        val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: error("unreadable")
        val (file, mime) = store(bytes)
        return Attachment(0, file.path, mime, Kind.Image, name.ifBlank { file.name })
    }

    /** A file the camera wrote into the draft folder. */
    suspend fun addCameraFile(file: File): String? {
        if (_attachments.value.size >= MAX_FILES) return "Six files at most in one message."
        val made = withContext(Dispatchers.IO) { runCatching { store(file.readBytes()).also { file.delete() } }.getOrNull() } ?: return "Couldn't read the photo."
        attach(Attachment(0, made.first.path, made.second, Kind.Image, "photo.jpg"))
        return null
    }

    /** A fresh file for the camera to write to. */
    fun cameraFile(): File = File(dir, "camera-${UUID.randomUUID()}.jpg")

    private fun attach(a: Attachment) {
        val n = DraftTokens.next(input.text.toString(), _attachments.value.map { it.number })
        val text = input.text.toString()
        val (out, cursor) = DraftTokens.insert(text, input.selection.end, n, a.kind, a.name)
        _attachments.value = _attachments.value + a.copy(number = n)
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
        if (_dictating.value.active.value) return stopDictation()
        val t = input.text.toString()
        val base = if (t.isEmpty() || t.last().isWhitespace()) t else "$t "
        dictationBase = base
        val engine = if (session.link.value is dev.herdertower.phone.data.Link.Live) towerDictation else phoneDictation
        _dictating.value = engine
        engine.start { said ->
            val b = dictationBase ?: return@start
            input.edit {
                replace(0, length, b + said)
                placeCursorAtEnd()
            }
        }
    }

    /** Stops listening; the last words still land (the base stays until the engine is done). */
    fun stopDictation() {
        _dictating.value.stop()
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
                // Sent from disk, not memory: videos and files can be 50 MB.
                val parts = ordered.map { FilePart(it.name.ifBlank { it.file.name }, it.mime, file = it.file) }
                session.act { it.ask(text, parts) }.map { }
            }
            if (result.isSuccess) {
                clear()
                session.linger()
            }
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
        prefs.edit().putString("attachments", _attachments.value.joinToString("\n") { "${it.number}\t${it.mime}\t${it.path}\t${it.kind}\t${it.name}" }).apply()
    }

    private fun loadAttachments(): List<Attachment> =
        (prefs.getString("attachments", "") ?: "").lines().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 3) return@mapNotNull null
            val kind = p.getOrNull(3)?.let { k -> Kind.entries.firstOrNull { it.name == k } } ?: Kind.Image
            Attachment(p[0].toIntOrNull() ?: return@mapNotNull null, p[2], p[1], kind, p.getOrNull(4).orEmpty()).takeIf { it.file.exists() }
        }

    companion object {
        /** The tower takes six files with one message. */
        const val MAX_FILES = 6
    }

    /** Files that aren't pictures: their kind and a name safe to keep on disk. */
    object Files {
        /** Videos and other files: 50 MB each (pictures: 15 MB). */
        const val MAX_OTHER = 50L * 1024 * 1024
        private val VIDEO_EXT = setOf("mov", "mp4", "m4v", "webm", "mkv", "avi")
        private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "webp", "gif", "heic", "heif")

        fun kind(mime: String, name: String): Kind {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when {
                mime.startsWith("image/") || ext in IMAGE_EXT -> Kind.Image
                mime.startsWith("video/") || ext in VIDEO_EXT -> Kind.Video
                else -> Kind.File
            }
        }

        fun safeName(name: String): String = name.replace(Regex("""[/\\:\u0000]"""), "_").take(120)
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
