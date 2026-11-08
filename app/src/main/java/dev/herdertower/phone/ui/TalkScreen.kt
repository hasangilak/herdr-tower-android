package dev.herdertower.phone.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.herdertower.phone.data.Link
import dev.herdertower.phone.data.TowerSession
import dev.herdertower.phone.voice.VoiceController
import dev.herdertower.phone.voice.VoiceController.Phase
import dev.herdertower.phone.voice.VoiceService

@Composable
fun TalkScreen(session: TowerSession, voice: VoiceController) {
    val context = LocalContext.current
    val state by session.state.collectAsStateWithLifecycle()
    val link by session.link.collectAsStateWithLifecycle()
    val voiceOn by session.voiceOn.collectAsStateWithLifecycle()
    val phase by voice.phase.collectAsStateWithLifecycle()
    val level by voice.level.collectAsStateWithLifecycle()
    val heard by voice.heard.collectAsStateWithLifecycle()
    val error by voice.error.collectAsStateWithLifecycle()
    val playing by session.player.nowPlaying.collectAsStateWithLifecycle()
    val paused by session.player.paused.collectAsStateWithLifecycle()
    val history by session.history.collectAsStateWithLifecycle()

    fun has(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    var micGranted by remember { mutableStateOf(has(Manifest.permission.RECORD_AUDIO)) }
    var enableAfterGrant by remember { mutableStateOf(false) }
    val askPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        micGranted = has(Manifest.permission.RECORD_AUDIO)
        if (enableAfterGrant) {
            enableAfterGrant = false
            session.setVoice(true)
            VoiceService.start(context)
        }
    }
    fun wanted(): Array<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    val s = state
    val towerSpeaking = playing != null || (!voiceOn && (s?.speaking == true || s?.announcing == true))
    val mood = when {
        phase is Phase.Recording -> OrbMood.Listening
        phase is Phase.Sending -> OrbMood.Thinking
        towerSpeaking -> OrbMood.Speaking
        link !is Link.Live -> OrbMood.Off
        s?.phase == "thinking" || s?.phase == "transcribing" -> OrbMood.Thinking
        s?.phase == "blocked" -> OrbMood.Alert
        else -> OrbMood.Calm
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Be the voice
        GlassCard(Modifier.fillMaxWidth(), highlighted = voiceOn) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Phone is the voice", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, color = tc.text)
                    Text(
                        if (voiceOn) "The tower talks here and the Mac stays silent, also with the screen off."
                        else "Off: the tower speaks on the Mac. Turn on to hear it here.",
                        style = MaterialTheme.typography.bodySmall, color = tc.muted,
                    )
                }
                Switch(
                    checked = voiceOn,
                    colors = SwitchDefaults.colors(checkedTrackColor = tc.cyan, checkedThumbColor = NAVY),
                    onCheckedChange = { on ->
                        if (!on) {
                            session.setVoice(false)
                        } else if (wanted().all(::has)) {
                            session.setVoice(true)
                            VoiceService.start(context)
                        } else {
                            enableAfterGrant = true
                            askPerms.launch(wanted())
                        }
                    },
                )
            }
        }

        // The orb: hold to talk, or tap and talk until you pause.
        val hint = when (phase) {
            is Phase.Recording -> if ((phase as Phase.Recording).handsFree) "Listening… pause or tap to send" else "Listening… let go to send"
            Phase.Sending -> "Sending…"
            Phase.Idle -> when {
                link !is Link.Live -> "Not connected"
                !micGranted -> "Tap to allow the microphone"
                else -> "Hold to talk · or tap, talk, and pause"
            }
        }
        Box(
            Modifier
                .size(260.dp)
                .semantics { contentDescription = "Talk to the tower. Hold to talk, or tap once." }
                .pointerInput(micGranted) {
                    detectTapGestures(onPress = {
                        if (!micGranted) {
                            askPerms.launch(wanted())
                            return@detectTapGestures
                        }
                        voice.press()
                        tryAwaitRelease()
                        voice.release()
                    })
                },
            contentAlignment = Alignment.Center,
        ) {
            Orb(mood, if (phase is Phase.Recording) level else 0f, Modifier.fillMaxSize())
        }
        Text(hint, style = MaterialTheme.typography.titleSmall, color = tc.muted, textAlign = TextAlign.Center)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (phase is Phase.Recording) {
                OutlinedButton(onClick = { voice.cancel() }) {
                    Icon(Icons.Filled.Close, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Cancel")
                }
            }
            FilledTonalButton(onClick = { session.hush() }, enabled = link is Link.Live) {
                Icon(Icons.AutoMirrored.Filled.VolumeOff, null, Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Hush")
            }
        }

        error?.let { Text(it, color = tc.rose, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center) }

        // Live transcript: what the Mac is hearing and heard, and what the tower says.
        val hearing = s?.hearing.orEmpty()
        val youSaid = heard?.text?.takeIf { it.isNotBlank() } ?: s?.heard.orEmpty()
        val towerSaid = playing?.text?.takeIf { it.isNotBlank() } ?: s?.said.orEmpty()
        GlassCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Transcript("You", if (hearing.isNotBlank()) "$hearing…" else youSaid.ifBlank { "—" }, tc.cyan)
                Transcript(
                    when {
                        playing != null && paused -> "Tower (held: someone is talking at the Mac)"
                        playing != null -> "Tower, speaking"
                        else -> "Tower"
                    },
                    towerSaid.ifBlank { "—" },
                    tc.peri,
                )
            }
        }

        // The last exchanges.
        val recent = history.filter { it.kind == "request" || it.kind == "answer" }.takeLast(6)
        if (recent.isNotEmpty()) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("RECENT", style = MaterialTheme.typography.labelSmall, color = tc.faint)
                recent.forEach { Bubble(it) }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun Transcript(who: String, text: String, color: androidx.compose.ui.graphics.Color) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Dot(color, 6.dp)
            Text(who, style = MaterialTheme.typography.labelMedium, color = tc.faint)
        }
        Text(text, style = MaterialTheme.typography.bodyLarge, color = tc.text, modifier = Modifier.padding(start = 12.dp, top = 2.dp))
    }
}
