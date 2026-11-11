package dev.herdertower.phone

import android.app.Application
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import dev.herdertower.phone.data.KeystoreCredentialStore
import dev.herdertower.phone.data.Settings
import dev.herdertower.phone.data.TowerSession
import dev.herdertower.phone.voice.VoiceController
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

class TowerApplication : Application() {
    lateinit var session: TowerSession
        private set
    lateinit var voice: VoiceController
        private set
    /** The one draft Home and Chat share. */
    lateinit var composer: dev.herdertower.phone.compose.Composer
        private set

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        session = TowerSession(this, KeystoreCredentialStore(this), Settings(this))
        voice = VoiceController(session)
        composer = dev.herdertower.phone.compose.Composer(this, session)
        // One microphone at a time: a voice take stops dictation into the draft.
        voice.beforeTake = { if (composer.dictation.value.active.value) composer.stopDictation() }
        // And the other way: dictation starting ends a voice take still running (left hands-free, or from the notification).
        composer.beforeDictation = { if (voice.phase.value is VoiceController.Phase.Recording) voice.cancel() }
        // How this device can draw the orb: found once, off the main thread, by drawing it.
        dev.herdertower.phone.orb.OrbProbe.override = dev.herdertower.phone.orb.OrbTier.entries.firstOrNull { it.name == session.settings.orbTier }
        Thread({ dev.herdertower.phone.orb.OrbProbe.run() }, "orb-probe").start()
        // The network changed (Wi-Fi joined or lost, Tailscale's VPN on or off): reconnect now if not connected,
        // and if connected, check the route still holds or the LAN is back (TowerSession.networkChanged).
        getSystemService(android.net.ConnectivityManager::class.java)?.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
            private var last: Pair<android.net.Network, Pair<Boolean, Boolean>>? = null

            override fun onAvailable(network: android.net.Network) {
                session.networkChanged()
            }

            override fun onLost(network: android.net.Network) {
                last = null
                session.networkChanged()
            }

            override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) {
                // Only a change of kind (a VPN on top or not, Wi-Fi or not), not every signal-strength update.
                val kind = caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) to caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                val before = last
                last = network to kind
                if (before != null && before != last) session.networkChanged()
            }
        })
        // Headphones pulled out: stop talking out loud.
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context, i: android.content.Intent) {
                    session.player.hush()
                }
            },
            android.content.IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // Say "stop" while it talks on the phone: listen only while a reply plays, the phone is the voice and no take runs.
        val hush = dev.herdertower.phone.voice.HushListener(session) { session.hush() }
        kotlinx.coroutines.MainScope().launch {
            val dictating = composer.dictation.flatMapLatest { it.active }
            kotlinx.coroutines.flow.combine(session.player.nowPlaying, session.voiceOn, session.hushWord, voice.phase, dictating) { clip, on, word, phase, dict ->
                val mic = androidx.core.content.ContextCompat.checkSelfPermission(this@TowerApplication, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
                // Not while the user is talking (a take, or dictation): one recording at a time.
                clip to (on && word && mic && phase == VoiceController.Phase.Idle && !dict)
            }.collect { (clip, enabled) -> hush.follow(clip, enabled) }
        }
        // A decision's notification showing with the app away is checked every minute, so it goes once it's over on the Mac.
        session.onNoticeShowing = { dev.herdertower.phone.data.DecisionCheck.watch(this) }
                // A decision notifies with the app closed too: a check every 15 minutes while paired, none otherwise.
        kotlinx.coroutines.MainScope().launch {
            session.credentials.collect { dev.herdertower.phone.data.DecisionCheck.follow(this@TowerApplication, paired = it != null) }
        }
                ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> session.setForeground(true)
                Lifecycle.Event.ON_STOP -> session.setForeground(false)
                else -> {}
            }
        })
    }
}

val Context.tower: TowerApplication get() = applicationContext as TowerApplication
