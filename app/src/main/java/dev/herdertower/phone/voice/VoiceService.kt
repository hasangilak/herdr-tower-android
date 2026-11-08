package dev.herdertower.phone.voice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.herdertower.phone.MainActivity
import dev.herdertower.phone.R
import dev.herdertower.phone.data.Link
import dev.herdertower.phone.tower
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps the voice stream (`?voice=1`) alive while the phone is the tower's
 * voice, screen on or off: a foreground service (microphone + media
 * playback) holding a Wi-Fi lock and a partial wake lock. Its notification
 * says how the connection is and has Stop (the tower talking), Talk and
 * End voice.
 */
class VoiceService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wifi: WifiManager.WifiLock? = null
    private var wake: PowerManager.WakeLock? = null
    private var media: HushSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        channel(this)
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
            (if (micGranted(this)) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        ServiceCompat.startForeground(this, NOTIFICATION, build("Connecting…", null), types)

        wifi = getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "tower:voice")
            .apply { setReferenceCounted(false); acquire() }
        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tower:voice")
            .apply { setReferenceCounted(false); acquire() }

        val app = tower
        scope.launch {
            app.session.voiceOn.collect { if (!it) stopSelf() }
        }
        // Headset / Bluetooth pause and volume down twice stop the tower while it talks.
        val hushSession = HushSession(this) { app.session.hush() }.also { media = it }
        scope.launch {
            app.session.player.nowPlaying.collect { hushSession.follow(it != null) }
        }
        scope.launch {
            combine(app.session.link, app.voice.phase, app.session.player.nowPlaying) { link, phase, playing ->
                val line = when (phase) {
                    is VoiceController.Phase.Recording -> "Listening…"
                    VoiceController.Phase.Sending -> "Sending what you said…"
                    VoiceController.Phase.Idle -> playing?.text?.takeIf { it.isNotBlank() } ?: describe(link)
                }
                Triple(line, phase is VoiceController.Phase.Recording, playing != null)
            }.distinctUntilChanged().collect { (line, rec, talking) ->
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION, build(line, rec, talking))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = tower
        when (intent?.action) {
            ACTION_TALK -> if (micGranted(this)) app.voice.toggle()
            ACTION_HUSH -> app.session.hush()
            ACTION_STOP -> app.session.setVoice(false)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        media?.release()
        wifi?.takeIf { it.isHeld }?.release()
        wake?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun describe(link: Link): String = when (link) {
        is Link.Live -> if (link.voice) "The phone is the tower's voice" else "Connected"
        is Link.Connecting -> "Connecting…"
        is Link.Retrying -> "Reconnecting in ${link.seconds} s"
        is Link.Revoked -> "Not paired any more. Open the app to pair again."
        is Link.CertificateChanged -> "The tower's certificate changed. Open the app."
        Link.Idle, Link.Unpaired -> "Not connected"
    }

    private fun build(text: String, recording: Boolean?, talking: Boolean = false): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        fun action(a: String, req: Int) = PendingIntent.getService(this, req, Intent(this, VoiceService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tower)
            .setContentTitle("Tower voice")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .apply {
                // While it talks, Stop comes first (it's what the collapsed notification shows).
                if (talking) addAction(R.drawable.ic_stop, "Stop", action(ACTION_HUSH, 2))
                addAction(0, if (recording == true) "Send" else "Talk", action(ACTION_TALK, 1))
                addAction(0, "End voice", action(ACTION_STOP, 3))
            }
            .build()
    }

    companion object {
        private const val CHANNEL = "voice"
        private const val NOTIFICATION = 1
        const val ACTION_TALK = "dev.herdertower.phone.TALK"
        const val ACTION_HUSH = "dev.herdertower.phone.HUSH"
        const val ACTION_STOP = "dev.herdertower.phone.STOP"

        fun micGranted(c: Context) = ContextCompat.checkSelfPermission(c, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        /** Call while the app is on screen (Android only lets a microphone service start then). */
        fun start(c: Context) {
            ContextCompat.startForegroundService(c, Intent(c, VoiceService::class.java))
        }

        private fun channel(c: Context) {
            val nm = c.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Tower voice", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while the phone is the tower's voice"
                })
            }
        }
    }
}
