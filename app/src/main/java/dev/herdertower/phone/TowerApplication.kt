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

    override fun onCreate() {
        super.onCreate()
        session = TowerSession(this, KeystoreCredentialStore(this), Settings(this))
        voice = VoiceController(session)
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
