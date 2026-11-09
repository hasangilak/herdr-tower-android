package dev.herdertower.phone.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.herdertower.phone.MainActivity
import dev.herdertower.phone.R
import dev.herdertower.phone.api.Pr

/**
 * A notification when a PR a session opened waits for the user (its
 * session ended its turn on it, or asked for the merge) while the app isn't
 * on screen: the PR's title, and an action that opens its chip in the chat.
 * Gone when it stops waiting, is merged or closed, or the app comes on screen.
 */
class PrNotices internal constructor(
    private val canPost: () -> Boolean,
    private val out: Out,
) {
    constructor(context: Context) : this({ QuestionNotices.allowedOn(context) }, SystemOut(context))

    internal interface Out {
        fun post(pr: Pr)
        fun cancel(key: String)
    }

    private val shown = mutableSetOf<String>()

    /** Follows the PRs: notifies one that starts waiting when [background] (once), clears those that stopped. */
    fun follow(prs: Collection<Pr>, background: Boolean) {
        val waiting = prs.filter { it.waiting && it.open }
        for (p in waiting) if (p.id !in shown) {
            shown += p.id // seen in the app already, or notified now: once either way
            if (background && canPost()) out.post(p)
        }
        val gone = shown - waiting.map { it.id }.toSet()
        for (k in gone) {
            out.cancel(k)
            shown -= k
        }
    }

    /** The app came on screen: the chips show there. */
    fun clearAll() {
        shown.forEach { out.cancel(it) }
    }

    /** Unpaired, or another tower: a PR with the same key notifies again. */
    fun forget() {
        clearAll()
        shown.clear()
    }

    private class SystemOut(private val context: Context) : Out {
        private val nm = context.getSystemService(NotificationManager::class.java)

        override fun cancel(key: String) {
            nm?.cancel(TAG, key.hashCode())
        }

        override fun post(pr: Pr) {
            channel()
            val open = PendingIntent.getActivity(
                context, pr.id.hashCode(),
                Intent(context, MainActivity::class.java).setAction(ACTION).putExtra(EXTRA_KEY, pr.id).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_tower)
                .setContentTitle(title(pr))
                .setContentText(pr.title.ifBlank { pr.id })
                .setStyle(NotificationCompat.BigTextStyle().bigText(text(pr)))
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                // On a locked screen: that a PR waits, not which (repo names and titles can be private).
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(
                    NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_tower)
                        .setContentTitle("A PR waits for you").setCategory(NotificationCompat.CATEGORY_REMINDER).build(),
                )
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(open)
                .addAction(0, "Open in chat", open)
                .setAutoCancel(true)
                .build()
            nm?.notify(TAG, pr.id.hashCode(), n)
        }

        private fun channel() {
            if (nm?.getNotificationChannel(CHANNEL) != null) return
            nm?.createNotificationChannel(NotificationChannel(CHANNEL, "Pull requests", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A PR a session opened waits for you to merge it"
            })
        }
    }

    companion object {
        private const val TAG = "pr"
        private const val CHANNEL = "prs"
        const val ACTION = "dev.herdertower.phone.PR"
        const val EXTRA_KEY = "pr"

        /** "PR #12 waits for you · owner/app". */
        fun title(pr: Pr): String = "PR #${pr.number} waits for you" + if (pr.repo.isNotBlank()) " · ${pr.repo}" else ""

        /** The title, then where it merges and what its checks say. */
        fun text(pr: Pr): String = buildString {
            append(pr.title.ifBlank { pr.id })
            if (pr.base.isNotBlank()) append("\nInto ${pr.base}" + if (pr.name.isNotBlank()) " · from ${pr.name}" else "")
            when (pr.checks.state) {
                "failing" -> append("\nFailing: ${pr.checks.failing.joinToString(", ")}")
                "pending" -> append("\nChecks running")
                "passing" -> append("\nChecks passing")
            }
        }
    }
}
