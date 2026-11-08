package dev.herdertower.phone.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.herdertower.phone.MainActivity
import dev.herdertower.phone.R
import dev.herdertower.phone.api.Question

/**
 * A notification when a session's question is left to the user (escalated)
 * while the app isn't on screen; tapping it opens that question. Gone when
 * the question is answered or closes.
 */
class QuestionNotices(private val context: Context) {
    private val shown = mutableSetOf<String>()
    private val nm = context.getSystemService(NotificationManager::class.java)

    /** Follows the open sets: notifies new escalated ones when [background], clears closed ones. */
    fun follow(open: List<Question>, background: Boolean) {
        val waiting = open.filter { it.forUser && it.decision == "escalate" && it.status in setOf("open", "failed") }
        for (q in waiting) if (q.id !in shown) {
            shown += q.id // seen in the app already, or notified now: once either way
            if (background) notify(q)
        }
        val gone = shown - waiting.map { it.id }.toSet()
        for (id in gone) {
            nm?.cancel(TAG, id.hashCode())
            shown -= id
        }
    }

    /** The app came on screen: the cards show there. */
    fun clearAll() {
        shown.forEach { nm?.cancel(TAG, it.hashCode()) }
    }

    private fun notify(q: Question) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
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

    companion object {
        private const val TAG = "question"
        private const val CHANNEL = "questions"
        const val ACTION = "dev.herdertower.phone.QUESTION"
        const val EXTRA_ID = "question"
    }
}
