package dev.herdertower.phone.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import dev.herdertower.phone.MainActivity
import dev.herdertower.phone.R
import dev.herdertower.phone.api.Decision
import dev.herdertower.phone.api.DecisionsApi
import dev.herdertower.phone.tower
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The only notification the app posts: a decision the phone has not seen
 * yet, while the app isn't on screen, with its first two answers as
 * actions. Nothing else notifies: no finishes, no deliveries, no messages
 * between sessions. It goes when the decision is answered or closes, or
 * when the app comes on screen (its card shows there).
 */
class DecisionNotices internal constructor(
    /** Whether a notification may be posted now. */
    private val canPost: () -> Boolean,
    private val out: Out,
    private val seen: Seen,
) {
    constructor(context: Context) : this({ allowedOn(context) }, SystemOut(context), PrefsSeen(context))

    /** Where notifications go: the system's notification manager, or a test's list. */
    internal interface Out {
        fun post(d: Decision)
        fun cancel(id: String)
        /** The decisions whose notification shows now. */
        fun active(): Set<String>
        /** An answer from the notification didn't go in: say so on it. */
        fun failed(d: Decision, why: String) {}
    }

    /** The ids already seen, in the order seen, kept across restarts: a decision notifies once, also after the process was stopped. */
    internal interface Seen {
        fun load(): List<String>
        fun save(ids: List<String>)
    }

    private val known = LinkedHashSet(seen.load())

    /**
     * Follows the decisions (every open one, a muted space's marked): one
     * whose id the phone has not seen notifies when it is open, not muted,
     * and the app is in the [background]. Every id goes into the seen set,
     * muted or not, whatever its state, and stays there: the same decision
     * asked again keeps its id, and a muted space's decision is never
     * notified, not when its space is unmuted either. A notification goes
     * when its decision is being answered, is muted, or leaves the list.
     */
    fun follow(all: List<Decision>, background: Boolean) {
        var changed = false
        for (d in all) {
            val new = known.add(d.id)
            changed = changed || new
            if (!d.open || d.muted) {
                if (!new) out.cancel(d.id)
                continue
            }
            if (new && background && canPost()) out.post(d)
        }
        val here = all.map { it.id }.toSet()
        (out.active() - here).forEach(out::cancel)
        if (changed) {
            while (known.size > MAX_SEEN) known.remove(known.first())
            seen.save(known.toList())
        }
    }

    /** The app came on screen: the cards show there. */
    fun clearAll() {
        out.active().forEach(out::cancel)
    }

    /** The decisions whose notification shows now: while any does, the phone keeps checking whether they are over. */
    fun showing(): Set<String> = out.active()

    /** Answered from its notification: it goes at once, before the queue says so. */
    fun answered(id: String) {
        out.cancel(id)
    }

    /** An answer from the notification failed: the notification stays and says why. */
    fun failed(d: Decision, why: String) {
        if (canPost()) out.failed(d, why)
    }

    /** Unpaired, or paired with another tower: its decisions are gone, and one with the same id notifies again. */
    fun forget() {
        clearAll()
        known.clear()
        seen.save(emptyList())
    }

    private class PrefsSeen(context: Context) : Seen {
        private val prefs = context.getSharedPreferences("decision_notices", Context.MODE_PRIVATE)
        // In order, one id a line (a string set keeps no order, and the oldest are the ones to drop).
        override fun load(): List<String> = prefs.getString("seen_ids", null)?.split('\n')?.filter { it.isNotBlank() }
            ?: prefs.getStringSet("seen", emptySet()).orEmpty().toList()
        override fun save(ids: List<String>) = prefs.edit {
            putString("seen_ids", ids.joinToString("\n"))
            remove("seen")
        }
    }

    /** Posts through the system's notification manager. */
    private class SystemOut(private val context: Context) : Out {
        private val nm = context.getSystemService(NotificationManager::class.java)

        override fun cancel(id: String) {
            nm?.cancel(TAG + id, NOTE_ID)
        }

        override fun active(): Set<String> =
            runCatching { nm?.activeNotifications.orEmpty().mapNotNull { it.tag?.takeIf { t -> t.startsWith(TAG) }?.removePrefix(TAG) }.toSet() }.getOrDefault(emptySet())

        override fun post(d: Decision) = show(d, null)

        override fun failed(d: Decision, why: String) = show(d, why)

        private fun show(d: Decision, failure: String?) {
            channel()
            val open = PendingIntent.getActivity(
                context, d.id.hashCode(),
                Intent(context, MainActivity::class.java).setAction(ACTION_OPEN).putExtra(EXTRA_ID, d.id).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val text = failure?.let { "Couldn't answer: $it" } ?: line(d)
            val b = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_tower)
                .setContentTitle(d.title.ifBlank { "A decision waits for you" })
                .setContentText(text)
                // Opened out: the whole decision (the title's one line cuts a long one short), what waits on it, what it's about.
                .setStyle(NotificationCompat.BigTextStyle().bigText(listOf(d.title.takeIf { it.length > TITLE_FITS }.orEmpty(), text, d.detail.take(240)).filter { it.isNotBlank() }.joinToString("\n")))
                .setSubText(d.space.takeIf { it.isNotBlank() })
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                // On a locked screen: that a decision waits, not which (it can be about private work),
                // unless the phone is set to show everything there.
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(
                    NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_tower)
                        .setContentTitle("A decision waits for you").setCategory(NotificationCompat.CATEGORY_REMINDER).build(),
                )
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOnlyAlertOnce(failure != null)
                .setContentIntent(open)
                .setAutoCancel(true)
            actions(d).forEach { (index, label) ->
                val answer = PendingIntent.getBroadcast(
                    context, (d.id + "#" + index).hashCode(),
                    Intent(context, Receiver::class.java).setAction(ACTION_ANSWER).putExtra(EXTRA_ID, d.id).putExtra(EXTRA_CHOICE, index).putExtra(EXTRA_LABEL, label),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                // An answer is the user's own: on a locked phone it asks for the unlock first.
                b.addAction(NotificationCompat.Action.Builder(0, label, answer).setAuthenticationRequired(true).build())
            }
            b.addAction(0, "Open", open)
            // Swiped away (or Clear all): the decision is dismissed on the tower too, so it doesn't wait there.
            b.setDeleteIntent(
                PendingIntent.getBroadcast(
                    context, (d.id + "#discard").hashCode(),
                    Intent(context, Receiver::class.java).setAction(ACTION_DISCARD).putExtra(EXTRA_ID, d.id),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            nm?.notify(TAG + d.id, NOTE_ID, b.build())
        }

        private fun channel() {
            if (nm?.getNotificationChannel(CHANNEL) != null) return
            nm?.createNotificationChannel(NotificationChannel(CHANNEL, "Decisions", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A decision only you can make. Nothing else notifies."
            })
            // From before the queue: questions and PRs each had their own.
            runCatching { nm?.deleteNotificationChannel("questions") }
            runCatching { nm?.deleteNotificationChannel("prs") }
        }
    }

    /** An answer tapped on a notification: sent in the background, the notification gone when it went in. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_DISCARD) return discard(context, intent)
            if (intent.action != ACTION_ANSWER) return
            val id = intent.getStringExtra(EXTRA_ID) ?: return
            val index = intent.getIntExtra(EXTRA_CHOICE, -1)
            val label = intent.getStringExtra(EXTRA_LABEL).orEmpty()
            if (index < 0) return
            val pending = goAsync()
            val session = context.tower.session
            session.scope.launch {
                try {
                    // A receiver has about ten seconds.
                    withTimeoutOrNull(9_000) { session.answerFromNotice(id, index, label) }
                        ?: session.noticeFailed(id, "the Mac didn't answer in time")
                } finally {
                    pending.finish()
                }
            }
        }

        /** The notification was swiped away: dismiss the decision on the tower. Nothing is posted again if that fails. */
        private fun discard(context: Context, intent: Intent) {
            val id = intent.getStringExtra(EXTRA_ID) ?: return
            val pending = goAsync()
            val session = context.tower.session
            session.scope.launch {
                try {
                    withTimeoutOrNull(9_000) { session.discardFromNotice(id) }
                } finally {
                    pending.finish()
                }
            }
        }
    }

    companion object {
        /** A notification's tag is this and the decision's id (so the ones showing can be listed). */
        private const val TAG = "decision:"
        private const val NOTE_ID = 1
        private const val CHANNEL = "decisions"
        private const val MAX_SEEN = 1000
        /** About what fits the notification's title line. */
        private const val TITLE_FITS = 30
        const val ACTION_OPEN = "dev.herdertower.phone.DECISION"
        const val ACTION_ANSWER = "dev.herdertower.phone.DECISION_ANSWER"
        const val ACTION_DISCARD = "dev.herdertower.phone.DECISION_DISCARD"
        const val EXTRA_ID = "decision"
        const val EXTRA_CHOICE = "choice"
        const val EXTRA_LABEL = "label"

        /** "sandbox · 3 sessions wait". */
        fun line(d: Decision): String = listOf(d.why, DecisionsApi.stake(d)).filter { it.isNotBlank() }.joinToString(" · ")

        /**
         * The answers a notification offers: the first two, each with its
         * index. None for a merge (merging shows what it would do first, in
         * the app) or for a decision with nothing to pick.
         */
        fun actions(d: Decision): List<Pair<Int, String>> =
            if (d.kind == "merge" || !DecisionsApi.answerable(d)) emptyList()
            else d.options.withIndex().filter { it.value.label.isNotBlank() }.take(2).map { it.index to it.value.label.take(40) }

        /**
         * Whether a notification may be posted: the app's notifications on,
         * and from Android 13 (API 33) the POST_NOTIFICATIONS permission.
         * Below 33 that permission doesn't exist: it always reads as denied
         * there, so it mustn't be asked about.
         */
        fun allowed(sdk: Int, notificationsOn: Boolean, permissionGranted: Boolean): Boolean =
            notificationsOn && (sdk < 33 || permissionGranted)

        fun allowedOn(context: Context): Boolean = runCatching {
            allowed(
                Build.VERSION.SDK_INT,
                NotificationManagerCompat.from(context).areNotificationsEnabled(),
                Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            )
        }.getOrDefault(false) // no notification service to ask: nothing is posted, and the queue is still followed
    }
}
