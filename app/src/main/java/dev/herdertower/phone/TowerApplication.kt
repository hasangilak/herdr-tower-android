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
import kotlinx.coroutines.launch

class TowerApplication : Application() {
    lateinit var session: TowerSession
        private set
    lateinit var voice: VoiceController
        private set
    /** The one draft Home and Chat share. */
    lateinit var composer: dev.herdertower.phone.compose.Composer
        private set

    override fun onCreate() {
        super.onCreate()
        session = TowerSession(this, KeystoreCredentialStore(this), Settings(this))
        voice = VoiceController(session)
        composer = dev.herdertower.phone.compose.Composer(this, session)
        // How this device can draw the orb: found once, off the main thread, by drawing it.
        dev.herdertower.phone.orb.OrbProbe.override = dev.herdertower.phone.orb.OrbTier.entries.firstOrNull { it.name == session.settings.orbTier }
        Thread({ dev.herdertower.phone.orb.OrbProbe.run() }, "orb-probe").start()
        // Wi-Fi back or changed: reconnect now instead of waiting out the backoff.
        getSystemService(android.net.ConnectivityManager::class.java)?.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                session.reconnect()
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
            kotlinx.coroutines.flow.combine(session.player.nowPlaying, session.voiceOn, session.hushWord, voice.phase) { clip, on, word, phase ->
                val mic = androidx.core.content.ContextCompat.checkSelfPermission(this@TowerApplication, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
                clip to (on && word && mic && phase == VoiceController.Phase.Idle)
            }.collect { (clip, enabled) -> hush.follow(clip, enabled) }
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
