package dev.herdertower.phone.data

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.herdertower.phone.tower
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Every 15 minutes while the phone is paired, with a network: reads the
 * queue when no stream is open, so a decision notifies with the app closed
 * and the phone not the voice (the stream only runs on screen, for a
 * moment after a send, or while the phone is the voice). The one
 * notification is still only a decision the phone hasn't seen.
 */
class DecisionCheck(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val session = applicationContext.tower.session
        // The session's state lives on the main thread; its calls don't block it.
        val did = withContext(Dispatchers.Main) { withTimeoutOrNull(TIMEOUT_MS) { session.checkInBackground() } } ?: "timed out"
        Log.i(TAG, "background check: $did")
        // Another try comes with the next period: no retries in between.
        return Result.success()
    }

    companion object {
        private const val TAG = "DecisionCheck"
        const val NAME = "decision-check"
        private const val TIMEOUT_MS = 60_000L

        /** Paired: the check runs every 15 minutes (kept as it is when it runs already). Not paired: it stops. */
        fun follow(context: Context, paired: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!paired) {
                wm.cancelUniqueWork(NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<DecisionCheck>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
