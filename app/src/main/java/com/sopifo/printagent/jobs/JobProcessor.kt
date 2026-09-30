package com.sopifo.printagent.jobs

import com.sopifo.printagent.core.AppLog
import com.sopifo.printagent.data.api.ApiClient
import com.sopifo.printagent.data.api.ApiException
import com.sopifo.printagent.data.api.PrintJobDto
import com.sopifo.printagent.data.api.ServerClock
import com.sopifo.printagent.data.api.UrlPolicy
import com.sopifo.printagent.data.db.RecentJobStatus
import com.sopifo.printagent.print.JobType
import com.sopifo.printagent.print.PrintDataBuilder
import com.sopifo.printagent.print.PrintException
import com.sopifo.printagent.print.PrinterManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

enum class JobOutcome { PRINTED, DUPLICATE, IN_PROGRESS, EXPIRED, NOT_PENDING, NOT_FOUND, INVALID, FAILED }

/** Where completion/failure reports go; implemented with WorkManager so reports survive restarts. */
interface JobReporter {
    fun reportCompleted(jobId: String, printedAtIso: String)
    fun reportFailed(jobId: String, reason: String)
}

/**
 * FCM wake → fetch job → download PNG → print → report. The backend owns every job; this class
 * only decides whether *this device* may print a job right now:
 *  - never twice (idempotency ledger, claimed atomically once the printer link is up),
 *  - never when older than 120 s (server time), re-checked before every attempt,
 *  - never when the backend no longer says "pending".
 * Every failure mode ends in a recorded outcome; nothing here throws except cancellation.
 */
class JobProcessor(
    private val api: ApiClient,
    private val jobs: JobRepository,
    private val printers: PrinterManager,
    private val reporter: JobReporter,
    private val clock: ServerClock,
    private val onBackendSync: suspend () -> Unit = {},
) {
    private val inFlight = HashSet<String>()

    /** Handles an FCM wake-up. Only the ID comes from FCM; everything else is fetched. */
    suspend fun processJobById(jobId: String, source: String): JobOutcome {
        if (!UrlPolicy.isValidJobId(jobId)) {
            AppLog.w(TAG, "Rejected malformed job id", null, "source" to source)
            return JobOutcome.INVALID
        }
        if (jobs.isHandled(jobId)) {
            AppLog.i(TAG, "Ignoring already printed job", "job" to jobId, "source" to source)
            return JobOutcome.DUPLICATE
        }
        val job = try {
            fetchWithRetry(jobId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (e.notFound) return JobOutcome.NOT_FOUND
            if (e.malformed) {
                AppLog.w(TAG, "Malformed job payload", e, "job" to jobId)
                reportFailedSafely(jobId, "malformed job payload")
                return JobOutcome.INVALID
            }
            AppLog.w(TAG, "Could not fetch job", e, "job" to jobId, "http" to e.httpCode)
            return JobOutcome.FAILED
        }
        if (job.id != jobId) {
            AppLog.w(TAG, "Backend returned a different job id", null, "requested" to jobId, "got" to job.id)
            return JobOutcome.INVALID
        }
        onBackendSync()
        return process(job, source)
    }

    /** Pending-job recovery: prints whatever the backend still has pending for this device (< 120 s). */
    suspend fun syncPending(source: String): Map<JobOutcome, Int> {
        val result = try {
            api.getPendingJobs()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "Pending sync failed", e, "source" to source)
            throw e
        }
        onBackendSync()
        if (result.invalidEntries > 0) AppLog.w(TAG, "Dropped malformed pending entries", null, "count" to result.invalidEntries)
        AppLog.i(TAG, "Pending sync", "source" to source, "jobs" to result.jobs.size)
        val outcomes = mutableMapOf<JobOutcome, Int>()
        // Oldest first, so jobs come out in the order they were created.
        val ordered = result.jobs.sortedBy { JobFreshness.parseTimestamp(it.createdAt)?.toEpochMilli() ?: Long.MAX_VALUE }
        for (job in ordered) {
            val outcome = process(job, source)
            outcomes[outcome] = (outcomes[outcome] ?: 0) + 1
        }
        return outcomes
    }

    /** Jobs claimed but never confirmed (process died mid-print) are reported, not reprinted. */
    suspend fun recoverInterrupted() {
        try {
            for (jobId in jobs.interruptedClaims(olderThanMs = 60_000)) {
                if (synchronized(inFlight) { jobId in inFlight }) continue
                AppLog.w(TAG, "Job was interrupted mid-print; not reprinting", null, "job" to jobId)
                jobs.markPrinted(jobId)
                jobs.recordRecent(jobId, "unknown", RecentJobStatus.INTERRUPTED, "App stopped while printing")
                reportFailedSafely(jobId, "interrupted while printing; output may be incomplete")
            }
            jobs.prune()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e(TAG, "Interrupted-job recovery failed", e)
        }
    }

    private suspend fun process(job: PrintJobDto, source: String): JobOutcome {
        if (!UrlPolicy.isValidJobId(job.id)) return JobOutcome.INVALID
        if (!synchronized(inFlight) { inFlight.add(job.id) }) return JobOutcome.IN_PROGRESS
        try {
            return processLocked(job, source)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Storage errors and anything unforeseen: record and move on, never crash.
            AppLog.e(TAG, "Unexpected error processing job", e, "job" to job.id)
            runCatching { jobs.recordRecent(job.id, job.type, RecentJobStatus.FAILED, e.message) }
            return JobOutcome.FAILED
        } finally {
            synchronized(inFlight) { inFlight.remove(job.id) }
        }
    }

    private suspend fun processLocked(job: PrintJobDto, source: String): JobOutcome {
        if (jobs.isHandled(job.id)) return JobOutcome.DUPLICATE

        val type = JobType.fromWire(job.type)
        val createdAt = JobFreshness.parseTimestamp(job.createdAt)
        val invalidReason = when {
            type == null -> "unknown job type '${job.type.take(40)}'"
            createdAt == null -> "missing or invalid created_at"
            job.imageUrl.isNullOrBlank() -> "missing image_url"
            job.copies < 1 -> "invalid copies"
            else -> null
        }
        if (invalidReason != null) {
            AppLog.w(TAG, "Invalid job", null, "job" to job.id, "reason" to invalidReason)
            jobs.recordRecent(job.id, job.type.take(40), RecentJobStatus.FAILED, invalidReason)
            reportFailedSafely(job.id, invalidReason)
            return JobOutcome.INVALID
        }
        type!!; createdAt!!

        if (!job.status.equals("pending", ignoreCase = true)) {
            AppLog.i(TAG, "Job no longer pending", "job" to job.id, "status" to job.status)
            return JobOutcome.NOT_PENDING
        }
        val fresh = { JobFreshness.isFresh(createdAt, clock.now()) }
        if (!fresh()) {
            AppLog.i(TAG, "Skipping expired job", "job" to job.id, "age_s" to JobFreshness.ageSeconds(createdAt, clock.now()))
            jobs.recordRecent(job.id, type.wireName, RecentJobStatus.SKIPPED_EXPIRED, "Older than 2 minutes")
            return JobOutcome.EXPIRED
        }

        val printer = printers.config(type.printer)
        if (printer == null) {
            val reason = "no ${type.printer.name.lowercase()} printer configured"
            jobs.recordRecent(job.id, type.wireName, RecentJobStatus.FAILED, reason)
            reportFailedSafely(job.id, reason)
            return JobOutcome.FAILED
        }

        jobs.recordRecent(job.id, type.wireName, RecentJobStatus.PRINTING)
        AppLog.i(TAG, "Printing job", "job" to job.id, "type" to type.wireName, "source" to source)

        val data = try {
            val png = downloadWithRetry(job.imageUrl!!)
            printers.buildFromPng(png, printer, job.copies)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = when (e) {
                is ApiException -> "image download failed: ${e.message}"
                is PrintException -> "bad image: ${e.message}"
                else -> "image processing failed: ${e.message}"
            }
            AppLog.w(TAG, "Job image unusable", e, "job" to job.id)
            jobs.recordRecent(job.id, type.wireName, RecentJobStatus.FAILED, reason)
            reportFailedSafely(job.id, reason)
            return JobOutcome.FAILED
        }

        var claimed = false
        var alreadyClaimed = false
        try {
            printers.print(
                printer = printer,
                data = data,
                shouldContinue = fresh,
                beforeWrite = {
                    if (!fresh()) throw AbortPrint("job expired before printer was reachable")
                    if (!jobs.claim(job.id)) {
                        alreadyClaimed = true
                        throw AbortPrint("already claimed")
                    }
                    claimed = true
                },
            )
        } catch (e: CancellationException) {
            if (claimed) runCatching { jobs.markPrinted(job.id) }
            throw e
        } catch (e: Exception) {
            if (alreadyClaimed) return JobOutcome.DUPLICATE
            if (claimed) {
                // Some bytes may have printed; never retry automatically. A reprint is a new job ID.
                jobs.markPrinted(job.id)
            }
            val reason = if (!fresh()) "expired before it could be printed" else "print failed: ${e.message}"
            AppLog.w(TAG, "Print failed", e, "job" to job.id, "claimed" to claimed)
            jobs.recordRecent(job.id, type.wireName, RecentJobStatus.FAILED, reason)
            reportFailedSafely(job.id, reason)
            return JobOutcome.FAILED
        }

        jobs.markPrinted(job.id)
        jobs.recordRecent(job.id, type.wireName, RecentJobStatus.PRINTED)
        AppLog.i(TAG, "Job printed", "job" to job.id)
        runCatching { reporter.reportCompleted(job.id, clock.now().toString()) }
            .onFailure { AppLog.e(TAG, "Could not schedule completion report", it, "job" to job.id) }
        return JobOutcome.PRINTED
    }

    private suspend fun fetchWithRetry(jobId: String): PrintJobDto {
        var last: ApiException? = null
        repeat(3) { attempt ->
            try {
                return api.getJob(jobId)
            } catch (e: ApiException) {
                if (!e.retryable) throw e
                last = e
                delay(1_000L * (attempt + 1))
            }
        }
        throw last!!
    }

    private suspend fun downloadWithRetry(url: String): ByteArray {
        var last: ApiException? = null
        repeat(3) { attempt ->
            try {
                return api.downloadImage(url, PrintDataBuilder.MAX_DOWNLOAD_BYTES)
            } catch (e: ApiException) {
                if (!e.retryable) throw e
                last = e
                delay(1_000L * (attempt + 1))
            }
        }
        throw last!!
    }

    private fun reportFailedSafely(jobId: String, reason: String) {
        runCatching { reporter.reportFailed(jobId, reason) }
            .onFailure { AppLog.e(TAG, "Could not schedule failure report", it, "job" to jobId) }
    }

    /** Thrown from the claim hook to stop before any byte is sent; the printer itself is fine. */
    private class AbortPrint(message: String) : Exception(message)

    private companion object {
        const val TAG = "JobProcessor"
    }
}
