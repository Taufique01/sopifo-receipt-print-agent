package com.sopifo.printagent.runs

import com.sopifo.printagent.core.AppLog
import com.sopifo.printagent.data.api.PrintResultItemDto
import com.sopifo.printagent.data.api.PrintResultsRequest
import com.sopifo.printagent.data.db.PrintRunDao
import com.sopifo.printagent.data.db.RunItemStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sends the outcomes of printed orders and parcels to the server (Cloud Print history and the
 * "printed" marks). Unsent results stay on the phone and go out on the next try; the server
 * ignores repeats, so a lost answer never double-counts.
 */
class RunReporter(
    private val dao: PrintRunDao,
    private val send: suspend (PrintResultsRequest) -> Unit,
) {
    private val lock = Mutex()

    /** Sends everything unsent; true when nothing is left (else try again later). */
    suspend fun reportPending(): Boolean = lock.withLock {
        try {
            var items = dao.unreported()
            while (items.isNotEmpty()) {
                for ((runId, group) in items.groupBy { it.runId }) {
                    send(
                        PrintResultsRequest(
                            run = runId,
                            items = group.map {
                                PrintResultItemDto(
                                    kind = it.kind,
                                    id = it.refId,
                                    doc = it.doc,
                                    status = wire(it.status),
                                    reason = it.reason?.take(300),
                                    attempt = it.attempt,
                                )
                            },
                        ),
                    )
                    dao.markReported(runId, group.map { it.idx })
                }
                items = dao.unreported()
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "Couldn't send print results yet", e)
            false
        }
    }

    private fun wire(status: RunItemStatus): String = when (status) {
        RunItemStatus.PRINTED -> "printed"
        RunItemStatus.INCOMPLETE -> "incomplete"
        RunItemStatus.NOT_PRINTED -> "not_printed"
        else -> "failed"
    }

    private companion object {
        const val TAG = "RunReporter"
    }
}
