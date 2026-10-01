package com.sopifo.printagent.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.sopifo.printagent.SopifoApp
import com.sopifo.printagent.core.AppLog
import com.sopifo.printagent.data.api.ApiException
import com.sopifo.printagent.data.api.PrinterStatusPayload
import com.sopifo.printagent.data.db.PrinterRole
import com.sopifo.printagent.service.Notifications
import com.sopifo.printagent.service.ServiceController
import kotlinx.coroutines.CancellationException

/** Shared plumbing: dependency access, a foreground notification for expedited work on API < 31, and a no-crash guard. */
abstract class AgentWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    protected val container get() = (applicationContext as SopifoApp).container

    override suspend fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(Notifications.WORK_NOTIFICATION_ID, Notifications.workNotification(applicationContext))

    final override suspend fun doWork(): Result = try {
        if (!container.device.hasSession()) {
            Result.success()
        } else {
            work()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        AppLog.e(javaClass.simpleName, "Worker failed unexpectedly", t)
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }

    protected abstract suspend fun work(): Result
}

class JobWorker(context: Context, params: WorkerParameters) : AgentWorker(context, params) {
    override suspend fun work(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val source = inputData.getString(KEY_SOURCE) ?: "fcm"
        val outcome = container.processor.processJobById(jobId, source)
        AppLog.i("JobWorker", "Job handled", "job" to jobId, "outcome" to outcome)
        return Result.success()
    }

    companion object {
        const val KEY_JOB_ID = "job_id"
        const val KEY_SOURCE = "source"
    }
}

class PendingSyncWorker(context: Context, params: WorkerParameters) : AgentWorker(context, params) {
    override suspend fun work(): Result {
        val reason = inputData.getString(KEY_REASON) ?: "unknown"
        container.processor.recoverInterrupted()
        return try {
            val outcomes = container.processor.syncPending(reason)
            AppLog.i("PendingSync", "Pending sync done", "reason" to reason, "outcomes" to outcomes)
            Result.success()
        } catch (e: ApiException) {
            // Retrying later than ~2 minutes is pointless: anything pending would have expired.
            if (e.retryable && runAttemptCount < 3) Result.retry() else Result.success()
        }
    }

    companion object {
        const val KEY_REASON = "reason"
    }
}

/** Status heartbeat every 5 minutes. Always schedules its successor, whatever happens. */
class HeartbeatWorker(context: Context, params: WorkerParameters) : AgentWorker(context, params) {
    override suspend fun work(): Result {
        try {
            ServiceController.start(applicationContext, "heartbeat")
            val printers = container.printers
            if (container.bluetooth.isBluetoothOn() && container.bluetooth.hasConnectPermission()) printers.probeAll(respectBackoff = true) else printers.refreshPassive()
            val status = printers.status.value
            container.device.sendHeartbeat(
                PrinterStatusPayload(
                    receipt = status.getValue(PrinterRole.RECEIPT).wire,
                    label = status.getValue(PrinterRole.LABEL).wire,
                ),
            )
            container.device.config()?.let { if (!it.fcmTokenSynced) container.scheduler.enqueueFcmTokenSync(null) }
            AppLog.d("Heartbeat", "Heartbeat sent")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w("Heartbeat", "Heartbeat failed", e)
        } finally {
            container.scheduler.scheduleNextHeartbeat()
        }
        return Result.success()
    }
}

/** Delivers completion/failure reports; retried with backoff across restarts until the backend accepts them. */
class ReportWorker(context: Context, params: WorkerParameters) : AgentWorker(context, params) {
    override suspend fun work(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val success = inputData.getBoolean(KEY_SUCCESS, false)
        return try {
            if (success) {
                container.api.completeJob(jobId, inputData.getString(KEY_PRINTED_AT) ?: java.time.Instant.now().toString())
            } else {
                container.api.failJob(jobId, inputData.getString(KEY_ERROR) ?: "print failed")
            }
            AppLog.i("Report", "Job status reported", "job" to jobId, "success" to success)
            Result.success()
        } catch (e: ApiException) {
            when {
                // Job gone, cancelled or already final on the backend: nothing left to report.
                e.httpCode == 404 || e.httpCode == 409 || e.httpCode == 410 || e.httpCode == 422 -> Result.success()
                runAttemptCount >= MAX_ATTEMPTS -> {
                    AppLog.e("Report", "Giving up reporting job status", e, "job" to jobId)
                    Result.failure()
                }
                else -> Result.retry()
            }
        }
    }

    companion object {
        const val KEY_JOB_ID = "job_id"
        const val KEY_SUCCESS = "success"
        const val KEY_PRINTED_AT = "printed_at"
        const val KEY_ERROR = "error"
        const val MAX_ATTEMPTS = 20
    }
}

class FcmTokenWorker(context: Context, params: WorkerParameters) : AgentWorker(context, params) {
    override suspend fun work(): Result = try {
        container.device.syncFcmToken(inputData.getString(KEY_TOKEN))
        Result.success()
    } catch (e: ApiException) {
        if (runAttemptCount < 10) Result.retry() else Result.failure()
    }

    companion object {
        const val KEY_TOKEN = "token"
    }
}

/** Periodic safety net and post-crash recovery. */
class WatchdogWorker(context: Context, params: WorkerParameters) : AgentWorker(context, params) {
    override suspend fun work(): Result {
        val reason = inputData.getString(KEY_REASON) ?: "watchdog"
        ServiceController.start(applicationContext, reason)
        container.scheduler.ensureHeartbeat()
        if (reason == "crash_recovery") container.scheduler.enqueuePendingSync(reason, force = true)
        return Result.success()
    }

    companion object {
        const val KEY_REASON = "reason"
    }
}
