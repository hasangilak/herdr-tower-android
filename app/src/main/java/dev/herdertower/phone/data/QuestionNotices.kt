package dev.herdertower.phone.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.herdertower.phone.MainActivity
import dev.herdertower.phone.R
import dev.herdertower.phone.api.Question

/**
 * A notification when a session's question is left to the user (escalated)
 * while the app isn't on screen; tapping it opens that question. Gone when
 * the question is answered or closes.
 */
class QuestionNotices internal constructor(
    /** Whether a notification may be posted now. */
    private val canPost: () -> Boolean,
    private val out: Out,
) {
    constructor(context: Context) : this({ allowedOn(context) }, SystemOut(context))

    /** Where notifications go: the system's notification manager, or a test's list. */
    internal interface Out {
        fun post(q: Question)
        fun cancel(id: String)
    }

    private val shown = mutableSetOf<String>()

    /** Follows the open sets: notifies new escalated ones when [background], clears closed ones. */
    fun follow(open: List<Question>, background: Boolean) {
        val waiting = open.filter { it.forUser && it.decision == "escalate" && it.status in setOf("open", "failed") }
        for (q in waiting) if (q.id !in shown) {
            shown += q.id // seen in the app already, or notified now: once either way
            if (background) notify(q)
        }
        val gone = shown - waiting.map { it.id }.toSet()
        for (id in gone) {
            out.cancel(id)
            shown -= id
        }
    }

    /** The app came on screen: the cards show there. */
    fun clearAll() {
        shown.forEach { out.cancel(it) }
    }

    /** Unpaired, or paired with another tower: its questions are gone, and a new one with the same id notifies again. */
    fun forget() {
        clearAll()
        shown.clear()
    }

    private fun notify(q: Question) {
        if (canPost()) out.post(q)
    }

    /** Posts through the system's notification manager. */
    private class SystemOut(private val context: Context) : Out {
        private val nm = context.getSystemService(NotificationManager::class.java)

        override fun cancel(id: String) {
            nm?.cancel(TAG, id.hashCode())
        }

        override fun post(q: Question) {
            channel()
            val open = PendingIntent.getActivity(
                context, q.id.hashCode(),
                Intent(context, MainActivity::class.java).setAction(ACTION).putExtra(EXTRA_ID, q.id).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val pick = q.recommendationsFor(0).firstOrNull()?.label
            val text = q.headline + (pick?.let { "\nI'd say: $it" } ?: "")
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_tower)
                .setContentTitle("${q.who} asks")
                .setContentText(q.headline)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                // On a locked screen: that a session asks, not what (it can be about private work).
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(
                    NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_tower)
                        .setContentTitle("A session asks you something").setCategory(NotificationCompat.CATEGORY_MESSAGE).build(),
                )
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            nm?.notify(TAG, q.id.hashCode(), n)
        }

        private fun channel() {
            if (nm?.getNotificationChannel(CHANNEL) != null) return
            nm?.createNotificationChannel(NotificationChannel(CHANNEL, "Questions", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A session asks something the tower leaves to you"
            })
        }
    }

    companion object {
        private const val TAG = "question"
        private const val CHANNEL = "questions"
        const val ACTION = "dev.herdertower.phone.QUESTION"
        const val EXTRA_ID = "question"

        /**
         * Whether a notification may be posted: the app's notifications on,
         * and from Android 13 (API 33) the POST_NOTIFICATIONS permission.
         * Below 33 that permission doesn't exist: it always reads as denied
         * there, so it mustn't be asked about.
         */
        fun allowed(sdk: Int, notificationsOn: Boolean, permissionGranted: Boolean): Boolean =
            notificationsOn && (sdk < 33 || permissionGranted)

        fun allowedOn(context: Context): Boolean = allowed(
            Build.VERSION.SDK_INT,
            NotificationManagerCompat.from(context).areNotificationsEnabled(),
            Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        )
    }
}
