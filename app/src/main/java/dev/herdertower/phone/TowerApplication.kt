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
