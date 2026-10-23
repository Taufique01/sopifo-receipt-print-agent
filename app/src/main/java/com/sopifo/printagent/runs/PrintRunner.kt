package com.sopifo.printagent.runs

import com.sopifo.printagent.core.AppLog
import com.sopifo.printagent.data.api.ApiException
import com.sopifo.printagent.data.db.PrintRunDao
import com.sopifo.printagent.data.db.PrintRunEntity
import com.sopifo.printagent.data.db.PrintRunItemEntity
import com.sopifo.printagent.data.db.PrinterConfigEntity
import com.sopifo.printagent.data.db.PrinterRole
import com.sopifo.printagent.data.db.RunItemStatus
import com.sopifo.printagent.data.db.RunState
import com.sopifo.printagent.print.PrintException
import com.sopifo.printagent.print.PrintFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The printer side of a run: which printer has a role, and one open connection to it. */
interface RunPrinterPort {
    suspend fun config(role: PrinterRole): PrinterConfigEntity?

    /** Opens the printer (with retries) and lets [block] write; throws PrintException if unreachable. */
    suspend fun <T> session(printer: PrinterConfigEntity, block: suspend (write: suspend (ByteArray) -> Unit) -> T): T
}

/** Turns an item into printer bytes: fetches the order / parcel image, or reads a quick print. */
interface RunContent {
    suspend fun prepare(item: PrintRunItemEntity, printer: PrinterConfigEntity): ByteArray
}

/** Progress and outcome for the seller while the phone is locked (notifications in the app). */
interface RunNotifier {
    fun progress(run: PrintRunEntity, done: Int) {}
    fun finished(run: PrintRunEntity) {}
}

/** A new item for [PrintRunner.start]. */
data class NewRunItem(val kind: String, val refId: String, val doc: String, val title: String)

/**
 * Prints what the seller picked in the app — straight to the printer, no job or push: each
 * item's image is fetched (a few ahead, while the printer works) and sent over one connection.
 *
 * Every item ends with a recorded outcome, saved as it happens so a crash loses nothing:
 *  - its image can't be loaded → FAILED, and the run carries on with the next one;
 *  - the link drops while sending → INCOMPLETE (part may have printed); the runner reconnects
 *    and carries on from the next item, never reprinting it by itself;
 *  - the printer can't be reached (at the start, or after the reconnects) → the rest NOT_PRINTED;
 *  - the seller stops the run → the rest NOT_PRINTED.
 * "Retry" puts the unsuccessful items back as a new attempt; reprints are always the seller's
 * choice. Results of orders and parcels go to the server through [onResults].
 */
class PrintRunner(
    private val dao: PrintRunDao,
    private val printers: RunPrinterPort,
    private val content: RunContent,
    private val scope: CoroutineScope,
    private val notifier: RunNotifier = object : RunNotifier {},
    /** Called when outcomes were recorded: send them to the server. */
    private val onResults: () -> Unit = {},
    /** Called before a run starts: keep the app alive while the phone is locked. */
    private val keepAlive: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val reconnectDelayMs: Long = 2_000,
) {
    private val _running = MutableStateFlow<String?>(null)
    /** The run printing right now, if any. */
    val running: StateFlow<String?> = _running.asStateFlow()

    /** One run at a time: printers are shared, and runs queue behind each other in order. */
    private val gate = Mutex()
    private val stopRequested: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Runs started in this process: printing now, or queued behind the one that is. */
    private val active: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Records a new run and starts printing it; returns its id. */
    suspend fun start(title: String, source: String, role: PrinterRole, items: List<NewRunItem>): String {
        require(items.isNotEmpty()) { "Nothing to print" }
        val id = UUID.randomUUID().toString()
        val now = clock()
        dao.insertRun(PrintRunEntity(id, title, source, role, RunState.RUNNING, items.size, createdAt = now, updatedAt = now))
        dao.insertItems(
            items.mapIndexed { i, it ->
                PrintRunItemEntity(id, i, it.kind, it.refId, it.doc, it.title, RunItemStatus.PENDING, updatedAt = now)
            },
        )
        launch(id)
        return id
    }

    /** Prints the run's failed, incomplete and not-printed items again. */
    suspend fun retry(runId: String) {
        if (dao.requeueUnsuccessful(runId, clock()) == 0) return
        val run = dao.getRun(runId) ?: return
        dao.setRun(runId, RunState.RUNNING, run.printed, run.failed, null, clock())
        launch(runId)
    }

    /** Carries on an interrupted run from where it stopped. */
    suspend fun resume(runId: String) {
        val run = dao.getRun(runId) ?: return
        dao.setRun(runId, RunState.RUNNING, run.printed, run.failed, null, clock())
        launch(runId)
    }

    /** Stops after the item being sent; the rest are recorded as not printed. */
    fun stop(runId: String) {
        stopRequested += runId
    }

    /**
     * After the app was killed mid-run: the item being sent may be part-printed, and the run
     * waits for the seller to continue it (never resumed by itself).
     */
    suspend fun recoverInterrupted() {
        for (run in dao.runsIn(RunState.RUNNING)) {
            if (run.id in active) continue
            val now = clock()
            dao.items(run.id).filter { it.status == RunItemStatus.SENDING }.forEach {
                dao.setItem(run.id, it.idx, RunItemStatus.INCOMPLETE, "The app was closed while printing this", now)
            }
            val counts = counts(run.id)
            dao.setRun(run.id, RunState.INTERRUPTED, counts.first, counts.second, "The app was closed before this finished", now)
        }
        onResults()
    }

    /** Old runs go after 30 days. */
    suspend fun prune() {
        dao.deleteRunsBefore(clock() - KEEP_MS)
        dao.deleteOrphanItems()
    }

    private fun launch(runId: String) {
        keepAlive()
        active += runId
        scope.launch {
            gate.withLock {
                _running.value = runId
                try {
                    execute(runId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.e(TAG, "Run failed unexpectedly", e, "run" to runId)
                    finish(runId, RunState.STOPPED, "Printing stopped: ${e.message}")
                } finally {
                    stopRequested -= runId
                    active -= runId
                    _running.value = null
                }
            }
        }
    }

    private suspend fun execute(runId: String) {
        val run = dao.getRun(runId) ?: return
        val printer = printers.config(run.printerRole)
        if (printer == null) {
            val reason = "No ${run.printerRole.name.lowercase()} printer is set up"
            notPrinted(runId, reason)
            return finish(runId, RunState.STOPPED, reason)
        }
        AppLog.i(TAG, "Run started", "run" to runId, "title" to run.title)
        var reconnects = 0
        while (true) {
            val pending = dao.items(runId).filter { it.status == RunItemStatus.PENDING }
            if (pending.isEmpty()) return finish(runId, RunState.DONE, null)
            try {
                printers.session(printer) { write -> printItems(run, printer, pending, write) }
                // Everything left was sent (or failed to load); loop once more to finish.
            } catch (e: Disconnected) {
                if (++reconnects > MAX_RECONNECTS) {
                    val reason = "The printer disconnected and didn't come back"
                    notPrinted(runId, reason)
                    return finish(runId, RunState.STOPPED, reason)
                }
                AppLog.w(TAG, "Printer disconnected; reconnecting", e.cause, "run" to runId, "try" to reconnects)
                delay(reconnectDelayMs)
            } catch (e: StopRun) {
                notPrinted(runId, "Stopped")
                return finish(runId, RunState.STOPPED, "Stopped")
            } catch (e: PrintException) {
                // Couldn't connect at all (after the printer manager's own retries).
                val reason = friendly(e)
                notPrinted(runId, reason)
                return finish(runId, RunState.STOPPED, reason)
            }
        }
    }

    /** Sends [items] in order over one open connection, preparing the next few meanwhile. */
    private suspend fun printItems(
        run: PrintRunEntity,
        printer: PrinterConfigEntity,
        items: List<PrintRunItemEntity>,
        write: suspend (ByteArray) -> Unit,
    ) = coroutineScope {
        val ready = ArrayDeque<Pair<PrintRunItemEntity, Deferred<Result<ByteArray>>>>()
        var next = 0
        fun fill() {
            while (ready.size < PREPARE_AHEAD && next < items.size) {
                val item = items[next++]
                ready.addLast(item to async { runCatching { content.prepare(item, printer) } })
            }
        }
        fill()
        try {
            while (ready.isNotEmpty()) {
                if (run.id in stopRequested) throw StopRun()
                val (item, data) = ready.removeFirst()
                fill()
                val bytes = data.await().getOrElse { e ->
                    if (e is CancellationException) throw e
                    setItem(run, item, RunItemStatus.FAILED, friendly(e))
                    continue
                }
                setItem(run, item, RunItemStatus.SENDING, null)
                try {
                    write(bytes)
                } catch (e: PrintException) {
                    setItem(run, item, RunItemStatus.INCOMPLETE, "The printer disconnected while printing this")
                    throw Disconnected(e)
                }
                setItem(run, item, RunItemStatus.PRINTED, null)
            }
        } finally {
            ready.forEach { it.second.cancel() }
        }
    }

    private suspend fun setItem(run: PrintRunEntity, item: PrintRunItemEntity, status: RunItemStatus, reason: String?) {
        dao.setItem(run.id, item.idx, status, reason, clock())
        if (status == RunItemStatus.SENDING) return
        val (printed, failed) = counts(run.id)
        dao.setRun(run.id, RunState.RUNNING, printed, failed, null, clock())
        notifier.progress(run.copy(printed = printed, failed = failed), printed + failed)
        if ((printed + failed) % REPORT_EVERY == 0) onResults()
    }

    private suspend fun notPrinted(runId: String, reason: String) {
        val now = clock()
        dao.items(runId).filter { it.status == RunItemStatus.PENDING }.forEach {
            dao.setItem(runId, it.idx, RunItemStatus.NOT_PRINTED, reason, now)
        }
    }

    private suspend fun finish(runId: String, state: RunState, message: String?) {
        val (printed, failed) = counts(runId)
        dao.setRun(runId, state, printed, failed, message, clock())
        dao.getRun(runId)?.let(notifier::finished)
        AppLog.i(TAG, "Run finished", "run" to runId, "state" to state, "printed" to printed, "failed" to failed)
        onResults()
    }

    /** Printed and unsuccessful items so far. */
    private suspend fun counts(runId: String): Pair<Int, Int> {
        val items = dao.items(runId)
        val printed = items.count { it.status == RunItemStatus.PRINTED }
        val failed = items.count {
            it.status == RunItemStatus.FAILED || it.status == RunItemStatus.INCOMPLETE || it.status == RunItemStatus.NOT_PRINTED
        }
        return printed to failed
    }

    private class Disconnected(cause: PrintException) : Exception(cause)
    private class StopRun : Exception()

    companion object {
        private const val TAG = "PrintRunner"
        /** Images prepared ahead of the printer. */
        const val PREPARE_AHEAD = 3
        /** Reconnects after the link drops mid-run before giving up. */
        const val MAX_RECONNECTS = 3
        /** Results go to the server every few items, and at the end. */
        const val REPORT_EVERY = 10
        private const val KEEP_MS = 30L * 24 * 60 * 60 * 1000

        /** A reason the seller can act on. */
        fun friendly(e: Throwable): String = when (e) {
            is PrintException -> when (e.failure) {
                PrintFailure.NOT_CONFIGURED -> "No printer set up for this"
                PrintFailure.BLUETOOTH_UNAVAILABLE -> "This phone has no Bluetooth"
                PrintFailure.BLUETOOTH_OFF -> "Bluetooth is off"
                PrintFailure.NO_PERMISSION -> "Bluetooth permission is missing"
                PrintFailure.CONNECT_FAILED -> "Printer not reachable: is it on and nearby?"
                PrintFailure.TIMEOUT -> "The printer didn't respond: is it on and nearby?"
                PrintFailure.WRITE_FAILED -> "The printer disconnected while printing"
                PrintFailure.BAD_IMAGE -> "Couldn't prepare this for the printer"
            }
            is ApiException -> when {
                e.httpCode == null -> "No internet: couldn't load it"
                e.notFound -> e.message ?: "Not found"
                e.unauthorized -> "This phone is no longer paired"
                else -> e.message ?: "The server couldn't prepare it"
            }
            else -> e.message ?: "Couldn't print this"
        }
    }
}
