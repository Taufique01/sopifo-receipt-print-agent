package com.sopifo.printagent.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.sopifo.printagent.core.AppLog
import com.sopifo.printagent.jobs.JobReporter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * All background work goes through WorkManager: it survives process death and reboots,
 * honours Doze, and batches with other apps' work. No thread in this app loops or polls.
 */
class WorkScheduler(context: Context) : JobReporter {
    private val appContext = context.applicationContext
    private val wm: WorkManager get() = WorkManager.getInstance(appContext)

    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** FCM wake-up for one job. Expedited so it runs within seconds even in the background. */
    fun enqueueJob(jobId: String, source: String) = safely("enqueueJob") {
        val request = OneTimeWorkRequestBuilder<JobWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(online)
            .setInputData(workDataOf(JobWorker.KEY_JOB_ID to jobId, JobWorker.KEY_SOURCE to source))
            .addTag(TAG_AGENT)
            .build()
        wm.enqueueUniqueWork("job-$jobId", ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Pending-job recovery. Triggers that fire together (boot + network + screen on) collapse
     * into one request through the debounce window and the unique-work KEEP policy.
     */
    fun enqueuePendingSync(reason: String, force: Boolean = false) = safely("enqueuePendingSync") {
        val now = System.currentTimeMillis()
        val last = lastPendingSync.get()
        if (!force && now - last < PENDING_SYNC_DEBOUNCE_MS) return@safely
        lastPendingSync.set(now)
        val request = OneTimeWorkRequestBuilder<PendingSyncWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(online)
            .setInputData(workDataOf(PendingSyncWorker.KEY_REASON to reason))
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.SECONDS)
            .addTag(TAG_AGENT)
            .build()
        wm.enqueueUniqueWork(UNIQUE_PENDING_SYNC, if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
        AppLog.d(TAG, "Pending sync enqueued", "reason" to reason)
    }

    /** Starts the heartbeat chain if it is not already scheduled. */
    fun ensureHeartbeat() = safely("ensureHeartbeat") {
        wm.enqueueUniqueWork(UNIQUE_HEARTBEAT, ExistingWorkPolicy.KEEP, heartbeatRequest(0))
    }

    /** Called by the heartbeat itself: appends the next beat, 5 minutes out. */
    fun scheduleNextHeartbeat() = safely("scheduleNextHeartbeat") {
        wm.enqueueUniqueWork(UNIQUE_HEARTBEAT, ExistingWorkPolicy.APPEND_OR_REPLACE, heartbeatRequest(HEARTBEAT_INTERVAL_MIN))
    }

    fun triggerHeartbeatNow() = safely("triggerHeartbeatNow") {
        wm.enqueueUniqueWork(UNIQUE_HEARTBEAT, ExistingWorkPolicy.REPLACE, heartbeatRequest(0))
    }

    private fun heartbeatRequest(delayMinutes: Long) = OneTimeWorkRequestBuilder<HeartbeatWorker>()
        .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
        .addTag(TAG_AGENT)
        .build()

    /**
     * 15-minute safety net (WorkManager's minimum period): restarts the heartbeat chain and the
     * foreground service if either was killed by the OS or an OEM battery manager.
     */
    fun ensureWatchdog() = safely("ensureWatchdog") {
        val request = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).addTag(TAG_AGENT).build()
        wm.enqueueUniquePeriodicWork(UNIQUE_WATCHDOG, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun enqueueFcmTokenSync(token: String?) = safely("enqueueFcmTokenSync") {
        val request = OneTimeWorkRequestBuilder<FcmTokenWorker>()
            .setConstraints(online)
            .setInputData(workDataOf(FcmTokenWorker.KEY_TOKEN to token))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG_AGENT)
            .build()
        wm.enqueueUniqueWork(UNIQUE_FCM_TOKEN, ExistingWorkPolicy.REPLACE, request)
    }

    override fun reportCompleted(jobId: String, printedAtIso: String) =
        enqueueReport(jobId, workDataOf(ReportWorker.KEY_JOB_ID to jobId, ReportWorker.KEY_SUCCESS to true, ReportWorker.KEY_PRINTED_AT to printedAtIso))

    override fun reportFailed(jobId: String, reason: String) =
        enqueueReport(jobId, workDataOf(ReportWorker.KEY_JOB_ID to jobId, ReportWorker.KEY_SUCCESS to false, ReportWorker.KEY_ERROR to reason.take(500)))

    private fun enqueueReport(jobId: String, data: androidx.work.Data) = safely("enqueueReport") {
        val request = OneTimeWorkRequestBuilder<ReportWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(online)
            .setInputData(data)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 20, TimeUnit.SECONDS)
            .addTag(TAG_AGENT)
            .build()
        wm.enqueueUniqueWork("report-$jobId", ExistingWorkPolicy.REPLACE, request)
    }

    fun scheduleCrashRecovery() = safely("scheduleCrashRecovery") {
        val request = OneTimeWorkRequestBuilder<WatchdogWorker>()
            .setInitialDelay(10, TimeUnit.SECONDS)
            .setInputData(workDataOf(WatchdogWorker.KEY_REASON to "crash_recovery"))
            .build()
        wm.enqueueUniqueWork(UNIQUE_RECOVERY, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelAll() = safely("cancelAll") { wm.cancelAllWorkByTag(TAG_AGENT); wm.cancelUniqueWork(UNIQUE_WATCHDOG) }

    private inline fun safely(op: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            // WorkManager can throw if its database is corrupted or storage is full.
            AppLog.e(TAG, "WorkManager call failed", t, "op" to op)
        }
    }

    companion object {
        private const val TAG = "WorkScheduler"
        const val TAG_AGENT = "sopifo-agent"
        const val UNIQUE_PENDING_SYNC = "pending-sync"
        const val UNIQUE_HEARTBEAT = "heartbeat"
        const val UNIQUE_WATCHDOG = "watchdog"
        const val UNIQUE_FCM_TOKEN = "fcm-token"
        const val UNIQUE_RECOVERY = "crash-recovery"
        const val HEARTBEAT_INTERVAL_MIN = 5L
        const val PENDING_SYNC_DEBOUNCE_MS = 5_000L
        private val lastPendingSync = AtomicLong(0)
    }
}
