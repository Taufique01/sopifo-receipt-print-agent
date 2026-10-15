package com.sopifo.printagent.jobs

import com.sopifo.printagent.core.AppLog
import com.sopifo.printagent.data.api.ApiClient
import com.sopifo.printagent.data.api.ApiException
import com.sopifo.printagent.data.api.PrintBatchDto
import com.sopifo.printagent.data.api.PrintJobDto
import com.sopifo.printagent.data.api.ServerClock
import com.sopifo.printagent.data.api.UrlPolicy
import com.sopifo.printagent.data.db.RecentJobStatus
import com.sopifo.printagent.print.JobType
import com.sopifo.printagent.print.PrintData
import com.sopifo.printagent.print.PrintDataBuilder
import com.sopifo.printagent.print.PrintException
import com.sopifo.printagent.print.PrinterManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.TreeMap

enum class JobOutcome { PRINTED, DUPLICATE, IN_PROGRESS, EXPIRED, NOT_PENDING, NOT_FOUND, INVALID, FAILED }

enum class BatchOutcome { DONE, CANCELLED, EXPIRED, STALLED, PRINTER_FAILED, IN_PROGRESS, NOT_FOUND, INVALID, FAILED }

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
    /** Pending sync found live bulk print batches; the caller schedules [processBatch] for each. */
    private val onBatchesPending: (List<String>) -> Unit = {},
    private val onBackendSync: suspend () -> Unit = {},
) {
    private val inFlight = HashSet<String>()
    private val activeBatches = HashSet<String>()

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
        if (result.batches.isNotEmpty()) onBatchesPending(result.batches)
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
                data = data.bytes,
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
        jobs.recordRecent(job.id, type.wireName, RecentJobStatus.PRINTED, data.note)
        AppLog.i(TAG, "Job printed", "job" to job.id)
        data.note?.let { AppLog.w(TAG, "Paper width mismatch", null, "job" to job.id, "note" to it) }
        runCatching { reporter.reportCompleted(job.id, clock.now().toString()) }
            .onFailure { AppLog.e(TAG, "Could not schedule completion report", it, "job" to job.id) }
        return JobOutcome.PRINTED
    }

    /**
     * Bulk print. Prints the batch's jobs in order over ONE printer connection, while images
     * download ahead in parallel. After every printed job it reports at once and re-fetches the
     * batch in the background; that fetch makes the backend render the next jobs and keep the
     * batch alive, so the printer rarely waits. Same safety rules as single jobs: the ledger
     * prevents duplicates, nothing prints after the batch expires, a failed write is not retried.
     * Resumable: if the worker is stopped, running it again continues from the backend's state.
     */
    suspend fun processBatch(batchId: String, source: String): BatchOutcome {
        if (!UrlPolicy.isValidJobId(batchId)) return BatchOutcome.INVALID
        if (!synchronized(activeBatches) { activeBatches.add(batchId) }) return BatchOutcome.IN_PROGRESS
        return try {
            coroutineScope { BatchRun(this, batchId, source).run() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e(TAG, "Batch failed unexpectedly", e, "batch" to batchId)
            BatchOutcome.FAILED
        } finally {
            synchronized(activeBatches) { activeBatches.remove(batchId) }
        }
    }

    private inner class BatchRun(
        private val scope: CoroutineScope,
        private val batchId: String,
        private val source: String,
    ) {
        /** Jobs waiting to print, by batch index. */
        private val queue = TreeMap<Int, PrintJobDto>()
        /** Downloaded + encoded print data, prepared ahead of the printer. */
        private val prepared = HashMap<String, Deferred<Result<PrintData>>>()
        /** Jobs this run already printed or failed (their report may not have landed yet). */
        private val done = HashSet<String>()
        private var latest: PrintBatchDto? = null
        private var fetch: Deferred<PrintBatchDto?>? = null
        private var printed = 0

        suspend fun run(): BatchOutcome {
            absorb(fetchBatch() ?: return BatchOutcome.NOT_FOUND)
            AppLog.i(TAG, "Batch started", "batch" to batchId, "total" to latest?.total, "source" to source)
            var idleFetches = 0
            while (true) {
                statusOutcome()?.let { return finish(it) }
                if (queue.isEmpty()) {
                    if (latest!!.renderedAll) return finish(BatchOutcome.DONE)
                    // The backend is still rendering; each fetch renders more.
                    if (++idleFetches > MAX_IDLE_FETCHES) return finish(BatchOutcome.STALLED)
                    delay(IDLE_FETCH_DELAY_MS)
                    absorb(awaitFetch() ?: return finish(BatchOutcome.FAILED))
                    continue
                }
                idleFetches = 0
                val first = queue.firstEntry()!!.value
                val type = JobType.fromWire(first.type)
                if (type == null) {
                    queue.pollFirstEntry()
                    done += first.id
                    prepared.remove(first.id)?.cancel()
                    fail(first, "unknown job type '${first.type.take(40)}'")
                    continue
                }
                val printer = printers.config(type.printer)
                if (printer == null) {
                    // Every job in the batch needs this printer: report the first, stop the batch.
                    fail(first, "no ${type.printer.name.lowercase()} printer configured")
                    return finish(BatchOutcome.PRINTER_FAILED)
                }
                try {
                    printers.session(printer, shouldContinue = ::fresh) { connection ->
                        while (true) {
                            if (queue.isEmpty()) {
                                // Keep the link open briefly for jobs the in-flight fetch brings.
                                val more = withTimeoutOrNull(KEEP_OPEN_MS) { awaitFetch() } ?: break
                                absorb(more)
                                if (queue.isEmpty() || statusOutcome() != null) break
                            }
                            val job = queue.firstEntry()!!.value
                            if (JobType.fromWire(job.type)?.printer != type.printer) break
                            if (statusOutcome() != null) break
                            queue.pollFirstEntry()
                            printOne(job, type) { connection.write(it) }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: PrintException) {
                    AppLog.w(TAG, "Batch printer failure", e, "batch" to batchId)
                    queue.firstEntry()?.value?.let { if (!jobs.isHandled(it.id)) fail(it, "printer not reachable: ${e.message}") }
                    return finish(BatchOutcome.PRINTER_FAILED)
                } catch (e: StopBatch) {
                    return finish(BatchOutcome.PRINTER_FAILED)
                }
            }
        }

        private suspend fun printOne(job: PrintJobDto, type: JobType, write: suspend (ByteArray) -> Unit) {
            done += job.id
            if (jobs.isHandled(job.id)) return
            val data = prepared.remove(job.id)!!.await().getOrElse { e ->
                fail(job, if (e is ApiException) "image download failed: ${e.message}" else "image unusable: ${e.message}")
                return
            }
            if (!jobs.claim(job.id)) return
            jobs.recordRecent(job.id, type.wireName, RecentJobStatus.PRINTING)
            try {
                write(data.bytes)
            } catch (e: PrintException) {
                // Some bytes may have printed: never retried; the seller can retry this order.
                jobs.markPrinted(job.id)
                jobs.recordRecent(job.id, type.wireName, RecentJobStatus.FAILED, "print failed: ${e.message}")
                reportFailedSafely(job.id, "print failed: ${e.message}")
                throw StopBatch()
            }
            jobs.markPrinted(job.id)
            jobs.recordRecent(job.id, type.wireName, RecentJobStatus.PRINTED, data.note)
            printed++
            scope.launch { reportCompletedNow(job.id) }
            refreshInBackground()
        }

        /** Queues what the last background fetch brought, then fetches again (renders more). */
        private suspend fun refreshInBackground() {
            fetch?.takeIf { it.isCompleted }?.let { finished ->
                fetch = null
                finished.await()?.let { absorb(it) }
            }
            startFetch()
        }

        /** Adds newly rendered jobs to the queue and starts preparing their print data. */
        private suspend fun absorb(batch: PrintBatchDto) {
            latest = batch
            for (job in batch.jobs) {
                if (!UrlPolicy.isValidJobId(job.id) || !job.status.equals("pending", ignoreCase = true)) continue
                if (job.id in done || job.id in prepared || jobs.isHandled(job.id)) continue
                val index = job.batchIndex ?: continue
                queue[index] = job
                val printer = JobType.fromWire(job.type)?.let { printers.config(it.printer) }
                val url = job.imageUrl
                prepared[job.id] = if (printer == null || url.isNullOrBlank() || job.copies < 1) {
                    // Fails when its turn comes (the run loop stops first if the printer is missing).
                    CompletableDeferred(Result.failure(IllegalStateException("missing image or printer")))
                } else {
                    scope.async { runCatching { printers.buildFromPng(downloadWithRetry(url), printer, job.copies) } }
                }
            }
        }

        private fun startFetch() {
            if (fetch?.isActive == true) return
            fetch = scope.async { runCatching { api.getBatch(batchId) }.getOrNull() }
        }

        private suspend fun awaitFetch(): PrintBatchDto? {
            startFetch()
            return fetch!!.await().also { fetch = null }
        }

        private suspend fun fetchBatch(): PrintBatchDto? {
            repeat(3) { attempt ->
                try {
                    return api.getBatch(batchId).also { onBackendSync() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiException) {
                    if (e.notFound || !e.retryable) {
                        AppLog.w(TAG, "Could not fetch batch", e, "batch" to batchId)
                        return null
                    }
                    delay(1_000L * (attempt + 1))
                }
            }
            return null
        }

        private fun statusOutcome(): BatchOutcome? = when (latest?.status?.lowercase()) {
            "active" -> if (fresh()) null else BatchOutcome.EXPIRED
            "cancelled" -> BatchOutcome.CANCELLED
            "expired" -> BatchOutcome.EXPIRED
            else -> BatchOutcome.FAILED
        }

        private fun fresh(): Boolean {
            val expiresAt: Instant = JobFreshness.parseTimestamp(latest?.expiresAt) ?: return false
            return !clock.now().isAfter(expiresAt)
        }

        private suspend fun fail(job: PrintJobDto, reason: String) {
            AppLog.w(TAG, "Batch job failed", null, "batch" to batchId, "job" to job.id, "reason" to reason)
            jobs.recordRecent(job.id, job.type.take(40), RecentJobStatus.FAILED, reason)
            reportFailedSafely(job.id, reason)
        }

        private fun finish(outcome: BatchOutcome): BatchOutcome {
            prepared.values.forEach { it.cancel() }
            fetch?.cancel()
            AppLog.i(TAG, "Batch finished", "batch" to batchId, "outcome" to outcome, "printed" to printed)
            return outcome
        }
    }

    /** Stops the batch after a write failure (the printer needs attention). */
    private class StopBatch : Exception()

    /** Reports straight away so the next batch fetch sees it; the durable report is the fallback. */
    private suspend fun reportCompletedNow(jobId: String) {
        val at = clock.now().toString()
        try {
            api.completeJob(jobId, at)
        } catch (e: CancellationException) {
            runCatching { reporter.reportCompleted(jobId, at) }
            throw e
        } catch (e: Exception) {
            if (e is ApiException && (e.notFound || e.httpCode == 409)) return
            runCatching { reporter.reportCompleted(jobId, at) }
                .onFailure { AppLog.e(TAG, "Could not schedule completion report", it, "job" to jobId) }
        }
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
        /** Empty fetches (backend still rendering) before giving up; the batch stays resumable. */
        const val MAX_IDLE_FETCHES = 40
        const val IDLE_FETCH_DELAY_MS = 750L
        /** How long an open printer link waits for the next job before closing. */
        const val KEEP_OPEN_MS = 8_000L
    }
}
