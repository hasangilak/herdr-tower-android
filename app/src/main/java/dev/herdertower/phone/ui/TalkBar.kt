package dev.herdertower.phone.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.herdertower.phone.compose.Composer
import dev.herdertower.phone.data.Link
import dev.herdertower.phone.data.TowerSession
import dev.herdertower.phone.orb.TowerOrb
import dev.herdertower.phone.voice.TalkTarget
import dev.herdertower.phone.voice.VoiceController
import dev.herdertower.phone.voice.VoiceController.Phase
import kotlinx.coroutines.delay

/** What the talk button and the listening view say, in words (tests read these). */
object TalkModel {
    /** The button's label. */
    fun button(phase: Phase): String = when (phase) {
        is Phase.Recording -> if (phase.handsFree) "Tap to send" else "Let go to send"
        Phase.Sending -> "Sending…"
        Phase.Idle -> "Hold to talk"
    }

    /** The listening view's title and the line under it. */
    fun heading(phase: Phase, waiting: Boolean, target: TalkTarget): Pair<String, String> = when (phase) {
        is Phase.Recording -> "Listening…" to when {
            waiting -> "Tap the button when you're done"
            phase.handsFree -> "Pause, or tap the button to send"
            else -> "Let go to send"
        }
        Phase.Sending -> (if (target is TalkTarget.Into) "Writing it down…" else "Sending…") to to(target)
        Phase.Idle -> "Heard" to to(target)
    }

    /** Where a take goes: "To the main tower", "To the sandbox tower", "Into the goal". */
    fun to(target: TalkTarget): String = when (target) {
        TalkTarget.Main -> "To the main tower"
        is TalkTarget.Space -> "To the ${target.name} tower"
        is TalkTarget.Into -> "Into the goal"
    }

    /** The input's hint: "Tell the tower…", "Tell the sandbox tower…". */
    fun hint(space: String?): String = if (space == null) "Tell the tower…" else "Tell the $space tower…"
}

/**
 * The bar above the tabs, on every screen: a line to type to the tower (or,
 * on a space's screens, to that space's own) and the one talk button. Hold
 * it and talk, let go to send; or tap it once and talk until you pause.
 * With something typed, the button sends it.
 */
@Composable
fun TalkBar(
    session: TowerSession,
    voice: VoiceController,
    composer: Composer,
    target: TalkTarget,
    /** False where the screen has its own field (a new space's goal): the talk button only. */
    input: Boolean,
    onSent: () -> Unit,
) {
    val link by session.link.collectAsStateWithLifecycle()
    val phase by voice.phase.collectAsStateWithLifecycle()
    val atts by composer.attachments.collectAsStateWithLifecycle()
    val sending by composer.sending.collectAsStateWithLifecycle()
    val engine by composer.dictation.collectAsStateWithLifecycle()
    val dictating by engine.active.collectAsStateWithLifecycle()
    val space = (target as? TalkTarget.Space)?.name
    val focusManager = LocalFocusManager.current
    // Sent: the line folds back to its hint, the talk button to its full size.
    val actions = rememberComposerActions(composer, onTalk = {}, onSent = { focusManager.clearFocus(); onSent() }, space = space)
    val live = link is Link.Live
    var focused by remember { mutableStateOf(false) }
    // Back leaves the line (after the keyboard, which takes the first Back itself).
    BackHandler(enabled = focused) { focusManager.clearFocus() }
    val hasDraft = input && (composer.input.text.isNotBlank() || atts.isNotEmpty())
    val expanded = focused || hasDraft || dictating
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (input) {
            val shape = RoundedCornerShape(24.dp)
            val focus = remember { FocusRequester() }
            Column(
                Modifier.weight(1f).clip(shape)
                    .background(if (tc.dark) Color(0xFF120B08).copy(alpha = 0.78f) else Color.White.copy(alpha = 0.86f))
                    .border(1.dp, tc.edge, shape)
                    // The whole pill starts typing, not only the line of words in it.
                    .clickable(interactionSource = null, indication = null) { focus.requestFocus() },
            ) {
                Column(Modifier.padding(start = 14.dp, end = 10.dp, top = if (dictating || atts.isNotEmpty()) 8.dp else 0.dp)) {
                    DictationHint(composer)
                    AttachmentStrip(composer, Modifier.padding(bottom = 4.dp))
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    if (expanded) Box(Modifier.padding(start = 4.dp, bottom = 4.dp)) { AttachButton(actions, size = 40.dp) }
                    DraftField(
                        composer, TalkModel.hint(space), 1, actions.send, actions.add,
                        Modifier.weight(1f).padding(start = if (expanded) 8.dp else 16.dp, end = 8.dp, top = 14.dp, bottom = 14.dp).focusRequester(focus).onFocusChanged { focused = it.isFocused },
                    )
                    if (expanded) Box(Modifier.padding(end = 4.dp, bottom = 4.dp)) { DictateButton(composer, actions, size = 40.dp) }
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (hasDraft) SendButton(sending, enabled = live, onSend = actions.send)
        else HoldToTalk(voice, target, enabled = live || phase is Phase.Recording, compact = input && expanded)
    }
}

@Composable
private fun SendButton(sending: Boolean, enabled: Boolean, onSend: () -> Unit) {
    Box(
        Modifier.requiredSize(48.dp).clip(CircleShape).background(if (enabled) tc.decision else tc.idle)
            .clickable(enabled = enabled && !sending, role = Role.Button, onClick = onSend)
            .semantics { contentDescription = "Send" },
        contentAlignment = Alignment.Center,
    ) {
        if (sending) CircularProgressIndicator(Modifier.size(20.dp), color = tc.onDecision, strokeWidth = 2.dp)
        else Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(22.dp), tint = tc.onDecision)
    }
}

/**
 * The one talk button. Hold it to talk and let go to send; a short tap
 * starts a take that ends when you pause (or tap again). [compact]: only
 * the microphone, while the line beside it is being typed in.
 */
@Composable
fun HoldToTalk(voice: VoiceController, target: TalkTarget, enabled: Boolean, compact: Boolean = false) {
    val perms = rememberPermissions()
    val phase by voice.phase.collectAsStateWithLifecycle()
    val level by voice.level.collectAsStateWithLifecycle()
    val recording = phase is Phase.Recording
    val to by rememberUpdatedState(target)
    val swell by animateFloatAsState(if (recording) 1f + level * 0.08f else 1f, tween(120), label = "talk")
    val bg = when {
        !enabled -> tc.idle
        recording -> tc.live
        else -> tc.talk
    }
    val fg = when {
        !enabled -> Color.White
        recording -> tc.onLive
        else -> tc.onTalk
    }
    val label = TalkModel.button(phase)
    Row(
        Modifier
            .scale(swell)
            .heightIn(min = 48.dp).widthIn(min = 48.dp)
            .clip(CircleShape)
            .background(bg)
            .semantics {
                role = Role.Button
                contentDescription = if (recording) "Talking. Let go or tap to send." else "Talk to the tower. Hold to talk, or tap once."
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    if (!perms.mic) {
                        perms.withMic {}
                        return@detectTapGestures
                    }
                    voice.target = to
                    voice.press()
                    // Also when the gesture is cut short (the screen recreated, the button disabled):
                    // otherwise the hold would record until the cap and send it all.
                    try {
                        tryAwaitRelease()
                    } finally {
                        voice.release()
                    }
                })
            }
            .padding(horizontal = if (compact && !recording) 12.dp else 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (phase == Phase.Sending) CircularProgressIndicator(Modifier.size(18.dp), color = fg, strokeWidth = 2.dp)
        else Icon(Icons.Filled.Mic, null, Modifier.size(22.dp), tint = fg)
        if (!compact || recording) {
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = fg, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

/**
 * Over the screen while a take runs: "Listening…", the orb, and what the
 * Mac has written down so far, large. It stays a moment after the take with
 * what was heard, then gives the screen back.
 */
@Composable
fun ListeningOverlay(voice: VoiceController, orbPhase: String, modifier: Modifier = Modifier) {
    val phase by voice.phase.collectAsStateWithLifecycle()
    val hearing by voice.hearing.collectAsStateWithLifecycle()
    val heard by voice.heard.collectAsStateWithLifecycle()
    val pause by voice.pause.collectAsStateWithLifecycle()
    val waiting by voice.waiting.collectAsStateWithLifecycle()
    val reduce = LocalReduceMotion.current
    // What was heard stays up a moment after the take.
    var lingering by remember { mutableStateOf(false) }
    var seen by remember { mutableStateOf(heard?.atMs ?: 0L) }
    LaunchedEffect(heard?.atMs) {
        val h = heard ?: return@LaunchedEffect
        if (h.atMs <= seen || h.text.isBlank()) return@LaunchedEffect
        seen = h.atMs
        lingering = true
        delay(HEARD_STAYS_MS)
        lingering = false
    }
    val busy = phase != Phase.Idle
    val recording = phase is Phase.Recording
    BackHandler(enabled = recording) { voice.cancel() }
    AnimatedVisibility(
        visible = busy || lingering, modifier = modifier,
        enter = if (reduce) EnterTransition.None else fadeIn(tween(160)),
        exit = if (reduce) ExitTransition.None else fadeOut(tween(260)),
    ) {
        val target = if (busy) voice.target else heard?.target ?: voice.target
        val (title, sub) = TalkModel.heading(phase, waiting, target)
        val words = when {
            recording -> hearing
            phase == Phase.Sending -> hearing
            else -> heard?.text.orEmpty()
        }
        Column(
            Modifier.fillMaxSize()
                .background(Brush.verticalGradient(0f to tc.bgTop, 0.4f to tc.bgMid, 0.85f to tc.bgBottom))
                // Nothing under it is tapped while it shows.
                .pointerInput(Unit) { detectTapGestures {} }
                .statusBarsPadding().padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(14.dp))
            Text(title, Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.headlineSmall, color = tc.text, fontWeight = FontWeight.SemiBold)
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = tc.muted)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                TowerOrb(
                    orbPhase, Modifier.fillMaxHeight(0.72f).aspectRatio(1f, matchHeightConstraintsFirst = true),
                    renderScale = 0.6f, interactive = false, description = "The tower's orb: $title",
                )
            }
            Text(
                words.ifBlank { if (recording) "Say what the tower should do." else "…" },
                Modifier.fillMaxWidth().heightIn(min = 96.dp).padding(horizontal = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
                style = if (words.length > 90) TranscriptStyle.copy(fontSize = 20.sp, lineHeight = 27.sp) else TranscriptStyle.copy(fontSize = 24.sp, lineHeight = 31.sp),
                color = if (words.isBlank()) tc.faint else tc.text, maxLines = 6, overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                // About to send after a pause: show it, so a thought mid-sentence isn't cut off. Talking again resets it.
                if (recording && pause > 0f && !waiting) SendingSoon(pause) { voice.keepListening() }
                Spacer(Modifier.weight(1f))
                if (recording) TextButton(onClick = { voice.cancel() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel", color = tc.muted, textAlign = TextAlign.End) }
            }
        }
    }
}

/** "Sending" with a bar running out through the pause, and Wait (keep listening until a tap). */
@Composable
private fun SendingSoon(progress: Float, onWait: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(tc.card).border(1.dp, tc.edge, CircleShape).padding(start = 16.dp, end = 6.dp)
            .semantics { liveRegion = LiveRegionMode.Polite; contentDescription = "Sending soon. Keep talking, or wait." },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Sending", style = MaterialTheme.typography.labelLarge, color = tc.text)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.width(56.dp).height(4.dp).clip(CircleShape).background(tc.edge)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(1f - progress).clip(CircleShape).background(tc.live))
        }
        TextButton(onClick = onWait) { Text("Wait", color = tc.text) }
    }
}

/** Mic when there's nothing typed, send when there is: one stable target either way (the conversation's own bar). */
@Composable
fun MicOrSend(hasText: Boolean, sending: Boolean, enabled: Boolean, size: Dp, onMic: () -> Unit, onSend: () -> Unit) {
    Box(
        Modifier
            .requiredSize(size)
            .clip(CircleShape)
            .background(if (enabled) tc.decision else tc.idle)
            .clickable(enabled = enabled && !sending, role = Role.Button, onClick = { if (hasText) onSend() else onMic() })
            .semantics { contentDescription = if (hasText) "Send" else "Talk to the tower" },
        contentAlignment = Alignment.Center,
    ) {
        when {
            sending -> CircularProgressIndicator(Modifier.size(20.dp), color = tc.onDecision, strokeWidth = 2.dp)
            hasText -> Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(22.dp), tint = tc.onDecision)
            else -> Icon(Icons.Filled.Mic, null, Modifier.size(24.dp), tint = tc.onDecision)
        }
    }
}

/** How long what was heard stays up after a take. */
private const val HEARD_STAYS_MS = 1400L
