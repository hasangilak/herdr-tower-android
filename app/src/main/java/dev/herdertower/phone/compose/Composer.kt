package dev.herdertower.phone.compose

import android.content.Context
import androidx.core.content.edit
import androidx.core.graphics.scale
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

    private val _pending = MutableStateFlow<List<dev.herdertower.phone.ui.ChatModel.Pending>>(emptyList())
    /** What was sent from here and not yet back in the thread (shown as "sending…", never twice). */
    val pending: StateFlow<List<dev.herdertower.phone.ui.ChatModel.Pending>> = _pending.asStateFlow()

    fun addPending(text: String, files: Int = 0, space: String? = null) {
        val now = System.currentTimeMillis()
        _pending.value = _pending.value.filter { now - it.atMs < 90_000 } + dev.herdertower.phone.ui.ChatModel.Pending(text, now, files, space)
    }
    /** Where dictation writes in the box, and whether it still may. */
    private val follow = DictationFollow()

    /** Runs before dictation starts (a voice take ends: one microphone at a time). */
    var beforeDictation: () -> Unit = {}

    init {
        session.scope.launch {
            // Done dictating: what's in the box is the user's to edit.
            _dictating.flatMapLatest { it.active }.collect { if (it) follow.live() else follow.idle() }
        }
        session.scope.launch {
            snapshotFlow { input.text.toString() }.collect { t ->
                prefs.edit { putString("text", t) }
                prune(t)
                // You typed or attached something while dictating: stop, and leave the box as it is.
                if (follow.edited(t)) stopDictation()
            }
        }
        session.scope.launch(Dispatchers.IO) { sweep() }
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
            // A copy once begun is attached, even if the screen that asked has gone: no file is left behind.
            val made = withContext(NonCancellable) { withContext(Dispatchers.IO) { runCatching { storeUri(uri) } }.onSuccess { attach(it) } }
            made.onFailure { if (it is TooBig) tooBig++ else failed++ }
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
            try {
                cr.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } } ?: error("unreadable")
            } catch (e: Exception) {
                f.delete()
                throw e
            }
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
        if (_attachments.value.size >= MAX_FILES) {
            file.delete()
            return "Six files at most in one message."
        }
        return withContext(NonCancellable) {
            val made = withContext(Dispatchers.IO) { runCatching { store(file.readBytes()) }.also { file.delete() }.getOrNull() }
                ?: return@withContext "Couldn't read the photo."
            attach(Attachment(0, made.first.path, made.second, Kind.Image, "photo.jpg"))
            null
        }
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

    /** Deletes files in the draft folder that no attachment holds (a copy cut short, a photo never taken), once they're an hour old. */
    private fun sweep() {
        val held = _attachments.value.map { it.path }.toSet()
        val old = System.currentTimeMillis() - 60 * 60 * 1000L
        dir.listFiles()?.forEach { if (it.path !in held && it.lastModified() < old) it.delete() }
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
        beforeDictation()
        val engine = if (session.link.value is dev.herdertower.phone.data.Link.Live) towerDictation else phoneDictation
        // The engine first: switching shows the new one as not running, which must not end the dictation about to start.
        _dictating.value = engine
        val gen = follow.begin(input.text.toString())
        engine.start { said ->
            // Typed over since the last words, or sent or cleared: never overwrite the box.
            if (follow.edited(input.text.toString())) return@start stopDictation()
            val out = follow.said(gen, said) ?: return@start
            input.edit {
                replace(0, length, out)
                placeCursorAtEnd()
            }
        }
        if (!engine.active.value) follow.reset() // it couldn't start
    }

    /** Stops listening; the last words still land (dictation writes until the engine is done). */
    fun stopDictation() {
        _dictating.value.stop()
    }

    // Sending

    /**
     * Sends the draft (text and pictures together) and takes what went out of
     * it when the tower took it. It runs in the app's scope, so leaving the
     * screen doesn't cancel it. True when something was sent.
     */
    suspend fun send(space: String? = null): Result<Boolean> {
        if (_sending.value) return Result.success(false)
        _sending.value = true
        return session.scope.async {
            try {
                sendNow(space)
            } finally {
                _sending.value = false
            }
        }.await()
    }

    /** [space]: the draft goes to that space's tower, not the main one. */
    private suspend fun sendNow(space: String?): Result<Boolean> {
        // Still dictating: the box shows words a second or two behind the voice. Stop, and wait for the
        // final transcript of the whole take, so what's sent is everything said (not cut at the last partial).
        if (_dictating.value.active.value) {
            stopDictation()
            withTimeoutOrNull(FINAL_WORDS_MS) { _dictating.value.active.first { !it } }
        }
        if (isEmpty) return Result.success(false)
        val raw = input.text.toString()
        // The tower's markers count files in order: number the tokens 1..n as they stand.
        val (text, renumber) = DraftTokens.renumber(raw.trim())
        val ordered = _attachments.value.mapNotNull { a -> renumber[a.number]?.let { it to a } }.sortedBy { it.first }.map { it.second }
        // Sent from disk, not memory: videos and files can be 50 MB.
        val parts = ordered.map { FilePart(it.name.ifBlank { it.file.name }, it.mime, file = it.file) }
        val result = session.ask(text, parts, space)
        return result.map {
            addPending(text, ordered.size, space)
            sent(raw, ordered)
            session.linger()
            true
        }
    }

    /** Takes what was sent out of the draft; what was typed or attached meanwhile stays. */
    private fun sent(raw: String, files: List<Attachment>) {
        follow.reset()
        val rest = DraftTokens.afterSend(raw, input.text.toString(), files.map { it.number })
        input.edit {
            replace(0, length, rest)
            placeCursorAtEnd()
        }
        files.forEach { it.file.delete() }
        val gone = files.map { it.number }.toSet()
        _attachments.value = _attachments.value.filterNot { it.number in gone }
        saveAttachments()
    }

    fun clear() {
        // Whatever dictation was running is over: a transcript that lands later goes nowhere.
        follow.reset()
        input.clearText()
        _attachments.value.forEach { it.file.delete() }
        _attachments.value = emptyList()
        saveAttachments()
    }

    private fun saveAttachments() {
        prefs.edit { putString("attachments", _attachments.value.joinToString("\n") { "${it.number}\t${it.mime}\t${it.path}\t${it.kind}\t${it.name}" }) }
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
        /** How long Send waits for dictation's final transcript at most. */
        const val FINAL_WORDS_MS = 8000L
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
                bmp.scale((bmp.width * s).toInt(), (bmp.height * s).toInt())
            } else bmp
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)
            return out.toByteArray() to "image/jpeg"
        }
    }
}

/**
 * Where dictation writes in the draft: after the text that was there when
 * it began, and only while the box holds what it last wrote. Typing or
 * attaching meanwhile, sending or clearing ends that, and the words of an
 * older dictation (a late transcript) go nowhere.
 */
class DictationFollow {
    private var gen = 0
    private var base: String? = null
    /** What dictation last put in the box (at first, what was there). */
    private var written: String? = null
    /** The engine was seen running since [begin]. */
    private var live = false

    /** Dictation begins on [text]; returns its number for [said]. */
    fun begin(text: String): Int {
        gen++
        base = if (text.isEmpty() || text.last().isWhitespace()) text else "$text "
        written = text
        live = false
        return gen
    }

    /** The box's text when dictation [g] has heard [said] so far, or null when it no longer writes there. */
    fun said(g: Int, said: String): String? {
        if (g != gen) return null
        val b = base ?: return null
        return (b + said).also { written = it }
    }

    /** The engine is running. */
    fun live() {
        if (base != null) live = true
    }

    /** The engine isn't running: once it ran, what's in the box is the user's. (A new engine shows "not running" before it starts: that changes nothing.) */
    fun idle() {
        if (live) drop()
    }

    /** The box now says [text]; true when that wasn't dictation (the user typed or attached), which ends it. */
    fun edited(text: String): Boolean {
        val w = written ?: return false
        if (base == null || text == w) return false
        drop()
        return true
    }

    /** Sent or cleared: what an older dictation says later goes nowhere. */
    fun reset() {
        gen++
        drop()
    }

    private fun drop() {
        base = null
        written = null
        live = false
    }
}
