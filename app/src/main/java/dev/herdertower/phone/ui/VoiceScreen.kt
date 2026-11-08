package dev.herdertower.phone.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.herdertower.phone.data.Link
import dev.herdertower.phone.data.TowerSession
import dev.herdertower.phone.orb.TowerOrb
import dev.herdertower.phone.voice.VoiceController
import dev.herdertower.phone.voice.VoiceController.Phase

@Composable
fun VoiceScreen(session: TowerSession, voice: VoiceController, startListening: Boolean, onClose: () -> Unit, onChat: () -> Unit) {
    val context = LocalContext.current
    val perms = rememberPermissions()
    val state by session.state.collectAsStateWithLifecycle()
    val link by session.link.collectAsStateWithLifecycle()
    val jobs by session.jobs.collectAsStateWithLifecycle()
    val voiceOn by session.voiceOn.collectAsStateWithLifecycle()
    val phase by voice.phase.collectAsStateWithLifecycle()
    val level by voice.level.collectAsStateWithLifecycle()
    val heard by voice.heard.collectAsStateWithLifecycle()
    val error by voice.error.collectAsStateWithLifecycle()
    val playing by session.player.nowPlaying.collectAsStateWithLifecycle()
    val paused by session.player.paused.collectAsStateWithLifecycle()
    val reduce = LocalReduceMotion.current

    LaunchedEffect(Unit) {
        if (startListening && voice.phase.value == Phase.Idle && perms.mic) voice.toggle()
    }

    val connected = link is Link.Live
    val s = state
    val blocked = HomeModel.blocked(s?.agents.orEmpty(), jobs).size
    val recording = phase is Phase.Recording
    val orbPhase = HomeModel.orbPhase(connected, s?.phase, recording, phase == Phase.Sending, playing)
    val needsYou = HomeModel.needsYou(connected, s?.phase, blocked)

    val statusLine = when {
        recording -> if ((phase as Phase.Recording).handsFree) "listening… pause or tap send" else "listening… let go to send"
        phase == Phase.Sending -> "understanding…"
        playing != null && paused -> "holding: someone is talking at the Mac"
        playing != null -> "speaking…"
        !connected -> "not connected"
        s?.phase == "thinking" -> "thinking…"
        needsYou -> "${plural(blocked, "session")} ${if (blocked == 1) "needs" else "need"} you"
        !voiceOn -> "replies play on the Mac"
        else -> "ready"
    }

    // The words of the clip playing light up as it plays.
    var progress by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(playing, reduce) {
        val clip = playing
        if (clip == null || reduce || clip.ms <= 0) {
            progress = 1f
            return@LaunchedEffect
        }
        val start = withFrameMillis { it }
        while (true) {
            val now = withFrameMillis { it }
            progress = ((now - start).toFloat() / clip.ms).coerceIn(0f, 1f)
            if (progress >= 1f) break
        }
    }
    val towerText = playing?.text?.takeIf { it.isNotBlank() }
    val transcript: Pair<String, Float> = when {
        recording -> "I'm listening." to 0f
        phase == Phase.Sending -> (heard?.text ?: "…") to 0f
        towerText != null -> towerText to progress
        heard != null && (s?.saidMs ?: 0) < (heard?.atMs ?: 0) -> heard!!.text.ifBlank { "…" } to 1f
        !s?.said.isNullOrBlank() -> s!!.said to 1f
        else -> "Hold the button and talk, or tap it once." to 0f
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().padding(top = 12.dp, start = 16.dp, end = 16.dp)) {
            ClosePill(modifier = Modifier.align(Alignment.Center)) {
                if (recording) voice.cancel()
                onClose()
            }
            RoundIcon(
                if (voiceOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                if (voiceOn) "Phone is the voice: on" else "Phone is the voice: off",
                Modifier.align(Alignment.CenterEnd), size = 40.dp,
            ) { setVoice(!voiceOn, session, perms, context) }
            RoundIcon(Icons.Filled.Forum, "Conversation", Modifier.align(Alignment.CenterStart), size = 40.dp, onClick = onChat)
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            TowerOrb(
                orbPhase,
                Modifier.fillMaxHeight(0.78f).aspectRatio(1f, matchHeightConstraintsFirst = true),
                needsYou = needsYou,
                renderScale = 0.65f,
                description = "The tower's orb: $statusLine",
            )
        }
        Text(statusLine, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium, color = tc.faint)
        Spacer(Modifier.height(18.dp))
        Transcript(lastWords(transcript.first), transcript.second, Modifier.padding(horizontal = 28.dp).heightIn(min = 108.dp, max = 220.dp))
        error?.let {
            Text(it, Modifier.padding(horizontal = 28.dp, vertical = 6.dp), style = MaterialTheme.typography.bodyMedium, color = tc.blocked, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            SmallRound(Icons.Filled.Pause, "Hush: stop the tower talking", enabled = connected) { session.hush() }
            Spacer(Modifier.weight(1f))
            TalkButton(voice, perms, recording, level, enabled = connected || recording)
            Spacer(Modifier.weight(1f))
            SmallRound(Icons.Filled.ArrowUpward, "Send what you said", enabled = recording) { voice.toggle() }
        }
    }
}

/** The end of a long text, from a sentence start, so the transcript stays a few lines. */
fun lastWords(text: String, max: Int = 160): String {
    val t = text.trim()
    if (t.length <= max) return t
    val tail = t.takeLast(max)
    val cut = Regex("[.!?]\\s+").findAll(tail).map { it.range.last + 1 }.firstOrNull { it < tail.length - 20 }
    return "…" + (if (cut != null) tail.substring(cut) else tail.substringAfter(' ')).trim()
}

/** The transcript, large and centred; with [progress] < 1 the words not yet spoken stay dim. */
@Composable
private fun Transcript(text: String, progress: Float, modifier: Modifier = Modifier) {
    val words = text.split(" ")
    val lit = (words.size * progress).toInt()
    val bright = tc.text
    val dim = tc.faint
    Text(
        buildAnnotatedString {
            words.forEachIndexed { i, w ->
                withStyle(SpanStyle(color = if (i < lit || progress >= 1f) bright else dim)) { append(w) }
                if (i < words.lastIndex) append(" ")
            }
        },
        modifier.semantics { contentDescription = text },
        style = if (text.length > 90) TranscriptStyle.copy(fontSize = 22.sp, lineHeight = 29.sp) else TranscriptStyle,
        textAlign = TextAlign.Center,
        maxLines = 6,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
    )
}

/** The big talk button: hold to talk, or tap once and talk until you pause. */
@Composable
private fun TalkButton(voice: VoiceController, perms: Permissions, recording: Boolean, level: Float, enabled: Boolean) {
    val ring by animateFloatAsState(if (recording) 1f + level * 0.35f else 1f, tween(120), label = "ring")
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(104.dp)) {
        Box(
            Modifier.size(96.dp).scale(ring).clip(CircleShape)
                .background(if (tc.dark) Color.Black.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.55f))
                .border(1.dp, tc.edge, CircleShape),
        )
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(if (enabled) tc.accentBrush else androidx.compose.ui.graphics.SolidColor(tc.idle))
                .semantics {
                    role = Role.Button
                    contentDescription = if (recording) "Talking. Release or tap to send." else "Talk to the tower. Hold to talk, or tap once."
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(onPress = {
                        if (!perms.mic) {
                            perms.withMic {}
                            return@detectTapGestures
                        }
                        voice.press()
                        tryAwaitRelease()
                        voice.release()
                    })
                },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Mic, null, Modifier.size(30.dp), tint = tc.onAccent) }
    }
}

@Composable
private fun SmallRound(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .border(1.dp, tc.edge, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(22.dp), tint = if (enabled) tc.text else tc.faint) }
}

/** Mic when there's nothing typed, send when there is: one stable target either way. */
@Composable
fun MicOrSend(hasText: Boolean, sending: Boolean, enabled: Boolean, size: Dp, onMic: () -> Unit, onSend: () -> Unit) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (enabled) tc.accentBrush else androidx.compose.ui.graphics.SolidColor(tc.idle))
            .clickable(enabled = enabled && !sending, role = Role.Button, onClick = { if (hasText) onSend() else onMic() })
            .semantics { contentDescription = if (hasText) "Send" else "Talk to the tower" },
        contentAlignment = Alignment.Center,
    ) {
        when {
            sending -> CircularProgressIndicator(Modifier.size(20.dp), color = tc.onAccent, strokeWidth = 2.dp)
            hasText -> Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(22.dp), tint = tc.onAccent)
            else -> Icon(Icons.Filled.GraphicEq, null, Modifier.size(24.dp), tint = tc.onAccent)
        }
    }
}
