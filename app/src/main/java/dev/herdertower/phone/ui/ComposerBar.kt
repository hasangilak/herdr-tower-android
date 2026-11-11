package dev.herdertower.phone.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardVoice
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.herdertower.phone.compose.Attachment
import dev.herdertower.phone.compose.Composer
import dev.herdertower.phone.compose.Kind
import dev.herdertower.phone.data.Link
import dev.herdertower.phone.data.TowerSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The draft's text field: several lines, Send on the keyboard sends, on a
 * hardware keyboard Enter sends and Shift+Enter starts a new line; a picture
 * pasted or inserted from the keyboard is attached.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DraftField(composer: Composer, placeholder: String, minLines: Int, onSend: () -> Unit, onPictures: (List<Uri>) -> Unit, modifier: Modifier = Modifier) {
    val empty = composer.input.text.isEmpty()
    // While it sends, the text stays as it went: what's sent is taken out of the box afterwards.
    val sending by composer.sending.collectAsStateWithLifecycle()
    BasicTextField(
        state = composer.input,
        readOnly = sending,
        modifier = modifier
            .semantics { contentDescription = placeholder }
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown || (ev.key != Key.Enter && ev.key != Key.NumPadEnter)) return@onPreviewKeyEvent false
                if (ev.isShiftPressed) {
                    if (!sending) composer.input.edit {
                        val at = selection.min
                        replace(selection.min, selection.max, "\n")
                        selection = TextRange(at + 1)
                    }
                } else {
                    onSend()
                }
                true
            }
            .contentReceiver(object : ReceiveContentListener {
                override fun onReceive(transferableContent: TransferableContent): TransferableContent? {
                    if (!transferableContent.hasMediaType(MediaType.Image)) return transferableContent
                    val uris = mutableListOf<Uri>()
                    val rest = transferableContent.consume { item -> item.uri?.let { uris += it; true } ?: false }
                    if (uris.isNotEmpty()) onPictures(uris)
                    return rest
                }
            }),
        lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = minLines, maxHeightInLines = 8),
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = tc.text),
        cursorBrush = SolidColor(tc.accentHi),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        onKeyboardAction = { onSend() },
        decorator = { inner ->
            Box {
                if (empty) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = tc.faint)
                inner()
            }
        },
    )
}

/** What the composer needs from its screen. */
class ComposerActions(
    val send: () -> Unit,
    val talk: () -> Unit,
    /** Opens the photo picker (pictures and videos). */
    val pictures: () -> Unit,
    /** Opens the file picker (anything: PDF, video, text, …). */
    val files: () -> Unit,
    val camera: () -> Unit,
    val dictate: () -> Unit,
    /** Pictures pasted or inserted from the keyboard. */
    val add: (List<Uri>) -> Unit,
)

/** Wires the composer to the tower, the photo picker, the camera and dictation. */
@Composable
fun rememberComposerActions(composer: Composer, onTalk: () -> Unit, onSent: () -> Unit = {}, space: String? = null): ComposerActions {
    // Read when Send is tapped: the screen in front may have changed where the draft goes.
    val to by androidx.compose.runtime.rememberUpdatedState(space)
    val sent by androidx.compose.runtime.rememberUpdatedState(onSent)
    val talk by androidx.compose.runtime.rememberUpdatedState(onTalk)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    val perms = rememberPermissions()
    // Saved: the activity may be recreated while the camera app is in front.
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    val add: (List<Uri>) -> Unit = { uris -> scope.launch { composer.add(uris)?.let(snack) } }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(Composer.MAX_FILES)) { if (it.isNotEmpty()) add(it) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) add(it) }
    val shoot = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = cameraPath?.let(::File)
        cameraPath = null
        if (ok && f != null) scope.launch { composer.addCameraFile(f)?.let(snack) } else f?.delete()
    }
    val engine by composer.dictation.collectAsStateWithLifecycle()
    val error by engine.error.collectAsStateWithLifecycle()
    LaunchedEffect(error) {
        error?.let {
            snack(it)
            engine.clearError() // shown once, not again on the next screen
        }
    }
    return remember(composer) {
        ComposerActions(
            send = {
                scope.launch {
                    composer.send(to)
                        .onSuccess { if (it) sent() }
                        .onFailure { snack("Couldn't send: ${TowerSession.describe(it)}") }
                }
            },
            talk = { perms.withMic { talk() } },
            pictures = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
            files = { pickFiles.launch(arrayOf("*/*")) },
            camera = {
                perms.withCamera {
                    val f = composer.cameraFile()
                    cameraPath = f.path
                    shoot.launch(FileProvider.getUriForFile(context, context.packageName + ".files", f))
                }
            },
            dictate = { perms.withMic { composer.toggleDictation() } },
            add = add,
        )
    }
}

/** The attached pictures, each with its token number and a remove button. */
@Composable
fun AttachmentStrip(composer: Composer, modifier: Modifier = Modifier) {
    val atts by composer.attachments.collectAsStateWithLifecycle()
    AnimatedVisibility(atts.isNotEmpty(), modifier) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(atts, key = { it.path }) { a -> AttachmentThumb(a) { composer.remove(a.number) } }
        }
    }
}

@Composable
private fun AttachmentThumb(a: Attachment, onRemove: () -> Unit) {
    val img by produceState<ImageBitmap?>(null, a.path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                when (a.kind) {
                    Kind.Image -> dev.herdertower.phone.data.Images.decode(a.file.readBytes(), 240)?.asImageBitmap()
                    Kind.Video -> android.media.MediaMetadataRetriever().run {
                        try {
                            setDataSource(a.path)
                            getScaledFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 240, 240)?.asImageBitmap()
                        } finally {
                            release()
                        }
                    }
                    Kind.File -> null
                }
            }.getOrNull()
        }
    }
    val label = when (a.kind) {
        Kind.Image -> "Picture"
        Kind.Video -> "Video"
        Kind.File -> "File ${a.name}"
    }
    Box(Modifier.size(width = if (a.kind == Kind.File) 132.dp else 68.dp, height = 68.dp)) {
        Box(
            Modifier.padding(top = 6.dp, end = 6.dp).fillMaxSize().clip(RoundedCornerShape(14.dp)).background(tc.card).border(1.dp, tc.edge, RoundedCornerShape(14.dp))
                .semantics { contentDescription = "$label #${a.number}" },
        ) {
            img?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            if (a.kind == Kind.Video) Icon(Icons.Filled.PlayCircle, null, Modifier.align(Alignment.Center).size(24.dp), tint = androidx.compose.ui.graphics.Color.White)
            if (a.kind == Kind.File) {
                Row(Modifier.fillMaxSize().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Description, null, Modifier.size(22.dp), tint = tc.muted)
                    Spacer(Modifier.width(6.dp))
                    Text(a.name, style = MaterialTheme.typography.labelSmall, color = tc.text, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
            if (a.kind != Kind.File) Text(
                "#${a.number}",
                Modifier.align(Alignment.BottomStart).padding(4.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f)).padding(horizontal = 5.dp),
                style = MaterialTheme.typography.labelSmall, color = androidx.compose.ui.graphics.Color.White,
            )
        }
        Box(
            Modifier.align(Alignment.TopEnd).size(24.dp).clip(CircleShape).background(tc.text).clickable(role = Role.Button, onClickLabel = "Remove $label #${a.number}", onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Close, "Remove $label #${a.number}", Modifier.size(14.dp), tint = tc.bgBottom) }
    }
}

/** ＋: attach from the photos or the camera. */
@Composable
fun AttachButton(actions: ComposerActions, size: Dp = 40.dp) {
    var open by remember { mutableStateOf(false) }
    Box {
        RoundIcon(Icons.Filled.Add, "Attach files", size = size) { open = true }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text("Photos and videos") }, leadingIcon = { Icon(Icons.Filled.PhotoLibrary, null) }, onClick = { open = false; actions.pictures() })
            DropdownMenuItem(text = { Text("Files") }, leadingIcon = { Icon(Icons.Filled.AttachFile, null) }, onClick = { open = false; actions.files() })
            DropdownMenuItem(text = { Text("Camera") }, leadingIcon = { Icon(Icons.Filled.PhotoCamera, null) }, onClick = { open = false; actions.camera() })
        }
    }
}

/** Dictate into the draft (the words stay to edit); shows it listens. */
@Composable
fun DictateButton(composer: Composer, actions: ComposerActions, size: Dp = 40.dp) {
    val engine by composer.dictation.collectAsStateWithLifecycle()
    val active by engine.active.collectAsStateWithLifecycle()
    val level by engine.level.collectAsStateWithLifecycle()
    Box(
        Modifier
            .size(size)
            .scale(if (active) 1f + level * 0.18f else 1f)
            .clip(CircleShape)
            .then(if (active) Modifier.background(tc.blocked) else Modifier.background(tc.card).border(1.dp, tc.edge, CircleShape))
            .clickable(role = Role.Button, onClickLabel = if (active) "Stop dictating" else "Dictate into the message", onClick = actions.dictate)
            .semantics { contentDescription = if (active) "Stop dictating" else "Dictate into the message" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (active) Icons.Filled.Stop else Icons.Filled.KeyboardVoice, null, Modifier.size(20.dp), tint = if (active) androidx.compose.ui.graphics.Color.White else tc.text)
    }
}

/** "Listening…" while dictating. */
@Composable
fun DictationHint(composer: Composer) {
    val engine by composer.dictation.collectAsStateWithLifecycle()
    val active by engine.active.collectAsStateWithLifecycle()
    AnimatedVisibility(active) {
        Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Dot(tc.blocked, size = 7.dp)
            Spacer(Modifier.width(8.dp))
            Text("Listening… the words go into your message to edit. Tap stop when done.", style = MaterialTheme.typography.labelMedium, color = tc.muted)
        }
    }
}

/** Send when there's a draft, Talk (the tower's voice) when there isn't. */
@Composable
fun SendOrTalk(composer: Composer, session: TowerSession, actions: ComposerActions, size: Dp) {
    val atts by composer.attachments.collectAsStateWithLifecycle()
    val sending by composer.sending.collectAsStateWithLifecycle()
    val link by session.link.collectAsStateWithLifecycle()
    val hasDraft = composer.input.text.isNotBlank() || atts.isNotEmpty()
    MicOrSend(hasDraft, sending, enabled = link is Link.Live, size = size, onMic = actions.talk, onSend = actions.send)
}
