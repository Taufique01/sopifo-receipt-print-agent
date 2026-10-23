package com.sopifo.printagent.runs

import com.sopifo.printagent.data.api.PrintResultsRequest
import com.sopifo.printagent.data.db.PrintRunDao
import com.sopifo.printagent.data.db.PrintRunEntity
import com.sopifo.printagent.data.db.PrintRunItemEntity
import com.sopifo.printagent.data.db.PrinterConfigEntity
import com.sopifo.printagent.data.db.PrinterProtocol
import com.sopifo.printagent.data.db.PrinterRole
import com.sopifo.printagent.data.db.RunItemStatus
import com.sopifo.printagent.data.db.RunState
import com.sopifo.printagent.print.PrintException
import com.sopifo.printagent.print.PrintFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory stand-in for Room. */
class FakeRunDao : PrintRunDao {
    val runs = linkedMapOf<String, PrintRunEntity>()
    val items = mutableListOf<PrintRunItemEntity>()
    private val tick = MutableStateFlow(0)

    override suspend fun insertRun(run: PrintRunEntity) { runs[run.id] = run; tick.value++ }
    override suspend fun insertItems(items: List<PrintRunItemEntity>) { this.items += items; tick.value++ }
    override suspend fun getRun(id: String) = runs[id]
    override fun observeRun(id: String): Flow<PrintRunEntity?> = tick.map { runs[id] }
    override fun observeRuns(): Flow<List<PrintRunEntity>> = tick.map { runs.values.toList() }
    override suspend fun runsIn(state: RunState) = runs.values.filter { it.state == state }
    override suspend fun items(runId: String) = items.filter { it.runId == runId }.sortedBy { it.idx }
    override fun observeItems(runId: String): Flow<List<PrintRunItemEntity>> = tick.map { items.filter { it.runId == runId } }
    override suspend fun setItem(runId: String, idx: Int, status: RunItemStatus, reason: String?, at: Long) {
        val i = items.indexOfFirst { it.runId == runId && it.idx == idx }
        items[i] = items[i].copy(status = status, reason = reason, updatedAt = at)
        tick.value++
    }
    override suspend fun requeueUnsuccessful(runId: String, at: Long): Int {
        var n = 0
        items.replaceAll {
            if (it.runId == runId && it.status in setOf(RunItemStatus.FAILED, RunItemStatus.INCOMPLETE, RunItemStatus.NOT_PRINTED)) {
                n++
                it.copy(status = RunItemStatus.PENDING, reason = null, attempt = it.attempt + 1, reported = false, updatedAt = at)
            } else it
        }
        return n
    }
    override suspend fun setRun(id: String, state: RunState, printed: Int, failed: Int, message: String?, at: Long) {
        runs[id] = runs.getValue(id).copy(state = state, printed = printed, failed = failed, message = message, updatedAt = at)
        tick.value++
    }
    override suspend fun unreported() = items.filter {
        !it.reported && it.kind in setOf("order", "parcel") &&
            it.status in setOf(RunItemStatus.PRINTED, RunItemStatus.FAILED, RunItemStatus.INCOMPLETE, RunItemStatus.NOT_PRINTED)
    }
    override suspend fun markReported(runId: String, idx: List<Int>) {
        items.replaceAll { if (it.runId == runId && it.idx in idx) it.copy(reported = true) else it }
    }
    override suspend fun deleteRunsBefore(before: Long) { runs.values.removeAll { it.createdAt < before && it.state != RunState.RUNNING } }
    override suspend fun deleteOrphanItems() { items.removeAll { it.runId !in runs } }

    fun status(runId: String) = items.filter { it.runId == runId }.sortedBy { it.idx }.map { it.status }
}

/** A printer that can be unreachable, or drop the link on chosen writes. */
class FakePrinter : RunPrinterPort {
    val printer = PrinterConfigEntity(PrinterRole.LABEL, "XP", "00:11:22:33:44:55", PrinterProtocol.TSPL, 384)
    var configured = true
    var unreachable = false
    /** Write numbers (1-based, across the whole test) that fail mid-send. */
    val dropOnWrite = mutableSetOf<Int>()
    var dropAlways = false
    val written = mutableListOf<String>()
    var sessions = 0
    private var writes = 0

    override suspend fun config(role: PrinterRole) = if (configured) printer else null

    override suspend fun <T> session(printer: PrinterConfigEntity, block: suspend (write: suspend (ByteArray) -> Unit) -> T): T {
        sessions++
        if (unreachable) throw PrintException(PrintFailure.CONNECT_FAILED, "Printer not reachable")
        return block { bytes ->
            writes++
            if (dropAlways || writes in dropOnWrite) throw PrintException(PrintFailure.WRITE_FAILED, "Write failed")
            written += String(bytes)
        }
    }
}

class FakeContent : RunContent {
    val failing = mutableSetOf<String>()
    override suspend fun prepare(item: PrintRunItemEntity, printer: PrinterConfigEntity): ByteArray {
        if (item.refId in failing) throw IllegalStateException("Order not found")
        return item.refId.toByteArray()
    }
}

class PrintRunnerTest {
    private val dao = FakeRunDao()
    private val printer = FakePrinter()
    private val content = FakeContent()
    private var reports = 0

    private fun TestScope.runner() = PrintRunner(
        dao, printer, content, this,
        onResults = { reports++ },
        clock = { testScheduler.currentTime },
        reconnectDelayMs = 10,
    )

    private fun items(vararg ids: String) = ids.map { NewRunItem("order", it, "label", "#$it") }

    @Test
    fun printsEveryItemInOrderOverOneConnection() = runTest {
        val r = runner()
        val id = r.start("Order labels", "orders", PrinterRole.LABEL, items("a", "b", "c"))
        advanceUntilIdle()
        assertEquals(listOf("a", "b", "c"), printer.written)
        assertEquals(1, printer.sessions)
        assertEquals(RunState.DONE, dao.runs[id]!!.state)
        assertEquals(3, dao.runs[id]!!.printed)
        assertTrue(reports > 0)
    }

    @Test
    fun anItemThatCannotLoadFailsAndTheRestStillPrint() = runTest {
        content.failing += "b"
        val id = runner().start("Order labels", "orders", PrinterRole.LABEL, items("a", "b", "c"))
        advanceUntilIdle()
        assertEquals(listOf("a", "c"), printer.written)
        assertEquals(listOf(RunItemStatus.PRINTED, RunItemStatus.FAILED, RunItemStatus.PRINTED), dao.status(id))
        assertEquals("Order not found", dao.items(id)[1].reason)
        assertEquals(RunState.DONE, dao.runs[id]!!.state)
        assertEquals(1, dao.runs[id]!!.failed)
    }

    @Test
    fun aDroppedLinkMarksThatItemIncompleteReconnectsAndNeverReprintsIt() = runTest {
        printer.dropOnWrite += 2
        val id = runner().start("Order labels", "orders", PrinterRole.LABEL, items("a", "b", "c", "d"))
        advanceUntilIdle()
        assertEquals(listOf("a", "c", "d"), printer.written)
        assertEquals(2, printer.sessions)
        assertEquals(
            listOf(RunItemStatus.PRINTED, RunItemStatus.INCOMPLETE, RunItemStatus.PRINTED, RunItemStatus.PRINTED),
            dao.status(id),
        )
        assertEquals(RunState.DONE, dao.runs[id]!!.state)
    }

    @Test
    fun anUnreachablePrinterLeavesEverythingNotPrintedWithAReason() = runTest {
        printer.unreachable = true
        val id = runner().start("Order labels", "orders", PrinterRole.LABEL, items("a", "b"))
        advanceUntilIdle()
        assertTrue(printer.written.isEmpty())
        assertEquals(listOf(RunItemStatus.NOT_PRINTED, RunItemStatus.NOT_PRINTED), dao.status(id))
        assertEquals(RunState.STOPPED, dao.runs[id]!!.state)
        assertEquals("Printer not reachable: is it on and nearby?", dao.runs[id]!!.message)
    }

    @Test
    fun noPrinterSetUpStopsWithoutSending() = runTest {
        printer.configured = false
        val id = runner().start("Order invoices", "orders", PrinterRole.RECEIPT, items("a"))
        advanceUntilIdle()
        assertEquals(0, printer.sessions)
        assertEquals(RunItemStatus.NOT_PRINTED, dao.status(id).single())
        assertEquals(RunState.STOPPED, dao.runs[id]!!.state)
    }

    @Test
    fun givesUpAfterRepeatedDropsInsteadOfLoopingForever() = runTest {
        printer.dropAlways = true
        val id = runner().start("Order labels", "orders", PrinterRole.LABEL, items("a", "b", "c", "d", "e", "f"))
        advanceUntilIdle()
        assertEquals(PrintRunner.MAX_RECONNECTS + 1, printer.sessions)
        val statuses = dao.status(id)
        assertEquals(PrintRunner.MAX_RECONNECTS + 1, statuses.count { it == RunItemStatus.INCOMPLETE })
        assertTrue(statuses.drop(PrintRunner.MAX_RECONNECTS + 1).all { it == RunItemStatus.NOT_PRINTED })
        assertEquals(RunState.STOPPED, dao.runs[id]!!.state)
    }

    @Test
    fun retryPrintsOnlyTheUnsuccessfulItemsAsANewAttempt() = runTest {
        content.failing += "b"
        val r = runner()
        val id = r.start("Order labels", "orders", PrinterRole.LABEL, items("a", "b", "c"))
        advanceUntilIdle()
        content.failing.clear()
        r.retry(id)
        advanceUntilIdle()
        assertEquals(listOf("a", "c", "b"), printer.written)
        assertEquals(List(3) { RunItemStatus.PRINTED }, dao.status(id))
        assertEquals(2, dao.items(id)[1].attempt)
        assertEquals(1, dao.items(id)[0].attempt)
    }

    @Test
    fun stopLeavesTheRestNotPrinted() = runTest {
        val r = runner()
        val id = r.start("Order labels", "orders", PrinterRole.LABEL, items("a", "b", "c"))
        r.stop(id)
        advanceUntilIdle()
        assertTrue(printer.written.isEmpty())
        assertTrue(dao.status(id).all { it == RunItemStatus.NOT_PRINTED })
        assertEquals("Stopped", dao.runs[id]!!.message)
    }

    @Test
    fun afterACrashTheItemBeingSentIsIncompleteAndTheRunWaitsForTheSeller() = runTest {
        val id = "crashed"
        dao.insertRun(PrintRunEntity(id, "Order labels", "orders", PrinterRole.LABEL, RunState.RUNNING, 3, createdAt = 0, updatedAt = 0))
        dao.insertItems(
            listOf(RunItemStatus.PRINTED, RunItemStatus.SENDING, RunItemStatus.PENDING).mapIndexed { i, s ->
                PrintRunItemEntity(id, i, "order", "o$i", "label", "#$i", s, updatedAt = 0)
            },
        )
        val r = runner()
        r.recoverInterrupted()
        assertEquals(RunState.INTERRUPTED, dao.runs[id]!!.state)
        assertEquals(listOf(RunItemStatus.PRINTED, RunItemStatus.INCOMPLETE, RunItemStatus.PENDING), dao.status(id))
        r.resume(id)
        advanceUntilIdle()
        // Only the one never sent prints; the part-printed one waits for a retry.
        assertEquals(listOf("o2"), printer.written)
        assertEquals(RunState.DONE, dao.runs[id]!!.state)
    }

    @Test
    fun reporterSendsEachRunsResultsOnceAndKeepsThemWhenOffline() = runTest {
        content.failing += "b"
        val id = runner().start("Order labels", "orders", PrinterRole.LABEL, items("a", "b"))
        advanceUntilIdle()

        var online = false
        val sent = mutableListOf<PrintResultsRequest>()
        val reporter = RunReporter(dao) { if (!online) error("offline") else sent += it }
        assertFalse(reporter.reportPending())
        assertTrue(dao.unreported().isNotEmpty())

        online = true
        assertTrue(reporter.reportPending())
        assertEquals(1, sent.size)
        assertEquals(id, sent[0].run)
        assertEquals(listOf("printed", "failed"), sent[0].items.map { it.status })
        assertEquals("Order not found", sent[0].items[1].reason)
        assertTrue(reporter.reportPending())
        assertEquals(1, sent.size)
    }

    @Test
    fun aRunQueuedBehindAnotherIsNotMistakenForAnInterruptedOne() = runTest {
        val r = runner()
        val first = r.start("Order labels", "orders", PrinterRole.LABEL, items("a"))
        val second = r.start("Order invoices", "orders", PrinterRole.LABEL, items("b"))
        // Both recorded as running; neither has printed yet. Recovery must leave them alone.
        r.recoverInterrupted()
        advanceUntilIdle()
        assertEquals(RunState.DONE, dao.runs[first]!!.state)
        assertEquals(RunState.DONE, dao.runs[second]!!.state)
        assertEquals(listOf("a", "b"), printer.written)
    }
}
