package com.sopifo.printagent.jobs

import com.sopifo.printagent.data.db.PrintedJobDao
import com.sopifo.printagent.data.db.PrintedJobEntity
import com.sopifo.printagent.data.db.PrintedState
import com.sopifo.printagent.data.db.RecentJobDao
import com.sopifo.printagent.data.db.RecentJobEntity
import kotlinx.coroutines.flow.Flow

/** Local job bookkeeping: the idempotency ledger and the 20-row display history. Not a queue. */
class JobRepository(
    private val printedDao: PrintedJobDao,
    private val recentDao: RecentJobDao,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun observeRecent(): Flow<List<RecentJobEntity>> = recentDao.observe()

    suspend fun isHandled(jobId: String): Boolean = printedDao.get(jobId) != null

    /** Atomically claims a job for printing. False means it was already printed (or is printing). */
    suspend fun claim(jobId: String): Boolean =
        printedDao.insertIfAbsent(PrintedJobEntity(jobId, PrintedState.PRINTING, now())) != -1L

    suspend fun markPrinted(jobId: String) = printedDao.setState(jobId, PrintedState.PRINTED, now())

    /** Releases a claim when nothing was sent, so the job may be retried. */
    suspend fun release(jobId: String) = printedDao.delete(jobId)

    suspend fun interruptedClaims(olderThanMs: Long): List<String> =
        printedDao.withState(PrintedState.PRINTING).filter { now() - it.updatedAt > olderThanMs }.map { it.jobId }

    suspend fun recordRecent(jobId: String, type: String, status: String, message: String? = null) =
        recentDao.upsert(RecentJobEntity(jobId, type, status, message?.take(200), now()))

    /** Ledger rows only need to outlive the 2-minute window by a wide margin. */
    suspend fun prune(retentionMs: Long = RETENTION_MS) = printedDao.pruneOlderThan(now() - retentionMs)

    companion object {
        const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000
    }
}
