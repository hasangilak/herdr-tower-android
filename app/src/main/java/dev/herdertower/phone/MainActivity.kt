package dev.herdertower.phone

import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.herdertower.phone.api.PairLink
import dev.herdertower.phone.voice.VoiceService
import dev.herdertower.phone.ui.Shell
import dev.herdertower.phone.ui.PairingScreen
import dev.herdertower.phone.ui.TowerTheme

class MainActivity : ComponentActivity() {
    /** A pairing link the app was opened with (the QR scanned with the camera app). */
    private var link by mutableStateOf<PairLink?>(null)
    /** The user asked to pair again while paired. */
    private var repairing by mutableStateOf(false)
    private var theme by mutableStateOf("system")
    /** A question to open (its notification was tapped). */
    private var openQuestion by mutableStateOf<String?>(null)
    /** A PR whose chip to open (its notification was tapped). */
    private var openPr by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        volumeControlStream = AudioManager.STREAM_MUSIC
        val app = tower
        theme = app.session.settings.theme
        // Only a fresh start reads the launch intent: after a rotation it would bring back a link already used.
        // A recreate gets back the link still being confirmed (and pairing again) from the saved state instead.
        if (savedInstanceState == null) take(intent) else {
            link = savedInstanceState.getString(SAVED_LINK)?.let { raw -> runCatching { PairLink.parse(raw) }.getOrNull() }
            repairing = savedInstanceState.getBoolean(SAVED_REPAIRING)
        }
        setContent {
            val creds by app.session.credentials.collectAsStateWithLifecycle()
            // Once paired (again), leave the pairing screen.
            var seen by remember { mutableStateOf(creds) }
            LaunchedEffect(creds) {
                if (creds != null && creds != seen) {
                    repairing = false
                    link = null
                }
                seen = creds
            }
            TowerTheme(theme) {
                val l = link
                if (creds == null || repairing || l != null) {
                    PairingScreen(
                        app.session,
                        initial = l,
                        onCancel = if (creds != null) ({ repairing = false; link = null }) else null,
                    )
                } else {
                    Shell(
                        app.session, app.voice, theme,
                        onTheme = { theme = it; app.session.settings.theme = it },
                        onRepair = { repairing = true },
                        openQuestion = openQuestion,
                        onQuestionOpened = { openQuestion = null },
                        openPr = openPr,
                        onPrOpened = { openPr = null },
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        link?.let { outState.putString(SAVED_LINK, it.toLink()) }
        outState.putBoolean(SAVED_REPAIRING, repairing)
    }

    private val hushKeys = dev.herdertower.phone.voice.HushKeys()

    /** Volume down twice (or held) while the tower talks stops it; the press still turns the volume down. */
    @android.annotation.SuppressLint("RestrictedApi") // ComponentActivity marks this override restricted; it's the platform's own method.
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN && event.action == android.view.KeyEvent.ACTION_DOWN) {
            val app = tower
            val talking = app.session.player.nowPlaying.value != null || app.session.state.value?.speaking == true
            if (talking && hushKeys.down(event.eventTime, event.repeatCount)) app.session.hush()
            if (!talking) hushKeys.reset()
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        take(intent)
        // Consumed: a recreate doesn't read it again.
        setIntent(Intent(this, MainActivity::class.java))
    }

    override fun onStart() {
        super.onStart()
        // The phone was the voice when the app last ran: carry on (a microphone service may only start from the foreground).
        if (tower.session.voiceOn.value) VoiceService.start(this)
    }

    private fun take(intent: Intent?) {
        if (intent?.action == dev.herdertower.phone.data.QuestionNotices.ACTION) {
            openQuestion = intent.getStringExtra(dev.herdertower.phone.data.QuestionNotices.EXTRA_ID)
            return
        }
        if (intent?.action == dev.herdertower.phone.data.PrNotices.ACTION) {
            openPr = intent.getStringExtra(dev.herdertower.phone.data.PrNotices.EXTRA_KEY)
            return
        }
        val data = intent?.data?.toString() ?: return
        if (!data.startsWith(PairLink.SCHEME + "://", ignoreCase = true)) return
        link = try {
            PairLink.parse(data)
        } catch (e: IllegalArgumentException) {
            null // not a valid pairing link: nothing to show
        }
    }

    private companion object {
        const val SAVED_LINK = "pair_link"
        const val SAVED_REPAIRING = "repairing"
    }
}
