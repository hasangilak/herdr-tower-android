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
        if (!promote()) {
            // Not even as media playback (restarted by the system with the app in the background on a recent Android):
            // the app starts it again when it next comes on screen.
            stopSelf()
            return
        }
        running = true

        wifi = getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "tower:voice")
            .apply { setReferenceCounted(false); acquire() }
        // Held with a timeout and renewed while the service runs: if it's ever left behind, it still ends.
        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tower:voice")
            .apply { setReferenceCounted(false); acquire(WAKE_MS) }
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(WAKE_MS - 60_000L)
                wake?.acquire(WAKE_MS)
            }
        }

        val app = tower
        scope.launch {
            app.session.voiceOn.collect { if (!it) stopSelf() }
        }
        scope.launch {
            // Muted (tower-wide), the notification says so and offers the way back.
            app.session.muted.collect { mutedNow = it; refresh() }
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
                last = Triple(line, rec, talking)
                refresh()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) return START_NOT_STICKY
        val app = tower
        // The microphone was allowed after the service started: take it on now (the app is on screen when it asks).
        if (!withMic && micGranted(this)) promote()
        when (intent?.action) {
            ACTION_TALK -> if (micGranted(this) && withMic) app.voice.toggle()
            ACTION_HUSH -> app.session.hush()
            ACTION_UNMUTE -> scope.launch { app.session.setMuted(false) }
            ACTION_STOP -> app.session.setVoice(false)
        }
        return START_STICKY
    }

    /** Whether the service runs with the microphone type (only then can a take record with the screen off). */
    private var withMic = false
    /** It made it to the foreground (onCreate may have given up). */
    private var running = false
    private var mutedNow = false
    private var last: Triple<String, Boolean, Boolean>? = null

    /**
     * Runs the service in the foreground: with the microphone when it's
     * allowed and Android lets it start now, else as media playback only.
     * False when neither is allowed (a background restart on Android 12+).
     */
    private fun promote(): Boolean {
        val note = last?.let { (line, rec, talking) -> build(line, rec, talking) } ?: build("Connecting…", null)
        val how = startForegroundAs(micGranted(this), android.os.Build.VERSION.SDK_INT) { types ->
            ServiceCompat.startForeground(this, NOTIFICATION, note, types)
        }
        withMic = how == Started.WithMic || how == Started.MediaMayRecord
        return how != Started.No
    }

    private fun refresh() {
        val (line, rec, talking) = last ?: return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, build(line, rec, talking))
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
        is Link.Connecting -> link.failing?.let { "Reconnecting…" } ?: "Connecting…"
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
            .setContentTitle(if (mutedNow) "Tower voice · muted" else "Tower voice")
            .setContentText(text)
            // On a locked screen: not what the tower is saying (it can be about private work).
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_tower).setContentTitle("Tower voice")
                    .setContentText("The phone is the tower's voice").setSilent(true).build(),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .apply {
                // While it talks, Stop comes first (it's what the collapsed notification shows).
                if (talking) addAction(R.drawable.ic_stop, "Stop", action(ACTION_HUSH, 2))
                if (mutedNow) addAction(0, "Unmute", action(ACTION_UNMUTE, 4))
                addAction(0, if (recording == true) "Send" else "Talk", action(ACTION_TALK, 1))
                addAction(0, "End voice", action(ACTION_STOP, 3))
            }
            .build()
    }

    /** How the service went to the foreground. */
    enum class Started {
        /** With the microphone type: takes may record with the screen off. */
        WithMic,
        /** As media playback only: Android refused the microphone (a background restart). */
        MediaOnly,
        /** Before Android 11, where there's no microphone type: a media service may record. */
        MediaMayRecord,
        /** Not at all. */
        No,
    }

    companion object {
        /**
         * Starts the foreground with the microphone when it's allowed and
         * Android lets it start now, else as media playback only. [start]
         * throws when Android refuses (SecurityException, or
         * ForegroundServiceStartNotAllowedException, both runtime exceptions).
         */
        @android.annotation.SuppressLint("InlinedApi") // the microphone type (API 30) is only used from [sdk] 30 on
        internal fun startForegroundAs(micGranted: Boolean, sdk: Int, start: (types: Int) -> Unit): Started {
            val media = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            if (micGranted && sdk >= 30) {
                try {
                    start(media or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                    return Started.WithMic
                } catch (e: RuntimeException) {
                    android.util.Log.i("VoiceService", "no microphone type: ${e.javaClass.simpleName}")
                }
            }
            return try {
                start(media)
                if (micGranted && sdk < 30) Started.MediaMayRecord else Started.MediaOnly
            } catch (e: RuntimeException) {
                android.util.Log.i("VoiceService", "can't start: ${e.javaClass.simpleName}")
                Started.No
            }
        }

        private const val CHANNEL = "voice"
        private const val NOTIFICATION = 1
        /** How long the wake lock holds at a time; renewed a minute before it runs out. */
        private const val WAKE_MS = 15 * 60_000L
        const val ACTION_TALK = "dev.herdertower.phone.TALK"
        const val ACTION_HUSH = "dev.herdertower.phone.HUSH"
        const val ACTION_STOP = "dev.herdertower.phone.STOP"
        const val ACTION_UNMUTE = "dev.herdertower.phone.UNMUTE"

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
